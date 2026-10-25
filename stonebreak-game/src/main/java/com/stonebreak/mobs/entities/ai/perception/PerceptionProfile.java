package com.stonebreak.mobs.entities.ai.perception;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ai.AwarenessController.AwarenessState;

/**
 * How one kind of mob perceives the player (#321): what an
 * {@link com.stonebreak.mobs.entities.ai.AwarenessController} reads instead of global constants,
 * so a new enemy needs new numbers here, not new AI code. Look one up with {@link #forType}.
 *
 * @param visionRange        blocks at which sight fades to nothing, in full light
 * @param fovDeg             full width of the forward cone seen at full strength
 * @param sideVisionFactor   sight strength outside the cone, out to ±{@link #SIDE_LIMIT_DEG}; none behind
 * @param darkVisionFloor    the least light the mob sees by: 1 ignores darkness, 0 is blind in it
 * @param darkRangeMult      vision range multiplier in full darkness (scaled in as light falls)
 * @param hearingRange       how far a sprint carries to these ears; scales every sound (see {@link Loudness})
 * @param suspicionThreshold meter level that makes the mob SUSPICIOUS
 * @param alarmThreshold     meter level that makes the mob ALERTED
 * @param sightGain          meter per second from a point-blank, fully lit, head-on view
 * @param hearGain           meter per second from a continuous sound heard at full strength
 * @param eventGain          meter per one-off sound heard at full strength
 * @param decayPerSec        meter drain while nothing is detected
 * @param shoutRange         blocks an alert carries to same-type mobs; 0 for loners
 * @param memorySeconds      how long a SEARCHING mob keeps looking before it gives up
 * @param alertness          detection multipliers by the mob's current state
 */
public record PerceptionProfile(
        float visionRange,
        float fovDeg,
        float sideVisionFactor,
        float darkVisionFloor,
        float darkRangeMult,
        float hearingRange,
        float suspicionThreshold,
        float alarmThreshold,
        float sightGain,
        float hearGain,
        float eventGain,
        float decayPerSec,
        float shoutRange,
        float memorySeconds,
        Alertness alertness) {

    /** Sight outside the forward cone reaches this far round to either side; nothing beyond. */
    public static final float SIDE_LIMIT_DEG = 110f;

    /** Meter ceiling; thresholds sit within it. */
    public static final float METER_MAX = 100f;

    /** Detection multipliers by awareness state: an already-suspicious mob notices faster. */
    public record Alertness(float unaware, float suspicious, float searching, float alerted) {
        public static final Alertness FLAT = new Alertness(1f, 1f, 1f, 1f);

        public float of(AwarenessState state) {
            return switch (state) {
                case UNAWARE -> unaware;
                case SUSPICIOUS -> suspicious;
                case SEARCHING -> searching;
                case ALERTED -> alerted;
            };
        }
    }

    /** The pre-#321 prototype's numbers: a narrow 16-block cone, no light, reference ears, no pack. */
    public static final PerceptionProfile DEFAULT = new PerceptionProfile(
            16f, 90f, 0f, 1f, 1f,
            Loudness.REFERENCE_HEARING_RANGE,
            35f, 100f,
            60f, 18f, 18f, 12f,
            0f, 10f,
            Alertness.FLAT);

    /**
     * The reference enemy. Average sight with a wide cone, night vision that only partly ignores
     * darkness, and sharp ears: goblins punish noise more than sight, so a player crouched behind
     * a rock stays hidden but a sprint through camp never does. Easily spooked, committed once
     * alerted, quick to lose interest, and they shout for the pack.
     */
    public static final PerceptionProfile GOBLIN = new PerceptionProfile(
            12f, 120f, 0.3f, 0.6f, 1.5f,
            16f,
            25f, 70f,
            80f, 45f, 45f, 12f,
            20f, 10f,
            new Alertness(1f, 1.6f, 2f, 2f));

    /** The profile for {@code type}; {@link #DEFAULT} for types without their own. */
    public static PerceptionProfile forType(EntityType type) {
        return type == EntityType.GOBLIN ? GOBLIN : DEFAULT;
    }
}
