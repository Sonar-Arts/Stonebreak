package com.stonebreak.world.generation.water;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.world.generation.diffusion.DiffusionBridgeConfig;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The native lakes tile pass, exercised through the same wrapper the production
 * tile chain installs. All tests skip without the Cenda kernels library (build
 * the release preset under openmason-engine/cenda).
 *
 * <p>The raw fake mirrors what the bridge serves with its hydrology off: pure
 * per-column heights and a sea-level-only water plane. The DEM is the same
 * height function averaged onto 16-block cells — what {@link CoarseDem} serves
 * — so the basin solve and the stamp see one world.
 *
 * <p>Everything inland the wrapper emits therefore comes from the depression
 * fill, and the assertions here validate the JAVA side of the contract: the
 * 3x3 window assembly, whose row/column orientation a transposed bug would
 * corrupt silently (both axes are the same length), and the DEM span slice,
 * which is the same hazard one level up.
 */
public class NativeWaterTilesTest {

    private static final int TILE = 256;
    private static final int SEA = WorldConfiguration.SEA_LEVEL;
    private static final long SEED = 20260801L;

    @BeforeAll
    static void requireKernels() {
        assumeTrue(CendaKernels.isAvailable(),
                "Cenda kernels library not present; native water tests skipped");
    }

    /** Raw tiles as the bridge serves them with hydrology off: sea water only. */
    private static final class RawHillsTiles implements TerrainTileSource {
        private final Map<Long, TerrainTile> cache = new HashMap<>();

        /**
         * A plain that drains toward -x into a coast, with isolated conical
         * pits on a 512-block lattice and a little ripple for texture.
         *
         * <p>The shape is chosen, not decorative. An earlier version was a sum
         * of sine PRODUCTS with no regional slope: superimposed troughs ran
         * into each other, Priority-Flood merged them into basins wider than
         * L1's 2048-block halo, and the cache correctly went looking for L0 —
         * 1,024 DEM chunks this fixture does not have. Two properties fix it
         * and both matter. The plain is strictly monotone in x, so every column
         * has somewhere to drain; and the ripple varies in z ONLY, so it has no
         * gradient to fight the tilt with and cannot close a basin by itself
         * (an x-varying ripple of amplitude 2 already out-gradients a 0.02
         * tilt, which is how the second version still reached L0). The pits are
         * then the only closed basins in the world, each 240 blocks across and
         * far narrower than the halo, so L1 owns every one of them.
         *
         * <p>The ripple stays because the terrain has to be asymmetric in x and
         * z for the transpose assertions to bite. Pit centres land on tile
         * seams, which is what gives the seam-agreement test a lake to
         * compare.
         */
        static int height(int x, int z) {
            double h = 360.0 + 0.02 * x + 0.5 * Math.sin(z * 0.027);
            int px = Math.floorDiv(x + 256, 512) * 512;
            int pz = Math.floorDiv(z + 256, 512) * 512;
            double r = Math.hypot(x - px, z - pz);
            if (r < 120.0) {
                h -= 18.0 * (1.0 - r / 120.0);
            }
            if (x < -200) { // coast toward -x
                h -= (-200 - x) * 0.15;
            }
            return Math.max(1, Math.min((int) h, WorldConfiguration.WORLD_HEIGHT - 1));
        }

        @Override
        public synchronized TerrainTile getTile(int worldX, int worldZ) {
            int tileX = Math.floorDiv(worldX, TILE);
            int tileZ = Math.floorDiv(worldZ, TILE);
            return cache.computeIfAbsent(((long) tileX << 32) ^ (tileZ & 0xFFFFFFFFL), k -> {
                int i1 = tileX * TILE;
                int j1 = tileZ * TILE;
                short[] heights = new short[TILE * TILE];
                short[] biomes = new short[TILE * TILE];
                short[] water = new short[TILE * TILE];
                for (int row = 0; row < TILE; row++) {
                    for (int col = 0; col < TILE; col++) {
                        int h = height(i1 + row, j1 + col);
                        int idx = row * TILE + col;
                        heights[idx] = (short) h;
                        biomes[idx] = (short) ((row + col) % 7); // asymmetric, catches transposes
                        water[idx] = h < SEA ? (short) SEA : TerrainTile.NO_WATER;
                    }
                }
                return new TerrainTile(tileX, tileZ, i1, j1, i1 + TILE, j1 + TILE,
                        TILE, TILE, heights, biomes, water);
            });
        }
    }

