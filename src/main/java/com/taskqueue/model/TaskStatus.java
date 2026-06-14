package com.taskqueue.model;

/**
 * Represents the lifecycle status of a task within the task queue system.
 * <p>
 * A task progresses through these states:
 * <ul>
 *   <li>{@code PENDING} — Submitted and waiting to be picked up by a worker</li>
 *   <li>{@code RUNNING} — Currently being executed by a worker thread</li>
 *   <li>{@code DONE} — Completed successfully</li>
 *   <li>{@code FAILED} — Execution failed (may be retried if retries remain)</li>
 *   <li>{@code CANCELLED} — Cancelled before or during execution</li>
 * </ul>
 * </p>
 */
public enum TaskStatus {

    /** Task is waiting in the queue to be picked up. */
    PENDING,

    /** Task is currently being executed by a worker. */
    RUNNING,

    /** Task completed successfully. */
    DONE,

    /** Task execution failed. */
    FAILED,

    /** Task was cancelled by the user or system. */
    CANCELLED
}
