package com.stonebreak.mobs.goblin.camp;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.lighting.WorldLightingContext;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * The few things a goblin needs to know about the ground to pick a camp: what block is where, and
 * how high each column's solid top is (what is overhead). Never loads a cube — an unknown cell
 * is {@code null} and an unready column {@link #NO_COLUMN}, and the search treats both as off-limits.
 * (The cubic world reaches below y = 0, so a negative top is a real one.)
 */
public interface CampTerrain {

    /** {@link #columnTop} of a column that is not ready (not every cube of its band is in). */
    int NO_COLUMN = Integer.MIN_VALUE;

    /** The block at a cell, or {@code null} when its chunk is not loaded. */
    BlockType block(int x, int y, int z);

    /** The highest opaque cell in a column, or {@link #NO_COLUMN} when the column is not ready. */
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
        return top != NO_COLUMN && top > y + 1;
    }

    /**
     * Roofed by the ground: the first solid block over the head of someone standing at the cell is
     * rock, soil or sand ({@link NaturalGround#isGroundRoof}) — a cave's ceiling or an overhang, of
     * any thickness. A tree's canopy, or a builder's planks, is no roof for a camp.
     */
    default boolean roofed(int x, int y, int z) {
        int top = columnTop(x, z);
        for (int above = y + 2; above <= top; above++) {
            BlockType b = block(x, above, z);
            if (b == null) {
                return false;
            }
            if (b.isSolid()) {
                return NaturalGround.isGroundRoof(b);
            }
        }
        return false;
    }

    static CampTerrain of(World world) {
        WorldLightingContext light = new WorldLightingContext(world);
        return new CampTerrain() {
            @Override
            public BlockType block(int x, int y, int z) {
                if (!WorldConfiguration.inBounds(y)) {
                    return y < WorldConfiguration.MIN_Y ? BlockType.BEDROCK : BlockType.AIR;
                }
                if (!world.isKnown(x, y, z)) {
                    return null;
                }
                return world.getBlockAt(x, y, z);
            }

            @Override
            public int columnTop(int x, int z) {
                if (!world.isColumnReadyAt(x, z)) {
                    return NO_COLUMN;
                }
                return light.getColumnHeight(x, z);
            }
        };
    }
}
