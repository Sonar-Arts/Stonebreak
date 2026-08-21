package com.openmason.engine.voxel.mms.mmsCore;

import com.openmason.engine.voxel.mms.mmsGeometry.MmsCuboidGenerator;

import java.nio.ByteBuffer;

/**
 * The 16-byte per-quad record behind {@link MmsVertexFormat#QUAD16} — the
 * vertex-pulling format. One axis-aligned, greedy-merged cube face is four
 * 32-bit words; the vertex shader reconstructs each of its four corners from
 * {@code gl_VertexID} (quad = id >> 2, corner = id & 3), so a face costs 16
 * bytes instead of 4 × 20 + 12.
 *
 * <pre>
 * word0: x:8 | y:9 | z:8 | face:3 | (w-1):4        positions relative to the mesh origin, whole blocks
 * word1: (h-1):4 | orient:3 | alpha:1 | translucent:1 | layer:16 | spare:7
 * word2: light0:8 | light1:8 | light2:8 | light3:8 per-corner light (0..1 → 0..255)
 * word3: spare (reserved: per-quad tint / water)
 * </pre>
 *
 * Corner geometry, winding, in-plane axes and normals come from
 * {@link MmsCuboidGenerator}'s tables — the same tables the classic path
 * uses — so {@code (face, corner)} means exactly the same thing here, in the
 * builder, and in the GLSL mirror ({@code QUAD_CORNER} / {@code QUAD_NORMAL}
 * in {@code world.vert}). Texture orientation is the D4 symmetry of the
 * mapper's unit-square UV frame ({@link #orientation}): 3 bits reproduce any
 * authored rotation/flip of the per-face texture.
 */
public final class MmsQuadCodec {

    /** Bytes per quad record. */
    public static final int QUAD_BYTES = 16;
    /** Texture unit the region/handle binds its quad buffer texture to. */
    public static final int QUAD_TEXTURE_UNIT = 7;
    /** Most quads one shared-EBO draw can address with u16 indices. */
    public static final int MAX_QUADS_PER_DRAW = 65536 / 4;

    private MmsQuadCodec() {
    }

    // ─── Packing ───────────────────────────────────────────────────────────

    public static int word0(int x, int y, int z, int face, int w) {
        check(x, 0, 255, "x");
        check(y, 0, 511, "y");
        check(z, 0, 255, "z");
        check(face, 0, 5, "face");
        check(w, 1, 16, "w");
        return x | (y << 8) | (z << 17) | (face << 25) | ((w - 1) << 28);
    }

    public static int word1(int h, int orientation, boolean alpha, boolean translucent, int layer) {
        check(h, 1, 16, "h");
        check(orientation, 0, 7, "orientation");
        check(layer, 0, 65535, "layer");
        return (h - 1) | (orientation << 4) | ((alpha ? 1 : 0) << 7) | ((translucent ? 1 : 0) << 8)
            | (layer << 9);
    }

    public static int word2(float l0, float l1, float l2, float l3) {
        return MmsBufferLayout.toUnsignedByte(l0) | (MmsBufferLayout.toUnsignedByte(l1) << 8)
            | (MmsBufferLayout.toUnsignedByte(l2) << 16) | (MmsBufferLayout.toUnsignedByte(l3) << 24);
    }

    private static void check(int v, int lo, int hi, String what) {
        if (v < lo || v > hi) {
            throw new IllegalArgumentException("QUAD16 " + what + " out of range: " + v
                + " (allowed " + lo + ".." + hi + ")");
        }
    }

    /**
     * Classifies a per-face UV frame (the mapper's corner at in-plane (0,0),
     * and the deltas to the (1,0) and (0,1) corners) as one of the eight
     * unit-square symmetries, or -1 when it is not one (then the quad cannot
     * be pulled and must go to the stamp mesh). Bits: 0 = u flipped
     * (u00 == 1), 1 = v flipped (v00 == 1), 2 = axes swapped.
     */
    public static int orientation(float u00, float v00, float duU, float duV, float dvU, float dvV) {
        if (u00 == 0f && v00 == 0f && duU == 0f && duV == 0f && dvU == 0f && dvV == 0f) {
            return 0; // degenerate all-zero frame (texture unused / stub mappers): identity
        }
        int uf = unit(u00);
        int vf = unit(v00);
        if (uf < 0 || vf < 0) {
            return -1;
        }
        boolean swap;
        if (duV == 0f && dvU == 0f && duU != 0f && dvV != 0f) {
            swap = false;
        } else if (duU == 0f && dvV == 0f && duV != 0f && dvU != 0f) {
            swap = true;
        } else {
            return -1;
        }
        int orient = uf | (vf << 1) | ((swap ? 1 : 0) << 2);
        // Verify the frame is exactly the one the shader will rebuild.
        if (frameDuU(orient) != duU || frameDuV(orient) != duV
                || frameDvU(orient) != dvU || frameDvV(orient) != dvV) {
            return -1;
        }
        return orient;
    }

