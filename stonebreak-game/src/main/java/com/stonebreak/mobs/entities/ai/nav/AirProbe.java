package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.nav.NavProbes;
import com.stonebreak.world.World;
import com.stonebreak.world.physics.WorldPhysics;

/**
 * Small local airspace queries for flying behaviours — is this bit of sky clear, and how high is
 * the ground under that column. The airborne counterpart of {@link GroundProbe}, and here for the
 * same reason: a flyer holding formation or checking the corridor a second ahead of itself needs an
 * answer about one small volume this instant.
 *
 * <p>Routing is {@link AirPathAgent}'s job, not this class's.
 *
 * <p><b>Unloaded chunks read as clear here</b>, which is the opposite of what a search does and is
 * deliberate. A search is choosing between routes and can afford to prefer the ones it can see; a
 * local probe is deciding whether to swerve <em>right now</em>, and treating every unloaded column as
 * a wall would have birds jinking around nothing at the edge of the loaded world.
 *
 * <p>Answered from the world's physics cache by the engine ({@link NavProbes}): a cell is solid when
 * it collides with mobs and fills its cell.
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
        return NavProbes.isClear(WorldPhysics.of(world).engine(), x, y, z, radius, WorldPhysics.MOB_MASK);
    }

    /**
     * Top face of the highest solid block within scan range of {@code fromY} in one column, or
     * {@link Float#NEGATIVE_INFINITY} when the corridor there is clear.
     */
    public static float columnPeak(World world, float x, float z, float fromY) {
        if (world == null) {
            return Float.NEGATIVE_INFINITY;
        }
        return NavProbes.columnPeak(WorldPhysics.of(world).engine(), x, z, fromY, PEAK_SCAN_RANGE,
                WorldPhysics.MOB_MASK);
    }
}
