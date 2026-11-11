package com.stonebreak.world.leaves;

import java.util.ArrayList;
import java.util.List;

import com.stonebreak.blocks.BlockType;

/**
 * In-memory {@link LeafWorld} for exercising {@link LeafDecaySystem} without a
 * World: a bounded region of blocks. Positions outside the region are unloaded
 * ({@code isLoaded} false, AIR) mirroring the game's unloaded-chunk contract.
 */
final class FakeLeafWorld implements LeafWorld {

    final int sizeX;
    final int sizeY;
    final int sizeZ;
    private final BlockType[][][] blocks;

    /** Tracks leaves the sim removed (set to AIR), for decay assertions. */
    final List<int[]> removals = new ArrayList<>();

    /** Cells marked player-placed; cleared when the cell changes type, like the chunk state map. */
    private final java.util.Set<Long> persistent = new java.util.HashSet<>();

    FakeLeafWorld(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.blocks = new BlockType[sizeX][sizeY][sizeZ];
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
        BlockType previous = blocks[x][y][z];
        blocks[x][y][z] = type;
        if (previous != type) {
            persistent.remove(key(x, y, z));
        }
        if (previous != null && previous.isLeaves() && type == BlockType.AIR) {
            removals.add(new int[]{x, y, z});
        }
    }

    @Override
    public boolean isPersistent(int x, int y, int z) {
        return persistent.contains(key(x, y, z));
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 40) | ((long) y << 20) | z;
    }

    // ===== Test helpers =====

    /**
     * Mirrors a player placing a leaf: the block goes through the funnel, then the
     * server writes the placed marker in the same tick (before the delayed recompute).
     */
    void placeByPlayer(LeafDecaySystem sim, int x, int y, int z, BlockType type) {
        placeBlock(sim, x, y, z, type);
        persistent.add(key(x, y, z));
    }

    /** Mirrors World.setBlockAt: writes the block and feeds the sim funnel. */
    void placeBlock(LeafDecaySystem sim, int x, int y, int z, BlockType type) {
        BlockType previous = getBlock(x, y, z);
        setBlock(x, y, z, type);
        sim.onBlockChanged(x, y, z, previous, type);
    }

    int countLeaves() {
        int count = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    if (blocks[x][y][z] != null && blocks[x][y][z].isLeaves()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    boolean isLeaf(int x, int y, int z) {
        BlockType block = getBlock(x, y, z);
        return block != null && block.isLeaves();
    }

    boolean isLog(int x, int y, int z) {
        BlockType block = getBlock(x, y, z);
        return block != null && block.isLog();
    }
}
