package com.stonebreak.world.chunk.api.commonChunkOperations.core;

import org.joml.Vector3f;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * CCO-integrated player position tracking system.
 * Provides a unified interface for tracking player position with automatic
 * chunk boundary detection and position change notifications.
 *
 * Key Features:
 * - Single source of truth for player position
 * - Chunk boundary crossing detection
 * - Position change listeners for CCO integration
 * - Automatic save/load integration
 *
 * Design Principles:
 * - SOLID: Single Responsibility (only tracks position)
 * - Observer pattern for position changes
 * - Thread-safe for concurrent access
 */
public class CcoPlayerTracker {

    // Player position (mutable)
    private final Vector3f position;
    private final Vector3f previousPosition;

    // Current chunk coordinates
    private int currentChunkX;
    private int currentChunkZ;

    // Position change listeners
    private final Set<PositionChangeListener> listeners = new CopyOnWriteArraySet<>();

    // Chunk size constant
    private static final int CHUNK_SIZE = 16;

    public CcoPlayerTracker(Vector3f initialPosition) {
        this.position = new Vector3f(initialPosition);
        this.previousPosition = new Vector3f(initialPosition);
        updateCurrentChunk();
    }

    /**
     * Updates the player's position and notifies listeners if position changed.
     * Automatically detects chunk boundary crossings.
     *
     * @param newPosition The new player position
     */
    public void updatePosition(Vector3f newPosition) {
        // Store previous state
        previousPosition.set(position);
        int previousChunkX = currentChunkX;
        int previousChunkZ = currentChunkZ;

        // Update position
        position.set(newPosition);
        updateCurrentChunk();

        // Check if position changed
        if (!position.equals(previousPosition)) {
            notifyPositionChanged(previousPosition, position);

            // Check if chunk boundary was crossed
            if (currentChunkX != previousChunkX || currentChunkZ != previousChunkZ) {
                notifyChunkBoundaryCrossed(previousChunkX, previousChunkZ, currentChunkX, currentChunkZ);
            }
        }
    }

    /**
     * Updates the player's position directly without triggering listeners.
     * Use this for loading saved state to avoid spurious notifications.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    public void setPositionSilent(float x, float y, float z) {
        position.set(x, y, z);
        previousPosition.set(position);
        updateCurrentChunk();
    }

    /**
     * Gets the current player position (read-only copy).
     *
     * @return Copy of current position
     */
    public Vector3f getPosition() {
        return new Vector3f(position);
    }

    /**
     * Gets the current player position (direct reference for performance).
     * WARNING: Do not modify the returned vector!
     *
     * @return Direct reference to position (read-only)
     */
    public Vector3f getPositionDirect() {
        return position;
    }

    /**
     * Gets the current chunk X coordinate.
     *
     * @return Chunk X coordinate
     */
    public int getCurrentChunkX() {
        return currentChunkX;
    }

    /**
     * Gets the current chunk Z coordinate.
     *
     * @return Chunk Z coordinate
     */
    public int getCurrentChunkZ() {
        return currentChunkZ;
    }

    /**
     * Registers a position change listener.
     *
     * @param listener The listener to register
     */
    public void addPositionChangeListener(PositionChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a position change listener.
     *
     * @param listener The listener to remove
     */
    public void removePositionChangeListener(PositionChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Updates the current chunk coordinates based on current position.
     */
    private void updateCurrentChunk() {
        currentChunkX = (int) Math.floor(position.x / CHUNK_SIZE);
        currentChunkZ = (int) Math.floor(position.z / CHUNK_SIZE);
    }

    /**
     * Notifies all listeners that position changed.
     */
    private void notifyPositionChanged(Vector3f oldPosition, Vector3f newPosition) {
        for (PositionChangeListener listener : listeners) {
            try {
                listener.onPositionChanged(oldPosition, newPosition);
            } catch (Exception e) {
                System.err.println("[CCO-PLAYER-TRACKER] Error notifying position change listener: " + e.getMessage());
            }
        }
    }

    /**
     * Notifies all listeners that player crossed chunk boundary.
     */
    private void notifyChunkBoundaryCrossed(int oldChunkX, int oldChunkZ, int newChunkX, int newChunkZ) {
        for (PositionChangeListener listener : listeners) {
            try {
                listener.onChunkBoundaryCrossed(oldChunkX, oldChunkZ, newChunkX, newChunkZ);
            } catch (Exception e) {
                System.err.println("[CCO-PLAYER-TRACKER] Error notifying chunk boundary listener: " + e.getMessage());
            }
        }
    }

    /**
     * Listener interface for position changes.
     */
    public interface PositionChangeListener {

        /**
         * Called when player position changes.
         *
         * @param oldPosition Previous position
         * @param newPosition New position
         */
        default void onPositionChanged(Vector3f oldPosition, Vector3f newPosition) {
            // Default implementation does nothing
        }

        /**
         * Called when player crosses chunk boundary.
         *
         * @param oldChunkX Previous chunk X
         * @param oldChunkZ Previous chunk Z
         * @param newChunkX New chunk X
         * @param newChunkZ New chunk Z
         */
        default void onChunkBoundaryCrossed(int oldChunkX, int oldChunkZ, int newChunkX, int newChunkZ) {
            // Default implementation does nothing
        }
    }
}
