package com.openmason.main.systems.menus.panes.propertyPane.sections;

import com.openmason.main.systems.menus.dialogs.AddPartDialog;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IPanelSection;
import com.openmason.main.systems.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.main.systems.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.rendering.model.gmr.parts.PartShapeFactory;
import com.openmason.main.systems.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.main.systems.themes.utils.ImGuiComponents;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiSelectableFlags;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Properties panel section for managing model parts.
 * Displays the part list with selection, visibility, lock controls,
 * and an "Add Part" button that opens the {@link AddPartDialog}.
 *
 * <p>Follows SRP — solely responsible for the model parts UI.
 * Delegates all part operations to {@link ModelPartManager}.
 */
public class ModelPartsSection implements IPanelSection {

    private static final Logger logger = LoggerFactory.getLogger(ModelPartsSection.class);

    private ModelPartManager partManager;
    private final AddPartDialog addPartDialog;
    private boolean visible = true;

    /**
     * Callback invoked after a part is added, receiving the part descriptor.
     * Used by the property panel to assign default materials to the new part's faces.
     */
    private java.util.function.Consumer<ModelPartDescriptor> onPartCreated;

    public ModelPartsSection() {
        this.addPartDialog = new AddPartDialog();
    }

    /**
     * Set the part manager reference.
     * Called when the viewport is connected.
     *
     * @param partManager The model part manager
     */
    public void setPartManager(ModelPartManager partManager) {
        this.partManager = partManager;
    }

    @Override
    public void render() {
        if (!visible || partManager == null) {
            return;
        }

        ImGuiComponents.renderCompactSectionHeader("Model Parts");
        ImGui.spacing();

        // Part list
        List<ModelPartDescriptor> parts = partManager.getAllParts();

        if (parts.isEmpty()) {
            ImGui.textDisabled("No parts");
        } else {
            renderPartList(parts);
        }

        ImGui.spacing();

        // Add Part button
        if (ImGui.button("Add Part", -1, 0)) {
            addPartDialog.show(this::onPartAdded);
        }

        // Render the modal dialog (must be called every frame)
        addPartDialog.render();
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public String getSectionName() {
        return "Model Parts";
    }

    /**
     * Set visibility of this section.
     *
     * @param visible true to show
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /**
     * Set callback invoked after a part is created.
     * The callback receives the new part descriptor and can set up default materials.
     *
     * @param callback Part creation callback
     */
    public void setOnPartCreated(java.util.function.Consumer<ModelPartDescriptor> callback) {
        this.onPartCreated = callback;
    }

    // ========== Private Rendering ==========

    /**
     * Render the list of parts with selection, visibility toggle, and lock toggle.
     */
    private void renderPartList(List<ModelPartDescriptor> parts) {
        for (int i = 0; i < parts.size(); i++) {
            ModelPartDescriptor part = parts.get(i);
            boolean isSelected = partManager.isPartSelected(part.id());

            ImGui.pushID(i);

            // Visibility toggle (eye icon)
            if (part.visible()) {
                if (ImGui.smallButton("V")) {
                    partManager.setPartVisible(part.id(), false);
                }
            } else {
                ImGui.pushStyleColor(ImGuiCol.Text, 0.5f, 0.5f, 0.5f, 0.5f);
                if (ImGui.smallButton("H")) {
                    partManager.setPartVisible(part.id(), true);
                }
                ImGui.popStyleColor();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(part.visible() ? "Hide part" : "Show part");
            }

            ImGui.sameLine();

            // Lock toggle
            if (part.locked()) {
                ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.6f, 0.0f, 1.0f);
                if (ImGui.smallButton("L")) {
                    partManager.setPartLocked(part.id(), false);
                }
                ImGui.popStyleColor();
            } else {
                if (ImGui.smallButton("U")) {
                    partManager.setPartLocked(part.id(), true);
                }
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(part.locked() ? "Unlock part" : "Lock part");
            }

            ImGui.sameLine();

            // Part name (selectable)
            String label = part.name();
            if (!part.visible()) {
                ImGui.pushStyleColor(ImGuiCol.Text, 0.5f, 0.5f, 0.5f, 0.5f);
            }

            if (ImGui.selectable(label, isSelected, ImGuiSelectableFlags.None)) {
                if (ImGui.getIO().getKeyCtrl()) {
                    partManager.togglePartSelection(part.id());
                } else {
                    partManager.deselectAllParts();
                    partManager.selectPart(part.id());
                }
            }

            if (!part.visible()) {
                ImGui.popStyleColor();
            }

            // Right-click context menu
            if (ImGui.beginPopupContextItem()) {
                if (ImGui.menuItem("Duplicate")) {
                    partManager.duplicatePart(part.id());
                }
                if (ImGui.menuItem("Delete") && parts.size() > 1) {
                    partManager.removePart(part.id());
                }
                if (parts.size() <= 1) {
                    ImGui.textDisabled("Cannot delete last part");
                }
                ImGui.endPopup();
            }

            // Tooltip with part info
            if (ImGui.isItemHovered() && part.meshRange() != null) {
                ImGui.setTooltip(String.format("Vertices: %d  Faces: %d",
                        part.meshRange().vertexCount(), part.meshRange().faceCount()));
            }

            ImGui.popID();
        }
    }

    // ========== Callbacks ==========

    /**
     * Callback from AddPartDialog when user confirms a new part.
     */
    private void onPartAdded(PartShapeFactory.Shape shape, String name) {
        if (partManager == null) {
            logger.warn("Cannot add part: no part manager connected");
            return;
        }

        // Create geometry with proper face mapping via the factory
        PartMeshRebuilder.PartGeometry geometry = PartShapeFactory.createGeometry(
                shape, name, new Vector3f(1, 1, 1)
        );

        // Add via the geometry path to preserve face mapping
        ModelPartDescriptor newPart = partManager.addPartFromGeometry(name, geometry, new Vector3f(0, 0, 0));

        // Notify callback to set up default material for the new part's faces
        if (onPartCreated != null && newPart != null) {
            onPartCreated.accept(newPart);
        }

        logger.info("Added {} part '{}'", shape.getDisplayName(), name);
    }
}
