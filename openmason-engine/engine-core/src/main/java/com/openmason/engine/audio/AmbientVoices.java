package com.openmason.engine.audio;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET;

/**
 * Long one-shot 2D ambience (thunder): a small pool of listener-relative
 * sources shared by every such sound. A new sound takes a free voice; when all
 * are busy it replaces the voice with the least loudness left (gain × the
 * share of its clip still to play), and only if the new sound is louder than
 * that. A loud clap is never cut off by a fainter one. The per-sound sources
 * of {@link SoundBuffer} would restart a still-rumbling clip instead.
 */
class AmbientVoices {

    private final int[] sources;
    private final float[] gains;
    private boolean created;

    AmbientVoices(int voices) {
        this.sources = new int[voices];
        this.gains = new float[voices];
    }

    /**
     * @param buffer the loaded sound's buffer, or null when not loaded (no-op)
     * @param gain   final gain, master volume already applied
     * @return whether the sound got a voice
     */
    boolean play(Integer buffer, float gain, float pitch) {
        if (buffer == null || gain <= 0f) return false;
        if (!created) create();
        int voice = -1;
        float quietest = gain;
        for (int i = 0; i < sources.length; i++) {
            if (alGetSourcei(sources[i], AL_SOURCE_STATE) != AL_PLAYING) {
                voice = i;
                break;
            }
            float left = loudnessLeft(i);
            if (left < quietest) {
                quietest = left;
                voice = i;
            }
        }
        if (voice < 0) return false;
        int src = sources[voice];
        alSourceStop(src);
        alSourcei(src, AL_BUFFER, buffer);
        alSourcef(src, AL_GAIN, gain);
        alSourcef(src, AL_PITCH, pitch);
        alSourcePlay(src);
        gains[voice] = gain;
        return true;
    }

    private float loudnessLeft(int voice) {
        int src = sources[voice];
        int buffer = alGetSourcei(src, AL_BUFFER);
        int bytesPerSample = Math.max(1, alGetBufferi(buffer, AL_BITS) / 8 * alGetBufferi(buffer, AL_CHANNELS));
        int samples = Math.max(1, alGetBufferi(buffer, AL_SIZE) / bytesPerSample);
        float played = alGetSourcei(src, AL_SAMPLE_OFFSET) / (float) samples;
        return gains[voice] * Math.max(0f, 1f - played);
    }

    private void create() {
        for (int i = 0; i < sources.length; i++) {
            int src = alGenSources();
            alSourcei(src, AL_SOURCE_RELATIVE, AL_TRUE);
            alSource3f(src, AL_POSITION, 0f, 0f, 0f);
            sources[i] = src;
        }
        created = true;
    }

    void cleanup() {
        if (!created) return;
        for (int src : sources) {
            alSourceStop(src);
            alDeleteSources(src);
        }
        created = false;
    }
}
