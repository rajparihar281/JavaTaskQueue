package com.taskqueue.worker;

import com.taskqueue.broker.TaskBroker;
import com.taskqueue.handler.TaskHandlerRegistry;
import com.taskqueue.model.Task;
import com.taskqueue.model.TaskStatus;
import com.taskqueue.persistence.TaskRepository;
import com.taskqueue.scheduler.DelayedTaskScheduler;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Manages a pool of worker threads that process tasks from the broker.
 * <p>
 * The pool creates a fixed number of {@link WorkerThread} instances and
 * runs them in an {@link ExecutorService}. On shutdown, remaining PENDING
 * tasks in the broker are cancelled, and the executor is given a grace
 * period before forceful termination.
 * </p>
 */
public class WorkerPool {

    private static final Logger LOGGER = Logger.getLogger(WorkerPool.class.getName());

    private static final int DEFAULT_THREAD_COUNT = 4;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10L;

    private final int threadCount;
    private final TaskBroker broker;
    private final TaskHandlerRegistry registry;
    private final TaskRepository repository;
    private final DelayedTaskScheduler scheduler;
    private ExecutorService executor;
    private volatile boolean shutdown = false;

    /**
     * Creates a worker pool with the default thread count (4).
     *
     * @param broker     the task broker
     * @param registry   the handler registry
     * @param repository the persistence layer
     * @param scheduler  the delayed task scheduler
     */
    public WorkerPool(TaskBroker broker, TaskHandlerRegistry registry,
                      TaskRepository repository, DelayedTaskScheduler scheduler) {
        this(DEFAULT_THREAD_COUNT, broker, registry, repository, scheduler);
    }

    /**
     * Creates a worker pool with a configurable thread count.
     *
     * @param threadCount number of worker threads to run
     * @param broker      the task broker
     * @param registry    the handler registry
     * @param repository  the persistence layer
     * @param scheduler   the delayed task scheduler
     */
    public WorkerPool(int threadCount, TaskBroker broker, TaskHandlerRegistry registry,
                      TaskRepository repository, DelayedTaskScheduler scheduler) {
        this.threadCount = threadCount;
        this.broker = broker;
        this.registry = registry;
        this.repository = repository;
        this.scheduler = scheduler;
    }

    /**
     * Starts the worker pool by launching all worker threads.
     */
    public void start() {
        executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            String workerName = "Worker-" + (i + 1);
            executor.submit(new WorkerThread(broker, registry, repository, scheduler, workerName));
        }
        LOGGER.info(() -> "WorkerPool started with " + threadCount + " threads");
    }

    /**
     * Shuts down the worker pool gracefully.
     * <ol>
     *   <li>Cancels all remaining PENDING tasks in the broker</li>
     *   <li>Initiates orderly shutdown of the executor</li>
     *   <li>Waits up to 10 seconds for running tasks to complete</li>
     *   <li>Forces shutdown if tasks are still running</li>
     * </ol>
     */
    public void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        LOGGER.info("WorkerPool shutting down...");

        // Cancel remaining pending tasks
        List<Task> allTasks = broker.listAll();
        for (Task task : allTasks) {
            if (task.getStatus() == TaskStatus.PENDING) {
                broker.cancel(task.getTaskId());
            }
        }

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    LOGGER.warning("WorkerPool did not terminate in time, forcing shutdown");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        LOGGER.info("WorkerPool shut down");
    }

    /**
     * Returns whether the worker pool has been shut down.
     *
     * @return {@code true} if shutdown has been initiated
     */
    public boolean isShutdown() {
        return shutdown;
    }
}
