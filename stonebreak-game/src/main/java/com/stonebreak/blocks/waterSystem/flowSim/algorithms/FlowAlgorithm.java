package com.stonebreak.blocks.waterSystem.flowSim.algorithms;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.blocks.waterSystem.flowSim.core.FlowUpdateScheduler;
import com.stonebreak.blocks.waterSystem.handlers.FlowBlockInteraction;
import com.stonebreak.blocks.waterSystem.types.FallingWaterType;
import com.stonebreak.blocks.waterSystem.types.FlowWaterType;
import com.stonebreak.blocks.waterSystem.types.SourceWaterType;
import com.stonebreak.blocks.waterSystem.types.WaterType;
import com.stonebreak.world.World;
import org.joml.Vector3i;

import java.util.HashMap;
import java.util.Map;

/**
 * Implements core water flow algorithms following Minecraft mechanics.
 * Handles horizontal spreading, depth calculations, and flow direction preferences.
 *
 * Following Single Responsibility Principle - only handles flow algorithms.
 */
public class FlowAlgorithm {

    private static final int MAX_HORIZONTAL_DISTANCE = 7;
    private static final int MAX_DEPTH = 7;
    private static final int MIN_FLOW_WEIGHT = 1000;

    private final PathfindingService pathfindingService;
    private final FlowUpdateScheduler scheduler;
    private final Map<Vector3i, WaterBlock> waterBlocks;

    public FlowAlgorithm(PathfindingService pathfindingService, FlowUpdateScheduler scheduler, Map<Vector3i, WaterBlock> waterBlocks) {
        this.pathfindingService = pathfindingService;
        this.scheduler = scheduler;
        this.waterBlocks = waterBlocks;
    }

    /**
     * Spreads water from a source block using Minecraft's algorithm.
     */
    public void spreadFromSource(Vector3i sourcePos, World world) {
        // Always try downward flow first (water falls down)
        Vector3i downPos = new Vector3i(sourcePos.x, sourcePos.y - 1, sourcePos.z);
        if (FlowBlockInteraction.canFlowTo(downPos, world)) {
            createOrUpdateFallingWaterAt(downPos, world);
            scheduler.scheduleFlowUpdate(downPos);
        }

        // Then spread horizontally from sources
        spreadHorizontally(sourcePos, WaterBlock.SOURCE_DEPTH, world);
    }

    /**
     * Spreads water horizontally using Minecraft's flow weight algorithm.
     */
    public void spreadHorizontally(Vector3i pos, int currentDepth, World world) {
        Vector3i[] directions = {
            new Vector3i(1, 0, 0),   // East
            new Vector3i(-1, 0, 0),  // West
            new Vector3i(0, 0, 1),   // South
            new Vector3i(0, 0, -1)   // North
        };

        // Calculate flow weights for each direction
        Map<Vector3i, Integer> flowWeights = calculateFlowWeights(pos, directions, world);

        if (flowWeights.isEmpty()) {
            return;
        }

        // Find the minimum weight
        int minWeight = flowWeights.values().stream()
            .mapToInt(Integer::intValue)
            .min()
            .orElse(MIN_FLOW_WEIGHT);

        // Flow to all directions with minimum weight
        // In Minecraft, water flows equally in all valid directions
        boolean isSource = (currentDepth == WaterBlock.SOURCE_DEPTH);
        int flowCount = 0;

        for (Map.Entry<Vector3i, Integer> entry : flowWeights.entrySet()) {
            Vector3i targetPos = entry.getKey();
            int newDepth = isSource ? 1 : currentDepth + 1; // Sources create depth 1, flowing water increments

            // Only flow if within the maximum depth range
            if (newDepth <= MAX_DEPTH) {
                // Flow to all directions with the minimum weight
                // This ensures water spreads even when no edges are nearby
                if (entry.getValue() == minWeight) {
                    createOrUpdateWaterAt(targetPos, newDepth, world);
                    scheduler.scheduleFlowUpdate(targetPos);
                    flowCount++;
                }
            }
        }
    }

