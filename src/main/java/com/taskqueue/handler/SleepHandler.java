package com.taskqueue.handler;

import com.taskqueue.model.Task;
import com.taskqueue.model.TaskResult;
import com.taskqueue.model.TaskStatus;

import java.util.logging.Logger;

/**
 * Handler that sleeps for a specified number of milliseconds.
 * <p>
 * Parses the task payload as an integer representing the sleep duration
 * in milliseconds. If the payload cannot be parsed, defaults to 1000ms.
 * Useful for testing task queue behavior with controlled execution times.
 * </p>
 */
public class SleepHandler implements TaskHandler {

    private static final Logger LOGGER = Logger.getLogger(SleepHandler.class.getName());
    private static final int DEFAULT_SLEEP_MS = 1000;

    /**
     * Executes the sleep task.
     *
     * @param task the task whose payload specifies the sleep duration in ms
     * @return a {@link TaskResult} with status {@code DONE} and a message
     *         indicating how long the handler slept
     */
    @Override
    public TaskResult execute(Task task) {
        long start = System.currentTimeMillis();
        int sleepMs = DEFAULT_SLEEP_MS;

        try {
            sleepMs = Integer.parseInt(task.getPayload().trim());
        } catch (NumberFormatException e) {
            LOGGER.warning(() -> "Unparseable sleep duration '" + task.getPayload()
                    + "', using default " + DEFAULT_SLEEP_MS + "ms");
        }

        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long duration = System.currentTimeMillis() - start;
            return new TaskResult(task.getTaskId(), TaskStatus.FAILED,
                    "", "Sleep interrupted", duration);
        }

        long duration = System.currentTimeMillis() - start;
        return new TaskResult(task.getTaskId(), TaskStatus.DONE,
                "Slept for " + sleepMs + "ms", "", duration);
    }
}
