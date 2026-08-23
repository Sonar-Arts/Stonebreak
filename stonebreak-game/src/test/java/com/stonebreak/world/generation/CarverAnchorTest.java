package com.stonebreak.world.generation;

import com.stonebreak.world.generation.heightmap.CarveMaskKey;
import com.stonebreak.world.generation.heightmap.CavernCarver;
import com.stonebreak.world.generation.heightmap.MegaCavernCarver;
import com.stonebreak.world.generation.heightmap.PerlinWormCarver;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The predictable-anchor trick, which is what makes the cave network connect.
 *
 * <p>Worm connectors, cavern connectors and sinkhole shafts all aim at a feature in a
 * neighbouring chunk without walking it: they replay that feature's spawn RNG far enough to
 * recover its origin and steer at the result. Three separate methods carry a comment saying
 * they must mirror the first three draws of their carver exactly — {@code computeOrigin},
 * {@code computeCavernOrigin} on both cavern carvers — and the mirror is upheld by nothing
 * but those comments. Insert one {@code nextInt} into a spawn path and the streams part: the
 * anchors keep coming back, they just name points in solid rock, and every connector drives
 * confidently into nothing.
 *
 * <p>That is the same shape as the failure {@code CaveReachabilityTest} was written for — a
 * cave system that generates perfectly and cannot be entered — and it is invisible from up
 * there, because losing the guaranteed links only moves the reachable fraction, and the
 * ratchet floor sits well below the current value.
 *
 * <p>So each test here checks the anchor against the carving it claims to describe: the
 * announced point must be inside the void the carver really cut.
 */
public class CarverAnchorTest {

    private static final long SEED = 12345L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    /** Chunks per side swept for features to check. */
    private static final int REGION = 48;

    /**
     * Share of anchors that must land in carved space.
     *
     * <p>Measured 1.000 for all three carvers (288 worm, 51 cavern, 198 megacavern anchors),
     * and the floor is just under that rather than at it: a carver step lands on rounded
     * block coordinates and its bore is only a few blocks wide, so an origin can in principle
     * sit outside the first sphere when the radius noise bottoms out. The slack costs nothing
     * either way — a desynced stream does not degrade to 0.99, it degrades to roughly the
     * ambient carved fraction, about 0.13.
     */
    private static final double MIN_ANCHORED = 0.99;

    private final DryHillsHeightMap heightMap = new DryHillsHeightMap(SEED);

    @Test
    public void aWormAnchorNamesAPointInsideTheWormsOwnTunnel() {
        PerlinWormCarver worm = new PerlinWormCarver(SEED, heightMap);
        CavernCarver caverns = new CavernCarver(SEED, heightMap);
        MegaCavernCarver megaCaverns = new MegaCavernCarver(SEED, heightMap);
        worm.setCavernCarver(caverns);
        worm.setMegaCavernCarver(megaCaverns);

        int anchors = 0;
        int inTunnel = 0;
        for (int cx = 0; cx < REGION; cx++) {
            for (int cz = 0; cz < REGION; cz++) {
                if (!worm.hasWormAt(cx, cz)) continue;
                // Radius 0 pins the answer to this chunk's own worm, so a hit cannot be
                // some neighbour's tunnel that happened to pass nearby.
                float[] o = worm.nearestWormOrigin(cx, cz, 0);
                assertNotNull(o, "hasWormAt says chunk (" + cx + "," + cz
                        + ") holds a worm but nearestWormOrigin found none there");
                assertEquals(cx, Math.floorDiv(Math.round(o[0]), CHUNK),
                        "worm origin x fell outside its own chunk");
                assertEquals(cz, Math.floorDiv(Math.round(o[2]), CHUNK),
                        "worm origin z fell outside its own chunk");

                anchors++;
                BitSet mask = worm.carveMaskForChunk(cx, cz, heights(cx, cz), waterLevels(cx, cz));
                if (mask.get(CarveMaskKey.pack(Math.round(o[0]) - cx * CHUNK, Math.round(o[1]),
                        Math.round(o[2]) - cz * CHUNK))) {
                    inTunnel++;
                }
            }
        }

        report("worm", inTunnel, anchors);
    }

    @Test
    public void aCavernAnchorNamesAPointInsideTheCavern() {
        CavernCarver carver = new CavernCarver(SEED, heightMap);
        int anchors = 0;
        int inVoid = 0;
        for (int cx = 0; cx < REGION; cx++) {
            for (int cz = 0; cz < REGION; cz++) {
                float[] o = carver.computeCavernOrigin(cx, cz);
                if (o == null) {
                    continue;
                }
                anchors++;
                BitSet mask = carver.buildForChunk(
                        cx, cz, heights(cx, cz), waterLevels(cx, cz)).carveMask;
                if (mask.get(CarveMaskKey.pack(Math.round(o[0]) - cx * CHUNK, Math.round(o[1]),
                        Math.round(o[2]) - cz * CHUNK))) {
                    inVoid++;
                }
            }
        }
        report("cavern", inVoid, anchors);
    }

