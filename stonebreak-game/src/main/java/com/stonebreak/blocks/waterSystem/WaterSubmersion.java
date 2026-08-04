package com.stonebreak.blocks.waterSystem;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;

/**
 * How deep a body is in the water — the one rule the player and every mob answer it with.
 *
 * <p>"In water" is not a yes/no question. A cow wading a shallow stream, a goose floating with its
 * body half under, and a chicken that fell down a well are all in water, and they should sink,
 * float and swim differently. A single probe point cannot tell them apart, which is why the old mob
 * check (one block sample at leg height) could only ever answer "wet" and left buoyancy nothing to
 * work with.
 *
 * <p>The measurement runs against the water's <em>real</em> surface — a flowing water block is
 * shorter than a source one ({@link WaterHeightUtil}) — so a body settles at the height the water
 * is actually drawn at, rather than at the top of its cell.
 */
public final class WaterSubmersion {

    private WaterSubmersion() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The fraction (0..1) of a body of {@code height} blocks, standing with its feet at
     * {@code feetY}, that is below the water surface.
     *
     * @return 0 when the body is entirely dry, 1 when it is entirely under
     */
    public static float fractionAt(World world, float x, float feetY, float z, float height) {
        if (world == null || height <= 0.0f) {
            return 0.0f;
        }
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int feetBlockY = (int) Math.floor(feetY);
        int headBlockY = (int) Math.floor(feetY + height);

        // The highest water block the body spans decides the surface; one block above catches a
        // body whose head is just under a surface sitting inside the next cell up.
        int topWaterY = Integer.MIN_VALUE;
        for (int y = feetBlockY; y <= headBlockY + 1; y++) {
            if (world.getBlockAt(blockX, y, blockZ) == BlockType.WATER) {
                topWaterY = y;
            }
        }
        if (topWaterY == Integer.MIN_VALUE) {
            return 0.0f;
        }

        float heightFraction = WaterHeightUtil.resolveSurfaceHeightFraction(world, blockX, topWaterY, blockZ);
        if (Float.isNaN(heightFraction)) {
            heightFraction = WaterHeightUtil.MAX_WATER_HEIGHT;
        }
        float surfaceY = topWaterY + heightFraction;
        float submergedDepth = Math.max(0.0f, Math.min(height, surfaceY - feetY));
        return submergedDepth / height;
    }

    /** Whether any part of the body is in water. */
    public static boolean touchesWater(World world, float x, float feetY, float z, float height) {
        return fractionAt(world, x, feetY, z, height) > 0.0f;
    }
}
