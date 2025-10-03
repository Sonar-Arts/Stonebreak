package com.stonebreak.world.chunk.api.commonChunkOperations.data;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Objects;

/**
 * Zero-copy wrapper around a 3D block array for CCO API.
 * Provides bounds-checked access without defensive copying.
 *
 * NOT thread-safe for writes - synchronization must be handled externally.
 * Thread-safe for concurrent reads.
 *
 * Performance: Direct array access, < 100ns per operation.
 */
public final class CcoBlockArray {
    private final BlockType[][][] blocks;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    /**
     * Wraps an existing block array (zero-copy).
     *
     * @param blocks Block array to wrap (NOT copied)
     * @throws NullPointerException if blocks is null
     * @throws IllegalArgumentException if dimensions are invalid
     */
    private CcoBlockArray(BlockType[][][] blocks) {
        Objects.requireNonNull(blocks, "blocks cannot be null");

        if (blocks.length == 0 || blocks[0].length == 0 || blocks[0][0].length == 0) {
            throw new IllegalArgumentException("Block array dimensions cannot be zero");
        }

        this.blocks = blocks;
        this.sizeX = blocks.length;
        this.sizeY = blocks[0].length;
        this.sizeZ = blocks[0][0].length;
    }

    /**
     * Wraps an existing block array without copying.
     * Caller must not modify the array after wrapping.
     */
    public static CcoBlockArray wrap(BlockType[][][] blocks) {
        return new CcoBlockArray(blocks);
    }

    /**
     * Creates a new block array filled with AIR for a standard chunk.
     */
    public static CcoBlockArray createEmpty() {
        return createEmpty(WorldConfiguration.CHUNK_SIZE, WorldConfiguration.WORLD_HEIGHT, WorldConfiguration.CHUNK_SIZE);
    }

    /**
     * Creates a new block array filled with AIR with custom dimensions.
     */
    public static CcoBlockArray createEmpty(int sizeX, int sizeY, int sizeZ) {
        BlockType[][][] blocks = new BlockType[sizeX][sizeY][sizeZ];

        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    blocks[x][y][z] = BlockType.AIR;
                }
            }
        }

        return new CcoBlockArray(blocks);
    }

    /**
     * Gets a block at the specified local coordinates.
     *
     * @param x Local X coordinate
     * @param y Local Y coordinate
     * @param z Local Z coordinate
     * @return Block type, or AIR if out of bounds
     */
    public BlockType get(int x, int y, int z) {
        if (!isInBounds(x, y, z)) {
            return BlockType.AIR;
        }
        return blocks[x][y][z];
    }

    /**
     * Sets a block at the specified local coordinates.
     * NOT thread-safe - caller must synchronize.
     *
     * @param x Local X coordinate
     * @param y Local Y coordinate
     * @param z Local Z coordinate
     * @param block Block type to set
     * @return true if block was changed, false if out of bounds or same block
     */
    public boolean set(int x, int y, int z, BlockType block) {
        if (!isInBounds(x, y, z)) {
            return false;
        }

        BlockType current = blocks[x][y][z];
        if (current == block) {
            return false;
        }

        blocks[x][y][z] = block;
        return true;
    }

    /**
     * Checks if coordinates are within bounds.
     */
    public boolean isInBounds(int x, int y, int z) {
        return x >= 0 && x < sizeX &&
               y >= 0 && y < sizeY &&
               z >= 0 && z < sizeZ;
    }

    /**
     * Gets the underlying array (zero-copy, direct access).
     * WARNING: Modifications will affect this wrapper.
     */
    public BlockType[][][] getUnderlyingArray() {
        return blocks;
    }

    /**
     * Creates a deep copy of the block array.
     * Use sparingly - expensive operation.
     */
    public BlockType[][][] deepCopy() {
        BlockType[][][] copy = new BlockType[sizeX][sizeY][sizeZ];

        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                System.arraycopy(blocks[x][y], 0, copy[x][y], 0, sizeZ);
            }
        }

        return copy;
    }

    public int getSizeX() {
        return sizeX;
    }

    public int getSizeY() {
        return sizeY;
    }

    public int getSizeZ() {
        return sizeZ;
    }

    /**
     * Counts non-AIR blocks (useful for compression hints).
     */
    public int countNonAirBlocks() {
        int count = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    if (blocks[x][y][z] != BlockType.AIR) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Counts unique block types (useful for palette size hints).
     */
    public int countUniqueBlockTypes() {
        java.util.Set<BlockType> unique = new java.util.HashSet<>();
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    unique.add(blocks[x][y][z]);
                }
            }
        }
        return unique.size();
    }

    @Override
    public String toString() {
        return String.format("CcoBlockArray{size=%dx%dx%d}", sizeX, sizeY, sizeZ);
    }
}
