package com.stonebreak.mobs.sbe;

/**
 * Maps the player's local movement state to an animation state name authored
 * in {@code SB_Player.sbe}.
 *
 * <p>The SBE manifest defines states {@code walking}, {@code jumping} and
 * {@code attacking}. When the state is {@code IDLE} (no matching clip), null
 * is returned so the SBE renderer falls back to the model's rest pose.
 */
public final class PlayerStateMapping {

    private PlayerStateMapping() {
        throw new UnsupportedOperationException("Utility class");
    }

    public enum PlayerMovementState {
        IDLE, WALKING, JUMPING, ATTACKING
    }

    /** SBE state name for a player movement state, or null for IDLE (rest pose). */
    public static String sbeState(PlayerMovementState state) {
        return switch (state) {
            case WALKING   -> "walking";
            case JUMPING   -> "jumping";
            case ATTACKING -> "attacking";
            case IDLE      -> null; // no clip in SBE → rest pose fallback
        };
    }
}
