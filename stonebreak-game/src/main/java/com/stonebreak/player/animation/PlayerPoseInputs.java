package com.stonebreak.player.animation;

import com.stonebreak.network.packet.player.PlayerStateFlags;

/**
 * The player graph's inputs for one body this frame (#457). ONE source for every player-shaped body: the local player
 * builds the same {@link PlayerStateFlags} it replicates ({@code Player.stateFlags()}), a remote player or decoy has
 * them from the network; the horizontal speed is the local velocity or the replicated displacement. Same inputs, same
 * graph, same pose.
 */
public final class PlayerPoseInputs {

    /** Horizontal speed, blocks per second. */
    public float speed;
    public boolean grounded = true;
    /** Body touching water ({@code PARTIALLY_IN_WATER}, or eyes under: {@code SWIMMING}). */
    public boolean inWater;
    public boolean seated;
    /** Sneaking: stealth is the sneak mechanic ({@code STEALTHED}). */
    public boolean crouched;
    public boolean attacking;
    /** Head look relative to the body, degrees (the head joint's pre-rotation). */
    public float headYaw;
    public float headPitch;

    /** Sets every input from replicated-style state. Returns this. */
    public PlayerPoseInputs set(short flags, float horizontalSpeed, boolean seated, float headYaw, float headPitch) {
        this.speed = Float.isFinite(horizontalSpeed) ? Math.max(0f, horizontalSpeed) : 0f;
        this.grounded = !PlayerStateFlags.has(flags, PlayerStateFlags.AIRBORNE);
        this.inWater = PlayerStateFlags.has(flags, PlayerStateFlags.PARTIALLY_IN_WATER)
                || PlayerStateFlags.has(flags, PlayerStateFlags.SWIMMING);
        this.seated = seated;
        this.crouched = PlayerStateFlags.has(flags, PlayerStateFlags.STEALTHED);
        this.attacking = PlayerStateFlags.has(flags, PlayerStateFlags.ATTACKING);
        this.headYaw = headYaw;
        this.headPitch = headPitch;
        return this;
    }

    /** A standing body looking ahead (UI previews). Returns this. */
    public PlayerPoseInputs standing() {
        return set((short) 0, 0f, false, 0f, 0f);
    }
}
