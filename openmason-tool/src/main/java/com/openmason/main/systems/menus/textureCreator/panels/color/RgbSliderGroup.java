package com.openmason.main.systems.menus.textureCreator.panels.color;

import com.openmason.main.systems.menus.textureCreator.color.ColorUtils;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;

/**
 * Smart RGB sliders: each track shows the actual color range produced by
 * varying that channel while the other two stay fixed. Mutates
 * {@link ColorSelectionState} directly.
 */
public final class RgbSliderGroup {

    private static final float SLIDER_WIDTH = 200f;
    private static final float SLIDER_HEIGHT = 28f;

    public void render(ColorSelectionState state) {
        ImGui.text("Color");
        ImGui.spacing();
        ImGui.spacing();

        ImDrawList drawList = ImGui.getWindowDrawList();
        ImVec2 cursorStart = ImGui.getCursorScreenPos();

        float panelWidth = SLIDER_WIDTH + 80f;
        float panelHeight = (SLIDER_HEIGHT + 15f) * 3 + 20f;
        drawList.addRectFilled(
                cursorStart.x - 5, cursorStart.y - 5,
                cursorStart.x + panelWidth, cursorStart.y + panelHeight,
                0x18FFFFFF, 4.0f);

        int[] rgba = state.getRgba();

        renderChannelSlider(state, "R", 0, rgba);
        ImGui.spacing();
        ImGui.spacing();
        renderChannelSlider(state, "G", 1, rgba);
        ImGui.spacing();
        ImGui.spacing();
        renderChannelSlider(state, "B", 2, rgba);
    }

    private void renderChannelSlider(ColorSelectionState state, String label, int channel, int[] rgba) {
        ImDrawList drawList = ImGui.getWindowDrawList();

        ImGui.text(label + ":");
        ImGui.sameLine();
        ImGui.dummy(5, 0);
        ImGui.sameLine();
        ImGui.textDisabled(String.format("%3d", rgba[channel]));
        ImGui.sameLine();
        ImGui.dummy(10, 0);
        ImGui.sameLine();

        ImVec2 cursorPos = ImGui.getCursorScreenPos();
        float shadowOffset = 2f;

        drawList.addRectFilled(
                cursorPos.x + shadowOffset, cursorPos.y + shadowOffset,
                cursorPos.x + SLIDER_WIDTH + shadowOffset, cursorPos.y + SLIDER_HEIGHT + shadowOffset,
                0x30000000, 3.0f);

        // Track gradient: this channel 0 → 255 with the others fixed
        int[] gradient = channelGradient(channel, rgba);
        drawList.addRectFilledMultiColor(
                cursorPos.x, cursorPos.y,
                cursorPos.x + SLIDER_WIDTH, cursorPos.y + SLIDER_HEIGHT,
                gradient[0], gradient[1], gradient[1], gradient[0]);

        drawList.addRect(
                cursorPos.x, cursorPos.y,
                cursorPos.x + SLIDER_WIDTH, cursorPos.y + SLIDER_HEIGHT,
                0xFF666666, 3.0f, 0, 2.0f);

        drawSliderIndicator(drawList, cursorPos, rgba[channel] / 255.0f, SLIDER_WIDTH, SLIDER_HEIGHT);

        ImGui.setCursorScreenPos(cursorPos.x, cursorPos.y);
        ImGui.invisibleButton("##" + label + "_slider", SLIDER_WIDTH, SLIDER_HEIGHT);

        if (ImGui.isItemActive() && (ImGui.isMouseDragging(0, 0) || ImGui.isMouseClicked(0))) {
            float newX = ColorUtils.clamp(ImGui.getMousePosX() - cursorPos.x, 0, SLIDER_WIDTH);
            int newValue = Math.round((newX / SLIDER_WIDTH) * 255.0f);
            if (newValue != rgba[channel]) {
                int[] updated = {rgba[0], rgba[1], rgba[2]};
                updated[channel] = newValue;
                state.setRgb(updated[0], updated[1], updated[2]);
            }
        }

        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(label + " = " + rgba[channel]);
        }
    }

    /** [startColor, endColor] (ImGui packing) for the channel's track. */
    private static int[] channelGradient(int channel, int[] rgba) {
        int[] start = {rgba[0], rgba[1], rgba[2]};
        int[] end = {rgba[0], rgba[1], rgba[2]};
        start[channel] = 0;
        end[channel] = 255;
        int startColor = 0xFF000000 | (start[2] << 16) | (start[1] << 8) | start[0];
        int endColor = 0xFF000000 | (end[2] << 16) | (end[1] << 8) | end[0];
        return new int[]{startColor, endColor};
    }

    /** Vertical line + top/bottom triangles marking the slider position. */
    static void drawSliderIndicator(ImDrawList drawList, ImVec2 cursorPos,
                                    float normalized, float width, float height) {
        float indicatorX = cursorPos.x + normalized * width;
        float triangleSize = 5f;

        drawList.addLine(indicatorX, cursorPos.y, indicatorX, cursorPos.y + height, 0xFFFFFFFF, 2.5f);

        drawList.addTriangleFilled(
                indicatorX - triangleSize, cursorPos.y - triangleSize,
                indicatorX + triangleSize, cursorPos.y - triangleSize,
                indicatorX, cursorPos.y,
                0xFFFFFFFF);
        drawList.addTriangle(
                indicatorX - triangleSize, cursorPos.y - triangleSize,
                indicatorX + triangleSize, cursorPos.y - triangleSize,
                indicatorX, cursorPos.y,
                0xFF000000, 1.5f);

        drawList.addTriangleFilled(
                indicatorX - triangleSize, cursorPos.y + height + triangleSize,
                indicatorX + triangleSize, cursorPos.y + height + triangleSize,
                indicatorX, cursorPos.y + height,
                0xFFFFFFFF);
        drawList.addTriangle(
                indicatorX - triangleSize, cursorPos.y + height + triangleSize,
                indicatorX + triangleSize, cursorPos.y + height + triangleSize,
                indicatorX, cursorPos.y + height,
                0xFF000000, 1.5f);
    }
}
