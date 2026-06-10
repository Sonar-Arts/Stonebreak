package com.openmason.main.systems.menus.textureCreator.panels;

import com.openmason.main.systems.menus.textureCreator.palette.PaletteModel;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiMouseButton;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Aseprite-style swatch palette strip (docked along the bottom of the texture
 * editor by the default layout). Left-click paints with a swatch; right-click
 * opens a context menu to replace/remove; the trailing "+" adds the current
 * color as a new swatch.
 */
public final class PaletteStripPanel {

    private static final float SWATCH_SIZE = 20.0f;
    private static final float SWATCH_SPACING = 3.0f;
    private static final int BORDER_COLOR = 0xFF000000;        // subtle dark outline
    private static final int SELECTED_RING_COLOR = 0xFFFFFFFF; // white ring on selection

    private final PaletteModel model;
    private final IntSupplier currentColorSupplier;
    private final IntConsumer onSwatchPicked;

    public PaletteStripPanel(PaletteModel model,
                             IntSupplier currentColorSupplier,
                             IntConsumer onSwatchPicked) {
        this.model = model;
        this.currentColorSupplier = currentColorSupplier;
        this.onSwatchPicked = onSwatchPicked;
    }

    public PaletteModel getModel() {
        return model;
    }

    public void render() {
        ImDrawList drawList = ImGui.getWindowDrawList();
        float availWidth = ImGui.getContentRegionAvailX();
        int perRow = Math.max(1, (int) ((availWidth + SWATCH_SPACING) / (SWATCH_SIZE + SWATCH_SPACING)));

        for (int i = 0; i < model.size(); i++) {
            if (i % perRow != 0) {
                ImGui.sameLine(0, SWATCH_SPACING);
            }
            renderSwatch(drawList, i);
        }

        if (model.size() % perRow != 0) {
            ImGui.sameLine(0, SWATCH_SPACING);
        }
        renderAddButton(drawList);
    }

    private void renderSwatch(ImDrawList drawList, int index) {
        int color = model.getColor(index);
        ImGui.pushID(index);
        ImVec2 pos = ImGui.getCursorScreenPos();
        ImGui.invisibleButton("##swatch", SWATCH_SIZE, SWATCH_SIZE);

        boolean hovered = ImGui.isItemHovered();
        if (ImGui.isItemClicked(ImGuiMouseButton.Left)) {
            model.select(index);
            onSwatchPicked.accept(color);
        }

        // Alpha checkerboard backing for translucent swatches
        if ((color >>> 24) < 0xFF) {
            drawCheckerboard(drawList, pos.x, pos.y, SWATCH_SIZE);
        }
        drawList.addRectFilled(pos.x, pos.y, pos.x + SWATCH_SIZE, pos.y + SWATCH_SIZE, color);
        drawList.addRect(pos.x, pos.y, pos.x + SWATCH_SIZE, pos.y + SWATCH_SIZE, BORDER_COLOR);

        if (index == model.getSelectedIndex()) {
            drawList.addRect(pos.x - 1, pos.y - 1,
                    pos.x + SWATCH_SIZE + 1, pos.y + SWATCH_SIZE + 1,
                    SELECTED_RING_COLOR, 0, 0, 2.0f);
        } else if (hovered) {
            drawList.addRect(pos.x, pos.y, pos.x + SWATCH_SIZE, pos.y + SWATCH_SIZE,
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

    private void renderAddButton(ImDrawList drawList) {
        ImVec2 pos = ImGui.getCursorScreenPos();
        ImGui.invisibleButton("##palette_add", SWATCH_SIZE, SWATCH_SIZE);

        boolean hovered = ImGui.isItemHovered();
        int border = hovered ? 0xFFCCCCCC : 0xFF777777;
        drawList.addRect(pos.x, pos.y, pos.x + SWATCH_SIZE, pos.y + SWATCH_SIZE, border);

        // "+" glyph
        float cx = pos.x + SWATCH_SIZE / 2f;
        float cy = pos.y + SWATCH_SIZE / 2f;
        float arm = SWATCH_SIZE * 0.25f;
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
