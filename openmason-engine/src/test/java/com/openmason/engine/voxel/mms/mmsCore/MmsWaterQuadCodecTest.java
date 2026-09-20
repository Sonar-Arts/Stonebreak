package com.openmason.engine.voxel.mms.mmsCore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

/**
 * word3's bit fields. Extent, flow and water-column depth share one 32-bit
 * word, and the shader decodes them by hand ({@code water.vert}), so a field
 * that overruns its neighbour shows up as the wrong river direction or a
 * shoreline that reads as an abyss.
 */
class MmsWaterQuadCodecTest {

    @Test
    void extentFlowAndDepthSurviveTheSameWord() {
        int w3 = MmsWaterQuadCodec.word3(16, 3, 5, 14);
        assertEquals(16, MmsWaterQuadCodec.width(w3));
        assertEquals(3, MmsWaterQuadCodec.height(w3));
        assertEquals(5, MmsWaterQuadCodec.flow(w3));
        assertEquals(14, MmsWaterQuadCodec.depth(w3));
    }

    @Test
    void aDepthOf255DoesNotBleedIntoFlowOrExtent() {
        int w3 = MmsWaterQuadCodec.word3(1, 1, MmsWaterQuadCodec.NO_FLOW, 255);
        assertEquals(1, MmsWaterQuadCodec.width(w3));
        assertEquals(1, MmsWaterQuadCodec.height(w3));
        assertEquals(MmsWaterQuadCodec.NO_FLOW, MmsWaterQuadCodec.flow(w3));
        assertEquals(255, MmsWaterQuadCodec.depth(w3));
    }

    @Test
    void callersThatNeverSetDepthGetZero() {
        assertEquals(0, MmsWaterQuadCodec.depth(MmsWaterQuadCodec.word3(4, 4)));
        assertEquals(0, MmsWaterQuadCodec.depth(MmsWaterQuadCodec.word3(4, 4, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> MmsWaterQuadCodec.word3(1, 1, MmsWaterQuadCodec.NO_FLOW, 256));
    }

    @Test
    void theLayerSlotOfAWaterVertexCarriesTheDepth() {
        // Water is untextured, so the per-vertex path reads the depth out of
        // the texture-layer channel (water.vert location 4) — the pulled
        // record has to expand to the same number for a reader of the mesh.
        ByteBuffer quads = ByteBuffer.allocate(MmsWaterQuadCodec.QUAD_BYTES).order(ByteOrder.nativeOrder());
        quads.putInt(MmsWaterQuadCodec.word0(1, 320, 2, 0, false, true));
        quads.putInt(MmsWaterQuadCodec.word1(320, 320.875f, 320.875f, 320.875f, 320.875f));
        quads.putInt(MmsWaterQuadCodec.word2(0.875f, 0.875f, 0.875f, 0.875f));
        quads.putInt(MmsWaterQuadCodec.word3(1, 1, MmsWaterQuadCodec.NO_FLOW, 9));
        quads.flip();

        for (int corner = 0; corner < 4; corner++) {
            assertEquals(9f, MmsVertexFormat.WATERQUAD16.layer(quads, corner), 1e-4f);
        }
    }
}
