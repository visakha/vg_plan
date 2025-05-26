package org.vgplan.plan;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.vgplan.plan.KanbanProjectManager.HierarchyType;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

/**
 * Utility class for database operations related to the project hierarchy.
 */
public class DatabaseUtil {
    /**
     * Loads the project hierarchy tree from the database.
     * 
     * @param rootItem the root tree item to populate
     */
    public void loadHierarchyTree(TreeItem<HierarchyNode> rootItem) {
        rootItem.getChildren().clear();
        try (Connection conn = KanbanProjectManager.dataSource.getConnection()) {
            String phaseSql = "SELECT phase_id, phase_name, skill_sets FROM project_phases ORDER BY phase_name";
            try (PreparedStatement psPhase = conn.prepareStatement(phaseSql);
                    ResultSet rsPhase = psPhase.executeQuery()) {
                while (rsPhase.next()) {
                    int phaseId = rsPhase.getInt("phase_id");
                    String phaseName = rsPhase.getString("phase_name");
                    String skillSets = rsPhase.getString("skill_sets");
                    TreeItem<HierarchyNode> phaseItem = new TreeItem<>(
                            new HierarchyNode(HierarchyType.PHASE, phaseId, "Phase: " + phaseName, skillSets));
                    String epicSql = "SELECT epic_id, epic_name FROM epics WHERE phase_id = ? ORDER BY epic_name";
                    try (PreparedStatement psEpic = conn.prepareStatement(epicSql)) {
                        psEpic.setInt(1, phaseId);
                        try (ResultSet rsEpic = psEpic.executeQuery()) {
                            while (rsEpic.next()) {
                                int epicId = rsEpic.getInt("epic_id");
                                String epicName = rsEpic.getString("epic_name");
                                TreeItem<HierarchyNode> epicItem = new TreeItem<>(
                                        new HierarchyNode(HierarchyType.EPIC, epicId, "Epic: " + epicName));
                                String taskSql = "SELECT id, title FROM tasks WHERE epic_id = ? ORDER BY title";
                                try (PreparedStatement psTask = conn.prepareStatement(taskSql)) {
                                    psTask.setInt(1, epicId);
                                    try (ResultSet rsTask = psTask.executeQuery()) {
                                        while (rsTask.next()) {
                                            int taskId = rsTask.getInt("id");
                                            String taskTitle = rsTask.getString("title");
                                            TreeItem<HierarchyNode> taskItem = new TreeItem<>(new HierarchyNode(
                                                    HierarchyType.TASK, taskId, "Task: " + taskTitle));
                                            String subSql = "SELECT subtask_id, subtask_name FROM subtasks WHERE task_id = ? ORDER BY subtask_name";
                                            try (PreparedStatement psSub = conn.prepareStatement(subSql)) {
                                                psSub.setInt(1, taskId);
                                                try (ResultSet rsSub = psSub.executeQuery()) {
                                                    while (rsSub.next()) {
                                                        int subId = rsSub.getInt("subtask_id");
                                                        String subName = rsSub.getString("subtask_name");
                                                        TreeItem<HierarchyNode> subItem = new TreeItem<>(
                                                                new HierarchyNode(HierarchyType.SUBTASK, subId,
                                                                        "Sub-Task: " + subName));
                                                        taskItem.getChildren().add(subItem);
                                                    }
                                                }
                                            }
                                            epicItem.getChildren().add(taskItem);
                                        }
                                    }
                                }
                                phaseItem.getChildren().add(epicItem);
                            }
                        }
                    }
                    rootItem.getChildren().add(phaseItem);
                }
            }
        } catch (SQLException e) {
            rootItem.getChildren().clear();
            rootItem.getChildren()
                    .add(new TreeItem<>(new HierarchyNode(HierarchyType.ROOT, null, "Error: " + e.getMessage())));
        }
    }

    /**
     * Deletes a node from the hierarchy in the database.
     * 
     * @param node     the node to delete
     * @param treeView the tree view (for reload)
     */
    public void deleteHierarchyNode(TreeItem<HierarchyNode> node, TreeView<HierarchyNode> treeView) {
        HierarchyNode n = node.getValue();
        try (Connection conn = KanbanProjectManager.dataSource.getConnection()) {
            if (n.type == HierarchyType.PHASE) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM project_phases WHERE phase_id = ?")) {
                    ps.setInt(1, n.id);
                    ps.executeUpdate();
                }
            } else if (n.type == HierarchyType.EPIC) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM epics WHERE epic_id = ?")) {
                    ps.setInt(1, n.id);
                    ps.executeUpdate();
                }
            } else if (n.type == HierarchyType.TASK) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM tasks WHERE id = ?")) {
                    ps.setInt(1, n.id);
                    ps.executeUpdate();
                }
            } else if (n.type == HierarchyType.SUBTASK) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM subtasks WHERE subtask_id = ?")) {
                    ps.setInt(1, n.id);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            // Use a static error dialog method from KanbanProjectManager
            KanbanProjectManager.showErrorDialogStatic("DB Error", e.getMessage());
        }
        loadHierarchyTree((TreeItem<HierarchyNode>) treeView.getRoot());
    }

    /**
     * Handles the result of the CRUD dialog for create or edit.
     */
    public void handleCrudDialogResult(boolean isCreate, HierarchyType targetType, HierarchyNode node, String name,
            String skillSets) {
        try (Connection conn = KanbanProjectManager.dataSource.getConnection()) {
            if (isCreate) {
                if (targetType == HierarchyType.PHASE) {
                    String sql = "INSERT INTO project_phases (phase_name, skill_sets) VALUES (?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, name);
                        ps.setString(2, skillSets);
                        ps.executeUpdate();
                    }
                } else if (targetType == HierarchyType.EPIC) {
                    String sql = "INSERT INTO epics (epic_name, phase_id) VALUES (?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, name);
                        ps.setInt(2, node.id);
                        ps.executeUpdate();
                    }
                } else if (targetType == HierarchyType.TASK) {
                    String sql = "INSERT INTO tasks (title, epic_id, status) VALUES (?, ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, name);
                        ps.setInt(2, node.id);
                        ps.setString(3, KanbanProjectManager.STATUS_LIST.get(0));
                        ps.executeUpdate();
                    }
                } else if (targetType == HierarchyType.SUBTASK) {
                    String sql = "INSERT INTO subtasks (subtask_name, task_id) VALUES (?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, name);
                        ps.setInt(2, node.id);
                        ps.executeUpdate();
                    }
                }
            } else {
                if (targetType == HierarchyType.PHASE) {
                    String sql = "UPDATE project_phases SET phase_name = ?, skill_sets = ? WHERE phase_id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, name);
                        ps.setString(2, skillSets);
                        ps.setInt(3, node.id);
                        ps.executeUpdate();
                    }
                } else if (targetType == HierarchyType.EPIC) {
                    String sql = "UPDATE epics SET epic_name = ? WHERE epic_id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, name);
                        ps.setInt(2, node.id);
                        ps.executeUpdate();
                    }
                } else if (targetType == HierarchyType.TASK) {
                    String sql = "UPDATE tasks SET title = ? WHERE id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, name);
                        ps.setInt(2, node.id);
                        ps.executeUpdate();
                    }
                } else if (targetType == HierarchyType.SUBTASK) {
                    String sql = "UPDATE subtasks SET subtask_name = ? WHERE subtask_id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, name);
                        ps.setInt(2, node.id);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            KanbanProjectManager.showErrorDialogStatic("DB Error", e.getMessage());
        }
    }
}