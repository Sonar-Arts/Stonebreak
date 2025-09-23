package com.stonebreak.blocks.waterSystem.flowSim.management;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.blocks.waterSystem.flowSim.core.FlowUpdateScheduler;
import com.stonebreak.blocks.waterSystem.types.OceanWaterType;
import com.stonebreak.blocks.waterSystem.types.SourceWaterType;
import com.stonebreak.blocks.waterSystem.types.WaterType;
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
        addWaterSource(x, y, z, false);
    }

    /**
     * Adds a water source block at the specified position with ocean water designation.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @param isOceanWater true if this is world-generated ocean water
     */
    public void addWaterSource(int x, int y, int z, boolean isOceanWater) {
        World world = Game.getWorld();
        if (world == null) return;

        Vector3i pos = new Vector3i(x, y, z);

        // Set block in world
        world.setBlockAt(x, y, z, BlockType.WATER);

        // Create source water block with appropriate type
        WaterType waterType = isOceanWater ? new OceanWaterType() : new SourceWaterType();
        WaterBlock waterBlock = WaterBlock.createWithType(waterType);

        if (isOceanWater) {
            waterBlock.setOceanWater(true);
        }

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

    /**
     * Adds an ocean water source block at the specified position.
     * This is a convenience method for world generation.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    public void addOceanWaterSource(int x, int y, int z) {
        addWaterSource(x, y, z, true);
    }

    /**
     * Checks if a water source at the given position is ocean water.
     *
     * @param pos Position to check
     * @return true if the source is ocean water
     */
    public boolean isOceanWaterSource(Vector3i pos) {
        WaterBlock waterBlock = waterBlocks.get(pos);
        if (waterBlock == null) {
            return false;
        }

        WaterType waterType = waterBlock.getWaterType();
        return waterType instanceof OceanWaterType;
    }

    /**
     * Checks if a water source at the given coordinates is ocean water.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return true if the source is ocean water
     */
    public boolean isOceanWaterSource(int x, int y, int z) {
        return isOceanWaterSource(new Vector3i(x, y, z));
    }

    /**
     * Gets the water type of a source block at the given position.
     *
     * @param pos Position to check
     * @return The water type, or null if not a source block
     */
    public WaterType getSourceWaterType(Vector3i pos) {
        if (!isWaterSource(pos)) {
            return null;
        }

        WaterBlock waterBlock = waterBlocks.get(pos);
        return waterBlock != null ? waterBlock.getWaterType() : null;
    }

    /**
     * Converts a regular source block to ocean water.
     * Used for world generation updates.
     *
     * @param pos Position of the source block
     * @return true if successfully converted
     */
    public boolean convertToOceanWater(Vector3i pos) {
        if (!isWaterSource(pos)) {
            return false;
        }

        WaterBlock waterBlock = waterBlocks.get(pos);
        if (waterBlock == null) {
            return false;
        }

        waterBlock.setWaterType(new OceanWaterType());
        waterBlock.setOceanWater(true);
        return true;
    }
}