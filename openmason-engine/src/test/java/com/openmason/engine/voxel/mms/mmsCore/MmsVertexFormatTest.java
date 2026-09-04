package com.openmason.engine.voxel.mms.mmsCore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encode/decode contract of every {@link MmsVertexFormat}: what the builder
 * packs, the SoA getters (and therefore the validator, mesh cache and
 * consistency tests) must read back — positions exactly for block-aligned
 * geometry, everything else within the format's quantization.
 */
class MmsVertexFormatTest {

    @AfterEach
    void restore() {
        MmsVertexFormat.override(MmsVertexFormat.DEFAULT);
    }

    @Test
    void stridesStayFourByteAligned() {
        for (MmsVertexFormat f : MmsVertexFormat.values()) {
            assertEquals(0, f.stride() % 4, f + " stride must keep staging-ring offsets 4-aligned");
        }
    }

    @Test
    void compact20RoundTripsBlockGeometryExactly() {
        MmsVertexFormat f = MmsVertexFormat.COMPACT20;
        ByteBuffer buf = ByteBuffer.allocate(f.stride() * 2).order(ByteOrder.nativeOrder());
        float ox = 1024f, oz = -2048f;
        // A greedy-merged top quad corner at world (1040.0, 73.0, -2033.0), uv (16, 9), layer 300
        f.encode(buf, 1040f, 73f, -2033f, 16f, 9f, 0f, 1f, 0f, 0.875f, 0f, 1f, 0.5f, 300f, ox, 0f, oz);
        // A water corner at a 1/8 surface height and a cross-block corner at 0.15
        f.encode(buf, 1024.15f, 63.875f, -2047.85f, 0.0625f, 0.5f, 0.7071f, 0f, -0.7071f,
            0f, 1f, 0f, 1f, 7f, ox, 0f, oz);

        assertEquals(1040f, f.position(buf, 0, 0, ox, 0f, oz));
        assertEquals(73f, f.position(buf, 0, 1, ox, 0f, oz));
        assertEquals(-2033f, f.position(buf, 0, 2, ox, 0f, oz));
        assertEquals(16f, f.texCoord(buf, 0, 0));
        assertEquals(9f, f.texCoord(buf, 0, 1));
        assertEquals(1f, f.normal(buf, 0, 1));
        assertEquals(300f, f.layer(buf, 0));
        int flags = f.flags(buf, 0);
        assertEquals(223, flags & 0xFF, "water 0.875 → 223");
        assertEquals(0, (flags >>> 8) & 0xFF);
        assertEquals(255, (flags >>> 16) & 0xFF);
        assertEquals(128, (flags >>> 24) & 0xFF, "light 0.5 → 128");

        assertEquals(63.875f, f.position(buf, 1, 1, ox, 0f, oz), "1/8 water heights are exact");
        assertEquals(1024.15f, f.position(buf, 1, 0, ox, 0f, oz), 1f / 128f);
        assertEquals(0.0625f, f.texCoord(buf, 1, 0), "texel UVs are exact in f16");
        assertEquals(0.7071f, f.normal(buf, 1, 0), 0.01f);
        assertEquals(-0.7071f, f.normal(buf, 1, 2), 0.01f);
        assertEquals(7f, f.layer(buf, 1));
    }

    /**
     * Chunk meshes are given originY = 0 (MmsCcoAdapter), so a stamp's Y is
     * encoded as a raw world Y. At the original 1/64-block unit this threw for
     * everything above y=512; the top vertex of the world then sat one unit
     * past the widened range until the Y window was shifted.
     */
    @Test
    void compact20SpansAWorldColumnFromAZeroYOrigin() {
        MmsVertexFormat f = MmsVertexFormat.COMPACT20;
        ByteBuffer buf = ByteBuffer.allocate(f.stride() * 3).order(ByteOrder.nativeOrder());
        for (float y : new float[]{0f, 535f, 1024f}) {
            f.encode(buf, 0f, y, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f);
        }
        assertEquals(0f, f.position(buf, 0, 1, 0f, 0f, 0f), "the floor of the world");
        assertEquals(535f, f.position(buf, 1, 1, 0f, 0f, 0f), "the peak that reported this bug");
        assertEquals(1024f, f.position(buf, 2, 1, 0f, 0f, 0f),
            "the top face of a block on the world's top layer");
    }

    /** The shift buys Y headroom by giving up depth no producer can reach. */
    @Test
    void compact20KeepsSlackBelowTheOriginForMeshesPivotedOnZero() {
        MmsVertexFormat f = MmsVertexFormat.COMPACT20;
        ByteBuffer buf = ByteBuffer.allocate(f.stride()).order(ByteOrder.nativeOrder());
        f.encode(buf, 0f, MmsVertexFormat.COMPACT_MIN_Y, 0f, 0f, 0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f);
        assertEquals(MmsVertexFormat.COMPACT_MIN_Y, f.position(buf, 0, 1, 0f, 0f, 0f));
        assertTrue(MmsVertexFormat.COMPACT_MIN_Y <= -512f,
            "a model mesh pivoted on its own centre must still encode");
    }

