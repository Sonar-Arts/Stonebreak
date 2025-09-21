package com.stonebreak.blocks.waterSystem;

import org.joml.Vector3f;

/**
 * Represents a water block with enhanced properties for physics simulation.
 * Contains water level, pressure, flow direction, and other physical properties.
 */
public class WaterBlock {

    public static final int MAX_WATER_LEVEL = 8;

    private float level = MAX_WATER_LEVEL;
    private float pressure = 0;
    private final Vector3f flowDirection = new Vector3f();
    private boolean isSource = false;
    private long lastUpdateTime = 0;
    private int distanceFromSource = 0;

    /**
     * Creates a new water block with full water level.
     */
    public WaterBlock() {
        this.level = MAX_WATER_LEVEL;
        this.distanceFromSource = 0;
    }

    /**
     * Creates a water block with specified initial level.
     *
     * @param initialLevel Initial water level (0.0 to MAX_WATER_LEVEL)
     */
    public WaterBlock(float initialLevel) {
        this.level = Math.max(0, Math.min(MAX_WATER_LEVEL, initialLevel));
        this.distanceFromSource = 0;
    }

    /**
     * Gets the current water level.
     *
     * @return Water level from 0.0 to MAX_WATER_LEVEL
     */
    public float getLevel() {
        return level;
    }

    /**
     * Sets the water level.
     *
     * @param level New water level (will be clamped to valid range)
     */
    public void setLevel(float level) {
        this.level = Math.max(0, Math.min(MAX_WATER_LEVEL, level));
    }

    /**
     * Gets the water pressure at this block.
     *
     * @return Current pressure value
     */
    public float getPressure() {
        return pressure;
    }

    /**
     * Sets the water pressure.
     *
     * @param pressure New pressure value
     */
    public void setPressure(float pressure) {
        this.pressure = pressure;
    }

    /**
     * Gets the flow direction vector.
     *
     * @return Flow direction as a Vector3f
     */
    public Vector3f getFlowDirection() {
        return flowDirection;
    }

    /**
     * Sets the flow direction.
     *
     * @param direction New flow direction
     */
    public void setFlowDirection(Vector3f direction) {
        this.flowDirection.set(direction);
    }

    /**
     * Checks if this is a water source block.
     *
     * @return true if this is a source block
     */
    public boolean isSource() {
        return isSource;
    }

    /**
     * Sets whether this is a water source block.
     *
     * @param source true to make this a source block
     */
    public void setSource(boolean source) {
        this.isSource = source;
        if (source) {
            this.level = MAX_WATER_LEVEL;
            this.pressure = MAX_WATER_LEVEL;
            this.distanceFromSource = 0;
        }
    }

    /**
     * Gets the last update time for this block.
     *
     * @return Last update time in milliseconds
     */
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    /**
     * Sets the last update time.
     *
     * @param time Update time in milliseconds
     */
    public void setLastUpdateTime(long time) {
        this.lastUpdateTime = time;
    }

    /**
     * Gets the visual height for rendering (0.0 to 1.0).
     *
     * @return Visual height for rendering
     */
    public float getVisualHeight() {
        if (level <= 0) return 0.0f;
        return Math.max(0.125f, Math.min(0.875f, level / (float)MAX_WATER_LEVEL));
    }

    /**
     * Gets the normalized water level (0.0 to 1.0).
     *
     * @return Normalized water level
     */
    public float getNormalizedLevel() {
        return level / (float)MAX_WATER_LEVEL;
    }

    /**
     * Checks if this water block is empty.
     *
     * @return true if water level is 0 or less
     */
    public boolean isEmpty() {
        return level <= 0;
    }

    /**
     * Checks if this water block is full.
     *
     * @return true if water level is at maximum
     */
    public boolean isFull() {
        return level >= MAX_WATER_LEVEL;
    }

    /**
     * Adds water to this block.
     *
     * @param amount Amount of water to add
     * @return Amount actually added (may be less if block becomes full)
     */
    public float addWater(float amount) {
        float oldLevel = level;
        level = Math.min(MAX_WATER_LEVEL, level + amount);
        return level - oldLevel;
    }

    /**
     * Removes water from this block.
     *
     * @param amount Amount of water to remove
     * @return Amount actually removed (may be less if block becomes empty)
     */
    public float removeWater(float amount) {
        if (isSource) {
            return 0; // Source blocks don't lose water
        }

        float oldLevel = level;
        level = Math.max(0, level - amount);
        return oldLevel - level;
    }

    /**
     * Updates the flow direction based on pressure differentials.
     *
     * @param deltaTime Time elapsed since last update
     * @param neighbors Array of neighboring water blocks (can contain nulls)
     * @param directions Array of direction vectors corresponding to neighbors
     */
    public void updateFlowDirection(float deltaTime, WaterBlock[] neighbors, Vector3f[] directions) {
        Vector3f newFlowDir = new Vector3f();
        float totalFlow = 0;

        for (int i = 0; i < neighbors.length && i < directions.length; i++) {
            WaterBlock neighbor = neighbors[i];
            if (neighbor != null) {
                float diff = this.level - neighbor.level;
                if (diff > 0) {
                    Vector3f directionToNeighbor = directions[i];
                    newFlowDir.add(directionToNeighbor.x * diff, 0, directionToNeighbor.z * diff);
                    totalFlow += diff;
                }
            }
        }

        if (totalFlow > 0) {
            newFlowDir.normalize();
            flowDirection.lerp(newFlowDir, 0.3f);
        }
    }

    /**
     * Gets the distance from the nearest water source.
     *
     * @return Distance from source in blocks
     */
    public int getDistanceFromSource() {
        return distanceFromSource;
    }

    /**
     * Sets the distance from the nearest water source.
     *
     * @param distance Distance from source in blocks
     */
    public void setDistanceFromSource(int distance) {
        this.distanceFromSource = Math.max(0, distance);
    }

    /**
     * Gets the maximum level this water block should have based on its distance from source.
     *
     * @return Maximum level based on distance
     */
    public float getMaxLevelForDistance() {
        if (isSource) {
            return MAX_WATER_LEVEL;
        }
        return Math.max(0, MAX_WATER_LEVEL - distanceFromSource);
    }

    @Override
    public String toString() {
        return String.format("WaterBlock{level=%.2f, pressure=%.2f, source=%s, distance=%d, flow=%.2f}",
                           level, pressure, isSource, distanceFromSource, flowDirection.length());
    }
}