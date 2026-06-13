package com.openmason.main.systems.menus.textureCreator.panels.color;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.color.ColorUtils;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;

/**
 * Shared swatch drawing for the color panel: checkerboard alpha backing,
 * bordered color rectangle, hover tooltip, optional click action.
 * Packed RGBA ints match ImGui's draw-list packing, so no conversion needed.
 */
final class SwatchRenderer {

    private SwatchRenderer() {
    }

    /**
     * Render one swatch at the current cursor position.
     *
     * @param id      unique ImGui id for the invisible button
     * @param color   packed RGBA color
     * @param size    swatch edge length
     * @param onClick optional click action (null = display only)
     */
    static void render(String id, int color, float size, Runnable onClick) {
        ImVec2 cursorPos = ImGui.getCursorScreenPos();
        ImDrawList drawList = ImGui.getWindowDrawList();

        drawCheckerboard(drawList, cursorPos.x, cursorPos.y, size, size, 8);
        drawList.addRectFilled(cursorPos.x, cursorPos.y, cursorPos.x + size, cursorPos.y + size, color);
        drawList.addRect(cursorPos.x, cursorPos.y, cursorPos.x + size, cursorPos.y + size,
                0xFF666666, 0f, 0, 2.0f);

        ImGui.setCursorScreenPos(cursorPos.x, cursorPos.y);
        ImGui.invisibleButton(id, size, size);

        if (onClick != null && ImGui.isItemClicked(0)) {
            onClick.run();
        }

        if (ImGui.isItemHovered()) {
            int[] rgba = PixelCanvas.unpackRGBA(color);
            int alphaPercent = (int) ((rgba[3] / 255.0f) * 100);
            ImGui.setTooltip("#" + ColorUtils.toHexString(color)
                    + "\nAlpha: " + rgba[3] + " (" + alphaPercent + "%)");
        }
    }

    static void drawCheckerboard(ImDrawList drawList, float x, float y,
                                 float width, float height, float cellSize) {
        int lightColor = 0xFFCCCCCC;
        int darkColor = 0xFF999999;

        int cols = (int) Math.ceil(width / cellSize);
        int rows = (int) Math.ceil(height / cellSize);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int color = ((row + col) % 2 == 0) ? lightColor : darkColor;
                float x1 = x + col * cellSize;
                float y1 = y + row * cellSize;
                float x2 = Math.min(x1 + cellSize, x + width);
                float y2 = Math.min(y1 + cellSize, y + height);
                drawList.addRectFilled(x1, y1, x2, y2, color);
            }
        }
    }
}
