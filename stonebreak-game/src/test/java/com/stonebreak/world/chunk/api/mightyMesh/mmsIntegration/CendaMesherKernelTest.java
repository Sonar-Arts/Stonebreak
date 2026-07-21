package com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration;

import com.openmason.engine.cenda.CendaKernels;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Bit-exact parity: the native mesher kernel vs a Java reference of the same
 * culling + lighting semantics (MmsCcoAdapter cube path + VertexLightSampler),
 * evaluated over identical snapshot arrays. Any divergence — culling rule,
 * face offsets, sky/AO math, float rounding — fails loudly here.
 */
class CendaMesherKernelTest {

    private static final int AIR = 0, STONE = 1, LEAVES = 2, WATER = 3, ICE_LIKE = 4;
    private static final byte[] CLASS_TABLE = new byte[5];

    static {
        CLASS_TABLE[STONE] = CendaKernels.CLASS_CUBE | CendaKernels.CLASS_OPAQUE_LIGHT;
        CLASS_TABLE[LEAVES] = CendaKernels.CLASS_CUBE | CendaKernels.CLASS_TRANSPARENT;
        CLASS_TABLE[WATER] = CendaKernels.CLASS_TRANSPARENT;             // special cell (java pass)
        CLASS_TABLE[ICE_LIKE] = CendaKernels.CLASS_CUBE | CendaKernels.CLASS_TRANSPARENT;
    }

