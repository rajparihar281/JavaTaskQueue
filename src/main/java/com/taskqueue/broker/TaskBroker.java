package com.taskqueue.broker;

import com.taskqueue.model.Task;
import com.taskqueue.model.TaskStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Central message broker that manages the in-memory task queue.
 * <p>
 * The broker is backed by a {@link PriorityBlockingQueue} which orders tasks
 * by their natural ordering (priority weight, then creation time). A
 * {@link ConcurrentHashMap} serves as a fast lookup index for task status
 * queries by ID.
 * </p>
 * <p>
 * All public methods are thread-safe and may be called concurrently from
 * worker threads, the TCP server, and the scheduler without external
 * synchronization.
 * </p>
 */
public class TaskBroker {

    private static final Logger LOGGER = Logger.getLogger(TaskBroker.class.getName());
    private static final int INITIAL_QUEUE_CAPACITY = 64;

    private final PriorityBlockingQueue<Task> queue;
    private final ConcurrentHashMap<String, Task> taskIndex;

    /**
     * Creates a new TaskBroker with an empty queue and index.
     */
    public TaskBroker() {
        this.queue = new PriorityBlockingQueue<>(INITIAL_QUEUE_CAPACITY);
        this.taskIndex = new ConcurrentHashMap<>();
    }

    /**
     * Submits a task to the broker queue and registers it in the status index.
     *
     * @param task the task to submit; must not be {@code null}
     */
    public void submit(Task task) {
        taskIndex.put(task.getTaskId(), task);
        queue.offer(task);
        LOGGER.fine(() -> "Task submitted: " + task.getTaskId() + " [" + task.getTaskType() + "]");
    }

    /**
     * Polls the queue for the next available task, blocking up to the
     * specified timeout.
     *
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout
     * @return the highest-priority task, or {@code null} if the timeout elapsed
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public Task poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    /**
     * Looks up a task by its unique identifier.
     *
     * @param taskId the task ID to search for
     * @return an {@link Optional} containing the task if found, or empty
     */
    public Optional<Task> findById(String taskId) {
        return Optional.ofNullable(taskIndex.get(taskId));
    }

    /**
     * Returns a snapshot list of all tasks known to the broker.
     *
     * @return an unmodifiable snapshot of all tasks in the index
     */
    public List<Task> listAll() {
        return new ArrayList<>(taskIndex.values());
    }

    /**
     * Attempts to cancel a task. Only tasks in {@link TaskStatus#PENDING}
     * status can be cancelled. Tasks that are already {@code RUNNING},
     * {@code DONE}, {@code FAILED}, or {@code CANCELLED} cannot be cancelled.
     *
     * @param taskId the ID of the task to cancel
     * @return {@code true} if the task was successfully cancelled,
     *         {@code false} if it was not found or is not in PENDING status
     */
    public boolean cancel(String taskId) {
        Task task = taskIndex.get(taskId);
        if (task == null) {
            LOGGER.warning(() -> "Cancel requested for unknown task: " + taskId);
            return false;
        }
        synchronized (task) {
            if (task.getStatus() == TaskStatus.PENDING) {
                task.setStatus(TaskStatus.CANCELLED);
                queue.remove(task);
                LOGGER.info(() -> "Task cancelled: " + taskId);
                return true;
            }
        }
        LOGGER.info(() -> "Cannot cancel task " + taskId + " in status " + task.getStatus());
        return false;
    }

    /**
     * Returns the current number of tasks waiting in the queue.
     *
     * @return the queue size
     */
    public int size() {
        return queue.size();
    }
}
