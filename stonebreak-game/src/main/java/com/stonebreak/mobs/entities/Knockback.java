package com.stonebreak.mobs.entities;

import org.joml.Vector3f;

/**
 * An extra knockback impulse carried with a hit (e.g. Rampage T3's sideways launch). It
 * travels with the damage intent so the server applies it to the real entity; writing it to
 * a network shadow would do nothing, since shadows are never simulated.
 *
 * @param dirX       horizontal direction X (need not be normalized)
 * @param dirZ       horizontal direction Z
 * @param horizontal horizontal force
 * @param vertical   vertical lift
 */
public record Knockback(float dirX, float dirZ, float horizontal, float vertical) {

    /** Applies this impulse to {@code target} (see {@link LivingEntity#applyKnockback}). */
    public void applyTo(LivingEntity target) {
        target.applyKnockback(new Vector3f(dirX, 0f, dirZ), horizontal, vertical);
    }
}
