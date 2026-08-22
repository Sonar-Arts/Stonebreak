package com.stonebreak.world;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.joml.Vector3f;

import com.stonebreak.world.fastlod.FastLodManager;
import com.stonebreak.world.fastlod.FastLodStore;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Owns the {@link FastLodManager} lifecycle for a {@link World}: lazy construction once the
 * render thread supplies a texture atlas, resolution and opening of the per-world SQLite
 * LOD store ({@code worlds/<name>/fastlod/cache.sqlite}), the render-only ring tick, and
 * the two shutdown paths (deferred GL drain on world cleanup, inline drain on world switch).
 */
final class FastLodLifecycle {
    private final WorldConfiguration config;
    private final TerrainGenerationSystem terrainSystem;

    // Lazily constructed once the render-thread hands us a texture atlas.
    private volatile FastLodManager fastLodManager;

    FastLodLifecycle(WorldConfiguration config, TerrainGenerationSystem terrainSystem) {
        this.config = config;
        this.terrainSystem = terrainSystem;
    }

    FastLodManager get() {
        return fastLodManager;
    }

    /**
     * Constructs the Fast LOD manager the first time the render thread hands
     * us a texture atlas. Opens a persistent SQLite cache under the active
     * world's save directory when one is available; otherwise runs without
     * persistence. Idempotent; safe to call each frame.
     */
    void ensure(com.stonebreak.rendering.textures.BlockTextureArray textureArray) {
        if (fastLodManager != null || textureArray == null || terrainSystem == null) return;
        synchronized (this) {
            if (fastLodManager != null) return;
            FastLodStore store = openStoreIfPossible();
            fastLodManager = new FastLodManager(config, terrainSystem, textureArray, store);
        }
    }

    private static FastLodStore openStoreIfPossible() {
        // Resolves the save directory without coupling World to how save state
        // is plumbed. Any failure (no save path, SQLite driver missing) falls
        // through to pure in-memory LOD.
        try {
            String worldPath = null;
            com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
            com.stonebreak.world.save.SaveService svc = (game != null) ? game.getSaveService() : null;
            if (svc != null) {
                worldPath = svc.getWorldPath();
            }
            if (worldPath == null || worldPath.isEmpty()) {
                // Two-world model: the client RENDER world carries no
                // SaveService — the authoritative one lives on the co-located
                // integrated server (singleplayer + LAN host). Only the render
                // world ever opens a FastLOD store (the headless server world
                // is never rendered), so there is no double-open on the file.
                // Remote-join clients have no integrated server and correctly
                // fall through to in-memory LOD.
                var server = com.stonebreak.network.MultiplayerSession.getServer();
                var ctx = (server != null) ? server.worldContext() : null;
                var level = (ctx != null) ? ctx.serverLevel() : null;
                var save = (level != null) ? level.saveService() : null;
                if (save != null) {
                    worldPath = save.getWorldPath();
                }
            }
            if (worldPath == null || worldPath.isEmpty()) return null;
            Path dbPath = Paths.get(worldPath, "fastlod", "cache.sqlite");
            return FastLodStore.open(dbPath);
        } catch (Exception e) {
            System.err.println("[World] FastLod store setup failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * FastLOD ring tick for render-only worlds. On a full world ChunkManager.update drives
     * this, but render-only worlds skip the chunk manager entirely (chunks stream from the
     * server), so without this call the LOD manager is created by the render pass yet never
     * schedules a single node — distant terrain simply never appears. The sampler reads the
     * local deterministic TerrainGenerationSystem (seeded from the server's WelcomeS2C world
     * seed), so client-side LOD matches server terrain without any chunk streaming. Runs on
     * the same logic-thread executor that ticks full-world updateRing — threading contract
     * unchanged.
     */
    void updateRing(Vector3f lodPos) {
        if (fastLodManager != null) {
            fastLodManager.updateRing(
                    (int) Math.floor(lodPos.x / WorldConfiguration.CHUNK_SIZE),
                    (int) Math.floor(lodPos.z / WorldConfiguration.CHUNK_SIZE));
        }
    }

    /** World cleanup: shut down and defer the GL drain to the main thread. */
    void shutdownDeferred() {
        if (fastLodManager != null) {
            fastLodManager.shutdown();
            final com.stonebreak.world.fastlod.FastLodManager lod = fastLodManager;
            com.stonebreak.core.Game.getInstance().runOnMainThread(lod::applyGLUpdates);
        }
    }

    /**
     * World switch: shut the Fast LOD manager down so its world-specific SQLite cache is
     * closed. The store points at worlds/<name>/fastlod/cache.sqlite, so leaving it open
     * here would keep the .sqlite/.sqlite-wal/.sqlite-shm files locked and block a later
     * world deletion. Runs on the main/GL thread (clearWorldData is invoked from the
     * quit-to-menu path), so we can drain the LOD GPU cleanup queue inline rather than
     * deferring it.
     */
    void shutdownInline() {
        if (fastLodManager != null) {
            fastLodManager.shutdown();
            fastLodManager.applyGLUpdates();
            fastLodManager = null;
        }
    }
}
