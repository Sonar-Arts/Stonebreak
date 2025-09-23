package com.stonebreak.blocks.waterSystem.flowSim.algorithms;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.blocks.waterSystem.flowSim.core.FlowUpdateScheduler;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3i;

import java.util.Map;
import java.util.Set;

/**
 * Validates water flow rules and handles water placement/removal.
 * Implements Minecraft's water validation mechanics.
 *
 * Following Single Responsibility Principle - only handles flow validation.
 */
public class FlowValidator {

    private static final int MAX_HORIZONTAL_DISTANCE = 7;
    private static final int MAX_DEPTH = 7;

    private final Map<Vector3i, WaterBlock> waterBlocks;
    private final Set<Vector3i> sourceBlocks;
    private final FlowUpdateScheduler scheduler;

    public FlowValidator(Map<Vector3i, WaterBlock> waterBlocks, Set<Vector3i> sourceBlocks, FlowUpdateScheduler scheduler) {
        this.waterBlocks = waterBlocks;
        this.sourceBlocks = sourceBlocks;
        this.scheduler = scheduler;
    }

    /**
     * Checks if flowing water has a valid source.
     * Water can flow infinitely downward but only 7 blocks horizontally.
     * Ocean water (at or below sea level) is always considered valid.
     */
    public boolean hasValidSource(Vector3i pos, World world) {
        // First check if this is ocean water - ocean water is always valid
        WaterBlock currentWater = waterBlocks.get(pos);
        if (currentWater != null && currentWater.isOceanWater()) {
            return true; // Ocean water is always valid and stable
        }

        // Check if this is at ocean level (at or below sea level) - treat as ocean water
        if (pos.y <= WorldConfiguration.SEA_LEVEL) {
            return true; // Water at ocean level is considered stable
        }

        // Check if there's water directly above (infinite vertical flow)
        Vector3i abovePos = new Vector3i(pos.x, pos.y + 1, pos.z);
        BlockType aboveBlock = world.getBlockAt(abovePos.x, abovePos.y, abovePos.z);
        if (aboveBlock == BlockType.WATER) {
            return true; // Water can flow infinitely downward
        }

        // Check for adjacent water blocks that could be feeding this one
        Vector3i[] neighbors = {
            new Vector3i(pos.x + 1, pos.y, pos.z),
            new Vector3i(pos.x - 1, pos.y, pos.z),
            new Vector3i(pos.x, pos.y, pos.z + 1),
            new Vector3i(pos.x, pos.y, pos.z - 1)
        };

        for (Vector3i neighbor : neighbors) {
            WaterBlock neighborWater = waterBlocks.get(neighbor);
            if (neighborWater != null) {
                // Ocean water can support any adjacent water
                if (neighborWater.isOceanWater()) {
                    return true;
                }

                // Check if neighbor has lower or equal depth (can feed us)
                int ourDepth = waterBlocks.get(pos) != null ? waterBlocks.get(pos).getDepth() : MAX_DEPTH;
                if (neighborWater.getDepth() < ourDepth) {
                    return true;
                }
            }
        }

        // Check if there's a source block nearby
        for (Vector3i sourcePos : sourceBlocks) {
            int horizontalDistance = Math.abs(pos.x - sourcePos.x) + Math.abs(pos.z - sourcePos.z);
            int verticalDistance = Math.abs(pos.y - sourcePos.y);

            // Simple check: within 7 blocks horizontally and on same level or one below
            if (horizontalDistance <= MAX_HORIZONTAL_DISTANCE && verticalDistance <= 1) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if there's a valid flow path between source and target.
     * This considers both horizontal spread limits and vertical flow.
     */
    public boolean hasValidFlowPath(Vector3i source, Vector3i target, World world) {
        // If target is directly below source, always valid (infinite vertical)
        if (source.x == target.x && source.z == target.z && source.y > target.y) {
            return true;
        }

        // For horizontal flow, check if within the 7-block limit
        int horizontalDistance = Math.abs(target.x - source.x) + Math.abs(target.z - source.z);

        // If on same level, simple distance check
        if (source.y == target.y) {
            return horizontalDistance <= MAX_HORIZONTAL_DISTANCE;
        }

        // If source is higher, water may have reset its depth when flowing down
        // Each level down allows another 7 blocks of horizontal spread
        if (source.y > target.y) {
            // Check if the horizontal distance is reasonable for the vertical drop
            return horizontalDistance <= MAX_HORIZONTAL_DISTANCE;
        }

        return false;
    }

    /**
     * Estimates depth from surrounding water blocks.
     */
    public int estimateDepthFromSurroundings(Vector3i pos, World world) {
        // Check for adjacent water blocks to estimate depth
        Vector3i[] neighbors = {
            new Vector3i(pos.x + 1, pos.y, pos.z),
            new Vector3i(pos.x - 1, pos.y, pos.z),
            new Vector3i(pos.x, pos.y, pos.z + 1),
            new Vector3i(pos.x, pos.y, pos.z - 1),
            new Vector3i(pos.x, pos.y + 1, pos.z)
        };

        int minDepth = MAX_DEPTH;
        for (Vector3i neighbor : neighbors) {
            WaterBlock neighborWater = waterBlocks.get(neighbor);
            if (neighborWater != null) {
                minDepth = Math.min(minDepth, neighborWater.getDepth() + 1);
            }
        }

        return Math.min(minDepth, MAX_DEPTH);
    }

    /**
     * Removes water at a position.
     */
    public void removeWaterAt(Vector3i pos, World world) {
        waterBlocks.remove(pos);
        sourceBlocks.remove(pos);
        world.setBlockAt(pos.x, pos.y, pos.z, BlockType.AIR);

        // Schedule neighbor updates
        scheduler.scheduleNeighborUpdates(pos);
    }

    /**
     * Validates if a water block should continue to exist.
     */
    public boolean shouldWaterExist(Vector3i pos, World world) {
        // Check if block is still water in the world
        BlockType blockType = world.getBlockAt(pos.x, pos.y, pos.z);
        if (blockType != BlockType.WATER) {
            return false;
        }

        // Source blocks always exist
        if (sourceBlocks.contains(pos)) {
            return true;
        }

        // Check if it has a valid source
        return hasValidSource(pos, world);
    }

    /**
     * Updates water block state based on validation.
     */
    public void validateAndUpdateWater(Vector3i pos, World world) {
        if (!shouldWaterExist(pos, world)) {
            removeWaterAt(pos, world);
        }
    }
}