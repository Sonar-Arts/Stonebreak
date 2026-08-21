package com.openmason.engine.voxel.mms.mmsCore;

import com.openmason.engine.voxel.mms.mmsGeometry.MmsCuboidGenerator;

import java.nio.ByteBuffer;

/**
 * The 16-byte per-quad record behind {@link MmsVertexFormat#LODQUAD16} — the
 * vertex-pulling format for FastLOD terrain. LOD geometry is axis-aligned
 * rectangles too, but unlike chunk quads it needs tall spans (foundations down
 * to y=0), half-block Y (tree canopies), unit-square UVs whatever the cell
 * size, and SMOOTH per-corner normals on the terrain tops — so it trades
 * per-corner light (LOD light is 0 or 1 per quad) for four octahedral normals.
 *
 * <pre>
 * word0: x:9 | z:9 | y:9 | face:3 | smooth:1 | light:1
 *        the rectangle's minimum corner ON ITS PLANE (unlike MmsQuadCodec, which
 *        stores the cell and lets the corner table add the face offset): x,z
 *        whole blocks relative to the LOD-region origin, biased by +8 (canopies
 *        overhang a cell by up to 2 blocks); y in HALF blocks (0..255.5)
 * word1: w:6 | h:10 | layer:15 | alpha:1
 *        w,h in half blocks along the face's u/v axes (w ≤ 32, h ≤ 1023)
 * word2: oct(n0) | oct(n1)      per-corner normals, 8+8 bits each, corner order =
 * word3: oct(n2) | oct(n3)      MmsCuboidGenerator.FACE_VERTEX_OFFSETS; used when smooth=1
 * </pre>
 *
 * Corners, winding and normals share {@link MmsCuboidGenerator}'s tables with
 * {@link MmsQuadCodec}; the GLSL mirror is {@code pullLodQuad} in
 * {@code world.vert} (selected by {@code aOrigin.w < -1.5}).
 */
public final class MmsLodQuadCodec {

    public static final int QUAD_BYTES = 16;
    /** Bias added to x/z so canopy overhang west/north of the region origin still encodes. */
    public static final int XZ_BIAS = 8;

    private MmsLodQuadCodec() {
    }

    // ─── Packing ───────────────────────────────────────────────────────────

    /**
     * @param x,z  whole blocks relative to the region origin (−8..503)
     * @param yHalf  base Y in half blocks (0..511)
     * @param wHalf  extent along the face's u axis in half blocks (1..63)
     * @param hHalf  extent along the face's v axis in half blocks (1..1023)
     */
    public static int word0(int x, int z, int yHalf, int face, boolean smooth, boolean lit) {
        int bx = x + XZ_BIAS;
        int bz = z + XZ_BIAS;
        check(bx, 0, 511, "x");
        check(bz, 0, 511, "z");
        check(yHalf, 0, 511, "y");
        check(face, 0, 5, "face");
        return bx | (bz << 9) | (yHalf << 18) | (face << 27) | ((smooth ? 1 : 0) << 30)
            | ((lit ? 1 : 0) << 31);
    }

    public static int word1(int wHalf, int hHalf, int layer, boolean alpha) {
        check(wHalf, 1, 63, "w");
        check(hHalf, 1, 1023, "h");
        check(layer, 0, 32767, "layer");
        return wHalf | (hHalf << 6) | (layer << 16) | ((alpha ? 1 : 0) << 31);
    }

    /** Two octahedral-encoded normals in one word (low half = first). */
    public static int normalPair(float ax, float ay, float az, float bx, float by, float bz) {
        return octEncode(ax, ay, az) | (octEncode(bx, by, bz) << 16);
    }

    private static void check(int v, int lo, int hi, String what) {
        if (v < lo || v > hi) {
            throw new IllegalArgumentException("LODQUAD16 " + what + " out of range: " + v
                + " (allowed " + lo + ".." + hi + ")");
        }
    }

    /** Octahedral unit-vector encoding to 2×u8 (low byte = u). */
    public static int octEncode(float x, float y, float z) {
        float l1 = Math.abs(x) + Math.abs(y) + Math.abs(z);
        if (l1 == 0f) {
            y = 1f;
            l1 = 1f;
        }
        float u = x / l1;
        float v = z / l1;
        if (y < 0f) {
            float ou = u;
            u = (1f - Math.abs(v)) * (ou >= 0f ? 1f : -1f);
            v = (1f - Math.abs(ou)) * (v >= 0f ? 1f : -1f);
        }
        // 254 steps so 0 maps to exactly 127 and axis vectors round-trip exactly.
        int iu = Math.round((u * 0.5f + 0.5f) * 254f);
        int iv = Math.round((v * 0.5f + 0.5f) * 254f);
        return (iu & 0xFF) | ((iv & 0xFF) << 8);
    }

