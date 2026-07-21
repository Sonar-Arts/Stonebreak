package com.stonebreak.world.generation.diffusion;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java-side tile cache in front of {@link DiffusionTerrainClient}: buckets
 * world coordinates the same way the bridge does ({@code Math.floorDiv},
 * see terrain-bridge/bridge/tiling.py), de-dupes concurrent in-flight
 * requests for the same tile (two chunk-worker threads landing on the same
 * tile share one HTTP call), and bounds memory with LRU eviction over
 * resolved tiles. Failures are never cached — the next probe gets a fresh
 * attempt rather than being stuck behind a stale failure.
 */
public class DiffusionTileCache implements TerrainTileSource {

    private record TileKey(int tileX, int tileZ) {}

    private final DiffusionBridgeConfig config;
    private final DiffusionTerrainClient client;
    private final ConcurrentHashMap<TileKey, CompletableFuture<TerrainTile>> tiles = new ConcurrentHashMap<>();
    private final LinkedHashMap<TileKey, Boolean> lru = new LinkedHashMap<>(16, 0.75f, true);
    private final Object lruLock = new Object();

    public DiffusionTileCache(DiffusionBridgeConfig config, long seed) {
        this(config, new DiffusionTerrainClient(config, seed));
    }

    DiffusionTileCache(DiffusionBridgeConfig config, DiffusionTerrainClient client) {
        this.config = config;
        this.client = client;
    }

    @Override
    public TerrainTile getTile(int worldX, int worldZ) {
        try {
            return getTileAsync(worldX, worldZ).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new TerrainBridgeException("failed to fetch tile for (" + worldX + "," + worldZ + ")", cause);
        }
    }

    public CompletableFuture<TerrainTile> getTileAsync(int worldX, int worldZ) {
        TileKey key = keyFor(worldX, worldZ);
        CompletableFuture<TerrainTile> future = tiles.computeIfAbsent(key, k -> client.fetchTile(worldX, worldZ));
        future.whenComplete((tile, err) -> {
            if (err != null) {
                tiles.remove(key, future);
            } else {
                touch(key);
            }
        });
        return future;
    }

    /** Fire-and-forget warm for a tile the player is heading toward. */
    public void prefetch(int worldX, int worldZ) {
        client.prefetch(worldX, worldZ);
    }

    private TileKey keyFor(int worldX, int worldZ) {
        int tileX = Math.floorDiv(worldX, config.tileSizeBlocks());
        int tileZ = Math.floorDiv(worldZ, config.tileSizeBlocks());
        return new TileKey(tileX, tileZ);
    }

    private void touch(TileKey key) {
        TileKey evicted = null;
        synchronized (lruLock) {
            lru.put(key, Boolean.TRUE);
            if (lru.size() > config.maxCachedTiles()) {
                Iterator<Map.Entry<TileKey, Boolean>> it = lru.entrySet().iterator();
                if (it.hasNext()) {
                    evicted = it.next().getKey();
                    it.remove();
                }
            }
        }
        if (evicted != null) {
            tiles.remove(evicted);
        }
    }

    public void close() {
        client.close();
    }
}
