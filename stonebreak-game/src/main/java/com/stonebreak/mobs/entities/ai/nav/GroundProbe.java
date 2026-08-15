package com.stonebreak.mobs.entities.ai.nav;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;

/**
 * Small local column queries for behaviours that need to know about the ground or the water right
 * here — not where to walk, which is {@link PathAgent}'s job.
 *
 * <p>A landing bird checking whether it is above a surface, or a floating one checking the water is
 * still there, needs an answer about one column this instant. Routing a search for that would be
 * absurd, and letting each behaviour scan columns its own way is how the old AI ended up with three
 * subtly different ground probes.
 */
public final class GroundProbe {

    /** How far above and below a position the ground scan looks. */
    private static final int GROUND_SCAN_UP = 5;
    private static final int GROUND_SCAN_DOWN = 10;

    /** Water is only interesting within a body's reach of its feet. */
    private static final int WATER_SCAN_UP = 1;
    private static final int WATER_SCAN_DOWN = 3;

    private GroundProbe() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The Y of the first standable surface in the column at (x, z) — a solid block with space
     * above it — scanning down from a little above {@code startY}.
     *
     * @return the surface height, or {@link Float#NEGATIVE_INFINITY} if the column has none
     */
    public static float groundLevel(World world, float x, float z, float startY) {
        if (world == null) {
            return Float.NEGATIVE_INFINITY;
        }
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int from = (int) Math.floor(startY);

        for (int y = from + GROUND_SCAN_UP; y >= from - GROUND_SCAN_DOWN; y--) {
            BlockType block = world.getBlockAt(blockX, y, blockZ);
            BlockType above = world.getBlockAt(blockX, y + 1, blockZ);
            if (block != null && block.isSolid() && (above == null || !above.isSolid())) {
                return y + 1.0f;
            }
        }
        return Float.NEGATIVE_INFINITY;
    }

    /**
     * The top face of the highest water block near (x, y, z), or
     * {@link Float#NEGATIVE_INFINITY} when there is no water within reach.
     */
    public static float waterSurface(World world, float x, float y, float z) {
        if (world == null) {
            return Float.NEGATIVE_INFINITY;
        }
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int from = (int) Math.floor(y);

        for (int cy = from + WATER_SCAN_UP; cy >= from - WATER_SCAN_DOWN; cy--) {
            if (world.getBlockAt(blockX, cy, blockZ) == BlockType.WATER) {
                return cy + 1.0f;
            }
        }
        return Float.NEGATIVE_INFINITY;
    }
}
