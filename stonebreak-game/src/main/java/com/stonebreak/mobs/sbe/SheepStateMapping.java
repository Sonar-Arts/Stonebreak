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
}
