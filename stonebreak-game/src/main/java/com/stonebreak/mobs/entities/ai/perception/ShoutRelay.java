package com.stonebreak.mobs.entities.ai.perception;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Carries an alerted mob's shout to its packmates (#321): same-type mobs within the shout range
 * who track awareness hear where the player was last known.
 */
@FunctionalInterface
public interface ShoutRelay {

    void shout(LivingEntity shouter, Vector3f lastKnown, float range);

    /** Nobody hears; for tests and loners. */
    ShoutRelay NONE = (shouter, lastKnown, range) -> { };

    /** Through the shouter's own world's entity manager — the authoritative one on the server. */
    ShoutRelay WORLD = (shouter, lastKnown, range) -> {
        World world = shouter.getWorld();
        EntityManager entities = world != null ? world.getEntityManager() : null;
        if (entities == null || range <= 0f) {
            return;
        }
        for (Entity e : entities.getEntitiesInRange(shouter.getPosition(), range)) {
            if (e != shouter && e.getType() == shouter.getType() && e instanceof LivingEntity mate) {
                AwarenessController awareness = mate.getAwareness();
                if (awareness != null) {
                    awareness.hearShout(lastKnown);
                }
            }
        }
    };
}
