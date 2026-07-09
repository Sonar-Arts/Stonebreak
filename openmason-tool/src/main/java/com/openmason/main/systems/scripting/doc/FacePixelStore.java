package com.openmason.main.systems.scripting.doc;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;

/**
 * Pixel-level access to per-face model textures — the seam that lets the
 * scripting texture domain ({@code om.tex} / {@code texture_*} ops) run
 * against the live renderer's GPU textures.
 *
 * <p>Headless documents return {@code null} from {@link ModelDocument#pixels()}
 * (face-texture pixels are live-only for now — the headless OMO writer carries
 * no pixel data); the command layer turns that into a teaching error.
 */
public interface FacePixelStore {

    /** [width, height] of a texture, or null when unknown. */
    int[] textureSize(int gpuTextureId);

    /** Full RGBA readback (row-major, 4 bytes/pixel), or null on failure. */
    byte[] readPixels(int gpuTextureId);

    /** Upload an RGBA sub-region into an existing texture. */
    void writeRegion(int gpuTextureId, int x, int y, int width, int height, byte[] rgbaBytes);

    /** Create a new GPU texture from a canvas; returns its id (> 0). */
    int createTexture(PixelCanvas canvas);

    /** Allocate a material id from the renderer's single source of truth. */
    int allocateMaterialId();

    /**
     * Assign already-registered materials to global face ids in one pass
     * (one UV regeneration + mesh upload for the whole batch).
     */
    void assignFaceMaterials(int[] globalFaceIds, int[] materialIds);

    /** Delete a GPU texture (rollback hygiene for script-created textures). */
    void deleteTexture(int gpuTextureId);
}
