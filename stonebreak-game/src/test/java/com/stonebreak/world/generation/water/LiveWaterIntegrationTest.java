package com.stonebreak.world.generation.water;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.world.generation.diffusion.DiffusionBridgeConfig;
import com.stonebreak.world.generation.diffusion.DiffusionTileCache;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The whole water stack against a live terrain bridge, at block resolution.
 *
 * <p>Everything else in this package runs on synthetic terrain or on the DEM
 * alone, which leaves two things unmeasurable. The first is how much of the
 * world is actually water — every figure up to now is at 16-block cell
 * resolution, and the acceptance criterion is about blocks. The second is
 * whether the containment invariant survives real diffusion terrain, which is
 * rougher than any fixture: a wet column with a lower dry neighbour is a
 * permanent spring, so this is the assertion that decides whether a world
 * floods.
 *
 * <p><b>Skipped unless a bridge is already running</b> on the configured port
 * and pinned to {@link #SEED}. Start one by hand:
 *
 * <pre>
 *   cd Dev Working/terrain-diffusion-spike/repo
 *   ../venv/bin/python -m terrain_diffusion.inference.minecraft_api \
 *       xandergos/terrain-diffusion-30m --no-compile --device cuda \
 *       --port 8010 --hdf5-file TEMP --seed 1433293152336000383
 *   cd terrain-bridge &amp;&amp; TERRAIN_BRIDGE_SEED=1433293152336000383 \
 *       TERRAIN_BRIDGE_UPSTREAM_URL=http://localhost:8010 \
 *       TERRAIN_BRIDGE_HYDROLOGY=0 venv/bin/python -m uvicorn bridge.main:app --port 8180
 * </pre>
 *
 * <p>This test does not start them itself. {@code TerrainServiceProcessManager}
 * resolves its paths against {@code user.dir}, and surefire runs with the
 * module directory as its working directory, so a test that autostarted would
 * launch the wrong python and fail for a reason that has nothing to do with
 * water.
 *
 * <p>The seed is the one the populated tile cache was written under, so tiles
 * come back warm and the run costs coarse-elevation chunks rather than a whole
 * world of GPU time.
 */
public class LiveWaterIntegrationTest {

    private static final long SEED = 1433293152336000383L;
    private static final int SEA = WorldConfiguration.SEA_LEVEL;

    /**
     * The whole of L1 region (0,0)'s own ground: 16x16 tiles of 256 blocks.
     *
     * <p>Not a corner of it. The first version of this test sampled the 1,280
     * blocks at the origin and reported 0.00 % inland water — because that
     * corner is coast (45 % of its cells sit at or below sea level) and every
     * one of the region's 4,066 lake cells and 1,633 river vertices lies
     * elsewhere, centred around (1876, 3250). "How much water does this world
     * have" is not a question a corner can answer, and a sample chosen to
     * contain water would not be answering it either.
     */
    private static final int TILES = 16;
    private static final int REGION_ORIGIN = 0;

    private static DiffusionBridgeConfig config;
    /** Fetched once in @BeforeAll: 256 tiles is minutes of bridge time cold. */
    private static TerrainTile[][] sample;
    private static BasinCache.Solved region;

    @BeforeAll
    static void requireLiveBridgeAndKernels() {
        assumeTrue(CendaKernels.isAvailable(),
                "Cenda kernels not built; run cmake --build on openmason-engine/cenda");
        config = DiffusionBridgeConfig.fromSystemProperties();
        assumeTrue(bridgeIsUpForOurSeed(config),
                "no terrain bridge on " + config.baseUrl() + " pinned to seed " + SEED
                        + "; see this class's javadoc to start one");

        NativeWaterTiles tiles = liveTiles();
        int tile = config.tileSizeBlocks();
        long t0 = System.nanoTime();
        sample = new TerrainTile[TILES][TILES];
        for (int tx = 0; tx < TILES; tx++) {
            for (int tz = 0; tz < TILES; tz++) {
                sample[tx][tz] = tiles.getTile(REGION_ORIGIN + tx * tile,
                                               REGION_ORIGIN + tz * tile);
            }
        }
        System.out.printf("live sample: %d tiles (%d blocks square) hydrated in %.1f s%n",
                TILES * TILES, TILES * tile, (System.nanoTime() - t0) / 1e9);
        region = basins().forColumn(REGION_ORIGIN, REGION_ORIGIN);
    }

    private static BasinCache basins() {
        return new BasinCache(new CoarseDem(config, SEED), SEED, null,
                BasinCache.DEFAULT_MIN_LAKE_DEPTH, BasinCache.DEFAULT_MIN_LAKE_AREA,
                SEA, BasinCache.DEFAULT_MIN_RIVER_LAKE_AREA,
                BasinCache.DEFAULT_RIVER_KEEP_FRACTION, 8);
    }

    private static boolean bridgeIsUpForOurSeed(DiffusionBridgeConfig cfg) {
        try (HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build()) {
            HttpResponse<String> health = http.send(
                    HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/health"))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return health.statusCode() == 200
                    && health.body().contains("\"seed\":" + SEED);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static NativeWaterTiles liveTiles() {
        DiffusionTileCache raw = new DiffusionTileCache(config, SEED);
        return new NativeWaterTiles(raw, basins(), SEED, config.tileSizeBlocks(),
                TILES * TILES + 16);
    }

    /**
     * The invariant that decides whether a world floods: every wet column's
     * four neighbours are wet themselves or stand at or above its level.
     * Wet-next-to-wet at different levels is a waterfall and is allowed.
     */
    @Test
    public void containmentHoldsOnRealTerrain() {
        long checked = 0;
        for (int tx = 0; tx < TILES; tx++) {
            for (int tz = 0; tz < TILES; tz++) {
                TerrainTile t = sample[tx][tz];
                for (int x = t.worldI1() + 1; x < t.worldI2() - 1; x++) {
                    for (int z = t.worldJ1() + 1; z < t.worldJ2() - 1; z++) {
                        int w = t.waterLevelAt(x, z);
                        if (w == TerrainTile.NO_WATER) {
                            continue;
                        }
                        checked++;
                        assertContained(t, x - 1, z, w);
                        assertContained(t, x + 1, z, w);
                        assertContained(t, x, z - 1, w);
                        assertContained(t, x, z + 1, w);
                    }
                }
            }
        }
        assertTrue(checked > 0, "the sampled area held no water at all to check");
        System.out.printf("live containment: %d wet columns, no leaks%n", checked);
    }

    private static void assertContained(TerrainTile t, int x, int z, int level) {
        if (t.waterLevelAt(x, z) != TerrainTile.NO_WATER) {
            return;
        }
        assertTrue(t.heightAt(x, z) >= level,
                "wet column pours onto (" + x + "," + z + "): terrain "
                        + t.heightAt(x, z) + " < level " + level);
    }

    /**
     * Whether a tile border is visible in the water.
     *
     * <p>There is nothing to compare directly: every column is emitted by
     * exactly one tile, so two tiles never disagree about a column — that is
     * what ownership buys and it is not something a test can catch failing.
     * What CAN go wrong is subtler: each tile computes its own halo to run the
     * containment repair against, and if a tile's view of its neighbour's
     * ground differed from what the neighbour emits, the border would show as a
     * line of anomalous steps in the water.
     *
     * <p>So this measures the distribution of level differences between
     * adjacent wet columns ACROSS a tile border, and compares it against the
     * same distribution INSIDE tiles. Big steps are legal everywhere — a
     * waterfall is one, and two different water bodies meeting is another — but
     * they should be no commoner at a border than anywhere else. If they are,
     * the border is doing something.
     */
    @Test
    public void tileBordersAreNotVisibleInTheWater() {
        long seamPairs = 0;
        long seamSteps = 0;
        long innerPairs = 0;
        long innerSteps = 0;
        int worstSeam = 0;
        for (int tx = 0; tx < TILES; tx++) {
            for (int tz = 0; tz < TILES; tz++) {
                TerrainTile t = sample[tx][tz];
                // Inside: every adjacent wet pair along +X.
                for (int x = t.worldI1(); x < t.worldI2() - 1; x++) {
                    for (int z = t.worldJ1(); z < t.worldJ2(); z++) {
                        int a = t.waterLevelAt(x, z);
                        int b = t.waterLevelAt(x + 1, z);
                        if (a > SEA && b > SEA) {
                            innerPairs++;
                            if (Math.abs(a - b) > 1) {
                                innerSteps++;
                            }
                        }
                    }
                }
                // Across the +X border with the next tile.
                if (tx + 1 < TILES) {
                    TerrainTile n = sample[tx + 1][tz];
                    int xa = t.worldI2() - 1;
                    int xb = n.worldI1();
                    for (int z = t.worldJ1(); z < t.worldJ2(); z++) {
                        int a = t.waterLevelAt(xa, z);
                        int b = n.waterLevelAt(xb, z);
                        if (a > SEA && b > SEA) {
                            seamPairs++;
                            if (Math.abs(a - b) > 1) {
                                seamSteps++;
                                worstSeam = Math.max(worstSeam, Math.abs(a - b));
                            }
                        }
                        // And the invariant that actually matters at a border.
                        if (a > SEA && b == TerrainTile.NO_WATER) {
                            assertTrue(n.heightAt(xb, z) >= a,
                                    "seam leak at (" + xb + "," + z + "): terrain "
                                            + n.heightAt(xb, z) + " < level " + a);
                        }
                        if (b > SEA && a == TerrainTile.NO_WATER) {
                            assertTrue(t.heightAt(xa, z) >= b,
                                    "seam leak at (" + xa + "," + z + "): terrain "
                                            + t.heightAt(xa, z) + " < level " + b);
                        }
                    }
                }
            }
        }
        double seamRate = seamPairs == 0 ? 0 : 100.0 * seamSteps / seamPairs;
        double innerRate = innerPairs == 0 ? 0 : 100.0 * innerSteps / innerPairs;
        System.out.printf("live seams: %.2f %% of %d border pairs step > 1 blk "
                        + "(worst %d), against %.2f %% of %d interior pairs%n",
                seamRate, seamPairs, worstSeam, innerRate, innerPairs);
        assertTrue(seamPairs > 0, "no water straddled a tile border to measure");
        // A border may be no worse than the terrain either side of it. The
        // slack is for sample size: there are ~200x fewer border pairs.
        assertTrue(seamRate <= innerRate * 2.0 + 0.5,
                "steps are commoner at tile borders (" + seamRate + " %) than inside them ("
                        + innerRate + " %), so the border is doing something");
    }

    /**
     * §10's acceptance figure, at block resolution for the first time: how much
     * of the land the water actually covers. Every earlier measurement was on
     * the 16-block DEM lattice.
     */
    @Test
    public void inlandWaterCoversEnoughOfTheLand() {
        long land = 0;
        long inland = 0;
        long sea = 0;
        long carved = 0;
        int deepestWater = 0;
        for (int tx = 0; tx < TILES; tx++) {
            for (int tz = 0; tz < TILES; tz++) {
                TerrainTile t = sample[tx][tz];
                for (int x = t.worldI1(); x < t.worldI2(); x++) {
                    for (int z = t.worldJ1(); z < t.worldJ2(); z++) {
                        int w = t.waterLevelAt(x, z);
                        int h = t.heightAt(x, z);
                        if (w == SEA && h < SEA) {
                            sea++;
                            continue;
                        }
                        land++;
                        if (w > SEA) {
                            inland++;
                            deepestWater = Math.max(deepestWater, w - h);
                        }
                        if (w != TerrainTile.NO_WATER && h < w) {
                            carved++;
                        }
                    }
                }
            }
        }
        double pct = 100.0 * inland / Math.max(1, land);
        System.out.printf("live inland water: %.2f %% of %d land columns "
                        + "(%d sea, %d wet, deepest %d blocks)%n",
                pct, land, sea, inland, deepestWater);
        assertTrue(land > 0, "the sampled area is entirely ocean");
        // 2.5 % is §10's acceptance figure and this is a whole region of real
        // terrain, so it is asserted rather than merely reported. It is still
        // ONE region: how wet a world is varies with where you stand, and the
        // number to watch across seeds is this one printed above.
        assertTrue(pct >= 2.5, "inland water is " + String.format("%.2f", pct)
                + " %% of land, under the 2.5 %% acceptance figure");
        assertTrue(carved > 0, "no column holds water below its own surface");
    }

    /**
     * The river plan as the region actually produced it, reported so a change
     * in density or geometry shows up as a number rather than as a surprise in
     * the world.
     */
    @Test
    public void reportsTheRegionsRiverPlan() {
        BasinCache.Solved s = region;
        int stride = CendaKernels.RIVER_VERTEX_FLOATS;
        int waterfalls = 0;
        int gorges = 0;
        float widest = 0;
        for (int v = 0; v < s.vertexCount(); v++) {
            int flags = (int) s.vertices()[v * stride + 6];
            if ((flags & CendaKernels.RIVER_FLAG_WATERFALL) != 0) {
                waterfalls++;
            }
            if ((flags & CendaKernels.RIVER_FLAG_GORGE) != 0) {
                gorges++;
            }
            widest = Math.max(widest, s.vertices()[v * stride + 3]);
        }
        long lakeCells = 0;
        for (float d : s.depth()) {
            if (d > 0.0f) {
                lakeCells++;
            }
        }
        System.out.printf("live region (0,0): %d basins withheld, %d rivers, %d vertices, "
                        + "%d waterfalls, %d gorge vertices, widest %.1f blocks, "
                        + "%d lake cells%n",
                s.withheld(), s.routeCount(), s.vertexCount(), waterfalls, gorges,
                widest, lakeCells);
        assertEquals(0, s.withheld(),
                "a withheld basin means the region needs L0, which costs ~55 minutes cold");
        assertTrue(lakeCells > 0, "the region has no lakes at all");
    }
}
