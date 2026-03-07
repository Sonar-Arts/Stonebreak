package com.openmason.main.systems.menus.panes.propertyPane.sections;

import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IPanelSection;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IViewportConnector;
import com.openmason.main.systems.menus.textureCreator.FaceEditorBridge;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureMapping;
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
            // Create a GPU texture for this face (gray, matches default appearance)
            int size = BLANK_FACE_TEXTURE_SIZE;
            int gpuTextureId = omtTextureLoader.createBlankTexture(size, size);
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
            faceEditorBridge.prepareBlankCanvas(size, size, BLANK_FACE_FILL_COLOR);

            // Open as normal rect face (now has a valid textureId)
            opened = faceEditorBridge.openRectFaceForEditing(ftm, faceId, 800, 600);
        } else {
            opened = faceEditorBridge.openRectFaceForEditing(ftm, faceId, 800, 600);
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
}
