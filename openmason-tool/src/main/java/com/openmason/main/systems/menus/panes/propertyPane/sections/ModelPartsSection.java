package com.openmason.main.systems.menus.panes.propertyPane.sections;

import com.openmason.main.systems.menus.dialogs.AddPartDialog;
import com.openmason.main.systems.menus.panes.propertyPane.interfaces.IPanelSection;
import com.openmason.main.systems.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.main.systems.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.rendering.model.gmr.parts.PartShapeFactory;
import com.openmason.main.systems.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.main.systems.rendering.model.gmr.parts.PartTransform;
import com.openmason.main.systems.themes.utils.ImGuiComponents;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiSelectableFlags;
import imgui.type.ImFloat;
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

    // Per-part transform editing (ImFloat for ImGui drag widgets)
    private final ImFloat posX = new ImFloat(), posY = new ImFloat(), posZ = new ImFloat();
    private final ImFloat rotX = new ImFloat(), rotY = new ImFloat(), rotZ = new ImFloat();
    private final ImFloat sclX = new ImFloat(1), sclY = new ImFloat(1), sclZ = new ImFloat(1);

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

        // Selected part transform editor
        renderSelectedPartTransform();

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

    /**
     * Set callback to invalidate viewport sub-renderers after part changes.
     *
     * @param callback Viewport invalidation callback
     */
    public void setOnViewportInvalidationNeeded(Runnable callback) {
        this.onViewportInvalidationNeeded = callback;
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
                    invalidateViewport();
                }
            } else {
                ImGui.pushStyleColor(ImGuiCol.Text, 0.5f, 0.5f, 0.5f, 0.5f);
                if (ImGui.smallButton("H")) {
                    partManager.setPartVisible(part.id(), true);
                    invalidateViewport();
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
                    invalidateViewport();
                }
                if (ImGui.menuItem("Delete") && parts.size() > 1) {
                    partManager.removePart(part.id());
                    invalidateViewport();
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

    /**
     * Fire viewport invalidation callback if set.
     */
    private void invalidateViewport() {
        if (onViewportInvalidationNeeded != null) {
            onViewportInvalidationNeeded.run();
        }
    }

    // ========== Selected Part Transform ==========

    /**
     * Render position/rotation/scale controls for the selected part.
     * Reads from the part's PartTransform, writes back on change.
     */
    private void renderSelectedPartTransform() {
        java.util.Set<String> selectedIds = partManager.getSelectedPartIds();
        if (selectedIds.isEmpty()) {
            return;
        }

        String selectedId = selectedIds.iterator().next();
        ModelPartDescriptor part = partManager.getPartById(selectedId).orElse(null);
        if (part == null) {
            return;
        }

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();
        ImGuiComponents.renderCompactSectionHeader("Part Transform: " + part.name());
        ImGui.spacing();

        PartTransform t = part.transform();

        // Sync ImFloat values from current part transform
        posX.set(t.position().x); posY.set(t.position().y); posZ.set(t.position().z);
        rotX.set(t.rotation().x); rotY.set(t.rotation().y); rotZ.set(t.rotation().z);
        sclX.set(t.scale().x);   sclY.set(t.scale().y);   sclZ.set(t.scale().z);

        boolean changed = false;

        // Position
        ImGui.text("Position");
        ImGui.columns(3, "##part_pos_cols", false);
        ImGui.pushItemWidth(-1);
        if (ImGui.dragFloat("##ppx", posX.getData(), 0.01f)) changed = true;
        ImGui.nextColumn();
        if (ImGui.dragFloat("##ppy", posY.getData(), 0.01f)) changed = true;
        ImGui.nextColumn();
        if (ImGui.dragFloat("##ppz", posZ.getData(), 0.01f)) changed = true;
        ImGui.popItemWidth();
        ImGui.columns(1);

        // Rotation
        ImGui.text("Rotation");
        ImGui.columns(3, "##part_rot_cols", false);
        ImGui.pushItemWidth(-1);
        if (ImGui.dragFloat("##prx", rotX.getData(), 0.5f)) changed = true;
        ImGui.nextColumn();
        if (ImGui.dragFloat("##pry", rotY.getData(), 0.5f)) changed = true;
        ImGui.nextColumn();
        if (ImGui.dragFloat("##prz", rotZ.getData(), 0.5f)) changed = true;
        ImGui.popItemWidth();
        ImGui.columns(1);

        // Scale
        ImGui.text("Scale");
        ImGui.columns(3, "##part_scl_cols", false);
        ImGui.pushItemWidth(-1);
        if (ImGui.dragFloat("##psx", sclX.getData(), 0.01f)) changed = true;
        ImGui.nextColumn();
        if (ImGui.dragFloat("##psy", sclY.getData(), 0.01f)) changed = true;
        ImGui.nextColumn();
        if (ImGui.dragFloat("##psz", sclZ.getData(), 0.01f)) changed = true;
        ImGui.popItemWidth();
        ImGui.columns(1);

        // Apply changes back to part transform
        if (changed && !part.locked()) {
            PartTransform updated = new PartTransform(
                    new Vector3f(t.origin()),
                    new Vector3f(posX.get(), posY.get(), posZ.get()),
                    new Vector3f(rotX.get(), rotY.get(), rotZ.get()),
                    new Vector3f(sclX.get(), sclY.get(), sclZ.get())
            );
            partManager.setPartTransform(selectedId, updated);
            invalidateViewport();
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
