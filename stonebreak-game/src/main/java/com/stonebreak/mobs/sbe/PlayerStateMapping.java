package com.stonebreak.mobs.sbe;

/**
 * Maps the player's local movement state to an animation state name authored
 * in {@code SB_Player.sbe}.
 *
 * <p>The SBE manifest defines states {@code idle}, {@code walking}, {@code sprinting},
 * {@code jumping} and {@code attacking}, each mapped one-to-one here. A state
 * with no matching SBE clip would return null, letting the renderer fall back
 * to the model's rest pose.
 */
public final class PlayerStateMapping {

    private PlayerStateMapping() {
        throw new UnsupportedOperationException("Utility class");
    }

    public enum PlayerMovementState {
        IDLE, WALKING, JUMPING, ATTACKING, SPRINTING
    }

    /** SBE state name for a player movement state (null only if a state has no clip). */
    public static String sbeState(PlayerMovementState state) {
        return switch (state) {
            case IDLE      -> "idle";
            case WALKING   -> "walking";
            case SPRINTING -> "sprinting";
            case JUMPING   -> "jumping";
            case ATTACKING -> "attacking";
        };
    }

    /** Shared land locomotion selection; sprint-swimming keeps the existing walk placeholder. */
    public static PlayerMovementState locomotion(boolean moving, boolean onGround,
                                                 boolean physicallyInWater, boolean sprinting) {
        if (physicallyInWater && sprinting && moving) return PlayerMovementState.WALKING;
        if (!onGround) return PlayerMovementState.JUMPING;
        if (!moving) return PlayerMovementState.IDLE;
        return sprinting && !physicallyInWater ? PlayerMovementState.SPRINTING : PlayerMovementState.WALKING;
    }

    /** Authored two-step cycle duration; defaults keep movement usable if the asset is unavailable. */
    public static float gaitDuration(PlayerMovementState state, SbeEntityAsset asset) {
        if (state != PlayerMovementState.WALKING && state != PlayerMovementState.SPRINTING) return 0f;
        var clip = asset == null ? null : asset.clipFor(sbeState(state));
        if (clip != null && Float.isFinite(clip.duration()) && clip.duration() > 0f) return clip.duration();
        return state == PlayerMovementState.SPRINTING ? 0.68f : 0.96f;
    }
}
