package com.openmason.main.systems.menus.panes.propertyPane.interfaces;

import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.main.systems.viewport.state.FaceSelectionState;

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
     * Set the current texture variant in the viewport.
     *
     * @param variantName The variant name
     */
    void setTextureVariant(String variantName);

    /**
     * Reload a model in the viewport (full reload including geometry).
     *
     * @param blockModel The model to reload
     */
    void reloadModel(BlockModel blockModel);

    /**
     * Update only the texture for the current model without rebuilding geometry.
     * Use this when changing textures to preserve any vertex/geometry modifications.
     *
     * @param blockModel The model with updated texture path
     */
    void updateModelTexture(BlockModel blockModel);

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
     * Get the face selection state from the viewport.
     *
     * @return FaceSelectionState, or null if not connected
     */
    FaceSelectionState getFaceSelectionState();

    /**
     * Get the face texture manager from the viewport's model renderer.
     *
     * @return FaceTextureManager, or null if not connected
     */
    FaceTextureManager getFaceTextureManager();

    /**
     * Assign a material to a specific face via the model renderer.
     *
     * @param faceId     Face identifier
     * @param materialId Material to assign
     */
    void setFaceTexture(int faceId, int materialId);

    /**
     * Check if the viewport is currently in face edit mode.
     *
     * @return true if face editing is allowed
     */
    boolean isInFaceEditMode();

    /**
     * Set the face index currently being edited in the texture editor.
     * The overlay renderer will use an outline instead of a filled highlight for this face.
     *
     * @param faceIndex face being edited, or -1 to clear
     */
    void setEditingFaceIndex(int faceIndex);

    /**
     * Read RGBA pixel data from a GPU texture.
     *
     * @param gpuTextureId OpenGL texture ID to read from
     * @return RGBA byte array, or null if the texture could not be read
     */
    byte[] readTexturePixels(int gpuTextureId);

    /**
     * Get the width and height of a GPU texture.
     *
     * @param gpuTextureId OpenGL texture ID
     * @return int array {@code [width, height]}, or null if invalid
     */
    int[] getTextureDimensions(int gpuTextureId);

    /**
     * Compute texture pixel dimensions for a face based on its 3D geometry.
     * Projects the face vertices into tangent space to determine proportional
     * width and height at the given resolution.
     *
     * @param faceId        Face identifier
     * @param pixelsPerUnit Resolution in pixels per world unit
     * @return int array {@code [width, height]}, or {@code null} if geometry data is unavailable
     */
    int[] computeFaceTextureDimensions(int faceId, int pixelsPerUnit);

    /**
     * Compute the 2D polygon outline for a face, projected into normalized local space.
     *
     * <p>Projects the face's 3D vertices onto its tangent frame and normalizes
     * the result to [0, 1] in both axes. Used to create a
     * {@link com.openmason.main.systems.menus.textureCreator.canvas.PolygonShapeMask}
     * for per-face texture editing.
     *
     * @param faceId Face identifier
     * @return {@code float[2][]} where [0] is X coords and [1] is Y coords (both 0–1),
     *         or {@code null} if geometry data is unavailable
     */
    float[][] computeFacePolygon2D(int faceId);
}
