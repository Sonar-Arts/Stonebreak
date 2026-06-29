package com.stonebreak.mobs.sbe;

import com.stonebreak.mobs.cow.CowAI;

/**
 * Maps a cow's AI behaviour state to the animation state name authored in
 * {@code SB_Cow.sbe}.
 *
 * <p>The SBE manifest defines states {@code Idle}, {@code Walking} and
 * {@code Grazing} (exact casing); the AI exposes {@code IDLE}, {@code WANDERING}
 * and {@code GRAZING}.
 */
public final class CowStateMapping {

    private CowStateMapping() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** SBE state name for a cow behaviour state. */
    public static String sbeState(CowAI.CowBehaviorState behaviorState) {
        return switch (behaviorState) {
            case WANDERING -> "Walking";
            case GRAZING -> "Grazing";
            case IDLE -> "Idle";
        };
    }

    /**
     * Inverse of {@link #sbeState}: the cow behaviour state for a replicated SBE state name.
     * Unknown/null names fall back to {@code IDLE}. Used on the client to apply a server-sent
     * animation state to a network-shadow cow.
     */
    public static CowAI.CowBehaviorState behaviorState(String sbeState) {
        if (sbeState == null) {
            return CowAI.CowBehaviorState.IDLE;
        }
        return switch (sbeState) {
            case "Walking" -> CowAI.CowBehaviorState.WANDERING;
            case "Grazing" -> CowAI.CowBehaviorState.GRAZING;
            default -> CowAI.CowBehaviorState.IDLE;
        };
    }
}
