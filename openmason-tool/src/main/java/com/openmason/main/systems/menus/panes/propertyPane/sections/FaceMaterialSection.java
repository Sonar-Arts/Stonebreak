package com.openmason.main.systems.menus.panes.propertyPane.sections;

import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IPanelSection;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IViewportConnector;
import com.openmason.main.systems.menus.textureCreator.FaceEditorBridge;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureSizer;
import com.openmason.main.systems.rendering.model.gmr.uv.MaterialDefinition;
import com.openmason.main.systems.rendering.model.miscComponents.OMTTextureLoader;
import com.openmason.main.systems.rendering.model.miscComponents.TextureLoadResult;
import com.openmason.main.systems.themes.utils.ImGuiComponents;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import imgui.ImGui;
import imgui.flag.ImGuiHoveredFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Property panel section for per-face material assignment.
 * Visible only in Face edit mode. Allows assigning OMT textures as materials
 * to individually selected faces in the viewport.
 *
 * <p>Integrates with:
 * <ul>
 *   <li>{@link FaceTextureManager} — material registration and face mapping</li>
 *   <li>{@link FaceSelectionState} — current face selection in the viewport</li>
 *   <li>{@link OMTTextureLoader} — loading OMT files as OpenGL textures</li>
 * </ul>
 */
public class FaceMaterialSection implements IPanelSection {

    private static final Logger logger = LoggerFactory.getLogger(FaceMaterialSection.class);

    private static final int DEFAULT_MATERIAL_ID = MaterialDefinition.DEFAULT.materialId();

    /** Counter for generating unique material IDs. Starts at 1 since 0 is the default material. */
    private static final AtomicInteger nextMaterialId = new AtomicInteger(1);

    private final FileDialogService fileDialogService;
    private final OMTTextureLoader omtTextureLoader;
    private IViewportConnector viewportConnector;
    private FaceEditorBridge faceEditorBridge;
    private Runnable onEditTextureRequested;

    /**
     * Creates a new FaceMaterialSection.
     *
     * @param fileDialogService service for showing native file dialogs
     */
    public FaceMaterialSection(FileDialogService fileDialogService) {
        this.fileDialogService = fileDialogService;
        this.omtTextureLoader = new OMTTextureLoader();
    }

    /**
     * Set the viewport connector for accessing face selection and material state.
     *
     * @param connector the viewport connector
     */
    public void setViewportConnector(IViewportConnector connector) {
        this.viewportConnector = connector;
    }

    /**
     * Set the face editor bridge for opening faces in the texture editor.
     *
     * @param bridge the bridge coordinating viewport-to-texture-editor handoff
     */
    public void setFaceEditorBridge(FaceEditorBridge bridge) {
        this.faceEditorBridge = bridge;
    }

    /**
     * Set a callback to be invoked when the user requests to edit a texture.
     * Typically used to show the texture editor window.
     *
     * @param callback action to run after the bridge opens the face region
     */
    public void setOnEditTextureRequested(Runnable callback) {
        this.onEditTextureRequested = callback;
    }

    @Override
    public void render() {
        if (!isVisible()) {
            return;
        }

        ImGuiComponents.renderCompactSectionHeader("Face Material");

        FaceSelectionState selectionState = viewportConnector.getFaceSelectionState();

        if (selectionState == null || !selectionState.hasSelection()) {
            ImGui.textDisabled("No face selected");
            ImGui.spacing();
            return;
        }

        int selectionCount = selectionState.getSelectionCount();
        Set<Integer> selectedFaces = selectionState.getSelectedFaceIndices();

        // Selection info
        if (selectionCount == 1) {
            int faceId = selectedFaces.iterator().next();
            ImGui.text("Face " + faceId);
        } else {
            ImGui.text(selectionCount + " faces selected");
        }

        // Current material display (show material for first selected face)
        int firstFaceId = selectedFaces.iterator().next();
        String materialName = getMaterialName(firstFaceId);
        ImGui.text("Material:");
        ImGui.sameLine();
        ImGui.textDisabled(materialName);

        ImGui.spacing();

        // Assign OMT button
        if (ImGui.button("Assign OMT Texture...")) {
            handleAssignOMT(selectedFaces);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Select an .OMT file to assign as material to the selected face(s)");
        }

        // Clear button (only show if non-default material is assigned)
        if (!materialName.equals("Default")) {
            ImGui.sameLine();
            if (ImGui.smallButton("Clear")) {
                handleClearMaterial(selectedFaces);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Revert to default material");
            }
        }

        // Edit Texture button — opens the face in the texture editor
        renderEditTextureButton(selectionCount, firstFaceId, materialName);

        ImGui.spacing();
    }

