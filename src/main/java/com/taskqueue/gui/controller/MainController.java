package com.taskqueue.gui.controller;

import com.taskqueue.gui.ServerConnection;
import com.taskqueue.gui.model.TaskViewModel;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;
import javafx.stage.Modality;
import javafx.stage.Stage;

import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main dashboard controller — wires up the task table, stat cards,
 * connection status indicator, and periodic background refresh.
 */
public class MainController {

    private static final Logger LOGGER = Logger.getLogger(MainController.class.getName());
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm:ss");

    // ---- Top Bar ----
    @FXML private Label titleLabel;
    @FXML private Circle connectionIndicator;
    @FXML private Label connectionLabel;
    @FXML private Button reconnectButton;
    @FXML private Button shutdownButton;

    // ---- Left Panel — stats ----
    @FXML private Label totalCount;
    @FXML private Label pendingCount;
    @FXML private Label runningCount;
    @FXML private Label doneCount;
    @FXML private Label failedCount;
    @FXML private Label cancelledCount;
    @FXML private Button submitButton;
    @FXML private Button refreshButton;

    // ---- Right Panel — task table ----
    @FXML private TableView<TaskViewModel> taskTable;
    @FXML private TableColumn<TaskViewModel, String> colTaskId;
    @FXML private TableColumn<TaskViewModel, String> colType;
    @FXML private TableColumn<TaskViewModel, String> colPriority;
    @FXML private TableColumn<TaskViewModel, String> colStatus;
    @FXML private TableColumn<TaskViewModel, String> colCreatedAt;
    @FXML private TableColumn<TaskViewModel, String> colRetry;
    @FXML private TableColumn<TaskViewModel, Void>   colActions;

    // ---- Bottom Status Bar ----
    @FXML private Label lastRefreshedLabel;
    @FXML private Label taskCountLabel;

    // ---- Internal state ----
    private final ServerConnection connection = new ServerConnection();
    private final ObservableList<TaskViewModel> taskData = FXCollections.observableArrayList();
    private ScheduledExecutorService scheduler;

