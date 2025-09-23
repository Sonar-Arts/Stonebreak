package com.stonebreak.blocks.waterSystem;

import com.stonebreak.blocks.waterSystem.flowSim.algorithms.FlowAlgorithm;
import com.stonebreak.blocks.waterSystem.flowSim.algorithms.FlowValidator;
import com.stonebreak.blocks.waterSystem.flowSim.algorithms.PathfindingService;
import com.stonebreak.blocks.waterSystem.flowSim.core.FlowProcessor;
import com.stonebreak.blocks.waterSystem.flowSim.core.FlowUpdateScheduler;
import com.stonebreak.blocks.waterSystem.flowSim.management.WaterQueryService;
import com.stonebreak.blocks.waterSystem.flowSim.management.WaterSourceManager;
import com.stonebreak.blocks.waterSystem.flowSim.world.WorldEventHandler;
import com.stonebreak.blocks.waterSystem.flowSim.world.WorldWaterDetector;
import org.joml.Vector3i;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft-accurate fluid flow simulation following the exact mechanics from the Minecraft Wiki.
 *
 * Refactored to use composition pattern with specialized sub-modules following SOLID principles.
 * This class now serves as a facade coordinating the various flow simulation components.
 *
 * Key mechanics implemented:
 * - Water spreads 1 block every 5 game ticks (4 blocks per second)
 * - Source blocks have depth 0, flowing water has depth 1-7
 * - Maximum horizontal spread of 7 blocks from source or reset point
 * - Depth resets to 0 when water flows down to a new elevation
 * - Flow prefers edges for aesthetic waterfall creation
 * - Diamond-shaped spread pattern (15 blocks point-to-point on flat surface)
 * - Vertical flow always takes priority over horizontal flow
 * - Water can flow infinitely downward but only 7 blocks horizontally
 * - Source blocks only spread horizontally when they cannot flow down
 */
public class FlowSimulation {

    // Core data structures shared across modules
    private final Map<Vector3i, WaterBlock> waterBlocks;
    private final Set<Vector3i> sourceBlocks;

    // Specialized sub-modules following Single Responsibility Principle
    private final FlowUpdateScheduler scheduler;
    private final WaterSourceManager sourceManager;
    private final WaterQueryService queryService;
    private final PathfindingService pathfindingService;
    private final FlowValidator validator;
    private final FlowAlgorithm flowAlgorithm;
    private final WorldWaterDetector waterDetector;
    private final WorldEventHandler eventHandler;
    private final FlowProcessor processor;

    public FlowSimulation() {
        // Initialize core data structures
        this.waterBlocks = new ConcurrentHashMap<>();
        this.sourceBlocks = Collections.synchronizedSet(new HashSet<>());

        // Initialize sub-modules with dependency injection
        this.scheduler = new FlowUpdateScheduler();
        this.sourceManager = new WaterSourceManager(waterBlocks, scheduler);
        this.queryService = new WaterQueryService(waterBlocks);
        this.pathfindingService = new PathfindingService();
        this.validator = new FlowValidator(waterBlocks, sourceBlocks, scheduler);
        this.flowAlgorithm = new FlowAlgorithm(pathfindingService, scheduler, waterBlocks);
        this.waterDetector = new WorldWaterDetector(waterBlocks, sourceManager, scheduler, validator);
        this.eventHandler = new WorldEventHandler(waterBlocks, scheduler, validator, waterDetector);
        this.processor = new FlowProcessor(waterBlocks, sourceManager, scheduler, flowAlgorithm, validator, waterDetector);
    }

    /**
     * Updates water flow physics according to Minecraft timing.
     */
    public void update(float deltaTime) {
        processor.update(deltaTime);
    }

    /**
     * Adds a water source block at the specified position.
     */
    public void addWaterSource(int x, int y, int z) {
        sourceManager.addWaterSource(x, y, z);
    }

    /**
     * Removes a water source at the specified position.
     */
    public void removeWaterSource(int x, int y, int z) {
        sourceManager.removeWaterSource(x, y, z);
    }

    /**
     * Gets the water level (0.0 to 1.0) at a position.
     */
    public float getWaterLevel(int x, int y, int z) {
        return queryService.getWaterLevel(x, y, z);
    }

    /**
     * Checks if a position contains a water source.
     */
    public boolean isWaterSource(int x, int y, int z) {
        return sourceManager.isWaterSource(x, y, z);
    }

    /**
     * Gets the visual height for rendering.
     */
    public float getWaterVisualHeight(int x, int y, int z) {
        return queryService.getWaterVisualHeight(x, y, z);
    }

    /**
     * Gets foam intensity for visual effects.
     */
    public float getFoamIntensity(float x, float y, float z) {
        return queryService.getFoamIntensity(x, y, z);
    }

    /**
     * Gets a water block at the specified position.
     */
    public WaterBlock getWaterBlock(int x, int y, int z) {
        return queryService.getWaterBlock(x, y, z);
    }

    /**
     * Gets a water block at the specified position.
     */
    public WaterBlock getWaterBlock(Vector3i pos) {
        return queryService.getWaterBlock(pos.x, pos.y, pos.z);
    }

    /**
     * Detects existing water blocks in the world and initializes them in the simulation.
     * This preserves naturally generated oceans and other pre-existing water.
     */
    public void detectExistingWater() {
        waterDetector.detectExistingWater();
    }

    /**
     * Handles block broken events.
     */
    public void onBlockBroken(int x, int y, int z) {
        eventHandler.onBlockBroken(x, y, z);
    }

    /**
     * Handles block placed events.
     */
    public void onBlockPlaced(int x, int y, int z) {
        eventHandler.onBlockPlaced(x, y, z);
    }

    // Additional public methods for external access

    /**
     * Gets processing statistics for monitoring and debugging.
     */
    public FlowProcessor.ProcessingStats getProcessingStats() {
        return processor.getStats();
    }

    /**
     * Clears all water simulation data.
     */
    public void clear() {
        waterBlocks.clear();
        sourceManager.clear();
        scheduler.clear();
    }

    /**
     * Gets the total number of tracked water blocks.
     */
    public int getWaterBlockCount() {
        return queryService.getTrackedWaterCount();
    }

    /**
     * Gets the total number of source blocks.
     */
    public int getSourceBlockCount() {
        return sourceManager.getSourceCount();
    }

    /**
     * Checks if there are any pending flow updates.
     */
    public boolean hasPendingUpdates() {
        return scheduler.hasPendingUpdates();
    }
}