package com.openmason.engine.audio;

import static org.lwjgl.openal.AL10.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class SoundBuffer {
    private static final Logger logger = LoggerFactory.getLogger(SoundBuffer.class);

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
        // Re-registering a name replaces it: free the previous sources and buffer
        // first, or nothing would reference them and they would outlive cleanup().
        releaseSound(name, bufferPointer);
        soundBuffers.put(name, bufferPointer);

        Integer[] soundSources = new Integer[sourcesPerSound];

        // Clear a stale error left by an unrelated earlier AL call; the per-source check below
        // would otherwise blame it on this sound and abort a valid registration.
        alGetError();

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
                logger.error("OpenAL error creating source {} for {}: {}", i, name, error);
                // Clean up any sources created so far, including this one
                alDeleteSources(sourcePointer);
                for (int j = 0; j < i; j++) {
                    alDeleteSources(soundSources[j]);
                }
                return false;
            }

            soundSources[i] = sourcePointer;
        }

        sources.put(name, soundSources);
        sourceIndexes.put(name, 0);

        logger.debug("Successfully created {} sources for sound: {}", sourcesPerSound, name);
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

    /**
     * Delete a registered sound's sources, then its buffer unless it is
     * {@code keepBuffer} (re-adding the same buffer under its own name). Sources
     * go first: OpenAL refuses to delete a buffer a source still has attached.
     */
    private void releaseSound(String name, int keepBuffer) {
        Integer[] oldSources = sources.remove(name);
        if (oldSources != null) {
            for (Integer source : oldSources) {
                if (source != null) {
                    alSourceStop(source);
                    alDeleteSources(source);
                }
            }
        }
        sourceIndexes.remove(name);
        Integer oldBuffer = soundBuffers.remove(name);
        if (oldBuffer != null && oldBuffer != keepBuffer) {
            alDeleteBuffers(oldBuffer);
        }
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