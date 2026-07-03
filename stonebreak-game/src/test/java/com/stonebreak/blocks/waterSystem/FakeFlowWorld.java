package com.stonebreak.blocks.waterSystem;

import java.util.ArrayList;
import java.util.List;

import com.stonebreak.blocks.BlockType;

/**
 * In-memory {@link FlowWorld} for exercising {@link WaterSim} without a World:
 * a bounded region of blocks + water values. Positions outside the region are
 * unloaded (isLoaded false, AIR), mirroring the game's unloaded-chunk contract.
 * {@link #setBlock} mirrors the Chunk.setBlock invariant: a non-WATER write
 * clears the cell's water value.
 */
final class FakeFlowWorld implements FlowWorld {

    final int sizeX;
    final int sizeY;
    final int sizeZ;
    private final BlockType[][][] blocks;
    private final int[][][] water;

    final List<int[]> fragileDrops = new ArrayList<>();

    FakeFlowWorld(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.blocks = new BlockType[sizeX][sizeY][sizeZ];
        this.water = new int[sizeX][sizeY][sizeZ];
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    blocks[x][y][z] = BlockType.AIR;
                }
            }
        }
    }

    private boolean inRegion(int x, int y, int z) {
        return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ;
    }

    @Override
    public BlockType getBlock(int x, int y, int z) {
        return inRegion(x, y, z) ? blocks[x][y][z] : BlockType.AIR;
    }

    @Override
    public boolean isLoaded(int x, int y, int z) {
        return inRegion(x, y, z);
    }

    @Override
    public void setBlock(int x, int y, int z, BlockType type) {
        if (!inRegion(x, y, z)) {
            return;
        }
        blocks[x][y][z] = type;
        if (type != BlockType.WATER) {
            water[x][y][z] = 0;
        }
    }

    @Override
    public int getWater(int x, int y, int z) {
        return inRegion(x, y, z) ? water[x][y][z] : 0;
    }

    @Override
    public void setWater(int x, int y, int z, int value) {
        if (inRegion(x, y, z)) {
            water[x][y][z] = value;
        }
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        BlockType block = getBlock(x, y, z);
        return block != null && block.isSolid();
    }

    @Override
    public void dropFragile(int x, int y, int z, BlockType type) {
        fragileDrops.add(new int[]{x, y, z});
    }

    @Override
    public void markWaterChanged(int x, int y, int z, int newValue) {
    }

    // ===== Test helpers =====

    /** Fills the full horizontal extent at the given y with a block type. */
    void fillLayer(int y, BlockType type) {
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                blocks[x][y][z] = type;
            }
        }
    }

    /** Mirrors World.setBlockAt: writes the block and feeds the sim funnel. */
    void placeBlock(WaterSim sim, int x, int y, int z, BlockType type) {
        BlockType previous = getBlock(x, y, z);
        setBlock(x, y, z, type);
        sim.onBlockChanged(x, y, z, previous, type);
    }

    /** Places a water source (block WATER, no layer value) via the funnel. */
    void placeSource(WaterSim sim, int x, int y, int z) {
        placeBlock(sim, x, y, z, BlockType.WATER);
    }

    int countWaterBlocks() {
        int count = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    if (blocks[x][y][z] == BlockType.WATER) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    int countSources() {
        int count = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    if (blocks[x][y][z] == BlockType.WATER && water[x][y][z] == 0) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Snapshot of (block==WATER, value) state for oscillation checks. */
    long stateHash() {
        long hash = 1469598103934665603L;
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    int cell = (blocks[x][y][z] == BlockType.WATER ? 16 : 0) | water[x][y][z];
                    hash = (hash ^ cell) * 1099511628211L;
                }
            }
        }
        return hash;
    }

    /** Runs logical ticks until the sim queue drains; fails the test if it never does. */
    static void tickUntilQuiet(WaterSim sim, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            if (sim.getQueuedUpdateCount() == 0) {
                return;
            }
            sim.advanceTicks(1);
        }
        throw new AssertionError("Water sim did not settle within " + maxTicks + " ticks; "
            + sim.getQueuedUpdateCount() + " updates still queued");
    }
}
