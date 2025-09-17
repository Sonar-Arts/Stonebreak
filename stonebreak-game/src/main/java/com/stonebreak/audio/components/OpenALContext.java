package com.stonebreak.audio.components;

import org.lwjgl.openal.*;
import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;

public class OpenALContext {
    private long device;
    private long context;
    private boolean initialized = false;

    public boolean initialize() {
        try {
            String defaultDeviceName = alcGetString(0, ALC_DEFAULT_DEVICE_SPECIFIER);
            System.out.println("Initializing OpenAL with device: " + defaultDeviceName);

            device = alcOpenDevice(defaultDeviceName);
            if (device == 0) {
                System.err.println("Failed to open OpenAL device");
                return false;
            }

            int[] attributes = {0};
            context = alcCreateContext(device, attributes);
            if (context == 0) {
                System.err.println("Failed to create OpenAL context");
                alcCloseDevice(device);
                return false;
            }

            if (!alcMakeContextCurrent(context)) {
                System.err.println("Failed to make OpenAL context current");
                alcDestroyContext(context);
                alcCloseDevice(device);
                return false;
            }

            AL.createCapabilities(ALC.createCapabilities(device));

            alListener3f(AL_POSITION, 0, 0, 1.0f);
            alListener3f(AL_VELOCITY, 0, 0, 0);
            alListener3f(AL_ORIENTATION, 0, 0, 1);

            initialized = true;
            System.out.println("OpenAL initialized successfully");
            return true;
        } catch (Exception e) {
            System.err.println("Failed to initialize OpenAL: " + e.getMessage());
            System.err.println("Stack trace: " + e.toString());
            return false;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public long getDevice() {
        return device;
    }

    public long getContext() {
        return context;
    }

    public void cleanup() {
        if (initialized) {
            alcDestroyContext(context);
            alcCloseDevice(device);
            initialized = false;
        }
    }
}