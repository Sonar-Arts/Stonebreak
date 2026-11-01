package com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The native {@code ck_mesh_chunk} kernel used to be compiled for a 16×256×16 chunk and CLAMPED
 * {@code max_y} to 255 rather than reporting that it could not do the job; {@code MmsCcoAdapter}
 * treats a successful kernel call as authoritative, so on a 1024-tall world the whole surface went
 * unmeshed. Since #385 (ABI 12) the kernel takes the world's chunk size and height as arguments:
 * it meshes the top of any column, and the only dimension that rules it out is a chunk wider than
 * its row masks.
 */
class CendaMesherDimensionsTest {

    @Test
    void theNativeMesherIsUsedWheneverTheChunkFitsItsRowMasks() {
        assertTrue(WorldConfiguration.CHUNK_SIZE <= CendaKernels.MESH_MAX_CHUNK_SIZE,
            "Stonebreak's chunks fit the native mesher");
        if (CendaKernels.isAvailable() && !"java".equalsIgnoreCase(System.getProperty("stonebreak.mesher.backend"))) {
            assertTrue(CendaMesher.enabled());
        }
    }

    @Test
    void theKernelMeshesTheTopOfTheGamesColumnAndOfATallerOne() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda kernels not built");
        for (int wh : new int[]{WorldConfiguration.WORLD_HEIGHT, 1024}) {
            int cs = WorldConfiguration.CHUNK_SIZE;
            short[] blocks = new short[cs * cs * wh];
            int top = wh - 1;
            blocks[(top * cs + cs / 2) * cs + cs / 2] = 1; // one cube in the top layer
            byte[] classes = {0, (byte) (CendaKernels.CLASS_CUBE)};
            short[] heights = new short[(cs + 2) * (cs + 2)];
            float[] out = new float[9 * 16];
            int n = CendaKernels.meshChunk(blocks, cs, wh, classes, 0, null, null, null, null,
                null, null, null, null, heights, top, true, out);
            assertEquals(6, n, "a lone cube at y=" + top + " of a " + wh + "-tall column");
            for (int q = 0; q < n; q++) {
                assertEquals(top, (int) out[q * 9 + 1]);
            }
        }
        assertEquals(Integer.MIN_VALUE, CendaKernels.meshChunk(new short[17 * 17 * 16], 17, 16, new byte[2], 0,
            null, null, null, null, null, null, null, null, new short[19 * 19], 15, true, new float[9]),
            "a chunk wider than the row masks is declined, never truncated");
    }
}
