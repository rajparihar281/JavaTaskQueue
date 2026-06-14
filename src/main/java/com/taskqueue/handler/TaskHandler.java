package com.taskqueue.handler;

import com.taskqueue.model.Task;
import com.taskqueue.model.TaskResult;

/**
 * Strategy interface for task execution handlers.
 * <p>
 * Each implementation handles a specific task type (e.g., "ECHO", "SLEEP",
 * "COMPUTE"). Handlers are registered with the {@link TaskHandlerRegistry}
 * and invoked by worker threads when a matching task is dequeued.
 * </p>
 * <p>
 * Implementations must be thread-safe, as multiple worker threads may
 * invoke the same handler instance concurrently.
 * </p>
 */
@FunctionalInterface
public interface TaskHandler {

    /**
     * Executes the given task and returns a result.
     *
     * @param task the task to execute
     * @return a {@link TaskResult} describing the outcome
     */
    TaskResult execute(Task task);
}
