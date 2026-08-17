package com.openmason.engine.voxel.mms.mmsGeometry;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the greedy quad merger: rectangles must cover exactly the
 * input quads (no loss, no overlap, no invention), merging must only join
 * same-id uniform-light runs, non-uniform quads must pass through verbatim,
 * and output must be deterministic.
 */
class MmsGreedyMesherTest {

    private static final int IN = MmsGreedyMesher.IN_STRIDE;
    private static final int OUT = MmsGreedyMesher.OUT_STRIDE;

    /** Builds one input record. */
    private static void quad(float[] buf, int i, int x, int y, int z, int face, int id,
                             float l0, float l1, float l2, float l3) {
        int base = i * IN;
        buf[base] = x;
        buf[base + 1] = y;
        buf[base + 2] = z;
        buf[base + 3] = face;
        buf[base + 4] = id;
        buf[base + 5] = l0;
        buf[base + 6] = l1;
        buf[base + 7] = l2;
        buf[base + 8] = l3;
    }

    private static void uniformQuad(float[] buf, int i, int x, int y, int z, int face, int id, float light) {
        quad(buf, i, x, y, z, face, id, light, light, light, light);
    }

    private static int merge(float[] quads, int count, float[][] holder) {
        return MmsGreedyMesher.merge(quads, count, holder);
    }

    // ── basic merging ────────────────────────────────────────────────────

