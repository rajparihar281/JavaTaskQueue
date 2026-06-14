package com.taskqueue.model;

import org.json.JSONObject;

/**
 * Encapsulates the result of executing a task by a handler.
 * <p>
 * A {@code TaskResult} captures the outcome of task execution, including
 * whether it succeeded or failed, any output produced, error messages,
 * and the execution duration in milliseconds.
 * </p>
 */
public class TaskResult {

    private static final String JSON_KEY_TASK_ID = "taskId";
    private static final String JSON_KEY_STATUS = "status";
    private static final String JSON_KEY_OUTPUT = "output";
    private static final String JSON_KEY_ERROR_MESSAGE = "errorMessage";
    private static final String JSON_KEY_DURATION_MS = "durationMs";

    private final String taskId;
    private final TaskStatus status;
    private final String output;
    private final String errorMessage;
    private final long durationMs;

    /**
     * Constructs a new TaskResult.
     *
     * @param taskId       the ID of the task that was executed
     * @param status       the resulting status (typically {@code DONE} or {@code FAILED})
     * @param output       the output produced by the handler (may be empty)
     * @param errorMessage an error message if the task failed (may be empty)
     * @param durationMs   the execution duration in milliseconds
     */
    public TaskResult(String taskId, TaskStatus status, String output,
                      String errorMessage, long durationMs) {
        this.taskId = taskId;
        this.status = status;
        this.output = output;
        this.errorMessage = errorMessage;
        this.durationMs = durationMs;
    }

    /**
     * Serializes this result to a JSON object.
     *
     * @return a {@link JSONObject} representation of this result
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put(JSON_KEY_TASK_ID, taskId);
        json.put(JSON_KEY_STATUS, status.name());
        json.put(JSON_KEY_OUTPUT, output);
        json.put(JSON_KEY_ERROR_MESSAGE, errorMessage);
        json.put(JSON_KEY_DURATION_MS, durationMs);
        return json;
    }

    // --- Getters ---

    public String getTaskId() {
        return taskId;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public String getOutput() {
        return output;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getDurationMs() {
        return durationMs;
    }

    @Override
    public String toString() {
        return "TaskResult{" +
                "taskId='" + taskId + '\'' +
                ", status=" + status +
                ", output='" + output + '\'' +
                ", durationMs=" + durationMs +
                '}';
    }
}
