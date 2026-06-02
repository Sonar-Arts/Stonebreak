package com.openmason.engine.audio;

/**
 * Game-agnostic OpenAL sound system. Owns the OpenAL context, sound buffers/sources,
 * the 3D listener, master volume, and playback (2D and positional 3D).
 *
 * <p>Higher-level, game-specific behaviour (player footstep selection, entity sounds,
 * player-position-relative test commands) lives in the consuming module, not here.
 */
public class SoundSystem {
    private static SoundSystem instance;

    private final OpenALContext openALContext;
    private final AudioLoader audioLoader;
    private final SoundBuffer soundBuffer;
    private final VolumeController volumeController;
    private final SoundPlayer soundPlayer;
    private final AudioListener audioListener;
    private final AudioDiagnostics audioDiagnostics;

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
        registerLoadResult(name, result);
    }

    /**
     * Loads a sound from a caller-supplied {@link java.io.InputStream}. Preferred when the audio
     * resource lives in a different module than this engine (the caller resolves it against its
     * own module/classloader). The stream is closed by this method.
     */
    public void loadSound(String name, java.io.InputStream stream) {
        AudioLoader.LoadResult result = audioLoader.loadSound(name, stream);
        registerLoadResult(name, result);
    }

    private void registerLoadResult(String name, AudioLoader.LoadResult result) {
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

    public void cleanup() {
        soundBuffer.cleanup();
        openALContext.cleanup();
    }
}
