package com.stonebreak.world;

import com.openmason.engine.cenda.CendaKernels;

import java.util.ArrayList;

/**
 * Bench-only Java implementation of the same z-packed row-mask culling the
 * native kernel's bitmask path uses ({@code int} rows,
 * {@code Integer.numberOfTrailingZeros}) — the honest Tier-1 bake-off arm for
 * {@link CendaChunkBenchmarkTest}. Chunk-interior only (out-of-chunk = air),
 * matching the {@code javaReferenceMesh} flat arm so the comparison is
 * apples-to-apples. NOT a production mesher path.
 *
 * <p>(The Tier-1 notes name {@code Long.compress} — that's a greedy-MERGE
 * tool; cull-only masking needs only tzcnt/clear-lowest, benched here.)
 */
final class JavaBitmaskMesher {

    private static final int CS = 16;
    private static final int WH = 256;

    private static final int[][][] OFF = {
        {{0,1,1},{1,1,1},{1,1,0},{0,1,0}}, {{0,0,0},{1,0,0},{1,0,1},{0,0,1}},
        {{1,0,0},{0,0,0},{0,1,0},{1,1,0}}, {{0,0,1},{1,0,1},{1,1,1},{0,1,1}},
        {{1,0,1},{1,0,0},{1,1,0},{1,1,1}}, {{0,0,0},{0,0,1},{0,1,1},{0,1,0}}};
    private static final int[] FDX = {0,0,0,0,1,-1};
    private static final int[] FDY = {1,-1,0,0,0,0};
    private static final int[] FDZ = {0,0,-1,1,0,0};
    private static final int[][] AON = {{0,0,0},{0,-1,0},{0,0,-1},{0,0,0},{0,0,0},{-1,0,0}};
    private static final int[][] AT1 = {{-1,0,0},{-1,0,0},{-1,0,0},{-1,0,0},{0,-1,0},{0,-1,0}};
    private static final int[][] AT2 = {{0,0,-1},{0,0,-1},{0,-1,0},{0,-1,0},{0,0,-1},{0,0,-1}};

    private JavaBitmaskMesher() {
    }

    static ArrayList<float[]> mesh(short[] blocks, byte[] ct, short[] heights, int maxY) {
        int[] opq = new int[CS * WH];
        int[] tcube = new int[CS * WH];
        int[] n = new int[CS * WH];
        int maxYb = Math.min(maxY + 1, WH - 1);
        for (int y = 0; y <= maxYb; y++) {
            for (int z = 0; z < CS; z++) {
                int rowBase = (y * CS + z) * CS;
                int zbit = 1 << z;
                for (int x = 0; x < CS; x++) {
                    int id = blocks[rowBase + x];
                    if (id == 0) {
                        n[x * WH + y] |= zbit;
                        continue;
                    }
                    int c = id >= 0 && id < ct.length ? ct[id] : 0;
                    if ((c & CendaKernels.CLASS_TRANSPARENT) != 0) {
                        n[x * WH + y] |= zbit;
                        if ((c & CendaKernels.CLASS_CUBE) != 0) {
                            tcube[x * WH + y] |= zbit;
                        }
                    } else if ((c & CendaKernels.CLASS_CUBE) != 0) {
                        opq[x * WH + y] |= zbit;
                    }
                }
            }
        }

        ArrayList<float[]> quads = new ArrayList<>();
        int[] faceBits = new int[6];
        for (int lx = 0; lx < CS; lx++) {
            for (int ly = 0; ly <= maxY; ly++) {
                int opqRow = opq[lx * WH + ly];
                int tc = tcube[lx * WH + ly];
                if ((opqRow | tc) == 0) {
                    continue;
                }
                int selfN = n[lx * WH + ly];
                // Out-of-chunk neighbors are air (all-renderable) on every border.
                faceBits[0] = opqRow & (ly < WH - 1 ? n[lx * WH + ly + 1] : 0xFFFF);
                faceBits[1] = opqRow & (ly > 0 ? n[lx * WH + ly - 1] : 0xFFFF);
                faceBits[2] = opqRow & (((selfN << 1) | 1) & 0xFFFF);
                faceBits[3] = opqRow & ((selfN >>> 1) | 0x8000);
                faceBits[4] = opqRow & (lx < CS - 1 ? n[(lx + 1) * WH + ly] : 0xFFFF);
                faceBits[5] = opqRow & (lx > 0 ? n[(lx - 1) * WH + ly] : 0xFFFF);
                int any = (faceBits[0] | faceBits[1] | faceBits[2] | faceBits[3]
                    | faceBits[4] | faceBits[5] | tc) & 0xFFFF;
                while (any != 0) {
                    int lz = Integer.numberOfTrailingZeros(any);
                    any &= any - 1;
                    int id = blocks[(ly * CS + lz) * CS + lx];
                    if (((tc >>> lz) & 1) != 0) {
                        for (int face = 0; face < 6; face++) {
                            int ax = lx + FDX[face], ay = ly + FDY[face], az = lz + FDZ[face];
                            int adj = (ay < 0 || ay >= WH || ax < 0 || ax >= CS || az < 0 || az >= CS)
                                ? 0 : blocks[(ay * CS + az) * CS + ax];
                            if (adj == 0 || adj != id) {
                                quads.add(lightRec(blocks, ct, heights, lx, ly, lz, face));
                            }
                        }
                    } else {
                        for (int face = 0; face < 6; face++) {
                            if (((faceBits[face] >>> lz) & 1) != 0) {
                                quads.add(lightRec(blocks, ct, heights, lx, ly, lz, face));
                            }
                        }
                    }
                }
            }
        }
        return quads;
    }

