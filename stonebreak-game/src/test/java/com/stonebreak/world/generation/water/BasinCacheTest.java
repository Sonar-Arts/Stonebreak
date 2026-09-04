package com.stonebreak.world.generation.water;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.world.generation.diffusion.DiffusionBridgeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The caching and cost-avoidance contract around {@code ck_solve_basins}. The
 * fill's correctness is pinned by the kernel's own closed-form fixtures
 * ({@code cenda/tests/kernels_basin_test.cpp}); what can only go wrong on this
 * side is caching the wrong thing, serving a stale thing, or reaching for L0
 * when nothing asked for it — the last being the difference between a world
 * that loads and one that takes twenty minutes.
 *
 * <p><b>How "no chunk was fetched" is asserted.</b> The DEM is pre-populated
 * with exactly the sixteen chunks an L1 region's window covers and nothing
 * else, and {@link CoarseDem} has no fallback: a chunk it was not given is an
 * HTTP fetch against a bridge that is not there, which throws. So a test that
 * simply passes has proved that nothing outside the L1 window was touched —
 * an L0 window is 65,536 blocks a side and could not possibly stay inside it.
 * That is a stronger statement than counting calls, and it needs no seam in
 * production code.
 *
 * <p>Terrain is a pure function of world coordinates with a crater cut into it,
 * so a region's answer can be checked against arithmetic rather than against a
 * previous run.
 */
class BasinCacheTest {

    private static final int CHUNK = 2048;
    private static final int CELL = 16;
    private static final int CELLS_PER_CHUNK = CHUNK / CELL;
    private static final float SEA = 320.0f;
    private static final long SEED = 4242L;

    /** A plain at 500 with one 800-block crater, well inside L1's 2048 halo. */
    private static float terrainAt(long worldX, long worldZ) {
        double h = 500.0;
        double r = Math.hypot(worldX - 1024.0, worldZ - 1024.0);
        if (r < 400.0) {
            h -= 40.0 * (1.0 - r / 400.0);
        }
        return (float) h;
    }

    private static DiffusionBridgeConfig config() {
        DiffusionBridgeConfig base = DiffusionBridgeConfig.fromSystemProperties();
        return new DiffusionBridgeConfig(
                base.baseUrl(), base.tileSizeBlocks(), base.connectTimeoutMs(),
                base.requestTimeoutMs(), base.maxRetries(), base.initialBackoffMs(),
                base.maxBackoffMs(), base.maxCachedTiles(), base.unreachableGraceMs(),
                base.hydrologySolveGraceMs(), base.solvePollIntervalMs(),
                CHUNK, CELL, 4096);
    }

    /**
     * Exactly the chunks L1 region (0,0)'s window spans — origin -2048, 8192
     * blocks, so chunks -1..2 on each axis. Nothing else, deliberately.
     */
    private static CoarseDem demForOriginRegion() {
        return demOver(-1, 2, BasinCacheTest::terrainAt);
    }

    /**
     * A plain at 500 with one 2,800-block crater — past L1's 2,016-block
     * ownership limit, comfortably inside L2's 4,032. This is the shape that
     * used to cost 24 minutes: L1 withholds it and the old code escalated
     * straight to a 65,536-block window.
     */
    private static float wideCraterAt(long worldX, long worldZ) {
        double h = 500.0;
        double r = Math.hypot(worldX - 1024.0, worldZ - 1024.0);
        if (r < 1400.0) {
            h -= 40.0 * (1.0 - r / 1400.0);
        }
        return (float) h;
    }

    /**
     * Exactly the chunks L2 region (0,0)'s window spans and not one more —
     * origin -4096, 16384 blocks, so chunks -2..5 on each axis. L3's window
     * would need -4..11, so a test that completes over this DEM has proved the
     * ladder stopped at L2.
     */
    private static CoarseDem demForL2Window() {
        return demOver(-2, 5, BasinCacheTest::wideCraterAt);
    }

