package com.taskqueue.server;

import com.taskqueue.broker.TaskBroker;
import com.taskqueue.persistence.TaskRepository;
import com.taskqueue.worker.WorkerPool;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TCP server that accepts client connections and dispatches task queue commands.
 * <p>
 * Listens on a configurable port (default 9090) for newline-delimited commands.
 * Each incoming connection is handled by a {@link ClientHandler} in a cached
 * thread pool. The server runs in a daemon thread and can be stopped gracefully.
 * </p>
 */
public class TCPServer {

    private static final Logger LOGGER = Logger.getLogger(TCPServer.class.getName());

    private static final int DEFAULT_PORT = 9090;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final int port;
    private final TaskBroker broker;
    private final TaskRepository repository;
    private ServerSocket serverSocket;
    private ExecutorService clientPool;
    private Thread acceptThread;
    private volatile boolean running = false;
    private WorkerPool workerPool;

    /**
     * Callback interface for server shutdown events.
     */
    @FunctionalInterface
    public interface ShutdownCallback {
        /** Called when the SHUTDOWN command is received. */
        void onShutdown();
    }

    private ShutdownCallback shutdownCallback;

    /**
     * Creates a TCP server on the default port (9090).
     *
     * @param broker     the task broker
     * @param repository the persistence layer
     */
    public TCPServer(TaskBroker broker, TaskRepository repository) {
        this(DEFAULT_PORT, broker, repository);
    }

    /**
     * Creates a TCP server on the specified port.
     *
     * @param port       the port to listen on
     * @param broker     the task broker
     * @param repository the persistence layer
     */
    public TCPServer(int port, TaskBroker broker, TaskRepository repository) {
        this.port = port;
        this.broker = broker;
        this.repository = repository;
    }

    /**
     * Sets the callback invoked when the SHUTDOWN command is received.
     *
     * @param callback the shutdown callback
     */
    public void setShutdownCallback(ShutdownCallback callback) {
        this.shutdownCallback = callback;
    }

    /**
     * Sets the worker pool reference (used by SHUTDOWN command).
     *
     * @param workerPool the worker pool
     */
    public void setWorkerPool(WorkerPool workerPool) {
        this.workerPool = workerPool;
    }

    /**
     * Starts the TCP server in a daemon thread.
     *
     * @throws IOException if the server socket cannot be opened
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        clientPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        running = true;

        acceptThread = new Thread(this::acceptLoop, "TCPServer-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        LOGGER.info(() -> "TCPServer started on port " + port);
    }

    /**
     * Main accept loop — runs in a daemon thread, accepting connections
     * and dispatching them to the client handler pool.
     */
    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                LOGGER.info(() -> "Client connected: " + clientSocket.getRemoteSocketAddress());
                clientPool.submit(new ClientHandler(clientSocket, broker, repository, this));
            } catch (IOException e) {
                if (running) {
                    LOGGER.log(Level.WARNING, "Error accepting client connection", e);
                }
                // If not running, the socket was closed intentionally during shutdown
            }
        }
    }

    /**
     * Stops the TCP server gracefully, closing the server socket and
     * terminating the client thread pool.
     */
    public void stop() {
        if (!running) {
            return;
        }
        running = false;

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error closing server socket", e);
        }

        if (clientPool != null) {
            clientPool.shutdown();
            try {
                if (!clientPool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    clientPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                clientPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        LOGGER.info("TCPServer stopped");
    }

    /**
     * Triggers a full system shutdown via the registered callback.
     */
    public void triggerShutdown() {
        if (shutdownCallback != null) {
            new Thread(() -> shutdownCallback.onShutdown(), "ShutdownTrigger").start();
        }
    }

    /**
     * Returns whether the server is currently running.
     *
     * @return {@code true} if the server is accepting connections
     */
    public boolean isRunning() {
        return running;
    }
}
