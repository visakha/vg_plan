package org.vgplan.plan;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.scene.control.Dialog;
import javafx.scene.control.ButtonType;

import java.util.Optional;

/**
 * Dialog for managing the Project Hierarchy (Phase → Epic → Task → Sub-Task).
 * Follows JavaFX best practices and is modular for reuse.
 */
public class ProjectHierarchyDialog {
    private final Stage ownerStage;
    private final DatabaseUtil dbUtil;
    private final KanbanProjectManager mainApp;

    /**
     * Constructs the ProjectHierarchyDialog.
     * 
     * @param ownerStage the parent stage
     * @param dbUtil     the database utility
     * @param mainApp    the main KanbanProjectManager app reference
     */
    public ProjectHierarchyDialog(Stage ownerStage, DatabaseUtil dbUtil, KanbanProjectManager mainApp) {
        this.ownerStage = ownerStage;
        this.dbUtil = dbUtil;
        this.mainApp = mainApp;
    }

    /**
     * Shows the project hierarchy dialog using FXML and controller.
     */
    public void show() {
        try {
            // Try absolute path first
            java.net.URL fxml = ProjectHierarchyDialog.class.getResource("/org/vgplan/plan/project_hierarchy.fxml");
            // If not found, try relative path (package-relative)
            if (fxml == null) {
                fxml = ProjectHierarchyDialog.class.getResource("project_hierarchy.fxml");
            }
            if (fxml == null) {
                throw new RuntimeException(
                        "FXML not found: tried /org/vgplan/plan/project_hierarchy.fxml and project_hierarchy.fxml");
            }
            FXMLLoader loader = new FXMLLoader(fxml);
            Parent root = loader.load();
            ProjectHierarchyController controller = loader.getController();
            controller.setDbUtil(dbUtil);
            controller.setMainAppReference(mainApp);
            Dialog<Void> dialog = new Dialog<>();
            dialog.initModality(Modality.APPLICATION_MODAL);
            dialog.initOwner(ownerStage);
            dialog.setTitle("Project Phase → Epic → Task → Sub-Task Hierarchy");
            dialog.getDialogPane().setContent(root);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dialog.showAndWait();
        } catch (Exception e) {
            KanbanProjectManager.showErrorDialogStatic("Error loading hierarchy dialog", e.getMessage());
        }
    }
}
