package com.stonebreak.mobs.entities.ai.perception;

import com.stonebreak.player.interaction.RaycastEngine;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Whether a straight line between two points is clear of solid blocks — a mob's line of sight to
 * whatever it is looking at, a player or its prey.
 *
 * <p>Stateful only to reuse its {@link RaycastEngine}, rebuilt when the world changes; hold one per
 * looker and use it from that looker's tick thread.
 */
public final class SightLine {

    private RaycastEngine ray;
    /** The world {@link #ray} was built over. */
    private World rayWorld;
    private final Vector3f dir = new Vector3f();

    /**
     * @param throughLeaves whether leaves let the view through
     * @return whether {@code eye} sees {@code point}; true without a world to block it
     */
    public boolean clear(World world, Vector3f eye, Vector3f point, boolean throughLeaves) {
        if (world == null) {
            return true;
        }
        dir.set(point).sub(eye);
        float dist = dir.length();
        if (dist < 1.0e-4f) {
            return true;
        }
        if (ray == null || rayWorld != world) {
            ray = new RaycastEngine(null, null, world);
            rayWorld = world;
        }
        return ray.distanceToFirstSolid(eye, dir.div(dist), dist, throughLeaves) >= dist;
    }
}
