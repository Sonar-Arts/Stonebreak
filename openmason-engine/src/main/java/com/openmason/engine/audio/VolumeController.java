package com.openmason.engine.audio;

public class VolumeController {
    private float masterVolume = 1.0f;
    private float environmentGain = 1.0f;

    /**
     * Sets the master volume for all sounds.
     * @param volume Volume level (0.0 = silent, 1.0 = normal volume)
     */
    public void setMasterVolume(float volume) {
        this.masterVolume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    /**
     * Gets the current master volume (the user's raw volume setting, independent
     * of any environmental ducking).
     * @return Current master volume level
     */
    public float getMasterVolume() {
        return masterVolume;
    }

    /**
     * Multiplicative ducking applied on top of the user's master volume — e.g. muffling
     * audio while the listener is underwater. 1.0 = no ducking.
     */
    public void setEnvironmentGain(float gain) {
        this.environmentGain = Math.max(0.0f, Math.min(1.0f, gain));
    }

    public float getEnvironmentGain() {
        return environmentGain;
    }

    /** Combined gain actually applied to sources: master volume times environmental ducking. */
    public float getEffectiveVolume() {
        return masterVolume * environmentGain;
    }
}