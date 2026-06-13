package com.openmason.main.systems.menus.textureCreator.panels;

import com.openmason.main.systems.menus.textureCreator.commands.CommandHistory;
import com.openmason.main.systems.menus.textureCreator.commands.LayerCommand;
import com.openmason.main.systems.menus.textureCreator.layers.Layer;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import com.openmason.main.systems.menus.textureCreator.keyboard.KeyCodeTranslator;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiDir;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer panel renderer - displays layer list with controls.
 */
public class LayerPanelRenderer {

    private static final Logger logger = LoggerFactory.getLogger(LayerPanelRenderer.class);

    /** Resolution the thumbnail is rendered/cached at (kept crisp). */
    private static final int THUMBNAIL_SIZE = 64;
    /** On-screen thumbnail size inside a layer card (smaller than the cache res). */
    private static final float THUMBNAIL_DISPLAY = 48f;
    /** Upper bound on a layer card's height so a single layer never dominates the pane. */
    private static final float MAX_LAYER_ITEM_HEIGHT = 92f;
    /** Upper bound on the content column width so it never stretches across a wide window. */
    private static final float MAX_PANEL_CONTENT_WIDTH = 300f;
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

        // Cap the content to a compact column so the toolbar, cards and sliders
        // never stretch across a wide/maximized window. Pin that column to the
        // right edge; any extra width is left as empty background on the left.
        float avail = ImGui.getContentRegionAvailX();
        float contentWidth = Math.min(avail, MAX_PANEL_CONTENT_WIDTH);
        if (avail > contentWidth) {
            ImGui.setCursorPosX(ImGui.getCursorPosX() + (avail - contentWidth));
        }
        ImGui.beginChild("##layers_panel", contentWidth, 0, false);

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
        // Three equal-width buttons spanning the panel width, so the toolbar
        // scales cleanly with the column instead of left-clumping.
        float spacing = ImGui.getStyle().getItemSpacingX();
        float btnWidth = (ImGui.getContentRegionAvailX() - spacing * 2f) / 3f;