    /**
     * Called by FXMLLoader after all @FXML fields have been injected.
     */
    @FXML
    public void initialize() {
        // --- Table column bindings ---
        colTaskId.setCellValueFactory(c -> c.getValue().shortIdProperty());
        colType.setCellValueFactory(c -> c.getValue().taskTypeProperty());
        colPriority.setCellValueFactory(c -> c.getValue().priorityProperty());
        colStatus.setCellValueFactory(c -> c.getValue().statusProperty());
        colCreatedAt.setCellValueFactory(c -> c.getValue().createdAtProperty());
        colRetry.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getRetryCount())));
        setupActionsColumn();

        taskTable.setItems(taskData);
        taskTable.setPlaceholder(new Label("No tasks to display"));

        // Row factory — colour rows by status, tooltip with full task ID
        taskTable.setRowFactory(tv -> {
            TableRow<TaskViewModel> row = new TableRow<>() {
                @Override
                protected void updateItem(TaskViewModel item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().removeAll("status-running", "status-done", "status-failed", "status-cancelled");
                    if (empty || item == null) {
                        setTooltip(null);
                        return;
                    }
                    switch (item.getStatus()) {
                        case "RUNNING"   -> getStyleClass().add("status-running");
                        case "DONE"      -> getStyleClass().add("status-done");
                        case "FAILED"    -> getStyleClass().add("status-failed");
                        case "CANCELLED" -> getStyleClass().add("status-cancelled");
                    }
                    setTooltip(new Tooltip("Task ID: " + item.getTaskId()));
                }
            };
            return row;
        });

        // --- Connection listener ---
        connection.addListener(new ServerConnection.ConnectionListener() {
            @Override
            public void onConnected() {
                Platform.runLater(() -> setConnectedUI(true));
            }
            @Override
            public void onDisconnected() {
                Platform.runLater(() -> setConnectedUI(false));
            }
        });

        // Initial connection attempt (off FX thread)
        CompletableFuture.runAsync(() -> {
            boolean ok = connection.connect();
            if (ok) {
                refreshTasks();
            }
        });

        // Background refresh every 2 seconds
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "task-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::refreshTasks, 2, 2, TimeUnit.SECONDS);
    }

    // ====================== Button Handlers ======================

    @FXML
    private void onReconnect() {
        reconnectButton.setDisable(true);
        CompletableFuture.runAsync(() -> {
            connection.connect();
            if (connection.isConnected()) {
                refreshTasks();
            }
            Platform.runLater(() -> reconnectButton.setDisable(!reconnectButton.isVisible()));
        });
    }

    @FXML
    private void onShutdown() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirm Shutdown");
        alert.setHeaderText("Shut down the server?");
        alert.setContentText("This will stop the JavaTaskQueue server. All running tasks will be interrupted.");
        alert.getDialogPane().getStylesheets().add(
                getClass().getResource("/com/taskqueue/gui/styles.css").toExternalForm());
        alert.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                CompletableFuture.runAsync(() -> {
                    connection.sendCommand("SHUTDOWN");
                    Platform.runLater(() -> {
                        setConnectedUI(false);
                        taskData.clear();
                    });
                });
            }
        });
    }

    @FXML
    private void onSubmitNewTask() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/com/taskqueue/gui/submit_task.fxml"));
            Parent root = loader.load();

            SubmitTaskController ctrl = loader.getController();
            ctrl.setConnection(connection);

            Stage dialog = new Stage();
            dialog.setTitle("Submit New Task");
            dialog.initModality(Modality.WINDOW_MODAL);
            dialog.initOwner(taskTable.getScene().getWindow());
            Scene scene = new Scene(root);
            scene.getStylesheets().add(
                    getClass().getResource("/com/taskqueue/gui/styles.css").toExternalForm());
            dialog.setScene(scene);
            dialog.setResizable(false);
            dialog.showAndWait();

            // Refresh immediately after dialog closes
            CompletableFuture.runAsync(this::refreshTasks);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to open Submit Task dialog", e);
        }
    }

    @FXML
    private void onRefreshNow() {
        CompletableFuture.runAsync(this::refreshTasks);
    }

    // ====================== Core Refresh Logic ======================

    /**
     * Sends LIST to the server, parses response, and updates both the
     * table and stat counters on the FX thread.
     */
    private void refreshTasks() {
        if (!connection.isConnected()) return;

        String response = connection.sendCommand("LIST");
        if (response.contains("\"error\"")) return;

        List<TaskViewModel> tasks = TaskViewModel.fromJsonArray(response);

        // Compute stats
        int total = tasks.size();
        int pending = 0, running = 0, done = 0, failed = 0, cancelled = 0;
        for (TaskViewModel t : tasks) {
            switch (t.getStatus()) {
                case "PENDING"   -> pending++;
                case "RUNNING"   -> running++;
                case "DONE"      -> done++;
                case "FAILED"    -> failed++;
                case "CANCELLED" -> cancelled++;
            }
        }
        final int p = pending, r = running, d = done, f = failed, ca = cancelled;
        String now = TIME_FMT.format(new Date());

        Platform.runLater(() -> {
            taskData.setAll(tasks);
            totalCount.setText(String.valueOf(total));
            pendingCount.setText(String.valueOf(p));
            runningCount.setText(String.valueOf(r));
            doneCount.setText(String.valueOf(d));
            failedCount.setText(String.valueOf(f));
            cancelledCount.setText(String.valueOf(ca));
            lastRefreshedLabel.setText("Last refreshed: " + now);
            taskCountLabel.setText("Tasks in queue: " + total);
        });
    }

    // ====================== Actions Column ======================

    private void setupActionsColumn() {
        colActions.setCellFactory(col -> new TableCell<>() {
            private final Button cancelBtn = new Button("Cancel");

            {
                cancelBtn.getStyleClass().add("btn-danger");
                cancelBtn.setStyle("-fx-font-size: 11px; -fx-padding: 2 8 2 8;");
                cancelBtn.setOnAction(e -> {
                    TaskViewModel task = getTableView().getItems().get(getIndex());
                    onCancelTask(task.getTaskId());
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                TaskViewModel task = getTableView().getItems().get(getIndex());
                cancelBtn.setDisable(!"PENDING".equals(task.getStatus()));
                HBox box = new HBox(cancelBtn);
                box.setStyle("-fx-alignment: center;");
                setGraphic(box);
            }
        });
    }

    private void onCancelTask(String taskId) {
        CompletableFuture.runAsync(() -> {
            String resp = connection.sendCommand("CANCEL " + taskId);
            try {
                JSONObject json = new JSONObject(resp);
                String status = json.optString("status", "");
                Platform.runLater(() -> {
                    if ("ok".equals(status)) {
                        showSnackbar("Task cancelled: " + taskId.substring(0, Math.min(8, taskId.length())));
                    } else {
                        showSnackbar("Cannot cancel — task may already be running");
                    }
                });
            } catch (Exception ignored) { }
            refreshTasks();
        });
    }

    // ====================== UI Helpers ======================

    private void setConnectedUI(boolean connected) {
        if (connected) {
            connectionIndicator.getStyleClass().removeAll("indicator-disconnected");
            connectionIndicator.getStyleClass().add("indicator-connected");
            connectionLabel.setText("Connected to " + connection.getHost() + ":" + connection.getPort());
            reconnectButton.setDisable(true);
        } else {
            connectionIndicator.getStyleClass().removeAll("indicator-connected");
            connectionIndicator.getStyleClass().add("indicator-disconnected");
            connectionLabel.setText("Disconnected");
            reconnectButton.setDisable(false);
        }
    }

    private void showSnackbar(String message) {
        Alert info = new Alert(Alert.AlertType.INFORMATION);
        info.setTitle("Info");
        info.setHeaderText(null);
        info.setContentText(message);
        info.getDialogPane().getStylesheets().add(
                getClass().getResource("/com/taskqueue/gui/styles.css").toExternalForm());
        info.show();
        // Auto-close after 2 seconds
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute(() ->
                Platform.runLater(() -> info.close()));
    }

    /**
     * Called when the main stage is closing — shuts down the background
     * scheduler and disconnects from the server.
     */
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        connection.disconnect();
    }
}
