package com.openmason.main.systems.menus.textureCreator;

import com.openmason.main.systems.menus.textureCreator.canvas.FaceBoundaryMask;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureMapping.UVRegion;
import com.openmason.main.systems.rendering.model.gmr.uv.IFaceTextureManager;
import com.openmason.main.systems.rendering.model.gmr.uv.MaterialDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates opening a face from the 3D viewport in the texture editor.
 *
 * <p>When a user selects a face in Face edit mode and triggers "Edit Texture"
 * (context menu or shortcut), this bridge:
 * <ol>
 *   <li>Reads the face's {@link FaceTextureMapping} from the {@link IFaceTextureManager}</li>
 *   <li>Resolves the material's texture ID via {@link MaterialDefinition}</li>
 *   <li>Creates a {@link FaceBoundaryMask} defining the paintable area</li>
 *   <li>Opens the texture editor zoomed/panned to the face's UV region via
 *       {@link TextureCreatorController#openFaceRegion}</li>
 * </ol>
 *
 * <p>This class is stateless — it performs a one-shot coordination between the
 * viewport and the texture editor. The controller holds the ongoing editing state.
 *
 * @see TextureCreatorController
 * @see FaceBoundaryMask
 * @see IFaceTextureManager
 */
public class FaceEditorBridge {

    private static final Logger logger = LoggerFactory.getLogger(FaceEditorBridge.class);

    private final TextureCreatorController controller;

    /**
     * Create a bridge between the viewport and the texture editor.
     *
     * @param controller the texture editor controller to open faces in
     */
    public FaceEditorBridge(TextureCreatorController controller) {
        this.controller = controller;
    }

    /**
     * Open a face for texture editing.
     *
     * <p>Reads the face's UV mapping, resolves the material, creates a boundary
     * mask from the face polygon, and opens the texture editor focused on
     * the face's UV region.
     *
     * @param faceTextureManager face texture data source
     * @param faceId             ID of the face to edit
     * @param polygonXCoords     face polygon X coordinates in local 2D space (0.0–1.0 normalized)
     * @param polygonYCoords     face polygon Y coordinates in local 2D space (0.0–1.0 normalized)
     * @param viewportWidth      available viewport width in screen pixels
     * @param viewportHeight     available viewport height in screen pixels
     * @return true if the face was opened successfully
     */
    public boolean openFaceForEditing(IFaceTextureManager faceTextureManager,
                                       int faceId,
                                       float[] polygonXCoords, float[] polygonYCoords,
                                       float viewportWidth, float viewportHeight) {
        // 1. Read face mapping
        FaceTextureMapping mapping = faceTextureManager.getFaceMapping(faceId);
        if (mapping == null) {
            logger.warn("No texture mapping found for face {}", faceId);
            return false;
        }

        int materialId = mapping.materialId();
        UVRegion uvRegion = mapping.uvRegion();

        // 2. Resolve material
        MaterialDefinition material = faceTextureManager.getMaterial(materialId);
        if (material == null) {
            logger.warn("No material found for materialId {} (face {})", materialId, faceId);
            return false;
        }

        logger.debug("Opening face {} for editing: material='{}' (id={}), textureId={}, uv=({},{})→({},{})",
            faceId, material.name(), materialId, material.textureId(),
            uvRegion.u0(), uvRegion.v0(), uvRegion.u1(), uvRegion.v1());

        // 3. Create face boundary mask from polygon vertices mapped to canvas space
        int canvasWidth = controller.getLayerManager().getCanvasWidth();
        int canvasHeight = controller.getLayerManager().getCanvasHeight();

        FaceBoundaryMask mask = FaceBoundaryMask.fromUVRegion(
            canvasWidth, canvasHeight,
            uvRegion.u0(), uvRegion.v0(), uvRegion.u1(), uvRegion.v1(),
            polygonXCoords, polygonYCoords
        );

        // 4. Open the texture editor focused on this face's region
        controller.openFaceRegion(materialId, uvRegion, mask, viewportWidth, viewportHeight);

        logger.info("Face {} opened for editing in texture editor", faceId);
        return true;
    }

    /**
     * Open a rectangular face for editing (no polygon mask needed).
     *
     * <p>Convenience method for standard quad faces where the UV region is
     * fully paintable — no boundary mask is applied.
     *
     * @param faceTextureManager face texture data source
     * @param faceId             ID of the face to edit
     * @param viewportWidth      available viewport width in screen pixels
     * @param viewportHeight     available viewport height in screen pixels
     * @return true if the face was opened successfully
     */
    public boolean openRectFaceForEditing(IFaceTextureManager faceTextureManager,
                                           int faceId,
                                           float viewportWidth, float viewportHeight) {
        FaceTextureMapping mapping = faceTextureManager.getFaceMapping(faceId);
        if (mapping == null) {
            logger.warn("No texture mapping found for face {}", faceId);
            return false;
        }

        UVRegion uvRegion = mapping.uvRegion();
        controller.openFaceRegion(mapping.materialId(), uvRegion, null,
                                   viewportWidth, viewportHeight);

        logger.info("Rectangular face {} opened for editing", faceId);
        return true;
    }

    /**
     * Prepare a blank canvas in the texture editor matching the given dimensions and fill color.
     * Must be called before opening a face that has no existing texture (e.g. a freshly created
     * blank material) so the canvas dimensions match the GPU texture.
     *
     * @param width     canvas width in pixels
     * @param height    canvas height in pixels
     * @param fillColor RGBA fill color (use {@link com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas#packRGBA})
     */
    public void prepareBlankCanvas(int width, int height, int fillColor) {
        controller.prepareBlankCanvas(width, height, fillColor);
    }

    /**
     * Prepare the canvas with existing pixel data read back from a GPU texture.
     * Must be called before opening a face whose material already has texture data
     * (e.g. loaded from .OMO) so the editor shows the current pixels.
     *
     * @param width      texture width in pixels
     * @param height     texture height in pixels
     * @param rgbaPixels RGBA byte array (4 bytes per pixel, row-major)
     */
    public void prepareCanvasFromPixels(int width, int height, byte[] rgbaPixels) {
        controller.prepareCanvasFromPixels(width, height, rgbaPixels);
    }

    /**
     * Close face editing and return to full canvas mode.
     */
    public void closeFaceEditing() {
        controller.closeFaceRegion();
    }
}