    @Override
    public boolean isVisible() {
        return viewportConnector != null
                && viewportConnector.isConnected()
                && viewportConnector.isInFaceEditMode();
    }

    @Override
    public String getSectionName() {
        return "Face Material";
    }

    /**
     * Render the "Edit Texture" button with appropriate enabled/disabled state.
     *
     * @param selectionCount number of selected faces
     * @param faceId         first selected face ID
     * @param materialName   display name of the face's current material
     */
    private void renderEditTextureButton(int selectionCount, int faceId, String materialName) {
        boolean canEdit = selectionCount == 1 && faceEditorBridge != null;

        if (!canEdit) {
            ImGui.beginDisabled();
        }

        if (ImGui.button("Edit Texture")) {
            handleEditTexture(faceId, materialName);
        }

        if (!canEdit) {
            ImGui.endDisabled();
        }

        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            if (faceEditorBridge == null) {
                ImGui.setTooltip("Texture editor bridge not configured");
            } else if (selectionCount != 1) {
                ImGui.setTooltip("Select exactly one face to edit its texture");
            } else {
                ImGui.setTooltip("Open this face in the texture editor");
            }
        }
    }

    /**
     * Open the selected face in the texture editor.
     * For faces with an assigned material, opens zoomed to the face's UV region.
     * For faces with the default material, opens with the full canvas.
     *
     * @param faceId       the face to edit
     * @param materialName display name of the face's current material
     */
    private static final int BLANK_FACE_TEXTURE_SIZE = 16;
    private static final int BLANK_FACE_FILL_COLOR = PixelCanvas.packRGBA(0xCC, 0xCC, 0xCC, 0xFF);

    private void handleEditTexture(int faceId, String materialName) {
        boolean opened;

        FaceTextureManager ftm = viewportConnector.getFaceTextureManager();
        if (ftm == null) {
            logger.error("FaceTextureManager not available");
            return;
        }

        if (materialName.equals("Default")) {
            // Compute texture dimensions from face geometry (falls back to default if unavailable)
            int[] faceDims = viewportConnector.computeFaceTextureDimensions(
                    faceId, FaceTextureSizer.DEFAULT_PIXELS_PER_UNIT);
            if (faceDims == null) {
                logger.debug("No face geometry data for face {} — using default {}x{} canvas",
                        faceId, BLANK_FACE_TEXTURE_SIZE, BLANK_FACE_TEXTURE_SIZE);
            }
            int texW = (faceDims != null) ? faceDims[0] : BLANK_FACE_TEXTURE_SIZE;
            int texH = (faceDims != null) ? faceDims[1] : BLANK_FACE_TEXTURE_SIZE;

            // Create a GPU texture for this face (gray, matches default appearance)
            int gpuTextureId = omtTextureLoader.createBlankTexture(texW, texH);
            if (gpuTextureId <= 0) {
                logger.error("Failed to create blank GPU texture for face {}", faceId);
                return;
            }

            // Register as a new material and assign to the face
            int materialId = nextMaterialId.getAndIncrement();
            MaterialDefinition material = new MaterialDefinition(
                    materialId, "Face " + faceId, gpuTextureId,
                    MaterialDefinition.RenderLayer.OPAQUE,
                    MaterialDefinition.MaterialProperties.NONE);
            ftm.registerMaterial(material);
            viewportConnector.setFaceTexture(faceId, materialId);

            // Create a matching canvas in the texture editor so the preview pipeline
            // uploads correct data (canvas dimensions must match the GPU texture)
            faceEditorBridge.prepareBlankCanvas(texW, texH, BLANK_FACE_FILL_COLOR);

            // Compute polygon AFTER setFaceTexture, which triggers UV regeneration.
            // Before material assignment the face has no mapping, so this falls back
            // to 3D projection — but computing after ensures consistency.
            float[][] polygon2D = viewportConnector.computeFacePolygon2D(faceId);
            opened = openWithPolygonMask(ftm, faceId, polygon2D);
        } else {
            // Existing material: check if the face geometry has changed since the
            // texture was created (e.g. subdivision + vertex moves). If so, create a
            // new GPU texture with the correct dimensions and re-register the material.
            FaceTextureMapping mapping = ftm.getFaceMapping(faceId);
            if (mapping != null) {
                MaterialDefinition material = ftm.getMaterial(mapping.materialId());
                if (material != null && material.textureId() > 0) {
                    int[] existingDims = viewportConnector.getTextureDimensions(material.textureId());
                    int[] currentDims = viewportConnector.computeFaceTextureDimensions(
                            faceId, FaceTextureSizer.DEFAULT_PIXELS_PER_UNIT);

                    boolean dimensionsChanged = existingDims != null && currentDims != null
                            && (existingDims[0] != currentDims[0] || existingDims[1] != currentDims[1]);

                    if (dimensionsChanged) {
                        // Face geometry changed — rescale existing painted pixels
                        // into the new dimensions using PixelCanvas.resized() so the
                        // rescaling is consistent with the texture editor's own resize.
                        byte[] oldPixels = viewportConnector.readTexturePixels(material.textureId());
                        PixelCanvas oldCanvas = new PixelCanvas(existingDims[0], existingDims[1]);
                        if (oldPixels != null) {
                            int[] px = oldCanvas.getPixels();
                            int count = Math.min(px.length, oldPixels.length / 4);
                            for (int i = 0; i < count; i++) {
                                int off = i * 4;
                                px[i] = PixelCanvas.packRGBA(
                                        oldPixels[off] & 0xFF, oldPixels[off + 1] & 0xFF,
                                        oldPixels[off + 2] & 0xFF, oldPixels[off + 3] & 0xFF);
                            }
                        }
                        PixelCanvas resizedCanvas = oldCanvas.resized(currentDims[0], currentDims[1]);

                        // Extend painted content into any transparent pixels so new
                        // regions (from geometry changes) are filled with the actual
                        // texture rather than a flat gray. Uses BFS flood fill from
                        // opaque pixel edges outward.
                        floodFillTransparent(resizedCanvas);

                        int newGpuTexId = omtTextureLoader.uploadPixelCanvasToGPU(resizedCanvas);
                        if (newGpuTexId > 0) {
                            MaterialDefinition updatedMaterial = new MaterialDefinition(
                                    material.materialId(), material.name(), newGpuTexId,
                                    material.renderLayer(), material.properties());
                            ftm.registerMaterial(updatedMaterial);
                            // Re-assign to trigger UV regeneration with new dimensions
                            viewportConnector.setFaceTexture(faceId, material.materialId());

                            byte[] resizedBytes = resizedCanvas.getPixelsAsRGBABytes();
                            faceEditorBridge.prepareCanvasFromPixels(
                                    currentDims[0], currentDims[1], resizedBytes);
                            logger.info("Resized face {} texture from {}x{} to {}x{} after geometry change",
                                    faceId, existingDims[0], existingDims[1],
                                    currentDims[0], currentDims[1]);
                        }
                    } else {
                        // Same dimensions — flood-fill the GPU texture so painted
                        // content extends into any new face regions, then re-upload
                        // and trigger UV regeneration.
                        int[] dims = (existingDims != null) ? existingDims : currentDims;
                        byte[] pixels = viewportConnector.readTexturePixels(material.textureId());
                        if (dims != null && pixels != null) {
                            PixelCanvas canvas = new PixelCanvas(dims[0], dims[1]);
                            int[] px = canvas.getPixels();
                            int count = Math.min(px.length, pixels.length / 4);
                            for (int i = 0; i < count; i++) {
                                int off = i * 4;
                                px[i] = PixelCanvas.packRGBA(
                                        pixels[off] & 0xFF, pixels[off + 1] & 0xFF,
                                        pixels[off + 2] & 0xFF, pixels[off + 3] & 0xFF);
                            }
                            floodFillTransparent(canvas);

                            // Re-upload filled texture to GPU and update material
                            int filledTexId = omtTextureLoader.uploadPixelCanvasToGPU(canvas);
                            if (filledTexId > 0) {
                                MaterialDefinition updatedMaterial = new MaterialDefinition(
                                        material.materialId(), material.name(), filledTexId,
                                        material.renderLayer(), material.properties());
                                ftm.registerMaterial(updatedMaterial);
                            }

                            byte[] filledBytes = canvas.getPixelsAsRGBABytes();
                            faceEditorBridge.prepareCanvasFromPixels(dims[0], dims[1], filledBytes);
                        }

                        // Re-assign to trigger UV regeneration
                        viewportConnector.setFaceTexture(faceId, material.materialId());
                    }
                }
            }
            // Compute polygon AFTER any UV regeneration so the mask reflects
            // the current geometry (vertex moves change UVs on next regen).
            float[][] polygon2D = viewportConnector.computeFacePolygon2D(faceId);
            opened = openWithPolygonMask(ftm, faceId, polygon2D);
        }

        if (opened) {
            // Switch from filled overlay to outline so the real-time texture preview is visible
            viewportConnector.setEditingFaceIndex(faceId);

            if (onEditTextureRequested != null) {
                onEditTextureRequested.run();
            }
        }
    }

    /**
     * Open a face for editing using polygon mask when 2D geometry is available,
     * falling back to rectangular mode when it is not.
     *
     * @param ftm       face texture manager
     * @param faceId    face to open
     * @param polygon2D projected 2D polygon (nullable)
     * @return true if the face was opened successfully
     */
    private boolean openWithPolygonMask(FaceTextureManager ftm, int faceId, float[][] polygon2D) {
        if (polygon2D != null) {
            return faceEditorBridge.openFaceForEditing(
                    ftm, faceId, polygon2D[0], polygon2D[1], 800, 600);
        }
        return faceEditorBridge.openRectFaceForEditing(ftm, faceId, 800, 600);
    }

    /**
     * Get the display name of the material assigned to a face.
     *
     * @param faceId face identifier
     * @return material name, or "Default" if no mapping exists
     */
    private String getMaterialName(int faceId) {
        FaceTextureManager ftm = viewportConnector.getFaceTextureManager();
        if (ftm == null) {
            return "Default";
        }

        FaceTextureMapping mapping = ftm.getFaceMapping(faceId);
        if (mapping == null) {
            return "Default";
        }

        MaterialDefinition material = ftm.getMaterial(mapping.materialId());
        if (material == null) {
            return "Default";
        }

        return material.name();
    }

    /**
     * Handle OMT texture assignment through file dialog.
     *
     * @param selectedFaces set of selected face IDs
     */
    private void handleAssignOMT(Set<Integer> selectedFaces) {
        fileDialogService.showOpenOMTDialog(filePath -> {
            Path omtPath = Paths.get(filePath);
            String fileName = omtPath.getFileName().toString();

            TextureLoadResult result = omtTextureLoader.loadTextureComposite(omtPath);
            if (!result.isSuccess()) {
                logger.error("Failed to load OMT texture: {}", filePath);
                return;
            }

            FaceTextureManager ftm = viewportConnector.getFaceTextureManager();
            if (ftm == null) {
                logger.error("FaceTextureManager not available");
                return;
            }

            // Create and register a new material
            int materialId = nextMaterialId.getAndIncrement();
            MaterialDefinition material = new MaterialDefinition(
                    materialId,
                    fileName,
                    result.getTextureId(),
                    MaterialDefinition.RenderLayer.OPAQUE,
                    MaterialDefinition.MaterialProperties.NONE
            );
            ftm.registerMaterial(material);

            // Assign material to all selected faces
            for (int faceId : selectedFaces) {
                viewportConnector.setFaceTexture(faceId, materialId);
            }

            logger.info("Assigned OMT material '{}' (ID {}) to {} face(s)",
                    fileName, materialId, selectedFaces.size());
        });
    }

    /**
     * Synchronize the material ID counter after loading a file with existing materials.
     * Bumps the counter to at least {@code loadedMaxId + 1} so that new materials
     * created by the user will not collide with loaded material IDs.
     *
     * @param loadedMaxId the highest material ID found in the loaded data
     */
    public static void syncNextMaterialId(int loadedMaxId) {
        nextMaterialId.updateAndGet(current -> Math.max(current, loadedMaxId + 1));
    }

    /**
     * Allocate and return the next unique material ID.
     * Thread-safe — uses the shared atomic counter.
     *
     * @return A new unique material ID
     */
    public static int allocateNextMaterialId() {
        return nextMaterialId.getAndIncrement();
    }

    /**
     * Clear material assignment on selected faces (revert to default).
     *
     * @param selectedFaces set of selected face IDs
     */
    private void handleClearMaterial(Set<Integer> selectedFaces) {
        for (int faceId : selectedFaces) {
            viewportConnector.setFaceTexture(faceId, DEFAULT_MATERIAL_ID);
        }
        logger.info("Cleared material on {} face(s)", selectedFaces.size());
    }

    /**
     * Flood-fill transparent pixels by propagating the nearest opaque pixel color
     * outward via BFS. This extends painted content into any new regions created
     * by geometry changes, preserving the actual texture rather than filling
     * with a flat color.
     *
     * @param canvas the canvas to fill (modified in place)
     */
    private static void floodFillTransparent(PixelCanvas canvas) {
        int w = canvas.getWidth();
        int h = canvas.getHeight();
        int[] pixels = canvas.getPixels();

        // Seed the BFS queue with opaque pixels that border at least one transparent pixel
        Deque<int[]> queue = new ArrayDeque<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((pixels[y * w + x] >>> 24) == 0) {
                    continue; // transparent — skip
                }
                // Check 4-connected neighbors for transparency
                if ((x > 0     && (pixels[y * w + (x - 1)] >>> 24) == 0)
                 || (x < w - 1 && (pixels[y * w + (x + 1)] >>> 24) == 0)
                 || (y > 0     && (pixels[(y - 1) * w + x] >>> 24) == 0)
                 || (y < h - 1 && (pixels[(y + 1) * w + x] >>> 24) == 0)) {
                    queue.add(new int[]{x, y});
                }
            }
        }

        // BFS: propagate opaque colors into adjacent transparent pixels
        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            int px = pos[0];
            int py = pos[1];
            int color = pixels[py * w + px];

            int[][] neighbors = {{px - 1, py}, {px + 1, py}, {px, py - 1}, {px, py + 1}};
            for (int[] n : neighbors) {
                int nx = n[0];
                int ny = n[1];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
                    continue;
                }
                if ((pixels[ny * w + nx] >>> 24) == 0) {
                    pixels[ny * w + nx] = color;
                    queue.add(new int[]{nx, ny});
                }
            }
        }
    }
}
