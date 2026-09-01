package com.stonebreak.world.generation;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded cache of per-chunk carved surface profiles — the topmost solid block of every
 * column, after the carve masks and {@code Density3D} have had their say.
 *
 * <p>Building one profile costs a full carve-mask build for the chunk, and the ravine
 * carver alone scans a 69x69 window of source chunks to do it. FastLOD would otherwise pay
 * that repeatedly: the sampler probes a padded grid that spills into the neighbouring
 * chunks, so a single coarse node touches nine chunks for its nine samples, and every one
 * of those neighbours is itself a node that will ask again. Caching collapses ring fill to
 * roughly one build per chunk.
 *
 * <p>A profile is a pure function of the world seed and the chunk coordinate, so entries
 * are never invalidated — only evicted. Concurrent callers for the same chunk share one
 * build rather than racing to do it twice, which matters because the FastLOD worker pool
 * hands adjacent nodes to different threads. The dedupe deliberately goes through an
 * incomplete future rather than {@code computeIfAbsent}: the build is milliseconds of work
 * and holding a map bin for it would stall unrelated chunks that hash to the same bin.
 */
final class SurfaceProfileCache {

    /** Builds the profile for one chunk. Must be deterministic and thread-safe. */
    @FunctionalInterface
    interface Builder {
        Profile build(int chunkX, int chunkZ);
    }

    /**
     * One chunk's carved surface, indexed {@code [x * CHUNK_SIZE + z]}.
     *
     * @param surfaceY one past the highest solid block of each column
     * @param carved   whether anything actually cut that column down from its raw height.
     *                 Kept alongside the height because a heightfield LOD cell covers up to
     *                 16x16 columns and needs to tell "part of this cell is a cave mouth"
     *                 from "part of this cell is downhill" — the heights alone cannot.
     */
    record Profile(int[] surfaceY, boolean[] carved) {}

    /**
     * Sized to the largest ring the settings allow, because a miss is not a cheap
     * re-read — it is a full carve-mask rebuild, measured at ~3.5 ms, and it is ~100%
     * of what a FastLOD node costs (a node whose nine chunk profiles are all cached
     * builds in under 0.1 ms).
     *
     * <p>At the default render distance 8 / LOD range 24 the ring is 4056 chunks and the
     * old 8192-entry ceiling was ample. At the maximums the sliders offer — render
     * distance 24, LOD range 48 — it is 145x145 = 21025 chunks, so the ring did not fit
     * and walking across it evicted profiles that were still being probed, turning the
     * steady-state cost of the far bands back into full rebuilds. Entries are only ever
     * allocated on demand, so the higher ceiling costs nothing at default settings and
     * ~24 MB of {@code int[256]} at the maximums.
     */
    private static final int MAX_ENTRIES = 24576;

    private final Builder builder;
    private final ConcurrentHashMap<Long, CompletableFuture<Profile>> profiles = new ConcurrentHashMap<>();
    private final LinkedHashMap<Long, Boolean> lru = new LinkedHashMap<>(16, 0.75f, true);
    private final Object lruLock = new Object();

    SurfaceProfileCache(Builder builder) {
        this.builder = builder;
    }

    /** Profile for a chunk, indexed {@code [x * CHUNK_SIZE + z]}. Never null. */
    Profile get(int chunkX, int chunkZ) {
        Long key = key(chunkX, chunkZ);
        CompletableFuture<Profile> future = profiles.get(key);
        if (future == null) {
            CompletableFuture<Profile> mine = new CompletableFuture<>();
            future = profiles.putIfAbsent(key, mine);
            if (future == null) {
                future = mine;
                try {
                    mine.complete(builder.build(chunkX, chunkZ));
                } catch (RuntimeException | Error e) {
                    // Failures are not cached: the next probe gets a fresh attempt rather
                    // than being stuck behind a stale bridge or interrupt failure.
                    profiles.remove(key, mine);
                    mine.completeExceptionally(e);
                    throw e;
                }
            }
        }
        touch(key);
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    private void touch(Long key) {
        Long evicted = null;
        synchronized (lruLock) {
            lru.put(key, Boolean.TRUE);
            if (lru.size() > MAX_ENTRIES) {
                Iterator<Map.Entry<Long, Boolean>> it = lru.entrySet().iterator();
                if (it.hasNext()) {
                    evicted = it.next().getKey();
                    it.remove();
                }
            }
        }
        if (evicted != null) {
            profiles.remove(evicted);
        }
    }

    private static Long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
