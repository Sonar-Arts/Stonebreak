package com.stonebreak.world.generation;

import com.stonebreak.world.generation.heightmap.CarveMaskKey;
import com.stonebreak.world.generation.heightmap.CavernCarver;
import com.stonebreak.world.generation.heightmap.FormationSupport;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.heightmap.MegaCavernCarver;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stalagmites and stalactites must touch the rock they grow from.
 *
 * <p>Two separate mechanisms are needed for that, and this covers both.
 *
 * <p><b>Inside the carver.</b> A formation is anchored to the tallest contiguous run of
 * carved cells in its column. The scan it replaced took the column's overall lowest and
 * highest carved cell, which in a column clipping two blobs spans the solid rock between
 * them: the pillar grew out of the lower room, through that rock, and its tip surfaced in
 * the upper one looking like a formation floating in mid-air. Every formation cell being
 * inside the carve mask is the invariant that fails under the old scan and holds under the
 * new one — it is checked against real generated caverns rather than a fixture, because the
 * two-blob column is a thing the blob cluster produces on its own and a fixture would only
 * assert that the author imagined the failure correctly.
 *
 * <p><b>Outside the carver.</b> Being inside your own carver's mask is not enough, since
 * worms, ravines, sinkholes and the noise field all carve afterwards and formations are
 * written as STONE before the carve mask is applied — so a pillar survives the cut that
 * removed its floor. {@link FormationSupport} is what drops those, and it is exercised here
 * directly: its input is a mask plus a predicate, so a fixture states the cases exactly.
 */
public class CaveFormationAttachmentTest {

    private static final long SEED = 24680L;
    private static final int REGION = 8;

    @Test
    public void everyFormationCellSitsInsideTheVoidItGrowsFrom() {
        HeightMapGenerator heightMap = new DryHillsHeightMap(SEED);
        CavernCarver caverns = new CavernCarver(SEED, heightMap);
        MegaCavernCarver megaCaverns = new MegaCavernCarver(SEED, heightMap);

        int cells = WorldConfiguration.CHUNK_SIZE * WorldConfiguration.CHUNK_SIZE;
        int[] heights = new int[cells];
        int[] waterLevels = new int[cells];
        long formationCells = 0;

        for (int cx = 0; cx < REGION; cx++) {
            for (int cz = 0; cz < REGION; cz++) {
                heightMap.populateChunkHeights(cx, cz, heights, waterLevels);
                CavernCarver.Result cavern = caverns.buildForChunk(cx, cz, heights, waterLevels);
                formationCells += assertContained(
                        cavern.formationMask, cavern.carveMask, "cavern", cx, cz);
                MegaCavernCarver.Result mega =
                        megaCaverns.buildForChunk(cx, cz, heights, waterLevels);
                formationCells += assertContained(
                        mega.formationMask, mega.carveMask, "megacavern", cx, cz);
            }
        }

        System.out.printf("[caves] %d formation cells over %dx%d chunks, all inside their own "
                + "carved volume%n", formationCells, REGION, REGION);
        assertTrue(formationCells > 0,
                "no formations generated at all over " + REGION + "x" + REGION
                        + " chunks — the test measured nothing");
    }

    /** Every set bit of {@code formations} must also be set in {@code carve}. */
    private static long assertContained(BitSet formations, BitSet carve, String what,
                                        int cx, int cz) {
        long count = 0;
        for (int bit = formations.nextSetBit(0); bit >= 0; bit = formations.nextSetBit(bit + 1)) {
            count++;
            assertTrue(carve.get(bit), String.format(
                    "%s formation cell at local (%d,%d,%d) in chunk (%d,%d) is in solid rock, "
                            + "not in the void it grows from — it will read as a floating pillar",
                    what, CarveMaskKey.x(bit), CarveMaskKey.y(bit), CarveMaskKey.z(bit), cx, cz));
        }
        return count;
    }

    @Test
    public void prunedFormationsAreTheOnesNothingHoldsUp() {
        BitSet formations = new BitSet();
        set(formations, 50, 52);   // floating: rock at neither end
        set(formations, 60, 62);   // stalagmite: rests on solid at 59
        set(formations, 70, 72);   // stalactite: hangs from solid at 73

        FormationSupport.prune(formations, (x, y, z) -> y == 59 || y == 73);

        for (int y = 50; y <= 52; y++) {
            assertFalse(formations.get(CarveMaskKey.pack(0, y, 0)),
                    "unsupported formation cell at y=" + y + " survived the prune");
        }
        for (int y = 60; y <= 62; y++) {
            assertTrue(formations.get(CarveMaskKey.pack(0, y, 0)),
                    "stalagmite standing on solid rock was pruned at y=" + y);
        }
        for (int y = 70; y <= 72; y++) {
            assertTrue(formations.get(CarveMaskKey.pack(0, y, 0)),
                    "stalactite hanging from solid rock was pruned at y=" + y);
        }
    }

    private static void set(BitSet mask, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) {
            mask.set(CarveMaskKey.pack(0, y, 0));
        }
    }
}
