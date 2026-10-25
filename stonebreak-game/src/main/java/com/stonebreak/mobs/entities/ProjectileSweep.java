package com.stonebreak.mobs.entities;

import org.joml.Vector3f;

/**
 * Hit detection for projectiles: which living entity the segment a projectile travelled
 * this step passes through.
 *
 * <p>Tested against each entity's {@linkplain LivingEntity#getHitBox() hitbox} rather than a
 * radius around its position. The position is the feet (or the underside of a cow's body), so a
 * radius test could only ever hit the bottom of a mob — a goblin's chest was unhittable. Sweeping the whole segment also
 * stops fast projectiles skipping past a mob between ticks: a full-draw arrow covers 2.25
 * blocks per 20 Hz server tick, far more than any mob is wide.
 */
public final class ProjectileSweep {

    private ProjectileSweep() {}

    /**
     * The living entity whose hitbox, grown by {@code margin}, the segment
     * {@code from → to} enters first; null when it enters none.
     */
    public static LivingEntity firstHit(EntityManager em, Vector3f from, Vector3f to, float margin) {
        Vector3f dir = new Vector3f(to).sub(from);
        LivingEntity closest = null;
        float closestT = Float.MAX_VALUE;
        for (LivingEntity le : em.getLivingEntities()) {
            float t = le.getHitBox().expand(margin).rayIntersect(from, dir);
            if (t <= 1.0f && t < closestT) {
                closestT = t;
                closest = le;
            }
        }
        return closest;
    }
}
