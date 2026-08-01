package com.stonebreak.world.generation.water;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The native rivers/lakes tile pass, exercised through the same wrapper the
 * production tile chain installs. All tests skip without the Cenda kernels
 * library (build the release preset under openmason-engine/cenda).
 *
 * <p>The raw fake mirrors what the bridge serves with its hydrology off: pure
 * per-column heights and a sea-level-only water plane. Everything inland the
 * wrapper emits therefore comes from {@code ck_carve_water}, and the
 * containment/seam assertions here validate the JAVA side of the contract —
 * above all the 3x3 window assembly, whose row/column orientation a transposed
 * bug would corrupt silently (both axes are the same length).
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

        static int height(int x, int z) {
            double h = 330.0
                    + 14.0 * Math.sin(x * 0.011) * Math.cos(z * 0.009)
                    + 6.0 * Math.sin(x * 0.031 + 1.7) * Math.sin(z * 0.027)
                    + 3.0 * Math.cos((x + z) * 0.05);
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

    private static NativeWaterTiles wrapper() {
        return new NativeWaterTiles(new RawHillsTiles(), SEED, TILE, 64);
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
    public void deterministicAndCached() {
        NativeWaterTiles a = wrapper();
        NativeWaterTiles b = wrapper();
        TerrainTile t1 = a.getTile(-700, 300);
        TerrainTile t2 = b.getTile(-700, 300);
        assertArrayEquals(t1.blockHeights(), t2.blockHeights());
        assertArrayEquals(t1.waterLevels(), t2.waterLevels());
        assertSame(t1, a.getTile(-700, 300), "same instance from the wrapper's cache");
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

        // Somewhere across a stretch of inland tiles the kernel must have
        // produced water above sea level (rivers or lakes) and carved ground.
        int wet = 0;
        int carved = 0;
        for (int tx = 0; tx < 4; tx++) {
            TerrainTile t = tiles.getTile(tx * TILE, TILE);
            for (int x = t.worldI1(); x < t.worldI2(); x++) {
                for (int z = t.worldJ1(); z < t.worldJ2(); z++) {
                    if (t.waterLevelAt(x, z) > SEA) wet++;
                    if (t.heightAt(x, z) < RawHillsTiles.height(x, z)) carved++;
                }
            }
        }
        assertTrue(wet > 0, "no inland water over 4 tiles");
        assertTrue(carved > 0, "no carved columns over 4 tiles");
    }
}
