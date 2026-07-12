package com.openmason.engine.audio;

import static org.lwjgl.openal.AL10.*;

import java.util.Map;

public class AudioDiagnostics {
    private final OpenALContext context;
    private final SoundBuffer soundBuffer;

    public AudioDiagnostics(OpenALContext context, SoundBuffer soundBuffer) {
        this.context = context;
        this.soundBuffer = soundBuffer;
    }

    public void testBasicFunctionality() {
        System.out.println("=== SoundSystem Test ===");
        System.out.println("Device: " + context.getDevice());
        System.out.println("Context: " + context.getContext());

        Map<String, Integer> buffers = soundBuffer.getSoundBuffers();
        Map<String, Integer[]> sources = soundBuffer.getAllSources();

        System.out.println("Loaded sound buffers: " + buffers.keySet());
        System.out.println("Available sources: " + sources.keySet());
        System.out.println("soundBuffers map size: " + buffers.size());
        System.out.println("sources map size: " + sources.size());

        // Test if OpenAL is working
        int error = alGetError();
        if (error != AL_NO_ERROR) {
            System.err.println("OpenAL error in test: " + error);
        } else {
            System.out.println("OpenAL is functioning correctly");
        }

        // Test if the eagerly-loaded pickup sound is present (block/entity
        // sounds are data-driven and load lazily, so they can't be probed here)
        if (soundBuffer.isSoundLoaded("blockpickup")) {
            System.out.println("✓ Blockpickup sound is properly loaded");
        } else {
            System.err.println("✗ Blockpickup sound failed to load!");
            System.err.println("soundBuffers contains 'blockpickup': " + buffers.containsKey("blockpickup"));
            System.err.println("sources contains 'blockpickup': " + sources.containsKey("blockpickup"));

            // Show what we actually have
            if (!buffers.isEmpty()) {
                System.err.println("Available sound buffers:");
                for (String key : buffers.keySet()) {
                    System.err.println("  - '" + key + "'");
                }
            }
            if (!sources.isEmpty()) {
                System.err.println("Available sources:");
                for (String key : sources.keySet()) {
                    System.err.println("  - '" + key + "'");
                }
            }
        }
        System.out.println("========================");
    }
}