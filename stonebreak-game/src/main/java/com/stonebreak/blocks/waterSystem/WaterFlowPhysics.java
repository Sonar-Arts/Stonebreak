package com.stonebreak.blocks.waterSystem;

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
    public static Vector3f calculateFlowDirection(World world, long x, long y, long z) {
        WaterBlock current = world.getWaterStateAt(x, y, z);
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
            long nx = x + dir[0];
            long nz = z + dir[1];

            WaterBlock neighbor = world.getWaterStateAt(nx, y, nz);
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
     * The force (acceleration) the current in one water cell puts on a body there: the flow
     * direction scaled by {@value #FLOW_FORCE_MULTIPLIER} and the cell's level strength, or straight
     * down at {@value #VERTICAL_FLOW_FORCE} in a falling column. Zero when the cell is not water.
     * This is what the physics cache stores per water cell ({@code WorldPhysics}), so changing it
     * changes every body's drift.
     */
    public static Vector3f cellForce(World world, long x, long y, long z, Vector3f out) {
        out.set(0, 0, 0);
        WaterBlock waterBlock = world.getWaterStateAt(x, y, z);
        if (waterBlock == null) {
            return out;
        }
        Vector3f flowDir = calculateFlowDirection(world, x, y, z);
        float levelStrength = (WaterBlock.MAX_LEVEL - waterBlock.level() + 1) / (float) (WaterBlock.MAX_LEVEL + 1);
        if (waterBlock.falling()) {
            out.set(flowDir.x * FLOW_FORCE_MULTIPLIER * levelStrength,
                    flowDir.y * VERTICAL_FLOW_FORCE,
                    flowDir.z * FLOW_FORCE_MULTIPLIER * levelStrength);
        } else {
            out.set(flowDir.x * FLOW_FORCE_MULTIPLIER * levelStrength,
                    flowDir.y * FLOW_FORCE_MULTIPLIER * levelStrength,
                    flowDir.z * FLOW_FORCE_MULTIPLIER * levelStrength);
        }
        return out;
    }

    /**
     * Applies water flow forces to an entity's velocity: the mean {@link #cellForce} of the water
     * cells at 3 heights (0.1, half and 0.9 of {@code height} above {@code position.y}) x 5 points
     * (centre and +-0.4 width on x and z), measured by the engine physics over its cell cache.
     *
     * @param velocity Entity's current velocity (will be modified)
     * @return the flow vector that was applied (zero vector if no water was sampled)
     */
    public static Vector3f applyWaterFlowForce(World world, org.joml.Vector3dc position, Vector3f velocity,
                                          float deltaTime, float width, float height) {
        if (world == null) {
            return new Vector3f();
        }
        com.openmason.engine.physics.FluidSample sample = com.stonebreak.world.physics.WorldPhysics.of(world).measureFluid(
                position.x(), position.y(), position.z(), height, width * 0.5f, position.y(), height);
        if (sample.flowSamples == 0) {
            return new Vector3f(0, 0, 0);
        }
        velocity.x += sample.flowX * deltaTime;
        velocity.y += sample.flowY * deltaTime;
        velocity.z += sample.flowZ * deltaTime;
        return new Vector3f(sample.flowX, sample.flowY, sample.flowZ);
    }
}