    private static float[] lightRec(short[] blocks, byte[] ct, short[] heights,
                                    int lx, int ly, int lz, int face) {
        float[] rec = new float[4];
        for (int corner = 0; corner < 4; corner++) {
            rec[corner] = light(blocks, ct, heights,
                lx + OFF[face][corner][0], ly + OFF[face][corner][1], lz + OFF[face][corner][2],
                face);
        }
        return rec;
    }

    private static float light(short[] blocks, byte[] ct, short[] heights,
                               int ivx, int ivy, int ivz, int face) {
        int lit = 0, sampled = 0;
        for (int a = -1; a <= 0; a++) {
            for (int b = -1; b <= 0; b++) {
                int cx, cy, cz;
                switch (face) {
                    case 0 -> { cx = ivx + a; cy = ivy;     cz = ivz + b; }
                    case 1 -> { cx = ivx + a; cy = ivy - 1; cz = ivz + b; }
                    case 2 -> { cx = ivx + a; cy = ivy + b; cz = ivz - 1; }
                    case 3 -> { cx = ivx + a; cy = ivy + b; cz = ivz;     }
                    case 4 -> { cx = ivx;     cy = ivy + a; cz = ivz + b; }
                    default -> { cx = ivx - 1; cy = ivy + a; cz = ivz + b; }
                }
                int h = heights[(cz + 1) * 18 + (cx + 1)];
                if (h < 0) continue;
                sampled++;
                if (cy >= h) lit++;
            }
        }
        float sky = sampled == 0 ? 1.0f : (float) lit / sampled;
        int[] nrm = AON[face], t1 = AT1[face], t2 = AT2[face];
        boolean s1 = solid(blocks, ct, ivx + nrm[0] + t1[0], ivy + nrm[1] + t1[1], ivz + nrm[2] + t1[2]);
        boolean s2 = solid(blocks, ct, ivx + nrm[0] + t2[0], ivy + nrm[1] + t2[1], ivz + nrm[2] + t2[2]);
        boolean co = solid(blocks, ct, ivx + nrm[0] + t1[0] + t2[0], ivy + nrm[1] + t1[1] + t2[1],
            ivz + nrm[2] + t1[2] + t2[2]);
        int count = (s1 ? 1 : 0) + (s2 ? 1 : 0) + (co ? 1 : 0);
        if (s1 && s2) count = 3;
        return sky * (1.0f - 0.13f * count);
    }

    private static boolean solid(short[] blocks, byte[] ct, int x, int y, int z) {
        if (y < 0 || y >= WH || x < 0 || x >= CS || z < 0 || z >= CS) return false;
        int id = blocks[(y * CS + z) * CS + x];
        return ((id < ct.length ? ct[id] : 0) & CendaKernels.CLASS_OPAQUE_LIGHT) != 0;
    }
}
