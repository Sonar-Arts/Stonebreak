package com.stonebreak.mobs.entities.combat;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;

/**
 * How a mob fights with a weapon. Timings mirror the authored clips: an attack's damage lands at
 * the moment its animation shows the hit, so the two can never disagree.
 */
public sealed interface WeaponProfile {

    /**
     * Close-quarters swing.
     *
     * @param animation     one-shot state whose clip shows the swing
     * @param damage        health taken from the player (who has 20)
     * @param reach         distance between centres at which a swing starts
     * @param cooldown      seconds after a swing ends before the next may start
     * @param clipSeconds   length of the swing clip; the mob is committed for this long
     * @param impactSeconds time into the clip at which the blow lands
     * @param knockback     horizontal shove given to the player, blocks per second
     */
    record Melee(MobBehaviorState animation, float damage, float reach, float cooldown,
                 float clipSeconds, float impactSeconds, float knockback) implements WeaponProfile {
    }

    /**
     * Drawn-and-loosed projectile.
     *
     * @param damage         health an arrow takes from the player it hits
     * @param minRange       closer than this the mob will not start a draw
     * @param maxRange       further than this it will not start one, or keep one going
     * @param drawSeconds    length of the draw clip; the arrow leaves at its end
     * @param releaseSeconds length of the follow-through clip after the shot
     * @param cooldown       seconds after the follow-through before the next draw
     * @param arrowSpeed     launch speed, blocks per second
     * @param spread         random aim error, radians
     */
    record Ranged(float damage, float minRange, float maxRange, float drawSeconds, float releaseSeconds,
                  float cooldown, float arrowSpeed, float spread) implements WeaponProfile {
    }
}
