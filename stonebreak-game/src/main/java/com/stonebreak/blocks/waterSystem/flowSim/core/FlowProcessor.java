package com.stonebreak.blocks.waterSystem.flowSim.core;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.blocks.waterSystem.flowSim.algorithms.FlowAlgorithm;
import com.stonebreak.blocks.waterSystem.flowSim.algorithms.FlowValidator;
import com.stonebreak.blocks.waterSystem.flowSim.management.WaterSourceManager;
import com.stonebreak.blocks.waterSystem.flowSim.world.WorldWaterDetector;
import com.stonebreak.blocks.waterSystem.handlers.FlowBlockInteraction;
import com.stonebreak.blocks.waterSystem.states.WaterState;
import com.stonebreak.blocks.waterSystem.states.WaterStateManager;
import com.stonebreak.blocks.waterSystem.states.WaterStateManagerImpl;
import com.stonebreak.blocks.waterSystem.types.FallingWaterType;
import com.stonebreak.blocks.waterSystem.types.FlowWaterType;
import com.stonebreak.blocks.waterSystem.types.SourceWaterType;
import com.stonebreak.blocks.waterSystem.types.WaterType;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import org.joml.Vector3i;

import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Core flow processing engine that coordinates water flow updates.
 * Manages timing, processing order, and delegation to specialized algorithms.
 *
 * Following Single Responsibility Principle - only handles flow processing coordination.
 */
public class FlowProcessor {

    // Minecraft-accurate flow timing (5 game ticks = 0.25 seconds at 20 TPS)
    private static final float FLOW_UPDATE_INTERVAL = 0.5f; // Slower updates to prevent chaos

    private final Map<Vector3i, WaterBlock> waterBlocks;
    private final WaterSourceManager sourceManager;
    private final FlowUpdateScheduler scheduler;
    private final FlowAlgorithm flowAlgorithm;
    private final FlowValidator validator;
    private final WorldWaterDetector detector;
    private final WaterStateManager stateManager;

    private float timeSinceLastUpdate;

    public FlowProcessor(Map<Vector3i, WaterBlock> waterBlocks, WaterSourceManager sourceManager,
                        FlowUpdateScheduler scheduler, FlowAlgorithm flowAlgorithm,
                        FlowValidator validator, WorldWaterDetector detector) {
        this.waterBlocks = waterBlocks;
        this.sourceManager = sourceManager;
        this.scheduler = scheduler;
        this.flowAlgorithm = flowAlgorithm;
        this.validator = validator;
        this.detector = detector;
        this.stateManager = new WaterStateManagerImpl();
        this.timeSinceLastUpdate = 0.0f;
    }

    /**
     * Updates water flow physics according to Minecraft timing.
     */
    public void update(float deltaTime) {
        timeSinceLastUpdate += deltaTime;

        if (timeSinceLastUpdate >= FLOW_UPDATE_INTERVAL) {
            processFlowUpdates();
            timeSinceLastUpdate = 0.0f;
        }
    }

    /**
     * Processes all scheduled flow updates according to Minecraft mechanics.
     */
    private void processFlowUpdates() {
        World world = Game.getWorld();
        if (world == null) return;

        Set<Vector3i> processedThisUpdate = new HashSet<>();

        // Get deduplicated queue of updates
        Queue<Vector3i> currentQueue = scheduler.getAndClearUpdates();
        int queueSize = currentQueue.size();

        while (!currentQueue.isEmpty()) {
            Vector3i pos = currentQueue.poll();

            if (processedThisUpdate.contains(pos)) {
                continue;
            }

            processedThisUpdate.add(pos);
            processFlowAtPosition(pos, world);
        }
    }