        // Add layer
        if (ImGui.button("+ Add", btnWidth, 0)) {
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

        // Duplicate (disabled if there's no active layer)
        int activeIndex = layerManager.getActiveLayerIndex();
        boolean canDuplicate = activeIndex >= 0;
        if (!canDuplicate) {
            ImGui.beginDisabled();
        }
        if (ImGui.button("Duplicate", btnWidth, 0)) {
            if (commandHistory != null) {
                LayerCommand cmd = LayerCommand.duplicateLayer(layerManager, activeIndex);
                commandHistory.executeCommand(cmd);
                logger.debug("Executed duplicate layer command at index: {}", activeIndex);
            } else {
                layerManager.duplicateLayer(activeIndex);
            }
        }
        if (!canDuplicate) {
            ImGui.endDisabled();
        }

        ImGui.sameLine();

        // Delete (disabled if only one layer)
        boolean canRemove = layerManager.getLayerCount() > 1;
        if (!canRemove) {
            ImGui.beginDisabled();
        }
        if (ImGui.button("Delete", btnWidth, 0)) {
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

        ImGui.beginChild("##layer_" + index, 0, computeLayerItemHeight(), true);

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
        renderThumbnail(thumbnailTexture, THUMBNAIL_DISPLAY);

        ImGui.sameLine();

        // Vertical control stack to the right of the thumbnail:
        //   Row 1: name (fills) + eye toggle + reorder arrows
        //   Row 2: full-width opacity slider with inline % readout
        ImGui.beginGroup();

        float frameH = ImGui.getFrameHeight();
        float spacing = ImGui.getStyle().getItemSpacingX();
        // Reserve room on the right of row 1 for: eye + up + down (3 frame-sized
        // controls) and the gaps between them and the name.
        float rowControlsWidth = frameH * 3f + spacing * 3f;
        float nameWidth = Math.max(40f, ImGui.getContentRegionAvailX() - rowControlsWidth);

        // ---- Row 1: name / eye / reorder ----
        if (renamingLayerIndex == index) {
            ImGui.setNextItemWidth(nameWidth);
            if (ImGui.inputText("##rename", renameBuffer, imgui.flag.ImGuiInputTextFlags.EnterReturnsTrue)) {
                layerManager.renameLayer(index, renameBuffer.get());
                renamingLayerIndex = -1;
            }
            if (KeyCodeTranslator.isKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
                renamingLayerIndex = -1;
            }
        } else {
            boolean clicked = ImGui.selectable(layer.getName(), isActive, 0, nameWidth, frameH);
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

        ImGui.sameLine();
        renderEyeToggle(layerManager, layer, index, frameH);

        ImGui.sameLine();
        renderLayerOrderButtons(layerManager, index, commandHistory);

        // ---- Row 2: opacity slider (fills width, inline % readout) ----
        // Slider runs in percent space so the "%.0f%%" overlay reads correctly
        // (e.g. "100%"); the model stays in 0..1.
        float[] opacityPct = { layer.getOpacity() * 100f };
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
        if (ImGui.sliderFloat("##opacity_" + index, opacityPct, 0.0f, 100.0f, "%.0f%%")) {
            layerManager.setLayerOpacity(index, opacityPct[0] / 100f);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Layer opacity");
        }

        ImGui.endGroup();

        // Drag-drop target (handle drops on this layer)
        if (ImGui.beginDragDropTarget()) {
            Object payload = ImGui.acceptDragDropPayload(DRAG_DROP_PAYLOAD_TYPE);
            if (payload instanceof Integer) {
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
     * Compute the height of a single layer item so its contents are never
     * clipped, regardless of font size or UI density scaling.
     *
     * <p>The card lays the thumbnail beside a two-row control group (name + eye
     * + reorder arrows, then the opacity slider). The needed height is the
     * taller of the thumbnail and the two rows, plus the bordered child's
     * top/bottom padding. Derived from live style metrics so nothing clips when
     * the theme scales frame height up, and clamped to {@link
     * #MAX_LAYER_ITEM_HEIGHT} so a single card never balloons.</p>
     */
    private float computeLayerItemHeight() {
        // Two stacked rows in the control group: name row, opacity slider row.
        float controlsHeight = ImGui.getFrameHeightWithSpacing() * 2f;
        float contentHeight = Math.max(THUMBNAIL_DISPLAY, controlsHeight);
        float height = contentHeight + ImGui.getStyle().getWindowPaddingY() * 2f;
        return Math.min(height, MAX_LAYER_ITEM_HEIGHT);
    }

    /**
     * Render the visibility toggle as a compact, square eye icon: an iris ring
     * with a pupil when visible, a dim flat line ("closed eye") when hidden.
     * Drawn with the window draw list so it needs no icon-font glyph.
     *
     * @param layerManager layer manager
     * @param layer        layer being toggled
     * @param index        layer index
     * @param size         square hit/draw size (frame height)
     */
    private void renderEyeToggle(LayerManager layerManager, Layer layer, int index, float size) {
        boolean visible = layer.isVisible();
        ImVec2 pos = ImGui.getCursorScreenPos();

        if (ImGui.invisibleButton("##vis_" + index, size, size)) {
            layerManager.setLayerVisibility(index, !visible);
        }
        boolean hovered = ImGui.isItemHovered();
        if (hovered) {
            ImGui.setTooltip(visible ? "Hide layer" : "Show layer");
        }

        ImDrawList dl = ImGui.getWindowDrawList();
        float cx = pos.x + size * 0.5f;
        float cy = pos.y + size * 0.5f;
        float r = size * 0.28f;

        final int col;
        if (visible) {
            col = hovered
                ? ImGui.colorConvertFloat4ToU32(0.70f, 0.86f, 1.0f, 1.0f)
                : ImGui.colorConvertFloat4ToU32(0.52f, 0.76f, 1.0f, 1.0f);
            dl.addCircle(cx, cy, r, col, 20, 1.6f);
            dl.addCircleFilled(cx, cy, r * 0.42f, col, 12);
        } else {
            col = hovered
                ? ImGui.colorConvertFloat4ToU32(0.62f, 0.62f, 0.62f, 1.0f)
                : ImGui.colorConvertFloat4ToU32(0.42f, 0.42f, 0.42f, 1.0f);
            dl.addLine(cx - r, cy, cx + r, cy, col, 1.6f);
        }
    }

    /**
     * Render the up/down reorder controls as two square arrow buttons.
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
        if (ImGui.arrowButton("##up_" + index, ImGuiDir.Up)) {
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
        if (canMoveUp && ImGui.isItemHovered()) {
            ImGui.setTooltip("Move layer up");
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
        if (ImGui.arrowButton("##down_" + index, ImGuiDir.Down)) {
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
        if (canMoveDown && ImGui.isItemHovered()) {
            ImGui.setTooltip("Move layer down");
        }
        if (!canMoveDown) {
            ImGui.endDisabled();
        }
    }

    /**
     * Render thumbnail image with border at the given on-screen size.
     *
     * @param textureId OpenGL texture ID
     * @param size on-screen size in pixels
     */
    private void renderThumbnail(int textureId, float size) {
        ImVec2 cursorPos = ImGui.getCursorScreenPos();

        // Draw thumbnail image
        ImGui.image(textureId, size, size);

        // Draw border
        ImGui.getWindowDrawList().addRect(
            cursorPos.x, cursorPos.y,
            cursorPos.x + size, cursorPos.y + size,
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
