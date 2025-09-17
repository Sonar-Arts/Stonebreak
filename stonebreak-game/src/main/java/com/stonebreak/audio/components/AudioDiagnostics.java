package com.stonebreak.audio.components;

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

        // Test if grasswalk sound is loaded
        if (soundBuffer.isSoundLoaded("grasswalk")) {
            System.out.println("✓ Grasswalk sound is properly loaded");
            System.out.println("Attempting to play grasswalk sound...");
        } else {
            System.err.println("✗ Grasswalk sound failed to load!");
            System.err.println("soundBuffers contains 'grasswalk': " + buffers.containsKey("grasswalk"));
            System.err.println("sources contains 'grasswalk': " + sources.containsKey("grasswalk"));

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