    /**
     * Processes flow for a single water block position.
     */
    private void processFlowAtPosition(Vector3i pos, World world) {
        BlockType blockType = world.getBlockAt(pos.x, pos.y, pos.z);

        // Remove water block data if block is no longer water
        if (blockType != BlockType.WATER) {
            waterBlocks.remove(pos);
            sourceManager.removeSourceBlock(pos);
            return;
        }

        WaterBlock waterBlock = waterBlocks.get(pos);
        if (waterBlock == null) {
            // Create water block for existing water in world
            detector.initializeWaterBlock(pos, world);
            waterBlock = waterBlocks.get(pos);

            // Also check and initialize nearby water blocks that might not be tracked
            detector.ensureNearbyWaterTracked(pos, world);
        }

        // Ensure water block has proper type and state
        if (waterBlock.getWaterType() == null) {
            // Lazy initialization will handle this in getWaterType()
            waterBlock.getWaterType(); // This triggers lazy initialization
        }

        // Convert legacy or waterfall-created sources that are no longer tracked as true sources
        if (!sourceManager.isWaterSource(pos) && !waterBlock.isOceanWater()
                && waterBlock.getWaterType() instanceof SourceWaterType) {
            waterBlock.setWaterType(new FallingWaterType());
            waterBlock.setSource(false);
        }

        // Source blocks don't need flow processing
        if (sourceManager.isWaterSource(pos)) {
            waterBlock.setSource(true);
            waterBlock.setDepth(WaterBlock.SOURCE_DEPTH);
            waterBlock.setWaterType(new SourceWaterType());

            // Update state for source block
            WaterState sourceState = stateManager.determineState(waterBlock, pos);
            stateManager.updateState(waterBlock, sourceState, pos);

            // Generate flows from active sources
            if (sourceState == WaterState.FLOWING || sourceState.isActive()) {
                flowAlgorithm.spreadFromSource(pos, world);
                scheduler.scheduleFlowUpdate(pos);
            }

            return;
        }

        // Process flowing water
        processFlowingWater(pos, waterBlock, world);
    }

    /**
     * Processes flowing water according to Minecraft mechanics.
     * Ensures flow blocks maintain FLOWING state permanently.
     */
    private void processFlowingWater(Vector3i pos, WaterBlock waterBlock, World world) {
        // CRITICAL RULE: Ensure flow blocks always maintain FLOWING state
        WaterType waterType = waterBlock.getWaterType();
        if (waterType instanceof FlowWaterType) {
            stateManager.updateState(waterBlock, WaterState.FLOWING, pos);
        }
        // Try to flow downward first - ALWAYS prioritize downward flow
        Vector3i downPos = new Vector3i(pos.x, pos.y - 1, pos.z);
        if (canFlowTo(downPos, world)) {
            // When water flows down, depth resets to 0 at the new elevation (Minecraft behavior)
            createOrUpdateFallingWaterAt(downPos, world);
            scheduler.scheduleFlowUpdate(downPos);

            // Don't spread horizontally from falling water - this causes chaos
            // Falling water should only fall, not spread sideways
            return;
        }

        // Only check source validity for non-falling water
        if (!validator.hasValidSource(pos, world)) {
            applyDecayRemoval(pos, world);
            return;
        }

        // Spread horizontally if can't flow down
        int currentDepth = waterBlock.getDepth();
        if (currentDepth < 7) { // MAX_DEPTH
            flowAlgorithm.spreadHorizontally(pos, currentDepth, world);
        }
    }

    /**
     * Checks if water can flow to a position.
     * Now includes support for destroying flower blocks.
     */
    private boolean canFlowTo(Vector3i pos, World world) {
        if (pos.y < 0 || pos.y >= 256) { // Using fixed world height for now
            return false;
        }

        return FlowBlockInteraction.canFlowTo(pos, world);
    }

