package com.stonebreak.audio.components;

import static org.lwjgl.openal.AL10.*;

public class SoundPlayer {
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
                System.err.println("Failed to get source index for sound: " + name);
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
                System.err.println("OpenAL error playing sound " + name + " with volume " + volume + ": " + error);
            }
        } else {
            System.err.println("Sound not found: " + name);
        }
    }

    public void playSoundWithVariation(String name, float volume) {
        Integer[] soundSources = soundBuffer.getSources(name);
        if (soundSources != null) {
            int currentIndex = soundBuffer.getNextSourceIndex(name);
            if (currentIndex == -1) {
                System.err.println("Failed to get source index for sound: " + name);
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
                System.err.println("OpenAL error playing sound " + name + " with variation: " + error);
            }
        } else {
            System.err.println("Sound not found: " + name);
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
        System.out.println("🔊 3D AUDIO DEBUG: Playing '" + name + "' at position (" + x + ", " + y + ", " + z + ") with volume " + volume);

        Integer[] soundSources = soundBuffer.getSources(name);
        if (soundSources != null) {
            int currentIndex = soundBuffer.getNextSourceIndex(name);
            if (currentIndex == -1) {
                System.err.println("Failed to get source index for sound: " + name);
                return;
            }

            int source = soundSources[currentIndex];

            // Sources are already configured for 3D audio in SoundBuffer creation
            // We only need to set position and ensure proper 3D mode

            // Ensure the source is set to positional (not relative to listener)
            alSourcei(source, AL_SOURCE_RELATIVE, AL_FALSE);
            System.out.println("🔊 3D AUDIO DEBUG: Set source " + source + " to world-space positioning (not relative)");

            // Set the source position in 3D space
            alSource3f(source, AL_POSITION, x, y, z);
            System.out.println("🔊 3D AUDIO DEBUG: Set source " + source + " position to (" + x + ", " + y + ", " + z + ")");

            // Set velocity to zero (static sound emitter)
            alSource3f(source, AL_VELOCITY, 0.0f, 0.0f, 0.0f);

            // For 3D audio, use the master volume and let OpenAL handle distance attenuation
            float baseVolume = volume * volumeController.getMasterVolume();
            alSourcef(source, AL_GAIN, baseVolume);
            System.out.println("🔊 3D AUDIO DEBUG: Set source " + source + " volume to " + baseVolume + " (distance attenuation will be applied by OpenAL)");

            alSourcePlay(source);
            System.out.println("🔊 3D AUDIO DEBUG: Started playing source " + source);

            int error = alGetError();
            if (error != AL_NO_ERROR) {
                System.err.println("OpenAL error playing 3D sound " + name + " at (" + x + ", " + y + ", " + z + "): " + error);
            } else {
                System.out.println("🔊 3D AUDIO DEBUG: Successfully playing 3D sound with no OpenAL errors");
            }
        } else {
            System.err.println("Sound not found: " + name);
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
                System.err.println("Failed to get source index for sound: " + name);
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
                System.err.println("OpenAL error playing 3D sound " + name + " with variation at (" + x + ", " + y + ", " + z + "): " + error);
            }
        } else {
            System.err.println("Sound not found: " + name);
        }
    }
}