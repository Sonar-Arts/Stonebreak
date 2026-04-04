package com.openmason.main.systems.menus.panes.propertyPane.adapters;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.engine.rendering.model.gmr.extraction.GMRFaceExtractor;
import com.openmason.engine.rendering.model.gmr.uv.FaceProjectionUtil;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureSizer;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IViewportConnector;
import com.openmason.main.systems.services.commands.FaceTextureCommand;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import com.openmason.main.systems.ViewportController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter that wraps OpenMason3DViewport to implement IViewportConnector interface.
 * Follows Adapter pattern and Dependency Inversion principle.
 */
public class ViewportAdapter implements IViewportConnector {

    private static final Logger logger = LoggerFactory.getLogger(ViewportAdapter.class);

    private final ViewportController viewport;

    /**
     * Create a viewport adapter.
     *
     * @param viewport The viewport to wrap (can be null)
     */
    public ViewportAdapter(ViewportController viewport) {
        this.viewport = viewport;
    }

    @Override
    public boolean isConnected() {
        return viewport != null;
    }

    @Override
    public void setTextureVariant(String variantName) {
        if (viewport != null) {
            viewport.setCurrentTextureVariant(variantName);
        }
    }

    @Override
    public void reloadModel(BlockModel blockModel) {
        if (viewport != null && blockModel != null) {
            viewport.loadModel(blockModel);
            logger.debug("Reloaded model in viewport: {}", blockModel.getName());
        }
    }

    @Override
    public void updateModelTexture(BlockModel blockModel) {
        if (viewport != null && blockModel != null) {
            viewport.updateModelTexture(blockModel);
            logger.debug("Updated model texture in viewport: {}", blockModel.getName());
        }
    }

    @Override
    public float getMinScale() {
        return viewport != null ? viewport.getMinScale() : 0.1f;
    }

    @Override
    public float getMaxScale() {
        return viewport != null ? viewport.getMaxScale() : 3.0f;
    }

    @Override
    public float getModelPositionX() {
        return viewport != null ? viewport.getModelPositionX() : 0.0f;
    }

    @Override
    public float getModelPositionY() {
        return viewport != null ? viewport.getModelPositionY() : 0.0f;
    }

    @Override
    public float getModelPositionZ() {
        return viewport != null ? viewport.getModelPositionZ() : 0.0f;
    }

    @Override
    public float getModelRotationX() {
        return viewport != null ? viewport.getModelRotationX() : 0.0f;
    }

    @Override
    public float getModelRotationY() {
        return viewport != null ? viewport.getModelRotationY() : 0.0f;
    }

    @Override
    public float getModelRotationZ() {
        return viewport != null ? viewport.getModelRotationZ() : 0.0f;
    }

    @Override
    public float getModelScaleX() {
        return viewport != null ? viewport.getModelScaleX() : 1.0f;
    }

    @Override
    public float getModelScaleY() {
        return viewport != null ? viewport.getModelScaleY() : 1.0f;
    }

    @Override
    public float getModelScaleZ() {
        return viewport != null ? viewport.getModelScaleZ() : 1.0f;
    }

    @Override
    public boolean getGizmoUniformScaling() {
        return viewport != null && viewport.getGizmoUniformScaling();
    }

    @Override
    public void setModelTransform(float posX, float posY, float posZ,
                                   float rotX, float rotY, float rotZ,
                                   float sclX, float sclY, float sclZ) {
        if (viewport != null) {
            viewport.setModelTransform(posX, posY, posZ, rotX, rotY, rotZ, sclX, sclY, sclZ);
        }
    }

    @Override
    public void setGizmoUniformScaling(boolean uniform) {
        if (viewport != null) {
            viewport.setGizmoUniformScaling(uniform);
        }
    }

    @Override
    public void resetModelTransform() {
        if (viewport != null) {
            viewport.resetModelTransform();
        }
    }

    @Override
    public FaceSelectionState getFaceSelectionState() {
        return viewport != null ? viewport.getFaceSelectionState() : null;
    }

