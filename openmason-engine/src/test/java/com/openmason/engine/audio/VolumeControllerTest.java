package com.openmason.engine.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gain math behind every SFX play call and the live music gain: clamping and master x ducking. */
class VolumeControllerTest {

    private static final float EPS = 1e-6f;

    @Test
    void defaultsToFullVolumeWithNoDucking() {
        VolumeController v = new VolumeController();
        assertEquals(1.0f, v.getEffectiveVolume(), EPS);
    }

    @Test
    void masterVolumeIsClampedToUnitRange() {
        VolumeController v = new VolumeController();
        v.setMasterVolume(1.7f);
        assertEquals(1.0f, v.getMasterVolume(), EPS);
        v.setMasterVolume(-0.3f);
        assertEquals(0.0f, v.getMasterVolume(), EPS);
        v.setMasterVolume(0.42f);
        assertEquals(0.42f, v.getMasterVolume(), EPS);
    }

    @Test
    void environmentGainIsClampedToUnitRange() {
        VolumeController v = new VolumeController();
        v.setEnvironmentGain(3f);
        assertEquals(1.0f, v.getEnvironmentGain(), EPS);
        v.setEnvironmentGain(-1f);
        assertEquals(0.0f, v.getEnvironmentGain(), EPS);
    }

    @Test
    void effectiveVolumeIsMasterTimesEnvironmentGain() {
        VolumeController v = new VolumeController();
        v.setMasterVolume(0.8f);
        v.setEnvironmentGain(0.25f);
        assertEquals(0.2f, v.getEffectiveVolume(), EPS);
    }

    @Test
    void duckingLeavesTheUsersMasterSettingUntouched() {
        VolumeController v = new VolumeController();
        v.setMasterVolume(0.6f);
        v.setEnvironmentGain(0.5f);
        v.setEnvironmentGain(1.0f); // surface again
        assertEquals(0.6f, v.getMasterVolume(), EPS);
        assertEquals(0.6f, v.getEffectiveVolume(), EPS);
    }

    @Test
    void mutingEitherFactorSilencesEverything() {
        VolumeController v = new VolumeController();
        v.setMasterVolume(0f);
        v.setEnvironmentGain(1f);
        assertEquals(0f, v.getEffectiveVolume(), EPS);
        v.setMasterVolume(1f);
        v.setEnvironmentGain(0f);
        assertEquals(0f, v.getEffectiveVolume(), EPS);
    }

    @Test
    void effectiveVolumeStaysInUnitRangeForAnyClampedInputs() {
        VolumeController v = new VolumeController();
        float[] inputs = {-5f, -0f, 0f, 0.001f, 0.5f, 0.999f, 1f, 2f, Float.MAX_VALUE, Float.NEGATIVE_INFINITY,
                Float.POSITIVE_INFINITY};
        for (float m : inputs) {
            for (float e : inputs) {
                v.setMasterVolume(m);
                v.setEnvironmentGain(e);
                float eff = v.getEffectiveVolume();
                assertTrue(eff >= 0f && eff <= 1f, "effective " + eff + " for master " + m + ", env " + e);
            }
        }
    }

    /**
     * Documents current behaviour (reported, not fixed): the clamp is
     * {@code max(0, min(1, x))}, and both {@code Math.min} and {@code Math.max} propagate NaN, so a
     * NaN volume is stored as-is and every subsequent AL_GAIN becomes NaN (OpenAL rejects it with
     * AL_INVALID_VALUE, so sounds keep their previous gain).
     */
    @Test
    void nanVolumeIsNotClampedCurrentBehaviour() {
        VolumeController v = new VolumeController();
        v.setMasterVolume(Float.NaN);
        assertTrue(Float.isNaN(v.getMasterVolume()));
        assertTrue(Float.isNaN(v.getEffectiveVolume()));
    }
}
