package com.stonebreak.world.generation.water;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Native lakes-and-rivers pass over raw bridge tiles: decorates a
 * {@link TerrainTileSource} so every tile it serves carries heights and
 * per-column inland water levels stamped by Cenda's {@code ck_carve_water}
 * kernel from the depression fill {@link BasinCache} holds for the region that
 * owns the tile.
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
 * corner). This pass needs the neighbor tiles the player is about to walk into
 * anyway, plus one 23 ms depression fill per 4096-block region — 0.089 ms
 * amortized over that region's 256 tiles, and cached to disk after the first.
 *
 * <p><b>Two caches, not one.</b> The basin solve is per REGION and the stamp is
 * per TILE, so this class keeps its hydrated tiles and {@link BasinCache} keeps
 * its solved regions. Sixteen tiles a side share one region's planes, which is
 * exactly why the solve can afford to be thorough.
 *
 * <p><b>Rivers arrive late, on purpose.</b> The seam-freedom above is a
 * property of the FINISHED article, and a tile is not served finished. Lakes
 * are: they come from the planes of the region owning the tile, and this waits
 * for those. Rivers are gathered from every region within a halo — four L1
 * regions for any tile, nine near a corner — and waiting for all of them is
 * what made the first tile of a cold world cost 36 coarse chunks against the
 * owner's 16. So the gather takes only the regions already to hand
 * ({@link BasinCache#solvedIfReady}) and the rest arrive behind it, dropping
 * and restamping the tiles they affect. Until a region lands, two adjacent
 * tiles CAN disagree about a river crossing them; once it has, they cannot.
 * The bound on that is {@link #dropRegion}, and the residual cost is a chunk
 * generated inside the gap, which keeps its terrain until it is regenerated.
 *
 * <p>Backend gate: {@code -Dstonebreak.water.backend=native|bridge}, default
 * native. With {@code bridge}, this wrapper is not installed and the terrain
 * services run with their hydrology enabled (the old path, unchanged). With
 * native selected but the kernels library absent — or with no basin cache
 * wired — tiles get sea-level-only water, logged once, rather than failing
 * world load.
 */
public final class NativeWaterTiles implements TerrainTileSource, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(NativeWaterTiles.class.getName());

    /** {@code -Dstonebreak.water.backend}: {@code native} (default) or {@code bridge}. */
    public static boolean nativeBackendSelected() {
        return !"bridge".equalsIgnoreCase(System.getProperty("stonebreak.water.backend", "native"));
    }

    private record TileKey(int tileX, int tileZ) {}

    private final TerrainTileSource raw;
    private final BasinCache basins;
    private final long seed;
    private final int tileSize;
    private final int maxCachedTiles;
    private final ConcurrentHashMap<TileKey, CompletableFuture<TerrainTile>> tiles = new ConcurrentHashMap<>();
    private final LinkedHashMap<TileKey, Boolean> lru = new LinkedHashMap<>(16, 0.75f, true);
    private final Object lruLock = new Object();
    private volatile boolean warnedUnavailable;
    private volatile boolean warnedNoBasins;

    /* Blocks beyond the window within which a route still changes something in
     * it: the kernel's valley radius plus the widest channel it can carve. */
    private static final float RIVER_GATHER_PAD = 128.0f;

    /**
     * @param basins the depression fill to stamp from, or null for sea-level-only
     *               water (no bridge reachable, or the caller opted out)
     */
    public NativeWaterTiles(TerrainTileSource raw, BasinCache basins, long seed,
                            int tileSize, int maxCachedTiles) {
        this.raw = raw;
        this.basins = basins;
        this.seed = seed;
        this.tileSize = tileSize;
        this.maxCachedTiles = Math.max(9, maxCachedTiles);
        if (basins != null) {
            // A provisional region is stamped without the lakes only a wider
            // rung can vouch for. When that rung lands, the tiles stamped from
            // it are stale — drop them so the next ask restamps against the
            // finished planes. Dropping is enough: nothing downstream holds a
            // TerrainTile past the call that asked for it.
            basins.onRegionUpgraded(this::dropRegion);
        }
        if (tileSize % BasinCache.CELL_BLOCKS != 0) {
            throw new IllegalArgumentException("tile size " + tileSize + " must be a whole number "
                + "of " + BasinCache.CELL_BLOCKS + "-block DEM cells, or a tile's window cannot "
                + "be addressed on the cell lattice");
        }
    }

    @Override
    public TerrainTile getTile(int worldX, int worldZ) {
        TileKey key = new TileKey(Math.floorDiv(worldX, tileSize), Math.floorDiv(worldZ, tileSize));
        // Exactly one thread hydrates a given tile; the rest wait on its future.
        //
        // The obvious shape — computeIfAbsent, then "if (!future.isDone())
        // complete(hydrate(key))" — makes EVERY thread that arrives before the
        // first one finishes run the whole hydration. complete() being
        // idempotent only makes the stored RESULT single-valued; it does not
        // stop the work. A hydration is nine raw-tile fetches, a river gather
        // across up to nine regions and a native carve, so a dozen chunk
        // threads landing on one cold tile paid for it a dozen times. The
        // creator is elected inside computeIfAbsent instead (the mapping
        // function only allocates, so it is safe to run under the bin lock).
        boolean[] mine = {false};
        CompletableFuture<TerrainTile> future = tiles.computeIfAbsent(key, k -> {
            mine[0] = true;
            return new CompletableFuture<>();
        });
        if (mine[0]) {
            try {
                future.complete(hydrate(key));
            } catch (RuntimeException | Error e) {
                // Never cache a failure: drop the slot so the next probe
                // retries. Completing exceptionally as well as removing is what
                // releases the waiters already parked on this future — dropping
                // it alone would leave them blocked on a join() nobody finishes.
                tiles.remove(key, future);
                future.completeExceptionally(e);
                throw e;
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
            if (e.getCause() instanceof Error err) {
                throw err;
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

        int originX = (key.tileX - 1) * t;
        int originZ = (key.tileZ - 1) * t;
        int demCells = w / BasinCache.CELL_BLOCKS;
        float[] demFilled = null;
        float[] demDepth = null;
        RiverSpan rivers = RiverSpan.EMPTY;
        if (basins != null) {
            // The 3x3 window reaches at most one tile beyond the region that
            // owns the center tile, and a region's window carries a 2048-block
            // halo, so one region's planes always cover the whole span.
            BasinCache.Solved region = basins.forColumn(key.tileX * (long) t, key.tileZ * (long) t);
            demFilled = new float[demCells * demCells];
            demDepth = new float[demCells * demCells];
            region.copySpan(originX, originZ, demCells, demFilled, demDepth);
            rivers = gatherRivers(originX, originZ, w);
        } else if (!warnedNoBasins) {
            warnedNoBasins = true;
            LOG.warning("no BasinCache wired: tiles get sea-level-only water with no lakes.");
        }

        short[] outHeights = new short[t * t];
        short[] outWater = new short[t * t];
        int rc = CendaKernels.carveWater(seed, t,
            originX, originZ, window,
            WorldConfiguration.SEA_LEVEL, WorldConfiguration.WORLD_HEIGHT,
            demCells, BasinCache.CELL_BLOCKS, demFilled, demDepth,
            rivers.routeCount(), rivers.starts(), rivers.vertices(),
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

    /** Routes near one tile's window, packed the way the kernel wants them. */
    private record RiverSpan(int routeCount, int[] starts, float[] vertices) {
        static final RiverSpan EMPTY = new RiverSpan(0, new int[]{0}, new float[0]);
    }

    /**
     * Every river route that can reach this window, gathered from the regions
     * that own them.
     *
     * <p>A route belongs to the region owning its SOURCE, so no two regions
     * plan the same river — but a river does not stop at a region border, and
     * a tile near one is crossed by water sourced next door. A route reaches at
     * most a halo from its own region (the kernel derives its step budget from
     * exactly that, so it cannot run further), which bounds the search: only
     * regions whose span expanded by one halo touches this window can matter.
     * In the middle of a region that is one region; near a corner it is four.
     *
     * <p>Solving a neighbour costs a region solve on a cold cache, which is why
     * the bound is worth computing rather than always sweeping 3x3.
     */
    private RiverSpan gatherRivers(int originX, int originZ, int windowBlocks) {
        int region = BasinCache.Level.L1.regionBlocks();
        int halo = BasinCache.Level.L1.haloBlocks();
        long lo = originX - halo;
        long hi = originX + windowBlocks + halo;
        long r0x = Math.floorDiv(lo, region);
        long r1x = Math.floorDiv(hi, region);
        long r0z = Math.floorDiv(originZ - (long) halo, region);
        long r1z = Math.floorDiv(originZ + windowBlocks + (long) halo, region);

        // Only routes that come within a valley radius of the window change
        // anything here; the rest belong to ground another tile emits.
        float pad = RIVER_GATHER_PAD;
        float wx0 = originX - pad;
        float wx1 = originX + windowBlocks + pad;
        float wz0 = originZ - pad;
        float wz1 = originZ + windowBlocks + pad;

        List<int[]> spans = new ArrayList<>();
        List<float[]> chunks = new ArrayList<>();
        int total = 0;
        for (long rx = r0x; rx <= r1x; rx++) {
            for (long rz = r0z; rz <= r1z; rz++) {
                // Only what is already to hand. A region still being solved is
                // started in the background and skipped here; when it lands it
                // announces itself and this tile is dropped and restamped. The
                // owner region is always in this loop and always resident by
                // now — forColumn just solved it — so the tile is never served
                // without the rivers of the ground it actually sits on.
                BasinCache.Solved s = basins.solvedIfReady(BasinCache.Level.L1, rx, rz);
                if (s == null) {
                    continue;
                }
                int stride = CendaKernels.RIVER_VERTEX_FLOATS;
                for (int r = 0; r < s.routeCount(); r++) {
                    int from = s.routeStarts()[r];
                    int to = s.routeStarts()[r + 1];
                    boolean near = false;
                    for (int v = from; v < to && !near; v++) {
                        float x = s.vertices()[v * stride];
                        float z = s.vertices()[v * stride + 1];
                        near = x >= wx0 && x <= wx1 && z >= wz0 && z <= wz1;
                    }
                    if (!near) {
                        continue;
                    }
                    spans.add(new int[]{from, to});
                    chunks.add(s.vertices());
                    total += to - from;
                }
            }
        }
        if (spans.isEmpty()) {
            return RiverSpan.EMPTY;
        }
        int stride = CendaKernels.RIVER_VERTEX_FLOATS;
        int[] starts = new int[spans.size() + 1];
        float[] verts = new float[total * stride];
        int at = 0;
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            int count = span[1] - span[0];
            System.arraycopy(chunks.get(i), span[0] * stride, verts, at * stride, count * stride);
            at += count;
            starts[i + 1] = at;
        }
        return new RiverSpan(spans.size(), starts, verts);
    }

    /**
     * Forget every hydrated tile that read {@code region} — its planes or its
     * rivers.
     *
     * <p><b>Rivers set the radius, not the planes.</b> A tile reads the PLANES
     * of the region owning its centre, so those reach one tile past the
     * region's own rectangle (the window overhangs by a tile). But a tile also
     * gathers ROUTES from every region within a halo, because a river does not
     * stop at a region border — so a region that has just been solved can
     * invalidate tiles a full halo outside itself, eight tiles at L1's 2048
     * against a 256-block tile. Dropping only the rectangle plus a ring, which
     * is what the planes need, would leave those stale: they were stamped
     * without this region's rivers and nothing would ever tell them.
     *
     * <p>Erring wide is nearly free — the sweep walks the resident tiles either
     * way, and a tile dropped needlessly costs one restamp — while erring
     * narrow leaves a river with a seam in it for the life of the session.
     */
    private void dropRegion(BasinCache.Solved region) {
        long lo = region.regionX() * (long) region.level().regionBlocks();
        long loZ = region.regionZ() * (long) region.level().regionBlocks();
        long hi = lo + region.level().regionBlocks();
        long hiZ = loZ + region.level().regionBlocks();
        // The furthest ground this region can have changed: its own rectangle
        // grown by the reach of a route sourced inside it. A tile's window runs
        // from (t-1)*tileSize to (t+2)*tileSize, hence the extra tile at each
        // end of the conversion.
        int halo = region.level().haloBlocks();
        int t0x = (int) Math.floorDiv(lo - halo, tileSize) - 2;
        int t1x = (int) Math.floorDiv(hi - 1 + halo, tileSize) + 2;
        int t0z = (int) Math.floorDiv(loZ - halo, tileSize) - 2;
        int t1z = (int) Math.floorDiv(hiZ - 1 + halo, tileSize) + 2;
        int dropped = 0;
        for (java.util.Iterator<TileKey> it = tiles.keySet().iterator(); it.hasNext(); ) {
            TileKey k = it.next();
            if (k.tileX() >= t0x && k.tileX() <= t1x && k.tileZ() >= t0z && k.tileZ() <= t1z) {
                it.remove();
                synchronized (lruLock) {
                    lru.remove(k);
                }
                dropped++;
            }
        }
        if (dropped > 0) {
            LOG.info("basin " + region.level() + " region (" + region.regionX() + ","
                + region.regionZ() + ") landed; dropped " + dropped
                + " tile(s) stamped before it was available");
        }
    }

    /**
     * Releases the {@link BasinCache} this wrapper owns, and with it the two
     * background threads it runs.
     *
     * <p>Both are daemons, so leaking them never blocked JVM exit — which is
     * why nothing called this for a while. It still leaked a pair of threads
     * and a region cache per world load and per terrain-mapper seed change,
     * and those add up over a session.
     *
     * <p>Idempotent, and safe to call while tiles are still being served: the
     * hydrations already in flight finish against whatever the cache has, and
     * anything asked for afterwards fails loudly rather than silently serving
     * water-free terrain.
     */
    @Override
    public void close() {
        if (basins != null) {
            basins.close();
        }
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
