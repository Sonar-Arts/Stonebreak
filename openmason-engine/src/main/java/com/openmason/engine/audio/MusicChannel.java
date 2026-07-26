package com.openmason.engine.audio;

import static org.lwjgl.openal.AL10.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Dedicated single-source OpenAL channel for background music. Unlike
 * {@link SoundBuffer}/{@link SoundPlayer} (a pool of sources per sound, forced mono, no looping —
 * tuned for overlapping 3D SFX), music needs exactly one continuously-playing 2D source that can
 * switch between tracks and report whether it has finished so a caller-driven playlist can advance.
 */
public class MusicChannel {
    private static final Logger logger = LoggerFactory.getLogger(MusicChannel.class);

    private final Map<String, Integer> trackBuffers = new HashMap<>();
    private int source = -1;

    public void addTrack(String name, int bufferPointer) {
        trackBuffers.put(name, bufferPointer);
    }

    public boolean isTrackLoaded(String name) {
        return trackBuffers.containsKey(name);
    }

    /** Stops whatever is currently playing (if anything) and plays {@code name} from the start. */
    public void play(String name, float gain) {
        Integer buffer = trackBuffers.get(name);
        if (buffer == null) {
            logger.warn("Music track not found: {}", name);
            return;
        }

        int src = ensureSource();
        // AL_BUFFER can only be (re)bound while the source is stopped/initial.
        alSourceStop(src);
        alSourcei(src, AL_BUFFER, buffer);
        alSourcef(src, AL_GAIN, gain);
        alSourcePlay(src);

        int error = alGetError();
        if (error != AL_NO_ERROR) {
            logger.error("OpenAL error playing music track {}: {}", name, error);
        }
    }

    public void stop() {
        if (source != -1) alSourceStop(source);
    }

    /** Applies live, unlike SFX gain which is baked in at play-call time. */
    public void setGain(float gain) {
        if (source != -1) alSourcef(source, AL_GAIN, gain);
    }

    /** False once the current track reaches its end — callers poll this to advance a playlist. */
    public boolean isPlaying() {
        return source != -1 && alGetSourcei(source, AL_SOURCE_STATE) == AL_PLAYING;
    }

    private int ensureSource() {
        if (source == -1) {
            source = alGenSources();
            alSourcei(source, AL_SOURCE_RELATIVE, AL_TRUE);
            alSource3f(source, AL_POSITION, 0.0f, 0.0f, 0.0f);
            alSourcei(source, AL_LOOPING, AL_FALSE);
        }
        return source;
    }

    public void cleanup() {
        if (source != -1) {
            alDeleteSources(source);
            source = -1;
        }
        for (int buffer : trackBuffers.values()) {
            alDeleteBuffers(buffer);
        }
        trackBuffers.clear();
    }
}
