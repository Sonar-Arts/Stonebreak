package com.stonebreak.world.generation.water;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Native rivers-and-lakes pass over raw bridge tiles: decorates a
 * {@link TerrainTileSource} so every tile it serves carries carved heights and
 * per-column inland water levels computed by Cenda's {@code ck_carve_water}
 * kernel (noise channels + canonical-lattice lakes + the containment repair)
 * instead of the bridge's hydrological L0/L1 solve.
 *
 * <p>Why here: every consumer of terrain data — chunk heights, water levels,
 * biomes, the cave carvers' water guard, FastLOD sampling, spawn search — reads
 * through {@code TerrainTileSource.getTile}, so hydrating at this one choke
 * point keeps all of them consistent with zero further plumbing. The kernel
 * reads a 3x3 window of RAW tiles (one-tile halo) and emits the center tile;
 * raw tiles are deterministic per seed and the kernel is seam-free by
 * construction, so a hydrated column has the same value whichever tile
 * computed it.
 *
 * <p>The cost model this replaces: the bridge's hydrology needed native-
 * resolution elevation over ~150 Mpx macro-windows from the GPU diffusion
 * model (~90 s per cold region, ~20 min worst case at an unsolved four-region
 * corner). This pass needs only the neighbor tiles the player is about to walk
 * into anyway, plus ~10-25 ms of kernel time per tile.
 *
 * <p>Backend gate: {@code -Dstonebreak.water.backend=native|bridge}, default
 * native. With {@code bridge}, this wrapper is not installed and the terrain
 * services run with their hydrology enabled (the old path, unchanged). With
 * native selected but the kernels library absent, tiles pass through raw —
 * sea-level-only water, logged once — rather than failing world load.
 */
public final class NativeWaterTiles implements TerrainTileSource {

    private static final Logger LOG = Logger.getLogger(NativeWaterTiles.class.getName());

    /** {@code -Dstonebreak.water.backend}: {@code native} (default) or {@code bridge}. */
    public static boolean nativeBackendSelected() {
        return !"bridge".equalsIgnoreCase(System.getProperty("stonebreak.water.backend", "native"));
    }

    private record TileKey(int tileX, int tileZ) {}

    private final TerrainTileSource raw;
    private final long seed;
    private final int tileSize;
    private final int maxCachedTiles;
    private final ConcurrentHashMap<TileKey, CompletableFuture<TerrainTile>> tiles = new ConcurrentHashMap<>();
    private final LinkedHashMap<TileKey, Boolean> lru = new LinkedHashMap<>(16, 0.75f, true);
    private final Object lruLock = new Object();
    private volatile boolean warnedUnavailable;

    public NativeWaterTiles(TerrainTileSource raw, long seed, int tileSize, int maxCachedTiles) {
        this.raw = raw;
        this.seed = seed;
        this.tileSize = tileSize;
        this.maxCachedTiles = Math.max(9, maxCachedTiles);
    }

    @Override
    public TerrainTile getTile(int worldX, int worldZ) {
        TileKey key = new TileKey(Math.floorDiv(worldX, tileSize), Math.floorDiv(worldZ, tileSize));
        CompletableFuture<TerrainTile> future =
            tiles.computeIfAbsent(key, k -> new CompletableFuture<>());
        if (!future.isDone()) {
            // First caller (or a concurrent one) computes; completeSupplied is
            // idempotent so a race costs at most one redundant hydration of the
            // same deterministic result.
            try {
                future.complete(hydrate(key));
            } catch (RuntimeException e) {
                // Never cache a failure: drop the slot so the next probe retries.
                tiles.remove(key, future);
                future.completeExceptionally(e);
            }
        }
        try {
            TerrainTile tile = future.join();
            touch(key);
            return tile;
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private TerrainTile hydrate(TileKey key) {
        TerrainTile center = rawTile(key.tileX, key.tileZ);
        if (!CendaKernels.isAvailable()) {
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                LOG.warning("Cenda kernels unavailable: native water backend selected but "
                    + "ck_carve_water cannot run; tiles pass through with sea-level-only water. "
                    + "Build the release preset (openmason-engine/cenda) or set "
                    + "-Dstonebreak.water.backend=bridge.");
            }
            return center;
        }

        int t = tileSize;
        int w = 3 * t;
        short[] window = new short[w * w];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                TerrainTile tile = (dx == 0 && dz == 0)
                    ? center : rawTile(key.tileX + dx, key.tileZ + dz);
                short[] heights = tile.blockHeights();
                int rowBase = (dx + 1) * t;
                int colBase = (dz + 1) * t;
                for (int r = 0; r < t; r++) {
                    System.arraycopy(heights, r * t, window, (rowBase + r) * w + colBase, t);
                }
            }
        }

        short[] outHeights = new short[t * t];
        short[] outWater = new short[t * t];
        int rc = CendaKernels.carveWater(seed, t,
            key.tileX * t, key.tileZ * t, window,
            WorldConfiguration.SEA_LEVEL, WorldConfiguration.WORLD_HEIGHT,
            null, outHeights, outWater);
        if (rc != 0) {
            // MIN_VALUE = library vanished mid-run; negatives = bad-args bugs.
            // Either way raw passthrough beats a failed world load, loudly.
            LOG.warning("ck_carve_water returned " + rc + " for tile ("
                + key.tileX + "," + key.tileZ + "); serving the raw tile");
            return center;
        }
        return new TerrainTile(
            center.tileX(), center.tileZ(),
            center.worldI1(), center.worldJ1(), center.worldI2(), center.worldJ2(),
            center.width(), center.height(),
            outHeights, center.biomeIds(), outWater);
    }

    private TerrainTile rawTile(int tileX, int tileZ) {
        return raw.getTile(tileX * tileSize, tileZ * tileSize);
    }

    private void touch(TileKey key) {
        TileKey evicted = null;
        synchronized (lruLock) {
            lru.put(key, Boolean.TRUE);
            if (lru.size() > maxCachedTiles) {
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
}
