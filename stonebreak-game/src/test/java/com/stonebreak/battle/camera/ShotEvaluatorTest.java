package com.stonebreak.battle.camera;

import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.camera.CameraShot.End;
import com.stonebreak.battle.camera.CameraShot.Motion;
import com.stonebreak.ui.startupIntro.tween.EasingType;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.stonebreak.battle.camera.CameraShot.shot;
import static org.junit.jupiter.api.Assertions.*;

class ShotEvaluatorTest {

    private final BattleStageLayout layout = FakeBattleView.crucibleLayout();

    private static void assertVec(float x, float y, float z, Vector3f v) {
        assertEquals(x, v.x, 1.0e-4f, "x of " + v);
        assertEquals(y, v.y, 1.0e-4f, "y of " + v);
        assertEquals(z, v.z, 1.0e-4f, "z of " + v);
    }

    @Test void anchorFramesFollowEachActorsFacing() {
        // Monk at +Z facing −Z: right = +X. Archon faces +Z: its right = −X.
        assertVec(1f, 2.06f, 6f, AnchoredPoint.at(CameraAnchor.MONK, 1f, 2f, 3f).resolve(layout, StagePoses.HOME));
        assertVec(-1f, 2.06f, -6f, AnchoredPoint.at(CameraAnchor.ARCHON, 1f, 2f, 3f).resolve(layout, StagePoses.HOME));
        assertVec(1f, 2.06f, -3f, AnchoredPoint.at(CameraAnchor.MIDPOINT, 1f, 2f, 3f).resolve(layout, StagePoses.HOME));
        assertVec(1f, 2.06f, -3f, AnchoredPoint.at(CameraAnchor.ARENA_CENTRE, 1f, 2f, 3f).resolve(layout, StagePoses.HOME));
    }

    @Test void panSlidesTheTargetScreenRightByAConstantAmountAllTheWayRoundAnOrbit() {
        CameraShot orbit = shot("T", Motion.ORBIT).eye(AnchoredPoint.at(CameraAnchor.MONK, -1.4f, 1.4f, 3.6f))
                .target(AnchoredPoint.at(CameraAnchor.MONK, 0f, 1f, 0f)).sweep(-360f).fov(56f).duration(8f)
                .easing(EasingType.Linear).pan(19f).crossesLine().build();
        Vector3f monkChest = layout.bodyPoint(CombatantId.MONK, com.stonebreak.battle.api.ActorPose.IDLE, 0.55f);
        float first = Float.NaN;
        for (int i = 0; i <= 16; i++) {
            CameraFrame f = ShotEvaluator.evaluate(orbit, i * 0.5f, layout, StagePoses.HOME);
            org.joml.Vector4f clip = new org.joml.Vector4f(monkChest, 1f).mul(new org.joml.Matrix4f()
                    .perspective((float) Math.toRadians(f.fovDeg()), 16f / 9f, 0.1f, 100f)
                    .lookAt(f.eye(), f.target(), new Vector3f(0f, 1f, 0f)));
            float x = clip.x / clip.w;
            assertTrue(x > 0.25f && x < 0.5f, "right third, not centre: ndc x " + x);
            if (Float.isNaN(first)) first = x;
            assertEquals(first, x, 1.0e-3f, "constant round the whole orbit");
        }
        // No pan: dead centre.
        CameraShot centred = shot("T", Motion.STATIC).eye(AnchoredPoint.at(CameraAnchor.MONK, -3f, 1.4f, 0f))
                .target(AnchoredPoint.at(CameraAnchor.MONK, 0f, 1f, 0f)).build();
        CameraFrame c = ShotEvaluator.evaluate(centred, 0f, layout, StagePoses.HOME);
        assertVec(0f, 1.06f, layout.monkHome().z, c.target());
    }

    @Test void anchorsSurviveARotatedArena() {
        // Same fight staged along X: the monk's "right" must rotate with it.
        BattleStageLayout rotated = new BattleStageLayout(new Vector3f(9f, 0f, 0f), new Vector3f(-9f, 0f, 0f),
                1.8f, 2.72f, 2f, 0.35f, 50f, 0f, List.of());
        assertVec(6f, 2f, -1f, AnchoredPoint.at(CameraAnchor.MONK, 1f, 2f, 3f).resolve(rotated, StagePoses.HOME));
        assertEquals(-4f, new ShotValidator(rotated).lineSide(
                AnchoredPoint.at(CameraAnchor.MIDPOINT, -4f, 2f, 1f).resolve(rotated, StagePoses.HOME)), 1.0e-4f);
    }

    @Test void followPoseUsesTheLiveDashPositionAndHomeAnchorsDoNot() {
        StagePoses dashed = StagePoses.dashing(CombatantId.MONK, 1f);
        float strikeZ = -9f + layout.strikeDistance();
        assertVec(0f, 0.06f, strikeZ, AnchoredPoint.following(CameraAnchor.MONK, 0f, 0f, 0f).resolve(layout, dashed));
        assertVec(0f, 0.06f, 9f, AnchoredPoint.at(CameraAnchor.MONK, 0f, 0f, 0f).resolve(layout, dashed));
        assertVec(0f, 0.06f, (strikeZ - 9f) / 2f, AnchoredPoint.following(CameraAnchor.MIDPOINT, 0f, 0f, 0f).resolve(layout, dashed));
        assertVec(0f, 0.06f, 0f, AnchoredPoint.following(CameraAnchor.ARENA_CENTRE, 0f, 0f, 0f).resolve(layout, dashed));
    }

