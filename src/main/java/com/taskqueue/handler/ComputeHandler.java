package com.taskqueue.handler;

import com.taskqueue.model.Task;
import com.taskqueue.model.TaskResult;
import com.taskqueue.model.TaskStatus;

import java.math.BigInteger;
import java.util.logging.Logger;

/**
 * Handler for computational tasks: factorial and primality checking.
 * <p>
 * Parses the task payload as a command string in the format
 * {@code "COMMAND:ARGUMENT"}, where supported commands are:
 * <ul>
 *   <li>{@code FACTORIAL:N} — computes N! using {@link BigInteger}</li>
 *   <li>{@code ISPRIME:N} — checks whether N is a probable prime</li>
 * </ul>
 * Returns a {@link TaskResult} with the computed answer as output,
 * or a FAILED result if the payload is malformed.
 * </p>
 */
public class ComputeHandler implements TaskHandler {

    private static final Logger LOGGER = Logger.getLogger(ComputeHandler.class.getName());

    private static final String CMD_FACTORIAL = "FACTORIAL";
    private static final String CMD_ISPRIME = "ISPRIME";
    private static final String COMMAND_SEPARATOR = ":";

    /**
     * Executes the compute task based on the command in the payload.
     *
     * @param task the task containing the command payload
     * @return a {@link TaskResult} with the computed output or a failure message
     */
    @Override
    public TaskResult execute(Task task) {
        long start = System.currentTimeMillis();
        String payload = task.getPayload();

        if (payload == null || !payload.contains(COMMAND_SEPARATOR)) {
            long duration = System.currentTimeMillis() - start;
            return new TaskResult(task.getTaskId(), TaskStatus.FAILED,
                    "", "Malformed payload: expected COMMAND:ARG format", duration);
        }

        String[] parts = payload.split(COMMAND_SEPARATOR, 2);
        String command = parts[0].trim().toUpperCase();
        String argument = parts[1].trim();

        try {
            String result = switch (command) {
                case CMD_FACTORIAL -> computeFactorial(argument);
                case CMD_ISPRIME -> checkPrimality(argument);
                default -> throw new IllegalArgumentException("Unknown command: " + command);
            };

            long duration = System.currentTimeMillis() - start;
            return new TaskResult(task.getTaskId(), TaskStatus.DONE, result, "", duration);

        } catch (Exception e) {
            LOGGER.warning(() -> "ComputeHandler error for task " + task.getTaskId()
                    + ": " + e.getMessage());
            long duration = System.currentTimeMillis() - start;
            return new TaskResult(task.getTaskId(), TaskStatus.FAILED,
                    "", e.getMessage(), duration);
        }
    }

    /**
     * Computes the factorial of N using BigInteger arithmetic.
     *
     * @param argument the string representation of N
     * @return the factorial as a string
     */
    private String computeFactorial(String argument) {
        int n = Integer.parseInt(argument);
        if (n < 0) {
            throw new IllegalArgumentException("Factorial undefined for negative numbers: " + n);
        }
        BigInteger result = BigInteger.ONE;
        for (int i = 2; i <= n; i++) {
            result = result.multiply(BigInteger.valueOf(i));
        }
        return result.toString();
    }

    /**
     * Checks whether N is a prime number.
     *
     * @param argument the string representation of N
     * @return "true" if N is prime, "false" otherwise
     */
    private String checkPrimality(String argument) {
        long n = Long.parseLong(argument);
        if (n < 2) {
            return "false";
        }
        // Use BigInteger's probabilistic primality test with high certainty
        boolean isPrime = BigInteger.valueOf(n).isProbablePrime(20);
        return String.valueOf(isPrime);
    }
}
