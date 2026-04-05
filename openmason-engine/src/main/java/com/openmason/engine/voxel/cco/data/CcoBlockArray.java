package com.openmason.engine.voxel.cco.data;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.VoxelWorldConfig;

import java.lang.reflect.Array;
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
    private final IBlockType[][][] blocks;
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
    private CcoBlockArray(IBlockType[][][] blocks) {
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
    public static CcoBlockArray wrap(IBlockType[][][] blocks) {
        return new CcoBlockArray(blocks);
    }

    /**
     * Creates a new block array filled with the given air block for a standard chunk.
     *
     * @param config Voxel world configuration providing chunk dimensions
     * @param airBlock Block type to use as the default fill (air)
     */
    public static CcoBlockArray createEmpty(VoxelWorldConfig config, IBlockType airBlock) {
        return createEmpty(config.chunkSize(), config.worldHeight(), config.chunkSize(), airBlock);
    }

    /**
     * Creates a new block array filled with the given air block with custom dimensions.
     *
     * @param sizeX X dimension
     * @param sizeY Y dimension
     * @param sizeZ Z dimension
     * @param airBlock Block type to use as the default fill (air)
     */
    public static CcoBlockArray createEmpty(int sizeX, int sizeY, int sizeZ, IBlockType airBlock) {
        // Create array with the concrete runtime type of airBlock so that
        // game code can safely cast back to its own block type array
        Class<?> blockClass = airBlock.getClass();
        IBlockType[][][] blocks = (IBlockType[][][]) Array.newInstance(blockClass, sizeX, sizeY, sizeZ);

        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    blocks[x][y][z] = airBlock;
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
     * @return Block type, or null if out of bounds
     */
    public IBlockType get(int x, int y, int z) {
        if (!isInBounds(x, y, z)) {
            return null;
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
    public boolean set(int x, int y, int z, IBlockType block) {
        if (!isInBounds(x, y, z)) {
            return false;
        }

        IBlockType current = blocks[x][y][z];
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
    public IBlockType[][][] getUnderlyingArray() {
        return blocks;
    }

    /**
     * Creates a deep copy of the block array.
     * Use sparingly - expensive operation.
     */
    public IBlockType[][][] deepCopy() {
        // Preserve the runtime component type so casts back to concrete arrays succeed
        Class<?> componentType = blocks.getClass().getComponentType().getComponentType().getComponentType();
        IBlockType[][][] copy = (IBlockType[][][]) Array.newInstance(componentType, sizeX, sizeY, sizeZ);

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
     * Counts non-air blocks (useful for compression hints).
     */
    public int countNonAirBlocks() {
        int count = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    IBlockType block = blocks[x][y][z];
                    if (block != null && !block.isAir()) {
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
        java.util.Set<IBlockType> unique = new java.util.HashSet<>();
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    if (blocks[x][y][z] != null) {
                        unique.add(blocks[x][y][z]);
                    }
                }
            }
        }
        return unique.size();
    }

    /**
     * Clears all block references by nulling out the underlying array.
     * Used for memory cleanup when unloading chunks.
     * After calling this, the CcoBlockArray should not be used.
     */
    public void clear() {
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                if (blocks[x][y] != null) {
                    for (int z = 0; z < sizeZ; z++) {
                        blocks[x][y][z] = null;
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return String.format("CcoBlockArray{size=%dx%dx%d}", sizeX, sizeY, sizeZ);
    }
}
