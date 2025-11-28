package com.stonebreak.world.chunk.utils;

/**
 * Priority-based chunk loading task for distance-ordered chunk loading.
 * Follows the same pattern as MeshUploadTask in MmsMeshPipeline.
 *
 * Lower priority number = higher urgency (closer to player).
 * Tasks with same priority are processed FIFO (by timestamp).
 */
class ChunkLoadTask implements Comparable<ChunkLoadTask>, Runnable {

    final ChunkPosition position;
    final int priority;  // Distance to player (lower = closer = higher urgency)
    final long timestamp;  // Nanosecond timestamp for FIFO tiebreaker
    private final Runnable loadAction;

    /**
     * Creates a new chunk load task with priority ordering.
     *
     * @param position The chunk position to load
     * @param priority The priority (distance to player) - lower values processed first
     * @param loadAction The actual loading logic to execute
     */
    ChunkLoadTask(ChunkPosition position, int priority, Runnable loadAction) {
        this.position = position;
        this.priority = priority;
        this.timestamp = System.nanoTime();
        this.loadAction = loadAction;
    }

    @Override
    public void run() {
        loadAction.run();
    }

    @Override
    public int compareTo(ChunkLoadTask other) {
        // Lower priority first (closer chunks have lower distance values)
        int priorityCompare = Integer.compare(this.priority, other.priority);
        if (priorityCompare != 0) {
            return priorityCompare;
        }
        // Same priority: FIFO (earlier timestamp first)
        return Long.compare(this.timestamp, other.timestamp);
    }
}