    /**
     * The same 2,800-block span — still too wide for L1 to own — but holding a
     * puddle instead of a lake: a saucer 0.2 blocks under its rim, which is
     * below {@code minLakeDepth} and so is never water, with a small deep core
     * at the middle that is.
     *
     * <p>This is the shape the escalation gate exists for, and it is not
     * contrived: {@code Basin}'s bbox covers the whole DEPRESSION rather than
     * the trimmed lake, so a wide shallow valley with one pond in it looks
     * exactly as unownable as a genuine inland sea. Measured on seed
     * 5145549158747503491, escalating for one of these cost 207 coarse chunks
     * and came back dry.
     *
     * <p>The core is deliberately monotone with the saucer — no intermediate
     * rim — so the two are ONE component filling to one level, rather than a
     * nested basin the fill would own separately.
     */
    private static float wideSaucerWithAPuddleAt(long worldX, long worldZ) {
        double r = Math.hypot(worldX - 1024.0, worldZ - 1024.0);
        if (r < 30.0) {
            return 495.0f;    /* ~11 cells of real lake, against a gate of 16 */
        }
        if (r < 1400.0) {
            return 499.8f;    /* 0.2 under the rim: filled, but never a lake  */
        }
        return 500.0f;
    }

    private interface Terrain {
        float at(long worldX, long worldZ);
    }

    /** A DEM holding exactly the chunks {@code c0..c1} on each axis. */
    private static CoarseDem demOver(long c0, long c1, Terrain terrain) {
        CoarseDem dem = new CoarseDem(config(), SEED);
        for (long cx = c0; cx <= c1; cx++) {
            for (long cz = c0; cz <= c1; cz++) {
                float[] cells = new float[CELLS_PER_CHUNK * CELLS_PER_CHUNK];
                for (int i = 0; i < CELLS_PER_CHUNK; i++) {
                    for (int j = 0; j < CELLS_PER_CHUNK; j++) {
                        cells[i * CELLS_PER_CHUNK + j] = terrain.at(
                                cx * CHUNK + (long) i * CELL, cz * CHUNK + (long) j * CELL);
                    }
                }
                dem.putChunkForTesting(cx, cz, cells);
            }
        }
        return dem;
    }

    /** Runs {@code body} with escalation forced onto the calling thread. */
    private static <T> T withSyncEscalation(java.util.function.Supplier<T> body) {
        return withProperty("stonebreak.water.asyncEscalation", "false", body);
    }