    @Test
    void compact20RejectsPositionsOutsideTheFixedPointRange() {
        MmsVertexFormat f = MmsVertexFormat.COMPACT20;
        ByteBuffer buf = ByteBuffer.allocate(f.stride()).order(ByteOrder.nativeOrder());
        assertThrows(IllegalArgumentException.class, () ->
            f.encode(buf, MmsVertexFormat.COMPACT_MAX_BLOCKS + 1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () ->
            f.encode(buf, 0f, MmsVertexFormat.COMPACT_MAX_Y + 1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f));
        assertThrows(IllegalArgumentException.class, () ->
            f.encode(buf, 0f, MmsVertexFormat.COMPACT_MIN_Y - 1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f));
    }

    /**
     * The Y shift is invisible outside the format: the shader adds the origin
     * attribute, so the shift has to be folded into the origin the GPU is
     * handed, not into the decode path alone.
     */
    @Test
    void compact20FoldsTheYShiftIntoTheOriginTheGpuReads() {
        assertEquals(MmsVertexFormat.COMPACT_Y_SHIFT, MmsVertexFormat.COMPACT20.originYShift());
        for (MmsVertexFormat f : MmsVertexFormat.values()) {
            if (f != MmsVertexFormat.COMPACT20) {
                assertEquals(0f, f.originYShift(),
                    f + " must read an unshifted origin — its Y is absolute world Y");
            }
        }
    }

    @Test
    void builderAndGettersAgreeUnderCompact20() {
        MmsVertexFormat.override(MmsVertexFormat.COMPACT20);
        MmsMeshBuilder b = MmsMeshBuilder.createWithCapacity(4).setOrigin(128f, 0f, 256f);
        b.beginFace();
        b.addVertex(130, 70, 260, 0, 0, 0, 1, 0, 0f, 0f, 0f, 1f, 5f);
        b.addVertex(146, 70, 260, 16, 0, 0, 1, 0, 0f, 0f, 0f, 1f, 5f);
        b.addVertex(146, 70, 263, 16, 3, 0, 1, 0, 0f, 0f, 0f, 1f, 5f);
        b.addVertex(130, 70, 263, 0, 3, 0, 1, 0, 0f, 0f, 0f, 1f, 5f);
        b.endFace();
        MmsMeshData mesh = b.build();

        assertEquals(MmsVertexFormat.COMPACT20, mesh.getFormat());
        assertEquals(4 * 20, mesh.getPackedVertexData().length);
        assertEquals(128f, mesh.getOriginX());
        assertEquals(256f, mesh.getOriginZ());
        assertArrayEquals(new float[]{130, 70, 260, 146, 70, 260, 146, 70, 263, 130, 70, 263},
            mesh.getVertexPositions());
        assertArrayEquals(new float[]{0, 0, 16, 0, 16, 3, 0, 3}, mesh.getTextureCoordinates());
        assertArrayEquals(new float[]{5, 5, 5, 5}, mesh.getLayerIndices());
        assertArrayEquals(new float[]{1, 1, 1, 1}, mesh.getLightValues());
        assertTrue(MmsMeshValidator.validate(mesh).isValid());

        // A SoA mesh packs through the same format with a self-contained origin.
        MmsMeshData soa = new MmsMeshData(
            new float[]{1000, 5, 1000, 1001, 5, 1000, 1001, 5, 1001, 1000, 5, 1001},
            new float[]{0, 0, 1, 0, 1, 1, 0, 1},
            new float[]{0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0},
            new float[4], new float[4], new float[4], new float[]{1, 1, 1, 1}, new float[4],
            new int[]{0, 2, 1, 0, 3, 2}, 6);
        MmsMeshData packed = soa.toPacked();
        assertEquals(1000f, packed.getOriginX());
        assertArrayEquals(soa.getVertexPositions(), packed.getVertexPositions());
    }

    @Test
    void legacy40IsByteIdenticalToTheOriginalLayout() {
        MmsVertexFormat f = MmsVertexFormat.LEGACY40;
        ByteBuffer buf = ByteBuffer.allocate(40).order(ByteOrder.nativeOrder());
        f.encode(buf, 1f, 2f, 3f, 4f, 5f, 0f, 0f, 1f, 0.5f, 1f, 0f, 1f, 9f, 999f, 999f, 999f);
        assertEquals(1f, buf.getFloat(0));
        assertEquals(5f, buf.getFloat(16));
        assertEquals(1f, buf.getFloat(28));
        assertEquals(MmsBufferLayout.packFlags(0.5f, 1f, 0f, 1f), buf.getInt(32));
        assertEquals(9f, buf.getFloat(36));
    }
}
