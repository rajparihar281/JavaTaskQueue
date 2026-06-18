package com.taskqueue.worker;

import com.taskqueue.broker.TaskBroker;
import com.taskqueue.handler.TaskHandlerRegistry;
import com.taskqueue.model.Priority;
import com.taskqueue.model.Task;
import com.taskqueue.model.TaskResult;
import com.taskqueue.model.TaskStatus;
import com.taskqueue.persistence.TaskRepository;
import com.taskqueue.scheduler.DelayedTaskScheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WorkerPool}.
 * <p>
 * Tests verify that tasks are executed to completion and that the worker pool
 * recovers gracefully when a handler throws an exception.
 * </p>
 */
class WorkerPoolTest {

    private TaskBroker broker;
    private TaskHandlerRegistry registry;
    private DelayedTaskScheduler scheduler;
    private WorkerPool workerPool;
    private StubRepository repository;

    @BeforeEach
    void setUp() {
        broker = new TaskBroker();
        registry = new TaskHandlerRegistry();
        repository = new StubRepository();
        scheduler = new DelayedTaskScheduler(broker);
        scheduler.start();
        workerPool = new WorkerPool(2, broker, registry, repository, scheduler);
    }

    @AfterEach
    void tearDown() {
        workerPool.shutdown();
        scheduler.stop();
    }

    @Test
    @DisplayName("All submitted tasks should eventually reach DONE status")
    void testAllTasksReachDone() throws InterruptedException {
        int taskCount = 5;
        CountDownLatch latch = new CountDownLatch(taskCount);

        // Register a handler that counts down the latch
        registry.register("LATCH", task -> {
            latch.countDown();
            return new TaskResult(task.getTaskId(), TaskStatus.DONE,
                    "completed", "", 1L);
        });

        workerPool.start();

        for (int i = 0; i < taskCount; i++) {
            broker.submit(new Task("LATCH", "payload-" + i, Priority.NORMAL));
        }

        boolean allDone = latch.await(10, TimeUnit.SECONDS);
        assertTrue(allDone, "All " + taskCount + " tasks should complete within timeout");
    }

    @Test
    @DisplayName("Worker should recover after a handler throws an exception")
    void testRecoveryAfterHandlerException() throws InterruptedException {
        AtomicInteger execCount = new AtomicInteger(0);
        CountDownLatch successLatch = new CountDownLatch(1);

        // First task's handler throws, second succeeds
        registry.register("BOMB", task -> {
            int count = execCount.incrementAndGet();
            if (count == 1) {
                throw new RuntimeException("Handler exploded!");
            }
            successLatch.countDown();
            return new TaskResult(task.getTaskId(), TaskStatus.DONE,
                    "survived", "", 1L);
        });

        workerPool.start();

        // Submit task that will throw (with maxRetries=0 so it doesn't retry)
        Task badTask = new Task("bad-id", "BOMB", "payload1", Priority.HIGH,
                TaskStatus.PENDING, 0, System.currentTimeMillis(), 0, 0, 0);
        broker.submit(badTask);

        // Submit task that should succeed even after the exception
        Task goodTask = new Task("BOMB", "payload2", Priority.NORMAL);
        broker.submit(goodTask);

        boolean success = successLatch.await(10, TimeUnit.SECONDS);
        assertTrue(success, "Second task should complete after first task's handler threw");
    }

    @Test
    @DisplayName("isShutdown should reflect pool state")
    void testIsShutdown() {
        assertFalse(workerPool.isShutdown(), "Should not be shutdown initially");
        workerPool.start();
        assertFalse(workerPool.isShutdown(), "Should not be shutdown after start");
        workerPool.shutdown();
        assertTrue(workerPool.isShutdown(), "Should be shutdown after shutdown()");
    }

    /**
     * A minimal stub implementation of TaskRepository for testing.
     * Does not persist to any database.
     */
    private static class StubRepository implements TaskRepository {

        @Override
        public void save(Task task) {
            // No-op
        }

        @Override
        public void updateStatus(String taskId, TaskStatus status) {
            // No-op
        }

        @Override
        public void updateStatusAndResult(String taskId, TaskStatus status, TaskResult result) {
            // No-op
        }

        @Override
        public List<Task> findPending() {
            return List.of();
        }

        @Override
        public Optional<Task> findById(String taskId) {
            return Optional.empty();
        }

        @Override
        public List<Task> findAll() {
            return List.of();
        }

        @Override
        public void updateRetryInfo(String taskId, int retryCount, long nextRetryAt) {
            // No-op
        }

        @Override
        public void saveToDlq(Task task) {
            // No-op
        }

        @Override
        public List<Task> findAllDlq() {
            return List.of();
        }

        @Override
        public void deleteFromDlq(String taskId) {
            // No-op
        }
    }
}
