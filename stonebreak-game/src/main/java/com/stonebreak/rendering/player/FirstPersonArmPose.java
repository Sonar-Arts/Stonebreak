package com.stonebreak.rendering.player;

import com.stonebreak.items.ItemStack;
import org.joml.Matrix4f;

/**
 * Camera placement for the authored empty-hand arm (its pose is its own animation graph, driven like the body's,
 * #457). No GL state.
 */
final class FirstPersonArmPose {

    private FirstPersonArmPose() {}

    static boolean usesAuthoredArm(ItemStack held) {
        return held == null || held.isEmpty();
    }

    static Matrix4f worldTransform(Matrix4f view, float gaitTime, boolean moving, Matrix4f dest) {
        float bob = moving ? (float) Math.sin(gaitTime * Math.PI * 4) * 0.008f : 0f;
        return view.invert(dest).translate(0.32f, -0.34f + bob, -0.12f);
    }

    static Matrix4f projection(Matrix4f worldProjection, Matrix4f dest) {
        float aspect = worldProjection.m11() / worldProjection.m00();
        return dest.setPerspective((float) Math.toRadians(70), aspect, 0.02f, 10f);
    }
}
