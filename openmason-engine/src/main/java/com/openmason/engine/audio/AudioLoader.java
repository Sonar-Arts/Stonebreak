package com.openmason.engine.audio;

import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.openal.AL10.*;

import javax.sound.sampled.*;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;

public class AudioLoader {

    private static final Logger logger = LoggerFactory.getLogger(AudioLoader.class);

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
        logger.debug("Loading sound '{}' from resource path: {}", name, resourcePath);

        if (logger.isTraceEnabled()) {
            logResourceDebugInfo(resourcePath);
        }

        InputStream is = findResourceInputStream(resourcePath);

        try (InputStream finalIs = is) {
            if (finalIs == null) {
                logger.warn("InputStream is NULL for path: {} — trying alternatives", resourcePath);
                return tryAlternativePaths(name);
            }

            return loadSoundFromStream(name, finalIs, resourcePath);

        } catch (IOException e) {
            String errorMsg = "IOException loading sound " + resourcePath + ": " + e.getMessage();
            logger.error(errorMsg, e);
            return LoadResult.failure(errorMsg);
        }
    }

    /**
     * Loads a sound from a caller-supplied {@link InputStream}. Preferred entry point when the
     * audio resource lives in a different module than this engine class: the consumer resolves
     * the resource against its own module/classloader and hands the bytes here for decoding.
     * The stream is always closed by this method.
     *
     * @param name  logical sound name (used for logging/diagnostics)
     * @param is    the audio data stream, or {@code null}
     * @return the load result; failure if {@code is} is {@code null} or decoding fails
     */
    public LoadResult loadSound(String name, InputStream is) {
        if (is == null) {
            String errorMsg = "InputStream is NULL for sound: " + name;
            logger.error(errorMsg);
            return LoadResult.failure(errorMsg);
        }
        try (InputStream finalIs = is) {
            return loadSoundFromStream(name, finalIs, name);
        } catch (IOException e) {
            String errorMsg = "IOException loading sound " + name + ": " + e.getMessage();
            logger.error(errorMsg, e);
            return LoadResult.failure(errorMsg);
        }
    }

    /**
     * Loads a music track from a caller-supplied {@link InputStream}, preserving stereo (up to 2
     * channels) rather than forcing mono like {@link #loadSound(String, InputStream)} does for 3D
     * positional SFX. Intended for use with {@link SoundSystem#loadMusic(String, InputStream)}.
     * The stream is always closed by this method.
     *
     * @param name logical track name (used for logging/diagnostics)
     * @param is   the audio data stream, or {@code null}
     * @return the load result; failure if {@code is} is {@code null} or decoding fails
     */
    public LoadResult loadMusic(String name, InputStream is) {
        if (is == null) {
            String errorMsg = "InputStream is NULL for music: " + name;
            logger.error(errorMsg);
            return LoadResult.failure(errorMsg);
        }
        try (InputStream finalIs = is) {
            return decodeToBuffer(name, finalIs, name, false);
        } catch (IOException e) {
            String errorMsg = "IOException loading music " + name + ": " + e.getMessage();
            logger.error(errorMsg, e);
            return LoadResult.failure(errorMsg);
        }
    }

    private void logResourceDebugInfo(String resourcePath) {
        logger.trace("Class: {}", getClass().getName());
        logger.trace("ClassLoader: {}", getClass().getClassLoader());

        try {
            java.net.URL resourceUrl = getClass().getResource("/sounds/");
            logger.trace("Sounds directory URL: {}", resourceUrl);

            if (resourceUrl != null) {
                java.io.File soundsDir = new java.io.File(resourceUrl.toURI());
                if (soundsDir.exists() && soundsDir.isDirectory()) {
                    logger.trace("Contents of sounds directory: {}",
                            java.util.Arrays.toString(soundsDir.list()));
                }
            }
        } catch (URISyntaxException | SecurityException e) {
            logger.trace("Error listing resources: {}", e.getMessage());
        }

        try {
            java.net.URL soundUrl = getClass().getResource(resourcePath);
            logger.trace("Direct URL for {}: {}", resourcePath, soundUrl);
        } catch (Exception e) {
            logger.trace("Error getting URL: {}", e.getMessage());
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
        logger.debug("Trying alternative sound paths for '{}'", name);
        String[] alternativePaths = {
            "/sounds/GrassWalk.wav",
            "sounds/GrassWalk.wav",
            "/GrassWalk.wav",
            "GrassWalk.wav"
        };

        for (String altPath : alternativePaths) {
            logger.trace("Trying: {}", altPath);
            InputStream altIs = findResourceInputStream(altPath);

            try (InputStream finalAltIs = altIs) {
                if (finalAltIs != null) {
                    logger.debug("Found sound at: {}", altPath);
                    return loadSoundFromStream(name, finalAltIs, altPath);
                }
            } catch (Exception e) {
                logger.trace("Error trying {}: {}", altPath, e.getMessage());
            }
        }

        return LoadResult.failure("Could not find sound file at any path!");
    }

    private LoadResult loadSoundFromStream(String name, InputStream is, String path) {
        // For 3D positional audio, sources need MONO sounds - see SoundBuffer's source setup.
        return decodeToBuffer(name, is, path, true);
    }

    /**
     * Decodes {@code is} to 16-bit PCM and uploads it to a new OpenAL buffer.
     *
     * @param forceMono when true, downmixes to mono (required for the 3D-positional SFX sources
     *                  in {@link SoundBuffer}); when false, preserves up to 2 channels (music,
     *                  played back on {@link MusicChannel}'s plain 2D source).
     */
    private LoadResult decodeToBuffer(String name, InputStream is, String path, boolean forceMono) {
        try {
            try (BufferedInputStream bis = new BufferedInputStream(is);
                 AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(bis)) {
                AudioFormat format = audioInputStream.getFormat();

                logger.debug("Sound '{}' original format: {}", name, format);

                int targetChannels = forceMono ? 1 : Math.min(format.getChannels(), 2);
                if (forceMono && format.getChannels() > 1) {
                    logger.debug("Converting {}-channel audio to MONO for 3D positional audio", format.getChannels());
                }

                AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    format.getSampleRate(),
                    16,
                    targetChannels,
                    targetChannels * 2,    // Frame size: channels * 2 bytes
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

                    logger.debug("Sound '{}' converted: {} channels, {} Hz, {} bytes",
                            name, channels, sampleRate, audioData.length);

                    int alFormat = channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;

                    int bufferPointer = alGenBuffers();
                    alBufferData(bufferPointer, alFormat, audioBuffer, sampleRate);

                    int error = alGetError();
                    if (error != AL_NO_ERROR) {
                        String errorMsg = "OpenAL error loading buffer: " + error;
                        logger.error(errorMsg);
                        return LoadResult.failure(errorMsg);
                    }

                    logger.debug("Successfully loaded sound: {} ({} channels, {} Hz)", name, channels, sampleRate);
                    return LoadResult.success(bufferPointer);
                }

            } catch (UnsupportedAudioFileException e) {
                String errorMsg = "Unsupported audio file format: " + path;
                logger.error(errorMsg, e);
                return LoadResult.failure(errorMsg);
            }

        } catch (IOException e) {
            String errorMsg = "Error loading sound " + path + ": " + e.getMessage();
            logger.error(errorMsg, e);
            return LoadResult.failure(errorMsg);
        }
    }
}
