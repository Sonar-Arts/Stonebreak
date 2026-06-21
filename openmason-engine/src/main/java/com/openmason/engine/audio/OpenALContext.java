package com.openmason.engine.audio;

import org.lwjgl.openal.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;

public class OpenALContext {
    private static final Logger logger = LoggerFactory.getLogger(OpenALContext.class);

    private long device;
    private long context;
    private boolean initialized = false;

    public boolean initialize() {
        try {
            String defaultDeviceName = alcGetString(0, ALC_DEFAULT_DEVICE_SPECIFIER);
            logger.debug("Initializing OpenAL with device: {}", defaultDeviceName);

            device = alcOpenDevice(defaultDeviceName);
            if (device == 0) {
                logger.error("Failed to open OpenAL device");
                return false;
            }

            int[] attributes = {0};
            context = alcCreateContext(device, attributes);
            if (context == 0) {
                logger.error("Failed to create OpenAL context");
                alcCloseDevice(device);
                return false;
            }

            if (!alcMakeContextCurrent(context)) {
                logger.error("Failed to make OpenAL context current");
                alcDestroyContext(context);
                alcCloseDevice(device);
                return false;
            }

            AL.createCapabilities(ALC.createCapabilities(device));

            // Set up 3D audio distance model for proper spatial audio
            alDistanceModel(AL_INVERSE_DISTANCE_CLAMPED);
            if (logger.isDebugEnabled()) {
                int currentModel = alGetInteger(AL_DISTANCE_MODEL);
                logger.debug("Distance model set to AL_INVERSE_DISTANCE_CLAMPED (current={}, expected={})",
                        currentModel, AL_INVERSE_DISTANCE_CLAMPED);
            }

            // Note: Initial listener setup will be handled by AudioListener.initializeDefaultListener()
            // We don't set listener properties here to avoid conflicts

            initialized = true;
            logger.debug("OpenAL initialized successfully");
            return true;
        } catch (Exception e) {
            logger.error("Failed to initialize OpenAL", e);
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