package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.generation.diffusion.tgmpipe.TGMPipe;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tile cache in front of the {@link TGMPipe} for one seed and level of detail: buckets
 * coordinates into tiles ({@code Math.floorDiv}, so negative coordinates land in the right tile),
 * shares one request among every thread that lands on the same tile, and bounds memory with LRU
 * eviction over resolved tiles. Failures are never cached — the next probe asks again.
 *
 * <p>Closing it withdraws every request still in flight: the service drops them if it has not
 * started them, and their waiters get {@link TileRequestCancelledException}.
 */
public class DiffusionTileCache implements TerrainTileSource, AutoCloseable {

    /** Where tiles come from: TGMPipe in production, a stub in tests. */
    @FunctionalInterface
    public interface TileFetcher {
        CompletableFuture<TerrainTile> fetch(long seed, int tileX, int tileZ, int lod, int priority);
    }

    private record TileKey(int tileX, int tileZ) {}

    private final TileFetcher fetcher;
    private final long seed;
    private final int lod;
    private final int priority;
    private final int tileSize;
    private final int maxCachedTiles;
    private final ConcurrentHashMap<TileKey, CompletableFuture<TerrainTile>> tiles = new ConcurrentHashMap<>();
    private final LinkedHashMap<TileKey, Boolean> lru = new LinkedHashMap<>(16, 0.75f, true);
    private final Object lruLock = new Object();
    private volatile boolean closed;

    /** Full-detail tiles for the world's chunks. */
    public DiffusionTileCache(long seed) {
        this(seed, 1, TGMPipe.PRIORITY_WORLD);
    }

    /**
     * @param lod world blocks per sample (a power of two). Above 1, every coordinate given or
     *            returned is in SAMPLE units (world blocks / lod): the terrain mapper zoomed out.
     */
    public DiffusionTileCache(long seed, int lod, int priority) {
        this(TGMPipe.getInstance()::requestTile, seed, lod, priority, TerrainScale.TILE_SIZE_BLOCKS,
                Integer.getInteger("stonebreak.tgmpipe.maxCachedTiles", 64));
    }

    DiffusionTileCache(TileFetcher fetcher, long seed, int lod, int priority, int tileSize, int maxCachedTiles) {
        if (lod < 1 || Integer.bitCount(lod) != 1) {
            throw new IllegalArgumentException("lod must be a power of two, got " + lod);
        }
        this.fetcher = fetcher;
        this.seed = seed;
        this.lod = lod;
        this.priority = priority;
        this.tileSize = tileSize;
        this.maxCachedTiles = maxCachedTiles;
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
            throw new TGMPipeException("failed to fetch tile for (" + worldX + "," + worldZ + ")", cause);
        }
    }

    public CompletableFuture<TerrainTile> getTileAsync(int worldX, int worldZ) {
        if (closed) {
            return CompletableFuture.failedFuture(new TileRequestCancelledException("tile cache is closed"));
        }
        TileKey key = new TileKey(Math.floorDiv(worldX, tileSize), Math.floorDiv(worldZ, tileSize));
        CompletableFuture<TerrainTile> future = tiles.computeIfAbsent(key,
                k -> fetcher.fetch(seed, k.tileX(), k.tileZ(), lod, priority));
        future.whenComplete((tile, err) -> {
            if (err != null) {
                tiles.remove(key, future);
            } else {
                touch(key);
            }
        });
        if (closed) {
            withdraw(); // raced close(): don't leave this one running for nobody
        }
        return future;
    }

    private void touch(TileKey key) {
        TileKey evicted = null;
        synchronized (lruLock) {
            lru.put(key, Boolean.TRUE);
            if (lru.size() > maxCachedTiles) {
                Iterator<Map.Entry<TileKey, Boolean>> it = lru.entrySet().iterator();
                evicted = it.next().getKey();
                it.remove();
            }
        }
        if (evicted != null) {
            tiles.remove(evicted);
        }
    }

    private void withdraw() {
        TileRequestCancelledException cancelled = new TileRequestCancelledException("tile cache closed");
        tiles.values().forEach(f -> f.completeExceptionally(cancelled));
        tiles.clear();
    }

    @Override
    public void close() {
        closed = true;
        withdraw();
    }
}
