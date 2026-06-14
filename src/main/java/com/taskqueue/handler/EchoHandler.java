package com.taskqueue.handler;

import com.taskqueue.model.Task;
import com.taskqueue.model.TaskResult;
import com.taskqueue.model.TaskStatus;

/**
 * A simple handler that echoes the task payload back as output.
 * <p>
 * This handler serves as the default fallback when no specific handler
 * is registered for a given task type. It prints the payload to stdout
 * and returns a successful result containing the original payload.
 * </p>
 */
public class EchoHandler implements TaskHandler {

    private static final String ECHO_PREFIX = "[ECHO] ";

    /**
     * Executes the echo task by printing the payload and returning it as output.
     *
     * @param task the task to execute
     * @return a {@link TaskResult} with status {@code DONE} and the payload as output
     */
    @Override
    public TaskResult execute(Task task) {
        long start = System.currentTimeMillis();
        String payload = task.getPayload();

        System.out.println(ECHO_PREFIX + payload);

        long duration = System.currentTimeMillis() - start;
        return new TaskResult(task.getTaskId(), TaskStatus.DONE, payload, "", duration);
    }
}
