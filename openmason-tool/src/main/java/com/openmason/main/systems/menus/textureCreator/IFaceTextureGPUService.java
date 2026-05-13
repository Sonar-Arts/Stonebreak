package com.openmason.main.systems.menus.textureCreator;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;

/**
 * Slim GPU/viewport service used by the texture editor's face-texture operations.
 *
 * <p>Decouples textureCreator components (e.g. {@link FaceTextureResizeDialog})
 * from the property panel's IViewportConnector. Implementations bridge to the
 * viewport adapter and the OMT texture loader.
 */
public interface IFaceTextureGPUService {

    /** @return {width, height} of the GPU texture, or {@code null} if unavailable */
    int[] getTextureDimensions(int gpuTextureId);

    /** @return RGBA pixel bytes read back from the GPU texture, or {@code null} on failure */
    byte[] readTexturePixels(int gpuTextureId);

    /**
     * Re-assign a face to a material, triggering UV regeneration and a draw-batch
     * rebuild. Called after the material's underlying GPU texture has been replaced.
     */
    void setFaceTexture(int faceId, int materialId);

    /**
     * @return 2D projected polygon for a face as {xCoords[], yCoords[]} in
     * normalized 0..1 space, or {@code null} if geometry is unavailable
     */
    float[][] computeFacePolygon2D(int faceId);

    /**
     * Upload pixel data to a new GPU texture.
     *
     * @return GPU texture ID, or {@code <= 0} on failure
     */
    int uploadPixelCanvasToGPU(PixelCanvas canvas);
}
