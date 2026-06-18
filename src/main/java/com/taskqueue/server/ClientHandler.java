package com.taskqueue.server;

import com.taskqueue.broker.TaskBroker;
import com.taskqueue.model.Task;
import com.taskqueue.model.TaskStatus;
import com.taskqueue.persistence.TaskRepository;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles a single client TCP connection.
 * <p>
 * Reads newline-delimited commands from the client socket, parses and
 * dispatches them against the task broker and repository, and writes
 * JSON responses back to the client. Supported commands:
 * <ul>
 *   <li>{@code SUBMIT <json>} — submit a new task</li>
 *   <li>{@code STATUS <taskId>} — query task status</li>
 *   <li>{@code LIST} — list all tasks</li>
 *   <li>{@code CANCEL <taskId>} — cancel a pending task</li>
 *   <li>{@code LIST_DLQ} — list all tasks in the dead-letter queue</li>
 *   <li>{@code REPLAY <taskId>} — move a dead-letter task back to the live queue</li>
 *   <li>{@code SHUTDOWN} — shut down the server</li>
 * </ul>
 * </p>
 */
public class ClientHandler implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(ClientHandler.class.getName());

    private static final String CMD_SUBMIT = "SUBMIT";
    private static final String CMD_STATUS = "STATUS";
    private static final String CMD_LIST = "LIST";
    private static final String CMD_CANCEL = "CANCEL";
    private static final String CMD_LIST_DLQ = "LIST_DLQ";
    private static final String CMD_REPLAY = "REPLAY";
    private static final String CMD_SHUTDOWN = "SHUTDOWN";

    private static final String KEY_STATUS = "status";
    private static final String KEY_ERROR = "error";
    private static final String KEY_TASK_ID = "taskId";

    private final Socket socket;
    private final TaskBroker broker;
    private final TaskRepository repository;
    private final TCPServer server;

    /**
     * Creates a new client handler.
     *
     * @param socket     the client socket
     * @param broker     the task broker
     * @param repository the persistence layer
     * @param server     the parent TCP server (for shutdown command)
     */
    public ClientHandler(Socket socket, TaskBroker broker,
                         TaskRepository repository, TCPServer server) {
        this.socket = socket;
        this.broker = broker;
        this.repository = repository;
        this.server = server;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(
                new BufferedOutputStream(socket.getOutputStream()), true)) {

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                LOGGER.fine(() -> "Received command: " + trimmed);
                String response = processCommand(trimmed);
                writer.println(response);
            }

        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Client disconnected: " + socket.getRemoteSocketAddress(), e);
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Error closing client socket", e);
            }
        }
    }

    /**
     * Parses and dispatches a single command string.
     * Package-private to allow direct invocation from integration tests.
     *
     * @param commandLine the raw command line from the client
     * @return a JSON string response
     */
    String processCommand(String commandLine) {
        // Split on first space to get command and arguments
        String[] parts = commandLine.split("\\s+", 2);
        String command = parts[0].toUpperCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        try {
            return switch (command) {
                case CMD_SUBMIT   -> handleSubmit(args);
                case CMD_STATUS   -> handleStatus(args);
                case CMD_LIST     -> handleList();
                case CMD_CANCEL   -> handleCancel(args);
                case CMD_LIST_DLQ -> handleListDlq();
                case CMD_REPLAY   -> handleReplay(args);
                case CMD_SHUTDOWN -> handleShutdown();
                default           -> errorResponse("unknown command");
            };
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing command: " + commandLine, e);
            return errorResponse(e.getMessage());
        }
    }

    /**
     * Handles the SUBMIT command: creates a task from JSON, persists it,
     * and submits it to the broker.
     */
    private String handleSubmit(String jsonStr) {
        if (jsonStr.isEmpty()) {
            return errorResponse("SUBMIT requires a JSON payload");
        }

        try {
            JSONObject json = new JSONObject(jsonStr);
            Task task = Task.fromJson(json);

            repository.save(task);
            broker.submit(task);

            JSONObject response = new JSONObject();
            response.put(KEY_STATUS, "ok");
            response.put(KEY_TASK_ID, task.getTaskId());
            return response.toString();
        } catch (JSONException e) {
            return errorResponse("Invalid JSON: " + e.getMessage());
        }
    }

    /**
     * Handles the STATUS command: looks up a task by ID in the broker first,
     * falling back to the repository.
     */
    private String handleStatus(String taskId) {
        if (taskId.isEmpty()) {
            return errorResponse("STATUS requires a taskId");
        }

        // Check broker (in-memory) first
        Optional<Task> brokerTask = broker.findById(taskId);
        if (brokerTask.isPresent()) {
            return brokerTask.get().toJson().toString();
        }

        // Fall back to persistence
        Optional<Task> dbTask = repository.findById(taskId);
        if (dbTask.isPresent()) {
            return dbTask.get().toJson().toString();
        }

        return errorResponse("not found");
    }

    /**
     * Handles the LIST command: returns a JSON array of all tasks.
     */
    private String handleList() {
        List<Task> tasks = broker.listAll();
        JSONArray array = new JSONArray();
        for (Task task : tasks) {
            array.put(task.toJson());
        }
        return array.toString();
    }

    /**
     * Handles the CANCEL command: attempts to cancel a pending task.
     */
    private String handleCancel(String taskId) {
        if (taskId.isEmpty()) {
            return errorResponse("CANCEL requires a taskId");
        }

        boolean cancelled = broker.cancel(taskId);
        JSONObject response = new JSONObject();
        if (cancelled) {
            response.put(KEY_STATUS, "ok");
            repository.updateStatus(taskId, TaskStatus.CANCELLED);
        } else {
            response.put(KEY_STATUS, "already_running");
        }
        return response.toString();
    }

    /**
     * Handles the SHUTDOWN command: triggers a graceful server shutdown.
     */
    private String handleShutdown() {
        JSONObject response = new JSONObject();
        response.put(KEY_STATUS, "ok");
        response.put("message", "Server shutting down...");

        // Trigger shutdown asynchronously
        server.triggerShutdown();

        return response.toString();
    }

    /**
     * Handles the LIST_DLQ command: returns a JSON array of all tasks in the
     * dead-letter queue.
     */
    private String handleListDlq() {
        List<Task> tasks = repository.findAllDlq();
        JSONArray array = new JSONArray();
        for (Task task : tasks) {
            array.put(task.toJson());
        }
        return array.toString();
    }

    /**
     * Handles the REPLAY command: moves a dead-letter task back into the live
     * queue with {@code retryCount} reset to 0 and status set to
     * {@link TaskStatus#PENDING}.
     *
     * <p>If the task is not found in the dead-letter queue, returns a structured
     * error response instead of throwing.</p>
     */
    private String handleReplay(String taskId) {
        if (taskId.isEmpty()) {
            return errorResponse("REPLAY requires a taskId");
        }

        // Look up the task in the DLQ
        List<Task> dlqTasks = repository.findAllDlq();
        Task dlqTask = null;
        for (Task t : dlqTasks) {
            if (t.getTaskId().equals(taskId)) {
                dlqTask = t;
                break;
            }
        }

        if (dlqTask == null) {
            JSONObject response = new JSONObject();
            response.put(KEY_STATUS, "error");
            response.put("message", "Task not found in dead-letter queue");
            return response.toString();
        }

        // Reset retry state and re-enqueue.
        // The task row already exists in the live 'tasks' table (with status=DEAD),
        // so we update it in-place rather than inserting a new row.
        dlqTask.setRetryCount(0);
        dlqTask.setStatus(TaskStatus.PENDING);
        dlqTask.setScheduledAt(0L);
        dlqTask.setNextRetryAt(0L);
        dlqTask.setCompletedAt(0L);

        repository.deleteFromDlq(taskId);
        repository.updateRetryInfo(taskId, 0, 0L);
        repository.updateStatus(taskId, TaskStatus.PENDING);
        broker.submit(dlqTask);

        JSONObject response = new JSONObject();
        response.put(KEY_STATUS, "ok");
        response.put(KEY_TASK_ID, taskId);
        return response.toString();
    }

    /**
     * Creates a JSON error response.
     */
    private String errorResponse(String message) {
        JSONObject response = new JSONObject();
        response.put(KEY_ERROR, message);
        return response.toString();
    }
}
