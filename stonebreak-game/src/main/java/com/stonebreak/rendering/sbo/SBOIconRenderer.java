package com.stonebreak.rendering.sbo;

import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor;
import com.openmason.engine.voxel.sbo.SBONormalComputer;
import com.openmason.engine.voxel.sbo.SBORenderData;
import com.stonebreak.blocks.BlockType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Renders SBO block models for UI elements (hotbar icons, block drops).
 *
 * <p>Maintains a cached GPU mesh per SBO block type, with triangles sorted
 * by face for per-face texture binding. Singleton initialized once from
 * the SBO mesh processor.
 */
public class SBOIconRenderer {

    private static final Logger logger = LoggerFactory.getLogger(SBOIconRenderer.class);
    private static SBOIconRenderer instance;

    private final Map<BlockType, SBORenderData> renderDataMap = new EnumMap<>(BlockType.class);
    private SBOMeshProcessor pendingProcessor;
    private boolean gpuReady = false;

    private SBOIconRenderer() {}

    /**
     * Stage the mesh processor for deferred GPU upload.
     * Call this during Renderer init (before MmsAPI is ready).
     */
    public static void initialize(SBOMeshProcessor meshProcessor) {
        instance = new SBOIconRenderer();
        instance.pendingProcessor = meshProcessor;
        logger.info("SBOIconRenderer staged with {} SBO block types (GPU upload deferred)", meshProcessor.size());
    }

    /**
     * Upload icon meshes to GPU. Call after MmsAPI is initialized.
     */
    public static void uploadToGPU() {
        if (instance == null || instance.pendingProcessor == null || instance.gpuReady) return;

        SBOMeshProcessor meshProcessor = instance.pendingProcessor;
        for (BlockType blockType : BlockType.values()) {
            if (!meshProcessor.hasMesh(blockType)) continue;

            SBONormalComputer.ProcessedMesh mesh = meshProcessor.getMesh(blockType);
            if (mesh == null) continue;

            int[] faceIds = meshProcessor.getTriangleToFaceId(blockType);
            var faceTextures = meshProcessor.getFaceTextures(blockType);

            SBORenderData data = buildSortedMesh(mesh, faceIds, faceTextures);
            if (data != null) {
                instance.renderDataMap.put(blockType, data);
                logger.info("SBO icon mesh uploaded for {}", blockType);
            }
        }

        instance.gpuReady = true;
        instance.pendingProcessor = null;
        logger.info("SBOIconRenderer GPU upload complete: {} block types", instance.renderDataMap.size());
    }

    /**
     * Check if a block type has an SBO model for icon rendering.
     */
    public static boolean hasSBOModel(BlockType blockType) {
        return instance != null && instance.renderDataMap.containsKey(blockType);
    }

    /**
     * Render the SBO model for a block type with per-face textures.
     */
    public static void render(BlockType blockType) {
        if (instance == null) return;
        SBORenderData data = instance.renderDataMap.get(blockType);
        if (data != null) {
            data.render();
        }
    }

    /**
     * Build a GPU mesh with triangles sorted by face, and per-face render batches.
     */
    private static SBORenderData buildSortedMesh(SBONormalComputer.ProcessedMesh mesh,
                                                  int[] faceIds,
                                                  Map<Integer, Integer> faceTextures) {
        float[] srcVerts = mesh.vertices();
        float[] srcNorms = mesh.normals();
        float[] srcUVs = mesh.texCoords();
        int triangleCount = mesh.triangleCount();

        // Build sorted vertex/index arrays by face
        List<Float> positions = new ArrayList<>();
        List<Float> texCoords = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();

        SBORenderData.FaceBatch[] batches = new SBORenderData.FaceBatch[6];
        int vertexIndex = 0;

        for (int face = 0; face < 6; face++) {
            int indexOffset = indices.size();
            int count = 0;

            for (int tri = 0; tri < triangleCount; tri++) {
                int fid = (faceIds != null && tri < faceIds.length) ? faceIds[tri] : 0;
                if (fid < 0 || fid >= 6) fid = 0;
                if (fid != face) continue;

                // Add 3 vertices for this triangle
                for (int v = 0; v < 3; v++) {
                    int i = tri * 3 + v;
                    int vOff = i * 3;
                    int tOff = i * 2;

                    positions.add(srcVerts[vOff]);
                    positions.add(srcVerts[vOff + 1]);
                    positions.add(srcVerts[vOff + 2]);

                    texCoords.add(srcUVs[tOff]);
                    texCoords.add(srcUVs[tOff + 1]);

                    normals.add(srcNorms[vOff]);
                    normals.add(srcNorms[vOff + 1]);
                    normals.add(srcNorms[vOff + 2]);

                    indices.add(vertexIndex++);
                }
                count += 3;
            }

            int texId = faceTextures.getOrDefault(face, 0);
            batches[face] = new SBORenderData.FaceBatch(texId, indexOffset, count);
        }

        if (indices.isEmpty()) return null;

        // Convert to arrays
        int vertCount = vertexIndex;
        float[] posArr = toFloatArray(positions);
        float[] texArr = toFloatArray(texCoords);
        float[] normArr = toFloatArray(normals);
        float[] waterArr = new float[vertCount];
        float[] alphaArr = new float[vertCount];
        int[] idxArr = indices.stream().mapToInt(Integer::intValue).toArray();

        MmsMeshData meshData = new MmsMeshData(posArr, texArr, normArr, waterArr, alphaArr, idxArr, idxArr.length);
        MmsRenderableHandle handle = com.stonebreak.world.chunk.api.mightyMesh.MmsAPI.getInstance().uploadMeshToGPU(meshData);

        return new SBORenderData(handle, batches);
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    public static void cleanup() {
        if (instance != null) {
            for (SBORenderData data : instance.renderDataMap.values()) {
                data.close();
            }
            instance.renderDataMap.clear();
            instance = null;
        }
    }
}