    @Test
    public void aMegaCavernAnchorNamesAPointInsideTheCavern() {
        MegaCavernCarver carver = new MegaCavernCarver(SEED, heightMap);
        int anchors = 0;
        int inVoid = 0;
        // Megacaverns are 1-in-192 chunks, so a region sized for the others would find one
        // or two and measure noise.
        for (int cx = 0; cx < REGION * 4; cx++) {
            for (int cz = 0; cz < REGION * 4; cz++) {
                float[] o = carver.computeCavernOrigin(cx, cz);
                if (o == null) {
                    continue;
                }
                anchors++;
                BitSet mask = carver.buildForChunk(
                        cx, cz, heights(cx, cz), waterLevels(cx, cz)).carveMask;
                if (mask.get(CarveMaskKey.pack(Math.round(o[0]) - cx * CHUNK, Math.round(o[1]),
                        Math.round(o[2]) - cz * CHUNK))) {
                    inVoid++;
                }
            }
        }
        report("megacavern", inVoid, anchors);
    }

    // ---- the contracts the aiming code relies on ---------------------------------------

    /** An anchor lookup must only ever name a chunk that really holds the feature. */
    @Test
    void anchorsAreOnlyOfferedForChunksThatHoldTheFeature() {
        PerlinWormCarver worm = new PerlinWormCarver(SEED, heightMap);
        CavernCarver caverns = new CavernCarver(SEED, heightMap);
        int checked = 0;

        for (int cx = 0; cx < REGION; cx++) {
            for (int cz = 0; cz < REGION; cz++) {
                if (!worm.hasWormAt(cx, cz)) {
                    assertNull(worm.nearestWormOrigin(cx, cz, 0),
                            "a worm anchor was offered for wormless chunk (" + cx + "," + cz + ")");
                }
                if (!caverns.hasCavern(cx, cz)) {
                    assertNull(caverns.computeCavernOrigin(cx, cz),
                            "a cavern anchor was offered for cavernless chunk ("
                                    + cx + "," + cz + ")");
                }

                float[] near = worm.nearestWormOrigin(cx, cz, 3);
                if (near != null) {
                    int ncx = Math.floorDiv(Math.round(near[0]), CHUNK);
                    int ncz = Math.floorDiv(Math.round(near[2]), CHUNK);
                    assertTrue(worm.hasWormAt(ncx, ncz),
                            "nearestWormOrigin pointed at chunk (" + ncx + "," + ncz
                                    + "), which holds no worm");
                    assertTrue(Math.max(Math.abs(ncx - cx), Math.abs(ncz - cz)) <= 3,
                            "nearestWormOrigin reached outside the radius it was given");
                    checked++;
                }
            }
        }
        assertTrue(checked > 0, "no worm anchors found to check");
    }

    /**
     * The cavern anchor a worm connector is handed is the one the cavern carver publishes.
     * These are two separate call paths into the same replay and they must not drift.
     */
    @Test
    void theConnectorAnchorIsTheCavernCarversOwnAnchor() {
        PerlinWormCarver worm = new PerlinWormCarver(SEED, heightMap);
        CavernCarver caverns = new CavernCarver(SEED, heightMap);
        MegaCavernCarver megaCaverns = new MegaCavernCarver(SEED, heightMap);
        worm.setCavernCarver(caverns);
        worm.setMegaCavernCarver(megaCaverns);

        int matched = 0;
        for (int cx = 0; cx < REGION; cx++) {
            for (int cz = 0; cz < REGION; cz++) {
                if (!worm.hasWormAt(cx, cz)) continue;
                float[] anchor = worm.cavernAnchorFor(cx, cz);
                if (anchor == null) continue;
                int acx = Math.floorDiv(Math.round(anchor[0]), CHUNK);
                int acz = Math.floorDiv(Math.round(anchor[2]), CHUNK);
                float[] published = caverns.hasCavern(acx, acz)
                        ? caverns.computeCavernOrigin(acx, acz)
                        : megaCaverns.computeCavernOrigin(acx, acz);
                assertNotNull(published, "connector anchor at chunk (" + acx + "," + acz
                        + ") belongs to no cavern of either size");
                assertEquals(published[0], anchor[0], "connector aims at a different x");
                assertEquals(published[1], anchor[1], "connector aims at a different y");
                assertEquals(published[2], anchor[2], "connector aims at a different z");
                matched++;
            }
        }
        assertTrue(matched > 0, "no cavern connectors found to check");
    }

    private static void report(String what, int hits, int anchors) {
        assertTrue(anchors > 0, "no " + what + " anchors found — the test measured nothing");
        double share = hits / (double) anchors;
        System.out.printf("[carvers] %s anchors landing in carved space: %.3f (%d/%d)%n",
                what, share, hits, anchors);
        assertTrue(share >= MIN_ANCHORED, String.format(
                "only %.3f of %s anchors name a point that is actually carved (need %.2f). "
                        + "The anchor replay has drifted out of step with the carver's own "
                        + "RNG stream, so every connector aimed at one of these is driving "
                        + "into solid rock",
                share, what, MIN_ANCHORED));
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
}
