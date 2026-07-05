package com.openmason.main.systems.scripting.doc;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;

/**
 * A model document with no GL context: a {@link ModelPartManager} whose
 * combined-mesh rebuilds are captured in memory instead of uploaded to the
 * GPU.
 *
 * <p>Face-id remaps produced by part add/remove are applied to the
 * {@link FaceTextureManager} exactly as the live pipeline does (see
 * {@code PartMeshBridge}); the latest combined buffers are kept for
 * {@link #extractMeshData()}.
 */
public final class HeadlessModelDocument implements ModelDocument {

    private final ModelPartManager partManager = new ModelPartManager();
    private final FaceTextureManager faceTextureManager = new FaceTextureManager();

    private PartMeshRebuilder.RebuildResult lastRebuild;

    public HeadlessModelDocument() {
        partManager.setMeshConsumer(this::onPartMeshRebuilt);
    }

    private void onPartMeshRebuilt(PartMeshRebuilder.RebuildResult result) {
        if (result.faceIdRemap() != null && !result.faceIdRemap().isEmpty()) {
            faceTextureManager.remapFaceIds(result.faceIdRemap());
        }
        this.lastRebuild = result;
    }

    @Override
    public ModelPartManager parts() {
        return partManager;
    }

    @Override
    public FaceTextureManager faceTextures() {
        return faceTextureManager;
    }

    @Override
    public OMOFormat.MeshData extractMeshData() {
        PartMeshRebuilder.RebuildResult r = lastRebuild;
        if (r == null || r.totalVertexCount() == 0) {
            return null;
        }
        return new OMOFormat.MeshData(
                r.combinedVertices(),
                r.combinedTexCoords(),
                r.combinedIndices(),
                r.triangleToFaceId(),
                "FLAT");
    }
}
