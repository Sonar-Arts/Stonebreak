package com.stonebreak.player;

import com.stonebreak.mobs.sbe.OverlayAnimState;
import org.joml.Vector3f;

/**
 * Animation clocks and orientation for the third-person body model: the continuous
 * locomotion clock, the one-shot jump clock, the attack overlay envelope and the
 * smoothed body/head facing. Advanced once per tick by {@link PlayerUpdatePipeline}.
 */
final class PlayerBodyAnimation {

    private float bodyAnimationTime = 0f;
    private float attackEventTime = 0f;  // seconds since attack animation started
    private float jumpEventTime = 0f;    // seconds since jump started
    private final OverlayAnimState attackOverlay = new OverlayAnimState();
    // Third-person body facing + head look angles. Decoupled from the first-person
    // camera: the camera only supplies a look yaw/pitch; this component decides how
    // the body turns to follow movement and the look direction.
    private final PlayerBodyOrientation bodyOrientation = new PlayerBodyOrientation();

    void update(float dt, boolean attacking, boolean onGround, Vector3f velocity, Vector3f cameraFront) {
        // Advance body animation clocks (used by third-person renderer).
        bodyAnimationTime += dt;
        if (attacking) attackEventTime += dt; else attackEventTime = 0f;
        if (!onGround) jumpEventTime += dt; else jumpEventTime = 0f;
        // Attack overlay envelope: attack plays on top of the locomotion clip,
        // masked to the parts the attack clip owns, with fade in/out.
        attackOverlay.update(dt, attacking);

        // Third-person body faces movement / look direction; the camera only
        // supplies the look yaw, converted from its front vector into model space.
        float lookModelYaw = PlayerBodyOrientation.modelYawFromDirection(cameraFront.x, cameraFront.z);
        bodyOrientation.update(dt, velocity, lookModelYaw);
    }

    float getBodyAnimationTime() { return bodyAnimationTime; }
    float getJumpEventTime() { return jumpEventTime; }
    OverlayAnimState getAttackOverlay() { return attackOverlay; }
    PlayerBodyOrientation getBodyOrientation() { return bodyOrientation; }
}
