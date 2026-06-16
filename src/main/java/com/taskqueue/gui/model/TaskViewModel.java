package com.taskqueue.gui.model;

import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * JavaFX-friendly view-model wrapping the JSON fields returned by the server
 * for a single task. Every field is exposed as a JavaFX property so it can
 * be directly bound to {@code TableView} columns.
 */
public class TaskViewModel {

    private final SimpleStringProperty taskId;
    private final SimpleStringProperty shortId;
    private final SimpleStringProperty taskType;
    private final SimpleStringProperty priority;
    private final SimpleStringProperty status;
    private final SimpleStringProperty createdAt;
    private final SimpleLongProperty   retryCount;
    private final SimpleLongProperty   createdAtRaw;

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

    /**
     * Constructs a view-model from a JSON object returned by the server.
     *
     * @param json the task JSON object
     */
    public TaskViewModel(JSONObject json) {
        String id = json.optString("taskId", "");
        this.taskId      = new SimpleStringProperty(id);
        this.shortId     = new SimpleStringProperty(id.length() > 8 ? id.substring(0, 8) + "..." : id);
        this.taskType    = new SimpleStringProperty(json.optString("type", "UNKNOWN"));
        this.priority    = new SimpleStringProperty(json.optString("priority", "NORMAL"));
        this.status      = new SimpleStringProperty(json.optString("status", "PENDING"));

        long created = json.optLong("createdAt", 0L);
        this.createdAtRaw = new SimpleLongProperty(created);
        this.createdAt    = new SimpleStringProperty(created > 0 ? TIME_FORMAT.format(new Date(created)) : "-");

        this.retryCount = new SimpleLongProperty(json.optLong("retryCount", 0));
    }

    // ---- Property accessors (for TableView column bindings) ----

    public String getTaskId()    { return taskId.get(); }
    public SimpleStringProperty taskIdProperty() { return taskId; }

    public String getShortId()   { return shortId.get(); }
    public SimpleStringProperty shortIdProperty() { return shortId; }

    public String getTaskType()  { return taskType.get(); }
    public SimpleStringProperty taskTypeProperty() { return taskType; }

    public String getPriority()  { return priority.get(); }
    public SimpleStringProperty priorityProperty() { return priority; }

    public String getStatus()    { return status.get(); }
    public SimpleStringProperty statusProperty() { return status; }

    public String getCreatedAt() { return createdAt.get(); }
    public SimpleStringProperty createdAtProperty() { return createdAt; }

    public long getRetryCount()  { return retryCount.get(); }
    public SimpleLongProperty retryCountProperty() { return retryCount; }

    public long getCreatedAtRaw() { return createdAtRaw.get(); }

    /**
     * Parses a JSON array string (as returned by the LIST command) into a
     * list of view-models.
     *
     * @param jsonArrayStr the raw JSON array string
     * @return list of {@link TaskViewModel} instances
     */
    public static List<TaskViewModel> fromJsonArray(String jsonArrayStr) {
        List<TaskViewModel> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(jsonArrayStr);
            for (int i = 0; i < array.length(); i++) {
                list.add(new TaskViewModel(array.getJSONObject(i)));
            }
        } catch (Exception e) {
            // Return empty list on parse failure
        }
        return list;
    }
}
