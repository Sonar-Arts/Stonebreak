package com.stonebreak.audio.components;

import org.lwjgl.BufferUtils;
import static org.lwjgl.openal.AL10.*;

import javax.sound.sampled.*;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;

public class AudioLoader {

    public static class LoadResult {
        public final int buffer;
        public final boolean success;
        public final String errorMessage;

        private LoadResult(int buffer, boolean success, String errorMessage) {
            this.buffer = buffer;
            this.success = success;
            this.errorMessage = errorMessage;
        }

        public static LoadResult success(int buffer) {
            return new LoadResult(buffer, true, null);
        }

        public static LoadResult failure(String errorMessage) {
            return new LoadResult(-1, false, errorMessage);
        }
    }

    public LoadResult loadSound(String name, String resourcePath) {
        System.out.println("=== Loading Sound ====");
        System.out.println("Sound name: " + name);
        System.out.println("Resource path: " + resourcePath);

        logResourceDebugInfo(resourcePath);

        InputStream is = findResourceInputStream(resourcePath);

        try (InputStream finalIs = is) {
            if (finalIs == null) {
                System.err.println("InputStream is NULL for path: " + resourcePath);
                return tryAlternativePaths(name);
            }

            System.out.println("Successfully opened InputStream for: " + resourcePath);
            return loadSoundFromStream(name, finalIs, resourcePath);

        } catch (IOException e) {
            String errorMsg = "IOException loading sound " + resourcePath + ": " + e.getMessage();
            System.err.println(errorMsg);
            System.err.println("Stack trace: " + e.toString());
            return LoadResult.failure(errorMsg);
        } finally {
            System.out.println("=====================");
        }
    }

    private void logResourceDebugInfo(String resourcePath) {
        System.out.println("Class: " + getClass().getName());
        System.out.println("ClassLoader: " + getClass().getClassLoader());

        try {
            java.net.URL resourceUrl = getClass().getResource("/sounds/");
            System.out.println("Sounds directory URL: " + resourceUrl);

            if (resourceUrl != null) {
                java.io.File soundsDir = new java.io.File(resourceUrl.toURI());
                if (soundsDir.exists() && soundsDir.isDirectory()) {
                    System.out.println("Contents of sounds directory:");
                    for (String file : soundsDir.list()) {
                        System.out.println("  - " + file);
                    }
                }
            }
        } catch (URISyntaxException | SecurityException e) {
            System.err.println("Error listing resources: " + e.getMessage());
        }

        try {
            java.net.URL soundUrl = getClass().getResource(resourcePath);
            System.out.println("Direct URL for " + resourcePath + ": " + soundUrl);
        } catch (Exception e) {
            System.err.println("Error getting URL: " + e.getMessage());
        }
    }

    private InputStream findResourceInputStream(String resourcePath) {
        // First try: Module's class loader
        InputStream is = getClass().getClassLoader().getResourceAsStream(
            resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath);

        // Second try: Module class itself
        if (is == null) {
            is = getClass().getResourceAsStream(resourcePath);
        }

        // Third try: Context class loader
        if (is == null) {
            is = Thread.currentThread().getContextClassLoader().getResourceAsStream(
                resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath);
        }

        return is;
    }

    private LoadResult tryAlternativePaths(String name) {
        System.out.println("Trying alternative paths...");
        String[] alternativePaths = {
            "/sounds/GrassWalk.wav",
            "sounds/GrassWalk.wav",
            "/GrassWalk.wav",
            "GrassWalk.wav"
        };

        for (String altPath : alternativePaths) {
            System.out.println("Trying: " + altPath);
            InputStream altIs = findResourceInputStream(altPath);

            try (InputStream finalAltIs = altIs) {
                if (finalAltIs != null) {
                    System.out.println("Found sound at: " + altPath);
                    return loadSoundFromStream(name, finalAltIs, altPath);
                }
            } catch (Exception e) {
                System.err.println("Error trying " + altPath + ": " + e.getMessage());
            }
        }

        return LoadResult.failure("Could not find sound file at any path!");
    }

    private LoadResult loadSoundFromStream(String name, InputStream is, String path) {
        try {
            System.out.println("Loading sound: " + name + " from " + path);

            try (BufferedInputStream bis = new BufferedInputStream(is);
                 AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(bis)) {
                AudioFormat format = audioInputStream.getFormat();

                System.out.println("Original format: " + format);

                AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    format.getSampleRate(),
                    16,
                    format.getChannels(),
                    format.getChannels() * 2,
                    format.getSampleRate(),
                    false
                );

                try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(targetFormat, audioInputStream)) {
                    byte[] audioData = pcmStream.readAllBytes();
                    ByteBuffer audioBuffer = BufferUtils.createByteBuffer(audioData.length);
                    audioBuffer.put(audioData);
                    audioBuffer.flip();

                    int channels = targetFormat.getChannels();
                    int sampleRate = (int) targetFormat.getSampleRate();

                    System.out.println("Converted format: " + channels + " channels, " + sampleRate + " Hz, " + audioData.length + " bytes");

                    int alFormat = switch (channels) {
                        case 1 -> AL_FORMAT_MONO16;
                        case 2 -> AL_FORMAT_STEREO16;
                        default -> {
                            System.err.println("Unsupported channel count: " + channels);
                            yield -1;
                        }
                    };

                    if (alFormat == -1) {
                        return LoadResult.failure("Unsupported channel count: " + channels);
                    }

                    int bufferPointer = alGenBuffers();
                    alBufferData(bufferPointer, alFormat, audioBuffer, sampleRate);

                    int error = alGetError();
                    if (error != AL_NO_ERROR) {
                        String errorMsg = "OpenAL error loading buffer: " + error;
                        System.err.println(errorMsg);
                        return LoadResult.failure(errorMsg);
                    }

                    System.out.println("Successfully loaded sound: " + name + " (" + channels + " channels, " + sampleRate + " Hz)");
                    return LoadResult.success(bufferPointer);
                }

            } catch (UnsupportedAudioFileException e) {
                String errorMsg = "Unsupported audio file format: " + path;
                System.err.println(errorMsg);
                System.err.println("Stack trace: " + e.toString());
                return LoadResult.failure(errorMsg);
            }

        } catch (IOException e) {
            String errorMsg = "Error loading sound " + path + ": " + e.getMessage();
            System.err.println(errorMsg);
            System.err.println("Stack trace: " + e.toString());
            return LoadResult.failure(errorMsg);
        }
    }
}