    // MmsCuboidGenerator.FACE_VERTEX_OFFSETS, verbatim.
    private static final int[][][] FACE_OFFSETS = {
        {{0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}},
        {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
        {{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}},
        {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
        {{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}},
        {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
    };
    private static final int[] FDX = {0, 0, 0, 0, 1, -1};
    private static final int[] FDY = {1, -1, 0, 0, 0, 0};
    private static final int[] FDZ = {0, 0, -1, 1, 0, 0};

    private record Fixture(short[] blocks, short[] planeXn, short[] planeXp,
                           short[] planeZn, short[] planeZp,
                           short[] cornerNn, short[] cornerPn, short[] cornerNp, short[] cornerPp,
                           short[] heights, int maxY) {

        int block(int x, int y, int z) {
            if (y < 0 || y >= 256) return AIR;
            if (x >= 0 && x < 16 && z >= 0 && z < 16) return blocks[(y * 16 + z) * 16 + x];
            if (x < 0) return planeXn == null ? AIR : planeXn[y * 16 + z];
            if (x >= 16) return planeXp == null ? AIR : planeXp[y * 16 + z];
            if (z < 0) return planeZn == null ? AIR : planeZn[y * 16 + x];
            return planeZp == null ? AIR : planeZp[y * 16 + x];
        }

        boolean solid(int x, int y, int z) {
            if (y < 0 || y >= 256) return false;
            boolean xn = x < 0, xp = x >= 16, zn = z < 0, zp = z >= 16;
            short[] col = null;
            if (!xn && !xp && !zn && !zp) return (cls(blocks[(y * 16 + z) * 16 + x]) & CendaKernels.CLASS_OPAQUE_LIGHT) != 0;
            if (xn && zn) col = cornerNn;
            else if (xp && zn) col = cornerPn;
            else if (xn && zp) col = cornerNp;
            else if (xp && zp) col = cornerPp;
            if (col != null || (xn || xp) && (zn || zp)) {
                return col != null && (cls(col[y]) & CendaKernels.CLASS_OPAQUE_LIGHT) != 0;
            }
            short[] plane = xn ? planeXn : xp ? planeXp : zn ? planeZn : planeZp;
            int idx = (xn || xp) ? y * 16 + z : y * 16 + x;
            return plane != null && (cls(plane[idx]) & CendaKernels.CLASS_OPAQUE_LIGHT) != 0;
        }

        int height(int lx, int lz) {
            return heights[(lz + 1) * 18 + (lx + 1)];
        }
    }

    private static int cls(int id) {
        return id >= 0 && id < CLASS_TABLE.length ? CLASS_TABLE[id] : 0;
    }

    /** Java reference: emits quad-key -> {id, lights} identical to the kernel contract. */
    private static Map<Integer, float[]> referenceMesh(Fixture f, boolean smooth) {
        Map<Integer, float[]> quads = new HashMap<>();
        for (int lx = 0; lx < 16; lx++) {
            for (int ly = 0; ly <= f.maxY(); ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    int id = f.block(lx, ly, lz);
                    int c = cls(id);
                    if ((c & CendaKernels.CLASS_CUBE) == 0) continue;
                    boolean transparent = (c & CendaKernels.CLASS_TRANSPARENT) != 0;
                    for (int face = 0; face < 6; face++) {
                        int adj = f.block(lx + FDX[face], ly + FDY[face], lz + FDZ[face]);
                        boolean render;
                        if (adj == AIR) render = true;
                        else if (transparent) render = adj != id;
                        else render = (cls(adj) & CendaKernels.CLASS_TRANSPARENT) != 0;
                        if (!render) continue;

                        float[] rec = new float[5];
                        rec[0] = id;
                        for (int corner = 0; corner < 4; corner++) {
                            int ivx = lx + FACE_OFFSETS[face][corner][0];
                            int ivy = ly + FACE_OFFSETS[face][corner][1];
                            int ivz = lz + FACE_OFFSETS[face][corner][2];
                            float light = sky(f, ivx, ivy, ivz, face, smooth);
                            if (smooth) light *= ao(f, ivx, ivy, ivz, face);
                            rec[1 + corner] = light;
                        }
                        quads.put(key(lx, ly, lz, face), rec);
                    }
                }
            }
        }
        return quads;
    }

    private static float sky(Fixture f, int ivx, int ivy, int ivz, int face, boolean smooth) {
        int lit = 0, sampled = 0;
        int lo = smooth ? -1 : 0;
        for (int a = lo; a <= 0; a++) {
            for (int b = lo; b <= 0; b++) {
                int cx, cy, czz;
                switch (face) {
                    case 0 -> { cx = ivx + a; cy = ivy;     czz = ivz + b; }
                    case 1 -> { cx = ivx + a; cy = ivy - 1; czz = ivz + b; }
                    case 2 -> { cx = ivx + a; cy = ivy + b; czz = ivz - 1; }
                    case 3 -> { cx = ivx + a; cy = ivy + b; czz = ivz;     }
                    case 4 -> { cx = ivx;     cy = ivy + a; czz = ivz + b; }
                    default -> { cx = ivx - 1; cy = ivy + a; czz = ivz + b; }
                }
                int h = f.height(cx, czz);
                if (h < 0) continue;
                sampled++;
                if (cy >= h) lit++;
            }
        }
        if (sampled == 0) return 1.0f;
        return (float) lit / (float) sampled;
    }

    private static final int[][] AO_N = {{0,0,0}, {0,-1,0}, {0,0,-1}, {0,0,0}, {0,0,0}, {-1,0,0}};
    private static final int[][] AO_T1 = {{-1,0,0}, {-1,0,0}, {-1,0,0}, {-1,0,0}, {0,-1,0}, {0,-1,0}};
    private static final int[][] AO_T2 = {{0,0,-1}, {0,0,-1}, {0,-1,0}, {0,-1,0}, {0,0,-1}, {0,0,-1}};

    private static float ao(Fixture f, int ivx, int ivy, int ivz, int face) {
        int[] n = AO_N[face], t1 = AO_T1[face], t2 = AO_T2[face];
        boolean side1 = f.solid(ivx + n[0] + t1[0], ivy + n[1] + t1[1], ivz + n[2] + t1[2]);
        boolean side2 = f.solid(ivx + n[0] + t2[0], ivy + n[1] + t2[1], ivz + n[2] + t2[2]);
        boolean corner = f.solid(ivx + n[0] + t1[0] + t2[0], ivy + n[1] + t1[1] + t2[1], ivz + n[2] + t1[2] + t2[2]);
        int count = (side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0);
        if (side1 && side2) count = 3;
        return 1.0f - 0.13f * count;
    }

    private static int key(int lx, int ly, int lz, int face) {
        return (((ly * 16 + lz) * 16 + lx) << 3) | face;
    }

    private static void assertKernelMatchesReference(Fixture f, boolean smooth) {
        Map<Integer, float[]> expected = referenceMesh(f, smooth);
        float[] out = new float[9 * 70000];
        int n = CendaKernels.meshChunk(f.blocks(), CLASS_TABLE, AIR,
            f.planeXn(), f.planeXp(), f.planeZn(), f.planeZp(),
            f.cornerNn(), f.cornerPn(), f.cornerNp(), f.cornerPp(),
            f.heights(), f.maxY(), smooth, out);
        assertTrue(n >= 0, "kernel must succeed");
        assertEquals(expected.size(), n, "quad count");
        for (int q = 0; q < n; q++) {
            int base = q * 9;
            int k = key((int) out[base], (int) out[base + 1], (int) out[base + 2], (int) out[base + 3]);
            float[] exp = expected.remove(k);
            assertTrue(exp != null, "unexpected kernel quad at packed key " + k);
            assertEquals(exp[0], out[base + 4], "block id");
            for (int corner = 0; corner < 4; corner++) {
                assertEquals(exp[1 + corner], out[base + 5 + corner],
                    "corner light " + corner + " @key " + k + " smooth=" + smooth);
            }
        }
        assertTrue(expected.isEmpty(), "kernel missed " + expected.size() + " quads");
    }

    @Test
    void randomizedChunksMatchJavaReferenceExactly() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda kernels not built");
        Random rng = new Random(42);
        for (int round = 0; round < 4; round++) {
            short[] blocks = new short[65536];
            int maxY = 48;
            for (int i = 0; i < 6000; i++) { // sparse random terrain incl. transparents
                int x = rng.nextInt(16), y = rng.nextInt(maxY), z = rng.nextInt(16);
                int id = switch (rng.nextInt(5)) {
                    case 0 -> LEAVES;
                    case 1 -> ICE_LIKE;
                    case 2 -> WATER;
                    default -> STONE;
                };
                blocks[(y * 16 + z) * 16 + x] = (short) id;
            }
            boolean withNeighbors = round % 2 == 0;
            short[] planeXn = withNeighbors ? randomPlane(rng, maxY) : null;
            short[] planeXp = withNeighbors ? randomPlane(rng, maxY) : null;
            short[] planeZn = withNeighbors ? randomPlane(rng, maxY) : null;
            short[] planeZp = round == 0 ? null : randomPlane(rng, maxY); // mixed presence
            short[] cornerNn = withNeighbors ? randomColumn(rng, maxY) : null;
            short[] cornerPn = withNeighbors ? randomColumn(rng, maxY) : null;
            short[] cornerNp = withNeighbors ? randomColumn(rng, maxY) : null;
            short[] cornerPp = round == 0 ? null : randomColumn(rng, maxY);
            short[] heights = new short[18 * 18];
            for (int i = 0; i < heights.length; i++) {
                heights[i] = (short) (rng.nextInt(10) == 0 ? -1 : rng.nextInt(60));
            }
            Fixture f = new Fixture(blocks, planeXn, planeXp, planeZn, planeZp,
                cornerNn, cornerPn, cornerNp, cornerPp, heights, maxY);
            assertKernelMatchesReference(f, true);
            assertKernelMatchesReference(f, false);
        }
    }

    private static short[] randomPlane(Random rng, int maxY) {
        short[] plane = new short[16 * 256];
        for (int y = 0; y < maxY; y++) {
            for (int i = 0; i < 16; i++) {
                if (rng.nextInt(3) == 0) {
                    plane[y * 16 + i] = (short) (rng.nextBoolean() ? STONE : LEAVES);
                }
            }
        }
        return plane;
    }

    private static short[] randomColumn(Random rng, int maxY) {
        short[] column = new short[256];
        for (int y = 0; y < maxY; y++) {
            if (rng.nextInt(3) == 0) {
                column[y] = STONE;
            }
        }
        return column;
    }
}
