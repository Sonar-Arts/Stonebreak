package com.stonebreak.mobs.sbe;

import com.stonebreak.mobs.sheep.SheepAI;

public final class SheepStateMapping {

    private SheepStateMapping() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static String sbeState(SheepAI.SheepBehaviorState behaviorState) {
        return switch (behaviorState) {
            case WANDERING -> "Walking";
            case GRAZING   -> "Grazing";
            case IDLE      -> "Idle";
        };
    }

    /**
     * Inverse of {@link #sbeState}: the sheep behaviour state for a replicated SBE state name.
     * Unknown/null names fall back to {@code IDLE}.
     */
    public static SheepAI.SheepBehaviorState behaviorState(String sbeState) {
        if (sbeState == null) {
            return SheepAI.SheepBehaviorState.IDLE;
        }
        return switch (sbeState) {
            case "Walking" -> SheepAI.SheepBehaviorState.WANDERING;
            case "Grazing" -> SheepAI.SheepBehaviorState.GRAZING;
            default        -> SheepAI.SheepBehaviorState.IDLE;
        };
    }
}