    /**
     * A DEM over the ground these tests touch: the same height function,
     * averaged onto 16-block cells.
     *
     * <p>Wider than the tiles themselves, and deliberately. Hydrating a tile
     * gathers river routes from every region whose own routes could reach it,
     * which is every region within one halo — so a tile at z = 0 pulls in
     * region (-1) in z, whose window starts at z = -6144. Rivers cross region
     * borders and the gather has to look across them.
     */
    private static CoarseDem dem() {
        return dem(-3, 2);
    }

    /** A DEM holding exactly the chunks {@code c0..c1} on each axis. */
    private static CoarseDem dem(long c0, long c1) {
        DiffusionBridgeConfig base = DiffusionBridgeConfig.fromSystemProperties();
        int chunk = 2048;
        int cell = BasinCache.CELL_BLOCKS;
        int cellsPerChunk = chunk / cell;
        DiffusionBridgeConfig config = new DiffusionBridgeConfig(
                base.baseUrl(), base.tileSizeBlocks(), base.connectTimeoutMs(),
                base.requestTimeoutMs(), base.maxRetries(), base.initialBackoffMs(),
                base.maxBackoffMs(), base.maxCachedTiles(), base.unreachableGraceMs(),
                base.hydrologySolveGraceMs(), base.solvePollIntervalMs(),
                chunk, cell, 4096);
        CoarseDem dem = new CoarseDem(config, SEED);
        for (long cx = c0; cx <= c1; cx++) {
            for (long cz = c0; cz <= c1; cz++) {
                float[] cells = new float[cellsPerChunk * cellsPerChunk];
                for (int i = 0; i < cellsPerChunk; i++) {
                    for (int j = 0; j < cellsPerChunk; j++) {
                        long x0 = cx * chunk + (long) i * cell;
                        long z0 = cz * chunk + (long) j * cell;
                        double acc = 0.0;
                        for (int a = 0; a < cell; a++) {
                            for (int b = 0; b < cell; b++) {
                                acc += RawHillsTiles.height((int) (x0 + a), (int) (z0 + b));
                            }
                        }
                        cells[i * cellsPerChunk + j] = (float) (acc / (cell * cell));
                    }
                }
                dem.putChunkForTesting(cx, cz, cells);
            }
        }
        return dem;
    }

    /**
     * The wrapper and the cache behind it. A tile is served before its
     * neighbour regions are solved, so a test that asserts on the finished
     * article has to settle that background work first — which needs the
     * cache, not just the wrapper.
     */
    private record Stack(NativeWaterTiles tiles, BasinCache basins) {
        /** Block until every backgrounded region has landed. */
        NativeWaterTiles settled() throws InterruptedException {
            assertTrue(basins.awaitBackground(60_000), "background region solves finished");
            return tiles;
        }
    }

    private static NativeWaterTiles wrapper() {
        return wrapper(BasinCache.DEFAULT_RIVER_KEEP_FRACTION);
    }

    /** @param keepFraction 0 for lakes only, 1 to give every lake an outlet */
    private static NativeWaterTiles wrapper(float keepFraction) {
        return stack(keepFraction, dem()).tiles();
    }

    private static Stack stack(float keepFraction, CoarseDem dem) {
        // Null cache root: memory only, so the tests touch no disk.
        BasinCache basins = new BasinCache(dem, SEED, null,
                BasinCache.DEFAULT_MIN_LAKE_DEPTH, BasinCache.DEFAULT_MIN_LAKE_AREA,
                SEA, BasinCache.DEFAULT_MIN_RIVER_LAKE_AREA, keepFraction, 4);
        return new Stack(new NativeWaterTiles(new RawHillsTiles(), basins, SEED, TILE, 64),
                basins);
    }

