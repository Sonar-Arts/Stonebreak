package com.stonebreak.audio;

import com.stonebreak.audio.components.*;

public class SoundSystem {
    private static SoundSystem instance;

    private final OpenALContext openALContext;
    private final AudioLoader audioLoader;
    private final SoundBuffer soundBuffer;
    private final VolumeController volumeController;
    private final SoundPlayer soundPlayer;
    private final AudioListener audioListener;
    private final AudioDiagnostics audioDiagnostics;
    private PlayerSounds playerSounds;

    private SoundSystem() {
        this.openALContext = new OpenALContext();
        this.audioLoader = new AudioLoader();
        this.soundBuffer = new SoundBuffer(4); // 4 sources per sound for overlapping playback
        this.volumeController = new VolumeController();
        this.soundPlayer = new SoundPlayer(soundBuffer, volumeController);
        this.audioListener = new AudioListener();
        this.audioDiagnostics = new AudioDiagnostics(openALContext, soundBuffer);
    }
    
    public static SoundSystem getInstance() {
        if (instance == null) {
            instance = new SoundSystem();
        }
        return instance;
    }
    
    public void initialize() {
        if (openALContext.initialize()) {
            audioListener.initializeDefaultListener();
        }
    }
    
    public void loadSound(String name, String resourcePath) {
        AudioLoader.LoadResult result = audioLoader.loadSound(name, resourcePath);
        if (result.success) {
            soundBuffer.addSound(name, result.buffer);
        } else {
            System.err.println("Failed to load sound " + name + ": " + result.errorMessage);
        }
    }
    
    public void playSound(String name) {
        soundPlayer.playSound(name);
    }
    
    public void playSoundWithVolume(String name, float volume) {
        soundPlayer.playSoundWithVolume(name, volume);
    }
    
    public void playSoundWithVariation(String name, float volume) {
        soundPlayer.playSoundWithVariation(name, volume);
    }
    
    public void setListenerPosition(float x, float y, float z) {
        audioListener.setListenerPosition(x, y, z);
    }
    
    public boolean isSoundLoaded(String name) {
        return soundBuffer.isSoundLoaded(name);
    }
    
    public void testBasicFunctionality() {
        audioDiagnostics.testBasicFunctionality();
        if (soundBuffer.isSoundLoaded("grasswalk")) {
            playSound("grasswalk");
        }
    }
    
    /**
     * Sets the master volume for all sounds.
     * @param volume Volume level (0.0 = silent, 1.0 = normal volume)
     */
    public void setMasterVolume(float volume) {
        volumeController.setMasterVolume(volume);
    }

    /**
     * Gets the current master volume.
     * @return Current master volume level
     */
    public float getMasterVolume() {
        return volumeController.getMasterVolume();
    }

    /**
     * Initializes player sound management with the given world.
     * Should be called when a world is loaded.
     */
    public void initializePlayerSounds(com.stonebreak.world.World world) {
        this.playerSounds = new PlayerSounds(world);
    }

    /**
     * Updates player walking sounds based on movement state.
     * Should be called every frame during player update.
     */
    public void updatePlayerSounds(org.joml.Vector3f position, org.joml.Vector3f velocity, boolean onGround, boolean physicallyInWater) {
        if (playerSounds != null) {
            playerSounds.updateWalkingSounds(position, velocity, onGround, physicallyInWater);
        }
    }

    /**
     * Resets player sound state. Call when player is created or respawned.
     */
    public void resetPlayerSounds() {
        if (playerSounds != null) {
            playerSounds.reset();
        }
    }

    public void cleanup() {
        soundBuffer.cleanup();
        openALContext.cleanup();
    }
}