package com.openmason.main.systems.menus.textureCreator.palette;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiMouseButton;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Reusable width-wrapping swatch grid for a {@link PaletteModel}. Left-click
 * picks a swatch, right-click opens a replace/remove context menu, and a
 * trailing "+" adds the current color. Used by both the bottom palette strip
 * and the color panel's Palette section.
 */
public final class PaletteSwatchGrid {

    private static final float SWATCH_SPACING = 3.0f;
    private static final int BORDER_COLOR = 0xFF000000;
    private static final int SELECTED_RING_COLOR = 0xFFFFFFFF;

    private final float swatchSize;

    public PaletteSwatchGrid(float swatchSize) {
        this.swatchSize = swatchSize;
    }

    /**
     * Render the grid wrapped to the available width.
     *
     * @param model                the palette to display and mutate
     * @param currentColorSupplier source for "+" and "replace with current"
     * @param onSwatchPicked       receives the packed RGBA color on left-click
     */
    public void render(PaletteModel model, IntSupplier currentColorSupplier, IntConsumer onSwatchPicked) {
        ImDrawList drawList = ImGui.getWindowDrawList();
        float availWidth = ImGui.getContentRegionAvailX();
        int perRow = Math.max(1, (int) ((availWidth + SWATCH_SPACING) / (swatchSize + SWATCH_SPACING)));

        for (int i = 0; i < model.size(); i++) {
            if (i % perRow != 0) {
                ImGui.sameLine(0, SWATCH_SPACING);
            }
            renderSwatch(drawList, model, i, currentColorSupplier, onSwatchPicked);
        }

        if (model.size() % perRow != 0) {
            ImGui.sameLine(0, SWATCH_SPACING);
        }
        renderAddButton(drawList, model, currentColorSupplier);
    }

    private void renderSwatch(ImDrawList drawList, PaletteModel model, int index,
                              IntSupplier currentColorSupplier, IntConsumer onSwatchPicked) {
        int color = model.getColor(index);
        ImGui.pushID(index);
        ImVec2 pos = ImGui.getCursorScreenPos();
        ImGui.invisibleButton("##swatch", swatchSize, swatchSize);

        boolean hovered = ImGui.isItemHovered();
        if (ImGui.isItemClicked(ImGuiMouseButton.Left)) {
            model.select(index);
            onSwatchPicked.accept(color);
        }

        // Alpha checkerboard backing for translucent swatches
        if ((color >>> 24) < 0xFF) {
            drawCheckerboard(drawList, pos.x, pos.y, swatchSize);
        }
        drawList.addRectFilled(pos.x, pos.y, pos.x + swatchSize, pos.y + swatchSize, color);
        drawList.addRect(pos.x, pos.y, pos.x + swatchSize, pos.y + swatchSize, BORDER_COLOR);

        if (index == model.getSelectedIndex()) {
            drawList.addRect(pos.x - 1, pos.y - 1,
                    pos.x + swatchSize + 1, pos.y + swatchSize + 1,
                    SELECTED_RING_COLOR, 0, 0, 2.0f);
        } else if (hovered) {
            drawList.addRect(pos.x, pos.y, pos.x + swatchSize, pos.y + swatchSize,
                    0xFFCCCCCC, 0, 0, 1.5f);
        }

        if (ImGui.beginPopupContextItem("##swatch_ctx")) {
            if (ImGui.menuItem("Replace with current color")) {
                model.replace(index, currentColorSupplier.getAsInt());
            }
            if (ImGui.menuItem("Remove")) {
                model.remove(index);
            }
            ImGui.endPopup();
        }
        ImGui.popID();
    }

    private void renderAddButton(ImDrawList drawList, PaletteModel model, IntSupplier currentColorSupplier) {
        ImVec2 pos = ImGui.getCursorScreenPos();
        ImGui.invisibleButton("##palette_add", swatchSize, swatchSize);

        boolean hovered = ImGui.isItemHovered();
        int border = hovered ? 0xFFCCCCCC : 0xFF777777;
        drawList.addRect(pos.x, pos.y, pos.x + swatchSize, pos.y + swatchSize, border);

        // "+" glyph
        float cx = pos.x + swatchSize / 2f;
        float cy = pos.y + swatchSize / 2f;
        float arm = swatchSize * 0.25f;
        drawList.addLine(cx - arm, cy, cx + arm, cy, border, 1.5f);
        drawList.addLine(cx, cy - arm, cx, cy + arm, border, 1.5f);

        if (ImGui.isItemClicked(ImGuiMouseButton.Left)) {
            model.add(currentColorSupplier.getAsInt());
        }
        if (hovered) {
            ImGui.setTooltip("Add current color to palette");
        }
    }

    private static void drawCheckerboard(ImDrawList drawList, float x, float y, float size) {
        float half = size / 2f;
        int light = 0xFFAAAAAA;
        int dark = 0xFF666666;
        drawList.addRectFilled(x, y, x + half, y + half, light);
        drawList.addRectFilled(x + half, y, x + size, y + half, dark);
        drawList.addRectFilled(x, y + half, x + half, y + size, dark);
        drawList.addRectFilled(x + half, y + half, x + size, y + size, light);
    }
}
