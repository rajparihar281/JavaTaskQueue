package com.taskqueue.handler;

import com.taskqueue.model.Priority;
import com.taskqueue.model.Task;
import com.taskqueue.model.TaskResult;
import com.taskqueue.model.TaskStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ComputeHandler}.
 * <p>
 * Tests cover factorial computation, primality checking, and malformed
 * payload handling.
 * </p>
 */
class ComputeHandlerTest {

    private ComputeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ComputeHandler();
    }

    @Test
    @DisplayName("FACTORIAL:10 should return 3628800")
    void testFactorial10() {
        Task task = new Task("COMPUTE", "FACTORIAL:10", Priority.NORMAL);
        TaskResult result = handler.execute(task);

        assertEquals(TaskStatus.DONE, result.getStatus());
        assertEquals("3628800", result.getOutput());
    }

    @Test
    @DisplayName("FACTORIAL:0 should return 1")
    void testFactorial0() {
        Task task = new Task("COMPUTE", "FACTORIAL:0", Priority.NORMAL);
        TaskResult result = handler.execute(task);

        assertEquals(TaskStatus.DONE, result.getStatus());
        assertEquals("1", result.getOutput());
    }

    @Test
    @DisplayName("FACTORIAL:20 should return correct large number")
    void testFactorial20() {
        Task task = new Task("COMPUTE", "FACTORIAL:20", Priority.NORMAL);
        TaskResult result = handler.execute(task);

        assertEquals(TaskStatus.DONE, result.getStatus());
        assertEquals("2432902008176640000", result.getOutput());
    }

    @Test
    @DisplayName("ISPRIME:97 should return true")
    void testIsPrime97() {
        Task task = new Task("COMPUTE", "ISPRIME:97", Priority.NORMAL);
        TaskResult result = handler.execute(task);

        assertEquals(TaskStatus.DONE, result.getStatus());
        assertEquals("true", result.getOutput());
    }

    @Test
    @DisplayName("ISPRIME:100 should return false")
    void testIsPrime100() {
        Task task = new Task("COMPUTE", "ISPRIME:100", Priority.NORMAL);
        TaskResult result = handler.execute(task);

        assertEquals(TaskStatus.DONE, result.getStatus());
        assertEquals("false", result.getOutput());
    }

    @Test
    @DisplayName("ISPRIME:2 should return true (smallest prime)")
    void testIsPrime2() {
        Task task = new Task("COMPUTE", "ISPRIME:2", Priority.NORMAL);
        TaskResult result = handler.execute(task);

        assertEquals(TaskStatus.DONE, result.getStatus());
        assertEquals("true", result.getOutput());
    }

    @Test
    @DisplayName("ISPRIME:1 should return false (1 is not prime)")
    void testIsPrime1() {
        Task task = new Task("COMPUTE", "ISPRIME:1", Priority.NORMAL);
        TaskResult result = handler.execute(task);

        assertEquals(TaskStatus.DONE, result.getStatus());
        assertEquals("false", result.getOutput());
    }

    @Test
    @DisplayName("Malformed payload without colon should return FAILED")
    void testMalformedPayloadNoColon() {
        Task task = new Task("COMPUTE", "GARBAGE", Priority.NORMAL);
        TaskResult result = handler.execute(task);

        assertEquals(TaskStatus.FAILED, result.getStatus());
        assertFalse(result.getErrorMessage().isEmpty(),
                "Error message should not be empty for malformed payload");
    }

    @Test
    @DisplayName("Malformed payload with invalid number should return FAILED")
    void testMalformedPayloadBadNumber() {
        Task task = new Task("COMPUTE", "FACTORIAL:abc", Priority.NORMAL);
        TaskResult result = handler.execute(task);

        assertEquals(TaskStatus.FAILED, result.getStatus());
    }

    @Test
    @DisplayName("Unknown compute command should return FAILED")
    void testUnknownCommand() {
        Task task = new Task("COMPUTE", "SQRT:16", Priority.NORMAL);
        TaskResult result = handler.execute(task);

        assertEquals(TaskStatus.FAILED, result.getStatus());
    }

    @Test
    @DisplayName("Null payload should return FAILED")
    void testNullPayload() {
        Task task = new Task("null-id", "COMPUTE", null, Priority.NORMAL,
                TaskStatus.PENDING, 0, System.currentTimeMillis(), 0, 0, 3);
        TaskResult result = handler.execute(task);

        assertEquals(TaskStatus.FAILED, result.getStatus());
    }
}
