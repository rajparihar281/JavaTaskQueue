package com.taskqueue.client;

import java.io.*;
import java.net.ConnectException;
import java.net.Socket;

/**
 * Interactive command-line client for the JavaTaskQueue server.
 * <p>
 * Connects to the TCP server and provides a REPL (Read-Eval-Print Loop)
 * interface for submitting tasks, querying status, and managing the queue.
 * This class has its own {@code main()} method and can be run standalone.
 * </p>
 *
 * <p>Usage:</p>
 * <pre>
 * java -cp JavaTaskQueue-jar-with-dependencies.jar com.taskqueue.client.TaskQueueClient
 * java -cp JavaTaskQueue-jar-with-dependencies.jar com.taskqueue.client.TaskQueueClient --host 192.168.1.10 --port 9091
 * </pre>
 */
public class TaskQueueClient {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 9090;
    private static final String PROMPT = "jtq> ";
    private static final String LOCAL_CMD_HELP = "help";
    private static final String LOCAL_CMD_EXIT = "exit";
    private static final String LOCAL_CMD_QUIT = "quit";

    private static final String HELP_TEXT = """
            
            JavaTaskQueue Client — Available Commands:
            ═══════════════════════════════════════════════════════════════
              SUBMIT <json>      Submit a new task
                                 Example: SUBMIT {"type":"ECHO","payload":"hello","priority":"HIGH"}
              STATUS <taskId>    Check the status of a task
              LIST               List all tasks
              CANCEL <taskId>    Cancel a pending task
              SHUTDOWN           Shut down the server
            ═══════════════════════════════════════════════════════════════
            Local commands:
              help               Show this help message
              exit / quit        Disconnect and exit the client
            """;

    private final String host;
    private final int port;

    /**
     * Creates a client targeting the specified host and port.
     *
     * @param host the server hostname or IP
     * @param port the server port
     */
    public TaskQueueClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Starts the interactive REPL session.
     */
    public void start() {
        System.out.println("Connecting to JavaTaskQueue server at " + host + ":" + port + "...");

        try (Socket socket = new Socket(host, port);
             BufferedReader serverReader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()));
             PrintWriter serverWriter = new PrintWriter(
                     new BufferedOutputStream(socket.getOutputStream()), true);
             BufferedReader stdinReader = new BufferedReader(
                     new InputStreamReader(System.in))) {

            System.out.println("Connected! Type 'help' for available commands.\n");

            String inputLine;
            while (true) {
                System.out.print(PROMPT);
                System.out.flush();
                inputLine = stdinReader.readLine();

                if (inputLine == null) {
                    // EOF (Ctrl+D / Ctrl+Z)
                    System.out.println("\nDisconnected.");
                    break;
                }

                String trimmed = inputLine.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                // Handle local commands
                String lower = trimmed.toLowerCase();
                if (LOCAL_CMD_HELP.equals(lower)) {
                    System.out.println(HELP_TEXT);
                    continue;
                }
                if (LOCAL_CMD_EXIT.equals(lower) || LOCAL_CMD_QUIT.equals(lower)) {
                    System.out.println("Goodbye!");
                    break;
                }

                // Send to server
                serverWriter.println(trimmed);

                // Read response
                String response = serverReader.readLine();
                if (response == null) {
                    System.out.println("Server disconnected.");
                    break;
                }

                // Pretty-print JSON if possible
                System.out.println(formatResponse(response));
            }

        } catch (ConnectException e) {
            System.err.println("Error: Could not connect to server at " + host + ":" + port);
            System.err.println("Make sure the JavaTaskQueue server is running.");
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        }
    }

    /**
     * Attempts to format a JSON response string for readability.
     *
     * @param response the raw JSON response
     * @return formatted response string
     */
    private String formatResponse(String response) {
        try {
            if (response.startsWith("[")) {
                return new org.json.JSONArray(response).toString(2);
            } else if (response.startsWith("{")) {
                return new org.json.JSONObject(response).toString(2);
            }
        } catch (Exception e) {
            // Fall through to return raw response
        }
        return response;
    }

    /**
     * Entry point for the standalone client.
     *
     * @param args optional: {@code --host <host>} and/or {@code --port <port>}
     */
    public static void main(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> {
                    if (i + 1 < args.length) {
                        host = args[++i];
                    }
                }
                case "--port" -> {
                    if (i + 1 < args.length) {
                        try {
                            port = Integer.parseInt(args[++i]);
                        } catch (NumberFormatException e) {
                            System.err.println("Invalid port number: " + args[i]);
                            return;
                        }
                    }
                }
                default -> {
                    // Ignore unknown args
                }
            }
        }

        TaskQueueClient client = new TaskQueueClient(host, port);
        client.start();
    }
}
