package com.taskqueue.persistence;

import com.taskqueue.model.Priority;
import com.taskqueue.model.Task;
import com.taskqueue.model.TaskResult;
import com.taskqueue.model.TaskStatus;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite-backed implementation of {@link TaskRepository}.
 * <p>
 * Stores all task data in a local SQLite database file ({@code taskqueue.db}
 * in the working directory). The schema is automatically created on first use.
 * All database access is synchronized on the instance to ensure thread safety
 * with SQLite's single-writer model.
 * </p>
 */
public class SQLiteTaskRepository implements TaskRepository {

    private static final Logger LOGGER = Logger.getLogger(SQLiteTaskRepository.class.getName());

    private static final String DEFAULT_DB_FILE = "taskqueue.db";
    private static final String JDBC_URL_PREFIX = "jdbc:sqlite:";

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS tasks (
                task_id       TEXT PRIMARY KEY,
                task_type     TEXT NOT NULL,
                payload       TEXT,
                priority      TEXT NOT NULL,
                status        TEXT NOT NULL,
                scheduled_at  INTEGER DEFAULT 0,
                created_at    INTEGER NOT NULL,
                completed_at  INTEGER DEFAULT 0,
                retry_count   INTEGER DEFAULT 0,
                max_retries   INTEGER DEFAULT 3,
                output        TEXT,
                error_message TEXT
            )
            """;

    private static final String INSERT_SQL = """
            INSERT INTO tasks (task_id, task_type, payload, priority, status,
                               scheduled_at, created_at, completed_at, retry_count, max_retries)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_STATUS_SQL =
            "UPDATE tasks SET status = ? WHERE task_id = ?";

    private static final String UPDATE_STATUS_AND_RESULT_SQL = """
            UPDATE tasks SET status = ?, completed_at = ?, output = ?, error_message = ?
            WHERE task_id = ?
            """;

    private static final String SELECT_PENDING_SQL =
            "SELECT * FROM tasks WHERE status IN ('PENDING', 'RUNNING')";

    private static final String SELECT_BY_ID_SQL =
            "SELECT * FROM tasks WHERE task_id = ?";

    private static final String SELECT_ALL_SQL =
            "SELECT * FROM tasks ORDER BY created_at DESC";

    private final Connection connection;

    /**
     * Creates a new repository using the default database file {@code taskqueue.db}.
     */
    public SQLiteTaskRepository() {
        this(DEFAULT_DB_FILE);
    }

    /**
     * Creates a new repository using the specified database file path.
     *
     * @param dbPath the path to the SQLite database file
     */
    public SQLiteTaskRepository(String dbPath) {
        try {
            this.connection = DriverManager.getConnection(JDBC_URL_PREFIX + dbPath);
            initSchema();
            LOGGER.info(() -> "SQLite repository initialized: " + dbPath);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite database: " + dbPath, e);
        }
    }

    /**
     * Creates the tasks table if it does not already exist.
     */
    private void initSchema() throws SQLException {
        synchronized (this) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(CREATE_TABLE_SQL);
            }
        }
    }

    @Override
    public synchronized void save(Task task) {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
            ps.setString(1, task.getTaskId());
            ps.setString(2, task.getTaskType());
            ps.setString(3, task.getPayload());
            ps.setString(4, task.getPriority().name());
            ps.setString(5, task.getStatus().name());
            ps.setLong(6, task.getScheduledAt());
            ps.setLong(7, task.getCreatedAt());
            ps.setLong(8, task.getCompletedAt());
            ps.setInt(9, task.getRetryCount());
            ps.setInt(10, task.getMaxRetries());
            ps.executeUpdate();
            LOGGER.fine(() -> "Task saved to DB: " + task.getTaskId());
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to save task: " + task.getTaskId(), e);
        }
    }

    @Override
    public synchronized void updateStatus(String taskId, TaskStatus status) {
        try (PreparedStatement ps = connection.prepareStatement(UPDATE_STATUS_SQL)) {
            ps.setString(1, status.name());
            ps.setString(2, taskId);
            ps.executeUpdate();
            LOGGER.fine(() -> "Task status updated: " + taskId + " -> " + status);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to update status for task: " + taskId, e);
        }
    }

    @Override
    public synchronized void updateStatusAndResult(String taskId, TaskStatus status, TaskResult result) {
        try (PreparedStatement ps = connection.prepareStatement(UPDATE_STATUS_AND_RESULT_SQL)) {
            ps.setString(1, status.name());
            ps.setLong(2, result.getDurationMs() > 0 ? System.currentTimeMillis() : 0L);
            ps.setString(3, result.getOutput());
            ps.setString(4, result.getErrorMessage());
            ps.setString(5, taskId);
            ps.executeUpdate();
            LOGGER.fine(() -> "Task result updated: " + taskId + " -> " + status);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to update result for task: " + taskId, e);
        }
    }

    @Override
    public synchronized List<Task> findPending() {
        List<Task> tasks = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_PENDING_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tasks.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to find pending tasks", e);
        }
        return tasks;
    }

    @Override
    public synchronized Optional<Task> findById(String taskId) {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_BY_ID_SQL)) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to find task: " + taskId, e);
        }
        return Optional.empty();
    }

    @Override
    public synchronized List<Task> findAll() {
        List<Task> tasks = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_ALL_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tasks.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to find all tasks", e);
        }
        return tasks;
    }

    /**
     * Maps a database result set row to a {@link Task} object.
     *
     * @param rs the result set positioned at the row to map
     * @return a new Task instance
     * @throws SQLException if a database access error occurs
     */
    private Task mapRow(ResultSet rs) throws SQLException {
        return new Task(
                rs.getString("task_id"),
                rs.getString("task_type"),
                rs.getString("payload"),
                Priority.valueOf(rs.getString("priority")),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getLong("scheduled_at"),
                rs.getLong("created_at"),
                rs.getLong("completed_at"),
                rs.getInt("retry_count"),
                rs.getInt("max_retries")
        );
    }
}
