package com.openmason.main.systems.menus.panes.propertyPane.sections;

import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IPanelSection;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import com.openmason.main.systems.themes.utils.ImGuiComponents;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Properties panel section for managing model parts.
 * Displays the part list with selection, visibility, lock controls,
 * and an "Add Part" button that opens the viewport slideout.
 *
 * <p>Follows SRP — solely responsible for the model parts UI.
 * Delegates all part operations to {@link ModelPartManager}.
 */
public class ModelPartsSection implements IPanelSection {

    private static final Logger logger = LoggerFactory.getLogger(ModelPartsSection.class);

    private ModelPartManager partManager;
    private boolean visible = true;

    /**
     * Callback invoked after a part is added, receiving the part descriptor.
     * Used by the property panel to assign default materials to the new part's faces.
     */
    private java.util.function.Consumer<ModelPartDescriptor> onPartCreated;

    /**
     * Callback invoked after any part change that affects the viewport.
     * Used to invalidate sub-renderer caches so changes appear immediately.
     */
    private Runnable onViewportInvalidationNeeded;

    /** Callback to open the Add Part slideout in the viewport. */
    private Runnable onOpenAddPartSlideout;

    /** Callback to open the Part Transform slideout when a part is selected. */
    private Runnable onOpenPartTransformSlideout;

