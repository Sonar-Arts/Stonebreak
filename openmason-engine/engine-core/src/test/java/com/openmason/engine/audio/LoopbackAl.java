package com.openmason.engine.audio;

import org.junit.jupiter.api.Assumptions;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.openal.ALC10.alcCloseDevice;
import static org.lwjgl.openal.ALC10.alcCreateContext;
import static org.lwjgl.openal.ALC10.alcDestroyContext;
import static org.lwjgl.openal.ALC10.alcMakeContextCurrent;
import static org.lwjgl.openal.ALC10.ALC_FREQUENCY;
import static org.lwjgl.openal.SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT;
import static org.lwjgl.openal.SOFTLoopback.ALC_FORMAT_TYPE_SOFT;
import static org.lwjgl.openal.SOFTLoopback.ALC_SHORT_SOFT;
import static org.lwjgl.openal.SOFTLoopback.ALC_STEREO_SOFT;
import static org.lwjgl.openal.SOFTLoopback.alcLoopbackOpenDeviceSOFT;
import static org.lwjgl.openal.SOFTLoopback.alcRenderSamplesSOFT;

/**
 * A real OpenAL Soft context on an ALC_SOFT_loopback device: no sound card, no audio output —
 * the mixer only advances when {@link #render(int)} pulls samples. Lets the OpenAL-backed audio
 * classes run for real on a headless CI box.
 *
 * <p>Trap: calling any {@code AL10} function before the FIRST {@code AL.createCapabilities} in a
 * JVM permanently breaks LWJGL's static AL dispatch for that JVM (ExceptionInInitializerError,
 * then NoClassDefFoundError). Tests that must not have a context therefore must never reach AL.
 */
final class LoopbackAl implements AutoCloseable {

    static final int RATE = 44100;

    final long device;
    final long context;

    private LoopbackAl(long device, long context) {
        this.device = device;
        this.context = context;
    }

    /** Opens a loopback device and makes its context current; skips the test if OpenAL Soft can't load. */
    static LoopbackAl open() {
        long device;
        try {
            device = alcLoopbackOpenDeviceSOFT((ByteBuffer) null);
        } catch (UnsatisfiedLinkError | ExceptionInInitializerError | NoClassDefFoundError e) {
            Assumptions.abort("OpenAL Soft native library unavailable: " + e);
            throw new AssertionError(e); // unreachable
        }
        if (device == 0) {
            Assumptions.abort("OpenAL Soft has no ALC_SOFT_loopback device");
        }
        ALCCapabilities caps = ALC.createCapabilities(device);
        int[] attrs = {
                ALC_FORMAT_CHANNELS_SOFT, ALC_STEREO_SOFT,
                ALC_FORMAT_TYPE_SOFT, ALC_SHORT_SOFT,
                ALC_FREQUENCY, RATE,
                0
        };
        long context = alcCreateContext(device, attrs);
        if (context == 0 || !alcMakeContextCurrent(context)) {
            alcCloseDevice(device);
            throw new IllegalStateException("could not create a loopback OpenAL context");
        }
        AL.createCapabilities(caps);
        return new LoopbackAl(device, context);
    }

    /** Mixes {@code frames} output frames, advancing every playing source by that much time. */
    void render(int frames) {
        ShortBuffer out = BufferUtils.createShortBuffer(frames * 2);
        alcRenderSamplesSOFT(device, out, frames);
    }

    @Override
    public void close() {
        alcMakeContextCurrent(0);
        alcDestroyContext(context);
        alcCloseDevice(device);
    }

    /** An {@link OpenALContext} whose initialize() opens a loopback device instead of the default one. */
    static final class Context extends OpenALContext {
        private LoopbackAl al;

        @Override
        public boolean initialize() {
            al = open();
            return true;
        }

        @Override
        public boolean isInitialized() {
            return al != null;
        }

        @Override
        public long getDevice() {
            return al == null ? 0 : al.device;
        }

        @Override
        public long getContext() {
            return al == null ? 0 : al.context;
        }

        LoopbackAl al() {
            return al;
        }

        @Override
        public void cleanup() {
            if (al != null) {
                al.close();
                al = null;
            }
        }
    }
}
