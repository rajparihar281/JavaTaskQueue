package com.taskqueue.worker;

import com.taskqueue.broker.TaskBroker;
import com.taskqueue.handler.TaskHandlerRegistry;
import com.taskqueue.model.Task;
import com.taskqueue.model.TaskResult;
import com.taskqueue.model.TaskStatus;
import com.taskqueue.persistence.TaskRepository;
import com.taskqueue.scheduler.DelayedTaskScheduler;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Worker thread that continuously polls the broker for tasks and executes them.
 * <p>
 * Each worker runs in an infinite loop: poll the broker for a task (with a 500ms
 * timeout), look up the appropriate handler from the registry, execute the task,
 * and update the task status in both the broker index and the persistence layer.
 * </p>
 * <p>
 * On failure, if the task has remaining retries, the worker increments the retry
 * count, applies exponential backoff delay, and resubmits the task through the
 * delayed task scheduler. All exceptions are caught to prevent a bad handler
 * from killing the worker thread.
 * </p>
 */
public class WorkerThread implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(WorkerThread.class.getName());

    private static final long POLL_TIMEOUT_MS = 500L;
    private static final long BACKOFF_BASE_MS = 1000L;

    private final TaskBroker broker;
    private final TaskHandlerRegistry registry;
    private final TaskRepository repository;
    private final DelayedTaskScheduler scheduler;
    private final String workerName;

    /**
     * Creates a new worker thread.
     *
     * @param broker     the task broker to poll for tasks
     * @param registry   the handler registry for task type dispatch
     * @param repository the persistence layer for status updates
     * @param scheduler  the delayed task scheduler for retry backoff
     * @param workerName a human-readable name for logging
     */
    public WorkerThread(TaskBroker broker, TaskHandlerRegistry registry,
                        TaskRepository repository, DelayedTaskScheduler scheduler,
                        String workerName) {
        this.broker = broker;
        this.registry = registry;
        this.repository = repository;
        this.scheduler = scheduler;
        this.workerName = workerName;
    }

    @Override
    public void run() {
        LOGGER.info(() -> workerName + " started");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                Task task = broker.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (task == null) {
                    continue;
                }

                // Skip cancelled tasks
                if (task.getStatus() == TaskStatus.CANCELLED) {
                    LOGGER.fine(() -> workerName + " skipping cancelled task: " + task.getTaskId());
                    continue;
                }

                // Skip tasks that are scheduled for the future
                if (task.getScheduledAt() > 0 && task.getScheduledAt() > System.currentTimeMillis()) {
                    // Put it back — the scheduler will pick it up
                    scheduler.scheduleTask(task);
                    continue;
                }

                executeTask(task);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.info(() -> workerName + " interrupted, shutting down");
                break;
            } catch (Throwable t) {
                // Catch everything — a bad handler must never kill the worker thread
                LOGGER.log(Level.SEVERE, workerName + " caught unexpected error", t);
            }
        }

        LOGGER.info(() -> workerName + " stopped");
    }

    /**
     * Executes a single task: updates status to RUNNING, invokes the handler,
     * and updates the final status based on the result.
     */
    private void executeTask(Task task) {
        LOGGER.info(() -> workerName + " executing task: " + task.getTaskId()
                + " [" + task.getTaskType() + "]");

        // Mark as RUNNING
        task.setStatus(TaskStatus.RUNNING);
        repository.updateStatus(task.getTaskId(), TaskStatus.RUNNING);

        TaskResult result;
        try {
            result = registry.getHandler(task.getTaskType()).execute(task);
        } catch (Throwable t) {
            // Handler threw an uncaught exception
            result = new TaskResult(task.getTaskId(), TaskStatus.FAILED,
                    "", t.getMessage(), 0L);
        }

        if (result.getStatus() == TaskStatus.DONE) {
            task.setStatus(TaskStatus.DONE);
            task.setCompletedAt(System.currentTimeMillis());
            repository.updateStatusAndResult(task.getTaskId(), TaskStatus.DONE, result);
            LOGGER.info(() -> workerName + " completed task: " + task.getTaskId());
        } else {
            handleFailure(task, result);
        }
    }

    /**
     * Handles a failed task execution. If retries remain, the task is
     * resubmitted with exponential backoff and its retry metadata is persisted.
     * If all retries are exhausted, the task is written to the dead-letter queue
     * and marked {@link TaskStatus#DEAD} in the live tasks table.
     */
    private void handleFailure(Task task, TaskResult result) {
        if (task.getRetryCount() < task.getMaxRetries()) {
            int newRetryCount = task.getRetryCount() + 1;
            task.setRetryCount(newRetryCount);
            task.setStatus(TaskStatus.PENDING);

            // Exponential backoff: 2^retryCount * 1000ms
            long backoffMs = (long) Math.pow(2, newRetryCount) * BACKOFF_BASE_MS;
            long nextRetryAt = System.currentTimeMillis() + backoffMs;
            task.setScheduledAt(nextRetryAt);
            task.setNextRetryAt(nextRetryAt);

            LOGGER.info(() -> workerName + " retrying task " + task.getTaskId()
                    + " (attempt " + newRetryCount + "/" + task.getMaxRetries()
                    + ", backoff " + backoffMs + "ms)");

            repository.updateRetryInfo(task.getTaskId(), newRetryCount, nextRetryAt);
            repository.updateStatus(task.getTaskId(), TaskStatus.PENDING);
            scheduler.scheduleTask(task);
        } else {
            // All retries exhausted — move to dead-letter queue.
            // saveToDlq must succeed before we mark the live record DEAD; if
            // saveToDlq throws, the task stays FAILED so it is never silently lost.
            task.setStatus(TaskStatus.DEAD);
            task.setCompletedAt(System.currentTimeMillis());
            try {
                repository.saveToDlq(task);
                repository.updateStatus(task.getTaskId(), TaskStatus.DEAD);
                LOGGER.warning(() -> workerName + " task moved to DLQ: " + task.getTaskId()
                        + " — " + result.getErrorMessage());
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, workerName + " failed to write task to DLQ: "
                        + task.getTaskId() + "; task remains FAILED", e);
            }
        }
    }
}
