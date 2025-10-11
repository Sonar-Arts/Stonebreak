package com.stonebreak.world.chunk.api.commonChunkOperations.operations;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.api.commonChunkOperations.coordinates.CcoBounds;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoBlockArray;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoDirtyTracker;

import java.util.Objects;

/**
 * Batch block operations for CCO chunks.
 * Optimized for bulk modifications with single dirty mark.
 *
 * NOT thread-safe - caller must synchronize.
 * Performance: Amortized dirty tracking overhead across many operations.
 */
public final class CcoBulkOperations {

    private final CcoBlockArray blocks;
    private final CcoDirtyTracker dirtyTracker;

    /**
     * Creates bulk operations handler.
     *
     * @param blocks Block array
     * @param dirtyTracker Dirty tracker
     */
    public CcoBulkOperations(CcoBlockArray blocks, CcoDirtyTracker dirtyTracker) {
        this.blocks = Objects.requireNonNull(blocks, "blocks cannot be null");
        this.dirtyTracker = Objects.requireNonNull(dirtyTracker, "dirtyTracker cannot be null");
    }

    /**
     * Fills a region with the specified block type.
     * Marks dirty only once if any blocks changed.
     *
     * @param startX Start X coordinate
     * @param startY Start Y coordinate
     * @param startZ Start Z coordinate
     * @param endX End X coordinate (inclusive)
     * @param endY End Y coordinate (inclusive)
     * @param endZ End Z coordinate (inclusive)
     * @param block Block type to fill
     * @return Number of blocks changed
     */
    public int fill(int startX, int startY, int startZ, int endX, int endY, int endZ, BlockType block) {
        if (block == null) {
            block = BlockType.AIR;
        }

        // Clamp to valid bounds
        startX = Math.max(0, startX);
        startY = Math.max(0, startY);
        startZ = Math.max(0, startZ);
        endX = Math.min(blocks.getSizeX() - 1, endX);
        endY = Math.min(blocks.getSizeY() - 1, endY);
        endZ = Math.min(blocks.getSizeZ() - 1, endZ);

        int changedCount = 0;
        BlockType[][][] array = blocks.getUnderlyingArray();

        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    if (array[x][y][z] != block) {
                        array[x][y][z] = block;
                        changedCount++;
                    }
                }
            }
        }

        if (changedCount > 0) {
            dirtyTracker.markBlockChanged();
        }

        return changedCount;
    }

    /**
     * Replaces all blocks of one type with another.
     *
     * @param from Block type to replace
     * @param to Block type to replace with
     * @return Number of blocks changed
     */
    public int replaceAll(BlockType from, BlockType to) {
        if (from == null || to == null || from == to) {
            return 0;
        }

        int changedCount = 0;
        BlockType[][][] array = blocks.getUnderlyingArray();

        for (int x = 0; x < blocks.getSizeX(); x++) {
            for (int y = 0; y < blocks.getSizeY(); y++) {
                for (int z = 0; z < blocks.getSizeZ(); z++) {
                    if (array[x][y][z] == from) {
                        array[x][y][z] = to;
                        changedCount++;
                    }
                }
            }
        }

        if (changedCount > 0) {
            dirtyTracker.markBlockChanged();
        }

        return changedCount;
    }

    /**
     * Clears entire chunk (sets all to AIR).
     *
     * @return Number of blocks cleared
     */
    public int clear() {
        return fill(0, 0, 0,
                blocks.getSizeX() - 1,
                blocks.getSizeY() - 1,
                blocks.getSizeZ() - 1,
                BlockType.AIR);
    }

    /**
     * Copies blocks from a source array to this chunk.
     * Assumes source has same dimensions.
     *
     * @param source Source block array
     * @return Number of blocks changed
     */
    public int copyFrom(BlockType[][][] source) {
        if (source == null) {
            return 0;
        }

        int changedCount = 0;
        BlockType[][][] array = blocks.getUnderlyingArray();

        int sizeX = Math.min(blocks.getSizeX(), source.length);
        int sizeY = source.length > 0 ? Math.min(blocks.getSizeY(), source[0].length) : 0;
        int sizeZ = source.length > 0 && source[0].length > 0 ?
                Math.min(blocks.getSizeZ(), source[0][0].length) : 0;

        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    BlockType sourceBlock = source[x][y][z];
                    if (sourceBlock == null) {
                        sourceBlock = BlockType.AIR;
                    }

                    if (array[x][y][z] != sourceBlock) {
                        array[x][y][z] = sourceBlock;
                        changedCount++;
                    }
                }
            }
        }

        if (changedCount > 0) {
            dirtyTracker.markBlockChanged();
        }

        return changedCount;
    }

    /**
     * Fills a sphere with the specified block type.
     *
     * @param centerX Center X coordinate
     * @param centerY Center Y coordinate
     * @param centerZ Center Z coordinate
     * @param radius Sphere radius
     * @param block Block type to fill
     * @return Number of blocks changed
     */
    public int fillSphere(int centerX, int centerY, int centerZ, int radius, BlockType block) {
        if (block == null) {
            block = BlockType.AIR;
        }

        if (radius <= 0) {
            return 0;
        }

        int changedCount = 0;
        int radiusSquared = radius * radius;
        BlockType[][][] array = blocks.getUnderlyingArray();

        int startX = Math.max(0, centerX - radius);
        int endX = Math.min(blocks.getSizeX() - 1, centerX + radius);
        int startY = Math.max(0, centerY - radius);
        int endY = Math.min(blocks.getSizeY() - 1, centerY + radius);
        int startZ = Math.max(0, centerZ - radius);
        int endZ = Math.min(blocks.getSizeZ() - 1, centerZ + radius);

        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    int dx = x - centerX;
                    int dy = y - centerY;
                    int dz = z - centerZ;
                    int distSquared = dx * dx + dy * dy + dz * dz;

                    if (distSquared <= radiusSquared && array[x][y][z] != block) {
                        array[x][y][z] = block;
                        changedCount++;
                    }
                }
            }
        }

        if (changedCount > 0) {
            dirtyTracker.markBlockChanged();
        }

        return changedCount;
    }

    /**
     * Gets the underlying block array for custom bulk operations.
     *
     * @return Block array
     */
    public CcoBlockArray getBlockArray() {
        return blocks;
    }

    /**
     * Gets the dirty tracker for manual dirty management.
     *
     * @return Dirty tracker
     */
    public CcoDirtyTracker getDirtyTracker() {
        return dirtyTracker;
    }
}
