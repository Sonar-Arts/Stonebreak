package com.openmason.engine.rendering.model.gmr.core;

import com.openmason.engine.rendering.model.UVMode;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.MeshImporter;
import com.openmason.engine.rendering.model.gmr.editable.RenderMesh;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.engine.rendering.model.gmr.uv.IModelStateManager;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;
import com.openmason.engine.format.omo.OMOFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Handles mesh serialization: OMO file loading/saving and undo/redo snapshot
 * restore. Loading imports triangle soup into the authoritative
 * {@link EditableMesh} (position weld + polygon-loop reconstruction); saving
 * exports the derived {@link RenderMesh} arrays, which are always
 * self-consistent — the legacy seam-duplicate stripping is unnecessary by
 * construction.
 */
public class MeshSerializationAdapter implements IMeshSerializationAdapter {

    private static final Logger logger = LoggerFactory.getLogger(MeshSerializationAdapter.class);

    private final FaceTextureManager faceTextureManager;
    private final IModelStateManager stateManager;
    private final MeshRebuildPipeline rebuildPipeline;

    public MeshSerializationAdapter(
            FaceTextureManager faceTextureManager,
            IModelStateManager stateManager,
            MeshRebuildPipeline rebuildPipeline) {
        this.faceTextureManager = faceTextureManager;
        this.stateManager = stateManager;
        this.rebuildPipeline = rebuildPipeline;
    }

    @Override
    public void loadMeshData(OMOFormat.MeshData meshData) {
        if (meshData == null || !meshData.hasCustomGeometry()) {
            logger.debug("No custom mesh data to load");
            return;
        }

        // Clear stale face texture data from any previously loaded model
        faceTextureManager.clear();

        importAndRebuild(meshData);

        logger.info("Loaded custom mesh data: {} shared vertices, {} faces, uvMode={}",
                rebuildPipeline.getEditableMesh().vertexCount(),
                rebuildPipeline.getEditableMesh().faceCount(),
                stateManager.getUVMode());
    }

    @Override
    public void updateMeshGeometry(OMOFormat.MeshData meshData) {
        if (meshData == null || !meshData.hasCustomGeometry()) {
            logger.debug("No custom mesh data for geometry update");
            return;
        }

        // NOTE: faceTextureManager is NOT cleared — existing mappings are
        // preserved. This is the key difference from loadMeshData().
        importAndRebuild(meshData);

        logger.debug("Updated mesh geometry (face textures preserved): {} shared vertices, {} faces",
                rebuildPipeline.getEditableMesh().vertexCount(),
                rebuildPipeline.getEditableMesh().faceCount());
    }

    private void importAndRebuild(OMOFormat.MeshData meshData) {
        applyUvMode(meshData.uvMode());
        stateManager.clearParts();

        MeshImporter.ImportResult result = MeshImporter.importSoup(
            meshData.vertices(), meshData.texCoords(),
            meshData.indices(), meshData.triangleToFaceId());
        rebuildPipeline.setEditableMesh(result.mesh());
        rebuildPipeline.setImportSoupMapping(result.vertexIdToSoupIndices());
        rebuildPipeline.rebuildFromEditable();
    }

    private void applyUvMode(String uvModeStr) {
        if (uvModeStr == null) {
            return;
        }
        try {
            stateManager.setUVMode(UVMode.valueOf(uvModeStr));
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown UV mode '{}', defaulting to FLAT", uvModeStr);
            stateManager.setUVMode(UVMode.FLAT);
        }
    }

    @Override
    public OMOFormat.MeshData toMeshData() {
        RenderMesh rm = rebuildPipeline.getLastRenderMesh();
        if (rm == null || rm.cornerCount() == 0) {
            logger.warn("No mesh data available to snapshot");
            return null;
        }

        String uvModeStr = stateManager.getUVMode() != null ? stateManager.getUVMode().name() : "FLAT";

        logger.debug("Created mesh data snapshot: {} corners, {} indices, uvMode={}",
                rm.cornerCount(), rm.indices().length, uvModeStr);

        return new OMOFormat.MeshData(
                rm.vertices().clone(),
                rm.texCoords().clone(),
                rm.indices().clone(),
                rm.triangleToFaceId().clone(),
                uvModeStr);
    }

    @Override
    public void restoreFromSnapshot(float[] vertices, float[] texCoords, int[] indices,
                                     int[] triangleToFaceId,
                                     Map<Integer, FaceTextureMapping> faceMappings,
                                     Map<Integer, MaterialDefinition> materials) {
        // Restore face texture state first — the rebuild projects UVs from it.
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

        MeshImporter.ImportResult result = MeshImporter.importSoup(
            vertices, texCoords, indices, triangleToFaceId);
        rebuildPipeline.setEditableMesh(result.mesh());
        rebuildPipeline.setImportSoupMapping(result.vertexIdToSoupIndices());
        rebuildPipeline.rebuildFromEditable();

        logger.debug("Restored from snapshot: {} shared vertices, {} faces",
            result.mesh().vertexCount(), result.mesh().faceCount());
    }

    @Override
    public void refreshUVs() {
        // UVs are projected during render-mesh derivation.
        rebuildPipeline.rebuildFromEditable();
    }
}
