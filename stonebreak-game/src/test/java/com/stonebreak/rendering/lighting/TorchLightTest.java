package com.stonebreak.rendering.lighting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The torch light's pulse tracks the ember clip: dim at the loop ends, bright mid-loop. */
class TorchLightTest {

    @Test
    void pulseFollowsTheEmberClip() {
        float d = TorchLight.CLIP_DURATION;
        float start = TorchLight.intensity(0f);
        float mid = TorchLight.intensity(d * 0.5f);
        float end = TorchLight.intensity(d);
        assertTrue(mid > start + 0.1f, "mid-loop is the ember's peak");
        assertEquals(start, end, 1e-4f, "loop ends match");
        assertEquals(start, TorchLight.intensity(7 * d), 1e-3f, "periodic");
        // Smooth: no step between adjacent frames larger than the pulse slope allows.
        float prev = TorchLight.intensity(0f);
        for (float t = 1f / 60f; t < 2 * d; t += 1f / 60f) {
            float cur = TorchLight.intensity(t);
            assertTrue(Math.abs(cur - prev) < 0.012f, "jump at t=" + t);
            prev = cur;
        }
    }

    @Test
    void theFxPulseIsTheEmbersSwellNormalised() {
        LightProfile p = TorchLight.PROFILE;
        float d = TorchLight.CLIP_DURATION;
        assertEquals(0f, p.pulse(0f), 1e-6f, "clip start: ember at rest");
        assertEquals(1f, p.pulse(d * 0.5f), 1e-6f, "0.6 s: the ember's 1.08 swell key");
        assertEquals(0f, p.pulse(d), 1e-4f, "clip end");
        for (float t = 0f; t < 3f; t += 0.05f) {
            assertEquals(p.floor() + (1f - p.floor()) * p.pulse(t), p.intensity(t), 1e-6f,
                    "the light and the FX read the same pulse");
        }
    }

    @Test
    void staysInASaneRange() {
        for (float t = 0f; t < 10f; t += 0.01f) {
            float i = TorchLight.intensity(t);
            assertTrue(i > 0.7f && i < 1.06f, "t=" + t + " i=" + i);
        }
    }
}
