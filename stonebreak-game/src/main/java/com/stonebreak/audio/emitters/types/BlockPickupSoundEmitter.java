package com.stonebreak.audio.emitters.types;

import org.joml.Vector3f;
import com.stonebreak.audio.SoundSystem;
import com.stonebreak.audio.emitters.SoundEmitter;

/**
 * A sound emitter that plays the block pickup sound effect at regular intervals.
 * This is useful for testing audio systems and creating ambient sound effects.
 */
public class BlockPickupSoundEmitter extends SoundEmitter {
    private static final String SOUND_NAME = "blockpickup";
    private static final float DEFAULT_VOLUME = 0.8f;

    private float volume;

    /**
     * Creates a new block pickup sound emitter with the default 3-second interval.
     * @param position The world position where the emitter should be placed
     */
    public BlockPickupSoundEmitter(Vector3f position) {
        this(position, 3.0f); // Default 3 second interval
    }

    /**
     * Creates a new block pickup sound emitter with a custom interval.
     * @param position The world position where the emitter should be placed
     * @param interval The time between sound emissions in seconds
     */
    public BlockPickupSoundEmitter(Vector3f position, float interval) {
        super(position, interval);
        this.volume = DEFAULT_VOLUME;
    }

    /**
     * Creates a new block pickup sound emitter with custom interval and volume.
     * @param position The world position where the emitter should be placed
     * @param interval The time between sound emissions in seconds
     * @param volume The volume level (0.0 to 1.0)
     */
    public BlockPickupSoundEmitter(Vector3f position, float interval, float volume) {
        super(position, interval);
        this.volume = Math.max(0.0f, Math.min(1.0f, volume)); // Clamp between 0 and 1
    }

    @Override
    protected void playSound() {
        SoundSystem soundSystem = SoundSystem.getInstance();

        // Check if the sound is loaded before trying to play it
        if (soundSystem.isSoundLoaded(SOUND_NAME)) {
            // Set listener position to the emitter position for 3D audio effect
            soundSystem.setListenerPosition(position.x, position.y, position.z);

            // Play the sound with the configured volume
            soundSystem.playSoundWithVolume(SOUND_NAME, volume);
        } else {
            // Fallback: try to play any available sound for testing
            // This ensures the emitter still functions even if the specific sound isn't loaded
            System.err.println("BlockPickupSoundEmitter: '" + SOUND_NAME + "' sound not loaded at position " + position);
        }
    }

    @Override
    public String getEmitterType() {
        return "Block Pickup Emitter";
    }

    /**
     * Gets the current volume level of this emitter.
     * @return The volume level (0.0 to 1.0)
     */
    public float getVolume() {
        return volume;
    }

    /**
     * Sets the volume level for this emitter.
     * @param volume The new volume level (0.0 to 1.0)
     */
    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume)); // Clamp between 0 and 1
    }

    /**
     * Gets the name of the sound this emitter plays.
     * @return The sound name identifier
     */
    public String getSoundName() {
        return SOUND_NAME;
    }

    @Override
    public String getDebugInfo() {
        return String.format("%s at (%.1f, %.1f, %.1f) - Active: %s, Timer: %.1f/%.1f, Volume: %.2f",
                getEmitterType(), position.x, position.y, position.z,
                isActive, timer, interval, volume);
    }
}