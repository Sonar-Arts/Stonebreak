package com.stonebreak.player.state;

import org.joml.Vector3f;

/**
 * Mutable shared state owned by the Player facade and passed to every subsystem that
 * needs to read/write physics-relevant data. This object breaks the god-object coupling
 * of the former monolithic Player by externalizing all state that multiple subsystems
 * must observe or mutate. No game logic lives here — accessors only.
 */
public class PhysicsState {

    private final Vector3f position = new Vector3f();
    private final Vector3f velocity = new Vector3f();
    private boolean onGround;

    // Fall tracking
    private float previousY;
    private boolean wasFalling;

    // Water state
    private boolean physicallyInWater;
    private boolean wasInWaterLastFrame;
    private boolean justExitedWaterThisFrame;
    private float waterExitTime;

    public Vector3f getPosition() { return position; }
    public Vector3f getVelocity() { return velocity; }

    public boolean isOnGround() { return onGround; }
    public void setOnGround(boolean onGround) { this.onGround = onGround; }

    public float getPreviousY() { return previousY; }
    public void setPreviousY(float previousY) { this.previousY = previousY; }

    public boolean wasFalling() { return wasFalling; }
    public void setWasFalling(boolean wasFalling) { this.wasFalling = wasFalling; }

    public boolean isPhysicallyInWater() { return physicallyInWater; }
    public void setPhysicallyInWater(boolean inWater) { this.physicallyInWater = inWater; }

    public boolean wasInWaterLastFrame() { return wasInWaterLastFrame; }
    public void setWasInWaterLastFrame(boolean was) { this.wasInWaterLastFrame = was; }

    public boolean justExitedWaterThisFrame() { return justExitedWaterThisFrame; }
    public void setJustExitedWaterThisFrame(boolean exited) { this.justExitedWaterThisFrame = exited; }

    public float getWaterExitTime() { return waterExitTime; }
    public void setWaterExitTime(float t) { this.waterExitTime = t; }
}
