package com.openmason.engine.rendering.model.gmr.uv;

/**
 * Interface for GPU texture I/O and UV management operations.
 * Handles texture read/write, UV regeneration, flood fill,
 * and vertex duplication at material boundaries.
 */
public interface ITextureGPUOperations {

    /**
     * Set the global texture for rendering.
     *
     * @param textureId OpenGL texture ID
     */
    void setTexture(int textureId);

    /**
     * Get the current global texture ID.
     *
     * @return OpenGL texture ID, or 0 if no texture is set
     */
    int getTextureId();

    /**
     * Check if a texture is currently active.
     *
     * @return true if a texture is set and active
     */
    boolean isTextureActive();

    /**
     * Update a sub-region of an existing GPU texture with new RGBA pixel data.
     *
     * @param targetTextureId OpenGL texture ID to update
     * @param x               left edge of the region (pixels)
     * @param y               top edge of the region (pixels)
     * @param width           width of the region (pixels)
     * @param height          height of the region (pixels)
     * @param rgbaBytes       pixel data in RGBA byte format
     */
    void updateTextureRegion(int targetTextureId, int x, int y,
                             int width, int height, byte[] rgbaBytes);

    /**
     * Read RGBA pixel data from a GPU texture via glGetTexImage.
     *
     * @param gpuTextureId OpenGL texture ID to read from
     * @return RGBA byte array, or null if the texture could not be read
     */
    byte[] readTexturePixels(int gpuTextureId);

    /**
     * Get the width and height of a GPU texture.
     *
     * @param gpuTextureId OpenGL texture ID
     * @return int array [width, height], or null if invalid
     */
    int[] getTextureDimensions(int gpuTextureId);

    /**
     * Assign a material to a specific face.
     *
     * @param faceId     Face identifier
     * @param materialId Material to assign
     */
    void setFaceMaterial(int faceId, int materialId);

    /**
     * Check if any face has a non-default material assigned.
     *
     * @return true if at least one face has a custom material
     */
    boolean hasCustomMaterials();

    /**
     * Regenerate UV coordinates for faces and upload to GPU.
     * Called by the rebuild pipeline after geometry changes.
     */
    void regenerateUVsAndUpload();
}
