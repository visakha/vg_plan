package org.vgplan.vg_plan;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;

import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class KanbanProjectManager extends Application {

    private static final String DB_URL = "jdbc:sqlite:project_kanban.db";
    private static HikariDataSource dataSource;

    private BorderPane rootPane;
    private HBox columnsContainer;
    private ObservableList<KanbanColumn> columns;

    private static final List<String> TEAM_MEMBERS = Arrays.asList("SSA1", "SA2", "India PM", "Dev1", "Dev2", "Dev3", "Dev4", "Dev5", "Dev6", "Unassigned");
    private static final List<String> MODULES = Arrays.asList("Ingress", "Egress", "MDM Customization", "Planning", "General");
    private static final List<String> PRIORITIES = Arrays.asList("High", "Medium", "Low");
    private static final List<String> STATUS_LIST = Arrays.asList("To Do", "In Progress", "Blocked", "In Review", "Done");

    // For keyboard navigation
    private int focusedColumnIndex = 0;
    private int focusedTaskIndexInColumn = -1; // -1 means column itself is focused, not a task

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        setupDataSource();
        setupDatabase();

        rootPane = new BorderPane();
        columnsContainer = new HBox(10);
        columnsContainer.setPadding(new Insets(10));
        columnsContainer.setAlignment(Pos.CENTER);

        columns = FXCollections.observableArrayList();
        for (String status : STATUS_LIST) {
            columns.add(new KanbanColumn(status));
        }

        columnsContainer.getChildren().addAll(columns);

        ScrollPane mainScrollPane = new ScrollPane(columnsContainer);
        mainScrollPane.setFitToHeight(true);
        mainScrollPane.setFitToWidth(true); // Allows horizontal scrolling if columns overflow
        mainScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        mainScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); // Vertical scroll within columns
        rootPane.setCenter(mainScrollPane);


        // Menu Bar
        MenuBar menuBar = createMenuBar(primaryStage);
        rootPane.setTop(menuBar);

        // Load tasks from DB
        loadTasksFromDB();

        Scene scene = new Scene(rootPane, 1200, 800);
        // Ensure styles.css is in the correct location (e.g., src/main/resources if using Maven/Gradle)
        // For simple setups, it can be in the same directory as the .java file, but classpath might need adjustment.
        try {
            String cssPath = getClass().getResource("/styles.css").toExternalForm();
            scene.getStylesheets().add(cssPath);
        } catch (NullPointerException e) {
            System.err.println("Warning: styles.css not found. Ensure it's in the classpath (e.g., src/main/resources folder or same directory and classpath is set).");
            // Add basic inline styles as a fallback or instruct user
        }


        setupKeyboardNavigation(scene);
        updateColumnFocus(); // Initial focus

        primaryStage.setTitle("Project Kanban Board - ETL & MDM (July 1st Start)");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void setupDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(DB_URL);
        // The driver class name is usually not needed for modern JDBC drivers if the JAR is in the classpath.
        // config.setDriverClassName("org.sqlite.JDBC");
        config.setUsername("");
        config.setPassword("");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(config);
    }


    private void setupDatabase() {
        String createProjectPhasesTableSQL = "CREATE TABLE IF NOT EXISTS project_phases (" +
                "phase_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "phase_name TEXT NOT NULL UNIQUE," +
                "skill_sets TEXT" +
                ");";
        String createEpicsTableSQL = "CREATE TABLE IF NOT EXISTS epics (" +
                "epic_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "epic_name TEXT NOT NULL," +
                "phase_id INTEGER NOT NULL," +
                "FOREIGN KEY (phase_id) REFERENCES project_phases(phase_id)" +
                ");";
        String createTaskTableSQL = "CREATE TABLE IF NOT EXISTS tasks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "description TEXT," +
                "assignee TEXT," +
                "module TEXT," +
                "status TEXT NOT NULL," +
                "priority TEXT," +
                "due_date TEXT," +
                "epic_id INTEGER," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "FOREIGN KEY (epic_id) REFERENCES epics(epic_id)" +
                ");";
        String createSubTaskTableSQL = "CREATE TABLE IF NOT EXISTS subtasks (" +
                "subtask_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "subtask_name TEXT NOT NULL," +
                "task_id INTEGER NOT NULL," +
                "FOREIGN KEY (task_id) REFERENCES tasks(id)" +
                ");";
        String createRaciActivitiesTableSQL = "CREATE TABLE IF NOT EXISTS raci_activities (" +
                "activity_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "activity_name TEXT NOT NULL UNIQUE" +
                ");";
        String createTeamMembersTableSQL = "CREATE TABLE IF NOT EXISTS team_members (" +
                "member_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "member_name TEXT NOT NULL UNIQUE" +
                ");";
        String createRaciAssignmentsTableSQL = "CREATE TABLE IF NOT EXISTS raci_assignments (" +
                "assignment_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "activity_id INTEGER NOT NULL," +
                "member_id INTEGER NOT NULL," +
                "raci_role TEXT NOT NULL," +
                "FOREIGN KEY (activity_id) REFERENCES raci_activities(activity_id)," +
                "FOREIGN KEY (member_id) REFERENCES team_members(member_id)," +
                "UNIQUE (activity_id, member_id)" +
                ");";


        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createProjectPhasesTableSQL);
            stmt.execute(createEpicsTableSQL);
            stmt.execute(createTaskTableSQL);
            stmt.execute(createSubTaskTableSQL);
            stmt.execute(createRaciActivitiesTableSQL);
            stmt.execute(createTeamMembersTableSQL);
            stmt.execute(createRaciAssignmentsTableSQL);
            System.out.println("Database tables checked/created successfully.");

            // Pre-populate team members if table is empty
            for (String memberName : TEAM_MEMBERS) {
                try (PreparedStatement psCheck = conn.prepareStatement("SELECT COUNT(*) FROM team_members WHERE member_name = ?");
                     PreparedStatement psInsert = conn.prepareStatement("INSERT INTO team_members (member_name) VALUES (?)")) {
                    psCheck.setString(1, memberName);
                    ResultSet rs = psCheck.executeQuery();
                    if (rs.next() && rs.getInt(1) == 0) {
                        psInsert.setString(1, memberName);
                        psInsert.executeUpdate();
                    }
                }
            }

        } catch (SQLException e) {
            System.err.println("Error creating database tables: " + e.getMessage());
        }
    }

    private MenuBar createMenuBar(Stage primaryStage) {
        MenuBar menuBar = new MenuBar();

        Menu fileMenu = new Menu("File");
        MenuItem newTaskItem = new MenuItem("New Task...");
        newTaskItem.setOnAction(e -> showTaskDialog(null, primaryStage));
        newTaskItem.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN));

        MenuItem projectHierarchyItem = new MenuItem("Project Hierarchy...");
        projectHierarchyItem.setOnAction(e -> showProjectHierarchyDialog(primaryStage));

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setOnAction(e -> primaryStage.close());
        fileMenu.getItems().addAll(newTaskItem, projectHierarchyItem, new SeparatorMenuItem(), exitItem);

        menuBar.getMenus().addAll(fileMenu);
        return menuBar;
    }

    // --- Project Hierarchy Dialog ---
    // Update: Ctrl+N always creates a child, double-click edits
    private void showProjectHierarchyDialog(Stage ownerStage) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(ownerStage);
        dialog.setTitle("Project Phase → Epic → Task → Sub-Task Hierarchy");

        TreeItem<HierarchyNode> rootItem = new TreeItem<>(new HierarchyNode(HierarchyType.ROOT, null, "All Project Phases"));
        rootItem.setExpanded(true);
        loadHierarchyTree(rootItem);

        TreeView<HierarchyNode> treeView = new TreeView<>(rootItem);
        treeView.setShowRoot(true);
        treeView.setMinHeight(500);
        treeView.setMinWidth(500);
        treeView.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(HierarchyNode item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null : item.displayName);
            }
        });

        // --- Double-click to edit ---
        treeView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                TreeItem<HierarchyNode> selected = treeView.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getValue().type != HierarchyType.ROOT) {
                    showHierarchyCrudDialog(selected, treeView, false); // edit mode
                }
            }
        });

        // Keyboard navigation and CRUD
        treeView.setOnKeyPressed(event -> {
            TreeItem<HierarchyNode> selected = treeView.getSelectionModel().getSelectedItem();
            if (event.getCode() == KeyCode.N && event.isControlDown()) {
                if (selected != null && selected.getValue().type != HierarchyType.ROOT) {
                    showHierarchyCrudDialog(selected, treeView, false); // edit mode
                } else {
                    // Create new root phase
                    showHierarchyCrudDialog(rootItem, treeView, true); // create mode
                }
                event.consume();
            } else if (event.getCode() == KeyCode.DELETE && selected != null && selected.getValue().type != HierarchyType.ROOT) {
                deleteHierarchyNode(selected, treeView);
                event.consume();
            }
        });

        VBox vbox = new VBox(10, treeView);
        vbox.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(vbox);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    // --- CRUD Dialog (Create or Edit) ---
    private void showHierarchyCrudDialog(TreeItem<HierarchyNode> nodeItem, TreeView<HierarchyNode> treeView, boolean isCreate) {
        HierarchyNode node = nodeItem.getValue();
        Dialog<Void> dialog = new Dialog<>();
        dialog.initModality(Modality.APPLICATION_MODAL);
        String dialogTitle;
        HierarchyType targetType;
        if (isCreate) {
            // Determine what child type to create
            if (node.type == HierarchyType.ROOT) {
                dialogTitle = "Create Phase";
                targetType = HierarchyType.PHASE;
            } else if (node.type == HierarchyType.PHASE) {
                dialogTitle = "Create Epic";
                targetType = HierarchyType.EPIC;
            } else if (node.type == HierarchyType.EPIC) {
                dialogTitle = "Create Task";
                targetType = HierarchyType.TASK;
            } else if (node.type == HierarchyType.TASK) {
                dialogTitle = "Create Sub-Task";
                targetType = HierarchyType.SUBTASK;
            } else {
                return;
            }
        } else {
            dialogTitle = "Edit " + node.type;
            targetType = node.type;
        }
        dialog.setTitle(dialogTitle);
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10); grid.setPadding(new Insets(20, 20, 10, 10));
        TextField nameField = new TextField();
        TextField skillSetsField = new TextField();
        if (targetType == HierarchyType.PHASE) {
            grid.add(new Label("Phase Name:"), 0, 0); grid.add(nameField, 1, 0);
            grid.add(new Label("Skill Sets Needed:"), 0, 1); grid.add(skillSetsField, 1, 1);
            if (!isCreate && node.type == HierarchyType.PHASE) {
                nameField.setText(node.displayName.replaceFirst("^Phase: ", ""));
                skillSetsField.setText(node.skillSets != null ? node.skillSets : "");
            }
        } else if (targetType == HierarchyType.EPIC) {
            grid.add(new Label("Epic Name:"), 0, 0); grid.add(nameField, 1, 0);
            if (!isCreate && node.type == HierarchyType.EPIC) {
                nameField.setText(node.displayName.replaceFirst("^Epic: ", ""));
            }
        } else if (targetType == HierarchyType.TASK) {
            grid.add(new Label("Task Name:"), 0, 0); grid.add(nameField, 1, 0);
            if (!isCreate && node.type == HierarchyType.TASK) {
                nameField.setText(node.displayName.replaceFirst("^Task: ", ""));
            }
        } else if (targetType == HierarchyType.SUBTASK) {
            grid.add(new Label("Sub-Task Name:"), 0, 0); grid.add(nameField, 1, 0);
            if (!isCreate && node.type == HierarchyType.SUBTASK) {
                nameField.setText(node.displayName.replaceFirst("^Sub-Task: ", ""));
            }
        }
        ButtonType saveBtn = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(grid);
        dialog.setResultConverter(btn -> btn == saveBtn ? null : null);
        Optional<Void> result = dialog.showAndWait();
        if (true) {
            try (Connection conn = dataSource.getConnection()) {
                if (isCreate) {
                    if (targetType == HierarchyType.PHASE) {
                        // Create Phase
                        String sql = "INSERT INTO project_phases (phase_name, skill_sets) VALUES (?, ?)";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            ps.setString(1, nameField.getText());
                            ps.setString(2, skillSetsField.getText());
                            ps.executeUpdate();
                        }
                    } else if (targetType == HierarchyType.EPIC) {
                        // Create Epic under selected Phase
                        String sql = "INSERT INTO epics (epic_name, phase_id) VALUES (?, ?)";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            ps.setString(1, nameField.getText());
                            ps.setInt(2, node.id); // parent phase id
                            ps.executeUpdate();
                        }
                    } else if (targetType == HierarchyType.TASK) {
                        // Create Task under selected Epic
                        String sql = "INSERT INTO tasks (title, epic_id, status) VALUES (?, ?, ?)";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            ps.setString(1, nameField.getText());
                            ps.setInt(2, node.id); // parent epic id
                            ps.setString(3, STATUS_LIST.get(0)); // default status
                            ps.executeUpdate();
                        }
                    } else if (targetType == HierarchyType.SUBTASK) {
                        // Create Sub-Task under selected Task
                        String sql = "INSERT INTO subtasks (subtask_name, task_id) VALUES (?, ?)";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            ps.setString(1, nameField.getText());
                            ps.setInt(2, node.id); // parent task id
                            ps.executeUpdate();
                        }
                    }
                } else {
                    // Edit selected node
                    if (node.type == HierarchyType.PHASE) {
                        String sql = "UPDATE project_phases SET phase_name = ?, skill_sets = ? WHERE phase_id = ?";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            ps.setString(1, nameField.getText());
                            ps.setString(2, skillSetsField.getText());
                            ps.setInt(3, node.id);
                            ps.executeUpdate();
                        }
                    } else if (node.type == HierarchyType.EPIC) {
                        String sql = "UPDATE epics SET epic_name = ? WHERE epic_id = ?";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            ps.setString(1, nameField.getText());
                            ps.setInt(2, node.id);
                            ps.executeUpdate();
                        }
                    } else if (node.type == HierarchyType.TASK) {
                        String sql = "UPDATE tasks SET title = ? WHERE id = ?";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            ps.setString(1, nameField.getText());
                            ps.setInt(2, node.id);
                            ps.executeUpdate();
                        }
                    } else if (node.type == HierarchyType.SUBTASK) {
                        String sql = "UPDATE subtasks SET subtask_name = ? WHERE subtask_id = ?";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            ps.setString(1, nameField.getText());
                            ps.setInt(2, node.id);
                            ps.executeUpdate();
                        }
                    }
                }
            } catch (SQLException e) {
                showErrorDialog("DB Error", e.getMessage());
            }
            loadHierarchyTree((TreeItem<HierarchyNode>) treeView.getRoot());
        }
    }

    // --- Hierarchy Types and Node ---
    enum HierarchyType { ROOT, PHASE, EPIC, TASK, SUBTASK }
    static class HierarchyNode {
        HierarchyType type;
        Integer id; // DB id
        String displayName;
        String skillSets; // Only for phase
        HierarchyNode(HierarchyType type, Integer id, String displayName) {
            this.type = type; this.id = id; this.displayName = displayName;
        }
        HierarchyNode(HierarchyType type, Integer id, String displayName, String skillSets) {
            this(type, id, displayName); this.skillSets = skillSets;
        }
        @Override public String toString() { return displayName; }
    }

    // --- Load Hierarchy Tree ---
    private void loadHierarchyTree(TreeItem<HierarchyNode> rootItem) {
        rootItem.getChildren().clear();
        try (Connection conn = dataSource.getConnection()) {
            // PHASES
            String phaseSql = "SELECT phase_id, phase_name, skill_sets FROM project_phases ORDER BY phase_name";
            try (PreparedStatement psPhase = conn.prepareStatement(phaseSql);
                 ResultSet rsPhase = psPhase.executeQuery()) {
                while (rsPhase.next()) {
                    int phaseId = rsPhase.getInt("phase_id");
                    String phaseName = rsPhase.getString("phase_name");
                    String skillSets = rsPhase.getString("skill_sets");
                    TreeItem<HierarchyNode> phaseItem = new TreeItem<>(new HierarchyNode(HierarchyType.PHASE, phaseId, "Phase: " + phaseName, skillSets));
                    // EPICS
                    String epicSql = "SELECT epic_id, epic_name FROM epics WHERE phase_id = ? ORDER BY epic_name";
                    try (PreparedStatement psEpic = conn.prepareStatement(epicSql)) {
                        psEpic.setInt(1, phaseId);
                        try (ResultSet rsEpic = psEpic.executeQuery()) {
                            while (rsEpic.next()) {
                                int epicId = rsEpic.getInt("epic_id");
                                String epicName = rsEpic.getString("epic_name");
                                TreeItem<HierarchyNode> epicItem = new TreeItem<>(new HierarchyNode(HierarchyType.EPIC, epicId, "Epic: " + epicName));
                                // TASKS
                                String taskSql = "SELECT id, title FROM tasks WHERE epic_id = ? ORDER BY title";
                                try (PreparedStatement psTask = conn.prepareStatement(taskSql)) {
                                    psTask.setInt(1, epicId);
                                    try (ResultSet rsTask = psTask.executeQuery()) {
                                        while (rsTask.next()) {
                                            int taskId = rsTask.getInt("id");
                                            String taskTitle = rsTask.getString("title");
                                            TreeItem<HierarchyNode> taskItem = new TreeItem<>(new HierarchyNode(HierarchyType.TASK, taskId, "Task: " + taskTitle));
                                            // SUBTASKS
                                            String subSql = "SELECT subtask_id, subtask_name FROM subtasks WHERE task_id = ? ORDER BY subtask_name";
                                            try (PreparedStatement psSub = conn.prepareStatement(subSql)) {
                                                psSub.setInt(1, taskId);
                                                try (ResultSet rsSub = psSub.executeQuery()) {
                                                    while (rsSub.next()) {
                                                        int subId = rsSub.getInt("subtask_id");
                                                        String subName = rsSub.getString("subtask_name");
                                                        TreeItem<HierarchyNode> subItem = new TreeItem<>(new HierarchyNode(HierarchyType.SUBTASK, subId, "Sub-Task: " + subName));
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
            rootItem.getChildren().add(new TreeItem<>(new HierarchyNode(HierarchyType.ROOT, null, "Error: " + e.getMessage())));
        }
    }

    // --- Delete Node ---
    private void deleteHierarchyNode(TreeItem<HierarchyNode> node, TreeView<HierarchyNode> treeView) {
        HierarchyNode n = node.getValue();
        try (Connection conn = dataSource.getConnection()) {
            if (n.type == HierarchyType.PHASE) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM project_phases WHERE phase_id = ?")) {
                    ps.setInt(1, n.id); ps.executeUpdate();
                }
            } else if (n.type == HierarchyType.EPIC) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM epics WHERE epic_id = ?")) {
                    ps.setInt(1, n.id); ps.executeUpdate();
                }
            } else if (n.type == HierarchyType.TASK) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM tasks WHERE id = ?")) {
                    ps.setInt(1, n.id); ps.executeUpdate();
                }
            } else if (n.type == HierarchyType.SUBTASK) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM subtasks WHERE subtask_id = ?")) {
                    ps.setInt(1, n.id); ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            showErrorDialog("DB Error", e.getMessage());
        }
        loadHierarchyTree((TreeItem<HierarchyNode>) treeView.getRoot());
    }


    private void loadTasksFromDB() {
        for (KanbanColumn column : columns) {
            column.clearTasks();
        }

        String sql = "SELECT id, title, description, assignee, module, status, priority, due_date FROM tasks";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                Task task = new Task(
                        rs.getInt("id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("assignee"),
                        rs.getString("module"),
                        rs.getString("status"),
                        rs.getString("priority"),
                        rs.getString("due_date") != null ? LocalDate.parse(rs.getString("due_date")) : null
                );
                addTaskToCorrectColumn(task);
            }
        } catch (SQLException e) {
            System.err.println("Error loading tasks from DB: " + e.getMessage());
            showErrorDialog("Database Error", "Could not load tasks from the database.");
        }
    }

    private void addTaskToCorrectColumn(Task task) {
        for (KanbanColumn column : columns) {
            if (column.getStatus().equals(task.getStatus())) {
                column.addTaskCard(task);
                return;
            }
        }
        System.err.println("Task '" + task.getTitle() + "' has unknown status: " + task.getStatus() + ". Adding to 'To Do'.");
        if (!columns.isEmpty()) {
            task.setStatus(columns.get(0).getStatus());
            columns.get(0).addTaskCard(task);
            updateTaskInDB(task);
        }
    }


    private void showTaskDialog(Task existingTask, Stage ownerStage) {
        Dialog<Task> dialog = new Dialog<>();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(ownerStage);
        dialog.setTitle(existingTask == null ? "Create New Task" : "Edit Task");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        TextField titleField = new TextField();
        titleField.setPromptText("Task Title");
        TextArea descriptionArea = new TextArea();
        descriptionArea.setPromptText("Task Description");
        descriptionArea.setPrefRowCount(3);
        ComboBox<String> assigneeCombo = new ComboBox<>(FXCollections.observableArrayList(TEAM_MEMBERS));
        assigneeCombo.setPromptText("Assignee");
        ComboBox<String> moduleCombo = new ComboBox<>(FXCollections.observableArrayList(MODULES));
        moduleCombo.setPromptText("Module");
        ComboBox<String> statusCombo = new ComboBox<>(FXCollections.observableArrayList(STATUS_LIST));
        statusCombo.setPromptText("Status");
        ComboBox<String> priorityCombo = new ComboBox<>(FXCollections.observableArrayList(PRIORITIES));
        priorityCombo.setPromptText("Priority");
        DatePicker dueDatePicker = new DatePicker();
        dueDatePicker.setPromptText("Due Date");

        if (existingTask != null) {
            titleField.setText(existingTask.getTitle());
            descriptionArea.setText(existingTask.getDescription());
            assigneeCombo.setValue(existingTask.getAssignee());
            moduleCombo.setValue(existingTask.getModule());
            statusCombo.setValue(existingTask.getStatus());
            priorityCombo.setValue(existingTask.getPriority());
            dueDatePicker.setValue(existingTask.getDueDate());
        } else {
            statusCombo.setValue(STATUS_LIST.get(0));
        }


        grid.add(new Label("Title:"), 0, 0); grid.add(titleField, 1, 0);
        grid.add(new Label("Description:"), 0, 1); grid.add(descriptionArea, 1, 1);
        grid.add(new Label("Assignee:"), 0, 2); grid.add(assigneeCombo, 1, 2);
        grid.add(new Label("Module:"), 0, 3); grid.add(moduleCombo, 1, 3);
        grid.add(new Label("Status:"), 0, 4); grid.add(statusCombo, 1, 4);
        grid.add(new Label("Priority:"), 0, 5); grid.add(priorityCombo, 1, 5);
        grid.add(new Label("Due Date:"), 0, 6); grid.add(dueDatePicker, 1, 6);

        dialog.getDialogPane().setContent(grid);

        ButtonType saveButtonType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        Node saveButton = dialog.getDialogPane().lookupButton(saveButtonType);
        saveButton.setDisable(true);
        titleField.textProperty().addListener((observable, oldValue, newValue) -> {
            saveButton.setDisable(newValue.trim().isEmpty());
        });
        if (existingTask != null && !existingTask.getTitle().isEmpty()) {
            saveButton.setDisable(false);
        }


        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                Task task = (existingTask == null) ? new Task() : existingTask;
                task.setTitle(titleField.getText());
                task.setDescription(descriptionArea.getText());
                task.setAssignee(assigneeCombo.getValue());
                task.setModule(moduleCombo.getValue());
                task.setStatus(statusCombo.getValue());
                task.setPriority(priorityCombo.getValue());
                task.setDueDate(dueDatePicker.getValue());
                return task;
            }
            return null;
        });

        Optional<Task> result = dialog.showAndWait();
        result.ifPresent(task -> {
            if (existingTask == null) {
                saveTaskToDB(task);
            } else {
                updateTaskInDB(task);
            }
            loadTasksFromDB();
        });
    }

    private void saveTaskToDB(Task task) {
        String sql = "INSERT INTO tasks(title, description, assignee, module, status, priority, due_date) VALUES(?,?,?,?,?,?,?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, task.getTitle());
            pstmt.setString(2, task.getDescription());
            pstmt.setString(3, task.getAssignee());
            pstmt.setString(4, task.getModule());
            pstmt.setString(5, task.getStatus());
            pstmt.setString(6, task.getPriority());
            pstmt.setString(7, task.getDueDate() != null ? task.getDueDate().format(DateTimeFormatter.ISO_LOCAL_DATE) : null);

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        task.setId(generatedKeys.getInt(1));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error saving task to DB: " + e.getMessage());
            showErrorDialog("Database Error", "Could not save the task.");
        }
    }

    private void updateTaskInDB(Task task) {
        String sql = "UPDATE tasks SET title = ?, description = ?, assignee = ?, module = ?, status = ?, priority = ?, due_date = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, task.getTitle());
            pstmt.setString(2, task.getDescription());
            pstmt.setString(3, task.getAssignee());
            pstmt.setString(4, task.getModule());
            pstmt.setString(5, task.getStatus());
            pstmt.setString(6, task.getPriority());
            pstmt.setString(7, task.getDueDate() != null ? task.getDueDate().format(DateTimeFormatter.ISO_LOCAL_DATE) : null);
            pstmt.setInt(8, task.getId());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error updating task in DB: " + e.getMessage());
            showErrorDialog("Database Error", "Could not update the task.");
        }
    }

    private void deleteTaskFromDB(Task task) {
        String sql = "DELETE FROM tasks WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, task.getId());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error deleting task from DB: " + e.getMessage());
            showErrorDialog("Database Error", "Could not delete the task.");
        }
    }

    private void setupKeyboardNavigation(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            KanbanColumn currentFocusedColumn = columns.get(focusedColumnIndex);
            TaskCard currentFocusedTaskCard = (focusedTaskIndexInColumn >= 0 && focusedTaskIndexInColumn < currentFocusedColumn.getTaskCount()) ?
                    currentFocusedColumn.getTaskCard(focusedTaskIndexInColumn) : null;

            if (event.getCode() == KeyCode.RIGHT) {
                if (focusedTaskIndexInColumn == -1) {
                    focusedColumnIndex = (focusedColumnIndex + 1) % columns.size();
                } else if (currentFocusedTaskCard != null) {
                    if (event.isControlDown()) {
                        moveTask(currentFocusedTaskCard.getTask(), 1);
                        event.consume();
                        return;
                    } else {
                        focusedColumnIndex = (focusedColumnIndex + 1) % columns.size();
                        focusedTaskIndexInColumn = -1;
                    }
                }
                updateColumnFocus();
                updateTaskFocus();
            } else if (event.getCode() == KeyCode.LEFT) {
                if (focusedTaskIndexInColumn == -1) {
                    focusedColumnIndex = (focusedColumnIndex - 1 + columns.size()) % columns.size();
                } else if (currentFocusedTaskCard != null) {
                    if (event.isControlDown()) {
                        moveTask(currentFocusedTaskCard.getTask(), -1);
                        event.consume();
                        return;
                    } else {
                        focusedColumnIndex = (focusedColumnIndex - 1 + columns.size()) % columns.size();
                        focusedTaskIndexInColumn = -1;
                    }
                }
                updateColumnFocus();
                updateTaskFocus();
            } else if (event.getCode() == KeyCode.DOWN) {
                if (currentFocusedColumn.getTaskCount() > 0) {
                    if (focusedTaskIndexInColumn < currentFocusedColumn.getTaskCount() - 1) {
                        focusedTaskIndexInColumn++;
                    } else {
                        focusedTaskIndexInColumn = 0;
                    }
                } else {
                    focusedTaskIndexInColumn = -1;
                }
                updateTaskFocus();
            } else if (event.getCode() == KeyCode.UP) {
                if (currentFocusedColumn.getTaskCount() > 0) {
                    if (focusedTaskIndexInColumn > 0) {
                        focusedTaskIndexInColumn--;
                    } else {
                        focusedTaskIndexInColumn = -1;
                    }
                } else {
                    focusedTaskIndexInColumn = -1;
                }
                updateTaskFocus();
            } else if (event.getCode() == KeyCode.ENTER) {
                if (currentFocusedTaskCard != null) {
                    showTaskDialog(currentFocusedTaskCard.getTask(), (Stage) scene.getWindow());
                } else if (focusedTaskIndexInColumn == -1 && currentFocusedColumn != null) {
                    // Potentially add a new task to the focused column if no task is selected
                    // showTaskDialog(null, (Stage) scene.getWindow());
                    // For now, Enter on column does nothing, but could be an enhancement
                }
            } else if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                if (currentFocusedTaskCard != null && event.isControlDown()) {
                    Alert confirmDialog = new Alert(Alert.AlertType.CONFIRMATION,
                            "Are you sure you want to delete task '" + currentFocusedTaskCard.getTask().getTitle() + "'?",
                            ButtonType.YES, ButtonType.NO);
                    confirmDialog.setTitle("Confirm Deletion");
                    confirmDialog.setHeaderText(null);
                    Optional<ButtonType> res = confirmDialog.showAndWait();
                    if (res.isPresent() && res.get() == ButtonType.YES) {
                        deleteTaskFromDB(currentFocusedTaskCard.getTask());
                        loadTasksFromDB();
                        focusedTaskIndexInColumn = -1;
                        updateTaskFocus();
                    }
                }
            }
            if (Arrays.asList(KeyCode.UP, KeyCode.DOWN, KeyCode.LEFT, KeyCode.RIGHT).contains(event.getCode())) {
                event.consume();
            }
        });
    }

    private void moveTask(Task task, int direction) {
        int currentStatusIndex = STATUS_LIST.indexOf(task.getStatus());
        int newStatusIndex = currentStatusIndex + direction;

        if (newStatusIndex >= 0 && newStatusIndex < STATUS_LIST.size()) {
            task.setStatus(STATUS_LIST.get(newStatusIndex));
            updateTaskInDB(task);
            loadTasksFromDB();

            focusedColumnIndex = newStatusIndex;
            KanbanColumn newColumn = columns.get(focusedColumnIndex);
            focusedTaskIndexInColumn = newColumn.findTaskIndex(task.getId());

            updateColumnFocus();
            updateTaskFocus();
        }
    }

    private void updateColumnFocus() {
        for (int i = 0; i < columns.size(); i++) {
            KanbanColumn col = columns.get(i);
            if (i == focusedColumnIndex) {
                col.getStyleClass().add("focused-column");
            } else {
                col.getStyleClass().remove("focused-column");
            }
        }
    }

    private void updateTaskFocus() {
        for (KanbanColumn col : columns) {
            col.clearAllTaskFocus();
        }

        if (focusedColumnIndex >= 0 && focusedColumnIndex < columns.size()) {
            KanbanColumn currentFocusedColumn = columns.get(focusedColumnIndex);
            if (focusedTaskIndexInColumn >= 0 && focusedTaskIndexInColumn < currentFocusedColumn.getTaskCount()) {
                currentFocusedColumn.setTaskFocus(focusedTaskIndexInColumn, true);
            }
        }
    }

    private void showErrorDialog(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }


    @Override
    public void stop() throws Exception {
        if (dataSource != null) {
            dataSource.close();
            System.out.println("HikariDataSource closed.");
        }
        super.stop();
    }

    // --- Inner Classes ---

    static class Task {
        private int id;
        private StringProperty title = new SimpleStringProperty();
        private StringProperty description = new SimpleStringProperty();
        private StringProperty assignee = new SimpleStringProperty();
        private StringProperty module = new SimpleStringProperty();
        private StringProperty status = new SimpleStringProperty();
        private StringProperty priority = new SimpleStringProperty();
        private ObjectProperty<LocalDate> dueDate = new SimpleObjectProperty<>();


        public Task() {}

        public Task(int id, String title, String description, String assignee, String module, String status, String priority, LocalDate dueDate) {
            this.id = id;
            setTitle(title);
            setDescription(description);
            setAssignee(assignee);
            setModule(module);
            setStatus(status);
            setPriority(priority);
            setDueDate(dueDate);
        }

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }

        public String getTitle() { return title.get(); }
        public void setTitle(String title) { this.title.set(title); }
        public StringProperty titleProperty() { return title; }

        public String getDescription() { return description.get(); }
        public void setDescription(String description) { this.description.set(description); }
        public StringProperty descriptionProperty() { return description; }

        public String getAssignee() { return assignee.get(); }
        public void setAssignee(String assignee) { this.assignee.set(assignee); }
        public StringProperty assigneeProperty() { return assignee; }

        public String getModule() { return module.get(); }
        public void setModule(String module) { this.module.set(module); }
        public StringProperty moduleProperty() { return module; }

        public String getStatus() { return status.get(); }
        public void setStatus(String status) { this.status.set(status); }
        public StringProperty statusProperty() { return status; }

        public String getPriority() { return priority.get(); }
        public void setPriority(String priority) { this.priority.set(priority); }
        public StringProperty priorityProperty() { return priority; }

        public LocalDate getDueDate() { return dueDate.get(); }
        public void setDueDate(LocalDate dueDate) { this.dueDate.set(dueDate); }
        public ObjectProperty<LocalDate> dueDateProperty() { return dueDate; }

        @Override
        public String toString() {
            return getTitle();
        }
    }

    class KanbanColumn extends VBox {
        private Label titleLabel;
        private VBox taskContainer;
        private String status;
        private ObservableList<TaskCard> taskCards = FXCollections.observableArrayList();


        public KanbanColumn(String status) {
            this.status = status;
            this.setSpacing(10);
            this.setPadding(new Insets(10));
            this.getStyleClass().add("kanban-column");
            this.setMinWidth(220); // Min width for each column
            this.setPrefWidth(280); // Preferred width
            this.setMaxWidth(350); // Max width
            this.setAlignment(Pos.TOP_CENTER);


            titleLabel = new Label(status);
            titleLabel.getStyleClass().add("column-title");

            taskContainer = new VBox(5);
            taskContainer.getStyleClass().add("task-container");
            VBox.setVgrow(taskContainer, Priority.ALWAYS);


            ScrollPane scrollPane = new ScrollPane(taskContainer);
            scrollPane.setFitToWidth(true);
            scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scrollPane.getStyleClass().add("column-scroll-pane");
            VBox.setVgrow(scrollPane, Priority.ALWAYS);


            this.getChildren().addAll(titleLabel, scrollPane);
            setupDragAndDrop(this);
        }

        public String getStatus() {
            return status;
        }

        public void addTaskCard(Task task) {
            TaskCard card = new TaskCard(task);
            taskCards.add(card);
            taskContainer.getChildren().add(card);
        }

        public void removeTaskCard(TaskCard card) {
            taskCards.remove(card);
            taskContainer.getChildren().remove(card);
        }

        public void clearTasks() {
            taskCards.clear();
            taskContainer.getChildren().clear();
        }

        public int getTaskCount() {
            return taskCards.size();
        }

        public TaskCard getTaskCard(int index) {
            if (index >= 0 && index < taskCards.size()) {
                return taskCards.get(index);
            }
            return null;
        }

        public int findTaskIndex(int taskId) {
            for (int i = 0; i < taskCards.size(); i++) {
                if (taskCards.get(i).getTask().getId() == taskId) {
                    return i;
                }
            }
            return -1;
        }

        public void clearAllTaskFocus() {
            for (TaskCard card : taskCards) {
                card.setFocusStyle(false);
            }
        }

        public void setTaskFocus(int index, boolean focused) {
            if (index >= 0 && index < taskCards.size()) {
                taskCards.get(index).setFocusStyle(focused);
            }
        }

        private void setupDragAndDrop(KanbanColumn targetColumn) {
            targetColumn.setOnDragOver(event -> {
                if (event.getGestureSource() != targetColumn && event.getDragboard().hasString()) {
                    try {
                        Integer.parseInt(event.getDragboard().getString());
                        event.acceptTransferModes(TransferMode.MOVE);
                    } catch (NumberFormatException e) {
                        // Ignore if not a valid task ID
                    }
                }
                event.consume();
            });

            targetColumn.setOnDragEntered(event -> {
                if (event.getGestureSource() != targetColumn && event.getDragboard().hasString()) {
                    targetColumn.getStyleClass().add("drag-over");
                }
                event.consume();
            });

            targetColumn.setOnDragExited(event -> {
                targetColumn.getStyleClass().remove("drag-over");
                event.consume();
            });

            targetColumn.setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                boolean success = false;
                if (db.hasString()) {
                    try {
                        int taskId = Integer.parseInt(db.getString());
                        Task taskToMove = findTaskByIdGlobal(taskId);

                        if (taskToMove != null) {
                            // Remove from old column UI only, DB update will refresh all
                            for (KanbanColumn col : columns) {
                                TaskCard cardToRemove = null;
                                for(TaskCard tc : col.taskCards){
                                    if(tc.getTask().getId() == taskId){
                                        cardToRemove = tc;
                                        break;
                                    }
                                }
                                if(cardToRemove != null){
                                    // Don't remove from UI here, let loadTasksFromDB handle it
                                    // col.removeTaskCard(cardToRemove);
                                    break;
                                }
                            }

                            taskToMove.setStatus(targetColumn.getStatus());
                            updateTaskInDB(taskToMove);
                            loadTasksFromDB(); // Reload all tasks to reflect the change correctly
                            success = true;

                            focusedColumnIndex = columns.indexOf(targetColumn);
                            focusedTaskIndexInColumn = targetColumn.findTaskIndex(taskId); // Re-find index after reload
                            updateColumnFocus();
                            updateTaskFocus();
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("Dragboard content is not a valid task ID: " + db.getString());
                    }
                }
                event.setDropCompleted(success);
                event.consume();
            });
        }

        private Task findTaskByIdGlobal(int taskId) {
            for (KanbanColumn column : columns) {
                for (TaskCard card : column.taskCards) {
                    if (card.getTask().getId() == taskId) {
                        return card.getTask();
                    }
                }
            }
            return null;
        }
    }


    class TaskCard extends VBox {
        private Task task;
        private Label titleLabel;
        private Label assigneeLabel;
        private Label priorityLabel;
        private Label dueDateLabel;

        public TaskCard(Task task) {
            this.task = task;
            this.setPadding(new Insets(8));
            this.setSpacing(5);
            this.getStyleClass().add("task-card");
            this.setStyle("-fx-border-color: #888; -fx-border-width: 2; -fx-border-radius: 8; -fx-background-radius: 8; -fx-background-color: white;");
            this.setMaxWidth(Double.MAX_VALUE); // Allow card to fill column width
            this.setMinWidth(0); // Allow card to shrink if needed


            titleLabel = new Label();
            titleLabel.textProperty().bind(task.titleProperty());
            titleLabel.getStyleClass().add("task-title");
            titleLabel.setWrapText(true);


            assigneeLabel = new Label();
            assigneeLabel.textProperty().bind(task.assigneeProperty());
            assigneeLabel.getStyleClass().add("task-detail");
            assigneeLabel.setWrapText(true);


            priorityLabel = new Label();
            priorityLabel.textProperty().bind(task.priorityProperty());
            priorityLabel.getStyleClass().add("task-detail");
            task.priorityProperty().addListener((obs, oldVal, newVal) -> updatePriorityStyle(newVal));
            updatePriorityStyle(task.getPriority());


            dueDateLabel = new Label();
            task.dueDateProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null) {
                    dueDateLabel.setText("Due: " + newVal.format(DateTimeFormatter.ISO_LOCAL_DATE));
                    if (newVal.isBefore(LocalDate.now())) {
                        dueDateLabel.getStyleClass().add("task-detail-overdue");
                        dueDateLabel.getStyleClass().remove("task-detail-upcoming");
                    } else {
                        dueDateLabel.getStyleClass().remove("task-detail-overdue");
                        dueDateLabel.getStyleClass().add("task-detail-upcoming");
                    }
                } else {
                    dueDateLabel.setText("");
                    dueDateLabel.getStyleClass().removeAll("task-detail-overdue", "task-detail-upcoming");
                }
            });
            if (task.getDueDate() != null) {
                dueDateLabel.setText("Due: " + task.getDueDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
                if (task.getDueDate().isBefore(LocalDate.now())) {
                    dueDateLabel.getStyleClass().add("task-detail-overdue");
                } else {
                    dueDateLabel.getStyleClass().add("task-detail-upcoming");
                }
            }
            dueDateLabel.getStyleClass().add("task-detail");


            this.getChildren().addAll(titleLabel, assigneeLabel, priorityLabel, dueDateLabel);

            this.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) {
                    showTaskDialog(task, (Stage) this.getScene().getWindow());
                }
            });

            this.setOnDragDetected(event -> {
                Dragboard db = this.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString(String.valueOf(task.getId()));
                db.setContent(content);
                this.getStyleClass().add("dragging-card");
                event.consume();
            });

            this.setOnDragDone(event -> {
                this.getStyleClass().remove("dragging-card");
                event.consume();
            });
        }

        public Task getTask() {
            return task;
        }

        public void setFocusStyle(boolean focused) {
            if (focused) {
                this.getStyleClass().add("focused-task-card");
            } else {
                this.getStyleClass().remove("focused-task-card");
            }
        }

        private void updatePriorityStyle(String priority) {
            this.getStyleClass().removeAll("priority-high", "priority-medium", "priority-low");
            if (priority != null) {
                switch (priority.toLowerCase()) {
                    case "high":
                        this.getStyleClass().add("priority-high");
                        break;
                    case "medium":
                        this.getStyleClass().add("priority-medium");
                        break;
                    case "low":
                        this.getStyleClass().add("priority-low");
                        break;
                }
            }
        }
    }
}
