package com.openmason.engine.rendering.model.gmr.core;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.IModelStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges part-level rebuilds from {@code ModelPartManager} into the
 * serialization/render pipeline.
 *
 * <p>GL-free: extracted from {@code GenericModelRenderer.onPartMeshRebuilt} so
 * headless model documents can wire the identical part→pipeline path without a
 * renderer. Register via
 * {@code partManager.setMeshConsumer(bridge::onPartMeshRebuilt)}.
 */
public final class PartMeshBridge {

    private static final Logger logger = LoggerFactory.getLogger(PartMeshBridge.class);

    private final MeshRebuildPipeline rebuildPipeline;
    private final FaceTextureManager faceTextureManager;
    private final MeshSerializationAdapter serializationAdapter;
    private final IModelStateManager stateManager;

    public PartMeshBridge(MeshRebuildPipeline rebuildPipeline,
                          FaceTextureManager faceTextureManager,
                          MeshSerializationAdapter serializationAdapter,
                          IModelStateManager stateManager) {
        this.rebuildPipeline = rebuildPipeline;
        this.faceTextureManager = faceTextureManager;
        this.serializationAdapter = serializationAdapter;
        this.stateManager = stateManager;
    }

    /**
     * Callback for {@code ModelPartManager}'s mesh consumer: remaps face
     * texture ids when parts shifted, then feeds the rebuilt combined buffers
     * through the geometry-only pipeline path (preserving face texture
     * mappings — critical when parts are added/moved/removed).
     */
    public void onPartMeshRebuilt(PartMeshRebuilder.RebuildResult result) {
        // When all parts are hidden, clear the rendered mesh instead of keeping stale geometry
        if (result.totalVertexCount() == 0) {
            logger.debug("Part mesh rebuild produced empty result — clearing mesh");
            rebuildPipeline.setEditableMesh(new EditableMesh());
            rebuildPipeline.rebuildFromEditable();
            return;
        }

        // Remap face texture mappings if face IDs shifted (e.g. after part deletion)
        if (result.faceIdRemap() != null && !result.faceIdRemap().isEmpty()) {
            faceTextureManager.remapFaceIds(result.faceIdRemap());
            logger.debug("Remapped {} face texture IDs after part change", result.faceIdRemap().size());
        }

        // Wrap the rebuilt data as MeshData and feed through the geometry-only path.
        // Uses updateMeshGeometry() instead of loadMeshData() to preserve existing
        // face texture mappings.
        OMOFormat.MeshData meshData = new OMOFormat.MeshData(
                result.combinedVertices(),
                result.combinedTexCoords(),
                result.combinedIndices(),
                result.triangleToFaceId(),
                stateManager.getUVMode() != null ? stateManager.getUVMode().name() : "FLAT"
        );

        serializationAdapter.updateMeshGeometry(meshData);
        logger.debug("Part mesh rebuild pushed to pipeline: {} vertices, {} indices",
                result.totalVertexCount(), result.totalIndexCount());
    }
}
