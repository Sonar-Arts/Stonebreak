package com.openmason.engine.voxel.mms.mmsCore;

import com.openmason.engine.voxel.mms.mmsGeometry.MmsCuboidGenerator;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the QUAD16 record: the decoded corners of a packed quad are exactly
 * the vertices {@link MmsCuboidGenerator#generateScaledFaceVertices} emits for
 * the same face rectangle, UVs match the mapper's affine frame for all eight
 * orientations, and the builder round-trips through {@link MmsMeshData}.
 */
class MmsQuadCodecTest {

    private final MmsCuboidGenerator cuboids = new MmsCuboidGenerator();

    @Test
    void cornersMatchTheCuboidGeneratorForEveryFace() {
        for (int face = 0; face < 6; face++) {
            int w = 3, h = 5;
            MmsQuadMeshBuilder b = new MmsQuadMeshBuilder(1).setOrigin(1024f, 0f, -512f);
            b.addQuad(7, 70, 9, face, w, h, 0, false, false, 12, 1f, 0.75f, 0.5f, 0.25f);
            MmsMeshData mesh = b.build();
            float[] expected = cuboids.generateScaledFaceVertices(face, 1024f + 7, 70f, -512f + 9, w, h);
            float[] actual = mesh.getVertexPositions();
            assertArrayEquals(expected, actual, 0f, "face " + face + " corners");
            float[] n = mesh.getVertexNormals();
            float[] en = cuboids.generateFaceNormals(face);
            assertArrayEquals(en, n, 0f, "face " + face + " normals");
            float[] light = mesh.getLightValues();
            assertEquals(1f, light[0]);
            assertEquals(0.25f, light[3], 0.01f);
            assertArrayEquals(new float[]{12, 12, 12, 12}, mesh.getLayerIndices());
            assertArrayEquals(new int[]{0, 2, 1, 0, 3, 2}, mesh.getIndices());
            assertTrue(MmsMeshValidator.validate(mesh).isValid());
        }
    }

    @Test
    void orientationRoundTripsAllEightUvFrames() {
        for (int o = 0; o < 8; o++) {
            float u0 = MmsQuadCodec.frameU0(o), v0 = MmsQuadCodec.frameV0(o);
            float duU = MmsQuadCodec.frameDuU(o), duV = MmsQuadCodec.frameDuV(o);
            float dvU = MmsQuadCodec.frameDvU(o), dvV = MmsQuadCodec.frameDvV(o);
            assertEquals(o, MmsQuadCodec.orientation(u0, v0, duU, duV, dvU, dvV), "orientation " + o);
            // Frame corners stay inside the unit square.
            assertTrue(u0 + duU == 0f || u0 + duU == 1f);
            assertTrue(v0 + dvV == 0f || v0 + dvV == 1f || (o & 4) != 0);
        }
        assertEquals(-1, MmsQuadCodec.orientation(0.5f, 0f, 1f, 0f, 0f, 1f));
        assertEquals(-1, MmsQuadCodec.orientation(0f, 0f, 1f, 1f, 0f, 1f));
    }

    @Test
    void builderRefusesQuadsBeyondTheSharedIndexLimit() {
        MmsQuadMeshBuilder b = new MmsQuadMeshBuilder(16);
        for (int i = 0; i < MmsQuadCodec.MAX_QUADS_PER_DRAW; i++) {
            assertTrue(b.addQuad(i & 255, i >> 8, 0, 0, 1, 1, 0, false, false, 0, 1, 1, 1, 1));
        }
        assertTrue(b.isFull());
        assertTrue(!b.addQuad(0, 0, 0, 0, 1, 1, 0, false, false, 0, 1, 1, 1, 1));
        assertEquals(MmsQuadCodec.MAX_QUADS_PER_DRAW * 4, b.build().getVertexCount());
    }

    @Test
    void uvsScaleWithTheRectangleInTheAuthoredFrame() {
        MmsQuadMeshBuilder b = new MmsQuadMeshBuilder(1);
        b.addQuad(0, 0, 0, 0, 4, 2, 0, true, false, 3, 1f, 1f, 1f, 1f);
        MmsMeshData mesh = b.build();
        float[] uv = mesh.getTextureCoordinates();
        // Top face corner offsets (0,1,1),(1,1,1),(1,1,0),(0,1,0): u = x·w, v = z·h
        assertArrayEquals(new float[]{0, 2, 4, 2, 4, 0, 0, 0}, uv, 0f);
        assertArrayEquals(new float[]{1, 1, 1, 1}, mesh.getAlphaTestFlags());
        ByteBuffer raw = ByteBuffer.wrap(mesh.getPackedVertexData()).order(ByteOrder.nativeOrder());
        assertEquals(16, mesh.getPackedVertexData().length);
        assertEquals(4, MmsQuadCodec.width(raw.getInt(0)));
        assertEquals(2, MmsQuadCodec.height(raw.getInt(4)));
    }
}
