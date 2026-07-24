package com.openmason.engine.voxel.mms.mmsCore;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Packed-representation contract of {@link MmsMeshBuilder#build()}: the
 * builder emits interleaved GPU-layout bytes + u16 indices directly on the
 * meshing thread, and the SoA getters materialize consistent values back
 * (flags quantized to the exact bytes the GPU reads).
 */
class MmsMeshBuilderPackedTest {

    private static MmsMeshBuilder quadBuilder() {
        MmsMeshBuilder builder = MmsMeshBuilder.createWithCapacity(4);
        builder.beginFace();
        builder.addVertex(0, 10, 0, 0, 0, 0, 1, 0, 0.875f, 0f, 0f, 0.5f, 3f);
        builder.addVertex(1, 10, 0, 1, 0, 0, 1, 0, 0.875f, 0f, 0f, 0.5f, 3f);
        builder.addVertex(1, 10, 1, 1, 1, 0, 1, 0, 0.875f, 1f, 0f, 0.5f, 3f);
        builder.addVertex(0, 10, 1, 0, 1, 0, 1, 0, 0.875f, 1f, 0f, 0.5f, 3f);
        builder.endFace();
        return builder;
    }

    @Test
    void buildProducesPackedFormWithShortIndices() {
        MmsMeshData mesh = quadBuilder().build();

        assertTrue(mesh.isPacked());
        assertTrue(mesh.hasShortIndices(), "4 vertices must use u16 indices");
        assertEquals(4, mesh.getVertexCount());
        assertEquals(6, mesh.getIndexCount());
        assertNotNull(mesh.getPackedVertexData());
        assertEquals(4 * MmsBufferLayout.VERTEX_STRIDE_BYTES, mesh.getPackedVertexData().length);
        assertEquals(6 * Short.BYTES, mesh.getPackedIndexData().length);

        // endFace winding: (0,2,1, 0,3,2)
        assertArrayEquals(new int[]{0, 2, 1, 0, 3, 2}, mesh.getIndices());
    }

    @Test
    void packedBytesFollowTheGpuLayout() {
        MmsMeshData mesh = quadBuilder().build();
        ByteBuffer buf = ByteBuffer.wrap(mesh.getPackedVertexData()).order(ByteOrder.nativeOrder());

        // Vertex 2: pos (1,10,1), uv (1,1), normal (0,1,0), layer 3
        int base = 2 * MmsBufferLayout.VERTEX_STRIDE_BYTES;
        assertEquals(1f, buf.getFloat(base));
        assertEquals(10f, buf.getFloat(base + 4));
        assertEquals(1f, buf.getFloat(base + 8));
        assertEquals(1f, buf.getFloat(base + (int) MmsBufferLayout.TEXTURE_OFFSET));
        assertEquals(1f, buf.getFloat(base + (int) MmsBufferLayout.TEXTURE_OFFSET + 4));
        assertEquals(0f, buf.getFloat(base + (int) MmsBufferLayout.NORMAL_OFFSET));
        assertEquals(1f, buf.getFloat(base + (int) MmsBufferLayout.NORMAL_OFFSET + 4));
        assertEquals(3f, buf.getFloat(base + (int) MmsBufferLayout.LAYER_OFFSET));

        // Flags word: water=0.875, alpha=0, translucent=0, light=0.5 — must
        // equal packFlags exactly (what the old GL-thread interleave produced).
        int packed = buf.getInt(base + (int) MmsBufferLayout.FLAGS_OFFSET);
        assertEquals(MmsBufferLayout.packFlags(0.875f, 0f, 0f, 0.5f), packed);
    }

    @Test
    void materializedGettersRoundTripFloatsExactlyAndFlagsQuantized() {
        MmsMeshData mesh = quadBuilder().build();

        float[] pos = mesh.getVertexPositions();
        assertEquals(12, pos.length);
        assertEquals(10f, pos[1]);
        assertEquals(1f, pos[6]);

        // Exact 0/1 flags survive quantization exactly.
        float[] alpha = mesh.getAlphaTestFlags();
        for (float a : alpha) assertEquals(0f, a);
        float[] translucent = mesh.getTranslucentFlags();
        for (float t : translucent) assertEquals(0f, t);

        // Fractional flags come back on the 1/255 grid — the GPU's view.
        float expectedWater = MmsBufferLayout.toUnsignedByte(0.875f) / 255.0f;
        for (float w : mesh.getWaterHeightFlags()) assertEquals(expectedWater, w, 1e-6f);
        float expectedLight = MmsBufferLayout.toUnsignedByte(0.5f) / 255.0f;
        for (float l : mesh.getLightValues()) assertEquals(expectedLight, l, 1e-6f);

        for (float layer : mesh.getLayerIndices()) assertEquals(3f, layer);
    }

    @Test
    void validationCatchesNonFinitePositions() {
        MmsMeshBuilder builder = MmsMeshBuilder.create();
        builder.beginFace();
        builder.addVertex(Float.NaN, 0, 0, 0, 0, 0, 1, 0, 0, 0);
        builder.addVertex(1, 0, 0, 1, 0, 0, 1, 0, 0, 0);
        builder.addVertex(1, 1, 0, 1, 1, 0, 1, 0, 0, 0);
        builder.addVertex(0, 1, 0, 0, 1, 0, 1, 0, 0, 0);
        builder.endFace();
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void validationCatchesOutOfBoundsIndices() {
        MmsMeshBuilder builder = MmsMeshBuilder.create();
        builder.beginFace();
        builder.addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0);
        builder.addVertex(1, 0, 0, 1, 0, 0, 1, 0, 0, 0);
        builder.addVertex(1, 1, 0, 1, 1, 0, 1, 0, 0, 0);
        builder.addVertex(0, 1, 0, 0, 1, 0, 1, 0, 0, 0);
        builder.endFace();
        builder.addIndex(9).addIndex(0).addIndex(1); // 9 > max vertex 3
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void validatorAcceptsPackedMeshes() {
        MmsMeshData mesh = quadBuilder().build();
        assertTrue(MmsMeshValidator.validate(mesh).isValid());
    }

    @Test
    void soaConstructorStaysUnpacked() {
        MmsMeshData mesh = new MmsMeshData(
            new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0},
            new float[]{0, 0, 1, 0, 1, 1},
            new float[]{0, 1, 0, 0, 1, 0, 0, 1, 0},
            new float[]{0, 0, 0},
            new float[]{0, 0, 0},
            new float[]{0, 0, 0},
            new int[]{0, 1, 2}, 3);
        assertFalse(mesh.isPacked());
        assertEquals(3, mesh.getVertexCount());
        assertArrayEquals(new int[]{0, 1, 2}, mesh.getIndices());
    }

    @Test
    void packedEqualityIsContentBased() {
        MmsMeshData a = quadBuilder().build();
        MmsMeshData b = quadBuilder().build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void buildAndResetAllowsBuilderReuse() {
        MmsMeshBuilder builder = quadBuilder();
        MmsMeshData first = builder.buildAndReset();
        assertTrue(builder.isEmpty());
        builder.beginFace();
        builder.addVertex(5, 5, 5, 0, 0, 0, 1, 0, 0, 0);
        builder.addVertex(6, 5, 5, 1, 0, 0, 1, 0, 0, 0);
        builder.addVertex(6, 6, 5, 1, 1, 0, 1, 0, 0, 0);
        builder.addVertex(5, 6, 5, 0, 1, 0, 1, 0, 0, 0);
        builder.endFace();
        MmsMeshData second = builder.build();

        assertEquals(4, first.getVertexCount());
        assertEquals(4, second.getVertexCount());
        assertEquals(5f, second.getVertexPositions()[0]);
        assertEquals(0f, first.getVertexPositions()[0]);
    }

    @Test
    void fromPackedRejectsMismatchedSizes() {
        assertThrows(IllegalArgumentException.class, () ->
            MmsMeshData.fromPacked(new byte[MmsBufferLayout.VERTEX_STRIDE_BYTES],
                new byte[5], true, 1, 3));
        assertThrows(IllegalArgumentException.class, () ->
            MmsMeshData.fromPacked(new byte[10], new byte[6], true, 1, 3));
    }
}
