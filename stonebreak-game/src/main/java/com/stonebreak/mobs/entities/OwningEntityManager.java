package com.stonebreak.mobs.entities;

import com.stonebreak.world.World;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The entity manager an entity's OWN world is bound to — the one rule every projectile and area
 * effect uses to find what it can hit.
 *
 * <p>There is deliberately no fallback to {@code Game.getEntityManager()}. In the two-world model
 * that singleton is the client's render manager (network shadows): a server-spawned arrow falling
 * back to it would scan the wrong world from the server tick thread, and anything it spawned would
 * exist only on the host's client. A world with no manager bound is a wiring bug, so it is
 * reported — once per entity class, since this runs every tick — and the scan is skipped rather
 * than thrown, which would take the whole tick down with it (#340).
 */
final class OwningEntityManager {

    private static final Set<Class<?>> REPORTED = ConcurrentHashMap.newKeySet();

    private OwningEntityManager() {
    }

    /** The manager of {@code entity}'s world, or {@code null} (reported) when none is bound. */
    static EntityManager of(Entity entity) {
        World world = entity.getWorld();
        EntityManager manager = world != null ? world.getEntityManager() : null;
        if (manager == null && REPORTED.add(entity.getClass())) {
            System.err.println("[ENTITY] " + entity.getClass().getSimpleName()
                + " is ticking in a world with no entity manager bound; skipping its entity scan."
                + " Bind one with World.setEntityManager (the Game singleton's is the client world).");
        }
        return manager;
    }
}
