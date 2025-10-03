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

    /**
     * Plays a sound at a specific 3D position in the world.
     * The sound will have proper 3D audio properties including distance attenuation.
     *
     * @param name The name of the sound to play
     * @param volume The volume level (0.0 to 1.0)
     * @param x The X coordinate in world space
     * @param y The Y coordinate in world space
     * @param z The Z coordinate in world space
     */
    public void playSoundAt3D(String name, float volume, float x, float y, float z) {
        soundPlayer.playSoundAt3D(name, volume, x, y, z);
    }

    /**
     * Plays a sound at a specific 3D position with pitch variation.
     *
     * @param name The name of the sound to play
     * @param volume The volume level (0.0 to 1.0)
     * @param x The X coordinate in world space
     * @param y The Y coordinate in world space
     * @param z The Z coordinate in world space
     */
    public void playSoundAt3DWithVariation(String name, float volume, float x, float y, float z) {
        soundPlayer.playSoundAt3DWithVariation(name, volume, x, y, z);
    }

    /**
     * Convenience method to play a sound at a 3D position using a Vector3f.
     *
     * @param name The name of the sound to play
     * @param volume The volume level (0.0 to 1.0)
     * @param position The position vector in world space
     */
    public void playSoundAt3D(String name, float volume, org.joml.Vector3f position) {
        playSoundAt3D(name, volume, position.x, position.y, position.z);
    }

    /**
     * Convenience method to play a sound at a 3D position with variation using a Vector3f.
     *
     * @param name The name of the sound to play
     * @param volume The volume level (0.0 to 1.0)
     * @param position The position vector in world space
     */
    public void playSoundAt3DWithVariation(String name, float volume, org.joml.Vector3f position) {
        playSoundAt3DWithVariation(name, volume, position.x, position.y, position.z);
    }
    
    public void setListenerPosition(float x, float y, float z) {
        audioListener.setListenerPosition(x, y, z);
    }

    /**
     * Sets the audio listener orientation for proper 3D audio.
     * @param forward The forward direction vector
     * @param up The up direction vector
     */
    public void setListenerOrientation(org.joml.Vector3f forward, org.joml.Vector3f up) {
        audioListener.setListenerOrientation(forward, up);
    }

    /**
     * Sets the audio listener position and orientation from camera data.
     * This should be called every frame for proper 3D spatial audio.
     * @param position The listener position (usually player position)
     * @param front The camera front/forward vector
     * @param up The camera up vector
     */
    public void setListenerFromCamera(org.joml.Vector3f position, org.joml.Vector3f front, org.joml.Vector3f up) {
        audioListener.setListenerFromCamera(position, front, up);
    }
    
    public boolean isSoundLoaded(String name) {
        return soundBuffer.isSoundLoaded(name);
    }
    
    public void testBasicFunctionality() {
        audioDiagnostics.testBasicFunctionality();
        // Note: Sound playing removed to prevent audio before world is loaded
        // Use testSoundPlayback() method if you need to test sound playback specifically
    }

    /**
     * Tests sound playback functionality - only call this when appropriate (e.g., in-game).
     * This method actually plays a sound, unlike testBasicFunctionality().
     */
    public void testSoundPlayback() {
        if (soundBuffer.isSoundLoaded("grasswalk")) {
            System.out.println("Testing sound playback with grasswalk...");
            playSound("grasswalk");
        } else {
            System.err.println("Cannot test sound playback - grasswalk sound not loaded");
        }
    }

    /**
     * Tests 3D audio functionality with known positions relative to the player.
     * This method plays sounds at specific distances to verify 3D audio is working.
     */
    public void test3DAudio() {
        if (!soundBuffer.isSoundLoaded("blockpickup")) {
            System.err.println("Cannot test 3D audio - blockpickup sound not loaded");
            return;
        }

        com.stonebreak.player.Player player = com.stonebreak.core.Game.getPlayer();
        if (player == null) {
            System.err.println("Cannot test 3D audio - player not found");
            return;
        }

        org.joml.Vector3f playerPos = player.getPosition();
        System.out.println("🧪 3D AUDIO TEST: Player position: (" + playerPos.x + ", " + playerPos.y + ", " + playerPos.z + ")");

        // Test sounds at various distances
        float[][] testPositions = {
            {playerPos.x + 2.0f, playerPos.y, playerPos.z},     // 2 blocks away (should be loud)
            {playerPos.x + 10.0f, playerPos.y, playerPos.z},    // 10 blocks away (should be quieter)
            {playerPos.x + 25.0f, playerPos.y, playerPos.z},    // 25 blocks away (should be very quiet)
            {playerPos.x + 60.0f, playerPos.y, playerPos.z}     // 60 blocks away (should be silent)
        };

        String[] descriptions = {
            "2 blocks (should be loud)",
            "10 blocks (should be quieter)",
            "25 blocks (should be very quiet)",
            "60 blocks (should be silent)"
        };

        for (int i = 0; i < testPositions.length; i++) {
            float[] pos = testPositions[i];
            System.out.println("🧪 3D AUDIO TEST: Playing sound at " + descriptions[i] + " - position (" + pos[0] + ", " + pos[1] + ", " + pos[2] + ")");

            // Calculate distance for reference
            float distance = (float) Math.sqrt(
                Math.pow(pos[0] - playerPos.x, 2) +
                Math.pow(pos[1] - playerPos.y, 2) +
                Math.pow(pos[2] - playerPos.z, 2)
            );
            System.out.println("🧪 3D AUDIO TEST: Calculated distance: " + distance + " blocks");

            playSoundAt3D("blockpickup", 0.8f, pos[0], pos[1], pos[2]);

            // Wait a bit between sounds
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("🧪 3D AUDIO TEST: Test completed. If 3D audio is working, you should have heard:");
        System.out.println("  - Loud sound at 2 blocks");
        System.out.println("  - Quieter sound at 10 blocks");
        System.out.println("  - Very quiet sound at 25 blocks");
        System.out.println("  - Silent or barely audible sound at 60 blocks");
    }

    /**
     * Simple immediate test of 3D audio at one position
     */
    public void testSingle3DAudio(float distance) {
        if (!soundBuffer.isSoundLoaded("blockpickup")) {
            System.err.println("Cannot test 3D audio - blockpickup sound not loaded");
            return;
        }

        com.stonebreak.player.Player player = com.stonebreak.core.Game.getPlayer();
        if (player == null) {
            System.err.println("Cannot test 3D audio - player not found");
            return;
        }

        org.joml.Vector3f playerPos = player.getPosition();
        float testX = playerPos.x + distance;
        float testY = playerPos.y;
        float testZ = playerPos.z;

        System.out.println("🧪 SINGLE 3D TEST: Player at (" + playerPos.x + ", " + playerPos.y + ", " + playerPos.z + ")");
        System.out.println("🧪 SINGLE 3D TEST: Playing sound " + distance + " blocks away at (" + testX + ", " + testY + ", " + testZ + ")");

        playSoundAt3D("blockpickup", 0.8f, testX, testY, testZ);
    }

    /**
     * Diagnoses OpenAL 3D audio configuration
     */
    public void diagnoseOpenAL3D() {
        System.out.println("🔍 OPENAL DIAGNOSIS:");

        // Check distance model
        int distanceModel = org.lwjgl.openal.AL10.alGetInteger(org.lwjgl.openal.AL10.AL_DISTANCE_MODEL);
        System.out.println("  Distance Model: " + distanceModel + " (AL_INVERSE_DISTANCE_CLAMPED=" + org.lwjgl.openal.AL10.AL_INVERSE_DISTANCE_CLAMPED + ")");

        // Check if we have a blockpickup source to examine
        Integer[] sources = soundBuffer.getSources("blockpickup");
        if (sources != null && sources.length > 0) {
            int source = sources[0];
            System.out.println("  Examining source " + source + " for 'blockpickup':");

            // Check source properties
            float refDist = org.lwjgl.openal.AL10.alGetSourcef(source, org.lwjgl.openal.AL10.AL_REFERENCE_DISTANCE);
            float maxDist = org.lwjgl.openal.AL10.alGetSourcef(source, org.lwjgl.openal.AL10.AL_MAX_DISTANCE);
            float rolloff = org.lwjgl.openal.AL10.alGetSourcef(source, org.lwjgl.openal.AL10.AL_ROLLOFF_FACTOR);
            int relative = org.lwjgl.openal.AL10.alGetSourcei(source, org.lwjgl.openal.AL10.AL_SOURCE_RELATIVE);

            System.out.println("    Reference Distance: " + refDist);
            System.out.println("    Max Distance: " + maxDist);
            System.out.println("    Rolloff Factor: " + rolloff);
            System.out.println("    Source Relative: " + relative + " (AL_FALSE=" + org.lwjgl.openal.AL10.AL_FALSE + ")");

            // Check listener position
            java.nio.FloatBuffer listenerPos = org.lwjgl.BufferUtils.createFloatBuffer(3);
            org.lwjgl.openal.AL10.alGetListenerfv(org.lwjgl.openal.AL10.AL_POSITION, listenerPos);
            System.out.println("    Listener Position: (" + listenerPos.get(0) + ", " + listenerPos.get(1) + ", " + listenerPos.get(2) + ")");

        } else {
            System.out.println("  No blockpickup sources found for examination");
        }

        int error = org.lwjgl.openal.AL10.alGetError();
        if (error != org.lwjgl.openal.AL10.AL_NO_ERROR) {
            System.err.println("  OpenAL Error during diagnosis: " + error);
        } else {
            System.out.println("  No OpenAL errors detected");
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