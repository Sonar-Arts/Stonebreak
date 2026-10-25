package com.stonebreak.mobs.entities.ai.perception;

/**
 * How loud things are, on the relative 0–10 scale of #321, and how far a loudness carries.
 *
 * <p>A sound of loudness {@code L} reaches {@code L × BLOCKS_PER_LOUDNESS} blocks for a mob with
 * reference ears ({@link #REFERENCE_HEARING_RANGE}); a mob's own
 * {@link PerceptionProfile#hearingRange()} scales that, so sharp ears hear everything further off.
 * Within the radius the sound fades linearly to nothing at the edge. With reference ears the
 * movement levels reproduce the old fixed radii: walk 5 blocks, sprint 10.
 */
public final class Loudness {

    // Continuous movement (re-evaluated every perception tick)
    public static final float STEALTH = 1f;
    public static final float WALK = 2f;
    public static final float SPRINT = 4f;
    /** A dodge's scuff, for its {@code DODGE_STEALTH_NOISE_DURATION} window. */
    public static final float DODGE = 3f;

    // One-off events (posted to a NoiseBus)
    public static final float DROP_ITEM = 2f;
    public static final float PLACE_BLOCK = 4f;
    public static final float MELEE_ATTACK = 5f;
    public static final float RANGED_ATTACK = 6f;
    public static final float BREAK_BLOCK = 8f;
    public static final float DEATH = 10f;
    /** A kill the victim never saw coming: a body slumping, not a fight. As loud as a walk. */
    public static final float STEALTH_KILL = WALK;

    /** Blocks a loudness of 1 carries for reference ears. */
    public static final float BLOCKS_PER_LOUDNESS = 2.5f;
    /** The hearing range of reference ears: how far they hear a sprint ({@link #SPRINT}). */
    public static final float REFERENCE_HEARING_RANGE = SPRINT * BLOCKS_PER_LOUDNESS;

    /** Horizontal speed (blocks/s) below which a player counts as standing still: silent. */
    public static final float STILL_SPEED = 0.5f;

    private Loudness() {}

    /** How far {@code loudness} carries for ears with {@code hearingRange}. */
    public static float audibleRadius(float loudness, float hearingRange) {
        return loudness * BLOCKS_PER_LOUDNESS * (hearingRange / REFERENCE_HEARING_RANGE);
    }

    /** How strongly {@code loudness} is heard {@code distance} away: 1 at the source, 0 at the edge and beyond. */
    public static float strength(float loudness, float distance, float hearingRange) {
        float radius = audibleRadius(loudness, hearingRange);
        if (radius <= 0f || distance >= radius) {
            return 0f;
        }
        return 1f - distance / radius;
    }

    /** How loud a death is: {@link #STEALTH_KILL} when the victim never knew it was hunted, else {@link #DEATH}. */
    public static float ofDeath(boolean caughtUnaware) {
        return caughtUnaware ? STEALTH_KILL : DEATH;
    }

    /**
     * A player's movement loudness: silent standing still, {@code stealthLoudness} while stealthed
     * (whatever the pace: stealth blocks sprinting), otherwise walk or sprint. A recent dodge takes
     * over when louder, even mid-stealth.
     */
    public static float ofMovement(boolean stealthed, float stealthLoudness, boolean sprinting,
                                   float horizontalSpeed, boolean dodging) {
        float base;
        if (horizontalSpeed <= STILL_SPEED) {
            base = 0f;
        } else if (stealthed) {
            base = stealthLoudness;
        } else {
            base = sprinting ? SPRINT : WALK;
        }
        return dodging ? Math.max(base, DODGE) : base;
    }
}
