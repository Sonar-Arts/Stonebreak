package com.stonebreak.world.voxel;

import com.openmason.engine.voxel.stamp.StampMeshProcessor.FaceStamp;
import com.openmason.engine.voxel.world.CubeWorld;
import com.openmason.engine.voxel.world.CubeWorld.ModelQuad;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stamp triangles -> cv_model_quads: shared-edge pairs become one quad, positions shift to cell-local [0,1]. */
class CubeRenderAssetsTest {

    /** The top face of a unit cube as two triangles (a, b, c), (a, c, d), centred on the cell. */
    private static FaceStamp topFace() {
        float[] a = {-0.5f, 0.5f, -0.5f}, b = {0.5f, 0.5f, -0.5f}, c = {0.5f, 0.5f, 0.5f}, d = {-0.5f, 0.5f, 0.5f};
        float[] pos = concat(a, b, c, a, c, d);
        float[] nrm = new float[18];
        for (int i = 0; i < 6; i++) nrm[i * 3 + 1] = 1f;
        float[] uv = {0, 0, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1};
        float[] layers = {7, 7, 7, 7, 7, 7};
        return new FaceStamp(pos, nrm, uv, layers, 6);
    }

    private static float[] concat(float[]... parts) {
        float[] out = new float[parts.length * 3];
        for (int i = 0; i < parts.length; i++) System.arraycopy(parts[i], 0, out, i * 3, 3);
        return out;
    }

    @Test
    void trianglePairBecomesOneQuad() {
        List<ModelQuad> out = new ArrayList<>();
        CubeRenderAssets.appendQuads(out, topFace(), 0, 0, 1f);
        assertEquals(1, out.size());
        ModelQuad q = out.get(0);
        assertEquals(7, q.textureLayer());
        assertEquals(0, q.cullFace());
        for (int c = 0; c < 4; c++) {
            assertEquals(1f, q.pos()[c * 3 + 1], 1e-6f);
            assertTrue(q.pos()[c * 3] == 0f || q.pos()[c * 3] == 1f);
        }
        assertEquals(1f, q.normal()[1], 0f);
    }

    @Test
    void loneTriangleBecomesADegenerateQuad() {
        FaceStamp full = topFace();
        FaceStamp one = new FaceStamp(java.util.Arrays.copyOf(full.positions(), 9),
            java.util.Arrays.copyOf(full.normals(), 9), java.util.Arrays.copyOf(full.atlasUVs(), 6),
            java.util.Arrays.copyOf(full.layers(), 3), 3);
        List<ModelQuad> out = new ArrayList<>();
        CubeRenderAssets.appendQuads(out, one, CubeWorld.CULL_NEVER, CubeWorld.MODEL_CUTOUT, 1f);
        assertEquals(1, out.size());
        float[] p = out.get(0).pos();
        assertEquals(p[6], p[9], 0f);
        assertEquals(p[8], p[11], 0f);
    }

    @Test
    void snowSquashesHeight() {
        List<ModelQuad> out = new ArrayList<>();
        CubeRenderAssets.appendQuads(out, topFace(), CubeWorld.CULL_NEVER, 0, 0.25f);
        for (int c = 0; c < 4; c++) {
            assertEquals(0.25f, out.get(0).pos()[c * 3 + 1], 1e-6f);
        }
    }

    @Test
    void aQuadPastTheModelRangeIsClippedWithItsUvs() {
        // A wall at x = 0.5 from y = 0 to y = 7 (a seven-cell model): the ABI takes corners in [-4, 5].
        float[] pos = {0.5f, 0f, 0f, 0.5f, 0f, 1f, 0.5f, 7f, 1f, 0.5f, 7f, 0f};
        float[] uv = {0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f};
        ModelQuad tall = new ModelQuad(pos, uv, new float[]{1f, 0f, 0f}, 3, CubeWorld.CULL_NEVER, 0);
        ModelQuad inside = new ModelQuad(new float[]{0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1}, new float[8],
            new float[]{0f, -1f, 0f}, 3, 1, 0);
        List<ModelQuad> in = List.of(inside, tall);

        List<ModelQuad> out = CubeRenderAssets.fitModelRange(in);

        assertEquals(2, out.size());
        assertTrue(out.get(0) == inside, "in-range quads pass through untouched");
        ModelQuad cut = out.get(1);
        float maxY = 0f, maxV = 0f;
        for (int c = 0; c < 4; c++) {
            maxY = Math.max(maxY, cut.pos()[c * 3 + 1]);
            maxV = Math.max(maxV, cut.uv()[c * 2 + 1]);
            assertEquals(0.5f, cut.pos()[c * 3], 0f);
        }
        assertEquals(CubeWorld.MODEL_CORNER_MAX, maxY, 1e-6f);
        assertEquals(CubeWorld.MODEL_CORNER_MAX / 7f, maxV, 1e-6f);
        assertEquals(3, cut.textureLayer());
    }

    @Test
    void aQuadWhollyOutsideTheRangeDisappears() {
        float[] pos = {0f, 7f, 0f, 1f, 7f, 0f, 1f, 7f, 1f, 0f, 7f, 1f};
        ModelQuad above = new ModelQuad(pos, new float[8], new float[]{0f, 1f, 0f}, 0, 0, 0);
        assertEquals(List.of(), CubeRenderAssets.fitModelRange(List.of(above)));
    }
}
