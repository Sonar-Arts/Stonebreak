package com.stonebreak.mobs.goblin.camp;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.lighting.WorldLightingContext;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * The few things a goblin needs to know about the ground to pick a camp: what block is where, and
 * how high each column's solid top is (what is overhead). Never loads a chunk — an unloaded cell
 * is {@code null} and an unloaded column {@code -1}, and the search treats both as off-limits.
 */
public interface CampTerrain {

    /** The block at a cell, or {@code null} when its chunk is not loaded. */
    BlockType block(int x, int y, int z);

    /** The highest opaque cell in a column, or {@code -1} when its chunk is not loaded. */
    int columnTop(int x, int z);

    default boolean solid(int x, int y, int z) {
        BlockType b = block(x, y, z);
        return b != null && b.isSolid();
    }

    /** Air a goblin can stand in: loaded, not solid, not water. */
    default boolean open(int x, int y, int z) {
        BlockType b = block(x, y, z);
        return b != null && !b.isSolid() && b != BlockType.WATER;
    }

    /** Somewhere to stand: solid floor, two open cells for the body. */
    default boolean standable(int x, int y, int z) {
        return solid(x, y - 1, z) && open(x, y, z) && open(x, y + 1, z);
    }

    /** Something solid overhead — out of the sun. */
    default boolean covered(int x, int y, int z) {
        int top = columnTop(x, z);
        return top >= 0 && top > y + 1;
    }

    static CampTerrain of(World world) {
        WorldLightingContext light = new WorldLightingContext(world);
        return new CampTerrain() {
            @Override
            public BlockType block(int x, int y, int z) {
                if (y < 0 || y >= WorldConfiguration.WORLD_HEIGHT) {
                    return y < 0 ? BlockType.BEDROCK : BlockType.AIR;
                }
                if (world.getChunkIfLoaded(Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE),
                        Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE)) == null) {
                    return null;
                }
                return world.getBlockAt(x, y, z);
            }

            @Override
            public int columnTop(int x, int z) {
                return light.getColumnHeight(x, z);
            }
        };
    }
}
