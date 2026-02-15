package com.openmason.main.systems.menus.panes.propertyPane.sections;

import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IPanelSection;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IViewportConnector;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.main.systems.rendering.model.gmr.uv.MaterialDefinition;
import com.openmason.main.systems.rendering.model.miscComponents.OMTTextureLoader;
import com.openmason.main.systems.rendering.model.miscComponents.TextureLoadResult;
import com.openmason.main.systems.themes.utils.ImGuiComponents;
import com.openmason.main.systems.viewport.state.FaceSelectionState;
import imgui.ImGui;
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
