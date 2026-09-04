package com.stonebreak.world.generation.water;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.world.generation.diffusion.DiffusionBridgeConfig;
import com.stonebreak.world.operations.WorldConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Per-region depression-fill solutions: the lakes, and the surface rivers will
 * descend. Memory-cached, disk-cached, and computed by
 * {@code ck_solve_basins} (Dev Working/Lakes-first hydrology plan.md §4).
 *
 * <p><b>Why regions.</b> The solve costs ~23 ms over the 512² window L1 uses.
 * Run per terrain tile that would dominate world generation; run once per
 * 4096-block region it is 0.089 ms amortized over that region's 256 tiles.
 * The region is therefore the unit of work, of caching and of ownership: a
 * column belongs to exactly one region by integer division of its world
 * coordinates, only that region emits it, so two regions never both compute
 * the same column and there is nothing for them to disagree about.
 *
 * <p><b>Why escalation is lazy and one rung at a time.</b> A basin wider than a
 * level's halo is not that level's to emit — a window one region over would cut
 * it differently and the two would disagree across the seam — so the level
 * withholds it and a wider one owns it instead. A wider rung is expensive: its
 * window grows 4x in area per rung, and the widest is 65,536 blocks a side,
 * which is 1,024 {@link CoarseDem} chunks at a measured 1.40 s each — 24
 * minutes of GPU, serial. Escalating straight there for a basin barely past
 * L1's limit is what made a world load unusable (measured 2026-09-03), and it
 * is the same failure that made the old hydrology a 20-minute load. So a region
 * escalates <b>only</b> on evidence (a non-zero withheld count) and <b>only one
 * rung</b>, repeating if that rung also withholds. On measured terrain the
 * largest basin spans 928 blocks against L1's 2,048, so the usual answer is
 * "withheld nothing" and every rung past L1 stays cold.
 *
 * <p><b>And it never blocks.</b> Even one rung is a minute or more of GPU,
 * which no chunk thread should wait on. {@link #solved} hands back the rung it
 * has — complete and seam-free, just missing the lakes a wider window would
 * vouch for — and finishes the escalation on a background thread, announcing it
 * through {@link #onRegionUpgraded} so tiles stamped from the provisional
 * planes can be dropped and restamped. Water appears a minute into play instead
 * of the world not appearing at all.
 *
 * <p><b>Why the fingerprint covers every rung.</b> The old L0/L1 hydrology
 * shipped a bug where {@code L1Params} did not hash L0's knobs, so L1 tiles
 * outlived an L0 retune and the two levels disagreed about lakes that spanned
 * them. Here one fingerprint covers every parameter of every rung and names the
 * cache directory, so retuning any rung — or inserting one — orphans the whole
 * ladder's caches rather than mixing them.
 *
 * <p>A cached solution is two float planes: {@code filled}, the complete
 * depression-filled surface, and {@code depth}, which is greater than zero
 * exactly where this level has a lake it may vouch for. That is the whole
 * protocol — ownership has already been applied inside the kernel, so no basin
 * table needs to cross out of C++ and a consumer needs none to use the planes.
 */
public final class BasinCache {

    private static final Logger LOG = Logger.getLogger(BasinCache.class.getName());

    /** Bumped when the stored payload's meaning changes. Part of the header. */
    private static final int DISK_VERSION = 3;
    private static final int HEADER_BYTES = 24;

    /* Caps on one region's river plan. The kernel stops at these rather than
     * overflowing, and a region that hits one is telling us the world is far
     * denser than measured (region (0,0) of the real fixture: 4 routes, ~1,900
     * vertices after refinement). */
    private static final int MAX_ROUTES = 512;
    private static final int MAX_VERTICES = 262144;

    /**
     * DEM resolution, in blocks. Public because every consumer of a solved
     * region has to address it on the same lattice — {@code ck_carve_water}'s
     * span, a tile's window, this cache's own windows — and a second copy of
     * the number is a seam waiting to happen.
     */
    public static final int CELL_BLOCKS = 16;

    /** Defaults mirroring §8's params table. */
    public static final float DEFAULT_MIN_LAKE_DEPTH = 0.5f;
    public static final int DEFAULT_MIN_LAKE_AREA = 8;
    /* Retuned in phase 10. At §8's 24 cells / 0.45 the one real region the
     * fixture can assemble produced zero rivers; this pair yields four over a
     * 4096-block region, and leaves both knobs with room to move. */
    public static final int DEFAULT_MIN_RIVER_LAKE_AREA = 12;
    public static final float DEFAULT_RIVER_KEEP_FRACTION = 0.70f;

    /**
     * How much of the next rung's own emission floor a withheld lake must be
     * worth before that rung is worth fetching. See {@link #worthEscalating}.
     *
     * <p>0.5 is the exact test with one doubling of headroom for the fact that
     * a truncated window under-measures the lake. 0 restores the old
     * behaviour — escalate on any withheld basin at all.
     */
    public static final float DEFAULT_ESCALATION_STAKE = 0.5f;

    /**
     * §4.4's ownership levels, finest first — an escalation LADDER rather than
     * the original two rungs.
     *
     * <p><b>Why more than two.</b> A level owns basins up to about its halo
     * wide, so a basin one block past L1's 2,016 used to escalate straight to
     * the widest rung there is. That rung's window is 65,536 blocks a side:
     * 1,024 {@link CoarseDem} chunks, measured 2026-09-03 at a steady 1.40 s
     * each on an RTX 5090 — <b>24 minutes of GPU, serial, blocking world
     * load</b>, to own one basin that a 4 km halo would have covered. Each rung
     * here doubles cell, region and halo together, so every window is 512²
     * cells (the solve stays ~23 ms) while the DEM it needs grows 4x a rung:
     *
     * <pre>
     *   rung  cell  region   halo    window   chunks   owns basins up to
     *   L1      16    4096    2048     8192       16      2,016 blocks
     *   L2      32    8192    4096    16384       64      4,032
     *   L3      64   16384    8192    32768      256      8,064
     *   L4     128   32768   16384    65536     1024     16,128
     * </pre>
     *
     * <p>Escalation is one rung at a time and only on evidence (§4.4's withheld
     * count), so the basin that used to cost 1,024 chunks now costs 64 and the
     * widest rung stays cold unless something really is 4 km across. L4 is the
     * old L0 unchanged, so worlds that genuinely need it are no worse off.
     *
     * <p>Cells, region and halo are all in blocks.
     */
    public enum Level {
        L1(CELL_BLOCKS, 4096, 2048),
        L2(2 * CELL_BLOCKS, 8192, 4096),
        L3(4 * CELL_BLOCKS, 16384, 8192),
        L4(8 * CELL_BLOCKS, 32768, 16384);

        final int cellBlocks;
        final int regionBlocks;
        final int haloBlocks;

        Level(int cellBlocks, int regionBlocks, int haloBlocks) {
            this.cellBlocks = cellBlocks;
            this.regionBlocks = regionBlocks;
            this.haloBlocks = haloBlocks;
        }

        /** The next rung out, or null if this is the widest there is. */
        Level coarser() {
            Level[] all = values();
            return ordinal() + 1 < all.length ? all[ordinal() + 1] : null;
        }

        int windowCells() {
            return (regionBlocks + 2 * haloBlocks) / cellBlocks;
        }

        /** Blocks of ground one region owns and emits. */
        public int regionBlocks() {
            return regionBlocks;
        }

        /** Blocks of scaffolding around it — and the furthest a river sourced
         *  inside can reach, which is what bounds a consumer's search. */
        public int haloBlocks() {
            return haloBlocks;
        }

        long regionOf(long world) {
            return Math.floorDiv(world, regionBlocks);
        }

        long originOf(long region) {
            return region * regionBlocks - haloBlocks;
        }
    }

    /**
     * One region's solved window.
     *
     * @param withheld basins this level would not emit; greater than zero means
     *                 the coarser level was consulted (or should be)
     * @param provisional this solve still owes an escalation — it withheld
     *                 basins and the coarser rung has not been imported yet.
     *                 Its planes are valid and seam-free, they are simply
     *                 missing the lakes only a wider window can vouch for, so a
     *                 consumer may use it immediately and will be told to drop
     *                 it when the upgrade lands. Never written to disk.
     */
    public record Solved(Level level, long regionX, long regionZ,
                         long originX, long originZ, int cells,
                         float[] filled, float[] depth, int withheld,
                         int routeCount, int[] routeStarts, float[] vertices,
                         boolean provisional) {

        private int index(long worldX, long worldZ) {
            int i = (int) Math.floorDiv(worldX - originX, level.cellBlocks);
            int j = (int) Math.floorDiv(worldZ - originZ, level.cellBlocks);
            if (i < 0 || i >= cells || j < 0 || j >= cells) {
                throw new IllegalArgumentException("column (" + worldX + "," + worldZ
                    + ") is outside region (" + regionX + "," + regionZ + ")'s window");
            }
            return i * cells + j;
        }

        /** The depression-filled surface at a column: water surface and routing field. */
        public float filledAt(long worldX, long worldZ) {
            return filled[index(worldX, worldZ)];
        }

        /** Lake depth in blocks; 0 where this level has no lake to vouch for. */
        public float depthAt(long worldX, long worldZ) {
            return depth[index(worldX, worldZ)];
        }

        /** Whether a column carries lake this level emits. */
        public boolean isLake(long worldX, long worldZ) {
            return depthAt(worldX, worldZ) > 0.0f;
        }

        /** Vertices in this region's packed river plan. */
        public int vertexCount() {
            return routeCount == 0 ? 0 : routeStarts[routeCount];
        }

        /**
         * Copy the {@code spanCells} square of both planes whose first cell
         * begins at {@code (spanOriginX, spanOriginZ)} — the sub-window
         * {@code ck_carve_water} stamps one tile from.
         *
         * <p>Throws rather than clamping if the span leaves this region's
         * window. Clamping would hand the kernel a plane that is silently dry
         * along one edge, and the tile next door — reading the same ground from
         * a region that does cover it — would disagree. A caller that trips
         * this has a geometry bug, and it should say so.
         */
        public void copySpan(long spanOriginX, long spanOriginZ, int spanCells,
                             float[] outFilled, float[] outDepth) {
            if (outFilled.length != spanCells * spanCells || outDepth.length != spanCells * spanCells) {
                throw new IllegalArgumentException("span buffers must be " + spanCells + "^2 floats");
            }
            long di = Math.floorDiv(spanOriginX - originX, level.cellBlocks);
            long dj = Math.floorDiv(spanOriginZ - originZ, level.cellBlocks);
            if ((spanOriginX - originX) % level.cellBlocks != 0
                    || (spanOriginZ - originZ) % level.cellBlocks != 0) {
                throw new IllegalArgumentException("span origin (" + spanOriginX + ","
                    + spanOriginZ + ") is off the " + level.cellBlocks + "-block cell lattice");
            }
            if (di < 0 || dj < 0 || di + spanCells > cells || dj + spanCells > cells) {
                throw new IllegalArgumentException("span of " + spanCells + " cells at ("
                    + spanOriginX + "," + spanOriginZ + ") leaves region (" + regionX + ","
                    + regionZ + ")'s window");
            }
            for (int i = 0; i < spanCells; i++) {
                int src = (int) (di + i) * cells + (int) dj;
                System.arraycopy(filled, src, outFilled, i * spanCells, spanCells);
                System.arraycopy(depth, src, outDepth, i * spanCells, spanCells);
            }
        }
    }

    private record Key(Level level, long regionX, long regionZ) {}

    /**
     * A key's in-flight slot, split by which answer the caller will accept.
     *
     * <p>{@link SingleFlight} hands a concurrent caller the owner's result, so
     * without this split the escalation thread arriving while a chunk thread is
     * mid-solve would be handed that thread's PROVISIONAL solve, return it
     * without escalating, and drop the key from {@code upgrading} — leaving the
     * region provisional with nothing queued to finish it. Chunk threads dedupe
     * against each other and escalations against each other, and the two never
     * inherit one another's answer.
     */
    private record Flight(Key key, boolean deferEscalation) {}

    private final CoarseDem dem;
    private final long seed;
    private final Path cacheDir;
    private final float minLakeDepth;
    private final int minLakeArea;
    private final float seaLevel;
    private final int minRiverLakeArea;
    private final float riverKeepFraction;
    private final float escalationStake;
    private final Map<Key, Solved> memory;
    private final SingleFlight<Flight, Solved> inFlight = new SingleFlight<>();

    /* Escalation runs off the calling thread so a withheld basin costs the
     * player a few minutes of missing lakes rather than a frozen world load.
     * One thread: the rungs are DEM-fetch bound against a single-threaded
     * upstream, so a second would only interleave two slow solves. */
    private final java.util.Set<Key> upgrading = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService escalator;
    private final boolean asyncEscalation;

    /* Regions nobody is blocked on: a neighbour whose rivers could reach a tile
     * that has already been served without them. Its own thread — see
     * schedulePrefetch. */
    private final java.util.Set<Key> prefetching = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService prefetcher;
    private final boolean asyncRiverGather;
    private volatile java.util.function.Consumer<Solved> upgradeListener = s -> { };

    public BasinCache(CoarseDem dem, long seed, Path cacheRoot,
                      float minLakeDepth, int minLakeArea, float seaLevel,
                      int maxCachedRegions) {
        this(dem, seed, cacheRoot, minLakeDepth, minLakeArea, seaLevel,
             DEFAULT_MIN_RIVER_LAKE_AREA, DEFAULT_RIVER_KEEP_FRACTION, maxCachedRegions);
    }

    public BasinCache(CoarseDem dem, long seed, Path cacheRoot,
                      float minLakeDepth, int minLakeArea, float seaLevel,
                      int minRiverLakeArea, float riverKeepFraction,
                      int maxCachedRegions) {
        this.dem = dem;
        this.seed = seed;
        this.minLakeDepth = minLakeDepth;
        this.minLakeArea = minLakeArea;
        this.seaLevel = seaLevel;
        this.minRiverLakeArea = minRiverLakeArea;
        this.riverKeepFraction = riverKeepFraction;
        this.escalationStake = Math.max(0.0f, Float.parseFloat(System.getProperty(
            "stonebreak.water.escalationStake", Float.toString(DEFAULT_ESCALATION_STAKE))));
        if (dem.cellBlocks() != Level.L1.cellBlocks) {
            throw new IllegalArgumentException("BasinCache needs a " + Level.L1.cellBlocks
                + "-block DEM; CoarseDem serves " + dem.cellBlocks());
        }
        this.cacheDir = cacheRoot == null ? null : cacheRoot.resolve("basins_" + fingerprint());
        this.asyncEscalation = Boolean.parseBoolean(
            System.getProperty("stonebreak.water.asyncEscalation", "true"));
        this.escalator = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "basin-escalation");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        this.asyncRiverGather = Boolean.parseBoolean(
            System.getProperty("stonebreak.water.asyncRiverGather", "true"));
        this.prefetcher = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "basin-prefetch");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        int capacity = Math.max(2, maxCachedRegions);
        this.memory = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Solved> eldest) {
                return size() > capacity;
            }
        };
    }

    /**
     * The production cache: a {@link CoarseDem} on the configured bridge, with
     * solved regions persisted under {@code cache/basins} (overridable with
     * {@code -Dstonebreak.water.basinCacheDir}, or {@code none} to keep the
     * cache in memory only).
     *
     * <p>One per seed. Both places that build the tile chain — the world
     * generator and the terrain mapper's preview — go through here, so the
     * preview shows the same lakes the world has rather than a second opinion.
     */
    public static BasinCache production(DiffusionBridgeConfig config, long seed) {
        String dir = System.getProperty("stonebreak.water.basinCacheDir", "cache/basins");
        Path root = "none".equalsIgnoreCase(dir) ? null : Path.of(dir);
        return new BasinCache(new CoarseDem(config, seed), seed, root,
                DEFAULT_MIN_LAKE_DEPTH, DEFAULT_MIN_LAKE_AREA,
                WorldConfiguration.SEA_LEVEL, 8);
    }

    /**
     * Everything that changes a solved region's contents, hashed into its
     * directory name — <b>both</b> levels' parameters, so a retune of either
     * orphans both rather than letting one outlive the other.
     */
    private String fingerprint() {
        StringBuilder raw = new StringBuilder()
            .append('v').append(DISK_VERSION)
            .append('|').append(seed)
            .append('|').append(minLakeDepth)
            .append('|').append(minLakeArea)
            .append('|').append(seaLevel)
            .append('|').append(minRiverLakeArea)
            .append('|').append(riverKeepFraction)
            // A region gated out of escalating is written to disk as finished,
            // with nothing in the file to say a lower stake would have taken it
            // further. Retuning the gate must therefore orphan those files, the
            // same as retuning a rung's geometry does.
            .append('|').append(escalationStake);
        for (Level level : Level.values()) {
            raw.append('|').append(level.cellBlocks)
               .append(':').append(level.regionBlocks)
               .append(':').append(level.haloBlocks);
        }
        return Long.toHexString(scramble(raw.toString().hashCode())).substring(0, 12);
    }

    private static long scramble(int h) {
        long x = (h & 0xFFFFFFFFL) + 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return (x ^ (x >>> 31)) >>> 4;
    }

    /**
     * The L1 solution owning a column, solved and cached on first ask.
     *
     * <p>If L1 withheld anything the escalation ladder finishes it in the
     * background (see {@link #onRegionUpgraded}); the solve returned here is
     * usable either way.
     */
    public Solved forColumn(long worldX, long worldZ) {
        return solved(Level.L1, Level.L1.regionOf(worldX), Level.L1.regionOf(worldZ));
    }

    /**
     * The solution for one region at one level.
     *
     * <p>A region is solved once even when a dozen chunk threads ask for it at
     * the same instant. The result is a pure function of the key, so a
     * duplicate solve would still be correct — but it re-fetches the region's
     * whole DEM window from the bridge, and a world load that lets every
     * generator thread solve the same region is the slow load this cache exists
     * to prevent. The recursive L0 solve inside {@link #compute} is a different
     * key, so it cannot wait on the L1 solve that asked for it.
     */
    public Solved solved(Level level, long regionX, long regionZ) {
        Solved s = resolve(level, regionX, regionZ, asyncEscalation);
        if (s.provisional()) {
            scheduleUpgrade(new Key(level, regionX, regionZ));
        }
        return s;
    }

    /**
     * The solution for one region <b>only if it is already to hand</b> — in
     * memory or on disk — or null, with the solve started in the background so
     * a later ask can have it.
     *
     * <p><b>Why this exists.</b> A tile needs the DEM planes of the region that
     * owns it, and must wait for those. It also needs every river route that
     * can reach it, and a route reaches a halo beyond its own region, so
     * {@code NativeWaterTiles.gatherRivers} has to consult <b>four</b> L1
     * regions for any tile (nine near a region corner). Waiting for all of them
     * makes the first tile of a cold world cost 36 coarse chunks where the
     * owner alone costs 16 — measured 50-79 s against 22-35 s, all of it before
     * a single block appears.
     *
     * <p>Disk counts as "to hand" deliberately. A warm second visit reads a
     * 2 MB plane in about a millisecond, and blocking for that is both cheap
     * and worth it: it keeps warm loads exactly as correct as they are today,
     * and confines the asynchrony to the case that is actually expensive — a
     * cold region needing its DEM off the GPU.
     *
     * <p><b>What the caller accepts.</b> A tile stamped while a neighbour is
     * still solving is missing that neighbour's rivers. It is dropped and
     * restamped when the solve lands ({@link #onRegionUpgraded}), so every tile
     * asked for after that point is correct — but a chunk already GENERATED
     * from the incomplete tile keeps its terrain until it is unloaded and
     * regenerated, because nothing rebuilds a chunk from a dropped tile. That
     * is the same trade the escalation path above already makes, and it is
     * bounded the same way: such a chunk is not persisted unless the player
     * edits it, so it regenerates correctly next session.
     * {@code -Dstonebreak.water.asyncRiverGather=false} restores waiting.
     */
    public Solved solvedIfReady(Level level, long regionX, long regionZ) {
        if (!asyncRiverGather) {
            return solved(level, regionX, regionZ);
        }
        Key key = new Key(level, regionX, regionZ);
        Solved hit = lookup(key);
        if (hit != null) {
            if (hit.provisional()) {
                scheduleUpgrade(key);
            }
            return hit;
        }
        schedulePrefetch(key);
        return null;
    }

    /**
     * A solve from memory or disk, never computed. Null means "not without a
     * DEM fetch".
     *
     * <p>Not routed through {@link SingleFlight}: the expensive thing it
     * guards is a solve, and this never runs one. Two threads that both miss
     * memory read the same page-cached file and store equal values, which
     * costs a duplicated 2 MB read rather than a duplicated GPU fetch.
     */
    private Solved lookup(Key key) {
        Solved hit = resident(key);
        if (hit != null) {
            return hit;
        }
        Solved fromDisk = readDisk(key);
        if (fromDisk != null) {
            synchronized (memory) {
                memory.put(key, fromDisk);
            }
        }
        return fromDisk;
    }

    /**
     * Solve a region nobody is waiting on, then announce it so tiles stamped
     * without it can be restamped.
     *
     * <p>On its own thread rather than the escalator's: an escalation is
     * minutes of DEM and a prefetch is the ground the player is walking into,
     * so queueing the second behind the first would hold rivers back for as
     * long as the rung takes. Both feed the same single-threaded GPU upstream,
     * so this adds interleaving, not parallelism.
     */
    private void schedulePrefetch(Key key) {
        if (!prefetching.add(key)) {
            return;   // already queued or running
        }
        try {
            prefetcher.execute(() -> {
                try {
                    upgradeListener.accept(solved(key.level(), key.regionX(), key.regionZ()));
                } catch (RuntimeException e) {
                    // The tile that wanted it keeps the rivers it could see.
                    // Nothing is stale, only incomplete, so this is a warning.
                    LOG.warning("prefetch of " + key.level() + " region ("
                        + key.regionX() + "," + key.regionZ() + ") failed: " + e);
                } finally {
                    prefetching.remove(key);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            prefetching.remove(key);
        }
    }

    /**
     * The solve for one region, escalating inline when {@code deferEscalation}
     * is false and returning a provisional solve for someone else to finish
     * when it is true.
     */
    private Solved resolve(Level level, long regionX, long regionZ, boolean deferEscalation) {
        Key key = new Key(level, regionX, regionZ);
        Solved hit = resident(key);
        if (hit != null && !(hit.provisional() && !deferEscalation)) {
            return hit;
        }
        return inFlight.compute(new Flight(key, deferEscalation), f -> {
            Key k = f.key();
            Solved raced = resident(k);
            if (raced != null && !(raced.provisional() && !deferEscalation)) {
                return raced;
            }
            Solved fresh = readDisk(k);
            if (fresh == null) {
                fresh = compute(k.level(), k.regionX(), k.regionZ(), deferEscalation);
                // Only a finished solve is worth persisting. Writing a
                // provisional one would let the next session read a region that
                // is permanently missing its widest lakes, with nothing left in
                // the file to say the escalation never ran.
                if (!fresh.provisional()) {
                    writeDisk(k, fresh);
                }
            }
            synchronized (memory) {
                memory.put(k, fresh);
            }
            return fresh;
        });
    }

    /**
     * Finish a provisional region on the escalation thread, then publish it and
     * tell whoever is holding tiles stamped from the provisional planes.
     *
     * <p>The upgrade recomputes the base solve rather than carrying the window
     * across: the solve is ~23 ms and its DEM chunks are still in
     * {@link CoarseDem}'s memory cache, against a megabyte of window held per
     * pending region if we kept it.
     */
    private void scheduleUpgrade(Key key) {
        if (!upgrading.add(key)) {
            return;   // already queued or running
        }
        try {
            escalator.execute(() -> {
                try {
                    Solved full = resolve(key.level(), key.regionX(), key.regionZ(), false);
                    if (!full.provisional()) {
                        upgradeListener.accept(full);
                    }
                } catch (RuntimeException e) {
                    // A failed escalation leaves the provisional planes in
                    // place: fewer lakes, never a broken world.
                    LOG.warning("escalation of " + key.level() + " region ("
                        + key.regionX() + "," + key.regionZ() + ") failed: " + e);
                } finally {
                    upgrading.remove(key);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            upgrading.remove(key);
        }
    }

    /**
     * Called with each region that has become available, or more complete,
     * since a consumer last read it — so tiles stamped without it can be
     * dropped and restamped. Replaces any previous listener.
     *
     * <p>Two things fire it: an escalation landing (the region's planes gained
     * the lakes a wider rung vouches for) and a prefetch landing (the region
     * exists at all now, and its river routes can reach tiles already served).
     * Both mean the same thing to a consumer — anything stamped before this is
     * out of date — so they share one hook rather than making the listener
     * distinguish cases it would treat identically.
     */
    public void onRegionUpgraded(java.util.function.Consumer<Solved> listener) {
        this.upgradeListener = listener == null ? s -> { } : listener;
    }

    /**
     * Test seam: block until nothing is queued on either background thread.
     *
     * <p>Covers prefetches as well as escalations because a test that asserts
     * on rivers has to synchronise with whichever one is still running, and a
     * caller that had to know which would be encoding this class's internals.
     */
    boolean awaitBackground(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while ((!upgrading.isEmpty() || !prefetching.isEmpty())
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return upgrading.isEmpty() && prefetching.isEmpty();
    }

    /** Releases the background threads. */
    public void close() {
        escalator.shutdownNow();
        prefetcher.shutdownNow();
    }

    private Solved resident(Key key) {
        synchronized (memory) {
            return memory.get(key);
        }
    }

    private Solved compute(Level level, long regionX, long regionZ, boolean deferEscalation) {
        int cells = level.windowCells();
        long originX = level.originOf(regionX);
        long originZ = level.originOf(regionZ);
        float[] window = demWindow(level, originX, originZ, cells);
        float[] filled = new float[cells * cells];
        float[] depth = new float[cells * cells];
        float[] params = riverParams();

        // Rivers are planned only at L1. The coarser rungs exist to lend their
        // LAKE surfaces to basins L1 cannot own; their 32-to-128-block cells
        // are far too coarse to route a channel on, and their routes would
        // duplicate L1's over the same ground with no ownership rule to
        // separate them.
        boolean wantRivers = level == Level.L1;
        int[] starts = wantRivers ? new int[MAX_ROUTES + 1] : null;
        float[] verts = wantRivers
            ? new float[MAX_VERTICES * CendaKernels.RIVER_VERTEX_FLOATS] : null;
        int[] counts = wantRivers ? new int[2] : null;

        int[] stake = new int[2];
        int withheld = CendaKernels.solveBasins(seed, level.regionBlocks, level.haloBlocks,
            cells, level.cellBlocks,
            originX, originZ, window, params,
            0, 0, 0L, 0L, null, null, filled, depth, starts, verts, counts, stake);
        if (withheld == Integer.MIN_VALUE) {
            throw new IllegalStateException("Cenda kernels unavailable: ck_solve_basins "
                + "cannot run. Build the release preset (openmason-engine/cenda) or set "
                + "-Dstonebreak.water.backend=bridge.");
        }
        if (withheld < 0) {
            throw new IllegalStateException("ck_solve_basins returned " + withheld
                + " for " + level + " region (" + regionX + "," + regionZ + ")");
        }

        Level coarserLevel = level.coarser();
        boolean provisional = false;
        if (withheld > 0 && coarserLevel != null
                && worthEscalating(level, coarserLevel, regionX, regionZ, stake)) {
            if (deferEscalation) {
                // Hand back what this rung does know and let the escalation
                // thread finish it. The planes are already complete and
                // seam-free — `applyOwnership` withholds a basin's LAKE and
                // keeps its FILL — so the only thing missing is water the next
                // rung out will vouch for.
                provisional = true;
            } else {
                // One rung, on evidence. Escalating straight to the widest is
                // what cost 24 minutes; the next rung out costs 4x this one's
                // DEM and almost always ends it.
                LOG.info(level + " region (" + regionX + "," + regionZ + ") withheld "
                    + withheld + " basin(s) too wide for its " + level.haloBlocks
                    + "-block halo; escalating to " + coarserLevel);
                // Each rung's region is a whole number of the finer rung's, on
                // the same lattice, so this region lies entirely inside one.
                Solved coarse = resolve(coarserLevel,
                    coarserLevel.regionOf(regionX * (long) level.regionBlocks),
                    coarserLevel.regionOf(regionZ * (long) level.regionBlocks), false);
                int rc = CendaKernels.solveBasins(seed, level.regionBlocks, level.haloBlocks,
                    cells, level.cellBlocks,
                    originX, originZ, window, params,
                    coarse.cells(), coarserLevel.cellBlocks, coarse.originX(), coarse.originZ(),
                    coarse.filled(), coarse.depth(), filled, depth, starts, verts, counts, null);
                if (rc < 0) {
                    throw new IllegalStateException("ck_solve_basins returned " + rc
                        + " importing " + coarserLevel + " into " + level + " region ("
                        + regionX + "," + regionZ + ")");
                }
            }
        }

        int routeCount = counts == null ? 0 : counts[0];
        int vertexCount = counts == null ? 0 : counts[1];
        if (routeCount >= MAX_ROUTES || vertexCount >= MAX_VERTICES) {
            LOG.warning("L1 region (" + regionX + "," + regionZ + ") filled its river plan ("
                + routeCount + " routes, " + vertexCount + " vertices); rivers past the cap "
                + "were dropped and this region will disagree with its neighbours. Raise "
                + "MAX_ROUTES / MAX_VERTICES.");
        }
        // Trimmed to what was produced: the caps are working buffers, and a
        // cached region should not carry two megabytes of unused float.
        int[] keptStarts = routeCount == 0 ? new int[]{0}
            : java.util.Arrays.copyOf(starts, routeCount + 1);
        float[] keptVerts = routeCount == 0 ? new float[0]
            : java.util.Arrays.copyOf(verts, vertexCount * CendaKernels.RIVER_VERTEX_FLOATS);

        return new Solved(level, regionX, regionZ, originX, originZ, cells,
                          filled, depth, withheld, routeCount, keptStarts, keptVerts,
                          provisional);
    }

    /**
     * Whether the next rung out could emit the lake this one withheld — the
     * difference between "a coarser level is permitted to help" and "a coarser
     * level can help", which the withheld count alone does not draw.
     *
     * <p><b>The rung you escalate to is the one least able to emit what you
     * escalated for.</b> {@code minLakeArea} is counted in CELLS and every rung
     * doubles its cell size, so the smallest lake a rung will emit grows with
     * its cell area — on the shipping parameters 0.46 km² at L1, 1.84 at L2,
     * 7.37 at L3, 29.5 at L4. Meanwhile the rung's DEM window costs 4x the one
     * below it. Escalating for a lake under the next rung's floor therefore
     * buys, at four times the price, a plane that is guaranteed to come back
     * dry over the ground it was fetched for.
     *
     * <p>Measured on seed 5145549158747503491 (2026-09-03): L1 region (-1,-1)
     * withheld one basin, escalated to L2, was withheld again, escalated to L3.
     * That cost 207 extra {@link CoarseDem} chunks — ~5-7 minutes of serial GPU
     * and 76 % of the entire world load — and both coarse planes emitted
     * <b>zero</b> lake cells over region (-1,-1)'s ground. This gate is what
     * stops that ladder being climbed for nothing.
     *
     * <p><b>The headroom, and why the test is not exact.</b> The stake is the
     * withheld basin's trimmed lake as measured in THIS window, and a window
     * that truncates a basin can only under-measure it: the border is an
     * escape, so a truncated fill sits at or below the true spill and the lake
     * it implies is at or smaller than the real one. {@link #escalationStake}
     * is the fraction of the coarser floor that must be reached, defaulting to
     * 0.5 — the exact test with one doubling of slack for that bias. Set
     * {@code -Dstonebreak.water.escalationStake=0} to restore the old
     * escalate-on-any-withheld-basin behaviour.
     *
     * <p><b>What this trades away.</b> The measurement is window-dependent, so
     * two regions sharing one huge basin can in principle land on opposite
     * sides of the threshold and disagree about whether it holds water. That is
     * bounded by what the gate is allowed to skip: only a lake the coarser rung
     * would itself have discarded, which is a lake near the smallest either
     * level can emit. A skipped basin keeps its FILL and simply stays dry —
     * both neighbours withhold it identically — so the failure mode is a small
     * missing pond, never a broken routing field or a wall of water.
     */
    private boolean worthEscalating(Level level, Level coarser,
                                    long regionX, long regionZ, int[] stake) {
        if (escalationStake <= 0.0f) {
            return true;
        }
        // Both sides in blocks², the only unit the two rungs' lattices share.
        long lakeBlocks = (long) stake[0] * level.cellBlocks * level.cellBlocks;
        long coarseFloor = (long) minLakeArea * coarser.cellBlocks * coarser.cellBlocks;
        long needed = (long) Math.ceil(escalationStake * coarseFloor);
        if (lakeBlocks >= needed) {
            return true;
        }
        LOG.info(level + " region (" + regionX + "," + regionZ + ") withheld a basin "
            + stake[1] + " blocks across, but its lake measures only " + lakeBlocks
            + " blocks^2 against the " + needed + " " + coarser + " needs to emit anything "
            + "there; leaving it dry rather than fetching " + coarser + "'s DEM. Lower "
            + "-Dstonebreak.water.escalationStake to escalate anyway.");
        return false;
    }

    /**
     * The parameter array both layers read (kernels.h documents the layout).
     * The knobs that decide how much water a world has are configured here; the
     * ones that decide what a river LOOKS like — meander, gorge threshold,
     * width terms — take the kernel's defaults until phase 10 retunes them.
     */
    private float[] riverParams() {
        return new float[]{minLakeDepth, minLakeArea, seaLevel,
                           minRiverLakeArea, riverKeepFraction};
    }

    /**
     * The DEM this level solves on. L1 reads {@link CoarseDem} directly; a
     * coarser rung box-downsamples the same cells rather than fetching an
     * independent coarse field — the old plan tried a cheap low-frequency L0
     * and measured lake surfaces misplaced by ~10 blocks against L1's, which is
     * exactly the seam the rungs exist to avoid. Sourcing the coarse rungs from
     * the diffusion model's own latent trend channel was measured on 2026-09-03
     * and rejected for the same reason: only 1.6-4x cheaper (the latent
     * sampler, not the decoder, is what a chunk costs) and still 1.4 blocks RMS
     * and 4.7 peak off the pooled field at L4's resolution.
     *
     * <p>A coarse rung streams chunk by chunk because its window at L1
     * resolution is far larger than the window it solves — the widest is 4096^2
     * cells, 67 MB, against 1 MB once downsampled to its own.
     */
    private float[] demWindow(Level level, long originX, long originZ, int cells) {
        if (level == Level.L1) {
            return dem.windowAt(originX, originZ, cells).cells();
        }
        int factor = level.cellBlocks / Level.L1.cellBlocks;
        int chunkCells = dem.cellsPerChunk();
        int chunkBlocks = dem.chunkBlocks();
        if (chunkCells % factor != 0) {
            throw new IllegalStateException("a " + chunkCells + "-cell CoarseDem chunk does not "
                + "divide into " + factor + "x" + factor + " boxes");
        }
        int coarsePerChunk = chunkCells / factor;
        float[] out = new float[cells * cells];
        long endX = originX + (long) cells * level.cellBlocks;
        long endZ = originZ + (long) cells * level.cellBlocks;
        for (long cx = Math.floorDiv(originX, chunkBlocks);
             cx <= Math.floorDiv(endX - 1, chunkBlocks); cx++) {
            for (long cz = Math.floorDiv(originZ, chunkBlocks);
                 cz <= Math.floorDiv(endZ - 1, chunkBlocks); cz++) {
                float[] src = dem.chunk(cx, cz);
                int baseI = (int) ((cx * chunkBlocks - originX) / level.cellBlocks);
                int baseJ = (int) ((cz * chunkBlocks - originZ) / level.cellBlocks);
                for (int i = 0; i < coarsePerChunk; i++) {
                    int di = baseI + i;
                    if (di < 0 || di >= cells) {
                        continue;
                    }
                    for (int j = 0; j < coarsePerChunk; j++) {
                        int dj = baseJ + j;
                        if (dj < 0 || dj >= cells) {
                            continue;
                        }
                        // Averaged, not sampled: a lake's level is set by the
                        // lowest saddle on its rim, and every 8th cell walks
                        // past most saddles.
                        double acc = 0.0;
                        for (int fi = 0; fi < factor; fi++) {
                            int row = (i * factor + fi) * chunkCells + j * factor;
                            for (int fj = 0; fj < factor; fj++) {
                                acc += src[row + fj];
                            }
                        }
                        out[di * cells + dj] = (float) (acc / ((double) factor * factor));
                    }
                }
            }
        }
        return out;
    }

    // ── Disk cache ────────────────────────────────────────────────────────
    //
    // The planes are a pure function of the key, so a corrupt or truncated file
    // is never a failure: it is discarded and the region is re-solved. Writes
    // go to a temp file and are renamed, so a crash mid-write cannot leave a
    // half-file that reads as valid.

    private Path pathFor(Key key) {
        return cacheDir.resolve(key.level().name().toLowerCase(java.util.Locale.ROOT)
            + "_r" + key.regionX() + "_" + key.regionZ() + ".plane");
    }

    private Solved readDisk(Key key) {
        if (cacheDir == null) {
            return null;
        }
        Path path = pathFor(key);
        int cells = key.level().windowCells();
        int area = cells * cells;
        try {
            if (!Files.exists(path)) {
                return null;
            }
            long size = Files.size(path);
            if (size < HEADER_BYTES) {
                return null;
            }
            ByteBuffer buf = ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
                while (buf.hasRemaining() && ch.read(buf) >= 0) {
                    // read to completion
                }
            }
            if (buf.hasRemaining()) {
                return null;
            }
            buf.flip();
            if (buf.getInt() != DISK_VERSION || buf.getInt() != key.level().haloBlocks
                    || buf.getInt() != cells) {
                return null;
            }
            int withheld = buf.getInt();
            int routeCount = buf.getInt();
            int vertexCount = buf.getInt();
            if (routeCount < 0 || vertexCount < 0 || routeCount > MAX_ROUTES) {
                return null;
            }
            long expected = HEADER_BYTES + 2L * area * Float.BYTES
                + (routeCount == 0 ? 0L
                   : (routeCount + 1L) * Integer.BYTES
                     + (long) vertexCount * CendaKernels.RIVER_VERTEX_FLOATS * Float.BYTES);
            if (size != expected) {
                return null;
            }
            float[] filled = new float[area];
            float[] depth = new float[area];
            buf.asFloatBuffer().get(filled).get(depth);
            buf.position(buf.position() + 2 * area * Float.BYTES);
            int[] starts = new int[]{0};
            float[] verts = new float[0];
            if (routeCount > 0) {
                starts = new int[routeCount + 1];
                buf.asIntBuffer().get(starts);
                buf.position(buf.position() + (routeCount + 1) * Integer.BYTES);
                verts = new float[vertexCount * CendaKernels.RIVER_VERTEX_FLOATS];
                buf.asFloatBuffer().get(verts);
            }
            return new Solved(key.level(), key.regionX(), key.regionZ(),
                key.level().originOf(key.regionX()), key.level().originOf(key.regionZ()),
                cells, filled, depth, withheld, routeCount, starts, verts, false);
        } catch (IOException e) {
            LOG.warning("basin region " + path.getFileName() + " unreadable (" + e
                + "); re-solving");
            return null;
        }
    }

    private void writeDisk(Key key, Solved s) {
        if (cacheDir == null) {
            return;
        }
        Path path = pathFor(key);
        try {
            Files.createDirectories(cacheDir);
            int routeBytes = s.routeCount() == 0 ? 0
                : (s.routeCount() + 1) * Integer.BYTES + s.vertices().length * Float.BYTES;
            ByteBuffer buf = ByteBuffer
                .allocate(HEADER_BYTES + 2 * s.filled().length * Float.BYTES + routeBytes)
                .order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(DISK_VERSION).putInt(key.level().haloBlocks)
               .putInt(s.cells()).putInt(s.withheld())
               .putInt(s.routeCount()).putInt(s.vertexCount());
            buf.asFloatBuffer().put(s.filled()).put(s.depth());
            buf.position(buf.position() + 2 * s.filled().length * Float.BYTES);
            if (s.routeCount() > 0) {
                buf.asIntBuffer().put(s.routeStarts());
                buf.position(buf.position() + s.routeStarts().length * Integer.BYTES);
                buf.asFloatBuffer().put(s.vertices());
                buf.position(buf.position() + s.vertices().length * Float.BYTES);
            }
            buf.flip();
            // A private temp name per writer. A shared one ("<region>.plane.tmp")
            // let a second writer of the same region — another process, or the
            // mapper preview alongside the game — rename the file out from
            // under the first, whose own move then failed on a path that no
            // longer existed.
            Path tmp = Files.createTempFile(cacheDir, path.getFileName().toString(), ".tmp");
            try {
                try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    while (buf.hasRemaining()) {
                        ch.write(buf);
                    }
                }
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            // A region that cannot be persisted is still perfectly usable; the
            // next session just pays for it again.
            LOG.warning("basin region " + path.getFileName() + " could not be cached: " + e);
        }
    }

    /** Test seam: how many distinct regions are resident. */
    int cachedRegionCount() {
        synchronized (memory) {
            return memory.size();
        }
    }
}
