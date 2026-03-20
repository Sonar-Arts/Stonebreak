package com.openmason.main.systems.themes.utils;

import com.openmason.main.systems.themes.core.ThemeManager;
import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;

import java.util.function.Consumer;

/**
 * Reusable ImGui UI component library for Open Mason.
 *
 * <p>All colours are derived from the active ImGui theme so that components
 * render correctly across both light and dark themes.</p>
 */
public class ImGuiComponents {

    private ImGuiComponents() {
    }

    // ========================================
    // Theme-aware colour helpers
    // ========================================

    /**
     * Read a theme colour as a packed U32 suitable for ImDrawList calls.
     */
    private static int themeColorU32(int imGuiCol) {
        ImVec4 c = ImGui.getStyle().getColor(imGuiCol);
        return ImGui.colorConvertFloat4ToU32(c.x, c.y, c.z, c.w);
    }

    /**
     * Read a theme colour, override its alpha, and return as packed U32.
     */
    private static int themeColorU32(int imGuiCol, float alphaOverride) {
        ImVec4 c = ImGui.getStyle().getColor(imGuiCol);
        return ImGui.colorConvertFloat4ToU32(c.x, c.y, c.z, alphaOverride);
    }

    /**
     * Blend a theme colour towards black/white by a factor and return as U32.
     * factor > 0 lightens, factor < 0 darkens.
     */
    private static int themeColorShiftedU32(int imGuiCol, float factor) {
        ImVec4 c = ImGui.getStyle().getColor(imGuiCol);
        float r, g, b;
        if (factor > 0) {
            r = c.x + (1.0f - c.x) * factor;
            g = c.y + (1.0f - c.y) * factor;
            b = c.z + (1.0f - c.z) * factor;
        } else {
            float f = 1.0f + factor; // factor is negative, so this shrinks towards 0
            r = c.x * f;
            g = c.y * f;
            b = c.z * f;
        }
        return ImGui.colorConvertFloat4ToU32(
                Math.max(0, Math.min(1, r)),
                Math.max(0, Math.min(1, g)),
                Math.max(0, Math.min(1, b)),
                c.w);
    }

    // ========================================
    // Section Headers
    // ========================================

    /**
     * Renders a prominent section header with accent border and background.
     * Colours are pulled from the active theme.
     */
    public static void renderSectionHeader(String title) {
        imgui.ImVec2 cursorPos = ImGui.getCursorScreenPos();
        imgui.ImVec2 textSize = ImGui.calcTextSize(title);

        // Accent bar colour — derived from HeaderActive (our accent at full strength)
        int borderColor = themeColorU32(ImGuiCol.HeaderActive);

        // Background — slightly shifted from FrameBg for contrast
        int bgColor = themeColorShiftedU32(ImGuiCol.FrameBg, -0.15f);

        float borderWidth = 4.0f;
        float padding = 8.0f;
        float height = textSize.y + padding * 2;
        float boxWidth = textSize.x + padding * 2;

        imgui.ImDrawList drawList = ImGui.getWindowDrawList();

        // Left accent bar
        drawList.addRectFilled(
                cursorPos.x,
                cursorPos.y,
                cursorPos.x + borderWidth,
                cursorPos.y + height,
                borderColor,
                2.0f
        );

        // Background box
        drawList.addRectFilled(
                cursorPos.x + borderWidth,
                cursorPos.y,
                cursorPos.x + borderWidth + boxWidth,
                cursorPos.y + height,
                bgColor,
                2.0f
        );

        // Text
        ImGui.setCursorScreenPos(cursorPos.x + borderWidth + padding, cursorPos.y + padding);
        ImGui.text(title);

        // Reset cursor
        ImGui.setCursorScreenPos(cursorPos.x, cursorPos.y + height);
        ImGui.spacing();
        ImGui.spacing();

        renderBigSeparator();
    }

    /**
     * Renders a compact property panel header with bold-style text.
     * Colours are pulled from the active theme.
     */
    public static void renderCompactSectionHeader(String title) {
        imgui.ImVec2 cursorPos = ImGui.getCursorScreenPos();
        float availableWidth = ImGui.getContentRegionAvailX();
        imgui.ImVec2 textSize = ImGui.calcTextSize(title);

        int borderColor = themeColorU32(ImGuiCol.HeaderActive);
        int bgColor = themeColorShiftedU32(ImGuiCol.FrameBg, -0.15f);

        float borderWidth = 2.0f;
        float padding = 4.0f;
        float height = textSize.y + padding * 2;
        float maxWidth = 250.0f;
        float boxWidth = Math.min(availableWidth, maxWidth);

        imgui.ImDrawList drawList = ImGui.getWindowDrawList();

        // Left accent bar
        drawList.addRectFilled(
                cursorPos.x,
                cursorPos.y,
                cursorPos.x + borderWidth,
                cursorPos.y + height,
                borderColor,
                1.0f
        );

        // Background box
        drawList.addRectFilled(
                cursorPos.x + borderWidth,
                cursorPos.y,
                cursorPos.x + borderWidth + boxWidth,
                cursorPos.y + height,
                bgColor,
                1.0f
        );

        // Bold text (double-pass)
        float textX = cursorPos.x + borderWidth + padding;
        float textY = cursorPos.y + padding;

        ImGui.setCursorScreenPos(textX, textY);
        ImGui.text(title);

        ImGui.setCursorScreenPos(textX + 0.5f, textY);
        ImGui.text(title);

        // Reset cursor
        ImGui.setCursorScreenPos(cursorPos.x, cursorPos.y + height);
        ImGui.spacing();
    }

