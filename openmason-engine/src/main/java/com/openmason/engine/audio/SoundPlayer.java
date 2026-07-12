package com.openmason.engine.audio;

import static org.lwjgl.openal.AL10.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SoundPlayer {
    private static final Logger logger = LoggerFactory.getLogger(SoundPlayer.class);

    private final SoundBuffer soundBuffer;
    private final VolumeController volumeController;

    public SoundPlayer(SoundBuffer soundBuffer, VolumeController volumeController) {
        this.soundBuffer = soundBuffer;
        this.volumeController = volumeController;
    }

    public void playSound(String name) {
        playSoundWithVolume(name, 0.5f);
    }

    public void playSoundWithVolume(String name, float volume) {
        Integer[] soundSources = soundBuffer.getSources(name);
        if (soundSources != null) {
            int currentIndex = soundBuffer.getNextSourceIndex(name);
            if (currentIndex == -1) {
                logger.warn("Failed to get source index for sound: {}", name);
                return;
            }

            int source = soundSources[currentIndex];

            float finalVolume = volume * volumeController.getMasterVolume();
            alSourcef(source, AL_GAIN, finalVolume);

            // For regular (non-3D) sounds, make them relative to the listener
            // This is appropriate for player sounds like walking, UI sounds, etc.
            alSourcei(source, AL_SOURCE_RELATIVE, AL_TRUE);
            alSource3f(source, AL_POSITION, 0.0f, 0.0f, 0.0f); // At listener position

            alSourcePlay(source);

            int error = alGetError();
            if (error != AL_NO_ERROR) {
                logger.error("OpenAL error playing sound {} with volume {}: {}", name, volume, error);
            }
        } else {
            logger.warn("Sound not found: {}", name);
        }
    }

    /**
     * Plays a 2D (listener-relative) sound at an explicit pitch. Used by
     * data-driven sound defs that author their own pitch range; the caller
     * picks the concrete pitch per trigger.
     */
    public void playSound(String name, float volume, float pitch) {
        Integer[] soundSources = soundBuffer.getSources(name);
        if (soundSources != null) {
            int currentIndex = soundBuffer.getNextSourceIndex(name);
            if (currentIndex == -1) {
                logger.warn("Failed to get source index for sound: {}", name);
                return;
            }

            int source = soundSources[currentIndex];

            float finalVolume = volume * volumeController.getMasterVolume();
            alSourcef(source, AL_GAIN, finalVolume);
            alSourcef(source, AL_PITCH, pitch);

            alSourcei(source, AL_SOURCE_RELATIVE, AL_TRUE);
            alSource3f(source, AL_POSITION, 0.0f, 0.0f, 0.0f);

            alSourcePlay(source);

            int error = alGetError();
            if (error != AL_NO_ERROR) {
                logger.error("OpenAL error playing sound {} at pitch {}: {}", name, pitch, error);
            }
        } else {
            logger.warn("Sound not found: {}", name);
        }
    }

    public void playSoundWithVariation(String name, float volume) {
        Integer[] soundSources = soundBuffer.getSources(name);
        if (soundSources != null) {
            int currentIndex = soundBuffer.getNextSourceIndex(name);
            if (currentIndex == -1) {
                logger.warn("Failed to get source index for sound: {}", name);
                return;
            }

            int source = soundSources[currentIndex];

            // Create slight pitch variation (0.9 to 1.1, so ±10% variation)
            float pitchVariation = 0.9f + (float)(Math.random() * 0.2f);

            float finalVolume = volume * volumeController.getMasterVolume();
            alSourcef(source, AL_GAIN, finalVolume);
            alSourcef(source, AL_PITCH, pitchVariation);

            // For regular (non-3D) sounds, make them relative to the listener
            alSourcei(source, AL_SOURCE_RELATIVE, AL_TRUE);
            alSource3f(source, AL_POSITION, 0.0f, 0.0f, 0.0f); // At listener position

            alSourcePlay(source);

            int error = alGetError();
            if (error != AL_NO_ERROR) {
                logger.error("OpenAL error playing sound {} with variation: {}", name, error);
            }
        } else {
            logger.warn("Sound not found: {}", name);
        }
    }

    /**
     * Plays a sound at a specific 3D position in the world.
     * The sound will have proper 3D audio properties including distance attenuation.
     *
     * @param name The name of the sound to play
     * @param volume The volume level (0.0 to 1.0)
     * @param x The X coordinate in world space
     * @param y The Y coordinate in world space
     * @param z The Z coordinate in world space
     */
    public void playSoundAt3D(String name, float volume, float x, float y, float z) {
        logger.trace("Playing 3D sound '{}' at ({}, {}, {}) with volume {}", name, x, y, z, volume);

        Integer[] soundSources = soundBuffer.getSources(name);
        if (soundSources != null) {
            int currentIndex = soundBuffer.getNextSourceIndex(name);
            if (currentIndex == -1) {
                logger.warn("Failed to get source index for sound: {}", name);
                return;
            }

            int source = soundSources[currentIndex];

            // Sources are already configured for 3D audio in SoundBuffer creation
            // We only need to set position and ensure proper 3D mode

            // Ensure the source is set to positional (not relative to listener)
            alSourcei(source, AL_SOURCE_RELATIVE, AL_FALSE);

            // Set the source position in 3D space
            alSource3f(source, AL_POSITION, x, y, z);

            // Set velocity to zero (static sound emitter)
            alSource3f(source, AL_VELOCITY, 0.0f, 0.0f, 0.0f);

            // For 3D audio, use the master volume and let OpenAL handle distance attenuation
            float baseVolume = volume * volumeController.getMasterVolume();
            alSourcef(source, AL_GAIN, baseVolume);

            alSourcePlay(source);
            logger.trace("Playing 3D sound on source {} at volume {}", source, baseVolume);

            int error = alGetError();
            if (error != AL_NO_ERROR) {
                logger.error("OpenAL error playing 3D sound {} at ({}, {}, {}): {}", name, x, y, z, error);
            }
        } else {
            logger.warn("Sound not found: {}", name);
        }
    }

    /**
     * Plays a sound at a specific 3D position at an explicit pitch. Used by
     * data-driven sound defs that author their own pitch range.
     */
    public void playSoundAt3D(String name, float volume, float pitch, float x, float y, float z) {
        Integer[] soundSources = soundBuffer.getSources(name);
        if (soundSources != null) {
            int currentIndex = soundBuffer.getNextSourceIndex(name);
            if (currentIndex == -1) {
                logger.warn("Failed to get source index for sound: {}", name);
                return;
            }

            int source = soundSources[currentIndex];

            alSourcei(source, AL_SOURCE_RELATIVE, AL_FALSE);
            alSource3f(source, AL_POSITION, x, y, z);
            alSource3f(source, AL_VELOCITY, 0.0f, 0.0f, 0.0f);
            alSourcef(source, AL_PITCH, pitch);

            float baseVolume = volume * volumeController.getMasterVolume();
            alSourcef(source, AL_GAIN, baseVolume);

            alSourcePlay(source);

            int error = alGetError();
            if (error != AL_NO_ERROR) {
                logger.error("OpenAL error playing 3D sound {} at pitch {} at ({}, {}, {}): {}",
                        name, pitch, x, y, z, error);
            }
        } else {
            logger.warn("Sound not found: {}", name);
        }
    }

    /**
     * Plays a sound at a specific 3D position with pitch variation.
     *
     * @param name The name of the sound to play
     * @param volume The volume level (0.0 to 1.0)
     * @param x The X coordinate in world space
     * @param y The Y coordinate in world space
     * @param z The Z coordinate in world space
     */
    public void playSoundAt3DWithVariation(String name, float volume, float x, float y, float z) {
        Integer[] soundSources = soundBuffer.getSources(name);
        if (soundSources != null) {
            int currentIndex = soundBuffer.getNextSourceIndex(name);
            if (currentIndex == -1) {
                logger.warn("Failed to get source index for sound: {}", name);
                return;
            }

            int source = soundSources[currentIndex];

            // Create slight pitch variation (0.9 to 1.1, so ±10% variation)
            float pitchVariation = 0.9f + (float)(Math.random() * 0.2f);

            // Sources are already configured for 3D audio in SoundBuffer creation
            // We only need to set position and ensure proper 3D mode

            // Ensure the source is set to positional (not relative to listener)
            alSourcei(source, AL_SOURCE_RELATIVE, AL_FALSE);

            // Set the source position in 3D space
            alSource3f(source, AL_POSITION, x, y, z);

            // Set velocity to zero (static sound emitter)
            alSource3f(source, AL_VELOCITY, 0.0f, 0.0f, 0.0f);

            // Set pitch variation
            alSourcef(source, AL_PITCH, pitchVariation);

            // For 3D audio, use the master volume and let OpenAL handle distance attenuation
            float baseVolume = volume * volumeController.getMasterVolume();
            alSourcef(source, AL_GAIN, baseVolume);

            alSourcePlay(source);

            int error = alGetError();
            if (error != AL_NO_ERROR) {
                logger.error("OpenAL error playing 3D sound {} with variation at ({}, {}, {}): {}", name, x, y, z, error);
            }
        } else {
            logger.warn("Sound not found: {}", name);
        }
    }
}