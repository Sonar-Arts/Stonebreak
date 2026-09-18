package com.stonebreak.rendering.player;

import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.sbe.AnimState;
import com.stonebreak.mobs.sbe.OverlayAnimState;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import org.joml.Matrix4f;

import java.util.List;

/** Camera placement and animation selection for the authored empty-hand arm. No GL state. */
final class FirstPersonArmPose {
    static final String RESOURCE = "/sbe/Player/SB_FPArm.sbe";

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

    static AnimState animation(SbeEntityAsset arm, float idleTime, OverlayAnimState attack) {
        var clip = arm.clipFor("attacking");
        if (clip == null || !attack.isVisible()) return AnimState.single("idle", idleTime);
        return new AnimState("idle", idleTime, List.of(new AnimState.Overlay("attacking", attack.time(),
                attack.weight(clip.layer().fadeInSeconds(), clip.layer().fadeOutSeconds()))));
    }
}
