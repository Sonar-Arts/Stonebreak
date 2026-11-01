package com.openmason.engine.format.oma;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sampling math tests. The numeric fixtures in {@code parity*} tests are
 * duplicated in the tool's {@code TrackSampleTest} — the two samplers must
 * produce identical results so the editor preview matches in-game playback.
 * If you change one, change both.
 */
class AnimSamplerTest {

    private static final float EPS = 1e-4f;

    // ===================== lerpAngleDeg =====================

    @Test
    void angleLerpCrossesZeroForward() {
        // 350 -> 10 is +20 the short way, through 0/360.
        assertEquals(355f, AnimSampler.lerpAngleDeg(350f, 10f, 0.25f), EPS);
        assertEquals(360f, AnimSampler.lerpAngleDeg(350f, 10f, 0.5f), EPS);
        assertEquals(370f, AnimSampler.lerpAngleDeg(350f, 10f, 1f), EPS); // == 10 mod 360
    }

    @Test
    void angleLerpCrossesOneEightyBackward() {
        // -170 -> 170 is -20 the short way, through ±180.
        assertEquals(-175f, AnimSampler.lerpAngleDeg(-170f, 170f, 0.25f), EPS);
        assertEquals(-180f, AnimSampler.lerpAngleDeg(-170f, 170f, 0.5f), EPS);
        assertEquals(-190f, AnimSampler.lerpAngleDeg(-170f, 170f, 1f), EPS); // == 170 mod 360
    }

    @Test
    void angleLerpEndpointsExact() {
        assertEquals(37f, AnimSampler.lerpAngleDeg(37f, 122f, 0f), EPS);
        assertEquals(122f, AnimSampler.lerpAngleDeg(37f, 122f, 1f), EPS);
    }

    @Test
    void angleLerpSmallDeltaIsPlainLerp() {
        assertEquals(45f, AnimSampler.lerpAngleDeg(30f, 60f, 0.5f), EPS);
        assertEquals(-45f, AnimSampler.lerpAngleDeg(-30f, -60f, 0.5f), EPS);
    }

    @Test
    void angleLerpExactOppositePicksNegativeDirection() {
        // 180° apart is ambiguous; the formula resolves to the negative direction.
        // Pinned so a refactor changing the convention is caught.
        assertEquals(-90f, AnimSampler.lerpAngleDeg(0f, 180f, 0.5f), EPS);
    }

    // ===================== wrapTime =====================

    @Test
    void wrapTimeLoops() {
        assertEquals(0.5f, AnimSampler.wrapTime(2.5f, 1f, true), EPS);
        assertEquals(0.75f, AnimSampler.wrapTime(-0.25f, 1f, true), EPS);
    }

    @Test
    void wrapTimeClampsWhenNotLooping() {
        assertEquals(1f, AnimSampler.wrapTime(2.5f, 1f, false), EPS);
        assertEquals(0f, AnimSampler.wrapTime(-0.5f, 1f, false), EPS);
        assertEquals(0f, AnimSampler.wrapTime(3f, 0f, true), EPS);
    }

    // ===================== sample =====================

    private static ParsedAnimTrack track(ParsedKeyframe... kfs) {
        return new ParsedAnimTrack("part-1", "part", List.of(kfs));
    }

    private static ParsedKeyframe kf(float t, Vector3f pos, Vector3f rot, Vector3f scale, String easing) {
        return new ParsedKeyframe(t, pos, rot, scale, easing);
    }

    @Test
    void sampleClampsOutsideRange() {
        ParsedAnimTrack tr = track(
                kf(0.2f, new Vector3f(1, 0, 0), new Vector3f(), new Vector3f(1, 1, 1), "LINEAR"),
                kf(0.8f, new Vector3f(3, 0, 0), new Vector3f(), new Vector3f(1, 1, 1), "LINEAR"));
        assertEquals(1f, AnimSampler.sample(tr, 0f).position().x, EPS);
        assertEquals(3f, AnimSampler.sample(tr, 1f).position().x, EPS);
    }

    @Test
    void emptyTrackYieldsIdentity() {
        AnimSampler.PartPose pose = AnimSampler.sample(new ParsedAnimTrack("p", "p", List.of()), 0.5f);
        assertEquals(0f, pose.position().x, EPS);
        assertEquals(1f, pose.scale().x, EPS);
    }

    // ---- Parity fixtures (mirrored in the tool's TrackSampleTest) ----

    @Test
    void parityLinearMidpoint() {
        ParsedAnimTrack tr = track(
                kf(0f, new Vector3f(0, 0, 0), new Vector3f(350, -170, 30), new Vector3f(1, 1, 1), "LINEAR"),
                kf(1f, new Vector3f(2, 4, -6), new Vector3f(10, 170, 60), new Vector3f(2, 2, 2), "LINEAR"));
        AnimSampler.PartPose pose = AnimSampler.sample(tr, 0.5f);
        assertEquals(1f, pose.position().x, EPS);
        assertEquals(2f, pose.position().y, EPS);
        assertEquals(-3f, pose.position().z, EPS);
        // shortest path: 350->10 crosses 0; -170->170 crosses ±180
        assertEquals(360f, pose.rotationDeg().x, EPS);
        assertEquals(-180f, pose.rotationDeg().y, EPS);
        assertEquals(45f, pose.rotationDeg().z, EPS);
        assertEquals(1.5f, pose.scale().x, EPS);
    }

    @Test
    void parityEaseInQuarter() {
        // EASE_IN cubic: e = u^3; at u=0.25 -> 0.015625
        ParsedAnimTrack tr = track(
                kf(0f, new Vector3f(0, 0, 0), new Vector3f(0, 0, 0), new Vector3f(1, 1, 1), "EASE_IN"),
                kf(2f, new Vector3f(8, 0, 0), new Vector3f(0, 90, 0), new Vector3f(1, 1, 1), "LINEAR"));
        AnimSampler.PartPose pose = AnimSampler.sample(tr, 0.5f);
        assertEquals(8f * 0.015625f, pose.position().x, EPS);
        assertEquals(90f * 0.015625f, pose.rotationDeg().y, EPS);
    }
}