    /**
     * Renders a sub-header with emphasized bold-style text.
     */
    public static void renderSubHeader(String title) {
        imgui.ImVec2 cursorPos = ImGui.getCursorScreenPos();

        ImGui.text(title);

        // Bold effect via slight offset
        ImGui.setCursorScreenPos(cursorPos.x + 0.5f, cursorPos.y);
        ImGui.text(title);

        ImGui.setCursorScreenPos(cursorPos.x, cursorPos.y);
        ImGui.dummy(ImGui.calcTextSize(title).x, ImGui.calcTextSize(title).y);

        ImGui.spacing();
    }

    // ========================================
    // Separators
    // ========================================

    /**
     * Renders a prominent separator line. Colour derived from theme Separator.
     */
    public static void renderBigSeparator() {
        imgui.ImVec2 cursorPos = ImGui.getCursorScreenPos();
        float availWidth = ImGui.getContentRegionAvailX();
        imgui.ImDrawList drawList = ImGui.getWindowDrawList();

        int separatorColor = themeColorU32(ImGuiCol.Separator, 0.6f);
        drawList.addLine(
                cursorPos.x,
                cursorPos.y,
                cursorPos.x + availWidth,
                cursorPos.y,
                separatorColor,
                2.0f
        );

        ImGui.dummy(0, 8.0f);
    }

    /**
     * Renders a subtle separator line for settings sections.
     */
    public static void renderSettingSeparator() {
        imgui.ImVec2 cursorPos = ImGui.getCursorScreenPos();
        float availWidth = ImGui.getContentRegionAvailX();
        imgui.ImDrawList drawList = ImGui.getWindowDrawList();

        int separatorColor = themeColorU32(ImGuiCol.Separator, 0.3f);
        drawList.addLine(
                cursorPos.x,
                cursorPos.y,
                cursorPos.x + availWidth,
                cursorPos.y,
                separatorColor,
                1.0f
        );

        ImGui.dummy(0, 4.0f);
    }

    // ========================================
    // Settings Controls
    // ========================================

    /**
     * Renders a labeled slider with tooltip and change callback.
     */
    public static void renderSliderSetting(String label, String tooltip, ImFloat value,
                                           float min, float max, String format,
                                           Consumer<Float> onChanged) {
        ImGui.text(label);
        ImGui.sameLine();
        ImGui.textDisabled("(?)");
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(tooltip);
        }

        if (ImGui.sliderFloat("##" + label, value.getData(), min, max, format)) {
            onChanged.accept(value.get());
        }

        ImGui.spacing();
    }

    /**
     * Renders a labeled checkbox with tooltip and change callback.
     */
    public static void renderCheckboxSetting(String label, String tooltip, ImBoolean value,
                                             Consumer<Boolean> onChanged) {
        if (ImGui.checkbox(label, value)) {
            onChanged.accept(value.get());
        }

        ImGui.sameLine();
        ImGui.textDisabled("(?)");
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(tooltip);
        }

        ImGui.spacing();
    }

    /**
     * Renders a labeled combo box (dropdown) with tooltip and change callback.
     */
    public static void renderComboBoxSetting(String label, String tooltip, String[] items,
                                             ImInt selected, float width,
                                             Consumer<Integer> onChanged) {
        ImGui.text(label);
        ImGui.sameLine();
        ImGui.textDisabled("(?)");
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(tooltip);
        }

        if (width > 0) {
            ImGui.setNextItemWidth(width);
        }

        if (ImGui.combo("##" + label, selected, items)) {
            onChanged.accept(selected.get());
        }

        ImGui.spacing();
    }

    /**
     * Renders a button with consistent styling.
     */
    public static void renderButton(String label, float width, float height, Runnable onClick) {
        if (ImGui.button(label, width, height)) {
            onClick.run();
        }
    }

    // ========================================
    // Layout Helpers
    // ========================================

    /**
     * Adds standard spacing between settings or sections.
     */
    public static void addSpacing() {
        ImGui.spacing();
        ImGui.spacing();
    }

    /**
     * Adds a standard separator between major sections.
     */
    public static void addSectionSeparator() {
        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();
    }
}
