package com.taskqueue.model;

/**
 * Represents the priority level of a task in the queue.
 * <p>
 * Tasks with higher priority (lower weight value) are dequeued and processed
 * before tasks with lower priority. {@code HIGH} priority tasks have the lowest
 * weight (0), ensuring they are picked up first by worker threads.
 * </p>
 */
public enum Priority {

    /** Highest priority — processed first. Weight: 0. */
    HIGH(0),

    /** Medium priority — between high and normal. Weight: 1. */
    MEDIUM(1),

    /** Normal priority — default level. Weight: 2. */
    NORMAL(2),

    /** Lowest priority — processed last. Weight: 3. */
    LOW(3);

    private final int weight;

    Priority(int weight) {
        this.weight = weight;
    }

    /**
     * Returns the numeric weight of this priority level.
     * Lower values indicate higher priority.
     *
     * @return the integer weight
     */
    public int getWeight() {
        return weight;
    }
}
