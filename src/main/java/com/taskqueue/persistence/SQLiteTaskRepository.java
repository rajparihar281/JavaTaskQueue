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
 * in the working directory). The schema is automatically created on first use,
 * and additive migrations (new columns, new tables) are applied on startup.
 * All database access is synchronized on the instance to ensure thread safety
 * with SQLite's single-writer model.
 * </p>
 */
public class SQLiteTaskRepository implements TaskRepository {

    private static final Logger LOGGER = Logger.getLogger(SQLiteTaskRepository.class.getName());

    private static final String DEFAULT_DB_FILE = "taskqueue.db";
    private static final String JDBC_URL_PREFIX = "jdbc:sqlite:";

    // -----------------------------------------------------------------------
    // DDL — tasks table
    // -----------------------------------------------------------------------

    private static final String CREATE_TASKS_TABLE_SQL = """
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
                next_retry_at INTEGER DEFAULT 0,
                output        TEXT,
                error_message TEXT
            )
            """;

    /**
     * Migration: add next_retry_at column to the tasks table if it does not
     * already exist. SQLite does not support IF NOT EXISTS for ALTER TABLE ADD
     * COLUMN, so we catch the duplicate-column exception and ignore it.
     */
    private static final String ADD_NEXT_RETRY_AT_SQL =
            "ALTER TABLE tasks ADD COLUMN next_retry_at INTEGER DEFAULT 0";

    // -----------------------------------------------------------------------
    // DDL — dead_letter table (identical structure to tasks)
    // -----------------------------------------------------------------------

    private static final String CREATE_DLQ_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS dead_letter (
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
                next_retry_at INTEGER DEFAULT 0,
                output        TEXT,
                error_message TEXT
            )
            """;

    // -----------------------------------------------------------------------
    // DML — tasks table
    // -----------------------------------------------------------------------

    private static final String INSERT_SQL = """
            INSERT INTO tasks (task_id, task_type, payload, priority, status,
                               scheduled_at, created_at, completed_at, retry_count, max_retries, next_retry_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE_STATUS_SQL =
            "UPDATE tasks SET status = ? WHERE task_id = ?";

    private static final String UPDATE_STATUS_AND_RESULT_SQL = """
            UPDATE tasks SET status = ?, completed_at = ?, output = ?, error_message = ?
            WHERE task_id = ?
            """;

    private static final String UPDATE_RETRY_INFO_SQL =
            "UPDATE tasks SET retry_count = ?, next_retry_at = ? WHERE task_id = ?";

    private static final String SELECT_PENDING_SQL =
            "SELECT * FROM tasks WHERE status IN ('PENDING', 'RUNNING')";

    private static final String SELECT_BY_ID_SQL =
            "SELECT * FROM tasks WHERE task_id = ?";

    private static final String SELECT_ALL_SQL =
            "SELECT * FROM tasks ORDER BY created_at DESC";

    // -----------------------------------------------------------------------
    // DML — dead_letter table
    // -----------------------------------------------------------------------

    private static final String INSERT_DLQ_SQL = """
            INSERT OR REPLACE INTO dead_letter
                (task_id, task_type, payload, priority, status,
                 scheduled_at, created_at, completed_at, retry_count, max_retries, next_retry_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_ALL_DLQ_SQL =
            "SELECT * FROM dead_letter ORDER BY created_at DESC";

    private static final String DELETE_DLQ_SQL =
            "DELETE FROM dead_letter WHERE task_id = ?";

    // -----------------------------------------------------------------------

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
     * Creates or migrates the database schema.
     * <ol>
     *   <li>Creates the {@code tasks} table if it does not exist.</li>
     *   <li>Adds the {@code next_retry_at} column via ALTER TABLE — the resulting
     *       {@code duplicate column name} exception is silently swallowed; any
     *       other {@link SQLException} is rethrown.</li>
     *   <li>Creates the {@code dead_letter} table if it does not exist.</li>
     * </ol>
     */
    private void initSchema() throws SQLException {
        synchronized (this) {
            try (Statement stmt = connection.createStatement()) {
                // 1. Core tasks table
                stmt.execute(CREATE_TASKS_TABLE_SQL);

                // 2. Additive column migration for next_retry_at
                try {
                    stmt.execute(ADD_NEXT_RETRY_AT_SQL);
                } catch (SQLException e) {
                    if (e.getMessage() != null
                            && e.getMessage().toLowerCase().contains("duplicate column name")) {
                        LOGGER.fine("next_retry_at column already exists, skipping migration");
                    } else {
                        throw e;
                    }
                }

                // 3. Dead-letter queue table
                stmt.execute(CREATE_DLQ_TABLE_SQL);
            }
        }
    }

    // -----------------------------------------------------------------------
    // TaskRepository — tasks table operations
    // -----------------------------------------------------------------------

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
            ps.setLong(11, task.getNextRetryAt());
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
    public synchronized void updateRetryInfo(String taskId, int retryCount, long nextRetryAt) {
        try (PreparedStatement ps = connection.prepareStatement(UPDATE_RETRY_INFO_SQL)) {
            ps.setInt(1, retryCount);
            ps.setLong(2, nextRetryAt);
            ps.setString(3, taskId);
            ps.executeUpdate();
            LOGGER.fine(() -> "Task retry info updated: " + taskId
                    + " retryCount=" + retryCount + " nextRetryAt=" + nextRetryAt);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to update retry info for task: " + taskId, e);
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

    // -----------------------------------------------------------------------
    // TaskRepository — dead_letter table operations
    // -----------------------------------------------------------------------

    @Override
    public synchronized void saveToDlq(Task task) {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_DLQ_SQL)) {
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
            ps.setLong(11, task.getNextRetryAt());
            ps.executeUpdate();
            LOGGER.info(() -> "Task saved to DLQ: " + task.getTaskId());
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to save task to DLQ: " + task.getTaskId(), e);
        }
    }

    @Override
    public synchronized List<Task> findAllDlq() {
        List<Task> tasks = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_ALL_DLQ_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tasks.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to find all DLQ tasks", e);
        }
        return tasks;
    }

    @Override
    public synchronized void deleteFromDlq(String taskId) {
        try (PreparedStatement ps = connection.prepareStatement(DELETE_DLQ_SQL)) {
            ps.setString(1, taskId);
            ps.executeUpdate();
            LOGGER.info(() -> "Task deleted from DLQ: " + taskId);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to delete task from DLQ: " + taskId, e);
        }
    }

    // -----------------------------------------------------------------------
    // Shared mapping
    // -----------------------------------------------------------------------

    /**
     * Maps a database result set row to a {@link Task} object.
     * Works for both the {@code tasks} and {@code dead_letter} tables, which
     * share an identical column schema.
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
                rs.getInt("max_retries"),
                rs.getLong("next_retry_at")
        );
    }
}
