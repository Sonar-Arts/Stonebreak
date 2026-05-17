package com.stonebreak.mobs.sbe;

import com.stonebreak.mobs.chicken.ChickenAI;

/**
 * Maps a chicken's AI behaviour state to the animation state name authored in
 * {@code SB_Chicken.sbe}.
 *
 * <p>The SBE manifest defines states {@code Idle}, {@code Walking} and
 * {@code Wingflap} (exact casing); the AI exposes {@code IDLE},
 * {@code WANDERING} and {@code WING_FLAP}.
 */
public final class ChickenStateMapping {

    private ChickenStateMapping() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** SBE state name for a chicken behaviour state. */
    public static String sbeState(ChickenAI.ChickenBehaviorState behaviorState) {
        return switch (behaviorState) {
            case WANDERING -> "Walking";
            case WING_FLAP -> "Wingflap";
            case IDLE -> "Idle";
        };
    }
}
