package com.stonebreak.world.fastlod;

import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeKind;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeSample;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The pulled LOD writer ({@code LODQUAD16}) must emit the same rectangles as
 * the per-vertex writer: same corner positions, layers, light, alpha, and
 * normals within octahedral precision — for cells with skirts, foundations,
 * a submerged cell and an L0 tree.
 */
class FastLodPulledParityTest {

    @AfterEach
    void restore() {
        MmsVertexFormat.override(MmsVertexFormat.DEFAULT);
    }

    private static FastLodMesher mesher() {
        BlockTextureArray textures = mock(BlockTextureArray.class);
        when(textures.getBlockFaceLayer(any(), anyInt())).thenAnswer(inv -> 3 + (int) inv.getArgument(1));
        return new FastLodMesher(textures);
    }

    /**
     * Rasterises every quad into half-block tiles on its plane, keyed by face,
     * plane coordinate, tile position, layer, light and alpha. Merged
     * rectangles and per-cell quads cover the same tiles, so the multisets
     * must match exactly; normals are checked separately.
     */
    private static java.util.Map<String, Integer> tiles(MmsMeshData mesh) {
        float[] p = mesh.getVertexPositions();
        float[] n = mesh.getVertexNormals();
        float[] l = mesh.getLightValues();
        float[] a = mesh.getAlphaTestFlags();
        float[] layer = mesh.getLayerIndices();
        java.util.Map<String, Integer> out = new java.util.TreeMap<>();
        for (int q = 0; q < mesh.getVertexCount() / 4; q++) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            float nx = 0, ny = 0, nz = 0;
            for (int c = 0; c < 4; c++) {
                int v = q * 4 + c;
                minX = Math.min(minX, p[v * 3]); maxX = Math.max(maxX, p[v * 3]);
                minY = Math.min(minY, p[v * 3 + 1]); maxY = Math.max(maxY, p[v * 3 + 1]);
                minZ = Math.min(minZ, p[v * 3 + 2]); maxZ = Math.max(maxZ, p[v * 3 + 2]);
                nx += n[v * 3]; ny += n[v * 3 + 1]; nz += n[v * 3 + 2];
            }
            String axis = Math.abs(nx) >= Math.abs(ny) && Math.abs(nx) >= Math.abs(nz) ? (nx > 0 ? "+x" : "-x")
                : Math.abs(ny) >= Math.abs(nz) ? (ny > 0 ? "+y" : "-y") : (nz > 0 ? "+z" : "-z");
            int x0 = Math.round(minX * 2), x1 = Math.max(x0 + 1, Math.round(maxX * 2));
            int y0 = Math.round(minY * 2), y1 = Math.max(y0 + 1, Math.round(maxY * 2));
            int z0 = Math.round(minZ * 2), z1 = Math.max(z0 + 1, Math.round(maxZ * 2));
            String attrs = String.format(Locale.ROOT, "%s L%.0f A%.0f layer%.0f", axis, l[q * 4], a[q * 4], layer[q * 4]);
            boolean yFace = axis.endsWith("y");
            boolean xFace = axis.endsWith("x");
            int ua = xFace ? z0 : x0, ub = xFace ? z1 : x1;   // first in-plane axis
            int va = yFace ? z0 : y0, vb = yFace ? z1 : y1;   // second in-plane axis
            int plane = yFace ? y0 : xFace ? x0 : z0;
            for (int u = ua; u < ub; u++) {
                for (int v = va; v < vb; v++) {
                    String key = attrs + " plane" + plane + " " + u + "," + v;
                    out.merge(key, 1, Integer::sum);
                }
            }
        }
        return out;
    }

    /**
     * Per quad matched by its box, averaged normals agree within 0.02. Merged flat
     * rectangles have no per-cell twin; for those every reference cell they cover
     * must have been flat (up normal), which is exactly the merge precondition.
     */
    private static void assertNormalsMatch(MmsMeshData ref, MmsMeshData pulled, FastLodLevel level) {
        java.util.Map<String, float[]> refN = quadNormals(ref);
        java.util.Map<String, float[]> pulN = quadNormals(pulled);
        for (var e : pulN.entrySet()) {
            float[] r = refN.get(e.getKey());
            if (r == null) {
                // merged rectangle: its uniform normal must equal the normal of every
                // reference cell quad it covers (the merge precondition)
                float[] pn = e.getValue();
                for (var rq : refN.entrySet()) {
                    if (covers(e.getKey(), rq.getKey())) {
                        for (int c = 0; c < 3; c++) {
                            assertEquals(rq.getValue()[c], pn[c], 0.02f,
                                level + " merged quad " + e.getKey() + " vs cell " + rq.getKey());
                        }
                    }
                }
                continue;
            }
            for (int c = 0; c < 3; c++) {
                assertEquals(r[c], e.getValue()[c], 0.02f, level + " normal[" + c + "] of " + e.getKey());
            }
        }
    }

    /** Box containment on the "[a,b,c]-[d,e,f]" signature. */
    private static boolean covers(String outer, String inner) {
        float[] o = box(outer), i = box(inner);
        return i[0] >= o[0] - 1e-3f && i[1] >= o[1] - 1e-3f && i[2] >= o[2] - 1e-3f
            && i[3] <= o[3] + 1e-3f && i[4] <= o[4] + 1e-3f && i[5] <= o[5] + 1e-3f;
    }

    private static float[] box(String sig) {
        String[] parts = sig.replace("[", "").replace("]", "").split("[-,]");
        // "-" also appears inside negative numbers; parse robustly by rejoining
        java.util.List<Float> vals = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int k = 0; k < sig.length(); k++) {
            char ch = sig.charAt(k);
            if (ch == '[' || ch == ']') {
                continue;
            }
            if (ch == ',' || (ch == '-' && cur.length() > 0 && cur.charAt(cur.length() - 1) != 'e')) {
                if (cur.length() > 0) {
                    vals.add(Float.parseFloat(cur.toString()));
                    cur.setLength(0);
                }
                if (ch == '-') {
                    cur.append(ch);
                }
                continue;
            }
            cur.append(ch);
        }
        if (cur.length() > 0) {
            vals.add(Float.parseFloat(cur.toString()));
        }
        float[] out = new float[6];
        for (int k = 0; k < 6; k++) {
            out[k] = vals.get(k);
        }
        return out;
    }

    private static java.util.Map<String, float[]> quadNormals(MmsMeshData mesh) {
        float[] p = mesh.getVertexPositions();
        float[] n = mesh.getVertexNormals();
        java.util.Map<String, float[]> out = new java.util.HashMap<>();
        for (int q = 0; q < mesh.getVertexCount() / 4; q++) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            float[] avg = new float[3];
            for (int c = 0; c < 4; c++) {
                int v = q * 4 + c;
                minX = Math.min(minX, p[v * 3]); maxX = Math.max(maxX, p[v * 3]);
                minY = Math.min(minY, p[v * 3 + 1]); maxY = Math.max(maxY, p[v * 3 + 1]);
                minZ = Math.min(minZ, p[v * 3 + 2]); maxZ = Math.max(maxZ, p[v * 3 + 2]);
                for (int k = 0; k < 3; k++) {
                    avg[k] += n[v * 3 + k] / 4f;
                }
            }
            out.put(String.format(Locale.ROOT, "[%.1f,%.1f,%.1f]-[%.1f,%.1f,%.1f]", minX, minY, minZ, maxX, maxY, maxZ), avg);
        }
        return out;
    }

    private static FastLodChunkData data(FastLodLevel level, int chunkX, int chunkZ) {
        int stride = level.stride();
        int[] heights = new int[stride * stride];
        BlockType[] surface = new BlockType[level.cellCount()];
        for (int x = 0; x < stride; x++) {
            for (int z = 0; z < stride; z++) {
                // West half: a ridge with a submerged corner (varied skirts, smooth normals,
                // one water cell at L4). East half: a flat plateau (merge candidates).
                int h = x >= stride / 2 ? 72
                    : 60 + x * 3 + (z % 2 == 0 ? 2 : 0) - (x == 0 && z == 0 ? 8 : 0);
                heights[x * stride + z] = h;
            }
        }
        java.util.Arrays.fill(surface, BlockType.GRASS);
        TreeSample[] trees = null;
        if (level.emitsTrees()) {
            trees = new TreeSample[level.cellCount()];
            trees[5] = new TreeSample(TreeKind.values()[0], 5);
        }
        return new FastLodChunkData(FastLodKey.of(level, chunkX, chunkZ), heights, surface, trees);
    }

    @Test
    void pulledLodQuadsMatchThePerVertexWriter() {
        for (FastLodLevel level : new FastLodLevel[]{FastLodLevel.L4, FastLodLevel.L2, FastLodLevel.L0}) {
            FastLodChunkData d = data(level, 17, -9);

            MmsVertexFormat.override(MmsVertexFormat.LEGACY40);
            FastLodMesher.Result ref = mesher().build(d);
            MmsVertexFormat.override(MmsVertexFormat.QUAD16);
            FastLodMesher.Result pulled = mesher().build(d);

            assertEquals(MmsVertexFormat.LODQUAD16, pulled.mesh().getFormat(), level + " terrain is pulled");
            assertTrue(pulled.mesh().getVertexCount() <= ref.mesh().getVertexCount(),
                level + " merging never adds quads");
            java.util.Map<String, Integer> expected = tiles(ref.mesh());
            java.util.Map<String, Integer> actual = tiles(pulled.mesh());
            assertEquals(expected, actual, level + " covered tiles");
            assertNormalsMatch(ref.mesh(), pulled.mesh(), level);
            System.out.printf("[lod-parity] %s: %d quads per-cell -> %d merged%n", level,
                ref.mesh().getVertexCount() / 4, pulled.mesh().getVertexCount() / 4);
            assertEquals(ref.minY(), pulled.minY(), 0.5f, level + " minY");
            assertEquals(ref.maxY(), pulled.maxY(), 0.5f, level + " maxY");
            assertEquals(ref.waterMesh() == null, pulled.waterMesh() == null, level + " water presence");
        }
    }
}