    @Test
    public void concurrentCallersForOneTileHydrateItOnce() throws Exception {
        // A hydration is nine raw-tile fetches, a river gather across up to
        // nine regions and a native carve. The wrapper used to let EVERY thread
        // that arrived before the first one finished run all of it — the
        // CompletableFuture deduplicated the result, not the work — so a dozen
        // chunk threads landing on one cold tile paid for it a dozen times.
        //
        // Counted at the raw source: hydrate() asks for its centre tile exactly
        // once, so requests for that one key ARE the hydration count.
        final int threads = 8;
        final int worldX = 1024;
        final int worldZ = 1024;
        CountDownLatch arrived = new CountDownLatch(threads);
        AtomicInteger centreRequests = new AtomicInteger();

        RawHillsTiles raw = new RawHillsTiles();
        TerrainTileSource counting = (x, z) -> {
            if (Math.floorDiv(x, TILE) == Math.floorDiv(worldX, TILE)
                    && Math.floorDiv(z, TILE) == Math.floorDiv(worldZ, TILE)) {
                centreRequests.incrementAndGet();
                // Hold the first hydrator here until every caller has had time
                // to reach the wrapper, so they really do contend.
                try {
                    arrived.await(10, TimeUnit.SECONDS);
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return raw.getTile(x, z);
        };

        BasinCache basins = new BasinCache(dem(), SEED, null,
                BasinCache.DEFAULT_MIN_LAKE_DEPTH, BasinCache.DEFAULT_MIN_LAKE_AREA,
                SEA, BasinCache.DEFAULT_MIN_RIVER_LAKE_AREA,
                BasinCache.DEFAULT_RIVER_KEEP_FRACTION, 4);
        try (NativeWaterTiles tiles =
                     new NativeWaterTiles(counting, basins, SEED, TILE, 64)) {
            List<TerrainTile> results = Collections.synchronizedList(new ArrayList<>());
            List<Thread> workers = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(() -> {
                    arrived.countDown();
                    results.add(tiles.getTile(worldX, worldZ));
                });
                workers.add(t);
                t.start();
            }
            for (Thread t : workers) {
                t.join(60_000);
                assertTrue(!t.isAlive(), "worker finished");
            }

            assertEquals(threads, results.size(), "every caller got a tile");
            assertEquals(1, centreRequests.get(),
                    "one hydration serves every concurrent caller");
            for (TerrainTile got : results) {
                assertSame(results.get(0), got, "and they all get the same instance");
            }
        }
    }

    @Test
    public void preservesBoundsAndBiomesAndUntouchedGround() {
        NativeWaterTiles tiles = wrapper();
        TerrainTile raw = new RawHillsTiles().getTile(512, 512);
        TerrainTile hydrated = tiles.getTile(512, 512);

        assertEquals(raw.tileX(), hydrated.tileX());
        assertEquals(raw.worldI1(), hydrated.worldI1());
        assertEquals(raw.worldJ2(), hydrated.worldJ2());
        assertArrayEquals(raw.biomeIds(), hydrated.biomeIds(), "biomes pass through untouched");

        // Ground with no water in its 4-neighborhood must be byte-identical to
        // the raw tile — this is the assertion a transposed window assembly
        // cannot survive, because the terrain function is asymmetric in x/z.
        int untouched = 0;
        for (int x = 513; x < 512 + TILE - 1; x++) {
            for (int z = 513; z < 512 + TILE - 1; z++) {
                if (hydrated.waterLevelAt(x, z) != TerrainTile.NO_WATER) continue;
                if (hydrated.waterLevelAt(x - 1, z) != TerrainTile.NO_WATER) continue;
                if (hydrated.waterLevelAt(x + 1, z) != TerrainTile.NO_WATER) continue;
                if (hydrated.waterLevelAt(x, z - 1) != TerrainTile.NO_WATER) continue;
                if (hydrated.waterLevelAt(x, z + 1) != TerrainTile.NO_WATER) continue;
                assertEquals(raw.heightAt(x, z), hydrated.heightAt(x, z),
                        "dry column away from water must keep its raw height at (" + x + "," + z + ")");
                untouched++;
            }
        }
        assertTrue(untouched > TILE * TILE / 2, "most of the tile should be dry ground");
    }

    @Test
    public void deterministicAndCached() throws Exception {
        Stack a = stack(BasinCache.DEFAULT_RIVER_KEEP_FRACTION, dem());
        Stack b = stack(BasinCache.DEFAULT_RIVER_KEEP_FRACTION, dem());
        // Settle first: a tile served while its neighbour regions are still
        // being solved is dropped when they land, so "the wrapper caches its
        // tiles" is only a statement about a wrapper that has stopped moving.
        a.tiles().getTile(-700, 300);
        b.tiles().getTile(-700, 300);
        TerrainTile t1 = a.settled().getTile(-700, 300);
        TerrainTile t2 = b.settled().getTile(-700, 300);
        assertArrayEquals(t1.blockHeights(), t2.blockHeights());
        assertArrayEquals(t1.waterLevels(), t2.waterLevels());
        assertSame(t1, a.tiles().getTile(-700, 300), "same instance from the wrapper's cache");
    }

    @Test
    public void aTileIsServedWithoutWaitingForItsNeighbourRegions() {
        // The cost this avoids: a tile's window comes within a halo of FOUR L1
        // regions (nine near a corner), so gathering rivers from all of them
        // made the first tile of a cold world cost 36 coarse chunks where the
        // region it actually sits on costs 16 — measured 50-79 s against 22-35,
        // every second of it before a single block appears.
        //
        // This DEM holds only the owner region (0,0)'s window, chunks -1..2.
        // Region (-1,-1) needs chunks -3..0, which are not there, so a gather
        // that waits for it asks for them and throws. Completing IS the
        // assertion — exactly as in BasinCacheTest.
        NativeWaterTiles tiles = stack(BasinCache.DEFAULT_RIVER_KEEP_FRACTION,
                dem(-1, 2)).tiles();
        TerrainTile t = assertDoesNotThrow(() -> tiles.getTile(0, 0));
        assertEquals(0, t.tileX());
        assertEquals(0, t.tileZ());
    }

    @Test
    public void theAsyncGatherIsWhatServesIt_notAWiderDem() {
        // The companion to the test above, and the reason it proves anything:
        // with the gather told to wait, the same tile over the same DEM reaches
        // for the neighbour's chunks and fails. Without this, a DEM that
        // happened to be wide enough would pass the test above vacuously.
        String key = "stonebreak.water.asyncRiverGather";
        String prior = System.getProperty(key);
        System.setProperty(key, "false");
        try {
            NativeWaterTiles tiles = stack(BasinCache.DEFAULT_RIVER_KEEP_FRACTION,
                    dem(-1, 2)).tiles();
            assertThrows(RuntimeException.class, () -> tiles.getTile(0, 0),
                    "a waiting gather must ask for the neighbour region's DEM");
        } finally {
            if (prior == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, prior);
            }
        }
    }

