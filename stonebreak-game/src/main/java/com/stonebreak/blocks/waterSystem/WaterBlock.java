package com.stonebreak.blocks.waterSystem;

import org.joml.Vector3f;

/**
 * Represents a water block following Minecraft's exact fluid mechanics.
 * Uses integer depth values (0 = source, 1-7 = flowing) and implements
 * Minecraft's flow direction weighting system.
 */
public class WaterBlock {

    public static final int SOURCE_DEPTH = 0;
    public static final int MAX_FLOW_DEPTH = 7;
    public static final int MAX_WATER_LEVEL = 8; // For backward compatibility

    private int depth = SOURCE_DEPTH; // 0 = source, 1-7 = flowing water depth
    private boolean isSource = false;
    private final Vector3f flowDirection = new Vector3f();
    private long lastUpdateTime = 0;

    /**
     * Creates a new water source block (depth 0).
     */
    public WaterBlock() {
        this.depth = SOURCE_DEPTH;
        this.isSource = true;
    }

    /**
     * Creates a water block with specified depth.
     *
     * @param depth Water depth (0 = source, 1-7 = flowing)
     */
    public WaterBlock(int depth) {
        this.depth = Math.max(0, Math.min(MAX_FLOW_DEPTH, depth));
        this.isSource = (depth == SOURCE_DEPTH);
    }

    /**
     * Gets the water depth (Minecraft system: 0 = source, 1-7 = flowing).
     *
     * @return Water depth from 0 to MAX_FLOW_DEPTH
     */
    public int getDepth() {
        return depth;
    }

    /**
     * Sets the water depth.
     *
     * @param depth New water depth (0 = source, 1-7 = flowing)
     */
    public void setDepth(int depth) {
        this.depth = Math.max(0, Math.min(MAX_FLOW_DEPTH, depth));
        this.isSource = (depth == SOURCE_DEPTH);
    }

    /**
     * Gets the current water level (backward compatibility).
     *
     * @return Water level from 0.0 to MAX_WATER_LEVEL
     */
    public float getLevel() {
        return MAX_WATER_LEVEL - depth;
    }

    /**
     * Sets the water level (backward compatibility).
     *
     * @param level New water level (will be converted to depth)
     */
    public void setLevel(float level) {
        int newDepth = Math.max(0, Math.min(MAX_FLOW_DEPTH, MAX_WATER_LEVEL - (int)level));
        setDepth(newDepth);
    }

    /**
     * Gets the water pressure (calculated from depth).
     *
     * @return Current pressure value
     */
    public float getPressure() {
        return MAX_WATER_LEVEL - depth;
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
            this.depth = SOURCE_DEPTH;
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
     * Gets the visual height for rendering based on depth (0.0 to 1.0).
     *
     * @return Visual height for rendering
     */
    public float getVisualHeight() {
        if (depth >= MAX_FLOW_DEPTH) return 0.0f;
        // Source blocks and depth 1 = full height, depth increases = lower height
        float heightRatio = (float)(MAX_WATER_LEVEL - depth) / MAX_WATER_LEVEL;
        return Math.max(0.125f, Math.min(0.875f, heightRatio));
    }

    /**
     * Gets the normalized water level (0.0 to 1.0).
     *
     * @return Normalized water level
     */
    public float getNormalizedLevel() {
        return (float)(MAX_WATER_LEVEL - depth) / MAX_WATER_LEVEL;
    }

    /**
     * Checks if this water block is empty (depth 7 or higher).
     *
     * @return true if water depth indicates empty
     */
    public boolean isEmpty() {
        return depth >= MAX_FLOW_DEPTH;
    }

    /**
     * Checks if this water block is full (source or depth 1).
     *
     * @return true if water block is at maximum level
     */
    public boolean isFull() {
        return depth <= 1;
    }

    /**
     * Decreases water depth (increases water level).
     *
     * @param depthReduction Amount to decrease depth
     * @return Actual depth reduction applied
     */
    public int addWater(int depthReduction) {
        int oldDepth = depth;
        depth = Math.max(SOURCE_DEPTH, depth - depthReduction);
        return oldDepth - depth;
    }

    /**
     * Increases water depth (decreases water level).
     *
     * @param depthIncrease Amount to increase depth
     * @return Actual depth increase applied
     */
    public int removeWater(int depthIncrease) {
        if (isSource) {
            return 0; // Source blocks don't lose water
        }

        int oldDepth = depth;
        depth = Math.min(MAX_FLOW_DEPTH, depth + depthIncrease);
        return depth - oldDepth;
    }

    /**
     * Backward compatibility: adds water using float amount.
     */
    public float addWater(float amount) {
        int depthReduction = (int)amount;
        return addWater(depthReduction);
    }

    /**
     * Backward compatibility: removes water using float amount.
     */
    public float removeWater(float amount) {
        int depthIncrease = (int)amount;
        return removeWater(depthIncrease);
    }

    /**
     * Updates the flow direction based on Minecraft's depth-based system.
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
                // In Minecraft, water flows toward lower depth (higher level)
                int depthDiff = neighbor.depth - this.depth;
                if (depthDiff > 0) {
                    Vector3f directionToNeighbor = directions[i];
                    newFlowDir.add(directionToNeighbor.x * depthDiff, 0, directionToNeighbor.z * depthDiff);
                    totalFlow += depthDiff;
                }
            }
        }

        if (totalFlow > 0) {
            newFlowDir.normalize();
            flowDirection.lerp(newFlowDir, 0.3f);
        }
    }

    /**
     * Gets the distance from the nearest water source (same as depth for flowing water).
     *
     * @return Distance from source in blocks
     */
    public int getDistanceFromSource() {
        return depth;
    }

    /**
     * Sets the distance from the nearest water source (updates depth).
     *
     * @param distance Distance from source in blocks
     */
    public void setDistanceFromSource(int distance) {
        setDepth(distance);
    }

    /**
     * Gets the maximum level this water block should have based on its depth.
     *
     * @return Maximum level based on depth
     */
    public float getMaxLevelForDistance() {
        return MAX_WATER_LEVEL - depth;
    }

    @Override
    public String toString() {
        return String.format("WaterBlock{depth=%d, level=%.2f, source=%s, flow=%.2f}",
                           depth, getLevel(), isSource, flowDirection.length());
    }
}