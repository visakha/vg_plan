package org.vgplan.plan;

import javafx.fxml.FXML;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ButtonBar;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;

import java.util.Optional;

/**
 * Controller for the Project Hierarchy FXML UI.
 */
public class ProjectHierarchyController {
    @FXML
    private TreeView<HierarchyNode> treeView;

    private DatabaseUtil dbUtil;
    private TreeItem<HierarchyNode> rootItem;
    private KanbanProjectManager mainApp;

    public void setDbUtil(DatabaseUtil dbUtil) {
        this.dbUtil = dbUtil;
    }

    public void setMainAppReference(KanbanProjectManager mainApp) {
        this.mainApp = mainApp;
    }

    public void initialize() {
        rootItem = new TreeItem<>(
                new HierarchyNode(KanbanProjectManager.HierarchyType.ROOT, null, "All Project Phases"));
        rootItem.setExpanded(true);
        dbUtil.loadHierarchyTree(rootItem);
        treeView.setRoot(rootItem);
        treeView.setShowRoot(true);
        treeView.setCellFactory(tv -> new javafx.scene.control.TreeCell<>() {
            @Override
            protected void updateItem(HierarchyNode item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? null : item.displayName);
            }
        });
        treeView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                TreeItem<HierarchyNode> selected = treeView.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getValue().type != KanbanProjectManager.HierarchyType.ROOT) {
                    showHierarchyCrudDialog(selected, false);
                }
            }
        });
        treeView.setOnKeyPressed(this::handleTreeViewKey);
    }

    private void handleTreeViewKey(KeyEvent event) {
        TreeItem<HierarchyNode> selected = treeView.getSelectionModel().getSelectedItem();
        if (event.getCode() == KeyCode.N && event.isControlDown()) {
            if (selected != null) {
                showHierarchyCrudDialog(selected, true);
            }
            event.consume();
        } else if (event.getCode() == KeyCode.DELETE && selected != null
                && selected.getValue().type != KanbanProjectManager.HierarchyType.ROOT) {
            dbUtil.deleteHierarchyNode(selected, treeView);
            event.consume();
        }
    }

    private void showHierarchyCrudDialog(TreeItem<HierarchyNode> nodeItem, boolean isCreate) {
        if (mainApp != null) {
            mainApp.showHierarchyCrudDialog(nodeItem, treeView, isCreate);
            dbUtil.loadHierarchyTree(rootItem);
        } else {
            // Fallback: call static if needed (or keep local logic if running standalone)
            // Optionally, you could throw or log an error here
        }
    }
}
