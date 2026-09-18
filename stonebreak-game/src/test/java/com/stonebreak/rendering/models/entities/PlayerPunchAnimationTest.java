package com.stonebreak.rendering.models.entities;

import com.stonebreak.mobs.sbe.AnimState;
import com.stonebreak.mobs.sbe.SbeEntityLoader;
import com.stonebreak.player.PlayerConstants;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the shipped punch through the same hierarchy and layering used by the game. */
class PlayerPunchAnimationTest {
    private static Map<String, Matrix4f> pose(AnimState state) {
        var asset = SbeEntityLoader.load("/sbe/Mobs/SB_Player.sbe");
        Map<String, Matrix4f> matrices = new HashMap<>();
        SbePoseSolver.forEachPartMatrix(asset.geometryFor("Default"), asset, state,
                new Matrix4f(), 0f, 0f, (matrix, part) -> matrices.put(part.name(), new Matrix4f(matrix)));
        return matrices;
    }

    @Test
    void anatomicalRightFistStrikesOnTheSlowerVisualTimeline() {
        float impact = 0.38f;
        assertTrue(impact > PlayerConstants.ATTACK_ANIMATION_DURATION);
        var matrices = pose(new AnimState(null, 0f, List.of(new AnimState.Overlay("attacking", impact, 1f))));
        // Forward (-Z) cross up (+Y) is anatomical right (+X).
        // V2's legacy hand labels are reversed; preserve the rig IDs for other clips.
        var right = matrices.get("hand_left").transformPosition(new Vector3f(0.223f, 0.842f, -0.012f));
        var left = matrices.get("hand_right").transformPosition(new Vector3f(-0.223f, 0.842f, -0.012f));
        assertTrue(right.z < -0.5f, "right fist must extend in front: " + right);
        assertTrue(right.z < left.z - 0.25f, "left stays in guard while right strikes: " + left + ", " + right);
        assertTrue(right.y > 1.25f && right.y < 1.65f, "punch stays at chest/shoulder height: " + right);
    }

    @Test
    void punchingPreservesWalkingAndSprintingLegMotion() {
        for (String gait : List.of("walking", "sprinting")) {
            var base = pose(AnimState.single(gait, 0.19f));
            var punch = pose(new AnimState(gait, 0.19f, List.of(new AnimState.Overlay("attacking", 0.38f, 1f))));
            for (String part : List.of("root", "leg_left", "leg_right", "calf_left", "calf_right",
                    "foot_left", "foot_right")) {
                assertTrue(base.get(part).equals(punch.get(part), 1e-6f), gait + ": punch changed " + part);
            }
        }
    }

    @Test
    void punchIsOneShotAndReturnsEveryPartToRest() {
        var asset = SbeEntityLoader.load("/sbe/Mobs/SB_Player.sbe");
        var clip = asset.clipFor("attacking");
        assertFalse(clip.loop());
        assertEquals(0.78f, clip.duration(), 1e-6f);
        var rest = pose(null);
        var end = pose(AnimState.single("attacking", clip.duration()));
        for (String name : rest.keySet()) {
            assertTrue(rest.get(name).equals(end.get(name), 1e-6f), name + " does not return to rest");
        }
    }
}