    /** Decodes an octahedral-encoded normal; returns component {@code c} (0=x,1=y,2=z). */
    public static float octDecode(int packed, int c) {
        float u = ((packed & 0xFF) / 254f) * 2f - 1f;
        float v = (((packed >>> 8) & 0xFF) / 254f) * 2f - 1f;
        float y = 1f - Math.abs(u) - Math.abs(v);
        float x = u;
        float z = v;
        if (y < 0f) {
            float ox = x;
            x = (1f - Math.abs(z)) * (ox >= 0f ? 1f : -1f);
            z = (1f - Math.abs(ox)) * (z >= 0f ? 1f : -1f);
        }
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        return switch (c) {
            case 0 -> x / len;
            case 1 -> y / len;
            default -> z / len;
        };
    }

    // ─── Decoding ──────────────────────────────────────────────────────────

    public static int x(int w0) {
        return (w0 & 0x1FF) - XZ_BIAS;
    }

    public static int z(int w0) {
        return ((w0 >>> 9) & 0x1FF) - XZ_BIAS;
    }

    public static float y(int w0) {
        return ((w0 >>> 18) & 0x1FF) * 0.5f;
    }

    public static int face(int w0) {
        return (w0 >>> 27) & 7;
    }

    public static boolean smooth(int w0) {
        return ((w0 >>> 30) & 1) != 0;
    }

    public static boolean lit(int w0) {
        return (w0 >>> 31) != 0;
    }

    public static float width(int w1) {
        return (w1 & 0x3F) * 0.5f;
    }

    public static float height(int w1) {
        return ((w1 >>> 6) & 0x3FF) * 0.5f;
    }

    public static int layer(int w1) {
        return (w1 >>> 16) & 0x7FFF;
    }

    public static boolean alpha(int w1) {
        return (w1 >>> 31) != 0;
    }

    public static float position(ByteBuffer quads, int q, int corner, int axis,
                                 float originX, float originY, float originZ) {
        int w0 = quads.getInt(q * QUAD_BYTES);
        int w1 = quads.getInt(q * QUAD_BYTES + 4);
        int face = face(w0);
        // x,y,z are the rectangle's min corner ON ITS PLANE: only the in-plane
        // axes take the corner offsets (scaled), the normal axis stays put.
        float off = 0f;
        if (axis == MmsCuboidGenerator.uAxis(face)) {
            off = MmsCuboidGenerator.cornerOffset(face, corner, axis) * width(w1);
        } else if (axis == MmsCuboidGenerator.vAxis(face)) {
            off = MmsCuboidGenerator.cornerOffset(face, corner, axis) * height(w1);
        }
        return switch (axis) {
            case 0 -> originX + x(w0) + off;
            case 1 -> originY + y(w0) + off;
            default -> originZ + z(w0) + off;
        };
    }

    /** Unit-square UV of a corner (FastLOD stretches one tile over the whole cell). */
    public static float texCoord(ByteBuffer quads, int q, int corner, int c) {
        int face = face(quads.getInt(q * QUAD_BYTES));
        int axis = c == 0 ? MmsCuboidGenerator.uAxis(face) : MmsCuboidGenerator.vAxis(face);
        return MmsCuboidGenerator.cornerOffset(face, corner, axis);
    }

    public static float normal(ByteBuffer quads, int q, int corner, int c) {
        int w0 = quads.getInt(q * QUAD_BYTES);
        if (smooth(w0)) {
            int word = quads.getInt(q * QUAD_BYTES + 8 + (corner >> 1) * 4);
            int packed = (corner & 1) == 0 ? (word & 0xFFFF) : ((word >>> 16) & 0xFFFF);
            return octDecode(packed, c);
        }
        return faceNormal(face(w0), c);
    }

    /** Component {@code c} of the outward normal of a cube face. */
    public static float faceNormal(int face, int c) {
        return switch (face) {
            case 0 -> c == 1 ? 1f : 0f;
            case 1 -> c == 1 ? -1f : 0f;
            case 2 -> c == 2 ? -1f : 0f;
            case 3 -> c == 2 ? 1f : 0f;
            case 4 -> c == 0 ? 1f : 0f;
            default -> c == 0 ? -1f : 0f;
        };
    }

    public static int flags(ByteBuffer quads, int q) {
        int w0 = quads.getInt(q * QUAD_BYTES);
        int w1 = quads.getInt(q * QUAD_BYTES + 4);
        return MmsBufferLayout.packFlags(0f, alpha(w1) ? 1f : 0f, 0f, lit(w0) ? 1f : 0f);
    }

    public static float layer(ByteBuffer quads, int q) {
        return layer(quads.getInt(q * QUAD_BYTES + 4));
    }
}
