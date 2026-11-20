package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.generation.ClimateLayout;
import com.stonebreak.world.generation.HeightCurveVersion;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.diffusion.tgmpipe.TGMPipe;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Tile cache in front of the {@link TGMPipe} for one seed and level of detail: buckets
 * coordinates into tiles ({@code Math.floorDiv}, so negative coordinates land in the right tile),
 * shares one request among every thread that lands on the same tile, and bounds memory with LRU
 * eviction over resolved tiles. Failures are never cached — the next probe asks again.
 *
 * <p>Closing it withdraws every request still in flight: the service drops them if it has not
 * started them, and their waiters get {@link TileRequestCancelledException}.
 *
 * <p>Work that can wait (FastLOD's distant rings) runs under {@link #deferred}, which asks for
 * its tiles at a less urgent priority. When urgent work later lands on a tile still queued that
 * way, the cache asks again at the urgent priority — the service moves the queued job up — so a
 * chunk the player needs never waits behind the horizon.
 */
public class DiffusionTileCache implements TerrainTileSource, AutoCloseable {

    /** Where tiles come from: TGMPipe in production, a stub in tests. */
    @FunctionalInterface
    public interface TileFetcher {
        CompletableFuture<TerrainTile> fetch(long seed, int tileX, int tileZ, int lod, int priority);
    }

    private record TileKey(int tileX, int tileZ) {}

    /** A tile request and the most urgent priority it has been asked for at. */
    private record Pending(CompletableFuture<TerrainTile> future, int priority) {}

    /** Priority the current thread's work asked for; see {@link #deferred}. */
    private static final ThreadLocal<Integer> DEFERRED_PRIORITY = new ThreadLocal<>();

    private final TileFetcher fetcher;
    private final long seed;
    private final int lod;
    private final int priority;
    private final int tileSize;
    private volatile int maxCachedTiles;
    private final ConcurrentHashMap<TileKey, Pending> tiles = new ConcurrentHashMap<>();
    private final LinkedHashMap<TileKey, Boolean> lru = new LinkedHashMap<>(16, 0.75f, true);
    /** Recently evicted keys (guarded by lruLock): a miss on one of them is a thrash miss. */
    private final java.util.LinkedHashSet<TileKey> ghosts = new java.util.LinkedHashSet<>();
    /** Adaptive growth ceiling (0 = fixed size): each thrash miss grows the cache by {@link #GROW_STEP} up to it. */
    private volatile int growLimit;
    static final int GROW_STEP = 4;
    private final Object lruLock = new Object();
    private volatile boolean closed;

    /** Full-detail tiles for the world's chunks (1:4). */
    public DiffusionTileCache(long seed) {
        this(seed, 1, TGMPipe.PRIORITY_WORLD);
    }

    /** Full-detail tiles for the chunks of a belts world at {@code scale}. */
    public DiffusionTileCache(long seed, TerrainScalePreset scale) {
        this(seed, scale, ClimateLayout.DEFAULT);
    }

    /** Full-detail tiles for the chunks of a world at {@code scale} with the climate {@code layout}. */
    public DiffusionTileCache(long seed, TerrainScalePreset scale, ClimateLayout layout) {
        this(seed, scale, layout, null);
    }

    /**
     * Full-detail tiles for the chunks of a world at {@code scale} with the climate {@code layout}, created with the
     * height curve {@code curve} (null = {@link HeightCurveVersion#CURRENT}; a saved world passes its own).
     */
    public DiffusionTileCache(long seed, TerrainScalePreset scale, ClimateLayout layout, HeightCurveVersion curve) {
        this(seed, 1, TGMPipe.PRIORITY_WORLD, scale, layout, curve);
    }

    /**
     * @param lod world blocks per sample (a power of two). Above 1, every coordinate given or
     *            returned is in SAMPLE units (world blocks / lod): the terrain mapper zoomed out.
     */
    public DiffusionTileCache(long seed, int lod, int priority) {
        this(seed, lod, priority, TerrainScalePreset.ONE_TO_FOUR);
    }

    /** {@link #DiffusionTileCache(long, int, int)} built at {@code scale}: its blocks, height curve and cache. */
    public DiffusionTileCache(long seed, int lod, int priority, TerrainScalePreset scale) {
        this(seed, lod, priority, scale, ClimateLayout.DEFAULT);
    }

    /**
     * {@link #DiffusionTileCache(long, int, int, TerrainScalePreset)} for a world with the climate {@code layout}
     * (its own handshake world, so its tiles never mix with another layout's).
     */
    public DiffusionTileCache(long seed, int lod, int priority, TerrainScalePreset scale, ClimateLayout layout) {
        this(seed, lod, priority, scale, layout, null);
    }

    /** {@link #DiffusionTileCache(long, int, int, TerrainScalePreset, ClimateLayout)} with the height curve {@code curve}. */
    public DiffusionTileCache(long seed, int lod, int priority, TerrainScalePreset scale, ClimateLayout layout,
                              HeightCurveVersion curve) {
        this(seed, lod, priority, scale, layout, curve, Integer.getInteger("stonebreak.tgmpipe.maxCachedTiles", 64));
    }

    /** {@link #DiffusionTileCache(long, int, int, TerrainScalePreset, ClimateLayout, HeightCurveVersion)} keeping at
     *  most {@code maxCachedTiles} resolved tiles (the coarse-LOD caches keep fewer: their tiles cover lod^2 more). */
    public DiffusionTileCache(long seed, int lod, int priority, TerrainScalePreset scale, ClimateLayout layout,
                              HeightCurveVersion curve, int maxCachedTiles) {
        this((s, x, z, l, p) -> TGMPipe.getInstance().requestTile(s, x, z, l, p, scale, layout, curve), seed, lod,
                priority, TerrainScale.TILE_SIZE_BLOCKS, maxCachedTiles);
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
        int want = effectivePriority();
        TileKey key = new TileKey(Math.floorDiv(worldX, tileSize), Math.floorDiv(worldZ, tileSize));
        Pending pending = tiles.computeIfAbsent(key, k -> {
            noteMiss(k);
            return new Pending(fetcher.fetch(seed, k.tileX(), k.tileZ(), lod, want), want);
        });
        if (want < pending.priority() && !pending.future().isDone()) {
            expedite(key, pending, want);
        }
        CompletableFuture<TerrainTile> future = pending.future();
        future.whenComplete((tile, err) -> {
            if (err != null) {
                tiles.computeIfPresent(key, (k, p) -> p.future() == future ? null : p);
            } else {
                touch(key);
            }
        });
        if (closed) {
            withdraw(); // raced close(): don't leave this one running for nobody
        }
        return future;
    }

    /**
     * Runs {@code work} with every tile it misses requested at {@code priority} or later — never
     * sooner than a cache's own priority. For work that can wait behind chunk generation.
     */
    public static <T> T deferred(int priority, Supplier<T> work) {
        Integer outer = DEFERRED_PRIORITY.get();
        DEFERRED_PRIORITY.set(priority);
        try {
            return work.get();
        } finally {
            if (outer == null) {
                DEFERRED_PRIORITY.remove();
            } else {
                DEFERRED_PRIORITY.set(outer);
            }
        }
    }

    private int effectivePriority() {
        Integer deferred = DEFERRED_PRIORITY.get();
        return deferred == null ? priority : Math.max(priority, deferred);
    }

    /**
     * Asks again for a tile still queued at a less urgent priority. The service keeps one job per
     * tile and moves it up for the new request; whichever answer lands first completes the
     * shared future, so nobody already waiting on it has to re-ask.
     */
    private void expedite(TileKey key, Pending pending, int want) {
        if (!tiles.replace(key, pending, new Pending(pending.future(), want))) {
            return; // someone else already expedited, completed or evicted it
        }
        fetcher.fetch(seed, key.tileX(), key.tileZ(), lod, want).whenComplete((tile, err) -> {
            if (err == null) {
                pending.future().complete(tile);
            }
        });
    }

    private void touch(TileKey key) {
        java.util.List<TileKey> evicted = null;
        synchronized (lruLock) {
            lru.put(key, Boolean.TRUE);
            Iterator<Map.Entry<TileKey, Boolean>> it = lru.entrySet().iterator();
            while (lru.size() > maxCachedTiles && it.hasNext()) {   // a loop: the limit may have just shrunk
                TileKey k = it.next().getKey();
                if (k.equals(key)) continue;
                it.remove();
                if (evicted == null) evicted = new java.util.ArrayList<>(1);
                evicted.add(k);
                if (growLimit > 0) {
                    ghosts.add(k);
                }
            }
            if (ghosts.size() > 2 * maxCachedTiles) {
                Iterator<TileKey> g = ghosts.iterator();
                for (int drop = ghosts.size() - 2 * maxCachedTiles; drop > 0 && g.hasNext(); drop--) {
                    g.next();
                    g.remove();
                }
            }
        }
        if (evicted != null) {
            evicted.forEach(tiles::remove);
        }
    }

    /**
     * Resizes the cache (2026-11-20): the tiles a sweep over the area its users cover needs at once. Below that an LRU
     * misses on nearly every request (FastLOD's ring sweeps round the player nearest-first, cube generation spirals):
     * a 64-tile lod-1 cache under an Ultra ring re-read ~100 tiles per second from the disk tile cache, ~190 MB/s of
     * fresh tile arrays. Shrinking evicts the least recent tiles on the next insertion.
     */
    public void setMaxCachedTiles(int tiles) {
        this.maxCachedTiles = Math.max(1, tiles);
    }

    /**
     * Lets the cache grow by itself, up to {@code limit} tiles (0 = fixed size): a request for a tile it evicted
     * recently (it remembers twice its size of evicted keys) means the working set is larger than the cache, so it
     * grows by {@link #GROW_STEP}. Measured 2026-11-20: a ring-sized lod-1 cache still re-read every tile ~19 times
     * when FastLOD's nearest-first sweep and cube generation interleaved; with room for the real working set every
     * tile was read once.
     */
    public void setGrowLimit(int limit) {
        this.growLimit = Math.max(0, limit);
        if (limit <= 0) {
            synchronized (lruLock) {
                ghosts.clear();
            }
        }
    }

    private void noteMiss(TileKey key) {
        int limit = growLimit;
        if (limit <= 0) {
            return;
        }
        synchronized (lruLock) {
            if (ghosts.remove(key) && maxCachedTiles < limit) {
                maxCachedTiles = Math.min(limit, maxCachedTiles + GROW_STEP);
            }
        }
    }

    public int maxCachedTiles() {
        return maxCachedTiles;
    }

    private void withdraw() {
        TileRequestCancelledException cancelled = new TileRequestCancelledException("tile cache closed");
        tiles.values().forEach(p -> p.future().completeExceptionally(cancelled));
        tiles.clear();
    }

    @Override
    public void close() {
        closed = true;
        withdraw();
    }
}
