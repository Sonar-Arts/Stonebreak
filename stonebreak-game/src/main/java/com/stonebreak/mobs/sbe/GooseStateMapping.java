package com.stonebreak.mobs.sbe;

import com.stonebreak.mobs.goose.GooseAI;

/**
 * Maps a goose's AI behaviour state to the animation state name authored in
 * {@code SB_Goose.sbe}.
 *
 * <p>The SBE manifest defines three clips (lowercase, as authored): {@code idle},
 * {@code walking} and {@code flying}. Standing / on-water states render as {@code idle};
 * grounded movement renders as {@code walking}; all airborne states render as {@code flying}.
 * Names are case-sensitive — {@link SbeEntityAsset#clipFor(String)} looks them up exactly.
 */
public final class GooseStateMapping {

    private GooseStateMapping() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** SBE state name for a goose behaviour state. */
    public static String sbeState(GooseAI.GooseBehaviorState behaviorState) {
        return switch (behaviorState) {
            case TAKEOFF, FORMATION, FREE_FLY, LANDING -> "flying";
            case WANDERING, FLEEING -> "walking";
            case IDLE, FLOATING -> "idle";
        };
    }
}
