package com.stonebreak.mobs.sbe;

/**
 * Maps the player's local movement state to an animation state name authored
 * in {@code SB_Player.sbe}.
 *
 * <p>The SBE manifest defines states {@code idle}, {@code walking},
 * {@code jumping} and {@code attacking}, each mapped one-to-one here. A state
 * with no matching SBE clip would return null, letting the renderer fall back
 * to the model's rest pose.
 */
public final class PlayerStateMapping {

    private PlayerStateMapping() {
        throw new UnsupportedOperationException("Utility class");
    }

    public enum PlayerMovementState {
        IDLE, WALKING, JUMPING, ATTACKING
    }

    /** SBE state name for a player movement state (null only if a state has no clip). */
    public static String sbeState(PlayerMovementState state) {
        return switch (state) {
            case IDLE      -> "idle";
            case WALKING   -> "walking";
            case JUMPING   -> "jumping";
            case ATTACKING -> "attacking";
        };
    }
}
