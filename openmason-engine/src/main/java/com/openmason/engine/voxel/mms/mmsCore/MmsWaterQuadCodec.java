package com.openmason.engine.voxel.mms.mmsCore;

import com.openmason.engine.voxel.mms.mmsGeometry.MmsCuboidGenerator;

import java.nio.ByteBuffer;

/**
 * The 16-byte per-quad record behind {@link MmsVertexFormat#WATERQUAD16} — the
 * vertex-pulling format for water: near-water cell faces (corner-sewn surface
 * heights, side bottoms sealed up to one block below the cell) and FastLOD sea
 * sheets (flat, up to 16×16 blocks).
 *
 * <pre>
 * word0: x:8 | y:9 | z:8 | face:3 | falling:1 | source:1 | sheet:1 | spare:1
 *        sheet = FastLOD sea sheet: no wave displacement in the shader
 *        cell relative to the region origin (y = the water cell's block Y)
 * word1: 4 × u8 vertex Y offsets from (y − 1) in 1/128 block, corner order =
 *        MmsCuboidGenerator.FACE_VERTEX_OFFSETS (the water generator's order)
 * word2: 4 × u8 per-corner surface-height flags (aFlags.x, 0..1 → 0..255)
 * word3: (w−1):4 | (h−1):4 | spare   in-plane extent (1 for near water, the
 *        cell size for LOD sheets)
 * </pre>
 *
 * The shader (`water.vert`, {@code aOrigin.w < -2.5}) rebuilds the corner
 * position from the table, the vertex Y from word1, and the flags vector
 * {@code (surface, falling, source, light=1)} the fragment stage expects.
 */
public final class MmsWaterQuadCodec {

    public static final int QUAD_BYTES = 16;
    /** Vertex Y offsets are stored from one block BELOW the cell base. */
    public static final float Y_BASE_OFFSET = -1f;
    public static final float Y_STEP = 1f / 128f;

    private MmsWaterQuadCodec() {
    }

    public static int word0(int x, int y, int z, int face, boolean falling, boolean source) {
        return word0(x, y, z, face, falling, source, false);
    }

    /**
     * @param sheet true for FastLOD sea sheets: the shader skips the per-vertex
     *              wave displacement. Merged rectangles of different sizes would
     *              otherwise interpolate the wave differently along a shared
     *              edge and open visible seams; distant water needs no waves.
     */
    public static int word0(int x, int y, int z, int face, boolean falling, boolean source, boolean sheet) {
        check(x, 0, 255, "x");
        check(y, 0, 511, "y");
        check(z, 0, 255, "z");
        check(face, 0, 5, "face");
        return x | (y << 8) | (z << 17) | (face << 25) | ((falling ? 1 : 0) << 28) | ((source ? 1 : 0) << 29)
            | ((sheet ? 1 : 0) << 30);
    }

    public static boolean sheet(int w0) {
        return ((w0 >>> 30) & 1) != 0;
    }

    /** Packs four vertex Y values (world units, {@code cellY - 1 ≤ vy < cellY + 1}) into 1/128 steps. */
    public static int word1(int cellY, float vy0, float vy1, float vy2, float vy3) {
        return yByte(cellY, vy0) | (yByte(cellY, vy1) << 8) | (yByte(cellY, vy2) << 16) | (yByte(cellY, vy3) << 24);
    }

    private static int yByte(int cellY, float vy) {
        float off = (vy - (cellY + Y_BASE_OFFSET)) / Y_STEP;
        return Math.clamp(Math.round(off), 0, 255);
    }

    /** Packs four per-corner surface flags (0..1) as the u8 {@link MmsBufferLayout#packFlags} would. */
    public static int word2(float f0, float f1, float f2, float f3) {
        return MmsBufferLayout.toUnsignedByte(f0) | (MmsBufferLayout.toUnsignedByte(f1) << 8)
            | (MmsBufferLayout.toUnsignedByte(f2) << 16) | (MmsBufferLayout.toUnsignedByte(f3) << 24);
    }

    public static int word3(int w, int h) {
        check(w, 1, 16, "w");
        check(h, 1, 16, "h");
        return (w - 1) | ((h - 1) << 4);
    }

    private static void check(int v, int lo, int hi, String what) {
        if (v < lo || v > hi) {
            throw new IllegalArgumentException("WATERQUAD16 " + what + " out of range: " + v
                + " (allowed " + lo + ".." + hi + ")");
        }
    }

    // ─── Decoding ──────────────────────────────────────────────────────────

    public static int x(int w0) {
        return w0 & 0xFF;
    }

    public static int y(int w0) {
        return (w0 >>> 8) & 0x1FF;
    }

    public static int z(int w0) {
        return (w0 >>> 17) & 0xFF;
    }

    public static int face(int w0) {
        return (w0 >>> 25) & 7;
    }

    public static boolean falling(int w0) {
        return ((w0 >>> 28) & 1) != 0;
    }

    public static boolean source(int w0) {
        return ((w0 >>> 29) & 1) != 0;
    }

    public static float vertexY(int cellY, int w1, int corner) {
        return cellY + Y_BASE_OFFSET + ((w1 >>> (corner * 8)) & 0xFF) * Y_STEP;
    }

    public static float surface(int w2, int corner) {
        return ((w2 >>> (corner * 8)) & 0xFF) / 255f;
    }

    public static int width(int w3) {
        return (w3 & 0xF) + 1;
    }

    public static int height(int w3) {
        return ((w3 >>> 4) & 0xF) + 1;
    }

    public static float position(ByteBuffer quads, int q, int corner, int axis,
                                 float originX, float originY, float originZ) {
        int w0 = quads.getInt(q * QUAD_BYTES);
        int w1 = quads.getInt(q * QUAD_BYTES + 4);
        int w3 = quads.getInt(q * QUAD_BYTES + 12);
        int face = face(w0);
        if (axis == 1) {
            return originY + vertexY(y(w0), w1, corner);
        }
        float off = MmsCuboidGenerator.cornerOffset(face, corner, axis);
        if (axis == MmsCuboidGenerator.uAxis(face)) {
            off *= width(w3);
        } else if (axis == MmsCuboidGenerator.vAxis(face)) {
            off *= height(w3);
        }
        return axis == 0 ? originX + x(w0) + off : originZ + z(w0) + off;
    }

    /** Face-local UV: u across the face's u axis, v DOWN the v axis (water.vert convention). */
    public static float texCoord(ByteBuffer quads, int q, int corner, int c) {
        int face = face(quads.getInt(q * QUAD_BYTES));
        int axis = c == 0 ? MmsCuboidGenerator.uAxis(face) : MmsCuboidGenerator.vAxis(face);
        float a = MmsCuboidGenerator.cornerOffset(face, corner, axis);
        return c == 1 && face >= 2 ? 1f - a : a;
    }

    public static float normal(ByteBuffer quads, int q, int c) {
        return MmsLodQuadCodec.faceNormal(face(quads.getInt(q * QUAD_BYTES)), c);
    }

    /** The {@link MmsBufferLayout#packFlags} word a vertex of this corner carries: (surface, falling, source, 1). */
    public static int flags(ByteBuffer quads, int q, int corner) {
        int w0 = quads.getInt(q * QUAD_BYTES);
        int w2 = quads.getInt(q * QUAD_BYTES + 8);
        return MmsBufferLayout.packFlags(surface(w2, corner), falling(w0) ? 1f : 0f, source(w0) ? 1f : 0f, 1f);
    }
}
