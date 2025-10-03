package com.stonebreak.blocks.waterSystem;

import com.stonebreak.blocks.Water;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Handles water flow physics calculations for entities.
 * Calculates flow direction based on water levels and applies forces to entities.
 */
public final class WaterFlowPhysics {

    private static final float FLOW_FORCE_MULTIPLIER = 2.5f; // Force applied per unit of flow gradient
    private static final float VERTICAL_FLOW_FORCE = 5.0f; // Downward force when water is falling

    private WaterFlowPhysics() {
        // Utility class
    }

    /**
     * Calculates the water flow direction at a given position.
     * Returns a normalized vector indicating the direction water is flowing.
     *
     * @param world The world instance
     * @param x Block X coordinate
     * @param y Block Y coordinate
     * @param z Block Z coordinate
     * @return Flow direction vector (normalized), or zero vector if no flow
     */
    public static Vector3f calculateFlowDirection(World world, int x, int y, int z) {
        WaterBlock current = Water.getWaterBlock(x, y, z);
        if (current == null) {
            return new Vector3f(0, 0, 0);
        }

        Vector3f flowDirection = new Vector3f(0, 0, 0);

        // Check if water is falling - apply strong downward force
        if (current.falling()) {
            flowDirection.y = -1.0f;
            return flowDirection.normalize();
        }

        // Calculate horizontal flow based on surrounding water levels
        // Water flows from higher levels (lower level values) to lower levels (higher level values)
        int currentLevel = current.level();

        // Check all four horizontal directions
        int[][] directions = {
            {1, 0},   // +X (east)
            {-1, 0},  // -X (west)
            {0, 1},   // +Z (south)
            {0, -1}   // -Z (north)
        };

        for (int[] dir : directions) {
            int nx = x + dir[0];
            int nz = z + dir[1];

            WaterBlock neighbor = Water.getWaterBlock(nx, y, nz);
            int neighborLevel;

            if (neighbor == null) {
                // No water in this direction - strong flow toward empty space
                neighborLevel = WaterBlock.MAX_LEVEL + 1;
            } else if (neighbor.isSource()) {
                // Source blocks don't contribute to flow
                continue;
            } else {
                neighborLevel = neighbor.level();
            }

            // Calculate flow gradient (higher gradient = stronger flow)
            // Lower level value = higher water, so we subtract to get proper gradient
            int gradient = neighborLevel - currentLevel;

            if (gradient > 0) {
                // Water flows toward this direction
                flowDirection.x += dir[0] * gradient;
                flowDirection.z += dir[1] * gradient;
            }
        }

        // Normalize the flow direction
        if (flowDirection.lengthSquared() > 0) {
            flowDirection.normalize();
        }

        return flowDirection;
    }

    /**
     * Applies water flow forces to an entity's velocity.
     *
     * @param world The world instance
     * @param position Entity's position
     * @param velocity Entity's current velocity (will be modified)
     * @param deltaTime Time step for physics
     * @param width Entity width (for sampling multiple points)
     * @param height Entity height (for sampling multiple points)
     */
    public static void applyWaterFlowForce(World world, Vector3f position, Vector3f velocity,
                                          float deltaTime, float width, float height) {
        // Sample water flow at multiple points within the entity's volume
        // This provides more accurate flow forces for larger entities

        Vector3f totalFlow = new Vector3f(0, 0, 0);
        int sampleCount = 0;

        // Sample at feet, middle, and head height
        float[] sampleHeights = {0.1f, height * 0.5f, height * 0.9f};

        for (float sampleHeight : sampleHeights) {
            float checkY = position.y + sampleHeight;
            int blockY = (int) Math.floor(checkY);

            // Sample at center and edges horizontally
            float[][] horizontalOffsets = {
                {0, 0},                      // Center
                {width * 0.4f, 0},          // Right
                {-width * 0.4f, 0},         // Left
                {0, width * 0.4f},          // Back
                {0, -width * 0.4f}          // Front
            };

            for (float[] offset : horizontalOffsets) {
                int blockX = (int) Math.floor(position.x + offset[0]);
                int blockZ = (int) Math.floor(position.z + offset[1]);

                WaterBlock waterBlock = Water.getWaterBlock(blockX, blockY, blockZ);
                if (waterBlock != null) {
                    Vector3f flowDir = calculateFlowDirection(world, blockX, blockY, blockZ);

                    // Scale flow force based on water level (higher levels = stronger flow)
                    float levelStrength = (WaterBlock.MAX_LEVEL - waterBlock.level() + 1) /
                                        (float)(WaterBlock.MAX_LEVEL + 1);

                    // Falling water has stronger vertical force
                    if (waterBlock.falling()) {
                        totalFlow.add(flowDir.x * FLOW_FORCE_MULTIPLIER * levelStrength,
                                    flowDir.y * VERTICAL_FLOW_FORCE,
                                    flowDir.z * FLOW_FORCE_MULTIPLIER * levelStrength);
                    } else {
                        totalFlow.add(flowDir.x * FLOW_FORCE_MULTIPLIER * levelStrength,
                                    flowDir.y * FLOW_FORCE_MULTIPLIER * levelStrength,
                                    flowDir.z * FLOW_FORCE_MULTIPLIER * levelStrength);
                    }

                    sampleCount++;
                }
            }
        }

        // Average the flow forces from all sample points
        if (sampleCount > 0) {
            totalFlow.div(sampleCount);

            // Apply the flow force to velocity
            velocity.x += totalFlow.x * deltaTime;
            velocity.y += totalFlow.y * deltaTime;
            velocity.z += totalFlow.z * deltaTime;
        }
    }
}
