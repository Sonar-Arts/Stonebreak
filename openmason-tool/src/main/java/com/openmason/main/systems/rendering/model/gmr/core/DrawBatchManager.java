package com.openmason.main.systems.rendering.model.gmr.core;

import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.main.systems.rendering.model.gmr.uv.IVertexDataManager;
import com.openmason.main.systems.rendering.model.gmr.uv.MaterialDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.lwjgl.opengl.GL15.*;

/**
 * Manages per-material draw batches for multi-texture rendering.
 * Groups triangles by their material's texture ID and maintains
 * a sorted index array for efficient batched draw calls.
 *
 * Extracted from GenericModelRenderer to satisfy Single Responsibility.
 */
public class DrawBatchManager {

    private static final Logger logger = LoggerFactory.getLogger(DrawBatchManager.class);

    /**
     * A contiguous range of indices sharing the same texture.
     */
    public record MaterialDrawBatch(int textureId, int indexOffset, int indexCount) {}

    private final IVertexDataManager vertexManager;
    private final ITriangleFaceMapper faceMapper;
    private final FaceTextureManager faceTextureManager;

    private List<MaterialDrawBatch> drawBatches = List.of();

    public DrawBatchManager(
            IVertexDataManager vertexManager,
            ITriangleFaceMapper faceMapper,
            FaceTextureManager faceTextureManager) {
        this.vertexManager = vertexManager;
        this.faceMapper = faceMapper;
        this.faceTextureManager = faceTextureManager;
    }

    /**
     * Get the current draw batches.
     *
     * @return Immutable list of draw batches
     */
    public List<MaterialDrawBatch> getDrawBatches() {
        return drawBatches;
    }

    /**
     * Rebuild draw batches by grouping triangles by their material's texture ID.
     * Uploads a sorted index array to the provided EBO.
     *
     * @param ebo OpenGL EBO handle to upload sorted indices to
     */
    public void rebuildDrawBatches(int ebo) {
        int[] indices = vertexManager.getIndices();
        if (indices == null || indices.length == 0) {
            drawBatches = List.of();
            return;
        }

        int triangleCount = indices.length / 3;

        // Group triangle indices by material texture ID
        Map<Integer, List<int[]>> textureTriangles = new LinkedHashMap<>();

        for (int t = 0; t < triangleCount; t++) {
            int faceId = faceMapper.getOriginalFaceIdForTriangle(t);
            int texId = 0;

            if (faceId >= 0) {
                FaceTextureMapping mapping = faceTextureManager.getFaceMapping(faceId);
                if (mapping != null) {
                    MaterialDefinition material = faceTextureManager.getMaterial(mapping.materialId());
                    if (material != null && material.textureId() > 0) {
                        texId = material.textureId();
                    }
                }
            }

            int base = t * 3;
            textureTriangles.computeIfAbsent(texId, k -> new ArrayList<>())
                    .add(new int[]{indices[base], indices[base + 1], indices[base + 2]});
        }

        // Build sorted index array and batch descriptors
        int[] sortedIndices = new int[indices.length];
        List<MaterialDrawBatch> batches = new ArrayList<>();
        int offset = 0;

        for (Map.Entry<Integer, List<int[]>> entry : textureTriangles.entrySet()) {
            int texId = entry.getKey();
            List<int[]> triangles = entry.getValue();
            int batchIndexCount = triangles.size() * 3;

            for (int[] tri : triangles) {
                sortedIndices[offset] = tri[0];
                sortedIndices[offset + 1] = tri[1];
                sortedIndices[offset + 2] = tri[2];
                offset += 3;
            }

            batches.add(new MaterialDrawBatch(texId, offset - batchIndexCount, batchIndexCount));
        }

        // Upload sorted indices directly to the EBO while VAO is bound
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, sortedIndices, GL_DYNAMIC_DRAW);

        drawBatches = batches;

        logger.debug("Rebuilt draw batches: {} batch(es) from {} triangles",
                batches.size(), triangleCount);
    }
}