    public ModelPartsSection() {
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

        // Section header with part count
        List<ModelPartDescriptor> parts = partManager.getAllParts();
        String headerText = parts.isEmpty() ? "Model Parts" : "Model Parts (" + parts.size() + ")";
        ImGuiComponents.renderCompactSectionHeader(headerText);
        ImGui.spacing();

        // Part list
        if (parts.isEmpty()) {
            ImGui.textDisabled("No parts");
        } else {
            renderPartList(parts);
        }

        ImGui.spacing();

        // Add Part button — opens the slideout panel in the viewport
        ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        ImGui.pushStyleColor(ImGuiCol.Button, accent.x, accent.y, accent.z, 0.20f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, accent.x, accent.y, accent.z, 0.40f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, accent.x, accent.y, accent.z, 0.55f);
        ImGui.pushStyleColor(ImGuiCol.Text, accent.x, accent.y, accent.z, 1.0f);

        if (ImGui.button("+ Add Part", -1, 0)) {
            if (onOpenAddPartSlideout != null) {
                onOpenAddPartSlideout.run();
            }
        }

        ImGui.popStyleColor(4);
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

    /**
     * Set callback to invalidate viewport sub-renderers after part changes.
     *
     * @param callback Viewport invalidation callback
     */
    public void setOnViewportInvalidationNeeded(Runnable callback) {
        this.onViewportInvalidationNeeded = callback;
    }

    /**
     * Set callback to open the Add Part slideout in the viewport.
     *
     * @param callback Slideout open callback
     */
    public void setOnOpenAddPartSlideout(Runnable callback) {
        this.onOpenAddPartSlideout = callback;
    }

    /**
     * Set callback to open the Part Transform slideout when a part is selected.
     *
     * @param callback Slideout open callback
     */
    public void setOnOpenPartTransformSlideout(Runnable callback) {
        this.onOpenPartTransformSlideout = callback;
    }

    // ========== Private Rendering ==========

    /**
     * Render the list of parts with functional buttons and selection.
     * Uses standard ImGui smallButton widgets so all controls receive clicks.
     */
    private void renderPartList(List<ModelPartDescriptor> parts) {
        for (int i = 0; i < parts.size(); i++) {
            ModelPartDescriptor part = parts.get(i);
            boolean isSelected = partManager.isPartSelected(part.id());
            boolean isHidden = !part.visible();

            ImGui.pushID(i);

            // --- Row background ---
            ImDrawList drawList = ImGui.getWindowDrawList();
            ImVec2 rowStart = ImGui.getCursorScreenPos();
            float rowWidth = ImGui.getContentRegionAvailX();
            float rowHeight = ImGui.getFrameHeight();

            if (isSelected) {
                ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
                drawList.addRectFilled(rowStart.x, rowStart.y,
                        rowStart.x + rowWidth, rowStart.y + rowHeight,
                        ImGui.colorConvertFloat4ToU32(accent.x, accent.y, accent.z, 0.15f), 3.0f);
                drawList.addRectFilled(rowStart.x, rowStart.y + 3,
                        rowStart.x + 3, rowStart.y + rowHeight - 3,
                        ImGui.colorConvertFloat4ToU32(accent.x, accent.y, accent.z, 0.6f), 1.0f);
            }

            // --- Visibility toggle ---
            if (isHidden) {
                ImGui.pushStyleColor(ImGuiCol.Text, 0.5f, 0.5f, 0.5f, 0.35f);
            }
            if (ImGui.smallButton(isHidden ? " - " : " o ")) {
                partManager.setPartVisible(part.id(), isHidden);
                invalidateViewport();
            }
            if (isHidden) {
                ImGui.popStyleColor();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(isHidden ? "Show part" : "Hide part");
            }

            ImGui.sameLine();

            // --- Lock toggle ---
            if (part.locked()) {
                ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.6f, 0.0f, 1.0f);
            } else {
                ImVec4 dim = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
                ImGui.pushStyleColor(ImGuiCol.Text, dim.x, dim.y, dim.z, 0.4f);
            }
            if (ImGui.smallButton(part.locked() ? " L " : " U ")) {
                partManager.setPartLocked(part.id(), !part.locked());
                invalidateViewport();
            }
            ImGui.popStyleColor();
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(part.locked() ? "Unlock part" : "Lock part");
            }

            ImGui.sameLine();

            // --- Part name (selectable for selection) ---
            if (isHidden) {
                ImGui.pushStyleColor(ImGuiCol.Text, 0.5f, 0.5f, 0.5f, 0.35f);
            }
            if (ImGui.selectable(part.name(), isSelected)) {
                if (ImGui.getIO().getKeyCtrl()) {
                    partManager.togglePartSelection(part.id());
                } else {
                    partManager.deselectAllParts();
                    partManager.selectPart(part.id());
                }
                if (onOpenPartTransformSlideout != null) {
                    onOpenPartTransformSlideout.run();
                }
            }
            if (isHidden) {
                ImGui.popStyleColor();
            }

            // Right-click context menu
            if (ImGui.beginPopupContextItem()) {
                if (ImGui.menuItem("Duplicate")) {
                    partManager.duplicatePart(part.id());
                    invalidateViewport();
                }
                if (parts.size() > 1) {
                    if (ImGui.menuItem("Delete")) {
                        partManager.removePart(part.id());
                        invalidateViewport();
                    }
                } else {
                    ImGui.textDisabled("Cannot delete last part");
                }
                ImGui.endPopup();
            }

            ImGui.popID();
        }
    }

    /**
     * Fire viewport invalidation callback if set.
     */
    private void invalidateViewport() {
        if (onViewportInvalidationNeeded != null) {
            onViewportInvalidationNeeded.run();
        }
    }

    // ========== Callbacks ==========

    /**
     * Callback invoked when user confirms adding a new part (from slideout or other trigger).
     */
    public void onPartAdded(PartShapeFactory.Shape shape, String name) {
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

        // Offset new part so it doesn't overlap existing geometry at origin
        if (newPart != null && partManager.getPartCount() > 1) {
            float offset = partManager.getPartCount() * 1.5f;
            PartTransform offsetTransform = new PartTransform(
                    new Vector3f(0, 0, 0),
                    new Vector3f(offset, 0, 0),
                    new Vector3f(0, 0, 0),
                    new Vector3f(1, 1, 1)
            );
            partManager.setPartTransform(newPart.id(), offsetTransform);
            // Re-fetch after transform update
            newPart = partManager.getPartById(newPart.id()).orElse(newPart);
        }

        // Notify callback to set up default material for the new part's faces
        if (onPartCreated != null && newPart != null) {
            onPartCreated.accept(newPart);
        }

        // Invalidate viewport so the new part renders immediately
        invalidateViewport();

        logger.info("Added {} part '{}'", shape.getDisplayName(), name);
    }
}
