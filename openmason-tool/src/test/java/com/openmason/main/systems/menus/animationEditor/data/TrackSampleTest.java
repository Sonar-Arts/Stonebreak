package com.openmason.main.systems.menus.animationEditor.data;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Parity guard: the {@code parity*} fixtures here are numerically identical to
 * the engine's {@code AnimSamplerTest} — the tool preview and in-game playback
 * must sample identically. If you change one, change both.
 */
class TrackSampleTest {

    private static final float EPS = 1e-4f;

    private static Track track(Keyframe... kfs) {
        Track t = new Track("part-1");
        for (Keyframe kf : kfs) {
            t.upsert(kf);
        }
        return t;
    }

    private static Keyframe kf(float t, Vector3f pos, Vector3f rot, Vector3f scale, Easing easing) {
        return new Keyframe(t, pos, rot, scale, easing);
    }

    @Test
    void emptyTrackSamplesNull() {
        assertNull(new Track("p").sample(0.5f));
    }

    @Test
    void sampleClampsOutsideRange() {
        Track tr = track(
                kf(0.2f, new Vector3f(1, 0, 0), new Vector3f(), new Vector3f(1, 1, 1), Easing.LINEAR),
                kf(0.8f, new Vector3f(3, 0, 0), new Vector3f(), new Vector3f(1, 1, 1), Easing.LINEAR));
        assertEquals(1f, tr.sample(0f).position().x, EPS);
        assertEquals(3f, tr.sample(1f).position().x, EPS);
    }

    // ---- Parity fixtures (mirrored in the engine's AnimSamplerTest) ----

    @Test
    void parityLinearMidpoint() {
        Track tr = track(
                kf(0f, new Vector3f(0, 0, 0), new Vector3f(350, -170, 30), new Vector3f(1, 1, 1), Easing.LINEAR),
                kf(1f, new Vector3f(2, 4, -6), new Vector3f(10, 170, 60), new Vector3f(2, 2, 2), Easing.LINEAR));
        Track.Sample s = tr.sample(0.5f);
        assertEquals(1f, s.position().x, EPS);
        assertEquals(2f, s.position().y, EPS);
        assertEquals(-3f, s.position().z, EPS);
        // shortest path: 350->10 crosses 0; -170->170 crosses ±180
        assertEquals(360f, s.rotation().x, EPS);
        assertEquals(-180f, s.rotation().y, EPS);
        assertEquals(45f, s.rotation().z, EPS);
        assertEquals(1.5f, s.scale().x, EPS);
    }

    @Test
    void parityEaseInQuarter() {
        // EASE_IN cubic: e = u^3; at u=0.25 -> 0.015625
        Track tr = track(
                kf(0f, new Vector3f(0, 0, 0), new Vector3f(0, 0, 0), new Vector3f(1, 1, 1), Easing.EASE_IN),
                kf(2f, new Vector3f(8, 0, 0), new Vector3f(0, 90, 0), new Vector3f(1, 1, 1), Easing.LINEAR));
        Track.Sample s = tr.sample(0.5f);
        assertEquals(8f * 0.015625f, s.position().x, EPS);
        assertEquals(90f * 0.015625f, s.rotation().y, EPS);
    }
}
