package com.stonebreak.blocks.waterSystem.flowSim.core;

import org.joml.Vector3i;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Manages scheduling and queuing of water flow updates.
 * Handles deduplication and provides efficient access to pending updates.
 *
 * Following Single Responsibility Principle - only handles update scheduling.
 */
public class FlowUpdateScheduler {

    private final Queue<Vector3i> flowUpdateQueue;
    private final Set<Vector3i> scheduledUpdates;

    public FlowUpdateScheduler() {
        this.flowUpdateQueue = new ConcurrentLinkedQueue<>();
        this.scheduledUpdates = Collections.synchronizedSet(new HashSet<>());
    }

    /**
     * Schedules a flow update for a position.
     * Automatically deduplicates to prevent multiple updates for the same position.
     */
    public void scheduleFlowUpdate(Vector3i pos) {
        if (scheduledUpdates.add(pos)) {
            flowUpdateQueue.offer(pos);
        }
    }

    /**
     * Schedules flow updates for all neighbors of a position.
     */
    public void scheduleNeighborUpdates(Vector3i pos) {
        Vector3i[] neighbors = {
            new Vector3i(pos.x + 1, pos.y, pos.z),
            new Vector3i(pos.x - 1, pos.y, pos.z),
            new Vector3i(pos.x, pos.y + 1, pos.z),
            new Vector3i(pos.x, pos.y - 1, pos.z),
            new Vector3i(pos.x, pos.y, pos.z + 1),
            new Vector3i(pos.x, pos.y, pos.z - 1)
        };

        for (Vector3i neighbor : neighbors) {
            scheduleFlowUpdate(neighbor);
        }
    }

    /**
     * Gets the current queue of pending updates and clears internal state.
     * Returns a deduplicated queue ready for processing.
     *
     * @return Queue of unique positions to update
     */
    public Queue<Vector3i> getAndClearUpdates() {
        // Remove duplicates from the queue before processing
        Set<Vector3i> uniqueUpdates = new HashSet<>(flowUpdateQueue);
        Queue<Vector3i> currentQueue = new ArrayDeque<>(uniqueUpdates);

        // Clear the queues for next cycle
        flowUpdateQueue.clear();
        scheduledUpdates.clear();

        return currentQueue;
    }

    /**
     * Gets the current number of pending updates.
     */
    public int getPendingUpdateCount() {
        return flowUpdateQueue.size();
    }

    /**
     * Clears all pending updates.
     */
    public void clear() {
        flowUpdateQueue.clear();
        scheduledUpdates.clear();
    }

    /**
     * Checks if there are any pending updates.
     */
    public boolean hasPendingUpdates() {
        return !flowUpdateQueue.isEmpty();
    }
}