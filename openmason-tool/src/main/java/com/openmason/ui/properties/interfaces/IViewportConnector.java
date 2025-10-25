package com.openmason.ui.properties.interfaces;

/**
 * Interface for viewport connection abstraction following SOLID principles.
 * Provides an abstraction layer between property panel and 3D viewport,
 * allowing for dependency inversion and easier testing.
 */
public interface IViewportConnector {

    /**
     * Check if the viewport is connected and available.
     *
     * @return true if viewport is connected
     */
    boolean isConnected();

    /**
     * Load a model into the viewport.
     *
     * @param modelFileName The model file name to load
     */
    void loadModel(String modelFileName);

    /**
     * Get the currently loaded model name.
     *
     * @return The model name, or null if no model is loaded
     */
    String getCurrentModelName();

    /**
     * Check if the viewport has an actual model loaded.
     *
     * @return true if a model is loaded
     */
    boolean hasModel();

    /**
     * Set the current texture variant in the viewport.
     *
     * @param variantName The variant name
     */
    void setTextureVariant(String variantName);

    /**
     * Request a render update from the viewport.
     */
    void requestRender();

    /**
     * Get the minimum allowed scale value.
     *
     * @return Minimum scale
     */
    float getMinScale();

    /**
     * Get the maximum allowed scale value.
     *
     * @return Maximum scale
     */
    float getMaxScale();

    /**
     * Get model position X.
     */
    float getModelPositionX();

    /**
     * Get model position Y.
     */
    float getModelPositionY();

    /**
     * Get model position Z.
     */
    float getModelPositionZ();

    /**
     * Get model rotation X.
     */
    float getModelRotationX();

    /**
     * Get model rotation Y.
     */
    float getModelRotationY();

    /**
     * Get model rotation Z.
     */
    float getModelRotationZ();

    /**
     * Get model scale X.
     */
    float getModelScaleX();

    /**
     * Get model scale Y.
     */
    float getModelScaleY();

    /**
     * Get model scale Z.
     */
    float getModelScaleZ();

    /**
     * Get gizmo uniform scaling mode.
     */
    boolean getGizmoUniformScaling();

    /**
     * Set the model transform in the viewport.
     *
     * @param posX Position X
     * @param posY Position Y
     * @param posZ Position Z
     * @param rotX Rotation X
     * @param rotY Rotation Y
     * @param rotZ Rotation Z
     * @param sclX Scale X
     * @param sclY Scale Y
     * @param sclZ Scale Z
     */
    void setModelTransform(float posX, float posY, float posZ,
                           float rotX, float rotY, float rotZ,
                           float sclX, float sclY, float sclZ);

    /**
     * Set gizmo uniform scaling mode.
     *
     * @param uniform true for uniform scaling
     */
    void setGizmoUniformScaling(boolean uniform);

    /**
     * Reset model transform to defaults.
     */
    void resetModelTransform();

    /**
     * Get the model renderer (for diagnostics).
     *
     * @return The model renderer instance, or null if not available
     */
    Object getModelRenderer();
}