    /**
     * Calculates flow weights for each direction using Minecraft's algorithm.
     */
    public Map<Vector3i, Integer> calculateFlowWeights(Vector3i pos, Vector3i[] directions, World world) {
        Map<Vector3i, Integer> weights = new HashMap<>();

        Map<Vector3i, Integer> edgeDistances = pathfindingService.findEdgeDistances(pos, world, 5);

        boolean isSourcePosition = waterBlocks.containsKey(pos) &&
            waterBlocks.get(pos).getDepth() == WaterBlock.SOURCE_DEPTH;

        for (Vector3i dir : directions) {
            Vector3i targetPos = new Vector3i(pos).add(dir);

            if (!FlowBlockInteraction.canFlowTo(targetPos, world)) {
                continue;
            }

            // If this is a source position, ensure it has unblocked side faces
            if (isSourcePosition && !hasUnblockedSideFaceForDirection(pos, dir, world)) {
                continue; // Source is blocked in this direction
            }

            // Check if target already has water - we can still flow if our depth is better
            WaterBlock existingWater = waterBlocks.get(targetPos);
            int sourceDepth = waterBlocks.get(pos) != null ? waterBlocks.get(pos).getDepth() : WaterBlock.SOURCE_DEPTH;

            if (existingWater != null) {
                // Only flow if we can provide better (lower) depth
                int targetDepth = existingWater.getDepth();
                int newDepth = sourceDepth + 1;
                if (newDepth >= targetDepth) {
                    continue; // Our water wouldn't be better
                }
            }

            // Calculate weight based on distance to nearest edge
            // Lower distance = lower weight = higher priority
            int weight = MIN_FLOW_WEIGHT;

            // Check if this direction leads toward an edge
            if (edgeDistances.containsKey(targetPos)) {
                weight = edgeDistances.get(targetPos);
            } else {
                // Also check if moving in this direction gets us closer to any edge
                int pathToDown = pathfindingService.findShortestPathToDown(targetPos, world, 5);
                if (pathToDown >= 0) {
                    weight = pathToDown + 1; // Add 1 since we're one step further
                }
            }

            weights.put(targetPos, weight);
        }

        return weights;
    }

    /**
     * Creates or updates water at a position with proper type system and flower breaking.
     */
    private void createOrUpdateFallingWaterAt(Vector3i pos, World world) {
        WaterBlock existing = waterBlocks.get(pos);

        if (existing == null) {
            FlowBlockInteraction.flowTo(pos, world);

            if (world.getBlockAt(pos.x, pos.y, pos.z) == BlockType.AIR) {
                world.setBlockAt(pos.x, pos.y, pos.z, BlockType.WATER);
            }

            WaterBlock newWater = WaterBlock.createWithType(new FallingWaterType());
            waterBlocks.put(pos, newWater);
            return;
        }

        if (existing.getWaterType() instanceof SourceWaterType) {
            return;
        }

        existing.setWaterType(new FallingWaterType());
    }

    private void createOrUpdateWaterAt(Vector3i pos, int depth, World world) {
        WaterBlock existing = waterBlocks.get(pos);

        if (existing == null) {
            // Use FlowBlockInteraction to handle destructible blocks like flowers
            FlowBlockInteraction.flowTo(pos, world);

            // Create new water block with appropriate type
            if (world.getBlockAt(pos.x, pos.y, pos.z) == com.stonebreak.blocks.BlockType.AIR) {
                world.setBlockAt(pos.x, pos.y, pos.z, com.stonebreak.blocks.BlockType.WATER);
            }

            // CRITICAL FIX: Always create FlowWaterType in createOrUpdateWaterAt
            // This method is for creating flowing water, never source blocks
            // Even if depth is 0, it should be flow water, not a source
            WaterType waterType = new FlowWaterType(depth);
            WaterBlock newWater = WaterBlock.createWithType(waterType);
            waterBlocks.put(pos, newWater);
        } else {
            WaterType existingType = existing.getWaterType();

            // CRITICAL RULE: Flow blocks NEVER override source blocks
            if (existingType instanceof SourceWaterType) {
                return; // Source blocks remain unchanged
            }

            // For flow blocks, use averaging and respect type system
            if (existingType instanceof FlowWaterType && depth < existing.getDepth()) {
                // Average the depths for flow convergence
                int averagedDepth = (existing.getDepth() + depth) / 2;
                existing.setWaterType(new FlowWaterType(averagedDepth));
            }
        }
    }

    /**
     * Checks if a source block has an unblocked side face in a specific direction.
     * This ensures that source blocks can only flow when their side faces are accessible.
     *
     * @param sourcePos Position of the source block
     * @param direction Direction vector to check
     * @param world The world instance
     * @return true if the side face in this direction is unblocked
     */
    private boolean hasUnblockedSideFaceForDirection(Vector3i sourcePos, Vector3i direction, World world) {
        // Calculate the position of the side face we're checking
        Vector3i sideFacePos = new Vector3i(sourcePos).add(direction);
        BlockType blockType = world.getBlockAt(sideFacePos.x, sideFacePos.y, sideFacePos.z);

        // Side face is unblocked if it's air, water, or a destructible block
        return blockType == BlockType.AIR ||
               blockType == BlockType.WATER ||
               FlowBlockInteraction.canFlowDestroy(blockType);
    }

}