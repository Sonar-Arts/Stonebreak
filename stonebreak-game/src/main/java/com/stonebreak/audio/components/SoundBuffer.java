package com.stonebreak.audio.components;

import static org.lwjgl.openal.AL10.*;

import java.util.HashMap;
import java.util.Map;

public class SoundBuffer {
    private final Map<String, Integer> soundBuffers;
    private final Map<String, Integer[]> sources;
    private final Map<String, Integer> sourceIndexes;
    private final int sourcesPerSound;

    public SoundBuffer(int sourcesPerSound) {
        this.soundBuffers = new HashMap<>();
        this.sources = new HashMap<>();
        this.sourceIndexes = new HashMap<>();
        this.sourcesPerSound = sourcesPerSound;
    }

    public boolean addSound(String name, int bufferPointer) {
        soundBuffers.put(name, bufferPointer);

        Integer[] soundSources = new Integer[sourcesPerSound];

        for (int i = 0; i < sourcesPerSound; i++) {
            int sourcePointer = alGenSources();
            alSourcei(sourcePointer, AL_BUFFER, bufferPointer);

            // Configure for 3D positional audio
            alSourcef(sourcePointer, AL_GAIN, 0.5f);
            alSourcef(sourcePointer, AL_PITCH, 1.0f);

            // Set initial position (will be overridden by 3D play methods)
            alSource3f(sourcePointer, AL_POSITION, 0.0f, 0.0f, 0.0f);
            alSource3f(sourcePointer, AL_VELOCITY, 0.0f, 0.0f, 0.0f);

            // Configure 3D audio distance parameters
            alSourcef(sourcePointer, AL_REFERENCE_DISTANCE, 1.0f);    // Distance where volume is at full
            alSourcef(sourcePointer, AL_MAX_DISTANCE, 50.0f);         // Maximum distance before volume reaches minimum
            alSourcef(sourcePointer, AL_ROLLOFF_FACTOR, 1.0f);        // How quickly volume decreases with distance

            // Ensure source is positional (not relative to listener)
            alSourcei(sourcePointer, AL_SOURCE_RELATIVE, AL_FALSE);

            // Set to non-looping by default
            alSourcei(sourcePointer, AL_LOOPING, AL_FALSE);

            int error = alGetError();
            if (error != AL_NO_ERROR) {
                System.err.println("OpenAL error creating source " + i + " for " + name + ": " + error);
                // Clean up any sources created so far
                for (int j = 0; j < i; j++) {
                    alDeleteSources(soundSources[j]);
                }
                return false;
            }

            soundSources[i] = sourcePointer;
        }

        sources.put(name, soundSources);
        sourceIndexes.put(name, 0);

        System.out.println("Successfully created " + sourcesPerSound + " sources for sound: " + name);
        return true;
    }

    public Integer[] getSources(String name) {
        return sources.get(name);
    }

    public int getNextSourceIndex(String name) {
        Integer currentIndex = sourceIndexes.get(name);
        if (currentIndex == null) {
            return -1;
        }

        int nextIndex = (currentIndex + 1) % sourcesPerSound;
        sourceIndexes.put(name, nextIndex);
        return currentIndex;
    }

    public boolean isSoundLoaded(String name) {
        return soundBuffers.containsKey(name) && sources.containsKey(name) && sources.get(name) != null;
    }

    public Map<String, Integer> getSoundBuffers() {
        return new HashMap<>(soundBuffers);
    }

    public Map<String, Integer[]> getAllSources() {
        return new HashMap<>(sources);
    }

    public void cleanup() {
        for (Integer[] soundSources : sources.values()) {
            if (soundSources != null) {
                for (int source : soundSources) {
                    alDeleteSources(source);
                }
            }
        }
        for (int buffer : soundBuffers.values()) {
            alDeleteBuffers(buffer);
        }

        soundBuffers.clear();
        sources.clear();
        sourceIndexes.clear();
    }
}