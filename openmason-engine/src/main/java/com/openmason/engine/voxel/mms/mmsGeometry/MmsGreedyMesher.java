package com.openmason.engine.voxel.mms.mmsGeometry;

import java.util.concurrent.atomic.LongAdder;

/**
 * Mighty Mesh System - greedy merging over cube-face quad streams.
 *
 * <p>Operates on the flat quad records the cube meshing paths produce (the
 * native {@code ck_mesh_chunk} kernel and the Java fallback emit the same
 * format): {@code [x, y, z, face, blockId, l0, l1, l2, l3]} per quad, where
 * {@code l0..l3} are the per-corner light values in face-corner order. Axis
 * ranges are chunk-local: x/z in [0,16), y in [0,256).
 *
 * <p>Adjacent coplanar quads merge into one rectangle when they have the same
 * block id and ALL EIGHT corner lights are one identical value. That is the
 * exact condition under which the merged rectangle rasterizes pixel-identically
 * to its constituents (per-vertex light interpolates, so anything short of a
 * constant field would shift gradients). Quads with non-uniform corner light
 * pass through verbatim — near walls and on sky-lit cliff sides merging simply
 * doesn't happen, while flat terrain tops and everything with sky factor 0
 * (cave interiors, ocean floors) collapse dramatically.
 *
 * <p>Merged rectangles carry unit-per-block texture extents (UV 0..w / 0..h),
 * which requires the block texture array to sample with {@code GL_REPEAT} —
 * each block face owns a full array layer, so tiling cannot bleed into other
 * textures.
 *
 * <p>Deterministic: quads are processed per face direction in stream order and
 * extended width-first then height-first, so identical input always produces
 * identical output — host and client meshes stay in agreement.
 *
 * <p>Thread-safe: all working state is per-thread scratch, reused across
 * calls. Consume the returned array before the next merge on the same thread.
 *
 * @since MMS 2.1
 */
public final class MmsGreedyMesher {

    /** Floats per input quad record: x, y, z, face, id, l0..l3. */
    public static final int IN_STRIDE = 9;

    /** Floats per output record: x, y, z, face, id, w, h, l0..l3. */
    public static final int OUT_STRIDE = 11;

    private static final int CS = 16;   // chunk size (matches ck_mesh_chunk)
    private static final int WH = 256;  // world height
    private static final int CELLS = CS * CS * WH;

    // Cumulative effectiveness counters (read by debug overlays).
    private static final LongAdder QUADS_IN = new LongAdder();
    private static final LongAdder QUADS_OUT = new LongAdder();

    /**
     * Per-thread working state. The grid stores quadIndex+1 per occupied cell
     * (0 = empty); only cells actually touched by the current call are written
     * and re-cleared, so the 256 KB array is never bulk-reset.
     */
    private static final class Scratch {
        final int[] grid = new int[CELLS];
        final int[][] dirQuads = new int[6][];
        final int[] dirCounts = new int[6];
        float[] out = new float[OUT_STRIDE * 4096];

