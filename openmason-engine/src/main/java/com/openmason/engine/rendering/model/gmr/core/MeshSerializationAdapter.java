package com.openmason.engine.rendering.model.gmr.core;

import com.openmason.engine.rendering.model.UVMode;
import com.openmason.engine.rendering.model.gmr.mapping.ITriangleFaceMapper;
import com.openmason.engine.rendering.model.gmr.uv.*;
import com.openmason.engine.format.omo.OMOFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Handles mesh serialization: OMO file loading/saving and undo/redo snapshot restore.
 * Each operation sets data on shared subsystems then triggers the rebuild pipeline.
 *
 * Extracted from GenericModelRenderer to satisfy Single Responsibility.
 */
public class MeshSerializationAdapter implements IMeshSerializationAdapter {

    private static final Logger logger = LoggerFactory.getLogger(MeshSerializationAdapter.class);

    private final IVertexDataManager vertexManager;
    private final ITriangleFaceMapper faceMapper;
    private final FaceTextureManager faceTextureManager;
    private final IModelStateManager stateManager;
    private final MeshRebuildPipeline rebuildPipeline;
    private final ITextureGPUOperations textureOps;

    // Callback for renderer state
    private final MeshMutationCoordinator.RendererStateAccess rendererState;

    public MeshSerializationAdapter(
            IVertexDataManager vertexManager,
            ITriangleFaceMapper faceMapper,
            FaceTextureManager faceTextureManager,
            IModelStateManager stateManager,
            MeshRebuildPipeline rebuildPipeline,
            ITextureGPUOperations textureOps,
            MeshMutationCoordinator.RendererStateAccess rendererState) {
        this.vertexManager = vertexManager;
        this.faceMapper = faceMapper;
        this.faceTextureManager = faceTextureManager;
        this.stateManager = stateManager;
        this.rebuildPipeline = rebuildPipeline;
        this.textureOps = textureOps;
        this.rendererState = rendererState;
    }

    @Override
    public void loadMeshData(OMOFormat.MeshData meshData) {
        if (meshData == null || !meshData.hasCustomGeometry()) {
            logger.debug("No custom mesh data to load");
            return;
        }

        // Clear stale face texture data from any previously loaded model
        faceTextureManager.clear();

        float[] vertices = meshData.vertices();
        float[] texCoords = meshData.texCoords();
        int[] indices = meshData.indices();
        int[] triangleToFaceId = meshData.triangleToFaceId();
        String uvModeStr = meshData.uvMode();

        // Update UV mode
        if (uvModeStr != null) {
            try {
                stateManager.setUVMode(UVMode.valueOf(uvModeStr));
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown UV mode '{}', defaulting to FLAT", uvModeStr);
                stateManager.setUVMode(UVMode.FLAT);
            }
        }

        // Clear parts (we're loading direct mesh data, not part-based)
        stateManager.clearParts();

        // Set vertex data
        vertexManager.setData(vertices.clone(), texCoords != null ? texCoords.clone() : null,
            indices != null ? indices.clone() : null);

        // Update counts
        int newVertexCount = vertices.length / 3;
        int newIndexCount = indices != null ? indices.length : 0;
        rendererState.setVertexCount(newVertexCount);
        rendererState.setIndexCount(newIndexCount);

        // Restore face mapping
        if (triangleToFaceId != null && triangleToFaceId.length > 0) {
            faceMapper.setMapping(triangleToFaceId.clone());
        } else if (indices != null) {
            faceMapper.initializeStandardMapping(indices.length / 3);
        } else {
            faceMapper.clear();
        }

        // Trigger full rebuild
        rebuildPipeline.rebuildFull(newVertexCount, newIndexCount);

        logger.info("Loaded custom mesh data: {} vertices, {} triangles, uvMode={}",
                newVertexCount, newIndexCount / 3, stateManager.getUVMode());
    }

