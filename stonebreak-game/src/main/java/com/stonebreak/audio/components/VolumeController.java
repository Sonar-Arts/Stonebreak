package com.stonebreak.audio.components;

public class VolumeController {
    private float masterVolume = 1.0f;

    /**
     * Sets the master volume for all sounds.
     * @param volume Volume level (0.0 = silent, 1.0 = normal volume)
     */
    public void setMasterVolume(float volume) {
        this.masterVolume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    /**
     * Gets the current master volume.
     * @return Current master volume level
     */
    public float getMasterVolume() {
        return masterVolume;
    }
}