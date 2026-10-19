package com.openmason.engine.voxel.mms.mmsGeometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that merging is altitude-independent.
 *
 * <p>The plane grid used to be a fixed {@code 16×16×256}, so every quad at
 * y ≥ 256 failed the bounds check and passed through UNMERGED. On this branch
 * ({@code WORLD_HEIGHT} 1024, sea level 320) that meant surface terrain never
 * merged at all — a ~256× quad explosion that blew
 * {@code MmsQuadCodec.MAX_QUADS_PER_DRAW} and pushed near-field chunks onto the
 * fallback path, so terrain above y=256 went missing in game. The failure was
 * silent: no exception, no dropped quad, just a merge that stopped happening.
 */
class MmsGreedyMesherWorldHeightTest {

    /** A full 16×16 sheet of identically-lit faces of one block id at height {@code y}. */
    private static float[] sheet(int y, int face) {
        float[] q = new float[256 * MmsGreedyMesher.IN_STRIDE];
        int i = 0;
        for (int a = 0; a < 16; a++) {
            for (int b = 0; b < 16; b++) {
                int o = i++ * MmsGreedyMesher.IN_STRIDE;
                // For ±Y faces the sheet spans x/z at one y; for side faces it
                // spans the in-plane axis and y, anchored at the given height.
                if (face <= 1) {
                    q[o] = a; q[o + 1] = y; q[o + 2] = b;
                } else if (face <= 3) {
                    q[o] = a; q[o + 1] = y + b; q[o + 2] = 0;
                } else {
                    q[o] = 0; q[o + 1] = y + b; q[o + 2] = a;
                }
                q[o + 3] = face;
                q[o + 4] = 1;
                q[o + 5] = q[o + 6] = q[o + 7] = q[o + 8] = 1f;
            }
        }
        return q;
    }

    private static int mergedCount(int y, int face) {
        float[][] holder = new float[1][];
        return MmsGreedyMesher.merge(sheet(y, face), 256, holder);
    }

    @Test
    void aUniformSheetMergesToOneRectangleAtEveryAltitude() {
        for (int face = 0; face < 6; face++) {
            int lowland = mergedCount(64, face);
            assertEquals(1, lowland, "face " + face + " merges at main's sea level");
            for (int y : new int[]{255, 256, 320, 512, 900, 1000}) {
                assertEquals(lowland, mergedCount(y, face),
                    "face " + face + " at y=" + y + " must merge exactly as at y=64");
            }
        }
    }

    @Test
    void quadsAboveTheAddressableCeilingStillPassThrough() {
        // Not merged, but never dropped: the caller relies on getting every face back.
        int n = mergedCount(MmsGreedyMesher.MAX_WORLD_HEIGHT + 10, 0);
        assertEquals(256, n, "out-of-range quads pass through unmerged");
    }

    @Test
    void theGridIsReusedCleanlyBetweenTallAndShortChunks() {
        // The grid grows for a tall chunk and is kept; a later short chunk indexes it
        // with a smaller stride. Stale non-zero cells would corrupt that merge.
        assertEquals(1, mergedCount(900, 2));
        assertEquals(1, mergedCount(10, 2), "short chunk after a tall one");
        assertEquals(1, mergedCount(900, 2), "tall chunk again");
        assertTrue(MmsGreedyMesher.quadsOut() > 0);
    }
}