    /**
     * Creates or updates water at a position with proper type system integration.
     * Implements flow averaging when multiple flows converge.
     */
    private void createOrUpdateFallingWaterAt(Vector3i pos, World world) {
        WaterBlock existing = waterBlocks.get(pos);

        if (existing == null) {
            FlowBlockInteraction.flowTo(pos, world);

            if (world.getBlockAt(pos.x, pos.y, pos.z) == BlockType.AIR) {
                world.setBlockAt(pos.x, pos.y, pos.z, BlockType.WATER);
            }

            WaterBlock newWater = WaterBlock.createWithType(new FallingWaterType());
            WaterState state = stateManager.determineState(newWater, pos);
            stateManager.updateState(newWater, state, pos);
            waterBlocks.put(pos, newWater);
            return;
        }

        if (existing.getWaterType() instanceof SourceWaterType && sourceManager.isWaterSource(pos)) {
            return;
        }

        existing.setWaterType(new FallingWaterType());
        WaterState newState = stateManager.determineState(existing, pos);
        stateManager.updateState(existing, newState, pos);
    }

    private void applyDecayRemoval(Vector3i pos, World world) {
        WaterBlock waterBlock = waterBlocks.get(pos);
        if (waterBlock == null) {
            validator.removeWaterAt(pos, world);
            return;
        }

        if (waterBlock.getWaterType() instanceof FallingWaterType && !waterBlock.isSource()) {
            scheduler.scheduleDelayedRemoval(pos, 0.6f);
        } else {
            validator.removeWaterAt(pos, world);
        }
    }

    private void createOrUpdateWaterAt(Vector3i pos, int depth, World world) {
        WaterBlock existing = waterBlocks.get(pos);

        if (existing == null) {
            // Destroy any destructible blocks first
            FlowBlockInteraction.flowTo(pos, world);

            // Create new water block with appropriate type
            if (world.getBlockAt(pos.x, pos.y, pos.z) == BlockType.AIR) {
                world.setBlockAt(pos.x, pos.y, pos.z, BlockType.WATER);
            }

            WaterType waterType = depth == WaterBlock.SOURCE_DEPTH ?
                new SourceWaterType() : new FlowWaterType(depth);
            WaterBlock newWater = WaterBlock.createWithType(waterType);

            // Set appropriate state
            WaterState state = stateManager.determineState(newWater, pos);
            stateManager.updateState(newWater, state, pos);

            waterBlocks.put(pos, newWater);
        } else {
            WaterType existingType = existing.getWaterType();

            // CRITICAL RULE: Flow blocks NEVER override source blocks
            if (existingType instanceof SourceWaterType) {
                // Source blocks remain unchanged when flows attempt to merge
                return;
            }

            // For flow blocks, implement averaging behavior
            if (existingType instanceof FlowWaterType && depth < existing.getDepth()) {
                // Average the depths instead of just taking minimum
                int averagedDepth = (existing.getDepth() + depth) / 2;
                existing.setWaterType(new FlowWaterType(averagedDepth));

                // Update state based on new conditions
                WaterState newState = stateManager.determineState(existing, pos);
                stateManager.updateState(existing, newState, pos);
            }
        }
    }

    /**
     * Gets the current processing statistics.
     */
    public ProcessingStats getStats() {
        return new ProcessingStats(
            waterBlocks.size(),
            sourceManager.getSourceCount(),
            scheduler.getPendingUpdateCount(),
            timeSinceLastUpdate
        );
    }

    /**
     * Resets the update timer.
     */
    public void resetUpdateTimer() {
        timeSinceLastUpdate = 0.0f;
    }

    /**
     * Statistics class for monitoring flow processing.
     */
    public static class ProcessingStats {
        public final int totalWaterBlocks;
        public final int sourceBlocks;
        public final int pendingUpdates;
        public final float timeSinceLastUpdate;

        public ProcessingStats(int totalWaterBlocks, int sourceBlocks, int pendingUpdates, float timeSinceLastUpdate) {
            this.totalWaterBlocks = totalWaterBlocks;
            this.sourceBlocks = sourceBlocks;
            this.pendingUpdates = pendingUpdates;
            this.timeSinceLastUpdate = timeSinceLastUpdate;
        }
    }
}