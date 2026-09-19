package com.stonebreak.battle.camera;

import org.joml.Vector3f;

/** One evaluated camera frame: what the rig hands to the player Camera override and the FOV seam. */
public record CameraFrame(Vector3f eye, Vector3f target, float rollDeg, float fovDeg) {
    public CameraFrame {
        eye = new Vector3f(eye);
        target = new Vector3f(target);
    }
}
