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
    /** What the current attacking action is ({@code PlayerStateFlags.ACTION_*}, #458). */
    public int action;
    /** What the hand holds ({@code HeldKind.graphValue()}, #458). */
    public int heldKind;
    /** Bow draw 0..1 (#458; 0 = not drawing). */
    public float bowDraw;
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
        this.action = PlayerStateFlags.action(flags);
        this.headYaw = headYaw;
        this.headPitch = headPitch;
        return this;
    }

    /** Sets what the hand holds and how far a bow is drawn (#458). Returns this. */
    public PlayerPoseInputs held(int heldKind, float bowDraw) {
        this.heldKind = heldKind;
        this.bowDraw = Float.isFinite(bowDraw) ? Math.clamp(bowDraw, 0f, 1f) : 0f;
        return this;
    }

    /** A standing body looking ahead, empty-handed (UI previews). Returns this. */
    public PlayerPoseInputs standing() {
        return set((short) 0, 0f, false, 0f, 0f).held(0, 0f);
    }
}
