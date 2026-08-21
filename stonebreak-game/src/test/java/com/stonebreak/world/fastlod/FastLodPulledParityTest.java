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

    /** One quad reduced to an order-independent signature (normals compared separately). */
    private static List<String> quads(MmsMeshData mesh) {
        return quads(mesh, true);
    }

    private static List<String> quads(MmsMeshData mesh, boolean sorted) {
        float[] p = mesh.getVertexPositions();
        float[] n = mesh.getVertexNormals();
        float[] l = mesh.getLightValues();
        float[] a = mesh.getAlphaTestFlags();
        float[] layer = mesh.getLayerIndices();
        List<String> out = new ArrayList<>();
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
            // Normal reduced to its dominant axis here; exact values are compared
            // with a tolerance in normalsMatch (octahedral 8+8-bit ≈ 0.005/component).
            String axis = Math.abs(nx) >= Math.abs(ny) && Math.abs(nx) >= Math.abs(nz) ? (nx > 0 ? "+x" : "-x")
                : Math.abs(ny) >= Math.abs(nz) ? (ny > 0 ? "+y" : "-y") : (nz > 0 ? "+z" : "-z");
            out.add(String.format(Locale.ROOT, "[%.1f,%.1f,%.1f]-[%.1f,%.1f,%.1f] n%s L%.0f A%.0f layer%.0f",
                minX, minY, minZ, maxX, maxY, maxZ, axis, l[q * 4], a[q * 4], layer[q * 4]));
        }
        if (sorted) {
            out.sort(Comparator.naturalOrder());
        }
        return out;
    }

    /** Per quad (matched by its box signature), averaged normals agree within 0.02. */
    private static void assertNormalsMatch(MmsMeshData ref, MmsMeshData pulled, FastLodLevel level) {
        java.util.Map<String, float[]> refN = quadNormals(ref);
        java.util.Map<String, float[]> pulN = quadNormals(pulled);
        for (var e : refN.entrySet()) {
            float[] p = pulN.get(e.getKey());
            if (p == null) {
                continue; // box mismatch already reported by the signature comparison
            }
            for (int c = 0; c < 3; c++) {
                assertEquals(e.getValue()[c], p[c], 0.02f, level + " normal[" + c + "] of " + e.getKey());
            }
        }
    }

    private static java.util.Map<String, float[]> quadNormals(MmsMeshData mesh) {
        List<String> sigs = quads(mesh, false);
        float[] n = mesh.getVertexNormals();
        java.util.Map<String, float[]> out = new java.util.HashMap<>();
        for (int q = 0; q < mesh.getVertexCount() / 4; q++) {
            float[] avg = new float[3];
            for (int c = 0; c < 4; c++) {
                for (int k = 0; k < 3; k++) {
                    avg[k] += n[(q * 4 + c) * 3 + k] / 4f;
                }
            }
            out.put(sigs.get(q), avg);
        }
        return out;
    }

    private static FastLodChunkData data(FastLodLevel level, int chunkX, int chunkZ) {
        int stride = level.stride();
        int[] heights = new int[stride * stride];
        BlockType[] surface = new BlockType[level.cellCount()];
        for (int x = 0; x < stride; x++) {
            for (int z = 0; z < stride; z++) {
                // A ridge with a submerged corner: varied skirts + one water cell at L4.
                int h = 60 + x * 3 + (z % 2 == 0 ? 2 : 0) - (x == 0 && z == 0 ? 8 : 0);
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
            assertEquals(ref.mesh().getVertexCount(), pulled.mesh().getVertexCount(), level + " quad count");
            List<String> expected = quads(ref.mesh());
            List<String> actual = quads(pulled.mesh());
            List<String> missing = new ArrayList<>(expected);
            missing.removeAll(actual);
            List<String> extra = new ArrayList<>(actual);
            extra.removeAll(expected);
            assertEquals(List.of(), missing, level + " quads missing from the pulled mesh (extra: " + extra + ")");
            assertEquals(List.of(), extra, level + " unexpected pulled quads");
            assertNormalsMatch(ref.mesh(), pulled.mesh(), level);
            assertEquals(ref.minY(), pulled.minY(), 0.5f, level + " minY");
            assertEquals(ref.maxY(), pulled.maxY(), 0.5f, level + " maxY");
            assertEquals(ref.waterMesh() == null, pulled.waterMesh() == null, level + " water presence");
        }
    }
}
