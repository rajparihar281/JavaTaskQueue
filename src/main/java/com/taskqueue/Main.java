package com.taskqueue;

import com.taskqueue.broker.TaskBroker;
import com.taskqueue.handler.*;
import com.taskqueue.model.Task;
import com.taskqueue.persistence.SQLiteTaskRepository;
import com.taskqueue.persistence.TaskRepository;
import com.taskqueue.scheduler.DelayedTaskScheduler;
import com.taskqueue.server.TCPServer;
import com.taskqueue.worker.WorkerPool;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Entry point for the JavaTaskQueue server application.
 * <p>
 * Wires together all components — persistence, broker, handlers, worker pool,
 * scheduler, and TCP server — and manages the application lifecycle including
 * graceful shutdown via a JVM shutdown hook.
 * </p>
 *
 * <p>Start the server:</p>
 * <pre>
 * java -jar target/JavaTaskQueue-jar-with-dependencies.jar
 * </pre>
 */
public class Main {

    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    private static final int SERVER_PORT = 9090;
    private static final int WORKER_THREAD_COUNT = 4;

    private static final String BANNER = """
            
            ╔═══════════════════════════════════════════════════════╗
            ║              JavaTaskQueue v1.0.0                    ║
            ║         Distributed Task Queue & Job Scheduler       ║
            ╚═══════════════════════════════════════════════════════╝
            """;

    private static final String HANDLER_TYPE_ECHO = "ECHO";
    private static final String HANDLER_TYPE_SLEEP = "SLEEP";
    private static final String HANDLER_TYPE_COMPUTE = "COMPUTE";

    /**
     * Application entry point.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {
        // 1. Create persistence layer
        TaskRepository repository = new SQLiteTaskRepository();

        // 2. Create task broker
        TaskBroker broker = new TaskBroker();

        // 3. Recover pending tasks from DB into broker
        List<Task> pendingTasks = repository.findPending();
        if (!pendingTasks.isEmpty()) {
            LOGGER.info(() -> "Recovering " + pendingTasks.size() + " tasks from database");
            for (Task task : pendingTasks) {
                broker.submit(task);
            }
        }

        // 4. Create and register handlers
        TaskHandlerRegistry registry = new TaskHandlerRegistry();
        registry.register(HANDLER_TYPE_ECHO, new EchoHandler());
        registry.register(HANDLER_TYPE_SLEEP, new SleepHandler());
        registry.register(HANDLER_TYPE_COMPUTE, new ComputeHandler());

        // 5. Create delayed task scheduler
        DelayedTaskScheduler scheduler = new DelayedTaskScheduler(broker);
        scheduler.start();

        // 6. Create and start worker pool
        WorkerPool workerPool = new WorkerPool(WORKER_THREAD_COUNT, broker, registry, repository, scheduler);
        workerPool.start();

        // 7. Create and start TCP server
        TCPServer tcpServer = new TCPServer(SERVER_PORT, broker, repository);
        tcpServer.setShutdownCallback(() -> {
            LOGGER.info("Shutdown command received, shutting down...");
            tcpServer.stop();
            workerPool.shutdown();
            scheduler.stop();
            LOGGER.info("All components shut down. Exiting.");
            System.exit(0);
        });

        try {
            tcpServer.start();
        } catch (IOException e) {
            LOGGER.severe("Failed to start TCP server on port " + SERVER_PORT + ": " + e.getMessage());
            workerPool.shutdown();
            scheduler.stop();
            return;
        }

        // 8. Register JVM shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("JVM shutdown hook triggered");
            tcpServer.stop();
            workerPool.shutdown();
            scheduler.stop();
        }, "ShutdownHook"));

        // 9. Print banner
        System.out.println(BANNER);
        System.out.println("  JavaTaskQueue started on port " + SERVER_PORT);
        System.out.println("  Workers: " + WORKER_THREAD_COUNT);
        System.out.println("  Recovered tasks: " + pendingTasks.size());
        System.out.println();
        System.out.println("  Use the client to connect:");
        System.out.println("  java -cp target/JavaTaskQueue-jar-with-dependencies.jar com.taskqueue.client.TaskQueueClient");
        System.out.println();

        // 10. Main thread blocks
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            LOGGER.info("Main thread interrupted");
            Thread.currentThread().interrupt();
        }
    }
}
