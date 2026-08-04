package com.stonebreak.blocks;

import com.stonebreak.blocks.stairs.StairShape;
import com.stonebreak.world.World;

/**
 * How tall a block actually is where something stands on it — the one rule every system that
 * cares about standing, stepping or fitting must agree on.
 *
 * <p>Three of them used to answer it separately (player collision, entity collision, placement
 * validation), and they had already drifted: one knew about stairs, one did not. Navigation needs
 * the same answer as collision or mobs plan routes their own physics refuses to walk, so the rule
 * lives here once.
 *
 * <p>Shaped blocks are answered over an XZ footprint: a stair reports the tallest step the
 * footprint overlaps, which is what makes a body rest on the tread it is really on, and what lets
 * a leading-edge query drive the step-up rule one tread at a time.
 */
public final class BlockShape {

    private BlockShape() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Collision height of a whole cell — the footprint-free form, for full-cell questions. */
    public static float collisionHeight(World world, int x, int y, int z) {
        return collisionHeight(world, x, y, z, x, z, x + 1.0f, z + 1.0f);
    }

    /**
     * Collision height of the cell under a world-space XZ footprint, in blocks above the cell
     * floor. Snow answers with its layer height, stairs with the tallest step the footprint
     * overlaps, everything else with a full block or nothing.
     *
     * <p>Animated blocks (doors) are deliberately <b>not</b> special-cased here: whether their
     * cell collides is the caller's policy, because only a caller that also resolves against the
     * posed model AABB can afford to answer "not solid".
     */
    public static float collisionHeight(World world, int x, int y, int z,
                                        float minX, float minZ, float maxX, float maxZ) {
        BlockType block = world.getBlockAt(x, y, z);
        if (block == BlockType.SNOW) {
            return world.getSnowHeight(x, y, z);
        }
        if (block.isStairs()) {
            return StairShape.stepHeight(world, x, y, z, block, minX, minZ, maxX, maxZ);
        }
        return block.getCollisionHeight();
    }
}
