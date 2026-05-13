package com.openmason.main.systems.menus.textureCreator;

import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Modal dialog for resizing the GPU texture of the face currently being edited
 * in the texture editor.
 *
 * <p>Triggered from the texture editor's Edit menu when a face region is active.
 * The dialog reads back the face's current GPU texture, nearest-neighbor rescales
 * it to the user-specified dimensions via {@link PixelCanvas#resized}, uploads
 * a new GPU texture, swaps the material's textureId, and refreshes the texture
 * editor's canvas in place so the user keeps editing at the new resolution.
 *
 * <p>UVs are unaffected — the face's {@code uvRegion} is normalized 0..1 within
 * the material texture, so changing the texture's pixel dimensions doesn't move
 * any mesh UVs.
 */
public final class FaceTextureResizeDialog {

    private static final Logger logger = LoggerFactory.getLogger(FaceTextureResizeDialog.class);

    private static final String POPUP_ID = "Resize Face Texture##FaceTextureResizeDialog";
    private static final int RESIZE_MIN = 1;
    private static final int RESIZE_MAX = 1024;

    private final ImInt resizeWidth = new ImInt(16);
    private final ImInt resizeHeight = new ImInt(16);
    private int targetFaceId = -1;
    private int targetMaterialId = -1;
    private boolean openNextFrame = false;

    private final Supplier<FaceTextureManager> ftmSupplier;
    private final IFaceTextureGPUService gpuService;
    private final FaceEditorBridge faceEditorBridge;

    public FaceTextureResizeDialog(Supplier<FaceTextureManager> ftmSupplier,
                                   IFaceTextureGPUService gpuService,
                                   FaceEditorBridge faceEditorBridge) {
        this.ftmSupplier = ftmSupplier;
        this.gpuService = gpuService;
        this.faceEditorBridge = faceEditorBridge;
    }

    /**
     * @return true if a face is currently open in the editor and therefore a
     * resize can be triggered.
     */
    public boolean canOpen() {
        return faceEditorBridge != null
                && faceEditorBridge.isFaceRegionActive()
                && faceEditorBridge.getActiveFaceRegionMaterialId() > 0;
    }

    /**
     * Schedule the popup to open on the next render frame, targeting whichever
     * face is currently being edited in the texture editor.
     */
    public void openForCurrentFace() {
        if (!canOpen()) {
            logger.debug("Resize dialog requested but no face region is active");
            return;
        }
        FaceTextureManager ftm = ftmSupplier.get();
        if (ftm == null) {
            logger.warn("Resize dialog requested but FaceTextureManager is unavailable");
            return;
        }

        int materialId = faceEditorBridge.getActiveFaceRegionMaterialId();
        int faceId = findFaceForMaterial(ftm, materialId);
        if (faceId < 0) {
            logger.warn("No face found for material {} — cannot resize", materialId);
            return;
        }

        MaterialDefinition material = ftm.getMaterial(materialId);
        if (material == null || material.textureId() <= 0) {
            logger.warn("Material {} has no GPU texture — cannot resize", materialId);
            return;
        }

        int[] dims = gpuService.getTextureDimensions(material.textureId());
        if (dims != null) {
            resizeWidth.set(dims[0]);
            resizeHeight.set(dims[1]);
        }
        this.targetFaceId = faceId;
        this.targetMaterialId = materialId;
        this.openNextFrame = true;
    }

    /**
     * Render the popup. Must be called every frame from the texture editor's
     * render loop so the modal stays alive while open.
     */
    public void render() {
        if (openNextFrame) {
            ImGui.openPopup(POPUP_ID);
            openNextFrame = false;
        }

        if (ImGui.beginPopupModal(POPUP_ID, ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGui.text("Face " + targetFaceId);
            ImGui.separator();
            ImGui.text("New dimensions (pixels):");

            ImGui.setNextItemWidth(120);
            ImGui.inputInt("Width", resizeWidth);
            ImGui.setNextItemWidth(120);
            ImGui.inputInt("Height", resizeHeight);

            int w = Math.clamp(resizeWidth.get(), RESIZE_MIN, RESIZE_MAX);
            int h = Math.clamp(resizeHeight.get(), RESIZE_MIN, RESIZE_MAX);
            if (w != resizeWidth.get()) resizeWidth.set(w);
            if (h != resizeHeight.get()) resizeHeight.set(h);

            ImGui.spacing();
            ImGui.textDisabled("Nearest-neighbor scaling preserves pixel art.");
            ImGui.spacing();

            if (ImGui.button("Apply", 100, 0)) {
                apply(targetFaceId, targetMaterialId, w, h);
                ImGui.closeCurrentPopup();
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel", 100, 0)) {
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    private int findFaceForMaterial(FaceTextureManager ftm, int materialId) {
        for (FaceTextureMapping m : ftm.getAllMappings()) {
            if (m.materialId() == materialId) {
                return m.faceId();
            }
        }
        return -1;
    }

    private void apply(int faceId, int materialId, int newWidth, int newHeight) {
        FaceTextureManager ftm = ftmSupplier.get();
        if (ftm == null) {
            logger.error("FaceTextureManager not available — cannot apply resize");
            return;
        }
        MaterialDefinition material = ftm.getMaterial(materialId);
        if (material == null || material.textureId() <= 0) {
            logger.warn("Material {} unavailable — cannot apply resize", materialId);
            return;
        }

        int[] dims = gpuService.getTextureDimensions(material.textureId());
        byte[] pixels = gpuService.readTexturePixels(material.textureId());
        if (dims == null || pixels == null) {
            logger.error("Failed to read GPU texture for material {}", materialId);
            return;
        }
        if (dims[0] == newWidth && dims[1] == newHeight) {
            logger.debug("Texture already at {}x{} — skipping resize", newWidth, newHeight);
            return;
        }

        PixelCanvas oldCanvas = new PixelCanvas(dims[0], dims[1]);
        int[] px = oldCanvas.getPixels();
        int count = Math.min(px.length, pixels.length / 4);
        for (int i = 0; i < count; i++) {
            int off = i * 4;
            px[i] = PixelCanvas.packRGBA(
                    pixels[off] & 0xFF, pixels[off + 1] & 0xFF,
                    pixels[off + 2] & 0xFF, pixels[off + 3] & 0xFF);
        }

        PixelCanvas resized = oldCanvas.resized(newWidth, newHeight);
        int newTexId = gpuService.uploadPixelCanvasToGPU(resized);
        if (newTexId <= 0) {
            logger.error("Failed to upload resized texture for face {}", faceId);
            return;
        }

        MaterialDefinition updated = new MaterialDefinition(
                material.materialId(), material.name(), newTexId,
                material.renderLayer(), material.properties());
        ftm.registerMaterial(updated);
        gpuService.setFaceTexture(faceId, material.materialId());

        logger.info("Resized face {} texture from {}x{} to {}x{}",
                faceId, dims[0], dims[1], newWidth, newHeight);

        // Refresh editor canvas in place + re-open the face region so the polygon
        // mask and view framing reflect the new canvas dimensions.
        byte[] resizedBytes = resized.getPixelsAsRGBABytes();
        faceEditorBridge.prepareCanvasFromPixels(newWidth, newHeight, resizedBytes);

        float[][] polygon2D = gpuService.computeFacePolygon2D(faceId);
        if (polygon2D != null) {
            faceEditorBridge.openFaceForEditing(ftm, faceId, polygon2D[0], polygon2D[1], 800, 600);
        } else {
            faceEditorBridge.openRectFaceForEditing(ftm, faceId, 800, 600);
        }
    }
}
