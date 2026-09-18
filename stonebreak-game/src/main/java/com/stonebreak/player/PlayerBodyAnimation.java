package com.stonebreak.player;

import com.stonebreak.mobs.sbe.OverlayAnimState;
import com.stonebreak.mobs.sbe.PlayerAttackAnimation;
import com.stonebreak.mobs.sbe.PlayerGaitClock;
import com.stonebreak.mobs.sbe.PlayerStateMapping;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import org.joml.Vector3f;

/**
 * Animation clocks and orientation for the third-person body model: the continuous
 * idle clock, the foot-contact gait clock, the one-shot jump clock, the attack overlay envelope and the
 * smoothed body/head facing. Advanced once per tick by {@link PlayerUpdatePipeline}.
 */
final class PlayerBodyAnimation {

    private float bodyAnimationTime = 0f;
    private float jumpEventTime = 0f;    // seconds since jump started
    private final PlayerAttackAnimation attackAnimation = new PlayerAttackAnimation();
    private final PlayerGaitClock gaitClock = new PlayerGaitClock();
    private boolean footstepDue;
    // Third-person body facing + head look angles. Decoupled from the first-person
    // camera: the camera only supplies a look yaw/pitch; this component decides how
    // the body turns to follow movement and the look direction.
    private final PlayerBodyOrientation bodyOrientation = new PlayerBodyOrientation();

    void update(float dt, boolean attacking, boolean onGround, Vector3f velocity, Vector3f cameraFront,
                PlayerStateMapping.PlayerMovementState movementState, boolean physicallyInWater,
                SbeEntityAsset asset) {
        // Advance body animation clocks (used by third-person renderer).
        bodyAnimationTime += dt;
        if (!onGround) jumpEventTime += dt; else jumpEventTime = 0f;
        boolean contact = gaitClock.update(dt, PlayerStateMapping.gaitDuration(movementState, asset));
        footstepDue = contact && onGround && !physicallyInWater;
        // Finish the authored punch even after the short mining/combat pulse ends.
        attackAnimation.update(dt, attacking, asset);

        // Third-person body faces movement / look direction; the camera only
        // supplies the look yaw, converted from its front vector into model space.
        float lookModelYaw = PlayerBodyOrientation.modelYawFromDirection(cameraFront.x, cameraFront.z);
        bodyOrientation.update(dt, velocity, lookModelYaw);
    }

    float getBodyAnimationTime() { return gaitClock.isActive() ? gaitClock.timeSeconds() : bodyAnimationTime; }
    boolean isFootstepDue() { return footstepDue; }
    float getJumpEventTime() { return jumpEventTime; }
    OverlayAnimState getAttackOverlay() { return attackAnimation.overlay(); }
    PlayerBodyOrientation getBodyOrientation() { return bodyOrientation; }
}
