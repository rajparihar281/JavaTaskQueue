package com.taskqueue.persistence;

import com.taskqueue.model.Task;
import com.taskqueue.model.TaskResult;
import com.taskqueue.model.TaskStatus;

import java.util.List;
import java.util.Optional;

/**
 * Data access interface for persisting and retrieving tasks.
 * <p>
 * Implementations provide durable storage for task state, enabling
 * crash recovery and status auditing. The task queue system uses this
 * interface to persist tasks on submission, update their status during
 * execution, and recover incomplete tasks on startup.
 * </p>
 */
public interface TaskRepository {

    /**
     * Saves a new task to persistent storage.
     *
     * @param task the task to persist
     */
    void save(Task task);

    /**
     * Updates the status of an existing task.
     *
     * @param taskId the ID of the task to update
     * @param status the new status
     */
    void updateStatus(String taskId, TaskStatus status);

    /**
     * Updates the status of a task and stores its execution result.
     *
     * @param taskId the ID of the task to update
     * @param status the new status
     * @param result the execution result containing output and error details
     */
    void updateStatusAndResult(String taskId, TaskStatus status, TaskResult result);

    /**
     * Returns all tasks that are in a recoverable state (PENDING or RUNNING).
     * Used for crash recovery on startup.
     *
     * @return a list of tasks with PENDING or RUNNING status
     */
    List<Task> findPending();

    /**
     * Looks up a task by its unique identifier.
     *
     * @param taskId the task ID to search for
     * @return an {@link Optional} containing the task if found
     */
    Optional<Task> findById(String taskId);

    /**
     * Returns all tasks in the repository.
     *
     * @return a list of all persisted tasks
     */
    List<Task> findAll();

    /**
     * Atomically updates the retry-related fields of a task after a failed
     * execution attempt.
     *
     * @param taskId      the ID of the task to update
     * @param retryCount  the new retry count
     * @param nextRetryAt the epoch-ms timestamp at which the next retry is scheduled
     */
    void updateRetryInfo(String taskId, int retryCount, long nextRetryAt);

    /**
     * Writes a task to the dead-letter queue table.
     * Called when a task has exhausted all retry attempts.
     *
     * @param task the task to move to the dead-letter queue
     */
    void saveToDlq(Task task);

    /**
     * Returns all tasks currently in the dead-letter queue.
     *
     * @return a list of all dead-letter tasks
     */
    List<Task> findAllDlq();

    /**
     * Removes a task from the dead-letter queue.
     * Used by the REPLAY command when re-enqueuing a dead task.
     *
     * @param taskId the ID of the dead-letter task to remove
     */
    void deleteFromDlq(String taskId);
}
