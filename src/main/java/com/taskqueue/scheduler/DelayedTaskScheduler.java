package com.taskqueue.scheduler;

import com.taskqueue.broker.TaskBroker;
import com.taskqueue.model.Task;

import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Scheduler for tasks with delayed execution.
 * <p>
 * Maintains an internal {@link DelayQueue} of tasks that are not yet ready
 * for processing. A background thread polls the delay queue every 200ms
 * and moves eligible tasks (whose {@code scheduledAt} time has passed)
 * into the main broker queue for processing.
 * </p>
 * <p>
 * This is used for:
 * <ul>
 *   <li>Tasks submitted with a future {@code scheduledAt} timestamp</li>
 *   <li>Tasks undergoing exponential backoff retries</li>
 * </ul>
 * </p>
 */
public class DelayedTaskScheduler {

    private static final Logger LOGGER = Logger.getLogger(DelayedTaskScheduler.class.getName());
    private static final long SCAN_INTERVAL_MS = 200L;

    private final TaskBroker broker;
    private final ConcurrentLinkedQueue<Task> delayedTasks;
    private ScheduledExecutorService schedulerExecutor;
    private volatile boolean running = false;

    /**
     * Creates a new delayed task scheduler.
     *
     * @param broker the broker to submit ready tasks to
     */
    public DelayedTaskScheduler(TaskBroker broker) {
        this.broker = broker;
        this.delayedTasks = new ConcurrentLinkedQueue<>();
    }

    /**
     * Adds a task to the delayed queue. The task will be moved to the
     * broker when its {@code scheduledAt} time arrives.
     *
     * @param task the task to schedule for delayed execution
     */
    public void scheduleTask(Task task) {
        delayedTasks.offer(task);
        LOGGER.fine(() -> "Task scheduled for delayed execution: " + task.getTaskId()
                + " at " + task.getScheduledAt());
    }

    /**
     * Starts the scheduler background thread that scans for ready tasks.
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        schedulerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DelayedTaskScheduler");
            t.setDaemon(true);
            return t;
        });

        schedulerExecutor.scheduleAtFixedRate(this::scanAndPromote,
                SCAN_INTERVAL_MS, SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);

        LOGGER.info("DelayedTaskScheduler started (scan interval: " + SCAN_INTERVAL_MS + "ms)");
    }

    /**
     * Stops the scheduler and its background thread.
     */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;

        if (schedulerExecutor != null) {
            schedulerExecutor.shutdown();
            try {
                if (!schedulerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    schedulerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                schedulerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        LOGGER.info("DelayedTaskScheduler stopped");
    }

    /**
     * Scans the delayed task queue and promotes ready tasks to the broker.
     * A task is ready when its {@code scheduledAt} time is in the past
     * or equal to the current time.
     */
    private void scanAndPromote() {
        long now = System.currentTimeMillis();
        ConcurrentLinkedQueue<Task> notReady = new ConcurrentLinkedQueue<>();

        Task task;
        while ((task = delayedTasks.poll()) != null) {
            if (task.getScheduledAt() <= now) {
                final Task readyTask = task;
                LOGGER.fine(() -> "Promoting delayed task to broker: " + readyTask.getTaskId());
                broker.submit(readyTask);
            } else {
                notReady.offer(task);
            }
        }

        // Put back tasks that aren't ready yet
        Task remaining;
        while ((remaining = notReady.poll()) != null) {
            delayedTasks.offer(remaining);
        }
    }
}
