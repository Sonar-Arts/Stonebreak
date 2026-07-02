package com.stonebreak.mobs.sbe;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;

/**
 * The single mapping between the shared {@link MobBehaviorState} vocabulary
 * and the SBE animation-state names authored in mob {@code .sbe} files.
 *
 * <p>All mob SBEs use the same clip-name convention ({@code Idle},
 * {@code Walking}, {@code Grazing}, {@code Wingflap} — exact casing), so one
 * bidirectional mapping serves every mob. A mob whose asset lacks a clip for
 * a state simply renders its rest pose for that state.
 */
public final class MobStateMapping {

    private MobStateMapping() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** SBE animation-state name for a behaviour state. */
    public static String sbeState(MobBehaviorState behaviorState) {
        return switch (behaviorState) {
            case WANDERING -> "Walking";
            case GRAZING   -> "Grazing";
            case WING_FLAP -> "Wingflap";
            case IDLE      -> "Idle";
        };
    }

    /**
     * Inverse of {@link #sbeState}: the behaviour state for a replicated SBE
     * state name. Unknown/null names fall back to {@code IDLE}. Used on the
     * client to apply a server-sent animation state to a network-shadow mob.
     */
    public static MobBehaviorState behaviorState(String sbeState) {
        if (sbeState == null) {
            return MobBehaviorState.IDLE;
        }
        return switch (sbeState) {
            case "Walking"  -> MobBehaviorState.WANDERING;
            case "Grazing"  -> MobBehaviorState.GRAZING;
            case "Wingflap" -> MobBehaviorState.WING_FLAP;
            default         -> MobBehaviorState.IDLE;
        };
    }
}
