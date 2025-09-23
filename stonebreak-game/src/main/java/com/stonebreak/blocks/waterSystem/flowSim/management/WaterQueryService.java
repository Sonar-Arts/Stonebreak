package com.stonebreak.blocks.waterSystem.flowSim.management;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import org.joml.Vector3i;

import java.util.Map;

/**
 * Provides query services for water properties and visual information.
 * Handles water level queries, visual heights, and foam calculations.
 *
 * Following Single Responsibility Principle - only handles water queries.
 */
public class WaterQueryService {

    private final Map<Vector3i, WaterBlock> waterBlocks;

    public WaterQueryService(Map<Vector3i, WaterBlock> waterBlocks) {
        this.waterBlocks = waterBlocks;
    }

    /**
     * Gets the water level (0.0 to 1.0) at a position.
     */
    public float getWaterLevel(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        WaterBlock waterBlock = waterBlocks.get(pos);

        if (waterBlock != null) {
            return waterBlock.getNormalizedLevel();
        }

        // Check if there's a water block in the world
        World world = Game.getWorld();
        if (world != null && world.getBlockAt(x, y, z) == BlockType.WATER) {
            return 1.0f; // Default to full for unknown water blocks
        }

        return 0.0f;
    }

    /**
     * Gets the visual height for rendering.
     */
    public float getWaterVisualHeight(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        WaterBlock waterBlock = waterBlocks.get(pos);

        if (waterBlock != null) {
            return waterBlock.getVisualHeight();
        }

        // Default height for water blocks not in simulation
        World world = Game.getWorld();
        if (world != null && world.getBlockAt(x, y, z) == BlockType.WATER) {
            return 0.875f;
        }

        return 0.0f;
    }

    /**
     * Gets foam intensity for visual effects.
     */
    public float getFoamIntensity(float x, float y, float z) {
        Vector3i pos = new Vector3i((int)x, (int)y, (int)z);
        WaterBlock waterBlock = waterBlocks.get(pos);

        if (waterBlock == null) return 0.0f;

        float foam = waterBlock.getFlowDirection().length() * 0.4f;

        // Add foam near solid blocks
        World world = Game.getWorld();
        if (world != null) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;

                    BlockType neighbor = world.getBlockAt((int)x + dx, (int)y, (int)z + dz);
                    if (neighbor != BlockType.WATER && neighbor != BlockType.AIR) {
                        foam += 0.2f;
                    }
                }
            }
        }

        return Math.min(foam, 1.0f);
    }

    /**
     * Gets a water block at the specified position.
     */
    public WaterBlock getWaterBlock(int x, int y, int z) {
        return waterBlocks.get(new Vector3i(x, y, z));
    }

    /**
     * Gets a water block at the specified position vector.
     */
    public WaterBlock getWaterBlock(Vector3i pos) {
        return waterBlocks.get(pos);
    }

    /**
     * Checks if a position has any water (either tracked or in world).
     */
    public boolean hasWater(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);

        // Check tracked water first
        if (waterBlocks.containsKey(pos)) {
            return true;
        }

        // Check world
        World world = Game.getWorld();
        if (world != null) {
            return world.getBlockAt(x, y, z) == BlockType.WATER;
        }

        return false;
    }

    /**
     * Checks if a position has tracked water in the simulation.
     */
    public boolean hasTrackedWater(Vector3i pos) {
        return waterBlocks.containsKey(pos);
    }

    /**
     * Gets the depth of water at a position (0-7, where 0 is source).
     */
    public int getWaterDepth(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        WaterBlock waterBlock = waterBlocks.get(pos);

        if (waterBlock != null) {
            return waterBlock.getDepth();
        }

        // Default depth for untracked water
        return WaterBlock.MAX_FLOW_DEPTH;
    }

    /**
     * Checks if water at a position is a source block.
     */
    public boolean isSource(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);
        WaterBlock waterBlock = waterBlocks.get(pos);

        return waterBlock != null && waterBlock.isSource();
    }

    /**
     * Gets the total number of tracked water blocks.
     */
    public int getTrackedWaterCount() {
        return waterBlocks.size();
    }
}