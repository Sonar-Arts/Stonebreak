package com.stonebreak.mobs.entities.ai.nav;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;

/**
 * Small local airspace queries for flying behaviours — is this bit of sky clear, and how high is
 * the ground under that column. The airborne counterpart of {@link GroundProbe}, and here for the
 * same reason: a flyer holding formation or checking the corridor a second ahead of itself needs an
 * answer about one small volume this instant, and letting each behaviour scan the sky its own way
 * is how three subtly different probes end up in the codebase.
 *
 * <p>Routing is {@link AirPathAgent}'s job, not this class's.
 *
 * <p><b>Unloaded chunks read as clear here</b>, which is the opposite of what
 * {@link WorldNavVolume} does for a search and is deliberate. A search is choosing between routes
 * and can afford to prefer the ones it can see; a local probe is deciding whether to swerve
 * <em>right now</em>, and treating every unloaded column as a wall would have birds jinking around
 * nothing at the edge of the loaded world. The route is what handles terrain properly; this is the
 * fine detail underneath it.
 */
public final class AirProbe {

    /** How far above and below a column the peak scan looks. */
    private static final int PEAK_SCAN_RANGE = 32;

    private AirProbe() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Whether the axis-aligned box of half-extent {@code radius} around a point contains no solid
     * block — the question "could a bird sit here".
     */
    public static boolean isClear(World world, float x, float y, float z, float radius) {
        if (world == null) {
            return true;
        }
        int minX = (int) Math.floor(x - radius);
        int maxX = (int) Math.floor(x + radius);
        int minY = (int) Math.floor(y - radius);
        int maxY = (int) Math.floor(y + radius);
        int minZ = (int) Math.floor(z - radius);
        int maxZ = (int) Math.floor(z + radius);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    BlockType block = world.getBlockAt(bx, by, bz);
                    if (block != null && block.isSolid()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Top face of the highest solid block within scan range of {@code fromY} in one column, or
     * {@link Float#NEGATIVE_INFINITY} when the corridor there is clear.
     */
    public static float columnPeak(World world, float x, float z, float fromY) {
        if (world == null) {
            return Float.NEGATIVE_INFINITY;
        }
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int centre = (int) Math.floor(fromY);
        for (int y = centre + PEAK_SCAN_RANGE; y >= centre - PEAK_SCAN_RANGE; y--) {
            BlockType block = world.getBlockAt(blockX, y, blockZ);
            if (block != null && block.isSolid()) {
                return y + 1.0f;
            }
        }
        return Float.NEGATIVE_INFINITY;
    }
}
