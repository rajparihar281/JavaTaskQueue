package com.taskqueue.model;

import org.json.JSONObject;

import java.util.UUID;

/**
 * Represents a unit of work submitted to the task queue system.
 * <p>
 * Each task has a unique identifier, a type that determines which handler
 * processes it, a payload containing task-specific data, a priority level,
 * and various timestamps tracking its lifecycle. Tasks implement
 * {@link Comparable} to support priority-based ordering in the queue.
 * </p>
 *
 * <p>Ordering rules:</p>
 * <ol>
 *   <li>Higher priority first (lower {@link Priority#getWeight()} value)</li>
 *   <li>Earlier creation time first (FIFO within same priority)</li>
 * </ol>
 */
public class Task implements Comparable<Task> {

    private static final String JSON_KEY_TASK_ID = "taskId";
    private static final String JSON_KEY_TYPE = "type";
    private static final String JSON_KEY_PAYLOAD = "payload";
    private static final String JSON_KEY_PRIORITY = "priority";
    private static final String JSON_KEY_STATUS = "status";
    private static final String JSON_KEY_SCHEDULED_AT = "scheduledAt";
    private static final String JSON_KEY_CREATED_AT = "createdAt";
    private static final String JSON_KEY_COMPLETED_AT = "completedAt";
    private static final String JSON_KEY_RETRY_COUNT = "retryCount";
    private static final String JSON_KEY_MAX_RETRIES = "maxRetries";

    private final String taskId;
    private final String taskType;
    private final String payload;
    private Priority priority;
    private volatile TaskStatus status;
    private long scheduledAt;
    private final long createdAt;
    private long completedAt;
    private int retryCount;
    private final int maxRetries;

    /**
     * Creates a new task with the given type, payload, and priority.
     * A unique UUID is generated automatically, and the creation timestamp
     * is set to the current system time.
     *
     * @param taskType the type identifier that maps to a {@link com.taskqueue.handler.TaskHandler}
     * @param payload  the task-specific data string
     * @param priority the priority level for queue ordering
     */
    public Task(String taskType, String payload, Priority priority) {
        this(UUID.randomUUID().toString(), taskType, payload, priority,
                TaskStatus.PENDING, 0L, System.currentTimeMillis(), 0L, 0, 3);
    }

    /**
     * Full constructor for restoring a task from persistence or JSON.
     *
     * @param taskId      unique task identifier
     * @param taskType    the type identifier
     * @param payload     task-specific data
     * @param priority    priority level
     * @param status      current status
     * @param scheduledAt scheduled execution time (0 for immediate)
     * @param createdAt   creation timestamp
     * @param completedAt completion timestamp (0 if not completed)
     * @param retryCount  current retry attempt count
     * @param maxRetries  maximum number of retries allowed
     */
    public Task(String taskId, String taskType, String payload, Priority priority,
                TaskStatus status, long scheduledAt, long createdAt, long completedAt,
                int retryCount, int maxRetries) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.payload = payload;
        this.priority = priority;
        this.status = status;
        this.scheduledAt = scheduledAt;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.retryCount = retryCount;
        this.maxRetries = maxRetries;
    }

    /**
     * Serializes this task to a JSON object.
     *
     * @return a {@link JSONObject} representation of this task
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put(JSON_KEY_TASK_ID, taskId);
        json.put(JSON_KEY_TYPE, taskType);
        json.put(JSON_KEY_PAYLOAD, payload);
        json.put(JSON_KEY_PRIORITY, priority.name());
        json.put(JSON_KEY_STATUS, status.name());
        json.put(JSON_KEY_SCHEDULED_AT, scheduledAt);
        json.put(JSON_KEY_CREATED_AT, createdAt);
        json.put(JSON_KEY_COMPLETED_AT, completedAt);
        json.put(JSON_KEY_RETRY_COUNT, retryCount);
        json.put(JSON_KEY_MAX_RETRIES, maxRetries);
        return json;
    }

    /**
     * Deserializes a task from a JSON object.
     *
     * @param json the JSON object containing task data
     * @return a new {@link Task} instance
     */
    public static Task fromJson(JSONObject json) {
        String taskId = json.optString(JSON_KEY_TASK_ID, UUID.randomUUID().toString());
        String taskType = json.getString(JSON_KEY_TYPE);
        String payload = json.optString(JSON_KEY_PAYLOAD, "");
        Priority priority = Priority.valueOf(json.optString(JSON_KEY_PRIORITY, Priority.NORMAL.name()));
        TaskStatus status = TaskStatus.valueOf(json.optString(JSON_KEY_STATUS, TaskStatus.PENDING.name()));
        long scheduledAt = json.optLong(JSON_KEY_SCHEDULED_AT, 0L);
        long createdAt = json.optLong(JSON_KEY_CREATED_AT, System.currentTimeMillis());
        long completedAt = json.optLong(JSON_KEY_COMPLETED_AT, 0L);
        int retryCount = json.optInt(JSON_KEY_RETRY_COUNT, 0);
        int maxRetries = json.optInt(JSON_KEY_MAX_RETRIES, 3);

        return new Task(taskId, taskType, payload, priority, status,
                scheduledAt, createdAt, completedAt, retryCount, maxRetries);
    }

    /**
     * Compares tasks by priority weight first (lower weight = higher priority),
     * then by creation time (earlier = first).
     */
    @Override
    public int compareTo(Task other) {
        int priorityCompare = Integer.compare(this.priority.getWeight(), other.priority.getWeight());
        if (priorityCompare != 0) {
            return priorityCompare;
        }
        return Long.compare(this.createdAt, other.createdAt);
    }

    @Override
    public String toString() {
        return "Task{" +
                "taskId='" + taskId + '\'' +
                ", taskType='" + taskType + '\'' +
                ", status=" + status +
                ", priority=" + priority +
                '}';
    }

    // --- Getters and Setters ---

    public String getTaskId() {
        return taskId;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getPayload() {
        return payload;
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public long getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(long scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(long completedAt) {
        this.completedAt = completedAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }
}
