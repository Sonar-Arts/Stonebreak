package com.openmason.engine.audio;

import static org.lwjgl.openal.AL10.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Looping 2D ambience (rain, wind): one listener-relative looping source per
 * sound, driven by a gain the caller updates every frame. A loop that has
 * stayed silent for {@link #PAUSE_AFTER_SILENT_NANOS} is paused so it costs
 * nothing; brief dips to zero keep it running, so it never restarts with a pop.
 * Unlike {@link SoundBuffer} sources, these loop and their gain applies live.
 */
class LoopChannel {

    private static final long PAUSE_AFTER_SILENT_NANOS = 3_000_000_000L;

    private final Map<String, Integer> sources = new HashMap<>();
    /** When each loop went silent; absent while it is audible. */
    private final Map<String, Long> silentSince = new HashMap<>();

    /**
     * @param buffer    the loaded sound's buffer, or null when not loaded (no-op)
     * @param gain      final gain, master volume already applied
     */
    void setGain(String name, Integer buffer, float gain) {
        Integer src = sources.get(name);
        if (gain <= 0f) {
            if (src == null || alGetSourcei(src, AL_SOURCE_STATE) != AL_PLAYING) return;
            alSourcef(src, AL_GAIN, 0f);
            long now = System.nanoTime();
            long since = silentSince.computeIfAbsent(name, n -> now);
            if (now - since >= PAUSE_AFTER_SILENT_NANOS) alSourcePause(src);
            return;
        }
        silentSince.remove(name);
        if (src == null) {
            if (buffer == null) return;
            src = alGenSources();
            alSourcei(src, AL_SOURCE_RELATIVE, AL_TRUE);
            alSource3f(src, AL_POSITION, 0f, 0f, 0f);
            alSourcei(src, AL_LOOPING, AL_TRUE);
            alSourcei(src, AL_BUFFER, buffer);
            sources.put(name, src);
        }
        alSourcef(src, AL_GAIN, gain);
        if (alGetSourcei(src, AL_SOURCE_STATE) != AL_PLAYING) {
            alSourcePlay(src);
        }
    }

    void cleanup() {
        for (int src : sources.values()) {
            alSourceStop(src);
            alDeleteSources(src);
        }
        sources.clear();
        silentSince.clear();
    }
}
