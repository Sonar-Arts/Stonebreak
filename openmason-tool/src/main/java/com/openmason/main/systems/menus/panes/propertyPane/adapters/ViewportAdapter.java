package com.openmason.main.systems.menus.panes.propertyPane.adapters;

import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IViewportConnector;
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
    public void reloadBlockModel(BlockModel blockModel) {
        if (viewport != null && blockModel != null) {
            viewport.loadBlockModel(blockModel);
            logger.debug("Reloaded BlockModel in viewport: {}", blockModel.getName());
        }
    }

    @Override
    public void updateBlockModelTexture(BlockModel blockModel) {
        if (viewport != null && blockModel != null) {
            viewport.updateBlockModelTexture(blockModel);
            logger.debug("Updated BlockModel texture in viewport: {}", blockModel.getName());
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
}