    @Test
    public void aTileStampedBeforeANeighbourLandsIsDroppedSoItRestamps()
            throws Exception {
        // Serving early is only safe because the tile is not left that way: the
        // region announces itself when it lands and every tile whose gather
        // could have wanted it is dropped, so the next ask restamps with the
        // rivers it was missing.
        Stack s = stack(BasinCache.DEFAULT_RIVER_KEEP_FRACTION, dem());
        TerrainTile early = s.tiles().getTile(0, 0);
        assertNotSame(early, s.settled().getTile(0, 0),
                "the tile stamped before its neighbours landed was dropped");
        // And once nothing is outstanding it is stable again.
        assertSame(s.tiles().getTile(0, 0), s.tiles().getTile(0, 0));
    }

    @Test
    public void containmentHoldsInsideTilesAndAcrossSeams() {
        NativeWaterTiles tiles = wrapper();
        // A row of adjacent tiles along +X; also covers the coast (tile -2).
        TerrainTile prev = null;
        for (int tx = -2; tx <= 2; tx++) {
            TerrainTile cur = tiles.getTile(tx * TILE, 0);

            // In-tile: interior wet columns never pour onto lower dry ground.
            for (int x = cur.worldI1() + 1; x < cur.worldI2() - 1; x++) {
                for (int z = cur.worldJ1() + 1; z < cur.worldJ2() - 1; z++) {
                    int w = cur.waterLevelAt(x, z);
                    if (w < 0) continue;
                    assertContained(cur, x - 1, z, w);
                    assertContained(cur, x + 1, z, w);
                    assertContained(cur, x, z - 1, w);
                    assertContained(cur, x, z + 1, w);
                }
            }

            // Across the seam with the previous tile: facing columns.
            if (prev != null) {
                int xa = prev.worldI2() - 1;
                int xb = cur.worldI1();
                for (int z = cur.worldJ1(); z < cur.worldJ2(); z++) {
                    int wa = prev.waterLevelAt(xa, z);
                    int wb = cur.waterLevelAt(xb, z);
                    if (wa >= 0 && wb < 0) {
                        assertTrue(cur.heightAt(xb, z) >= wa,
                                "seam leak at z=" + z + ": " + cur.heightAt(xb, z) + " < " + wa);
                    }
                    if (wb >= 0 && wa < 0) {
                        assertTrue(prev.heightAt(xa, z) >= wb,
                                "seam leak at z=" + z + ": " + prev.heightAt(xa, z) + " < " + wb);
                    }
                }
            }
            prev = cur;
        }
    }

    private static void assertContained(TerrainTile tile, int x, int z, int level) {
        if (tile.waterLevelAt(x, z) != TerrainTile.NO_WATER) {
            return; // wet neighbor at any level is a waterfall, allowed
        }
        assertTrue(tile.heightAt(x, z) >= level,
                "wet column pours onto (" + x + "," + z + "): terrain "
                        + tile.heightAt(x, z) + " < level " + level);
    }

