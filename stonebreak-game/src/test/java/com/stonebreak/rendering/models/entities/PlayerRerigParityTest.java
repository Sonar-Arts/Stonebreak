package com.stonebreak.rendering.models.entities;

import com.openmason.engine.entity.model.EntityAsset;
import com.openmason.engine.entity.model.EntityModelLoader;
import com.stonebreak.mobs.sbe.AnimState;
import com.stonebreak.mobs.sbe.GameSbeAssets;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stonebreak #456: SB_Player was re-rigged so its BONES are the skeleton (the clips key the bones; the old "joint
 * parts" are geometry under them, renamed {@code *_mesh}). Proof that nothing visible changed: every part of the
 * shipped asset gets the same world matrix as the frozen pre-rerig asset ({@code rig/pre-rerig/SB_Player.sbe}) in
 * every clip across its whole length, with head tracking, and under the attacking overlay - through the game's own
 * pose solver. Re-rig script: {@code Dev Working/scripts/sb-player-rerig/rerig_sb_player.py}.
 */
class PlayerRerigParityTest {

    private static final float TOLERANCE = 2e-5f;

    private static EntityAsset before() {
        return EntityModelLoader.load("/rig/pre-rerig/SB_Player.sbe", PlayerRerigParityTest.class::getResourceAsStream);
    }

    private static Map<String, Matrix4f> pose(EntityAsset asset, AnimState anim, float yaw, float pitch) {
        Map<String, Matrix4f> byId = new HashMap<>();
        SbePoseSolver.forEachPartMatrix(asset.geometryFor("Default"), asset, anim, new Matrix4f(), yaw, pitch,
                (matrix, part) -> byId.put(part.id(), new Matrix4f(matrix)));
        return byId;
    }

    private static List<String> states(EntityAsset asset) {
        List<String> out = new ArrayList<>(asset.clips().keySet());
        java.util.Collections.sort(out);
        return out;
    }

    @Test
    void everyPartPosesExactlyAsBeforeInEveryClip() {
        EntityAsset old = before();
        EntityAsset rerig = GameSbeAssets.load("/sbe/Mobs/SB_Player.sbe");
        List<String> states = states(old);
        assertEquals(states, states(rerig), "same states");
        assertEquals(35, states.size());
        int compared = 0;
        double worst = 0;
        String worstAt = "";
        for (String state : states) {
            var clip = old.clipFor(state);
            float duration = clip == null ? 0f : clip.duration();
            for (int k = 0; k <= 12; k++) {
                float t = duration * k / 12f;
                for (float[] head : new float[][] {{0f, 0f}, {35f, -20f}}) {
                    Map<String, Matrix4f> a = pose(old, AnimState.single(state, t), head[0], head[1]);
                    Map<String, Matrix4f> b = pose(rerig, AnimState.single(state, t), head[0], head[1]);
                    assertEquals(a.keySet(), b.keySet(), "same parts");
                    for (var e : a.entrySet()) {
                        double d = maxDiff(e.getValue(), b.get(e.getKey()));
                        if (d > worst) {
                            worst = d;
                            worstAt = state + " t=" + t + " head=" + head[0] + " part " + e.getKey();
                        }
                        compared++;
                    }
                }
            }
        }
        assertTrue(worst <= TOLERANCE, "worst " + worst + " at " + worstAt);
        assertTrue(compared > 85 * 35 * 13, "compared " + compared);
    }

    @Test
    void theAttackingOverlayStillMasksTheUpperBody() {
        EntityAsset old = before();
        EntityAsset rerig = GameSbeAssets.load("/sbe/Mobs/SB_Player.sbe");
        for (String gait : List.of("walking", "sprinting", "idle")) {
            for (float t : new float[] {0.1f, 0.38f, 0.6f}) {
                AnimState anim = new AnimState(gait, 0.19f, List.of(new AnimState.Overlay("attacking", t, 0.7f)));
                Map<String, Matrix4f> a = pose(old, anim, 10f, 5f);
                Map<String, Matrix4f> b = pose(rerig, anim, 10f, 5f);
                for (var e : a.entrySet()) {
                    assertTrue(maxDiff(e.getValue(), b.get(e.getKey())) <= TOLERANCE, gait + " t=" + t + " part " + e.getKey());
                }
            }
        }
    }

    @Test
    void theBonesAreTheSkeleton() {
        EntityAsset rerig = GameSbeAssets.load("/sbe/Mobs/SB_Player.sbe");
        var rig = rerig.geometryFor("Default").rig().rig();
        // Every joint part hangs under its bone; a name never binds two joints.
        java.util.Set<String> names = new java.util.HashSet<>();
        for (var j : rig.joints()) assertTrue(names.add(j.name().toLowerCase()), "duplicate joint name " + j.name());
        int torso = rig.find("torso");
        assertTrue(torso >= 0 && rig.joints().get(torso).kind() == com.openmason.engine.rig.RigJoint.Kind.BONE);
        int mesh = rig.find("arm_left_mesh");
        assertTrue(mesh >= 0 && rig.joints().get(rig.joints().get(mesh).parent()).name().equals("arm_left"));
    }

    private static double maxDiff(Matrix4f a, Matrix4f b) {
        double m = 0;
        float[] x = a.get(new float[16]), y = b.get(new float[16]);
        for (int i = 0; i < 16; i++) m = Math.max(m, Math.abs(x[i] - y[i]));
        return m;
    }
}
