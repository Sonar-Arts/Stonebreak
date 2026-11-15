package com.stonebreak.mobs.goblin.pack;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.List;

/**
 * The living entities around a point — how a pack finds other goblins and prey. A seam so pack
 * rules can be tested over a hand-made list instead of a world.
 */
@FunctionalInterface
public interface NearbyEntities {

    List<Entity> within(Vector3f center, float radius);

    /** Scans {@code world}'s entity manager; nothing when it has none bound. */
    static NearbyEntities of(World world) {
        return (center, radius) -> {
            EntityManager entities = world != null ? world.getEntityManager() : null;
            return entities != null ? entities.getEntitiesInRange(center, radius) : List.of();
        };
    }
}