    private static <T> T withProperty(String key, String value,
                                      java.util.function.Supplier<T> body) {
        String prior = System.getProperty(key);
        System.setProperty(key, value);
        try {
            return body.get();
        } finally {
            if (prior == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, prior);
            }
        }
    }

    /** A DEM that can serve nothing: anything it is asked for throws. */
    private static CoarseDem emptyDem() {
        return new CoarseDem(config(), SEED);
    }

    private static BasinCache cache(CoarseDem dem, Path dir) {
        return new BasinCache(dem, SEED, dir, 0.5f, 8, SEA, 8);
    }

    private static void requireKernels() {
        assumeTrue(CendaKernels.isAvailable(),
                "Cenda kernels not built; run cmake --build on openmason-engine/cenda");
    }

    @Test
    void solvesTheRegionOwningAColumnAndFindsItsLake(@TempDir Path dir) {
        requireKernels();
        BasinCache.Solved s = cache(demForOriginRegion(), dir).forColumn(1024, 1024);

        assertEquals(0, s.regionX());
        assertEquals(0, s.regionZ());
        assertEquals(-2048, s.originX());
        assertEquals(512, s.cells(), "4096-block region plus a 2048-block halo at 16-block cells");
        assertTrue(s.isLake(1024, 1024), "the crater floor is lake");
        // A cone 40 blocks deep in a plain at 500 fills to 500; the floor sits
        // 40 below that, and the plain around it stays dry.
        assertEquals(500.0f, s.filledAt(1024, 1024), 0.01f);
        assertEquals(40.0f, s.depthAt(1024, 1024), 0.5f);
        assertFalse(s.isLake(3000, 3000), "the surrounding plain is dry");
    }

    @Test
    void ownsEveryBasinItFindsSoL0IsNeverSolved(@TempDir Path dir) {
        requireKernels();
        // Reaching for L0 would ask this DEM for chunks it does not have, which
        // throws — so the assertion is that this simply completes, plus the
        // count the decision is made on.
        BasinCache.Solved s = cache(demForOriginRegion(), dir).forColumn(1024, 1024);
        assertEquals(0, s.withheld(),
                "an 800-block crater is well inside L1's 2048-block halo");
    }

    @Test
    void secondAskIsServedFromMemory(@TempDir Path dir) {
        requireKernels();
        BasinCache cache = cache(demForOriginRegion(), dir);
        BasinCache.Solved first = cache.forColumn(1024, 1024);
        BasinCache.Solved second = cache.forColumn(2000, 2000);

        assertSame(first, second, "both columns belong to region (0,0)");
        assertEquals(1, cache.cachedRegionCount());
    }

    @Test
    void aColdCacheReadsBackWhatTheLastRunSolved(@TempDir Path dir) {
        requireKernels();
        BasinCache.Solved written = cache(demForOriginRegion(), dir).forColumn(1024, 1024);

        // Over an empty DEM: anything served came off disk, because solving
        // would need chunks that are not there and would throw.
        BasinCache.Solved read = cache(emptyDem(), dir).forColumn(1024, 1024);

        assertArrayEquals(written.filled(), read.filled());
        assertArrayEquals(written.depth(), read.depth());
        assertEquals(written.withheld(), read.withheld());
        assertEquals(written.originX(), read.originX());
    }

    @Test
    void retuningEitherLevelOrphansBothLevelsCaches(@TempDir Path dir) throws Exception {
        requireKernels();
        // The old L0/L1 hydrology hashed L1's knobs but not L0's, so L1 tiles
        // outlived an L0 retune and the levels disagreed about shared lakes.
        // One fingerprint covers both, so any retune moves the whole directory.
        cache(demForOriginRegion(), dir).forColumn(1024, 1024);
        new BasinCache(demForOriginRegion(), SEED, dir, 2.5f, 8, SEA, 8).forColumn(1024, 1024);

        try (var entries = Files.list(dir)) {
            List<Path> dirs = entries.filter(Files::isDirectory).toList();
            assertEquals(2, dirs.size(), "a retune must not reuse the old directory");
            assertNotEquals(dirs.get(0).getFileName(), dirs.get(1).getFileName());
        }
    }

    @Test
    void aTruncatedCacheFileIsDiscardedRatherThanTrusted(@TempDir Path dir) throws Exception {
        requireKernels();
        BasinCache.Solved written = cache(demForOriginRegion(), dir).forColumn(1024, 1024);

        Path plane;
        try (var entries = Files.walk(dir)) {
            plane = entries.filter(p -> p.getFileName().toString().endsWith(".plane"))
                    .findFirst().orElseThrow();
        }
        Files.write(plane, new byte[]{1, 2, 3});

        // The planes are a pure function of the key, so a corrupt file is never
        // a failure — it is discarded and the region is re-solved.
        BasinCache.Solved resolved = cache(demForOriginRegion(), dir).forColumn(1024, 1024);
        assertArrayEquals(written.filled(), resolved.filled());
        assertTrue(Files.size(plane) > 3, "the discarded file was rewritten");
    }

    @Test
    void concurrentAsksForOneRegionSolveItOnceAndCacheItCleanly(@TempDir Path dir)
            throws Exception {
        requireKernels();
        // The world generator asks from every chunk thread at once. Before
        // this was single-flighted each of them solved the region itself,
        // pulling its own copy of the region's sixteen coarse chunks across the
        // bridge, and then raced the others to rename one shared temp file —
        // which is what a slow world load looked like from the log:
        // "basin region l1_r-1_0.plane could not be cached: NoSuchFileException".
        BasinCache cache = cache(demForOriginRegion(), dir);
        List<LogRecord> complaints = new CopyOnWriteArrayList<>();
        Logger log = Logger.getLogger(BasinCache.class.getName());
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    complaints.add(record);
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        log.addHandler(capture);

        int threads = 16;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<BasinCache.Solved> seen = ConcurrentHashMap.newKeySet();
        try {
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(() -> {
                    try {
                        go.await();
                        seen.add(cache.forColumn(1024, 1024));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                t.setDaemon(true);
                t.start();
            }
            go.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "a solve never returned");
        } finally {
            log.removeHandler(capture);
        }

        assertEquals(1, seen.size(), "every caller must get the one solved region");
        assertEquals(1, cache.cachedRegionCount());
        assertEquals(List.of(), complaints.stream().map(LogRecord::getMessage).toList(),
                "a region that solves once has nothing to race over");

        try (var entries = Files.walk(dir)) {
            List<Path> files = entries.filter(Files::isRegularFile).toList();
            assertEquals(1, files.size(), "one region, one cache file and no temp left behind");
            assertTrue(files.get(0).getFileName().toString().endsWith(".plane"));
        }
    }

    @Test
    void escalatesOneRungAtATimeRatherThanStraightToTheWidest(@TempDir Path dir) {
        requireKernels();
        // The 24-minute bug: a basin one block past L1's limit used to jump to
        // a 65,536-block window (1,024 coarse chunks at a measured 1.40 s).
        // This DEM holds exactly L2's 64 chunks; reaching L3 would ask for
        // chunks that are not there and throw. Completing IS the assertion.
        BasinCache cache = withSyncEscalation(() -> cache(demForL2Window(), dir));
        BasinCache.Solved s = withSyncEscalation(() -> cache.forColumn(1024, 1024));

        assertFalse(s.provisional(), "escalation ran to completion");
        assertTrue(s.withheld() > 0, "L1 could not own a 2,800-block basin");
        assertTrue(s.isLake(1024, 1024),
                "the lake L1 withheld came back from the rung that owns it");
        assertEquals(500.0f, s.filledAt(1024, 1024), 0.01f);
    }

    @Test
    void aWithheldPuddleIsLeftDryRatherThanPaidForAtTheNextRung(@TempDir Path dir) {
        requireKernels();
        // The measured failure this gate exists for: L1 region (-1,-1) of seed
        // 5145549158747503491 withheld one basin, climbed two rungs for it at
        // 207 coarse chunks — 76 % of that world load — and both rungs emitted
        // ZERO lake cells, because minLakeArea is counted in CELLS and every
        // rung's cells are 4x the area of the one below.
        //
        // This DEM holds only L1's 16 chunks, so escalating at all would ask
        // for chunks that are not there and throw. Completing IS the assertion.
        BasinCache cache = withSyncEscalation(
                () -> cache(demOver(-1, 2, BasinCacheTest::wideSaucerWithAPuddleAt), dir));
        BasinCache.Solved s = withSyncEscalation(() -> cache.forColumn(1024, 1024));

        assertTrue(s.withheld() > 0, "L1 still cannot own a 2,800-block depression");
        assertFalse(s.provisional(), "the region is settled, not waiting on a rung");
        assertFalse(s.isLake(1024, 1024),
                "a puddle no coarser rung would emit is left dry rather than chased");
        assertEquals(500.0f, s.filledAt(1024, 1024), 0.01f,
                "the fill is kept whole — only the water is withheld, so routing still works");
    }

    @Test
    void theGateIsWhatStopsIt_notTheFixture(@TempDir Path dir) {
        requireKernels();
        // The companion to the test above, and the reason it proves anything:
        // the same terrain with the gate switched off DOES climb, and reaches
        // for L2's chunks this DEM does not hold. Without this, a fixture that
        // simply withheld nothing would pass the test above vacuously.
        assertThrows(RuntimeException.class,
                () -> withProperty("stonebreak.water.escalationStake", "0", () ->
                        withSyncEscalation(() -> cache(
                                demOver(-1, 2, BasinCacheTest::wideSaucerWithAPuddleAt), dir)
                                .forColumn(1024, 1024))),
                "with the gate off the ladder is climbed and the missing DEM is asked for");
    }

    @Test
    void aWithheldLakeWorthHavingStillEscalates(@TempDir Path dir) {
        requireKernels();
        // The gate must not simply refuse everything: the 2,800-block CRATER is
        // 40 blocks deep, so its lake is far above the next rung's floor and is
        // exactly what escalation is for. Same span as the puddle fixture, so
        // span cannot be what separates them — only the water at stake can.
        BasinCache cache = withSyncEscalation(() -> cache(demForL2Window(), dir));
        BasinCache.Solved s = withSyncEscalation(() -> cache.forColumn(1024, 1024));
        assertTrue(s.isLake(1024, 1024), "a real lake is still worth a rung");
    }

    @Test
    void aWithheldBasinIsServedAtOnceAndUpgradedBehindThePlayer(@TempDir Path dir)
            throws Exception {
        requireKernels();
        // World load must not wait on escalation. The first answer is complete
        // and seam-free but missing the lake only a wider rung may vouch for;
        // the lake arrives once the background solve lands.
        BasinCache cache = cache(demForL2Window(), dir);
        BasinCache.Solved provisional = cache.forColumn(1024, 1024);

        assertTrue(provisional.provisional(), "served without waiting for L2");
        assertFalse(provisional.isLake(1024, 1024),
                "a withheld basin keeps its fill and loses only its lake");
        assertEquals(500.0f, provisional.filledAt(1024, 1024), 0.01f,
                "the fill is complete even while the lake is withheld");

        assertTrue(cache.awaitBackground(60_000), "escalation finished");
        BasinCache.Solved upgraded = cache.forColumn(1024, 1024);
        assertFalse(upgraded.provisional());
        assertTrue(upgraded.isLake(1024, 1024), "the lake landed on upgrade");
        cache.close();
    }

    @Test
    void anUpgradeIsAnnouncedSoStampedTilesCanBeDropped(@TempDir Path dir) throws Exception {
        requireKernels();
        BasinCache cache = cache(demForL2Window(), dir);
        List<BasinCache.Solved> upgrades = new CopyOnWriteArrayList<>();
        cache.onRegionUpgraded(upgrades::add);

        cache.forColumn(1024, 1024);
        assertTrue(cache.awaitBackground(60_000), "escalation finished");
        // The listener fires on the escalation thread just after publication.
        for (int i = 0; i < 200 && upgrades.isEmpty(); i++) {
            Thread.sleep(5);
        }

        assertEquals(1, upgrades.size(), "exactly one region finished");
        assertEquals(BasinCache.Level.L1, upgrades.get(0).level());
        assertFalse(upgrades.get(0).provisional());
        cache.close();
    }

    @Test
    void aProvisionalRegionIsNeverPersisted(@TempDir Path dir) throws Exception {
        requireKernels();
        // Persisting one would let the next session read a region permanently
        // missing its widest lakes, with nothing in the file to say so.
        BasinCache cache = cache(demForL2Window(), dir);
        BasinCache.Solved provisional = cache.forColumn(1024, 1024);
        assertTrue(provisional.provisional());

        try (var walk = Files.walk(dir)) {
            assertTrue(walk.noneMatch(f -> f.getFileName().toString().startsWith("l1_")),
                    "no L1 plane on disk while the region is still provisional");
        }

        assertTrue(cache.awaitBackground(60_000));
        try (var walk = Files.walk(dir)) {
            assertTrue(walk.anyMatch(f -> f.getFileName().toString().startsWith("l1_")),
                    "the finished region is persisted");
        }
        cache.close();
    }

    @Test
    void aCrowdOfChunkThreadsIsAnsweredAtOnceAndStillGetsItsUpgrade(@TempDir Path dir)
            throws Exception {
        requireKernels();
        // World load asks from every chunk thread at once. All of them must be
        // answered without waiting on escalation, and the upgrade must still
        // land exactly once behind them.
        //
        // NOTE: this does NOT pin the narrow SingleFlight race the `Flight`
        // split fixes — reverting that split leaves this test green, because
        // the collision needs the escalation thread to arrive inside the
        // microseconds a waiter momentarily owns the flight slot. The split is
        // reasoned, not measured; this test only covers the coarse contract.
        BasinCache cache = cache(demForL2Window(), dir);
        int threads = 12;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<Boolean> sawProvisional = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    sawProvisional.add(cache.forColumn(1024, 1024).provisional());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "every asker was answered");
        assertTrue(sawProvisional.contains(Boolean.TRUE), "nobody waited on escalation");

        assertTrue(cache.awaitBackground(60_000), "escalation finished");
        assertTrue(cache.forColumn(1024, 1024).isLake(1024, 1024),
                "the upgrade landed despite the concurrent asks");
        cache.close();
    }

    @Test
    void everyRungSolvesTheSameSizedWindowSoTheSolveCostIsFlat() {
        // Each rung doubles cell, region and halo together, so the DEM a rung
        // needs grows 4x in area while the solve it runs stays 512^2. A rung
        // added without that property would make the fill itself expensive.
        for (BasinCache.Level level : BasinCache.Level.values()) {
            assertEquals(512, level.windowCells(), level + " solves a 512^2 window");
        }
    }
}
