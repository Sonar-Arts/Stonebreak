package com.stonebreak.core.bootstrap;

import com.stonebreak.audio.SoundSystem;
import com.stonebreak.config.Settings;
import com.stonebreak.player.Player;
import com.stonebreak.mobs.sbe.SbeCowLoader;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.ui.DebugOverlay;
import com.stonebreak.util.MemoryLeakDetector;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.model.WorldData;

/**
 * Holds the side-effect / configuration steps that {@code Game}'s init
 * methods used to inline (sound loading, engine pre-init, diagnostics
 * startup, MMS guard rails). Field wiring stays on {@code Game} — this
 * class provides reusable building blocks, not an alternate assembler.
 */
public final class GameBootstrap {

    private GameBootstrap() {
    }

    /**
     * Initializes OpenAL, loads the movement/pickup sound samples, and
     * applies the saved master-volume setting. Also runs the sanity check.
     */
    public static void configureSoundSystem(SoundSystem soundSystem) {
        soundSystem.initialize();
        soundSystem.loadSound("grasswalk", "/sounds/GrassWalk.wav");
        soundSystem.loadSound("sandwalk", "/sounds/SandWalk-001.wav");
        soundSystem.loadSound("woodwalk", "/sounds/WoodWalk.wav");
        soundSystem.loadSound("blockpickup", "/sounds/BlockPickup.wav");

        soundSystem.setMasterVolume(Settings.getInstance().getMasterVolume());
        soundSystem.testBasicFunctionality();
    }

    /**
     * Starts memory-leak monitoring and returns the detector. Logs status
     * to stdout to mirror legacy behavior.
     */
    public static MemoryLeakDetector startMemoryLeakDetection() {
        MemoryLeakDetector detector = MemoryLeakDetector.getInstance();
        detector.startMonitoring();
        System.out.println("Memory leak detection started.");
        return detector;
    }

    /**
     * Creates the F3 debug overlay, logging its readiness.
     */
    public static DebugOverlay createDebugOverlay() {
        DebugOverlay overlay = new DebugOverlay();
        System.out.println("Debug overlay initialized (F3 to toggle).");
        return overlay;
    }

    /**
     * Primes the SBE cow asset (mesh, variants and animation clips) so the
     * decode cost is paid up front rather than on the first cow spawn.
     */
    public static void initializeCowAsset() {
        SbeCowLoader.get();
        System.out.println("SBE cow asset loaded");
    }

    /**
     * Configures engine voxel bounds and pre-initializes the MMS API so
     * {@link World} construction can hand meshes off immediately.
     * The world reference is filled in later by {@link World} itself.
     */
    public static void configureEngine(TextureAtlas textureAtlas, Renderer renderer) {
        com.openmason.engine.voxel.cco.coordinates.CcoBounds.configure(
                new com.openmason.engine.voxel.VoxelWorldConfig(
                        WorldConfiguration.CHUNK_SIZE,
                        WorldConfiguration.WORLD_HEIGHT,
                        WorldConfiguration.SEA_LEVEL));

        com.stonebreak.world.chunk.api.mightyMesh.MmsAPI.initialize(textureAtlas, null);
        System.out.println("[MMS-API] Mighty Mesh System pre-initialized (World will be set later)");

        if (renderer != null) {
            renderer.applySBODispatcher();
        }
    }

    /**
     * Defensive re-initialization when the MMS API is found uninitialized
     * at world-component setup time. Normally a no-op because
     * {@link World}'s constructor registers itself with the API.
     */
    public static void ensureMmsApiInitialized(TextureAtlas textureAtlas, World world) {
        if (com.stonebreak.world.chunk.api.mightyMesh.MmsAPI.isInitialized()) {
            return;
        }
        System.err.println("[MMS-API] WARNING: MmsAPI not initialized - this should not happen!");
        if (textureAtlas != null && world != null) {
            com.stonebreak.world.chunk.api.mightyMesh.MmsAPI.initialize(textureAtlas, world);
            System.out.println("[MMS-API] Emergency initialization performed");
        }
    }

    /**
     * Re-points an existing {@link SaveService} at fresh world/player
     * instances during world-component re-init.
     */
    public static void reinitializeSaveService(SaveService saveService, WorldData worldData, Player player, World world) {
        if (saveService == null || worldData == null) {
            return;
        }
        System.out.println("[SAVE-SYSTEM] Updating save service references during world component initialization");
        saveService.initialize(worldData, player, world);
    }
}
