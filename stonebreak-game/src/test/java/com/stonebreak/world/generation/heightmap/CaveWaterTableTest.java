package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.DryHillsHeightMap;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The water table is the datum every carver steers by, and it is pure arithmetic — so it is
 * the one part of the cave system that can be pinned exactly rather than measured.
 *
 * <p>{@code CaveStoreyStructureTest} already asks the statistical question, "does carved
 * volume cluster into galleries", and it would keep passing through several ways of getting
 * this class wrong: a roof clamp that stopped winning would breach galleries to the surface
 * while leaving the enrichment ratio untouched, and a {@code tableForChunk} that drifted from
 * {@code tableFrom} would give the batched block fill and the worm walk two different tables
 * for the same column — a seam, not a ratio.
 *
 * <p>The private tuning constants are deliberately not restated here. Everything is asserted
 * either as an inequality or against a value derived from the public API, so retuning DAMP,
 * WOBBLE or LEVEL_SPACING does not trip this test and breaking an invariant does.
 */
class CaveWaterTableTest {

    private static final long SEED = 4242L;
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final int NO_WATER = WorldConfiguration.NO_WATER;

    private final DryHillsHeightMap heightMap = new DryHillsHeightMap(SEED);
    private final CaveWaterTable table = new CaveWaterTable(SEED, heightMap);

    // ---- the column resolve ------------------------------------------------------------

    /**
     * The invariant the whole class exists to keep: the top of the highest gallery band stays
     * under rock. A table that reached the surface would carve an open trench across the
     * landscape, which is the failure the roof clamp is there to prevent.
     */
    @Test
    void everySurfaceLeavesRockOverTheTopGallery() {
        for (int surface = 20; surface < 240; surface += 7) {
            for (int wx = -300; wx <= 300; wx += 97) {
                int t = table.tableFrom(wx, wx * 3, surface, NO_WATER);
                assertTrue(t + CaveWaterTable.BAND < surface,
                        "gallery band tops out at or above the surface: table=" + t
                                + " for surface=" + surface);
            }
        }
    }

    /** A wet column takes the water's own level, so galleries meet the river the player sees. */
    @Test
    void aWetColumnPinsTheTableToItsWaterSurface() {
        int surface = 140;
        int waterLevel = 95;
        assertEquals(waterLevel, table.tableFrom(0, 0, surface, waterLevel));
    }

    /**
     * The roof clamp is applied last so it always wins — including over the wet-column pin.
     * A shallow pond whose level sits just under its own banks must not drag the table up to
     * within a few blocks of the ground.
     */
    @Test
    void theRoofClampOutranksTheWetColumnPin() {
        int surface = 140;
        int pinned = table.tableFrom(0, 0, surface, surface - 2);
        assertTrue(pinned + CaveWaterTable.BAND < surface,
                "a water level just under the surface was taken at face value: table=" + pinned
                        + " for surface=" + surface);
        // Every water level too high for the roof clamps to the same value, so the clamp is
        // what decided this — not some arithmetic that happened to land low.
        assertEquals(pinned, table.tableFrom(0, 0, surface, surface - 1),
                "the roof clamp should pin every too-high water level to the same table");
    }

    /**
     * ...and over the floor clamp too. Under very low ground the table goes below the floor
     * rather than punching a gallery through the surface; "last wins" is the documented
     * ordering and the two clamps genuinely conflict here.
     */
    @Test
    void theRoofClampOutranksTheFloorClamp() {
        int lowSurface = 14;
        int t = table.tableFrom(0, 0, lowSurface, NO_WATER);
        assertTrue(t + CaveWaterTable.BAND < lowSurface,
                "floor clamp pushed the table up through a low surface: table=" + t);
    }

    /**
     * The batched per-chunk fill and the point sample must agree exactly. The block fill uses
     * the grid and the worm walk uses the point method, on the same columns, so any drift
     * between them puts a gallery at two different heights depending on who asked.
     */
    @Test
    void theBatchedTableMatchesThePointSampleColumnForColumn() {
        int[] heights = new int[CHUNK_SIZE * CHUNK_SIZE];
        int[] waterLevels = new int[CHUNK_SIZE * CHUNK_SIZE];
        for (int chunkX = -2; chunkX <= 2; chunkX++) {
            for (int chunkZ = -2; chunkZ <= 2; chunkZ++) {
                heightMap.populateChunkHeights(chunkX, chunkZ, heights, waterLevels);
                int[] batched = table.tableForChunk(chunkX, chunkZ, heights, waterLevels);
                for (int x = 0; x < CHUNK_SIZE; x++) {
                    for (int z = 0; z < CHUNK_SIZE; z++) {
                        int i = x * CHUNK_SIZE + z;
                        int wx = chunkX * CHUNK_SIZE + x;
                        int wz = chunkZ * CHUNK_SIZE + z;
                        assertEquals(table.tableFrom(wx, wz, heights[i], waterLevels[i]),
                                batched[i],
                                () -> "batched and point tables disagree at " + wx + "," + wz);
                        assertEquals(batched[i], table.tableAt(wx, wz),
                                () -> "tableAt disagrees with the batched grid at "
                                        + wx + "," + wz);
                    }
                }
            }
        }
    }

