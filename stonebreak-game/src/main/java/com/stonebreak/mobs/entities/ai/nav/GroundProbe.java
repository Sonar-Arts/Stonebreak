package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.nav.NavProbes;
import com.openmason.engine.physics.PhysicsWorld;
import com.stonebreak.mobs.entities.PhysicsFrame;
import com.stonebreak.world.World;
import com.stonebreak.world.physics.WorldPhysics;

/**
 * Small local column queries for behaviours that need to know about the ground or the water right
 * here — not where to walk, which is {@link PathAgent}'s job.
 *
 * <p>A landing bird checking whether it is above a surface, or a floating one checking the water is
 * still there, needs an answer about one column this instant. Routing a search for that would be
 * absurd, and letting each behaviour scan columns its own way is how the old AI ended up with three
 * subtly different ground probes.
 *
 * <p>Answered from the world's physics cache by the engine ({@link NavProbes}): a cell counts as
 * ground when it collides with mobs and fills its cell (a snow layer or a stair tread is not a
 * ground cell here, as a non-solid block was not before).
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
    public static double groundLevel(World world, double x, double z, double startY) {
        if (world == null) {
            return Double.NEGATIVE_INFINITY;
        }
        PhysicsWorld p = WorldPhysics.of(world).engine();
        long ox = PhysicsFrame.originX(p), oy = PhysicsFrame.originY(p), oz = PhysicsFrame.originZ(p);
        return NavProbes.groundLevel(p, x - ox, z - oz, startY - oy, GROUND_SCAN_UP,
                GROUND_SCAN_DOWN, WorldPhysics.MOB_MASK) + oy;
    }

    /**
     * The top face of the highest water block near (x, y, z), or
     * {@link Float#NEGATIVE_INFINITY} when there is no water within reach.
     */
    public static double waterSurface(World world, double x, double y, double z) {
        if (world == null) {
            return Double.NEGATIVE_INFINITY;
        }
        PhysicsWorld p = WorldPhysics.of(world).engine();
        long ox = PhysicsFrame.originX(p), oy = PhysicsFrame.originY(p), oz = PhysicsFrame.originZ(p);
        return NavProbes.fluidTop(p, x - ox, y - oy, z - oz, WATER_SCAN_UP, WATER_SCAN_DOWN,
                WorldPhysics.WATER) + oy;
    }
}
