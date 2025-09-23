package com.stonebreak.blocks.waterSystem.flowSim.management;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.blocks.waterSystem.flowSim.core.FlowUpdateScheduler;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import org.joml.Vector3i;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Manages water source blocks and their lifecycle.
 * Handles creation, removal, and tracking of water sources.
 *
 * Following Single Responsibility Principle - only handles source management.
 */
public class WaterSourceManager {

    private final Set<Vector3i> sourceBlocks;
    private final Map<Vector3i, WaterBlock> waterBlocks;
    private final FlowUpdateScheduler scheduler;

    public WaterSourceManager(Map<Vector3i, WaterBlock> waterBlocks, FlowUpdateScheduler scheduler) {
        this.sourceBlocks = Collections.synchronizedSet(new HashSet<>());
        this.waterBlocks = waterBlocks;
        this.scheduler = scheduler;
    }

    /**
     * Adds a water source block at the specified position.
     */
    public void addWaterSource(int x, int y, int z) {
        World world = Game.getWorld();
        if (world == null) return;

        Vector3i pos = new Vector3i(x, y, z);

        // Set block in world
        world.setBlockAt(x, y, z, BlockType.WATER);

        // Create source water block (depth 0)
        WaterBlock waterBlock = new WaterBlock(WaterBlock.SOURCE_DEPTH);
        waterBlock.setSource(true);

        waterBlocks.put(pos, waterBlock);
        sourceBlocks.add(pos);

        // Schedule immediate flow update
        scheduler.scheduleFlowUpdate(pos);
    }

    /**
     * Removes a water source at the specified position.
     */
    public void removeWaterSource(int x, int y, int z) {
        Vector3i pos = new Vector3i(x, y, z);

        sourceBlocks.remove(pos);
        WaterBlock waterBlock = waterBlocks.get(pos);

        if (waterBlock != null) {
            waterBlock.setSource(false);
            scheduler.scheduleFlowUpdate(pos);
            scheduler.scheduleNeighborUpdates(pos);
        }
    }

    /**
     * Checks if a position contains a water source.
     */
    public boolean isWaterSource(int x, int y, int z) {
        return sourceBlocks.contains(new Vector3i(x, y, z));
    }

    /**
     * Checks if a position vector contains a water source.
     */
    public boolean isWaterSource(Vector3i pos) {
        return sourceBlocks.contains(pos);
    }

    /**
     * Gets all current source block positions.
     */
    public Set<Vector3i> getSourceBlocks() {
        return Collections.unmodifiableSet(sourceBlocks);
    }

    /**
     * Adds a source block position to tracking (for initialization).
     */
    public void addSourceBlock(Vector3i pos) {
        sourceBlocks.add(pos);
    }

    /**
     * Removes a source block position from tracking.
     */
    public void removeSourceBlock(Vector3i pos) {
        sourceBlocks.remove(pos);
    }

    /**
     * Clears all source blocks.
     */
    public void clear() {
        sourceBlocks.clear();
    }

    /**
     * Gets the total number of source blocks.
     */
    public int getSourceCount() {
        return sourceBlocks.size();
    }
}