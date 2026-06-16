package com.taskqueue.gui;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages a single persistent TCP connection to the JavaTaskQueue server.
 * <p>
 * All commands are sent synchronously (one at a time) to the server via
 * newline-delimited text; the response is a single JSON line read back.
 * If the connection drops, the state is updated and listeners are notified.
 * </p>
 */
public class ServerConnection {

    private static final Logger LOGGER = Logger.getLogger(ServerConnection.class.getName());

    /**
     * Callback interface for connection state changes.
     */
    public interface ConnectionListener {
        void onConnected();
        void onDisconnected();
    }

    private final String host;
    private final int port;

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private volatile boolean connected;

    private final CopyOnWriteArrayList<ConnectionListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Creates a connection targeting the given host and port.
     *
     * @param host server hostname
     * @param port server port
     */
    public ServerConnection(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Convenience constructor targeting {@code localhost:9090}.
     */
    public ServerConnection() {
        this("localhost", 9090);
    }

    /**
     * Registers a listener for connection state changes.
     */
    public void addListener(ConnectionListener listener) {
        listeners.add(listener);
    }

    /**
     * Attempts to open a TCP connection to the server.
     *
     * @return {@code true} if the connection was established successfully
     */
    public synchronized boolean connect() {
        try {
            disconnect();
            socket = new Socket(host, port);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(new BufferedOutputStream(socket.getOutputStream()), true);
            connected = true;
            fireConnected();
            LOGGER.info("Connected to server at " + host + ":" + port);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to connect to " + host + ":" + port, e);
            connected = false;
            fireDisconnected();
            return false;
        }
    }

    /**
     * Closes the TCP connection.
     */
    public synchronized void disconnect() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Error closing socket", e);
            }
        }
        reader = null;
        writer = null;
        socket = null;
        if (connected) {
            connected = false;
            fireDisconnected();
        }
    }

    /**
     * Sends a command to the server and returns the single-line JSON response.
     * <p>
     * If the connection is lost, the method returns a JSON error string and
     * updates the connection state.
     * </p>
     *
     * @param command the command string (e.g. {@code LIST}, {@code SUBMIT {...}})
     * @return the server's JSON response, or an error JSON if disconnected
     */
    public synchronized String sendCommand(String command) {
        if (!connected || writer == null || reader == null) {
            return "{\"error\":\"disconnected\"}";
        }
        try {
            writer.println(command);
            String response = reader.readLine();
            if (response == null) {
                handleDisconnect();
                return "{\"error\":\"disconnected\"}";
            }
            return response;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Send failed: " + e.getMessage());
            handleDisconnect();
            return "{\"error\":\"disconnected\"}";
        }
    }

    /**
     * Returns whether the connection is currently open.
     */
    public boolean isConnected() {
        return connected;
    }

    public String getHost() { return host; }
    public int getPort() { return port; }

    // ---- internal helpers ----

    private void handleDisconnect() {
        connected = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) { /* best-effort close */ }
        reader = null;
        writer = null;
        socket = null;
        fireDisconnected();
    }

    private void fireConnected() {
        for (ConnectionListener l : listeners) {
            try { l.onConnected(); } catch (Exception ignored) { }
        }
    }

    private void fireDisconnected() {
        for (ConnectionListener l : listeners) {
            try { l.onDisconnected(); } catch (Exception ignored) { }
        }
    }
}