        Scratch() {
            for (int d = 0; d < 6; d++) {
                dirQuads[d] = new int[1024];
            }
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private MmsGreedyMesher() {
    }

    /** Total cube quads fed into the merger since startup. */
    public static long quadsIn() {
        return QUADS_IN.sum();
    }

    /** Total quads emitted after merging since startup. */
    public static long quadsOut() {
        return QUADS_OUT.sum();
    }

    /**
     * Grid cell for a quad of the given face direction. The in-plane "width"
     * axis (x, or z for the ±X faces) is the innermost index (+1 per step) and
     * the "height" axis strides by {@link #CS}, so run extension is pointer
     * arithmetic. All three layouts address the same 65536-cell space.
     */
    private static int cellIndex(int face, int x, int y, int z) {
        return switch (face) {
            case 0, 1 -> (y * CS + z) * CS + x;  // width x, height z, plane y
            case 2, 3 -> (z * WH + y) * CS + x;  // width x, height y, plane z
            default -> (x * WH + y) * CS + z;    // width z, height y, plane x
        };
    }

    /** Width-axis coordinate of a quad (x, or z for ±X faces). */
    private static int uCoord(int face, int x, int z) {
        return face >= 4 ? z : x;
    }

    /** Height-axis coordinate of a quad (z for ±Y faces, else y). */
    private static int vCoord(int face, int y, int z) {
        return face <= 1 ? z : y;
    }

    private static int vLimit(int face) {
        return face <= 1 ? CS : WH;
    }

    /**
     * Merges a cube-face quad stream. Returns the merged record count and the
     * per-thread output array via {@code outHolder[0]} (11 floats per record,
     * see {@link #OUT_STRIDE}). Unmergeable quads are passed through with
     * {@code w = h = 1} and their original corner lights.
     *
     * @param quads     input records, {@link #IN_STRIDE} floats each
     * @param quadCount number of input records
     * @param outHolder length-1 array receiving the output buffer
     * @return number of output records
     */
    public static int merge(float[] quads, int quadCount, float[][] outHolder) {
        Scratch s = SCRATCH.get();
        int required = quadCount * OUT_STRIDE;
        if (s.out.length < required) {
            s.out = new float[Math.max(required, s.out.length + (s.out.length >> 1))];
        }
        float[] out = s.out;
        int outCount = 0;

        // Bucket quads by face direction, preserving stream order; anything
        // outside the addressable volume passes straight through (defensive —
        // the meshers never emit such coords).
        int[] counts = s.dirCounts;
        java.util.Arrays.fill(counts, 0);
        for (int qi = 0; qi < quadCount; qi++) {
            int base = qi * IN_STRIDE;
            int x = (int) quads[base];
            int y = (int) quads[base + 1];
            int z = (int) quads[base + 2];
            int face = (int) quads[base + 3];
            if (face < 0 || face >= 6
                || x < 0 || x >= CS || z < 0 || z >= CS || y < 0 || y >= WH) {
                outCount = emit(out, outCount, quads, base, 1, 1);
                continue;
            }
            int[] list = s.dirQuads[face];
            if (counts[face] == list.length) {
                list = java.util.Arrays.copyOf(list, list.length * 2);
                s.dirQuads[face] = list;
            }
            list[counts[face]++] = qi;
        }

        int[] grid = s.grid;
        for (int face = 0; face < 6; face++) {
            int[] list = s.dirQuads[face];
            int n = counts[face];
            if (n == 0) {
                continue;
            }

            // Index this direction's quads into the plane grid.
            for (int i = 0; i < n; i++) {
                int base = list[i] * IN_STRIDE;
                grid[cellIndex(face, (int) quads[base], (int) quads[base + 1],
                    (int) quads[base + 2])] = list[i] + 1;
            }

            int vMax = vLimit(face);
            for (int i = 0; i < n; i++) {
                int qi = list[i];
                int base = qi * IN_STRIDE;
                int x = (int) quads[base];
                int y = (int) quads[base + 1];
                int z = (int) quads[base + 2];
                int cell = cellIndex(face, x, y, z);
                if (grid[cell] != qi + 1) {
                    continue; // consumed by an earlier rectangle
                }

                float id = quads[base + 4];
                float l0 = quads[base + 5];
                boolean uniform = l0 == quads[base + 6]
                    && l0 == quads[base + 7]
                    && l0 == quads[base + 8];
                if (!uniform) {
                    grid[cell] = 0;
                    outCount = emit(out, outCount, quads, base, 1, 1);
                    continue;
                }

                int u = uCoord(face, x, z);
                int v = vCoord(face, y, z);

                // Extend along the width axis.
                int w = 1;
                while (u + w < CS && matches(quads, grid[cell + w], id, l0)) {
                    w++;
                }

                // Extend along the height axis: the whole width row must match.
                int h = 1;
                height:
                while (v + h < vMax) {
                    int rowBase = cell + h * CS;
                    for (int k = 0; k < w; k++) {
                        if (!matches(quads, grid[rowBase + k], id, l0)) {
                            break height;
                        }
                    }
                    h++;
                }

                // Consume the rectangle.
                for (int r = 0; r < h; r++) {
                    int rowBase = cell + r * CS;
                    for (int k = 0; k < w; k++) {
                        grid[rowBase + k] = 0;
                    }
                }
                outCount = emit(out, outCount, quads, base, w, h);
            }

            // Re-clear any cells left occupied (idempotent for consumed ones).
            for (int i = 0; i < n; i++) {
                int base = list[i] * IN_STRIDE;
                grid[cellIndex(face, (int) quads[base], (int) quads[base + 1],
                    (int) quads[base + 2])] = 0;
            }
        }

        QUADS_IN.add(quadCount);
        QUADS_OUT.add(outCount);
        outHolder[0] = out;
        return outCount;
    }

    /** Candidate-cell predicate for run extension. */
    private static boolean matches(float[] quads, int gridValue, float id, float light) {
        if (gridValue == 0) {
            return false;
        }
        int base = (gridValue - 1) * IN_STRIDE;
        return quads[base + 4] == id
            && quads[base + 5] == light
            && quads[base + 6] == light
            && quads[base + 7] == light
            && quads[base + 8] == light;
    }

    private static int emit(float[] out, int outCount, float[] quads, int inBase, int w, int h) {
        int o = outCount * OUT_STRIDE;
        out[o] = quads[inBase];
        out[o + 1] = quads[inBase + 1];
        out[o + 2] = quads[inBase + 2];
        out[o + 3] = quads[inBase + 3];
        out[o + 4] = quads[inBase + 4];
        out[o + 5] = w;
        out[o + 6] = h;
        out[o + 7] = quads[inBase + 5];
        out[o + 8] = quads[inBase + 6];
        out[o + 9] = quads[inBase + 7];
        out[o + 10] = quads[inBase + 8];
        return outCount + 1;
    }
}