    @Override
    public void updateMeshGeometry(OMOFormat.MeshData meshData) {
        if (meshData == null || !meshData.hasCustomGeometry()) {
            logger.debug("No custom mesh data for geometry update");
            return;
        }

        float[] vertices = meshData.vertices();
        float[] texCoords = meshData.texCoords();
        int[] indices = meshData.indices();
        int[] triangleToFaceId = meshData.triangleToFaceId();
        String uvModeStr = meshData.uvMode();

        // Update UV mode
        if (uvModeStr != null) {
            try {
                stateManager.setUVMode(UVMode.valueOf(uvModeStr));
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown UV mode '{}', defaulting to FLAT", uvModeStr);
                stateManager.setUVMode(UVMode.FLAT);
            }
        }

        // Clear parts (we're loading direct mesh data)
        stateManager.clearParts();

        // Set vertex data
        vertexManager.setData(vertices.clone(), texCoords != null ? texCoords.clone() : null,
            indices != null ? indices.clone() : null);

        // Update counts
        int newVertexCount = vertices.length / 3;
        int newIndexCount = indices != null ? indices.length : 0;
        rendererState.setVertexCount(newVertexCount);
        rendererState.setIndexCount(newIndexCount);

        // Restore face mapping
        if (triangleToFaceId != null && triangleToFaceId.length > 0) {
            faceMapper.setMapping(triangleToFaceId.clone());
        } else if (indices != null) {
            faceMapper.initializeStandardMapping(indices.length / 3);
        } else {
            faceMapper.clear();
        }

        // NOTE: faceTextureManager is NOT cleared — existing mappings are preserved
        // This is the key difference from loadMeshData()

        // Trigger full rebuild
        rebuildPipeline.rebuildFull(newVertexCount, newIndexCount);

        logger.debug("Updated mesh geometry (face textures preserved): {} vertices, {} triangles",
                newVertexCount, newIndexCount / 3);
    }

    @Override
    public OMOFormat.MeshData toMeshData() {
        float[] vertices = vertexManager.getVertices();
        if (vertices == null || vertices.length == 0) {
            logger.warn("No vertex data available to snapshot");
            return null;
        }

        float[] texCoords = vertexManager.getTexCoords();
        int[] indices = vertexManager.getIndices();
        int[] triangleToFaceId = faceMapper.getMappingCopy();
        String uvModeStr = stateManager.getUVMode() != null ? stateManager.getUVMode().name() : "FLAT";

        logger.debug("Created mesh data snapshot: {} vertices, {} indices, uvMode={}",
                vertices.length / 3,
                indices != null ? indices.length : 0,
                uvModeStr);

        return new OMOFormat.MeshData(
                vertices.clone(),
                texCoords != null ? texCoords.clone() : null,
                indices != null ? indices.clone() : null,
                triangleToFaceId,
                uvModeStr);
    }

    @Override
    public void restoreFromSnapshot(float[] vertices, float[] texCoords, int[] indices,
                                     int[] triangleToFaceId,
                                     Map<Integer, FaceTextureMapping> faceMappings,
                                     Map<Integer, MaterialDefinition> materials) {
        // Set vertex data
        vertexManager.setData(
            vertices != null ? vertices.clone() : null,
            texCoords != null ? texCoords.clone() : null,
            indices != null ? indices.clone() : null
        );

        // Update counts
        int newVertexCount = vertices != null ? vertices.length / 3 : 0;
        int newIndexCount = indices != null ? indices.length : 0;
        rendererState.setVertexCount(newVertexCount);
        rendererState.setIndexCount(newIndexCount);

        // Restore face mapping
        if (triangleToFaceId != null && triangleToFaceId.length > 0) {
            faceMapper.setMapping(triangleToFaceId.clone());
        } else {
            faceMapper.clear();
        }

        // Restore face texture state
        faceTextureManager.clear();
        if (materials != null) {
            for (MaterialDefinition mat : materials.values()) {
                if (mat.materialId() != MaterialDefinition.DEFAULT.materialId()) {
                    faceTextureManager.registerMaterial(mat);
                }
            }
        }
        if (faceMappings != null) {
            for (FaceTextureMapping mapping : faceMappings.values()) {
                faceTextureManager.setFaceMapping(mapping);
            }
        }

        // Trigger full rebuild
        rebuildPipeline.rebuildFull(newVertexCount, newIndexCount);

        logger.debug("Restored from snapshot: {} vertices, {} triangles",
            newVertexCount, newIndexCount / 3);
    }

    @Override
    public void refreshUVs() {
        textureOps.regenerateUVsAndUpload();
        rebuildPipeline.markDrawBatchesDirty();
    }
}
