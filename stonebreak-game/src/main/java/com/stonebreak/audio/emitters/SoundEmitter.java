package com.stonebreak.audio.emitters;

import org.joml.Vector3f;
import com.stonebreak.audio.SoundSystem;

/**
 * Base class for sound emitters that can be placed in the world and play sounds automatically.
 * Sound emitters are modular objects that can play different types of sounds at regular intervals.
 */
public abstract class SoundEmitter {
    protected Vector3f position;
    protected float interval; // Time between sound emissions in seconds
    protected float timer; // Current timer value
    protected boolean isActive;
    protected boolean isDebugVisible;

    /**
     * Creates a new sound emitter at the specified position.
     * @param position The world position of the sound emitter
     * @param interval The time interval between sound emissions in seconds
     */
    public SoundEmitter(Vector3f position, float interval) {
        this.position = new Vector3f(position);
        this.interval = interval;
        this.timer = 0.0f;
        this.isActive = true;
        this.isDebugVisible = true;
    }

    /**
     * Updates the sound emitter, handling timing and sound playback.
     * Only plays sounds if a world is loaded and a player exists.
     * @param deltaTime The time elapsed since last update in seconds
     */
    public void update(float deltaTime) {
        if (!isActive) {
            return;
        }

        // Safety check: ensure we have a valid game state before playing sounds
        if (!canPlaySounds()) {
            return;
        }

        timer += deltaTime;
        if (timer >= interval) {
            playSound();
            timer = 0.0f;
        }
    }

    /**
     * Checks if sounds can be played based on game state.
     * @return True if sounds should be played, false otherwise
     */
    protected boolean canPlaySounds() {
        // Ensure we have a loaded world and player before playing sounds
        return com.stonebreak.core.Game.getWorld() != null &&
               com.stonebreak.core.Game.getPlayer() != null;
    }

    /**
     * Abstract method that subclasses must implement to define what sound to play.
     */
    protected abstract void playSound();

    /**
     * Gets the display name of this sound emitter type.
     * @return The human-readable name of this emitter type
     */
    public abstract String getEmitterType();

    /**
     * Gets the world position of this sound emitter.
     * @return A copy of the position vector
     */
    public Vector3f getPosition() {
        return new Vector3f(position);
    }

    /**
     * Sets the world position of this sound emitter.
     * @param position The new position
     */
    public void setPosition(Vector3f position) {
        this.position.set(position);
    }

    /**
     * Gets the sound emission interval in seconds.
     * @return The interval between sound emissions
     */
    public float getInterval() {
        return interval;
    }

    /**
     * Sets the sound emission interval.
     * @param interval The new interval in seconds
     */
    public void setInterval(float interval) {
        this.interval = Math.max(0.1f, interval); // Minimum 0.1 seconds
    }

    /**
     * Checks if this sound emitter is currently active.
     * @return True if the emitter is active and playing sounds
     */
    public boolean isActive() {
        return isActive;
    }

    /**
     * Sets the active state of this sound emitter.
     * @param active True to activate, false to deactivate
     */
    public void setActive(boolean active) {
        this.isActive = active;
        if (!active) {
            timer = 0.0f; // Reset timer when deactivated
        }
    }

    /**
     * Checks if this sound emitter should be visible in debug mode.
     * @return True if the emitter should show debug visualization
     */
    public boolean isDebugVisible() {
        return isDebugVisible;
    }

    /**
     * Sets the debug visibility of this sound emitter.
     * @param visible True to show debug visualization, false to hide
     */
    public void setDebugVisible(boolean visible) {
        this.isDebugVisible = visible;
    }

    /**
     * Gets the current timer value (useful for debugging).
     * @return The current timer value in seconds
     */
    public float getCurrentTimer() {
        return timer;
    }

    /**
     * Immediately triggers the sound emission, regardless of timer state.
     * Only plays if the emitter is active and game state allows sound playback.
     */
    public void triggerSound() {
        if (isActive && canPlaySounds()) {
            playSound();
        }
    }

    /**
     * Resets the timer to zero, effectively restarting the emission cycle.
     */
    public void resetTimer() {
        timer = 0.0f;
    }

    /**
     * Gets a description of this sound emitter for debugging purposes.
     * @return A string describing the emitter's current state
     */
    public String getDebugInfo() {
        return String.format("%s at (%.1f, %.1f, %.1f) - Active: %s, Timer: %.1f/%.1f",
                getEmitterType(), position.x, position.y, position.z,
                isActive, timer, interval);
    }
}