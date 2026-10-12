package com.openmason.engine.audio;

import org.junit.jupiter.api.Tag;
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

    /** NaN used to pass the min/max clamp and poison every later AL_GAIN; it now keeps the old value. */
    @Tag("regression")
    @Test
    void nanVolumeKeepsThePreviousSetting() {
        VolumeController v = new VolumeController();
        v.setMasterVolume(0.6f);
        v.setEnvironmentGain(0.5f);

        v.setMasterVolume(Float.NaN);
        v.setEnvironmentGain(Float.NaN);

        assertEquals(0.6f, v.getMasterVolume(), 1e-6f);
        assertEquals(0.5f, v.getEnvironmentGain(), 1e-6f);
        assertEquals(0.3f, v.getEffectiveVolume(), 1e-6f);
    }

    @Test
    void infinitiesClampToTheRangeEnds() {
        VolumeController v = new VolumeController();
        v.setMasterVolume(Float.POSITIVE_INFINITY);
        assertEquals(1.0f, v.getMasterVolume());
        v.setMasterVolume(Float.NEGATIVE_INFINITY);
        assertEquals(0.0f, v.getMasterVolume());
    }
}