    /** A null water plane is the "no water anywhere" case, not a crash. */
    @Test
    void aNullWaterPlaneResolvesAsDryGround() {
        int[] heights = new int[CHUNK_SIZE * CHUNK_SIZE];
        heightMap.populateChunkHeights(0, 0, heights);
        int[] dry = table.tableForChunk(0, 0, heights, null);
        for (int i = 0; i < dry.length; i++) {
            int x = i / CHUNK_SIZE;
            int z = i % CHUNK_SIZE;
            assertEquals(table.tableFrom(x, z, heights[i], NO_WATER), dry[i]);
        }
    }

    // ---- storeys and zones -------------------------------------------------------------

    @Test
    void storeysDescendByAFixedSpacingToTheDeepest() {
        int t = 120;
        int spacing = CaveWaterTable.storeyY(t, 0) - CaveWaterTable.storeyY(t, 1);
        assertEquals(t, CaveWaterTable.storeyY(t, 0), "storey 0 is the present table");
        assertTrue(spacing > 2 * CaveWaterTable.BAND,
                "storeys are spaced " + spacing + " apart but bands are "
                        + (2 * CaveWaterTable.BAND + 1) + " tall — adjacent galleries merge "
                        + "into one slab instead of reading as separate levels");
        int deepest = CaveWaterTable.deepestStoreyY(t);
        assertEquals(0, (t - deepest) % spacing,
                "the deepest storey is not on the storey spacing");
        assertTrue(deepest < t, "deepest storey is not below the present table");
    }

    @Test
    void zonesPartitionTheColumnAroundTheStoreys() {
        int t = 120;
        int spacing = CaveWaterTable.storeyY(t, 0) - CaveWaterTable.storeyY(t, 1);
        int storeys = (t - CaveWaterTable.deepestStoreyY(t)) / spacing + 1;

        assertSame(CaveWaterTable.Zone.VADOSE,
                CaveWaterTable.zoneAt(t, t + CaveWaterTable.BAND + 1),
                "the first block clear of the top band should be vadose");
        assertSame(CaveWaterTable.Zone.EPIPHREATIC,
                CaveWaterTable.zoneAt(t, t + CaveWaterTable.BAND),
                "the top of the present table's band is still a gallery");

        for (int k = 0; k < storeys; k++) {
            int storey = CaveWaterTable.storeyY(t, k);
            for (int dy = -CaveWaterTable.BAND; dy <= CaveWaterTable.BAND; dy++) {
                assertSame(CaveWaterTable.Zone.EPIPHREATIC, CaveWaterTable.zoneAt(t, storey + dy),
                        "y=" + (storey + dy) + " is inside storey " + k + "'s band");
            }
        }

        assertSame(CaveWaterTable.Zone.PHREATIC,
                CaveWaterTable.zoneAt(t, CaveWaterTable.deepestStoreyY(t) - CaveWaterTable.BAND - 1),
                "below every storey band the column is phreatic");
        // Between two storeys is phreatic too — the zone is "not vadose, not in a band",
        // which is wider than the class javadoc's "below them all".
        assertSame(CaveWaterTable.Zone.PHREATIC,
                CaveWaterTable.zoneAt(t, t - spacing / 2),
                "the gap between two storeys is phreatic");
    }

    @Test
    void galleryWeightPeaksOnEachStoreyAndVanishesOffIt() {
        int t = 120;
        int spacing = CaveWaterTable.storeyY(t, 0) - CaveWaterTable.storeyY(t, 1);
        int storeys = (t - CaveWaterTable.deepestStoreyY(t)) / spacing + 1;

        for (int k = 0; k < storeys; k++) {
            int storey = CaveWaterTable.storeyY(t, k);
            assertEquals(1f, CaveWaterTable.galleryWeight(t, storey), 1e-6f,
                    "storey " + k + " does not peak at its own level");
            assertEquals(0f, CaveWaterTable.galleryWeight(t, storey + CaveWaterTable.BAND), 1e-6f,
                    "storey " + k + " has not fallen to zero at the edge of its band");
        }

        // Never negative and never over 1 anywhere in the column, including far outside every
        // band — the weight feeds a carve threshold directly, so an out-of-range value would
        // carve (or refuse to carve) a whole slab.
        for (int y = 1; y < WorldConfiguration.WORLD_HEIGHT; y++) {
            float w = CaveWaterTable.galleryWeight(t, y);
            assertTrue(w >= 0f && w <= 1f, "gallery weight " + w + " out of [0,1] at y=" + y);
            if (w > 0f) {
                assertSame(CaveWaterTable.Zone.EPIPHREATIC, CaveWaterTable.zoneAt(t, y),
                        "y=" + y + " carries gallery weight " + w + " but is not in a gallery "
                                + "zone — the threshold and the steering disagree");
            }
        }
    }

    /** Different seeds must give different wobble, or every world gets the same table. */
    @Test
    void theWobbleIsSeedDependent() {
        CaveWaterTable other = new CaveWaterTable(SEED + 1, heightMap);
        boolean differs = false;
        for (int wx = 0; wx < 400 && !differs; wx += 37) {
            differs = table.tableAt(wx, wx * 2) != other.tableAt(wx, wx * 2);
        }
        assertTrue(differs, "two seeds produced the same water table everywhere sampled");
    }

    @Test
    void theTableIsStableForTheSameSeedAndColumn() {
        CaveWaterTable twin = new CaveWaterTable(SEED, heightMap);
        for (int wx = -200; wx <= 200; wx += 41) {
            assertEquals(table.tableAt(wx, -wx), twin.tableAt(wx, -wx),
                    "the same seed gave two different tables at " + wx);
        }
    }
}
