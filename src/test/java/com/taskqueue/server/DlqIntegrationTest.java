package com.taskqueue.server;

import com.taskqueue.broker.TaskBroker;
import com.taskqueue.handler.TaskHandlerRegistry;
import com.taskqueue.model.Priority;
import com.taskqueue.model.Task;
import com.taskqueue.model.TaskStatus;
import com.taskqueue.persistence.SQLiteTaskRepository;
import com.taskqueue.scheduler.DelayedTaskScheduler;
import com.taskqueue.worker.WorkerPool;

import org.json.JSONObject;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Dead Letter Queue (DLQ) pipeline.
 *
 * <p>These tests spin up a real in-memory SQLite database, a real broker,
 * worker pool, and scheduler — they exercise the full retry-to-DLQ path
 * and the REPLAY TCP command end-to-end via {@link ClientHandler#processCommand}.</p>
 *
 * <p>Because exponential backoff is used (2^n × 1000 ms), a task with
 * {@code maxRetries=3} requires up to ~14 s total backoff (2 + 4 + 8 s).
 * All polling loops use a 30 s hard deadline.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DlqIntegrationTest {

    /** Task type whose handler always throws, driving the task into the DLQ. */
    private static final String BROKEN_TYPE = "BROKEN";

    /** Shared infrastructure — initialised once for both ordered tests. */
    private static SQLiteTaskRepository repository;
    private static TaskBroker broker;
    private static WorkerPool workerPool;
    private static DelayedTaskScheduler scheduler;

    /**
     * Task ID set by test (a) and consumed by test (b).
     */
    private static String brokenTaskId;

    // -----------------------------------------------------------------------
    // Suite lifecycle
    // -----------------------------------------------------------------------

    @BeforeAll
    static void setUpSuite() {
        // In-memory SQLite — fully isolated, no leftover state between runs
        repository = new SQLiteTaskRepository(":memory:");

        broker = new TaskBroker();

        TaskHandlerRegistry registry = new TaskHandlerRegistry();
        // Register a handler that always throws to force the retry→DLQ path
        registry.register(BROKEN_TYPE,
                task -> { throw new RuntimeException("Simulated permanent failure"); });

        scheduler = new DelayedTaskScheduler(broker);
        scheduler.start();

        workerPool = new WorkerPool(1, broker, registry, repository, scheduler);
        workerPool.start();
    }

    @AfterAll
    static void tearDownSuite() {
        if (!workerPool.isShutdown()) {
            workerPool.shutdown();
        }
        scheduler.stop();
    }

    // -----------------------------------------------------------------------
    // Test (a): task exhausts retries → DEAD + present in DLQ
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("(a) Task exhausting maxRetries ends up DEAD and in the dead-letter queue")
    void testTaskExhaustsRetriesAndMovesToDlq() throws InterruptedException {
        // Build the task with maxRetries=3 (system default)
        Task task = new Task(
                UUID.randomUUID().toString(),
                BROKEN_TYPE,
                "dlq-test-payload",
                Priority.NORMAL,
                TaskStatus.PENDING,
                0L,
                System.currentTimeMillis(),
                0L,
                0,   // retryCount starts at 0
                3    // maxRetries = default 3
        );
        brokenTaskId = task.getTaskId();

        repository.save(task);
        broker.submit(task);

        // Backoff schedule: retry-1 = 2s, retry-2 = 4s, retry-3 = 8s → ~14s total
        // Poll every 500 ms with a 30 s hard deadline
        long deadline = System.currentTimeMillis() + 30_000;
        TaskStatus finalStatus = null;

        while (System.currentTimeMillis() < deadline) {
            Optional<Task> found = repository.findById(brokenTaskId);
            if (found.isPresent()) {
                finalStatus = found.get().getStatus();
                if (finalStatus == TaskStatus.DEAD) {
                    break;
                }
            }
            Thread.sleep(500);
        }

        // (1) Live tasks table must show DEAD
        assertEquals(TaskStatus.DEAD, finalStatus,
                "Task should reach DEAD status after exhausting all retries");

        // (2) Task must appear in the dead-letter queue
        List<Task> dlq = repository.findAllDlq();
        boolean foundInDlq = dlq.stream()
                .anyMatch(t -> brokenTaskId.equals(t.getTaskId()));
        assertTrue(foundInDlq,
                "Task should be present in the dead-letter queue after retry exhaustion");
    }

    // -----------------------------------------------------------------------
    // Test (b): REPLAY resets retryCount and moves task back to live queue
    // -----------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("(b) REPLAY moves dead-letter task back to live queue with retryCount=0")
    void testReplayMovesDlqTaskToLiveQueue() {
        assertNotNull(brokenTaskId,
                "brokenTaskId must be set by test (a) — run tests in order");

        // Verify the task is still in the DLQ before replay
        List<Task> dlqBefore = repository.findAllDlq();
        assertTrue(dlqBefore.stream().anyMatch(t -> brokenTaskId.equals(t.getTaskId())),
                "Task should still be in DLQ before REPLAY is called");

        // Stop the worker pool before calling REPLAY so the re-enqueued task
        // cannot be immediately picked up and mutated before our assertions.
        workerPool.shutdown();

        // Exercise REPLAY via ClientHandler.processCommand (package-private)
        // A null socket is safe here because handleReplay never reads from the socket;
        // it only calls repository and broker methods.
        StubTCPServer stubServer = new StubTCPServer();
        ClientHandler clientHandler = new ClientHandler(null, broker, repository, stubServer);

        String response = clientHandler.processCommand("REPLAY " + brokenTaskId);

        // Response must be {"status":"ok","taskId":"<id>"}
        JSONObject json = new JSONObject(response);
        assertEquals("ok", json.optString("status"),
                "REPLAY response should have status=ok; got: " + response);

        // (1) Task must no longer be in the DLQ
        List<Task> dlqAfter = repository.findAllDlq();
        assertFalse(dlqAfter.stream().anyMatch(t -> brokenTaskId.equals(t.getTaskId())),
                "Task should be removed from the DLQ after REPLAY");

        // (2) Task must be back in the live table with retryCount=0 and status=PENDING.
        // The worker pool is shut down, so the task cannot be picked up before we assert.
        Optional<Task> requeued = repository.findById(brokenTaskId);
        assertTrue(requeued.isPresent(),
                "Replayed task should be present in the live tasks table");
        assertEquals(0, requeued.get().getRetryCount(),
                "Replayed task retryCount must be reset to 0");
        assertEquals(TaskStatus.PENDING, requeued.get().getStatus(),
                "Replayed task status must be PENDING");
    }

    // -----------------------------------------------------------------------
    // Stub helpers
    // -----------------------------------------------------------------------

    /**
     * Minimal {@link TCPServer} stub that lets {@link ClientHandler} be
     * constructed without binding a real server socket.
     */
    private static class StubTCPServer extends TCPServer {
        StubTCPServer() {
            // Port 0 — socket is never actually opened because we don't call start()
            super(0, broker, repository);
        }

        @Override
        public void triggerShutdown() {
            // No-op for tests
        }
    }
}
