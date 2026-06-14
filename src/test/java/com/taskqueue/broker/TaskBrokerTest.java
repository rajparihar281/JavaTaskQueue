package com.taskqueue.broker;

import com.taskqueue.model.Priority;
import com.taskqueue.model.Task;
import com.taskqueue.model.TaskStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TaskBroker}.
 * <p>
 * Tests cover priority ordering, cancellation semantics, and thread safety
 * under concurrent access.
 * </p>
 */
class TaskBrokerTest {

    private TaskBroker broker;

    @BeforeEach
    void setUp() {
        broker = new TaskBroker();
    }

    @Test
    @DisplayName("Poll should return HIGH priority task before LOW priority task")
    void testPriorityOrdering() throws InterruptedException {
        Task lowTask = new Task("LOW_TYPE", "low payload", Priority.LOW);
        Task highTask = new Task("HIGH_TYPE", "high payload", Priority.HIGH);

        // Submit LOW first, then HIGH
        broker.submit(lowTask);
        broker.submit(highTask);

        // Poll should return HIGH first
        Task first = broker.poll(100, TimeUnit.MILLISECONDS);
        Task second = broker.poll(100, TimeUnit.MILLISECONDS);

        assertNotNull(first, "First polled task should not be null");
        assertNotNull(second, "Second polled task should not be null");
        assertEquals(Priority.HIGH, first.getPriority(), "First task should be HIGH priority");
        assertEquals(Priority.LOW, second.getPriority(), "Second task should be LOW priority");
    }

    @Test
    @DisplayName("Cancel should set task status to CANCELLED")
    void testCancelTask() {
        Task task = new Task("ECHO", "test", Priority.NORMAL);
        broker.submit(task);

        boolean cancelled = broker.cancel(task.getTaskId());

        assertTrue(cancelled, "Cancel should return true for PENDING task");

        var found = broker.findById(task.getTaskId());
        assertTrue(found.isPresent(), "Task should still be in index after cancel");
        assertEquals(TaskStatus.CANCELLED, found.get().getStatus(),
                "Cancelled task should have CANCELLED status");
    }

    @Test
    @DisplayName("Cancel should return false for non-existent task")
    void testCancelNonExistentTask() {
        boolean result = broker.cancel("non-existent-id");
        assertFalse(result, "Cancel should return false for non-existent task");
    }

    @Test
    @DisplayName("Cancel should return false for RUNNING task")
    void testCancelRunningTask() {
        Task task = new Task("ECHO", "test", Priority.NORMAL);
        task.setStatus(TaskStatus.RUNNING);
        broker.submit(task);

        boolean result = broker.cancel(task.getTaskId());
        assertFalse(result, "Cancel should return false for RUNNING task");
    }

    @Test
    @DisplayName("Thread safety: 10 threads submitting 100 tasks each should all be tracked")
    void testThreadSafety() throws InterruptedException {
        int threadCount = 10;
        int tasksPerThread = 100;
        int totalTasks = threadCount * tasksPerThread;
        CountDownLatch latch = new CountDownLatch(threadCount);

        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < tasksPerThread; i++) {
                        broker.submit(new Task("ECHO", "payload", Priority.NORMAL));
                    }
                } finally {
                    latch.countDown();
                }
            });
            threads.add(thread);
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all to complete
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "All threads should complete within timeout");

        // Verify all tasks are in the index
        List<Task> allTasks = broker.listAll();
        assertEquals(totalTasks, allTasks.size(),
                "All " + totalTasks + " tasks should be in listAll()");
    }

    @Test
    @DisplayName("Size should reflect current queue contents")
    void testSize() {
        assertEquals(0, broker.size(), "Empty broker should have size 0");

        broker.submit(new Task("ECHO", "a", Priority.NORMAL));
        broker.submit(new Task("ECHO", "b", Priority.NORMAL));

        assertEquals(2, broker.size(), "Broker should have size 2 after two submits");
    }

    @Test
    @DisplayName("findById should return empty Optional for unknown task")
    void testFindByIdNotFound() {
        var result = broker.findById("does-not-exist");
        assertTrue(result.isEmpty(), "Should return empty for unknown taskId");
    }
}
