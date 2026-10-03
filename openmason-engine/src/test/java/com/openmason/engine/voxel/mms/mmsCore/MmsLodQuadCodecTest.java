package com.openmason.engine.voxel.mms.mmsCore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openmason.engine.voxel.mms.mmsTexturing.MmsArrayTextureMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

/**
 * LOD UVs are derived, not stored (issue #234): they must tile the layer once
 * per block on the block grid and face the same way native chunk faces do, or
 * the LOD→native handover pops in texel density / flips grass sides.
 */
class MmsLodQuadCodecTest {

    private static ByteBuffer record(int x, int yHalf, int z, int face, int wHalf, int hHalf) {
        ByteBuffer b = ByteBuffer.allocate(MmsLodQuadCodec.QUAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, MmsLodQuadCodec.word0(x, z, yHalf, face, false, true));
        b.putInt(4, MmsLodQuadCodec.word1(wHalf, hHalf, 7, false));
        return b;
    }

    /** Unit block faces reproduce the native array mapper's frame, corner for corner. */
    @Test
    void unitFaceMatchesTheNativeChunkFrame() {
        MmsArrayTextureMapper mapper = new MmsArrayTextureMapper(
            (block, face) -> new float[]{0f, 0f, 1f, 1f}, (block, face) -> 0);
        for (int face = 0; face < 6; face++) {
            float[] nativeUv = mapper.generateFaceTextureCoordinates(null, face).clone();
            ByteBuffer q = record(3, 10, 7, face, 2, 2);
            float u0 = MmsLodQuadCodec.texCoord(q, 0, 0, 0);
            float v0 = MmsLodQuadCodec.texCoord(q, 0, 0, 1);
            assertEquals(Math.rint(u0), u0, 1e-6f, "face " + face + " u on the block grid");
            assertEquals(Math.rint(v0), v0, 1e-6f, "face " + face + " v on the block grid");
            for (int corner = 1; corner < 4; corner++) {
                assertEquals(nativeUv[corner * 2] - nativeUv[0],
                    MmsLodQuadCodec.texCoord(q, 0, corner, 0) - u0, 1e-6f,
                    "face " + face + " corner " + corner + " du");
                assertEquals(nativeUv[corner * 2 + 1] - nativeUv[1],
                    MmsLodQuadCodec.texCoord(q, 0, corner, 1) - v0, 1e-6f,
                    "face " + face + " corner " + corner + " dv");
            }
        }
    }

    /** A 16-block cell spans 16 repetitions, not one stretched tile. */
    @Test
    void wideRecordsRepeatOncePerBlock() {
        ByteBuffer top = record(16, 140, 32, 0, 32, 32);
        float du = 0f;
        float dv = 0f;
        for (int corner = 1; corner < 4; corner++) {
            du = Math.max(du, Math.abs(MmsLodQuadCodec.texCoord(top, 0, corner, 0)
                - MmsLodQuadCodec.texCoord(top, 0, 0, 0)));
            dv = Math.max(dv, Math.abs(MmsLodQuadCodec.texCoord(top, 0, corner, 1)
                - MmsLodQuadCodec.texCoord(top, 0, 0, 1)));
        }
        assertEquals(16f, du, 1e-6f);
        assertEquals(16f, dv, 1e-6f);

        // A tall foundation: 3.5 blocks wide, 100 blocks high.
        ByteBuffer wall = record(0, 0, 0, 4, 7, 200);
        float dy = 0f;
        for (int corner = 1; corner < 4; corner++) {
            dy = Math.max(dy, Math.abs(MmsLodQuadCodec.texCoord(wall, 0, corner, 1)
                - MmsLodQuadCodec.texCoord(wall, 0, 0, 1)));
        }
        assertEquals(100f, dy, 1e-6f);
    }
}