    @Test
    public void seaSurvivesAndInlandWaterAppears() {
        NativeWaterTiles tiles = wrapper();
        // Coastal tile: raw sea columns must still report sea level.
        TerrainTile coast = tiles.getTile(-3 * TILE, 0);
        int seaCols = 0;
        for (int x = coast.worldI1(); x < coast.worldI2(); x++) {
            for (int z = coast.worldJ1(); z < coast.worldJ2(); z++) {
                if (coast.heightAt(x, z) < SEA) {
                    assertEquals(SEA, coast.waterLevelAt(x, z));
                    seaCols++;
                }
            }
        }
        assertTrue(seaCols > 0, "coastal tile should contain sea columns");

        // Across a stretch of inland tiles the fill must have put water in the
        // depressions this terrain has.
        int wet = 0;
        for (int tx = 0; tx < 4; tx++) {
            TerrainTile t = tiles.getTile(tx * TILE, TILE);
            for (int x = t.worldI1(); x < t.worldI2(); x++) {
                for (int z = t.worldJ1(); z < t.worldJ2(); z++) {
                    if (t.waterLevelAt(x, z) > SEA) wet++;
                }
            }
        }
        assertTrue(wet > 0, "no inland water over 4 tiles");
    }

    @Test
    public void lakesAloneExcavateNothing() {
        // With no river planned, the only water is lakes — and a lake sits in a
        // depression the terrain already had, so nothing is dug for it. The
        // only permitted height change is the containment repair RAISING a dry
        // column beside water. Rivers are the opposite and are tested next.
        NativeWaterTiles tiles = wrapper(0.0f);
        int wet = 0;
        int dug = 0;
        for (int tx = 0; tx < 4; tx++) {
            TerrainTile t = tiles.getTile(tx * TILE, TILE);
            for (int x = t.worldI1(); x < t.worldI2(); x++) {
                for (int z = t.worldJ1(); z < t.worldJ2(); z++) {
                    if (t.waterLevelAt(x, z) > SEA) wet++;
                    if (t.heightAt(x, z) < RawHillsTiles.height(x, z)) dug++;
                }
            }
        }
        assertTrue(wet > 0, "the pits should still hold lakes");
        assertEquals(0, dug, "no ground may be excavated to hold a lake");
    }

    @Test
    public void riversCarveAChannelAndHoldWaterInIt() {
        // The end of the pipeline as the game sees it: a lake's spill becomes a
        // route in BasinCache, the route reaches the tile through the gather,
        // and the kernel cuts a channel that holds water.
        NativeWaterTiles withRivers = wrapper(1.0f);
        NativeWaterTiles lakesOnly = wrapper(0.0f);
        int dug = 0;
        int extraWet = 0;
        for (int tx = 0; tx < 4; tx++) {
            TerrainTile a = withRivers.getTile(tx * TILE, TILE);
            TerrainTile b = lakesOnly.getTile(tx * TILE, TILE);
            for (int x = a.worldI1(); x < a.worldI2(); x++) {
                for (int z = a.worldJ1(); z < a.worldJ2(); z++) {
                    if (a.heightAt(x, z) < RawHillsTiles.height(x, z)) dug++;
                    boolean wetNow = a.waterLevelAt(x, z) > SEA;
                    boolean wetBefore = b.waterLevelAt(x, z) > SEA;
                    if (wetNow && !wetBefore) extraWet++;
                    if (wetNow) {
                        assertTrue(a.heightAt(x, z) < a.waterLevelAt(x, z),
                                "a wet column must sit below its own surface at ("
                                        + x + "," + z + ")");
                    }
                }
            }
        }
        assertTrue(dug > 0, "rivers must cut a channel into the ground");
        assertTrue(extraWet > 0, "rivers must put water where the lakes alone did not");
    }

    @Test
    public void everyTileTouchingALakeAgreesOnItsLevel() {
        // Two tiles' windows overlap by a whole tile, and adjacent tiles are
        // hydrated independently. A lake spanning the seam must come out on one
        // level from both, which is the property the region-owned DEM buys.
        NativeWaterTiles tiles = wrapper();
        int compared = 0;
        for (int tx = 0; tx < 4; tx++) {
            TerrainTile a = tiles.getTile(tx * TILE, TILE);
            TerrainTile b = tiles.getTile((tx + 1) * TILE, TILE);
            int xa = a.worldI2() - 1;
            int xb = b.worldI1();
            for (int z = a.worldJ1(); z < a.worldJ2(); z++) {
                int wa = a.waterLevelAt(xa, z);
                int wb = b.waterLevelAt(xb, z);
                if (wa > SEA && wb > SEA) {
                    assertEquals(wa, wb, "lake level disagrees across the tile seam at z=" + z);
                    compared++;
                }
            }
        }
        assertTrue(compared > 0, "no lake straddled a tile seam to compare");
    }
}
