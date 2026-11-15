package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.Set;

/**
 * A mob's sense of nearby non-player threats — a sheep noticing a goblin. Players have their own
 * path ({@link AiContext#nearestPlayer}); this is everything else a mob is afraid of.
 */
public interface ThreatSense {

    /**
     * The nearest threat within {@code range} of the mob, written to {@code out}.
     *
     * @return {@code out}, or {@code null} when nothing threatening is that close
     */
    Vector3f nearest(AiContext context, float range, Vector3f out);

    /** Afraid of nothing but players. */
    ThreatSense NONE = (context, range, out) -> null;

    /**
     * Afraid of the given entity types. Stateful (it caches the last scan), so build one per mob.
     *
     * @param scanRadius how far it looks; ranges asked of it beyond this see nothing further
     */
    static ThreatSense ofTypes(float scanRadius, EntityType first, EntityType... rest) {
        return new ByType(scanRadius, EnumSet.of(first, rest));
    }

    /**
     * Scans the mob's world a few times a second, not every tick: there is no spatial index, and
     * a herd of livestock each walking the entity list every tick adds up. Between scans it
     * follows the threat it last saw, live.
     */
    final class ByType implements ThreatSense {

        /** Seconds between scans. */
        static final float SCAN_INTERVAL = 0.25f;

        private final float scanRadius;
        private final Set<EntityType> types;

        private Entity seen;
        /** Mob age at which the next scan runs; NaN until the first call picks a phase. */
        private float nextScanAt = Float.NaN;

        ByType(float scanRadius, Set<EntityType> types) {
            this.scanRadius = scanRadius;
            this.types = types;
        }

        @Override
        public Vector3f nearest(AiContext context, float range, Vector3f out) {
            float age = context.entity().getAge();
            if (Float.isNaN(nextScanAt)) {
                // A random phase, so a herd that spawned together does not scan on the same tick.
                nextScanAt = age + context.random().nextFloat() * SCAN_INTERVAL;
            }
            if (age >= nextScanAt) {
                nextScanAt = age + SCAN_INTERVAL;
                seen = scan(context);
            }
            if (seen == null || !seen.isAlive()) {
                return null;
            }
            Vector3f position = seen.getPosition();
            return context.distanceTo(position) <= range ? out.set(position) : null;
        }

        private Entity scan(AiContext context) {
            World world = context.world();
            EntityManager entities = world != null ? world.getEntityManager() : null;
            if (entities == null) {
                return null;
            }
            Vector3f self = context.entity().getPosition();
            Entity nearest = null;
            float best = Float.MAX_VALUE;
            for (Entity e : entities.getEntitiesInRange(self, scanRadius)) {
                if (e != context.entity() && types.contains(e.getType())) {
                    float d = e.getPosition().distanceSquared(self);
                    if (d < best) {
                        best = d;
                        nearest = e;
                    }
                }
            }
            return nearest;
        }
    }
}