    @Override
    public FaceTextureManager getFaceTextureManager() {
        if (viewport != null) {
            var renderer = viewport.getModelRenderer();
            return renderer != null ? renderer.getFaceTextureManager() : null;
        }
        return null;
    }

    @Override
    public void setFaceTexture(int faceId, int materialId) {
        if (viewport == null) {
            return;
        }
        GenericModelRenderer renderer = viewport.getModelRenderer();
        if (renderer == null) {
            return;
        }

        // Capture old mapping before mutation
        FaceTextureManager ftm = renderer.getFaceTextureManager();
        FaceTextureMapping oldMapping = (ftm != null) ? ftm.getFaceMapping(faceId) : null;

        // Apply mutation
        renderer.setFaceMaterial(faceId, materialId);

        // Capture new mapping and record undo command
        ModelCommandHistory history = viewport.getCommandHistory();
        if (ftm != null && history != null) {
            FaceTextureMapping newMapping = ftm.getFaceMapping(faceId);
            if (newMapping != null) {
                history.pushCompleted(new FaceTextureCommand(
                    faceId, oldMapping, newMapping, ftm, renderer));
            }
        }
    }

    @Override
    public boolean isInFaceEditMode() {
        return EditModeManager.getInstance().isFaceEditingAllowed();
    }

    @Override
    public void setEditingFaceIndex(int faceIndex) {
        if (viewport != null) {
            viewport.setEditingFaceIndex(faceIndex);
        }
    }

    @Override
    public byte[] readTexturePixels(int gpuTextureId) {
        if (viewport != null) {
            var renderer = viewport.getModelRenderer();
            if (renderer != null) {
                return renderer.readTexturePixels(gpuTextureId);
            }
        }
        return null;
    }

    @Override
    public int[] getTextureDimensions(int gpuTextureId) {
        if (viewport != null) {
            var renderer = viewport.getModelRenderer();
            if (renderer != null) {
                return renderer.getTextureDimensions(gpuTextureId);
            }
        }
        return null;
    }

    @Override
    public int[] computeFaceTextureDimensions(int faceId, int pixelsPerUnit) {
        if (viewport == null) {
            return null;
        }
        GenericModelRenderer renderer = viewport.getModelRenderer();
        if (renderer == null) {
            return null;
        }

        GMRFaceExtractor.FaceExtractionResult faceData = renderer.extractFaceData();
        if (faceData == null) {
            return null;
        }

        FaceTextureSizer.FaceTextureDimensions dims =
            FaceTextureSizer.computeForFace(faceData, faceId, pixelsPerUnit);
        if (dims == null) {
            return null;
        }

        return new int[]{dims.width(), dims.height()};
    }

    @Override
    public float[][] computeFacePolygon2D(int faceId) {
        if (viewport == null) {
            return null;
        }
        GenericModelRenderer renderer = viewport.getModelRenderer();
        if (renderer == null) {
            return null;
        }

        // Prefer UV-based extraction: reads actual UV coordinates written by the
        // per-face UV generator, guaranteeing the polygon mask exactly matches the
        // texture mapping. Falls back to 3D projection for faces without a mapping.
        float[][] uvPolygon = renderer.extractFacePolygonFromUVs(faceId);
        if (uvPolygon != null) {
            return uvPolygon;
        }

        // Fallback: project 3D positions to 2D (for faces without UV mappings)
        GMRFaceExtractor.FaceExtractionResult faceData = renderer.extractFaceData();
        if (faceData == null || faceId < 0 || faceId >= faceData.faceCount()) {
            return null;
        }

        int startFloat = faceData.faceOffsets()[faceId];
        int endFloat = faceData.faceOffsets()[faceId + 1];
        int vertexCount = faceData.verticesPerFace()[faceId];

        if (vertexCount < 3 || endFloat - startFloat < vertexCount * 3) {
            return null;
        }

        int floatCount = vertexCount * 3;
        float[] facePositions = new float[floatCount];
        System.arraycopy(faceData.positions(), startFloat, facePositions, 0, floatCount);

        return FaceProjectionUtil.projectFaceToLocalSpace(facePositions, vertexCount);
    }
}
