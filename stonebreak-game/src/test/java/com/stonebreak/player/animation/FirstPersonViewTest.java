package com.stonebreak.player.animation;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.rig.JointPalette;
import com.stonebreak.player.PlayerCharacter;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #458 plumbing for #462: the first-person view of the shipped player character - the camera on its eyes, the
 * first-person palette showing only the arm branches. Not drawn until the character has its first-person clips.
 */
class FirstPersonViewTest {

    private static final float DT = 1f / 60f;

    @BeforeAll
    static void needsCenda() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda native kernels unavailable");
    }

    @Test
    void theGraphHasTheViewLayerButTheCharacterHasNoFirstPersonClipsYet() {
        PlayerAnimationSet set = PlayerAnimationSet.player();
        assertEquals(1, set.graph().firstPersonLayerCount(), "the player graph's root carries the first-person layer");
        assertTrue(set.graph().slots().stream().anyMatch(s -> s.startsWith("fp_")));
        assertFalse(FirstPersonView.ready(set), "SB_Player has no fp_* clips: first person stays the old arm (#462)");
    }

    @Test
    void theFirstPersonPaletteShowsOnlyTheArms() {
        PlayerAnimationSet set = PlayerAnimationSet.player();
        try (PlayerBodyAnimator body = PlayerBodyAnimator.player(); FirstPersonView view = new FirstPersonView(set)) {
            PlayerPoseInputs in = new PlayerPoseInputs().standing();
            for (int i = 0; i < 30; i++) body.update(DT, in, PlayerBodyAnimator.Events.NONE);
            Matrix4f base = new Matrix4f().translation(5f, 64f, -3f);
            Vector3f camera = new Vector3f();
            for (int i = 0; i < 10; i++) camera.set(view.camera(body, base, DT));
            float eyes = PlayerCharacter.get().eyeHeight();
            assertEquals(64f + eyes, camera.y, 0.05f, "the camera sits at the character's eyes");

            Matrix4f viewMatrix = new Matrix4f().translation(-camera.x, -camera.y, -camera.z);
            Matrix4f worldProjection = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.1f, 1000f);
            view.compose(body, base, viewMatrix, worldProjection);

            float[] shown = view.firstPersonMask().weights();
            JointPalette fp = view.palette();
            Matrix4f m = new Matrix4f();
            int visible = 0, hidden = 0;
            for (int j = 0; j < fp.count(); j++) {
                float det = Math.abs(fp.skinMatrix(j, m).determinant3x3());
                if (shown[j] > 0.999f) {
                    visible++;
                    assertTrue(det > 1e-6f, "arm joint " + set.rig().joints().get(j).name() + " is drawn");
                } else if (shown[j] < 0.001f) {
                    hidden++;
                    assertTrue(det < 1e-9f, "joint " + set.rig().joints().get(j).name() + " collapses in first person");
                }
            }
            assertTrue(visible >= 6, "both arms (upper, lower, hand + fingers): " + visible);
            assertTrue(hidden > visible, "the rest of the body is hidden: " + hidden);
            assertTrue(shown[set.headJoint] < 0.001f, "the head never draws in first person");
        }
    }
}
