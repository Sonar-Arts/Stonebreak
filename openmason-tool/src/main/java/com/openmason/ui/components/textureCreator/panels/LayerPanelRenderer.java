package com.openmason.ui.components.textureCreator.panels;

import com.openmason.ui.components.textureCreator.layers.Layer;
import com.openmason.ui.components.textureCreator.layers.LayerManager;
import imgui.ImGui;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImString;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer panel renderer - displays layer list with controls.
 *
 * Follows SOLID principles - Single Responsibility: renders layer UI only.
 *
 * @author Open Mason Team
 */
public class LayerPanelRenderer {

    private static final Logger logger = LoggerFactory.getLogger(LayerPanelRenderer.class);

    // UI state for layer renaming
    private int renamingLayerIndex = -1;
    private final ImString renameBuffer = new ImString(256);

    /**
     * Create layer panel renderer.
     */
    public LayerPanelRenderer() {
        logger.debug("Layer panel renderer created");
    }

    /**
     * Render the layers panel.
     *
     * @param layerManager layer manager instance
     */
    public void render(LayerManager layerManager) {
        if (layerManager == null) {
            ImGui.text("No layer manager");
            return;
        }

        ImGui.beginChild("##layers_panel", 0, 0, false);

        // Header with add/remove buttons
        renderLayerControls(layerManager);

        ImGui.separator();

        // Layer list
        renderLayerList(layerManager);

        ImGui.endChild();
    }

    /**
     * Render layer control buttons (Add, Remove, Duplicate).
     *
     * @param layerManager layer manager
     */
    private void renderLayerControls(LayerManager layerManager) {
        // Add layer button
        if (ImGui.button("Add Layer")) {
            int layerCount = layerManager.getLayerCount();
            layerManager.addLayer("Layer " + (layerCount + 1));
        }

        ImGui.sameLine();

        // Remove layer button (disabled if only one layer)
        boolean canRemove = layerManager.getLayerCount() > 1;
        if (!canRemove) {
            ImGui.beginDisabled();
        }

        if (ImGui.button("Remove")) {
            int activeIndex = layerManager.getActiveLayerIndex();
            if (activeIndex >= 0 && canRemove) {
                layerManager.removeLayer(activeIndex);
            }
        }

        if (!canRemove) {
            ImGui.endDisabled();
        }

        ImGui.sameLine();

        // Duplicate layer button
        if (ImGui.button("Duplicate")) {
            int activeIndex = layerManager.getActiveLayerIndex();
            if (activeIndex >= 0) {
                layerManager.duplicateLayer(activeIndex);
            }
        }
    }

    /**
     * Render list of layers.
     *
     * @param layerManager layer manager
     */
    private void renderLayerList(LayerManager layerManager) {
        int layerCount = layerManager.getLayerCount();
        int activeIndex = layerManager.getActiveLayerIndex();

        // Render layers from top to bottom (reverse order for display)
        for (int i = layerCount - 1; i >= 0; i--) {
            Layer layer = layerManager.getLayer(i);
            boolean isActive = (i == activeIndex);

            renderLayerItem(layerManager, layer, i, isActive);
        }
    }

    /**
     * Render a single layer item.
     *
     * @param layerManager layer manager
     * @param layer layer to render
     * @param index layer index
     * @param isActive whether this is the active layer
     */
    private void renderLayerItem(LayerManager layerManager, Layer layer, int index, boolean isActive) {
        ImGui.pushID(index);

        // Background highlight for active layer
        if (isActive) {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.ChildBg, 0.3f, 0.5f, 0.7f, 0.3f);
        }

        ImGui.beginChild("##layer_" + index, 0, 80, true);

        // Layer name (clickable to make active)
        if (renamingLayerIndex == index) {
            // Renaming mode
            if (ImGui.inputText("##rename", renameBuffer, imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue)) {
                layerManager.renameLayer(index, renameBuffer.get());
                renamingLayerIndex = -1;
            }

            // Cancel rename on escape
            if (ImGui.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
                renamingLayerIndex = -1;
            }
        } else {
            // Normal display
            boolean clicked = ImGui.selectable(layer.getName(), isActive);
            if (clicked) {
                layerManager.setActiveLayer(index);
            }

            // Double-click to rename
            if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) {
                renamingLayerIndex = index;
                renameBuffer.set(layer.getName());
            }
        }

        // Visibility toggle
        ImBoolean visibleBool = new ImBoolean(layer.isVisible());
        if (ImGui.checkbox("Visible##" + index, visibleBool)) {
            layerManager.setLayerVisibility(index, visibleBool.get());
        }

        // Opacity slider
        ImFloat opacityFloat = new ImFloat(layer.getOpacity());
        ImGui.pushItemWidth(150);
        if (ImGui.sliderFloat("Opacity##" + index, opacityFloat.getData(), 0.0f, 1.0f, "%.2f")) {
            layerManager.setLayerOpacity(index, opacityFloat.get());
        }
        ImGui.popItemWidth();

        // Layer reordering buttons
        ImGui.sameLine();
        renderLayerOrderButtons(layerManager, index);

        ImGui.endChild();

        if (isActive) {
            ImGui.popStyleColor();
        }

        ImGui.popID();
    }

    /**
     * Render layer order buttons (move up/down).
     *
     * @param layerManager layer manager
     * @param index current layer index
     */
    private void renderLayerOrderButtons(LayerManager layerManager, int index) {
        int layerCount = layerManager.getLayerCount();

        // Move up button (towards higher index)
        boolean canMoveUp = index < layerCount - 1;
        if (!canMoveUp) {
            ImGui.beginDisabled();
        }

        if (ImGui.button("^##up_" + index)) {
            layerManager.moveLayer(index, index + 1);
        }

        if (!canMoveUp) {
            ImGui.endDisabled();
        }

        ImGui.sameLine();

        // Move down button (towards lower index)
        boolean canMoveDown = index > 0;
        if (!canMoveDown) {
            ImGui.beginDisabled();
        }

        if (ImGui.button("v##down_" + index)) {
            layerManager.moveLayer(index, index - 1);
        }

        if (!canMoveDown) {
            ImGui.endDisabled();
        }

        // Tooltip for layer ordering
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Move layer down in stack");
        }
    }
}
