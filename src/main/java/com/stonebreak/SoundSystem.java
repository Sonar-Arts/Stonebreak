package com.stonebreak;

import org.lwjgl.openal.*;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;

public class SoundSystem {
    private static SoundSystem instance;
    
    private long device;
    private long context;
    private Map<String, Integer> soundBuffers;
    private Map<String, Integer[]> sources; // Multiple sources per sound
    private Map<String, Integer> sourceIndexes; // Track current source index
    private float masterVolume = 1.0f; // Master volume multiplier
    
    private SoundSystem() {
        soundBuffers = new HashMap<>();
        sources = new HashMap<>();
        sourceIndexes = new HashMap<>();
    }
    
    public static SoundSystem getInstance() {
        if (instance == null) {
            instance = new SoundSystem();
        }
        return instance;
    }
    
    public void initialize() {
        try {
            String defaultDeviceName = alcGetString(0, ALC_DEFAULT_DEVICE_SPECIFIER);
            System.out.println("Initializing OpenAL with device: " + defaultDeviceName);
            
            device = alcOpenDevice(defaultDeviceName);
            if (device == 0) {
                System.err.println("Failed to open OpenAL device");
                return;
            }
            
            int[] attributes = {0};
            context = alcCreateContext(device, attributes);
            if (context == 0) {
                System.err.println("Failed to create OpenAL context");
                alcCloseDevice(device);
                return;
            }
            
            if (!alcMakeContextCurrent(context)) {
                System.err.println("Failed to make OpenAL context current");
                alcDestroyContext(context);
                alcCloseDevice(device);
                return;
            }
            
            AL.createCapabilities(ALC.createCapabilities(device));
            
            alListener3f(AL_POSITION, 0, 0, 1.0f);
            alListener3f(AL_VELOCITY, 0, 0, 0);
            alListener3f(AL_ORIENTATION, 0, 0, 1);
            
            System.out.println("OpenAL initialized successfully");
        } catch (Exception e) {
            System.err.println("Failed to initialize OpenAL: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void loadSound(String name, String resourcePath) {
        System.out.println("=== Loading Sound ====");
        System.out.println("Sound name: " + name);
        System.out.println("Resource path: " + resourcePath);
        System.out.println("Class: " + getClass().getName());
        System.out.println("ClassLoader: " + getClass().getClassLoader());
        
        // Try to list available resources
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
        } catch (Exception e) {
            System.err.println("Error listing resources: " + e.getMessage());
        }
        
        // Also try URL-based access
        try {
            java.net.URL soundUrl = getClass().getResource(resourcePath);
            System.out.println("Direct URL for " + resourcePath + ": " + soundUrl);
        } catch (Exception e) {
            System.err.println("Error getting URL: " + e.getMessage());
        }
        
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                System.err.println("InputStream is NULL for path: " + resourcePath);
                
                // Try alternative paths
                System.out.println("Trying alternative paths...");
                String[] alternativePaths = {
                    "/sounds/GrassWalk.wav",
                    "sounds/GrassWalk.wav",
                    "/GrassWalk.wav",
                    "GrassWalk.wav"
                };
                
                for (String altPath : alternativePaths) {
                    System.out.println("Trying: " + altPath);
                    try (InputStream altIs = getClass().getResourceAsStream(altPath)) {
                        if (altIs != null) {
                            System.out.println("Found sound at: " + altPath);
                            loadSoundFromStream(name, altIs, altPath);
                            return;
                        }
                    } catch (Exception e) {
                        System.err.println("Error trying " + altPath + ": " + e.getMessage());
                    }
                }
                
                System.err.println("Could not find sound file at any path!");
                return;
            }
            
            System.out.println("Successfully opened InputStream for: " + resourcePath);
            loadSoundFromStream(name, is, resourcePath);
            
        } catch (IOException e) {
            System.err.println("IOException loading sound " + resourcePath + ": " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("=====================");
    }
    
    private void loadSoundFromStream(String name, InputStream is, String path) {
        try {
            System.out.println("Loading sound: " + name + " from " + path);
            
            // Use Java Sound API to load WAV files
            try (BufferedInputStream bis = new BufferedInputStream(is)) {
                AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(bis);
                AudioFormat format = audioInputStream.getFormat();
                
                System.out.println("Original format: " + format);
                
                // Convert to PCM if necessary
                AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    format.getSampleRate(),
                    16,
                    format.getChannels(),
                    format.getChannels() * 2,
                    format.getSampleRate(),
                    false
                );
                
                AudioInputStream pcmStream = AudioSystem.getAudioInputStream(targetFormat, audioInputStream);
                
                byte[] audioData = pcmStream.readAllBytes();
                ByteBuffer audioBuffer = BufferUtils.createByteBuffer(audioData.length);
                audioBuffer.put(audioData);
                audioBuffer.flip();
                
                int channels = targetFormat.getChannels();
                int sampleRate = (int) targetFormat.getSampleRate();
                
                System.out.println("Converted format: " + channels + " channels, " + sampleRate + " Hz, " + audioData.length + " bytes");
                
                int alFormat;
                if (channels == 1) {
                    alFormat = AL_FORMAT_MONO16;
                } else if (channels == 2) {
                    alFormat = AL_FORMAT_STEREO16;
                } else {
                    System.err.println("Unsupported channel count: " + channels);
                    return;
                }
                
                int bufferPointer = alGenBuffers();
                alBufferData(bufferPointer, alFormat, audioBuffer, sampleRate);
                
                // Check for OpenAL errors
                int error = alGetError();
                if (error != AL_NO_ERROR) {
                    System.err.println("OpenAL error loading buffer: " + error);
                    return;
                }
                
                soundBuffers.put(name, bufferPointer);
                
                // Create multiple sources for overlapping sounds (e.g., 4 sources per sound)
                int numSources = 4;
                Integer[] soundSources = new Integer[numSources];
                
                for (int i = 0; i < numSources; i++) {
                    int sourcePointer = alGenSources();
                    alSourcei(sourcePointer, AL_BUFFER, bufferPointer);
                    alSourcef(sourcePointer, AL_GAIN, 0.5f);
                    alSourcef(sourcePointer, AL_PITCH, 1f);
                    alSource3f(sourcePointer, AL_POSITION, 0, 0, 0);
                    
                    error = alGetError();
                    if (error != AL_NO_ERROR) {
                        System.err.println("OpenAL error creating source " + i + ": " + error);
                        return;
                    }
                    
                    soundSources[i] = sourcePointer;
                }
                
                sources.put(name, soundSources);
                sourceIndexes.put(name, 0);
                
                System.out.println("Successfully loaded sound: " + name + " (" + channels + " channels, " + sampleRate + " Hz)");
                
                pcmStream.close();
                audioInputStream.close();
                
            } catch (UnsupportedAudioFileException e) {
                System.err.println("Unsupported audio file format: " + path);
                e.printStackTrace();
            }
            
        } catch (IOException e) {
            System.err.println("Error loading sound " + path + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void playSound(String name) {
        Integer[] soundSources = sources.get(name);
        if (soundSources != null) {
            // Get the current source index and cycle through sources
            int currentIndex = sourceIndexes.get(name);
            int source = soundSources[currentIndex];
            
            // Apply master volume and play the sound
            alSourcef(source, AL_GAIN, 0.5f * masterVolume); // 0.5f is the default volume
            alSourcePlay(source);
            
            // Move to next source for next call (round-robin)
            sourceIndexes.put(name, (currentIndex + 1) % soundSources.length);
            
            int error = alGetError();
            if (error != AL_NO_ERROR) {
                System.err.println("OpenAL error playing sound " + name + ": " + error);
            }
        } else {
            System.err.println("Sound not found: " + name);
        }
    }
    
    public void playSoundWithVolume(String name, float volume) {
        Integer[] soundSources = sources.get(name);
        if (soundSources != null) {
            // Get the current source index and cycle through sources
            int currentIndex = sourceIndexes.get(name);
            int source = soundSources[currentIndex];
            
            // Apply master volume multiplied by the specific volume and play the sound
            alSourcef(source, AL_GAIN, volume * masterVolume);
            alSourcePlay(source);
            
            // Move to next source for next call (round-robin)
            sourceIndexes.put(name, (currentIndex + 1) % soundSources.length);
            
            int error = alGetError();
            if (error != AL_NO_ERROR) {
                System.err.println("OpenAL error playing sound " + name + " with volume " + volume + ": " + error);
            }
        } else {
            System.err.println("Sound not found: " + name);
        }
    }
    
    public void setListenerPosition(float x, float y, float z) {
        alListener3f(AL_POSITION, x, y, z);
    }
    
    public boolean isSoundLoaded(String name) {
        return soundBuffers.containsKey(name) && sources.containsKey(name) && sources.get(name) != null;
    }
    
    public void testBasicFunctionality() {
        System.out.println("=== SoundSystem Test ===");
        System.out.println("Device: " + device);
        System.out.println("Context: " + context);
        System.out.println("Loaded sound buffers: " + soundBuffers.keySet());
        System.out.println("Available sources: " + sources.keySet());
        System.out.println("soundBuffers map size: " + soundBuffers.size());
        System.out.println("sources map size: " + sources.size());
        
        // Test if OpenAL is working
        int error = alGetError();
        if (error != AL_NO_ERROR) {
            System.err.println("OpenAL error in test: " + error);
        } else {
            System.out.println("OpenAL is functioning correctly");
        }
        
        // Test if grasswalk sound is loaded
        if (soundBuffers.containsKey("grasswalk") && sources.containsKey("grasswalk")) {
            System.out.println("✓ Grasswalk sound is properly loaded");
            System.out.println("Attempting to play grasswalk sound...");
            playSound("grasswalk");
        } else {
            System.err.println("✗ Grasswalk sound failed to load!");
            System.err.println("soundBuffers contains 'grasswalk': " + soundBuffers.containsKey("grasswalk"));
            System.err.println("sources contains 'grasswalk': " + sources.containsKey("grasswalk"));
            
            // Show what we actually have
            if (!soundBuffers.isEmpty()) {
                System.err.println("Available sound buffers:");
                for (String key : soundBuffers.keySet()) {
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
    
    /**
     * Sets the master volume for all sounds.
     * @param volume Volume level (0.0 = silent, 1.0 = normal volume)
     */
    public void setMasterVolume(float volume) {
        this.masterVolume = Math.max(0.0f, Math.min(1.0f, volume));
    }
    
    /**
     * Gets the current master volume.
     * @return Current master volume level
     */
    public float getMasterVolume() {
        return masterVolume;
    }
    
    public void cleanup() {
        for (Integer[] soundSources : sources.values()) {
            if (soundSources != null) {
                for (int source : soundSources) {
                    alDeleteSources(source);
                }
            }
        }
        for (int buffer : soundBuffers.values()) {
            alDeleteBuffers(buffer);
        }
        
        alcDestroyContext(context);
        alcCloseDevice(device);
    }
}