    private static int unit(float v) {
        if (v == 0f) {
            return 0;
        }
        if (v == 1f) {
            return 1;
        }
        return -1;
    }

    static float frameU0(int orient) {
        return (orient & 1);
    }

    static float frameV0(int orient) {
        return (orient >> 1) & 1;
    }

    static float frameDuU(int orient) {
        return (orient & 4) == 0 ? 1f - 2f * frameU0(orient) : 0f;
    }

    static float frameDuV(int orient) {
        return (orient & 4) == 0 ? 0f : 1f - 2f * frameV0(orient);
    }

    static float frameDvU(int orient) {
        return (orient & 4) == 0 ? 0f : 1f - 2f * frameU0(orient);
    }

    static float frameDvV(int orient) {
        return (orient & 4) == 0 ? 1f - 2f * frameV0(orient) : 0f;
    }

    // ─── Decoding (CPU readback mirrors the shader) ────────────────────────

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

    public static int width(int w0) {
        return ((w0 >>> 28) & 0xF) + 1;
    }

    public static int height(int w1) {
        return (w1 & 0xF) + 1;
    }

    public static int orientation(int w1) {
        return (w1 >>> 4) & 7;
    }

    public static boolean alpha(int w1) {
        return ((w1 >>> 7) & 1) != 0;
    }

    public static boolean translucent(int w1) {
        return ((w1 >>> 8) & 1) != 0;
    }

    public static int layer(int w1) {
        return (w1 >>> 9) & 0xFFFF;
    }

    public static float light(int w2, int corner) {
        return ((w2 >>> (corner * 8)) & 0xFF) / 255f;
    }

    /** World-space position component {@code axis} of corner {@code corner} of the quad at {@code q}. */
    public static float position(ByteBuffer quads, int q, int corner, int axis,
                                 float originX, float originY, float originZ) {
        int w0 = quads.getInt(q * QUAD_BYTES);
        int w1 = quads.getInt(q * QUAD_BYTES + 4);
        int face = face(w0);
        float off = MmsCuboidGenerator.cornerOffset(face, corner, axis);
        if (axis == MmsCuboidGenerator.uAxis(face)) {
            off *= width(w0);
        } else if (axis == MmsCuboidGenerator.vAxis(face)) {
            off *= height(w1);
        }
        return switch (axis) {
            case 0 -> originX + x(w0) + off;
            case 1 -> originY + y(w0) + off;
            default -> originZ + z(w0) + off;
        };
    }

    /** Texture coordinate component (0 = u, 1 = v) of a corner: 0..w / 0..h in the authored frame. */
    public static float texCoord(ByteBuffer quads, int q, int corner, int c) {
        int w0 = quads.getInt(q * QUAD_BYTES);
        int w1 = quads.getInt(q * QUAD_BYTES + 4);
        int face = face(w0);
        float a = MmsCuboidGenerator.cornerOffset(face, corner, MmsCuboidGenerator.uAxis(face)) * width(w0);
        float b = MmsCuboidGenerator.cornerOffset(face, corner, MmsCuboidGenerator.vAxis(face)) * height(w1);
        int o = orientation(w1);
        return c == 0
            ? frameU0(o) + a * frameDuU(o) + b * frameDvU(o)
            : frameV0(o) + a * frameDuV(o) + b * frameDvV(o);
    }

    public static float normal(ByteBuffer quads, int q, int c) {
        int face = face(quads.getInt(q * QUAD_BYTES));
        return switch (face) {
            case 0 -> c == 1 ? 1f : 0f;
            case 1 -> c == 1 ? -1f : 0f;
            case 2 -> c == 2 ? -1f : 0f;
            case 3 -> c == 2 ? 1f : 0f;
            case 4 -> c == 0 ? 1f : 0f;
            default -> c == 0 ? -1f : 0f;
        };
    }

    /** The {@link MmsBufferLayout#packFlags} word a vertex of this quad corner would carry. */
    public static int flags(ByteBuffer quads, int q, int corner) {
        int w1 = quads.getInt(q * QUAD_BYTES + 4);
        int w2 = quads.getInt(q * QUAD_BYTES + 8);
        return MmsBufferLayout.packFlags(0f, alpha(w1) ? 1f : 0f, translucent(w1) ? 1f : 0f,
            light(w2, corner));
    }

    public static float layer(ByteBuffer quads, int q) {
        return layer(quads.getInt(q * QUAD_BYTES + 4));
    }
}