    @Test void orbitTravelsAnArcNotAChord() {
        CameraShot orbit = shot("O", Motion.ORBIT)
                .eye(AnchoredPoint.at(CameraAnchor.MONK, 0f, 1f, -4f), AnchoredPoint.at(CameraAnchor.MONK, -4f, 1f, 0f))
                .target(AnchoredPoint.at(CameraAnchor.MONK, 0f, 1f, 0f)).easing(EasingType.Linear).duration(2f).build();
        for (int i = 0; i <= 10; i++) {
            CameraFrame f = ShotEvaluator.evaluate(orbit, 2f * i / 10f, layout, StagePoses.HOME);
            assertEquals(4f, f.eye().distance(f.target()), 1.0e-3f, "radius must hold along the arc");
            assertTrue(f.eye().x <= 1.0e-4f, "shortest arc stays on the −X side");
        }
        assertVec(-4f, 1.06f, 9f, ShotEvaluator.evaluate(orbit, 2f, layout, StagePoses.HOME).eye());

        CameraShot dolly = shot("D", Motion.DOLLY).eye(orbit.eyeFrom(), orbit.eyeTo()).target(orbit.targetFrom())
                .easing(EasingType.Linear).duration(2f).build();
        CameraFrame chordMid = ShotEvaluator.evaluate(dolly, 1f, layout, StagePoses.HOME);
        assertTrue(chordMid.eye().distance(chordMid.target()) < 3f, "a dolly cuts the corner");
    }

    @Test void explicitSweepGoesTheLongWayRoundAndFullCirclesReturnHome() {
        CameraShot full = shot("F", Motion.ORBIT).eye(AnchoredPoint.at(CameraAnchor.ARCHON, 1f, 1f, 3f))
                .target(AnchoredPoint.at(CameraAnchor.ARCHON, 0f, 1f, 0f)).sweep(360f).easing(EasingType.Linear).duration(4f).build();
        Vector3f start = ShotEvaluator.evaluate(full, 0f, layout, StagePoses.HOME).eye();
        Vector3f half = ShotEvaluator.evaluate(full, 2f, layout, StagePoses.HOME).eye();
        Vector3f end = ShotEvaluator.evaluate(full, 4f, layout, StagePoses.HOME).eye();
        assertTrue(start.distance(end) < 1.0e-3f);
        assertVec(1f, 1.06f, -12f, half); // diametrically opposite
    }

    @Test void endBehaviours() {
        CameraShot.Builder base = shot("E", Motion.DOLLY)
                .eye(AnchoredPoint.at(CameraAnchor.MIDPOINT, -5f, 2f, 0f), AnchoredPoint.at(CameraAnchor.MIDPOINT, -7f, 2f, 0f))
                .target(AnchoredPoint.at(CameraAnchor.MIDPOINT, 0f, 1f, 0f)).easing(EasingType.Linear).duration(2f);
        CameraShot hold = base.end(End.HOLD).build();
        CameraShot loop = base.end(End.LOOP).build();
        CameraShot ping = base.end(End.PING_PONG).build();
        assertEquals(-7f, ShotEvaluator.evaluate(hold, 99f, layout, StagePoses.HOME).eye().x, 1.0e-4f);
        assertEquals(-6f, ShotEvaluator.evaluate(loop, 3f, layout, StagePoses.HOME).eye().x, 1.0e-4f);
        assertEquals(-6f, ShotEvaluator.evaluate(ping, 3f, layout, StagePoses.HOME).eye().x, 1.0e-4f);
        assertEquals(-5f, ShotEvaluator.evaluate(ping, 4f, layout, StagePoses.HOME).eye().x, 1.0e-4f);
        assertEquals(-5.5f, ShotEvaluator.evaluate(ping, 0.5f, layout, StagePoses.HOME).eye().x, 1.0e-4f);
    }

    @Test void degenerateShotsStillYieldAUsableFrame() {
        AnchoredPoint same = AnchoredPoint.at(CameraAnchor.MIDPOINT, -3f, 2f, 0f);
        CameraFrame coincident = ShotEvaluator.evaluate(shot("C", Motion.STATIC).eye(same).target(same).build(), 0f, layout, StagePoses.HOME);
        assertTrue(coincident.eye().distance(coincident.target()) > 0.01f, "target nudged off the eye");

        CameraFrame straightDown = ShotEvaluator.evaluate(shot("V", Motion.STATIC)
                .eye(AnchoredPoint.at(CameraAnchor.MIDPOINT, 0f, 9f, 0f)).target(AnchoredPoint.at(CameraAnchor.MIDPOINT, 0f, 0f, 0f))
                .build(), 0f, layout, StagePoses.HOME);
        float run = (float) Math.hypot(straightDown.target().x - straightDown.eye().x, straightDown.target().z - straightDown.eye().z);
        assertTrue(run > 0.01f, "lookAt with a vertical view direction is degenerate");

        CameraFrame wild = ShotEvaluator.evaluate(shot("W", Motion.DOLLY).eye(same).target(AnchoredPoint.at(CameraAnchor.MONK, 0f, 1f, 0f))
                .fov(10f, 170f).duration(0f).easing(EasingType.EaseOutElastic).build(), Float.NaN, layout, StagePoses.HOME);
        assertTrue(wild.eye().isFinite() && wild.target().isFinite());
        assertTrue(wild.fovDeg() >= 30f && wild.fovDeg() <= 90f);
    }
}
