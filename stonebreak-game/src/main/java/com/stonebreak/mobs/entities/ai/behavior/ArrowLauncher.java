package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import org.joml.Vector3f;

/**
 * Puts a mob's arrow into the world. A seam so the ranged behaviour's timing and aim are testable
 * without an entity manager.
 */
@FunctionalInterface
public interface ArrowLauncher {

    void launch(LivingEntity shooter, Vector3f origin, Vector3f velocity, float damage);

    /** Spawns a real arrow in the shooter's own world (the server's, on a server world). */
    ArrowLauncher WORLD = (shooter, origin, velocity, damage) -> {
        EntityManager manager = shooter.getWorld().getEntityManager();
        if (manager == null) {
            manager = Game.getEntityManager();
        }
        if (manager != null) {
            manager.spawnArrow(origin, velocity).firedBy(shooter, damage);
        }
    };
}
