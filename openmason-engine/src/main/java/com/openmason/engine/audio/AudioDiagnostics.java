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

        // Sounds are data-driven and mostly load lazily, so only report what is resident
        if (buffers.isEmpty()) {
            System.out.println("No sounds loaded yet");
        } else {
            System.out.println("Loaded sounds (" + buffers.size() + "):");
            for (String key : buffers.keySet()) {
                System.out.println("  - '" + key + "' sources=" + (sources.containsKey(key) ? "yes" : "MISSING"));
            }
        }
        System.out.println("========================");
    }
}