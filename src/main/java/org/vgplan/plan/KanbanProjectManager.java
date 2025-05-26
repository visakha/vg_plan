package org.vgplan.plan;

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
import java.util.Properties;
import java.io.InputStream;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class KanbanProjectManager extends Application {

    private static final String DB_URL = "jdbc:sqlite:project_kanban.db";
    static HikariDataSource dataSource;

    private BorderPane rootPane;
    private HBox columnsContainer;
    private ObservableList<KanbanColumn> columns;

    private static final List<String> TEAM_MEMBERS = Arrays.asList("SSA1", "SA2", "India PM", "Dev1", "Dev2", "Dev3",
            "Dev4", "Dev5", "Dev6", "Unassigned");
    private static final List<String> MODULES = Arrays.asList("Ingress", "Egress", "MDM Customization", "Planning",
            "General");
    private static final List<String> PRIORITIES = Arrays.asList("High", "Medium", "Low");
    static final List<String> STATUS_LIST = Arrays.asList("To Do", "In Progress", "Blocked", "In Review", "Done");

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
        // Ensure styles.css is in the correct location (e.g., src/main/resources if
        // using Maven/Gradle)
        // For simple setups, it can be in the same directory as the .java file, but
        // classpath might need adjustment.
        try {
            String cssPath = getClass().getResource("/styles.css").toExternalForm();
            scene.getStylesheets().add(cssPath);
        } catch (NullPointerException e) {
            System.err.println(
                    "Warning: styles.css not found. Ensure it's in the classpath (e.g., src/main/resources folder or same directory and classpath is set).");
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
        // The driver class name is usually not needed for modern JDBC drivers if the
        // JAR is in the classpath.
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
        Properties sqlProps = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/org/vgplan/plan/db_schema.properties")) {
            if (in != null) {
                sqlProps.load(in);
            } else {
                throw new RuntimeException("Could not find db_schema.properties");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load db_schema.properties: " + e.getMessage(), e);
        }
        String createProjectPhasesTableSQL = sqlProps.getProperty("createProjectPhasesTable");
        String createEpicsTableSQL = sqlProps.getProperty("createEpicsTable");
        String createTaskTableSQL = sqlProps.getProperty("createTaskTable");
        String createSubTaskTableSQL = sqlProps.getProperty("createSubTaskTable");
        String createRaciActivitiesTableSQL = sqlProps.getProperty("createRaciActivitiesTable");
        String createTeamMembersTableSQL = sqlProps.getProperty("createTeamMembersTable");
        String createRaciAssignmentsTableSQL = sqlProps.getProperty("createRaciAssignmentsTable");

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
            try (PreparedStatement psCheck = conn.prepareStatement("SELECT COUNT(*) FROM team_members");
                    ResultSet rs = psCheck.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    insertDefaultTeamMembers(conn);
                }
            }

        } catch (SQLException e) {
            System.err.println("Error creating database tables: " + e.getMessage());
        }
    }

    private void insertDefaultTeamMembers(Connection conn) throws SQLException {
        for (String memberName : TEAM_MEMBERS) {
            try (PreparedStatement psCheck = conn
                    .prepareStatement("SELECT COUNT(*) FROM team_members WHERE member_name = ?");
                    PreparedStatement psInsert = conn
                            .prepareStatement("INSERT INTO team_members (member_name) VALUES (?)")) {
                psCheck.setString(1, memberName);
                ResultSet rs = psCheck.executeQuery();
                if (rs.next() && rs.getInt(1) == 0) {
                    psInsert.setString(1, memberName);
                    psInsert.executeUpdate();
                }
            }
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
    /**
     * Shows the Project Hierarchy dialog using the modular ProjectHierarchyDialog
     * class.
     * 
     * @param ownerStage the parent stage
     */
    private void showProjectHierarchyDialog(Stage ownerStage) {
        new ProjectHierarchyDialog(ownerStage, new DatabaseUtil(), this).show();
    }

    // --- CRUD Dialog (Create or Edit) ---
    public void showHierarchyCrudDialog(TreeItem<HierarchyNode> nodeItem, TreeView<HierarchyNode> treeView,
            boolean isCreate) {
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
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 10, 10));
        TextField nameField = new TextField();
        TextField skillSetsField = new TextField();
        if (targetType == HierarchyType.PHASE) {
            grid.add(new Label("Phase Name:"), 0, 0);
            grid.add(nameField, 1, 0);
            grid.add(new Label("Skill Sets Needed:"), 0, 1);
            grid.add(skillSetsField, 1, 1);
            if (!isCreate && node.type == HierarchyType.PHASE) {
                nameField.setText(node.displayName.replaceFirst("^Phase: ", ""));
                skillSetsField.setText(node.skillSets != null ? node.skillSets : "");
            }
        } else if (targetType == HierarchyType.EPIC) {
            grid.add(new Label("Epic Name:"), 0, 0);
            grid.add(nameField, 1, 0);
            if (!isCreate && node.type == HierarchyType.EPIC) {
                nameField.setText(node.displayName.replaceFirst("^Epic: ", ""));
            }
        } else if (targetType == HierarchyType.TASK) {
            grid.add(new Label("Task Name:"), 0, 0);
            grid.add(nameField, 1, 0);
            if (!isCreate && node.type == HierarchyType.TASK) {
                nameField.setText(node.displayName.replaceFirst("^Task: ", ""));
            }
        } else if (targetType == HierarchyType.SUBTASK) {
            grid.add(new Label("Sub-Task Name:"), 0, 0);
            grid.add(nameField, 1, 0);
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

    /**
     * Shows an error dialog (static version for use in utility classes).
     * 
     * @param title   the dialog title
     * @param content the dialog content
     */
    public static void showErrorDialogStatic(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    // --- Hierarchy Types and Node ---
    /**
     * Hierarchy types for the project hierarchy tree.
     */
    public static enum HierarchyType {
        ROOT, PHASE, EPIC, TASK, SUBTASK
    }

    // --- Load Hierarchy Tree ---
    /**
     * Loads the project hierarchy tree into the given root item.
     * 
     * @param rootItem the root TreeItem to populate
     */
    private void loadHierarchyTree(TreeItem<HierarchyNode> rootItem) {
        rootItem.getChildren().clear();
        try (Connection conn = dataSource.getConnection()) {
            loadPhases(conn, rootItem);
        } catch (SQLException e) {
            rootItem.getChildren().clear();
            rootItem.getChildren()
                    .add(new TreeItem<>(new HierarchyNode(HierarchyType.ROOT, null, "Error: " + e.getMessage())));
        }
    }

    /**
     * Loads all phases and their children into the root item.
     */
    private void loadPhases(Connection conn, TreeItem<HierarchyNode> rootItem) throws SQLException {
        String phaseSql = "SELECT phase_id, phase_name, skill_sets FROM project_phases ORDER BY phase_name";
        try (PreparedStatement psPhase = conn.prepareStatement(phaseSql); ResultSet rsPhase = psPhase.executeQuery()) {
            while (rsPhase.next()) {
                int phaseId = rsPhase.getInt("phase_id");
                String phaseName = rsPhase.getString("phase_name");
                String skillSets = rsPhase.getString("skill_sets");
                TreeItem<HierarchyNode> phaseItem = new TreeItem<>(
                        new HierarchyNode(HierarchyType.PHASE, phaseId, "Phase: " + phaseName, skillSets));
                loadEpics(conn, phaseItem, phaseId);
                rootItem.getChildren().add(phaseItem);
            }
        }
    }

    /**
     * Loads all epics for a given phase and their children.
     */
    private void loadEpics(Connection conn, TreeItem<HierarchyNode> phaseItem, int phaseId) throws SQLException {
        String epicSql = "SELECT epic_id, epic_name FROM epics WHERE phase_id = ? ORDER BY epic_name";
        try (PreparedStatement psEpic = conn.prepareStatement(epicSql)) {
            psEpic.setInt(1, phaseId);
            try (ResultSet rsEpic = psEpic.executeQuery()) {
                while (rsEpic.next()) {
                    int epicId = rsEpic.getInt("epic_id");
                    String epicName = rsEpic.getString("epic_name");
                    TreeItem<HierarchyNode> epicItem = new TreeItem<>(
                            new HierarchyNode(HierarchyType.EPIC, epicId, "Epic: " + epicName));
                    loadTasks(conn, epicItem, epicId);
                    phaseItem.getChildren().add(epicItem);
                }
            }
        }
    }

    /**
     * Loads all tasks for a given epic and their children.
     */
    private void loadTasks(Connection conn, TreeItem<HierarchyNode> epicItem, int epicId) throws SQLException {
        String taskSql = "SELECT id, title FROM tasks WHERE epic_id = ? ORDER BY title";
        try (PreparedStatement psTask = conn.prepareStatement(taskSql)) {
            psTask.setInt(1, epicId);
            try (ResultSet rsTask = psTask.executeQuery()) {
                while (rsTask.next()) {
                    int taskId = rsTask.getInt("id");
                    String taskTitle = rsTask.getString("title");
                    TreeItem<HierarchyNode> taskItem = new TreeItem<>(
                            new HierarchyNode(HierarchyType.TASK, taskId, "Task: " + taskTitle));
                    loadSubtasks(conn, taskItem, taskId);
                    epicItem.getChildren().add(taskItem);
                }
            }
        }
    }

    /**
     * Loads all subtasks for a given task.
     */
    private void loadSubtasks(Connection conn, TreeItem<HierarchyNode> taskItem, int taskId) throws SQLException {
        String subSql = "SELECT subtask_id, subtask_name FROM subtasks WHERE task_id = ? ORDER BY subtask_name";
        try (PreparedStatement psSub = conn.prepareStatement(subSql)) {
            psSub.setInt(1, taskId);
            try (ResultSet rsSub = psSub.executeQuery()) {
                while (rsSub.next()) {
                    int subId = rsSub.getInt("subtask_id");
                    String subName = rsSub.getString("subtask_name");
                    TreeItem<HierarchyNode> subItem = new TreeItem<>(
                            new HierarchyNode(HierarchyType.SUBTASK, subId, "Sub-Task: " + subName));
                    taskItem.getChildren().add(subItem);
                }
            }
        }
    }

    // --- Delete Node ---
    private void deleteHierarchyNode(TreeItem<HierarchyNode> node, TreeView<HierarchyNode> treeView) {
        HierarchyNode n = node.getValue();
        try (Connection conn = dataSource.getConnection()) {
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
                Task task = new Task(rs.getInt("id"), rs.getString("title"), rs.getString("description"),
                        rs.getString("assignee"), rs.getString("module"), rs.getString("status"),
                        rs.getString("priority"),
                        rs.getString("due_date") != null ? LocalDate.parse(rs.getString("due_date")) : null);
                addTaskToCorrectColumn(task);
            }
        } catch (SQLException e) {
            System.err.println("Error loading tasks from DB: " + e.getMessage());
            showErrorDialog("Database Error", "Could not load tasks from the database.");
        }
    }

    private void addTaskToCorrectColumn(Task task) {
        for (KanbanColumn column : columns) {
            if (column.getStatus().equals(task.status())) {
                column.addTaskCard(task);
                return;
            }
        }
        System.err.println("Task '" + task.title() + "' has unknown status: " + task.status() + ". Adding to 'To Do'.");
        if (!columns.isEmpty()) {
            Task updatedTask = new Task(task.id(), task.title(), task.description(), task.assignee(), task.module(),
                    columns.get(0).getStatus(), task.priority(), task.dueDate());
            columns.get(0).addTaskCard(updatedTask);
            updateTaskInDB(updatedTask);
        }
    }

    @SuppressWarnings("unused")
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
            titleField.setText(existingTask.title());
            descriptionArea.setText(existingTask.description());
            assigneeCombo.setValue(existingTask.assignee());
            moduleCombo.setValue(existingTask.module());
            statusCombo.setValue(existingTask.status());
            priorityCombo.setValue(existingTask.priority());
            dueDatePicker.setValue(existingTask.dueDate());
        } else {
            statusCombo.setValue(STATUS_LIST.get(0));
        }

        grid.add(new Label("Title:"), 0, 0);
        grid.add(titleField, 1, 0);
        grid.add(new Label("Description:"), 0, 1);
        grid.add(descriptionArea, 1, 1);
        grid.add(new Label("Assignee:"), 0, 2);
        grid.add(assigneeCombo, 1, 2);
        grid.add(new Label("Module:"), 0, 3);
        grid.add(moduleCombo, 1, 3);
        grid.add(new Label("Status:"), 0, 4);
        grid.add(statusCombo, 1, 4);
        grid.add(new Label("Priority:"), 0, 5);
        grid.add(priorityCombo, 1, 5);
        grid.add(new Label("Due Date:"), 0, 6);
        grid.add(dueDatePicker, 1, 6);

        dialog.getDialogPane().setContent(grid);

        ButtonType saveButtonType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        Node saveButton = dialog.getDialogPane().lookupButton(saveButtonType);
        saveButton.setDisable(true);
        titleField.textProperty().addListener((unused1, unused2, newValue) -> {
            saveButton.setDisable(newValue.trim().isEmpty());
        });
        if (existingTask != null && !existingTask.title().isEmpty()) {
            saveButton.setDisable(false);
        }

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                return new Task(existingTask == null ? 0 : existingTask.id(), titleField.getText(),
                        descriptionArea.getText(), assigneeCombo.getValue(), moduleCombo.getValue(),
                        statusCombo.getValue(), priorityCombo.getValue(), dueDatePicker.getValue());
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
            pstmt.setString(1, task.title());
            pstmt.setString(2, task.description());
            pstmt.setString(3, task.assignee());
            pstmt.setString(4, task.module());
            pstmt.setString(5, task.status());
            pstmt.setString(6, task.priority());
            pstmt.setString(7, task.dueDate() != null ? task.dueDate().format(DateTimeFormatter.ISO_LOCAL_DATE) : null);

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        // No way to update id in record, but not used after insert
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
        try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, task.title());
            pstmt.setString(2, task.description());
            pstmt.setString(3, task.assignee());
            pstmt.setString(4, task.module());
            pstmt.setString(5, task.status());
            pstmt.setString(6, task.priority());
            pstmt.setString(7, task.dueDate() != null ? task.dueDate().format(DateTimeFormatter.ISO_LOCAL_DATE) : null);
            pstmt.setInt(8, task.id());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error updating task in DB: " + e.getMessage());
            showErrorDialog("Database Error", "Could not update the task.");
        }
    }

    private void deleteTaskFromDB(Task task) {
        String sql = "DELETE FROM tasks WHERE id = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, task.id());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error deleting task from DB: " + e.getMessage());
            showErrorDialog("Database Error", "Could not delete the task.");
        }
    }

    private void setupKeyboardNavigation(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            KanbanColumn currentFocusedColumn = columns.get(focusedColumnIndex);
            TaskCard currentFocusedTaskCard = (focusedTaskIndexInColumn >= 0
                    && focusedTaskIndexInColumn < currentFocusedColumn.getTaskCount())
                            ? currentFocusedColumn.getTaskCard(focusedTaskIndexInColumn)
                            : null;

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
                            "Are you sure you want to delete task '" + currentFocusedTaskCard.getTask().title() + "'?",
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
        int currentStatusIndex = STATUS_LIST.indexOf(task.status());
        int newStatusIndex = currentStatusIndex + direction;

        if (newStatusIndex >= 0 && newStatusIndex < STATUS_LIST.size()) {
            Task updatedTask = new Task(task.id(), task.title(), task.description(), task.assignee(), task.module(),
                    STATUS_LIST.get(newStatusIndex), task.priority(), task.dueDate());
            updateTaskInDB(updatedTask);
            loadTasksFromDB();

            focusedColumnIndex = newStatusIndex;
            KanbanColumn newColumn = columns.get(focusedColumnIndex);
            focusedTaskIndexInColumn = newColumn.findTaskIndex(updatedTask.id());

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

    /**
     * Represents a Kanban task. Immutable record version for Java 21.
     */
    public static record Task(int id, String title, String description, String assignee, String module, String status,
            String priority, LocalDate dueDate) {
        @Override
        public String toString() {
            return title;
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
                if (taskCards.get(i).getTask().id() == taskId) {
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
                                for (TaskCard tc : col.taskCards) {
                                    if (tc.getTask().id() == taskId) {
                                        cardToRemove = tc;
                                        break;
                                    }
                                }
                                if (cardToRemove != null) {
                                    // Don't remove from UI here, let loadTasksFromDB handle it
                                    // col.removeTaskCard(cardToRemove);
                                    break;
                                }
                            }

                            Task updatedTask = new Task(taskToMove.id(), taskToMove.title(), taskToMove.description(),
                                    taskToMove.assignee(), taskToMove.module(), targetColumn.getStatus(),
                                    taskToMove.priority(), taskToMove.dueDate());
                            updateTaskInDB(updatedTask);
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
                    if (card.getTask().id() == taskId) {
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
            this.setStyle(
                    "-fx-border-color: #888; -fx-border-width: 2; -fx-border-radius: 8; -fx-background-radius: 8; -fx-background-color: white;");
            this.setMaxWidth(Double.MAX_VALUE); // Allow card to fill column width
            this.setMinWidth(0); // Allow card to shrink if needed

            titleLabel = new Label(task.title());
            titleLabel.getStyleClass().add("task-title");
            titleLabel.setWrapText(true);

            assigneeLabel = new Label(task.assignee());
            assigneeLabel.getStyleClass().add("task-detail");
            assigneeLabel.setWrapText(true);

            priorityLabel = new Label(task.priority());
            priorityLabel.getStyleClass().add("task-detail");
            updatePriorityStyle(task.priority());

            dueDateLabel = new Label();
            if (task.dueDate() != null) {
                dueDateLabel.setText("Due: " + task.dueDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
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
                content.putString(String.valueOf(task.id()));
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
                this.setStyle(this.getStyle() + ";-fx-background-color: #e0f7fa;");
            } else {
                this.setStyle(
                        "-fx-border-color: #888; -fx-border-width: 2; -fx-border-radius: 8; -fx-background-radius: 8; -fx-background-color: white;");
            }
        }

        private void updatePriorityStyle(String priority) {
            this.getStyleClass().removeAll("priority-high", "priority-medium", "priority-low");
            if (priority != null) {
                switch (priority) {
                case "High" -> this.getStyleClass().add("priority-high");
                case "Medium" -> this.getStyleClass().add("priority-medium");
                case "Low" -> this.getStyleClass().add("priority-low");
                }
            }
        }
    }
}
