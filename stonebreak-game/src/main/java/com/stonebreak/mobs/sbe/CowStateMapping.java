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
}
