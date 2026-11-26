package com.stonebreak.player;

import com.stonebreak.player.animation.PlayerBodyAnimator;
import com.stonebreak.player.animation.PlayerPoseInputs;
import org.joml.Vector3f;

/**
 * The local player's body: its facing ({@link PlayerBodyOrientation}) and its pose, the player animation graph
 * driven by the same inputs a remote copy of this player gets ({@link PlayerPoseInputs} from the replicated state
 * flags, #457). Advanced once per tick by {@link PlayerUpdatePipeline}; the third-person figure, its shadow and the
 * main menu preview draw {@link #animator()}'s palette.
 */
final class PlayerBodyAnimation {

    // Third-person body facing + head look angles, decoupled from the first-person camera: the camera only supplies
    // a look yaw/pitch; this decides how the body turns to follow movement and the look direction.
    private final PlayerBodyOrientation bodyOrientation = new PlayerBodyOrientation();
    private final PlayerPoseInputs inputs = new PlayerPoseInputs();
    private PlayerBodyAnimator animator;

    /** Turns the body (movement / look; a seat overrides it). */
    void orient(float dt, Vector3f velocity, Vector3f cameraFront, boolean seated, float seatYaw) {
        float lookModelYaw = PlayerBodyOrientation.modelYawFromDirection(cameraFront.x, cameraFront.z);
        bodyOrientation.update(dt, velocity, lookModelYaw);
        if (seated) bodyOrientation.seat(seatYaw);
    }

    /** Poses the body for this tick from the player's replicated-style state. */
    void animate(float dt, short stateFlags, float horizontalSpeed, boolean seated, float headYaw, float headPitch,
                 int heldKind, float bowDraw, PlayerBodyAnimator.Events events) {
        if (animator == null) animator = PlayerBodyAnimator.player();
        animator.update(dt, inputs.set(stateFlags, horizontalSpeed, seated, headYaw, headPitch)
                .held(heldKind, bowDraw), events);
    }

    PlayerBodyOrientation getBodyOrientation() { return bodyOrientation; }

    /** The body's animator; null before the first tick. */
    PlayerBodyAnimator animator() { return animator; }
}
