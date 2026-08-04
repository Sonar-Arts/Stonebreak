package com.stonebreak.mobs.sbe;

import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;

/**
 * The single mapping between the shared {@link MobBehaviorState} vocabulary and the SBE animation
 * clip names authored in mob {@code .sbe} files.
 *
 * <p>Most mobs share one clip-naming convention ({@code Idle}, {@code Walking}, {@code Grazing},
 * {@code Wingflap}); the goose's asset was authored in lowercase with a {@code flying} clip instead
 * of the ground-only set. Names are matched exactly — {@link SbeEntityAsset#clipFor(String)} is
 * case-sensitive — so the per-asset difference lives here rather than in a second mapping class
 * beside the mob it belongs to.
 *
 * <p>A mob whose asset has no clip for a state simply renders its rest pose.
 */
public final class MobStateMapping {

    private MobStateMapping() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** SBE animation-state name for a behaviour state on a given mob. */
    public static String sbeState(EntityType type, MobBehaviorState behaviorState) {
        if (type == EntityType.GOOSE) {
            return switch (behaviorState) {
                case FLYING -> "flying";
                case WANDERING -> "walking";
                // Standing, grazing, flapping and floating all render as the goose's idle pose.
                case IDLE, GRAZING, WING_FLAP, SWIMMING -> "idle";
            };
        }
        return switch (behaviorState) {
            case WANDERING, FLYING, SWIMMING -> "Walking";
            case GRAZING -> "Grazing";
            case WING_FLAP -> "Wingflap";
            case IDLE -> "Idle";
        };
    }

    /**
     * Inverse of {@link #sbeState}: the behaviour state for a replicated SBE clip name. States that
     * share a clip collapse onto one representative, which is all a client shadow needs — it
     * animates the state, it does not act on it. Unknown or null names fall back to {@code IDLE}.
     */
    public static MobBehaviorState behaviorState(EntityType type, String sbeState) {
        if (sbeState == null) {
            return MobBehaviorState.IDLE;
        }
        if (type == EntityType.GOOSE) {
            return switch (sbeState) {
                case "flying" -> MobBehaviorState.FLYING;
                case "walking" -> MobBehaviorState.WANDERING;
                default -> MobBehaviorState.IDLE;
            };
        }
        return switch (sbeState) {
            case "Walking" -> MobBehaviorState.WANDERING;
            case "Grazing" -> MobBehaviorState.GRAZING;
            case "Wingflap" -> MobBehaviorState.WING_FLAP;
            default -> MobBehaviorState.IDLE;
        };
    }
}
