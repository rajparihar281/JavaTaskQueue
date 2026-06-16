package com.taskqueue.gui.controller;

import com.taskqueue.gui.ServerConnection;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Stage;

import org.json.JSONObject;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Controller for the Submit Task modal dialog.
 * <p>
 * Validates user input, builds a JSON command string conforming to the
 * server protocol, and sends it via the shared {@link ServerConnection}.
 * </p>
 */
public class SubmitTaskController {

    @FXML private ChoiceBox<String> taskTypeChoice;
    @FXML private TextField customTypeField;
    @FXML private TextField payloadField;
    @FXML private Label payloadHint;
    @FXML private RadioButton radioPriorityHigh;
    @FXML private RadioButton radioPriorityNormal;
    @FXML private RadioButton radioPriorityLow;
    @FXML private ToggleGroup priorityGroup;
    @FXML private TextField delayField;
    @FXML private Button submitBtn;
    @FXML private Button cancelBtn;

    private ServerConnection connection;

    /**
     * Injects the shared server connection before the dialog is shown.
     */
    public void setConnection(ServerConnection connection) {
        this.connection = connection;
    }

    @FXML
    public void initialize() {
        // Populate task type choices
        taskTypeChoice.getItems().addAll("COMPUTE", "SLEEP", "ECHO", "Custom...");
        taskTypeChoice.setValue("COMPUTE");
        updatePayloadHint("COMPUTE");

        // Dynamic hint when task type changes
        taskTypeChoice.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> {
            if (val != null) {
                onTaskTypeChanged(val);
            }
        });

        // Show/hide custom type field
        customTypeField.setVisible(false);
        customTypeField.setManaged(false);

        // Numeric-only filter for delay field
        UnaryOperator<TextFormatter.Change> intFilter = change -> {
            String newText = change.getControlNewText();
            if (newText.matches("\\d*")) {
                return change;
            }
            return null;
        };
        delayField.setTextFormatter(new TextFormatter<>(intFilter));

        // Default priority
        radioPriorityNormal.setSelected(true);
    }

    // ====================== Event Handlers ======================

    private void onTaskTypeChanged(String type) {
        boolean isCustom = "Custom...".equals(type);
        customTypeField.setVisible(isCustom);
        customTypeField.setManaged(isCustom);

        if (!isCustom) {
            updatePayloadHint(type);
        } else {
            payloadHint.setText("Enter type above, then provide payload");
        }
    }

    private void updatePayloadHint(String type) {
        switch (type) {
            case "COMPUTE" -> payloadHint.setText("e.g. FACTORIAL:20 or ISPRIME:97");
            case "SLEEP"   -> payloadHint.setText("e.g. 2000 (milliseconds)");
            case "ECHO"    -> payloadHint.setText("e.g. Hello World");
            default        -> payloadHint.setText("Provide task payload");
        }
    }

    @FXML
    private void onSubmit() {
        // Determine task type
        String selectedType = taskTypeChoice.getValue();
        String taskType;
        if ("Custom...".equals(selectedType)) {
            taskType = customTypeField.getText().trim();
            if (taskType.isEmpty()) {
                showError("Please enter a custom task type.");
                return;
            }
        } else {
            taskType = selectedType;
        }

        // Validate payload
        String payload = payloadField.getText().trim();
        if (payload.isEmpty()) {
            showError("Payload is required.");
            return;
        }

        // Priority
        String priority = "NORMAL";
        if (radioPriorityHigh.isSelected()) priority = "HIGH";
        else if (radioPriorityLow.isSelected()) priority = "LOW";

        // Delay
        long delay = 0;
        String delayText = delayField.getText().trim();
        if (!delayText.isEmpty()) {
            delay = Long.parseLong(delayText);
        }

        // Build JSON
        JSONObject json = new JSONObject();
        json.put("type", taskType);
        json.put("payload", payload);
        json.put("priority", priority);
        if (delay > 0) {
            json.put("scheduledAt", System.currentTimeMillis() + delay);
        }

        String command = "SUBMIT " + json;

        submitBtn.setDisable(true);
        CompletableFuture.runAsync(() -> {
            String resp = connection.sendCommand(command);
            Platform.runLater(() -> {
                submitBtn.setDisable(false);
                try {
                    JSONObject result = new JSONObject(resp);
                    if (result.has("taskId")) {
                        String taskId = result.getString("taskId");
                        showSuccess("Task submitted!\nID: " + taskId.substring(0, Math.min(8, taskId.length())) + "...");
                        closeDialog();
                    } else if (result.has("error")) {
                        showError("Server error: " + result.getString("error"));
                    } else {
                        showError("Unexpected response: " + resp);
                    }
                } catch (Exception e) {
                    showError("Failed to parse response: " + resp);
                }
            });
        });
    }

    @FXML
    private void onCancel() {
        closeDialog();
    }

    // ====================== Helpers ======================

    private void closeDialog() {
        Stage stage = (Stage) cancelBtn.getScene().getWindow();
        stage.close();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Validation Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().getStylesheets().add(
                getClass().getResource("/com/taskqueue/gui/styles.css").toExternalForm());
        alert.showAndWait();
    }

    private void showSuccess(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Success");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.getDialogPane().getStylesheets().add(
                getClass().getResource("/com/taskqueue/gui/styles.css").toExternalForm());
        alert.show();
        // Auto-close after 2 seconds
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute(() ->
                Platform.runLater(alert::close));
    }
}