    @Test
    void flatPlaneCollapsesToOneRectangle() {
        float[] in = new float[256 * IN];
        int n = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                uniformQuad(in, n++, x, 5, z, 0, 7, 1.0f);
            }
        }
        float[][] holder = new float[1][];
        int out = merge(in, n, holder);
        assertEquals(1, out, "16x16 uniform top plane must merge into one quad");
        float[] r = holder[0];
        assertEquals(0f, r[0]);
        assertEquals(5f, r[1]);
        assertEquals(0f, r[2]);
        assertEquals(0f, r[3]);
        assertEquals(7f, r[4]);
        assertEquals(16f, r[5], "width");
        assertEquals(16f, r[6], "height");
        assertEquals(1.0f, r[7]);
    }

    @Test
    void sideFacesMergeVerticallyThroughTheFullColumn() {
        // An east-facing 1-wide wall from y=10..40 with sky factor 0 (cave wall).
        float[] in = new float[31 * IN];
        int n = 0;
        for (int y = 10; y <= 40; y++) {
            uniformQuad(in, n++, 4, y, 9, 4, 3, 0.0f);
        }
        float[][] holder = new float[1][];
        int out = merge(in, n, holder);
        assertEquals(1, out);
        float[] r = holder[0];
        assertEquals(1f, r[5], "width (z axis run of 1)");
        assertEquals(31f, r[6], "height (y axis run)");
    }

    @Test
    void differentBlockIdsDoNotMerge() {
        float[] in = new float[2 * IN];
        uniformQuad(in, 0, 0, 5, 0, 0, 1, 1.0f);
        uniformQuad(in, 1, 1, 5, 0, 0, 2, 1.0f);
        float[][] holder = new float[1][];
        assertEquals(2, merge(in, 2, holder));
    }

    @Test
    void differentLightValuesDoNotMerge() {
        float[] in = new float[2 * IN];
        uniformQuad(in, 0, 0, 5, 0, 0, 1, 1.0f);
        uniformQuad(in, 1, 1, 5, 0, 0, 1, 0.87f);
        float[][] holder = new float[1][];
        assertEquals(2, merge(in, 2, holder));
    }

    @Test
    void differentPlanesDoNotMerge() {
        // Same (x,z), adjacent y-planes, top faces: coplanarity is per plane.
        float[] in = new float[2 * IN];
        uniformQuad(in, 0, 3, 5, 3, 0, 1, 1.0f);
        uniformQuad(in, 1, 3, 6, 3, 0, 1, 1.0f);
        float[][] holder = new float[1][];
        assertEquals(2, merge(in, 2, holder));
    }

    @Test
    void nonUniformQuadPassesThroughVerbatim() {
        float[] in = new float[IN];
        quad(in, 0, 2, 9, 3, 2, 5, 0.5f, 0.75f, 1.0f, 0.87f);
        float[][] holder = new float[1][];
        int out = merge(in, 1, holder);
        assertEquals(1, out);
        float[] r = holder[0];
        assertArrayEquals(new float[] {2, 9, 3, 2, 5, 1, 1, 0.5f, 0.75f, 1.0f, 0.87f},
            java.util.Arrays.copyOf(r, OUT));
    }

    @Test
    void nonUniformNeighborsBreakARun() {
        float[] in = new float[3 * IN];
        uniformQuad(in, 0, 0, 5, 0, 0, 1, 1.0f);
        quad(in, 1, 1, 5, 0, 0, 1, 1.0f, 1.0f, 1.0f, 0.87f); // AO-shaded corner
        uniformQuad(in, 2, 2, 5, 0, 0, 1, 1.0f);
        float[][] holder = new float[1][];
        assertEquals(3, merge(in, 3, holder));
    }

    @Test
    void isolatedCubeEmitsSixUnitQuads() {
        float[] in = new float[6 * IN];
        for (int f = 0; f < 6; f++) {
            uniformQuad(in, f, 8, 20, 8, f, 4, 1.0f);
        }
        float[][] holder = new float[1][];
        int out = merge(in, 6, holder);
        assertEquals(6, out);
        for (int q = 0; q < 6; q++) {
            assertEquals(1f, holder[0][q * OUT + 5]);
            assertEquals(1f, holder[0][q * OUT + 6]);
        }
    }

    // ── coverage + determinism ───────────────────────────────────────────

    @Test
    void mergeIsDeterministic() {
        Random rng = new Random(42);
        float[] in = randomStream(rng, 500);
        float[][] h1 = new float[1][];
        int n1 = merge(in, 500, h1);
        float[] first = java.util.Arrays.copyOf(h1[0], n1 * OUT);
        float[][] h2 = new float[1][];
        int n2 = merge(in, 500, h2);
        assertEquals(n1, n2);
        assertArrayEquals(first, java.util.Arrays.copyOf(h2[0], n2 * OUT));
    }

    /**
     * Property fuzz: over random streams, expanding every output rectangle
     * back into unit faces must reproduce the input set exactly — same cells,
     * same face, same id — and every cell covered by a merged (w*h > 1)
     * rectangle must have carried that rectangle's uniform light.
     */
    @Test
    void expandedRectanglesCoverExactlyTheInputQuads() {
        Random rng = new Random(1234);
        for (int round = 0; round < 50; round++) {
            int count = 1 + rng.nextInt(800);
            float[] in = randomStream(rng, count);

            // Input index: key -> uniform light (or NaN sentinel for non-uniform).
            Map<Long, float[]> expected = new HashMap<>();
            for (int i = 0; i < count; i++) {
                int base = i * IN;
                expected.put(key((int) in[base], (int) in[base + 1], (int) in[base + 2],
                        (int) in[base + 3], (int) in[base + 4]),
                    new float[] {in[base + 5], in[base + 6], in[base + 7], in[base + 8]});
            }

            float[][] holder = new float[1][];
            int out = merge(in, count, holder);
            float[] r = holder[0];

            Map<Long, float[]> covered = new HashMap<>();
            for (int q = 0; q < out; q++) {
                int base = q * OUT;
                int x = (int) r[base], y = (int) r[base + 1], z = (int) r[base + 2];
                int face = (int) r[base + 3];
                int id = (int) r[base + 4];
                int w = (int) r[base + 5], h = (int) r[base + 6];
                for (int b = 0; b < h; b++) {
                    for (int a = 0; a < w; a++) {
                        int cx = x, cy = y, cz = z;
                        if (face >= 4) {
                            cz += a; // width runs along z for ±X faces
                        } else {
                            cx += a;
                        }
                        if (face <= 1) {
                            cz += b; // height runs along z for ±Y faces
                        } else {
                            cy += b;
                        }
                        float[] prev = covered.put(key(cx, cy, cz, face, id),
                            new float[] {r[base + 7], r[base + 8], r[base + 9], r[base + 10]});
                        assertTrue(prev == null, "cell covered twice in round " + round);
                    }
                }
                if (w > 1 || h > 1) {
                    assertTrue(r[base + 7] == r[base + 8]
                            && r[base + 7] == r[base + 9]
                            && r[base + 7] == r[base + 10],
                        "merged rectangle must carry uniform light");
                }
            }

            assertEquals(expected.keySet(), covered.keySet(),
                "round " + round + ": expanded coverage must equal the input face set");
            for (Map.Entry<Long, float[]> e : expected.entrySet()) {
                float[] got = covered.get(e.getKey());
                float[] want = e.getValue();
                boolean wantUniform = want[0] == want[1] && want[0] == want[2] && want[0] == want[3];
                if (wantUniform) {
                    assertArrayEquals(new float[] {want[0], want[0], want[0], want[0]}, got);
                } else {
                    assertArrayEquals(want, got, 0f);
                }
            }
        }
    }

    /**
     * Random quad stream: random subsets of cells over a few planes and faces,
     * ids and light values drawn from small pools so merges and splits both
     * happen, with occasional non-uniform corner lights.
     */
    private static float[] randomStream(Random rng, int count) {
        float[] in = new float[count * IN];
        Map<Long, Integer> used = new HashMap<>();
        int i = 0;
        while (i < count) {
            int face = rng.nextInt(6);
            int x = rng.nextInt(16);
            int z = rng.nextInt(16);
            int y = rng.nextInt(24); // small y range → collisions → real merges
            int id = 1 + rng.nextInt(3);
            long cellKey = key(x, y, z, face, 0);
            if (used.containsKey(cellKey)) {
                continue; // one quad per cell+face, like a real mesher
            }
            used.put(cellKey, i);
            float base = rng.nextInt(3) * 0.5f;
            if (rng.nextInt(5) == 0) {
                quad(in, i, x, y, z, face, id, base, base, Math.min(1f, base + 0.13f), base);
            } else {
                uniformQuad(in, i, x, y, z, face, id, base);
            }
            i++;
        }
        return in;
    }

    private static long key(int x, int y, int z, int face, int id) {
        return ((long) id << 40) | ((long) face << 32) | ((long) y << 16) | ((long) z << 8) | x;
    }
}
