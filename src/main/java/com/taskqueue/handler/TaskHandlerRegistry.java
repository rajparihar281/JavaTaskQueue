package com.taskqueue.handler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Registry that maps task type strings to their corresponding {@link TaskHandler}
 * implementations.
 * <p>
 * This registry uses a {@link ConcurrentHashMap} internally, making it safe
 * for concurrent registration and lookup. If a handler for a requested task
 * type is not found, a default {@link EchoHandler} is returned — the registry
 * never throws an exception for unknown types.
 * </p>
 */
public class TaskHandlerRegistry {

    private static final Logger LOGGER = Logger.getLogger(TaskHandlerRegistry.class.getName());

    private final ConcurrentHashMap<String, TaskHandler> handlers;
    private final TaskHandler defaultHandler;

    /**
     * Creates a new registry with an {@link EchoHandler} as the default
     * fallback handler.
     */
    public TaskHandlerRegistry() {
        this.handlers = new ConcurrentHashMap<>();
        this.defaultHandler = new EchoHandler();
    }

    /**
     * Registers a handler for the given task type.
     * Overwrites any previously registered handler for the same type.
     *
     * @param taskType the task type identifier (case-sensitive)
     * @param handler  the handler implementation
     */
    public void register(String taskType, TaskHandler handler) {
        handlers.put(taskType, handler);
        LOGGER.info(() -> "Registered handler for task type: " + taskType);
    }

    /**
     * Returns the handler registered for the given task type, or a default
     * {@link EchoHandler} if no handler is registered for that type.
     *
     * @param taskType the task type identifier
     * @return the registered handler, or the default handler
     */
    public TaskHandler getHandler(String taskType) {
        TaskHandler handler = handlers.get(taskType);
        if (handler == null) {
            LOGGER.warning(() -> "No handler registered for type '" + taskType
                    + "', using default EchoHandler");
            return defaultHandler;
        }
        return handler;
    }
}
