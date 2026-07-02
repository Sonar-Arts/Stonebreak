package com.openmason.main.systems.menus.textureCreator.panels.color;

import com.openmason.main.systems.menus.textureCreator.color.ColorUtils;
import imgui.ImDrawList;
import com.openmason.main.systems.menus.textureCreator.utils.SafeText;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImString;

/**
 * Hex input field and alpha slider for the color panel. The hex field is the
 * only place a textual color representation lives; it resyncs from
 * {@link ColorSelectionState} whenever the color changes elsewhere.
 */
public final class HexAlphaControls {

    private static final float SLIDER_WIDTH = 200f;
    private static final float SLIDER_HEIGHT = 28f;

    private final ImString hexInput = new ImString(16);
    private int lastSyncedColor = ~0;   // force initial sync
    private boolean hexFieldActive = false; // hex input active last frame

    public void renderAlphaSlider(ColorSelectionState state) {
        ImDrawList drawList = ImGui.getWindowDrawList();

        ImGui.text("Alpha:");
        ImGui.sameLine();
        ImGui.dummy(5, 0);
        ImGui.sameLine();

        int alpha = state.getAlpha();
        int alphaPercent = (int) ((alpha / 255.0f) * 100);
        SafeText.textDisabled(String.format("%3d (%3d%%)", alpha, alphaPercent));

        ImGui.sameLine();
        ImGui.dummy(10, 0);
        ImGui.sameLine();

        ImVec2 cursorPos = ImGui.getCursorScreenPos();
        float shadowOffset = 2f;

        drawList.addRectFilled(
                cursorPos.x + shadowOffset, cursorPos.y + shadowOffset,
                cursorPos.x + SLIDER_WIDTH + shadowOffset, cursorPos.y + SLIDER_HEIGHT + shadowOffset,
                0x30000000, 3.0f);

        SwatchRenderer.drawCheckerboard(drawList, cursorPos.x, cursorPos.y,
                SLIDER_WIDTH, SLIDER_HEIGHT, 8);

        // Transparent → opaque gradient of the current color
        int[] rgba = state.getRgba();
        int colorTransparent = (rgba[2] << 16) | (rgba[1] << 8) | rgba[0];
        int colorOpaque = 0xFF000000 | colorTransparent;
        drawList.addRectFilledMultiColor(
                cursorPos.x, cursorPos.y,
                cursorPos.x + SLIDER_WIDTH, cursorPos.y + SLIDER_HEIGHT,
                colorTransparent, colorOpaque, colorOpaque, colorTransparent);

        drawList.addRect(
                cursorPos.x, cursorPos.y,
                cursorPos.x + SLIDER_WIDTH, cursorPos.y + SLIDER_HEIGHT,
                0xFF666666, 3.0f, 0, 2.0f);

        RgbSliderGroup.drawSliderIndicator(drawList, cursorPos,
                alpha / 255.0f, SLIDER_WIDTH, SLIDER_HEIGHT);

        ImGui.setCursorScreenPos(cursorPos.x, cursorPos.y);
        ImGui.invisibleButton("##alpha_slider", SLIDER_WIDTH, SLIDER_HEIGHT);

        if (ImGui.isItemActive() && (ImGui.isMouseDragging(0, 0) || ImGui.isMouseClicked(0))) {
            float newX = ColorUtils.clamp(ImGui.getMousePosX() - cursorPos.x, 0, SLIDER_WIDTH);
            state.setAlpha(Math.round((newX / SLIDER_WIDTH) * 255.0f));
        }

        if (ImGui.isItemHovered()) {
            int percent = (int) ((state.getAlpha() / 255.0f) * 100);
            ImGui.setTooltip("Alpha = " + state.getAlpha() + " (" + percent + "%)");
        }
    }

    public void renderHexInput(ColorSelectionState state) {
        syncFromState(state);

        ImGui.text("Hex");
        ImGui.sameLine();
        ImGui.pushItemWidth(120);

        if (ImGui.inputText("##hex", hexInput,
                ImGuiInputTextFlags.CharsHexadecimal | ImGuiInputTextFlags.CharsUppercase)) {
            int parsed = ColorUtils.fromHexString(hexInput.get());
            state.setColor(parsed);
            lastSyncedColor = parsed;
        }
        hexFieldActive = ImGui.isItemActive();

        ImGui.popItemWidth();

        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Format: RRGGBBAA (e.g., FF8800FF)");
        }
    }

    /**
     * Refresh the hex text when the color was changed by another control —
     * but never while the user is typing in the hex field itself.
     */
    private void syncFromState(ColorSelectionState state) {
        int current = state.getCurrentColor();
        if (current != lastSyncedColor && !hexFieldActive) {
            hexInput.set(ColorUtils.toHexString(current));
            lastSyncedColor = current;
        }
    }
}
