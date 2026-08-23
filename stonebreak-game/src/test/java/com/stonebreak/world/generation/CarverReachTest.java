package com.stonebreak.world.generation;

import com.stonebreak.world.generation.heightmap.CavernCarver;
import com.stonebreak.world.generation.heightmap.CarveMaskKey;
import com.stonebreak.world.generation.heightmap.MegaCavernCarver;
import com.stonebreak.world.generation.heightmap.RavineCarver;
import com.stonebreak.world.generation.heightmap.SinkholeCarver;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every carver's {@code SCAN_RADIUS} must still cover how far that carver actually reaches.
 *
 * <p>Each carver builds a chunk's mask by looping over the source chunks within its own
 * {@code SCAN_RADIUS} and asking each what it contributes here. That radius is a constant
 * derived by hand from the carver's blob radii and offsets. Retune a radius or an offset
 * upward without recomputing it and the failure is silent and one-sided: the chunks near a
 * feature still carve it, the chunks just outside the stale scan window do not, and the
 * feature is sliced off flat along a chunk border. Nothing else in the suite looks at chunk
 * borders — {@code CaveVolumeTest} and {@code CaveReachabilityTest} both measure a whole
 * region in aggregate, where a few clipped chunks vanish into the average.
 *
 * <p>Ravines can be asked directly, because {@link RavineCarver#carveMaskForChunkFrom} exposes
 * one named ravine's contribution to one chunk: sweep targets past the scan window and the
 * mask must be empty out there. The other carvers have no such entry point, so the test finds
 * a feature with no other of its kind within reach, measures how far its carving actually
 * gets from its own origin, and checks that against the radius. A reach of {@code R} blocks
 * needs {@code SCAN_RADIUS >= R/16 + 1} chunks, since the origin can sit anywhere inside its
 * own chunk — which is the same arithmetic the constants are derived by, checked against what
 * the carver does rather than against what it was meant to do.
 */
public class CarverReachTest {

    private static final long SEED = 12345L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;

    /** Chunks per side swept looking for isolated features. */
    private static final int SEARCH = 220;
    /** Isolated features to measure per carver, once found. */
    private static final int SAMPLES = 8;

    private final DryHillsHeightMap heightMap = new DryHillsHeightMap(SEED);

    // ---- ravines: exact, via the single-ravine entry point ------------------------------

    /**
     * A ravine is the longest thing any carver draws — up to a couple of hundred blocks of
     * arm — so its scan window is the one most likely to be outgrown by a retune.
     */
    @Test
    public void aRavineNeverReachesPastItsScanWindow() {
        RavineCarver carver = new RavineCarver(SEED, heightMap);
        int radius = RavineCarver.SCAN_RADIUS;
        int checked = 0;

        for (int cx = 0; cx < SEARCH && checked < SAMPLES; cx++) {
            for (int cz = 0; cz < SEARCH && checked < SAMPLES; cz++) {
                if (!carver.hasRavine(cx, cz)) continue;
                checked++;
                // One ring past the scan window: anything carved out here is carving the
                // production path would never have asked for, i.e. a sliced-off ravine.
                for (int dcx = -radius - 1; dcx <= radius + 1; dcx++) {
                    for (int dcz = -radius - 1; dcz <= radius + 1; dcz++) {
                        if (Math.max(Math.abs(dcx), Math.abs(dcz)) <= radius) continue;
                        int tcx = cx + dcx;
                        int tcz = cz + dcz;
                        BitSet mask = carver.carveMaskForChunkFrom(
                                cx, cz, tcx, tcz, heights(tcx, tcz), waterLevels(tcx, tcz));
                        assertTrue(mask.isEmpty(), String.format(
                                "the ravine at chunk (%d,%d) carves %d blocks into chunk "
                                        + "(%d,%d), which is %d chunks away — past its "
                                        + "SCAN_RADIUS of %d, so the production path never "
                                        + "asks for them and the ravine is cut off flat at "
                                        + "the border",
                                cx, cz, mask.cardinality(), tcx, tcz,
                                Math.max(Math.abs(dcx), Math.abs(dcz)), radius));
                    }
                }
            }
        }
        assertTrue(checked > 0, "no ravine found in " + SEARCH + "x" + SEARCH + " chunks");
    }

    // ---- caverns, megacaverns, sinkholes: measured reach vs the radius -------------------

    @Test
    public void aCavernFitsInsideItsScanWindow() {
        CavernCarver carver = new CavernCarver(SEED, heightMap);
        assertReachFits("cavern", CavernCarver.SCAN_RADIUS, carver::hasCavern,
                carver::computeCavernOrigin,
                (cx, cz) -> carver.buildForChunk(cx, cz, heights(cx, cz), waterLevels(cx, cz))
                        .carveMask);
    }

    @Test
    public void aMegaCavernFitsInsideItsScanWindow() {
        MegaCavernCarver carver = new MegaCavernCarver(SEED, heightMap);
        assertReachFits("megacavern", MegaCavernCarver.SCAN_RADIUS, carver::hasCavern,
                carver::computeCavernOrigin,
                (cx, cz) -> carver.buildForChunk(cx, cz, heights(cx, cz), waterLevels(cx, cz))
                        .carveMask);
    }

    @Test
    public void aSinkholeFitsInsideItsScanWindow() {
        SinkholeCarver carver = new SinkholeCarver(SEED, heightMap);
        assertReachFits("sinkhole", SinkholeCarver.SCAN_RADIUS, carver::hasSinkhole,
                // A sinkhole has no published origin; its shaft is centred somewhere in its
                // own chunk, so the chunk centre is the worst-case-safe reference point and
                // the half-chunk slack is folded into the bound below.
                (cx, cz) -> new float[] { cx * CHUNK + CHUNK / 2f, 0f, cz * CHUNK + CHUNK / 2f },
                (cx, cz) -> carver.carveMaskForChunk(cx, cz, heights(cx, cz), waterLevels(cx, cz)));
    }

    /**
     * Finds isolated features, measures the farthest any of them carves from its own origin
     * column, and checks that against the scan radius.
     *
     * <p>Isolation is over {@code 2 * SCAN_RADIUS} chunks, which is what makes the
     * measurement attributable: a second feature that far away cannot contribute to any of
     * the target chunks swept here, so every carved block in the window belongs to the one
     * being measured.
     */
    private void assertReachFits(String what, int scanRadius, BiPredicate<Integer, Integer> has,
                                 OriginFn origin, MaskFn mask) {
        int isolation = 2 * scanRadius;
        double worstReach = 0;
        int worstCx = 0, worstCz = 0;
        int measured = 0;

        for (int cx = 0; cx < SEARCH && measured < SAMPLES; cx++) {
            for (int cz = 0; cz < SEARCH && measured < SAMPLES; cz++) {
                if (!has.test(cx, cz) || !isIsolated(has, cx, cz, isolation)) continue;
                float[] o = origin.at(cx, cz);
                if (o == null) continue;
                measured++;

                for (int dcx = -scanRadius; dcx <= scanRadius; dcx++) {
                    for (int dcz = -scanRadius; dcz <= scanRadius; dcz++) {
                        int tcx = cx + dcx;
                        int tcz = cz + dcz;
                        BitSet m = mask.at(tcx, tcz);
                        for (int bit = m.nextSetBit(0); bit >= 0; bit = m.nextSetBit(bit + 1)) {
                            double wx = tcx * CHUNK + CarveMaskKey.x(bit);
                            double wz = tcz * CHUNK + CarveMaskKey.z(bit);
                            double d = Math.hypot(wx - o[0], wz - o[2]);
                            if (d > worstReach) {
                                worstReach = d;
                                worstCx = cx;
                                worstCz = cz;
                            }
                        }
                    }
                }
            }
        }

        assertTrue(measured > 0, String.format(
                "no isolated %s found in %dx%d chunks — the test measured nothing", what,
                SEARCH, SEARCH));

        // An origin may sit anywhere in its own chunk, so a feature reaching R blocks can
        // touch a chunk ceil(R/16) + 1 away in the worst case. That is exactly how each
        // SCAN_RADIUS was derived; here it is checked against carving that really happened.
        int needed = (int) Math.ceil(worstReach / CHUNK) + 1;
        System.out.printf("[carvers] %s reach: %.1f blocks over %d isolated samples "
                        + "(needs SCAN_RADIUS %d, has %d)%n",
                what, worstReach, measured, needed, scanRadius);

        assertTrue(needed <= scanRadius, String.format(
                "the %s at chunk (%d,%d) carves %.1f blocks from its origin, which needs a "
                        + "SCAN_RADIUS of %d, but the carver only scans %d chunks — chunks "
                        + "past that window never ask this feature for its blocks, so it is "
                        + "cut off flat at a chunk border",
                what, worstCx, worstCz, worstReach, needed, scanRadius));
    }

    private static boolean isIsolated(BiPredicate<Integer, Integer> has, int cx, int cz, int r) {
        for (int dcx = -r; dcx <= r; dcx++) {
            for (int dcz = -r; dcz <= r; dcz++) {
                if (dcx == 0 && dcz == 0) continue;
                if (has.test(cx + dcx, cz + dcz)) return false;
            }
        }
        return true;
    }

    private int[] heights(int cx, int cz) {
        int[] h = new int[CHUNK * CHUNK];
        heightMap.populateChunkHeights(cx, cz, h);
        return h;
    }

    private int[] waterLevels(int cx, int cz) {
        int[] h = new int[CHUNK * CHUNK];
        int[] w = new int[CHUNK * CHUNK];
        heightMap.populateChunkHeights(cx, cz, h, w);
        return w;
    }

    @FunctionalInterface
    private interface OriginFn {
        float[] at(int cx, int cz);
    }

    @FunctionalInterface
    private interface MaskFn {
        BitSet at(int cx, int cz);
    }
}
