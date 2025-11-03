package com.openmason.ui.components.textureCreator.panels;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.CommandHistory;
import com.openmason.ui.components.textureCreator.commands.LayerCommand;
import com.openmason.ui.components.textureCreator.layers.Layer;
import com.openmason.ui.components.textureCreator.layers.LayerManager;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
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

    private static final int THUMBNAIL_SIZE = 64;
    private static final String DRAG_DROP_PAYLOAD_TYPE = "LAYER_REORDER";

    // UI state for layer renaming
    private int renamingLayerIndex = -1;
    private final ImString renameBuffer = new ImString(256);

    // Thumbnail cache with version-based invalidation
    private final LayerThumbnailCache thumbnailCache = new LayerThumbnailCache();

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
     * @param commandHistory command history for undo/redo support
     */
    public void render(LayerManager layerManager, CommandHistory commandHistory) {
        if (layerManager == null) {
            ImGui.text("No layer manager");
            return;
        }

        ImGui.beginChild("##layers_panel", 0, 0, false);

        // Header with add/remove buttons
        renderLayerControls(layerManager, commandHistory);

        ImGui.separator();

        // Layer list
        renderLayerList(layerManager, commandHistory);

        ImGui.endChild();
    }

    /**
     * Render layer control buttons (Add, Remove, Duplicate).
     *
     * @param layerManager layer manager
     * @param commandHistory command history for undo support
     */
    private void renderLayerControls(LayerManager layerManager, CommandHistory commandHistory) {
        // Add layer button
        if (ImGui.button("Add Layer")) {
            int layerCount = layerManager.getLayerCount();
            String layerName = "Layer " + (layerCount + 1);

            if (commandHistory != null) {
                LayerCommand cmd = LayerCommand.addLayer(layerManager, layerName);
                commandHistory.executeCommand(cmd);
                logger.debug("Executed add layer command: {}", layerName);
            } else {
                layerManager.addLayer(layerName);
            }
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
                if (commandHistory != null) {
                    LayerCommand cmd = LayerCommand.removeLayer(layerManager, activeIndex);
                    commandHistory.executeCommand(cmd);
                    logger.debug("Executed remove layer command at index: {}", activeIndex);
                } else {
                    layerManager.removeLayer(activeIndex);
                }
                // Invalidate cache when layer is removed (indices change)
                thumbnailCache.invalidateAll();
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
                if (commandHistory != null) {
                    LayerCommand cmd = LayerCommand.duplicateLayer(layerManager, activeIndex);
                    commandHistory.executeCommand(cmd);
                    logger.debug("Executed duplicate layer command at index: {}", activeIndex);
                } else {
                    layerManager.duplicateLayer(activeIndex);
                }
            }
        }
    }

    /**
     * Render list of layers.
     *
     * @param layerManager layer manager
     * @param commandHistory command history for undo support
     */
    private void renderLayerList(LayerManager layerManager, CommandHistory commandHistory) {
        int layerCount = layerManager.getLayerCount();
        int activeIndex = layerManager.getActiveLayerIndex();

        // Render layers from top to bottom (reverse order for display)
        for (int i = layerCount - 1; i >= 0; i--) {
            Layer layer = layerManager.getLayer(i);
            boolean isActive = (i == activeIndex);

            renderLayerItem(layerManager, layer, i, isActive, commandHistory);
        }
    }

    /**
     * Render a single layer item.
     *
     * @param layerManager layer manager
     * @param layer layer to render
     * @param index layer index
     * @param isActive whether this is the active layer
     * @param commandHistory command history for undo support
     */
    private void renderLayerItem(LayerManager layerManager, Layer layer, int index, boolean isActive, CommandHistory commandHistory) {
        ImGui.pushID(index);

        // Apply visual polish styling
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 4.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8.0f, 8.0f);

        // Background highlight for active layer
        if (isActive) {
            ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.25f, 0.45f, 0.65f, 0.25f);
        }

        ImGui.beginChild("##layer_" + index, 0, 100, true);

        // Check if hovered for hover state
        boolean isHovered = ImGui.isWindowHovered();

        // Apply hover state background
        if (!isActive && isHovered) {
            ImVec2 min = ImGui.getWindowPos();
            ImVec2 max = new ImVec2(min.x + ImGui.getWindowWidth(), min.y + ImGui.getWindowHeight());
            ImGui.getWindowDrawList().addRectFilled(min.x, min.y, max.x, max.y,
                ImGui.colorConvertFloat4ToU32(0.5f, 0.5f, 0.5f, 0.15f));
        }

        // Get cached thumbnail (or generate if needed)
        int thumbnailTexture = thumbnailCache.getThumbnail(layer, index);
        renderThumbnail(thumbnailTexture);

        ImGui.sameLine();

        // Begin vertical layout for controls
        ImGui.beginGroup();

        // Layer name (clickable to make active)
        if (renamingLayerIndex == index) {
            // Renaming mode
            ImGui.pushItemWidth(150);
            if (ImGui.inputText("##rename", renameBuffer, imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue)) {
                layerManager.renameLayer(index, renameBuffer.get());
                renamingLayerIndex = -1;
            }
            ImGui.popItemWidth();

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

            // Drag-drop source must be called immediately after the item
            if (ImGui.beginDragDropSource()) {
                ImGui.setDragDropPayload(DRAG_DROP_PAYLOAD_TYPE, index, imgui.flag.ImGuiCond.Once);
                ImGui.text("Moving layer: " + layer.getName());
                ImGui.endDragDropSource();
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

        // Opacity label and slider on new line
        ImGui.text(String.format("Opacity: %d%%", (int)(layer.getOpacity() * 100)));

        ImGui.sameLine();

        // Draggable opacity slider
        ImFloat opacityFloat = new ImFloat(layer.getOpacity());
        ImGui.pushItemWidth(120);
        if (ImGui.sliderFloat("##opacity_" + index, opacityFloat.getData(), 0.0f, 1.0f, "%.2f")) {
            layerManager.setLayerOpacity(index, opacityFloat.get());
        }
        ImGui.popItemWidth();

        ImGui.sameLine();

        // Layer reordering buttons
        renderLayerOrderButtons(layerManager, index, commandHistory);

        ImGui.endGroup();

        // Drag-drop target (handle drops on this layer)
        if (ImGui.beginDragDropTarget()) {
            Object payload = ImGui.acceptDragDropPayload(DRAG_DROP_PAYLOAD_TYPE);
            if (payload != null && payload instanceof Integer) {
                int sourceIndex = (Integer) payload;
                if (sourceIndex != index) {
                    if (commandHistory != null) {
                        LayerCommand cmd = LayerCommand.moveLayer(layerManager, sourceIndex, index);
                        commandHistory.executeCommand(cmd);
                        logger.debug("Drag-drop layer: {} -> {}", sourceIndex, index);
                    } else {
                        layerManager.moveLayer(sourceIndex, index);
                    }
                    // Invalidate cache when layers are reordered (indices change)
                    thumbnailCache.invalidateAll();
                }
            }
            ImGui.endDragDropTarget();
        }

        // Context menu
        renderContextMenu(layerManager, index, commandHistory);

        ImGui.endChild();

        if (isActive) {
            ImGui.popStyleColor();
        }

        ImGui.popStyleVar(2);
        ImGui.popID();
    }

    /**
     * Render layer order buttons (move up/down).
     *
     * @param layerManager layer manager
     * @param index current layer index
     * @param commandHistory command history for undo support
     */
    private void renderLayerOrderButtons(LayerManager layerManager, int index, CommandHistory commandHistory) {
        int layerCount = layerManager.getLayerCount();

        // Move up button (towards higher index)
        boolean canMoveUp = index < layerCount - 1;
        if (!canMoveUp) {
            ImGui.beginDisabled();
        }

        if (ImGui.button("^##up_" + index)) {
            if (commandHistory != null) {
                LayerCommand cmd = LayerCommand.moveLayer(layerManager, index, index + 1);
                commandHistory.executeCommand(cmd);
                logger.debug("Executed move layer command: {} -> {}", index, index + 1);
            } else {
                layerManager.moveLayer(index, index + 1);
            }
            // Invalidate cache when layers are reordered
            thumbnailCache.invalidateAll();
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
            if (commandHistory != null) {
                LayerCommand cmd = LayerCommand.moveLayer(layerManager, index, index - 1);
                commandHistory.executeCommand(cmd);
                logger.debug("Executed move layer command: {} -> {}", index, index - 1);
            } else {
                layerManager.moveLayer(index, index - 1);
            }
            // Invalidate cache when layers are reordered
            thumbnailCache.invalidateAll();
        }

        if (!canMoveDown) {
            ImGui.endDisabled();
        }

        // Tooltip for layer ordering
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Move layer down in stack");
        }
    }

    /**
     * Render thumbnail image with border.
     *
     * @param textureId OpenGL texture ID
     */
    private void renderThumbnail(int textureId) {
        ImVec2 cursorPos = ImGui.getCursorScreenPos();

        // Draw thumbnail image
        ImGui.image(textureId, THUMBNAIL_SIZE, THUMBNAIL_SIZE);

        // Draw border
        ImGui.getWindowDrawList().addRect(
            cursorPos.x, cursorPos.y,
            cursorPos.x + THUMBNAIL_SIZE, cursorPos.y + THUMBNAIL_SIZE,
            ImGui.colorConvertFloat4ToU32(0.3f, 0.3f, 0.3f, 1.0f),
            0.0f, 0, 1.5f
        );
    }

    /**
     * Render right-click context menu for layer operations.
     *
     * @param layerManager layer manager
     * @param index layer index
     * @param commandHistory command history for undo support
     */
    private void renderContextMenu(LayerManager layerManager, int index, CommandHistory commandHistory) {
        if (ImGui.beginPopupContextItem("layer_context_" + index)) {
            Layer layer = layerManager.getLayer(index);

            // Rename
            if (ImGui.menuItem("Rename")) {
                renamingLayerIndex = index;
                renameBuffer.set(layer.getName());
            }

            // Duplicate
            if (ImGui.menuItem("Duplicate")) {
                if (commandHistory != null) {
                    LayerCommand cmd = LayerCommand.duplicateLayer(layerManager, index);
                    commandHistory.executeCommand(cmd);
                } else {
                    layerManager.duplicateLayer(index);
                }
            }

            // Delete (disabled if only one layer)
            boolean canDelete = layerManager.getLayerCount() > 1;
            if (!canDelete) {
                ImGui.beginDisabled();
            }
            if (ImGui.menuItem("Delete")) {
                if (canDelete) {
                    if (commandHistory != null) {
                        LayerCommand cmd = LayerCommand.removeLayer(layerManager, index);
                        commandHistory.executeCommand(cmd);
                    } else {
                        layerManager.removeLayer(index);
                    }
                    // Invalidate cache when layer is removed
                    thumbnailCache.invalidateAll();
                }
            }
            if (!canDelete) {
                ImGui.endDisabled();
            }

            ImGui.separator();

            // Toggle Visibility
            String visText = layer.isVisible() ? "Hide Layer" : "Show Layer";
            if (ImGui.menuItem(visText)) {
                layerManager.setLayerVisibility(index, !layer.isVisible());
            }

            // Reset Opacity
            if (ImGui.menuItem("Reset Opacity to 100%")) {
                layerManager.setLayerOpacity(index, 1.0f);
            }

            ImGui.endPopup();
        }
    }

    /**
     * Dispose of all resources.
     * Must be called when panel is no longer needed.
     */
    public void dispose() {
        thumbnailCache.cleanup();
        logger.debug("Layer panel renderer disposed");
    }
}
