package com.openmason.ui.preferences;

import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;

import java.util.function.Consumer;

/**
 * Provides DRY (Don't Repeat Yourself) helper methods for rendering preference UI components.
 * <p>
 * This class eliminates duplicate UI rendering code across preference pages by providing
 * reusable components with consistent styling.
 * </p>
 * <p>
 * All methods are static for simple, functional usage without instance management.
 * </p>
 */
public class PreferencesPageRenderer {

    // Private constructor to prevent instantiation (utility class)
    private PreferencesPageRenderer() {
    }

    // ========================================
    // Section Headers
    // ========================================

    /**
     * Renders a prominent section header with blue accent border and background.
     * <p>
     * Creates a visually distinct section header with:
     * - Bright blue left accent bar (4px width)
     * - Darker gray-blue background box
     * - Compact sizing (fits text with padding)
     * - Thick separator line below
     * </p>
     *
     * @param title the section title text
     */
    public static void renderSectionHeader(String title) {
        imgui.ImVec2 cursorPos = ImGui.getCursorScreenPos();
        imgui.ImVec2 textSize = ImGui.calcTextSize(title);

        // Visible blue accent color
        int borderColor = ImGui.colorConvertFloat4ToU32(0.26f, 0.59f, 0.98f, 1.0f); // Bright blue

        // Draw prominent left border accent
        float borderWidth = 4.0f;
        float padding = 8.0f;
        float height = textSize.y + padding * 2;
        float boxWidth = textSize.x + padding * 2; // Compact, sized to text

        imgui.ImDrawList drawList = ImGui.getWindowDrawList();

        // Left accent bar (bright blue)
        drawList.addRectFilled(
                cursorPos.x,
                cursorPos.y,
                cursorPos.x + borderWidth,
                cursorPos.y + height,
                borderColor,
                2.0f
        );

        // Compact background box (darker gray-blue)
        int bgColor = ImGui.colorConvertFloat4ToU32(0.25f, 0.28f, 0.35f, 1.0f);
        drawList.addRectFilled(
                cursorPos.x + borderWidth,
                cursorPos.y,
                cursorPos.x + borderWidth + boxWidth,
                cursorPos.y + height,
                bgColor,
                2.0f
        );

        // Render text with padding
        ImGui.setCursorScreenPos(cursorPos.x + borderWidth + padding, cursorPos.y + padding);
        ImGui.text(title);

        // Reset cursor for next element
        ImGui.setCursorScreenPos(cursorPos.x, cursorPos.y + height);
        ImGui.spacing();
        ImGui.spacing();

        // Draw big separator line below section header
        renderBigSeparator();
    }

    /**
     * Renders a compact property panel header with bold-style text.
     * <p>
     * Creates a sleek, professional header with:
     * - Smaller height (compact design)
     * - Longer blue background bar (extends to available width with max limit)
     * - Bold-style text (rendered with double-pass technique)
     * - Thin blue left accent (2px width)
     * - Minimal padding for space efficiency
     * - Maximum width constraint (250px) to prevent excessive stretching
     * </p>
     *
     * @param title the section title text
     */
    public static void renderCompactSectionHeader(String title) {
        imgui.ImVec2 cursorPos = ImGui.getCursorScreenPos();
        float availableWidth = ImGui.getContentRegionAvailX();

        imgui.ImVec2 textSize = ImGui.calcTextSize(title);

        // Visible blue accent color
        int borderColor = ImGui.colorConvertFloat4ToU32(0.26f, 0.59f, 0.98f, 1.0f); // Bright blue

        // Compact dimensions with size limit
        float borderWidth = 2.0f; // Thinner accent bar
        float padding = 4.0f; // Smaller padding
        float height = textSize.y + padding * 2; // Smaller height
        float maxWidth = 250.0f; // Maximum width constraint
        float boxWidth = Math.min(availableWidth, maxWidth); // Extends to available width but capped at maxWidth

        imgui.ImDrawList drawList = ImGui.getWindowDrawList();

        // Left accent bar (bright blue, thinner)
        drawList.addRectFilled(
                cursorPos.x,
                cursorPos.y,
                cursorPos.x + borderWidth,
                cursorPos.y + height,
                borderColor,
                1.0f // Less rounding
        );

        // Long background box (darker gray-blue, extends to available width with max limit)
        int bgColor = ImGui.colorConvertFloat4ToU32(0.25f, 0.28f, 0.35f, 1.0f);
        drawList.addRectFilled(
                cursorPos.x + borderWidth,
                cursorPos.y,
                cursorPos.x + borderWidth + boxWidth,
                cursorPos.y + height,
                bgColor,
                1.0f // Less rounding
        );

        // Render text with bold effect (double-pass rendering with slight offset)
        float textX = cursorPos.x + borderWidth + padding;
        float textY = cursorPos.y + padding;

        // First pass: normal position
        ImGui.setCursorScreenPos(textX, textY);
        ImGui.text(title);

        // Second pass: slight offset for bold effect
        ImGui.setCursorScreenPos(textX + 0.5f, textY);
        ImGui.text(title);

        // Reset cursor for next element
        ImGui.setCursorScreenPos(cursorPos.x, cursorPos.y + height);
        ImGui.spacing();
    }

    /**
     * Renders a sub-header with emphasized bold-style text.
     * <p>
     * Creates visual hierarchy within sections by rendering emphasized text
     * (simulates bold by drawing twice with slight offset).
     * </p>
     *
     * @param title the sub-header title text
     */
    public static void renderSubHeader(String title) {
        // Simulate bold by rendering text twice with slight offset (common technique)
        imgui.ImVec2 cursorPos = ImGui.getCursorScreenPos();

        // Draw text
        ImGui.text(title);

        // Draw again slightly offset to create bold effect
        ImGui.setCursorScreenPos(cursorPos.x + 0.5f, cursorPos.y);
        ImGui.text(title);

        // Reset cursor to after text
        ImGui.setCursorScreenPos(cursorPos.x, cursorPos.y);
        ImGui.dummy(ImGui.calcTextSize(title).x, ImGui.calcTextSize(title).y);

        ImGui.spacing();
    }

    // ========================================
    // Separators
    // ========================================

    /**
     * Renders a prominent separator line with accent color.
     * <p>
     * Used below section headers for clear visual separation.
     * Creates a 2px thick line with 8px vertical spacing.
     * </p>
     */
    public static void renderBigSeparator() {
        imgui.ImVec2 cursorPos = ImGui.getCursorScreenPos();
        float availWidth = ImGui.getContentRegionAvailX();
        imgui.ImDrawList drawList = ImGui.getWindowDrawList();

        // Draw thicker separator line with accent color
        int separatorColor = ImGui.colorConvertFloat4ToU32(0.5f, 0.5f, 0.55f, 0.6f);
        drawList.addLine(
                cursorPos.x,
                cursorPos.y,
                cursorPos.x + availWidth,
                cursorPos.y,
                separatorColor,
                2.0f // Thicker line (2px)
        );

        // Add vertical spacing
        ImGui.dummy(0, 8.0f);
    }

    /**
     * Renders a subtle separator line for settings sections.
     * <p>
     * Used between sub-header and settings for visual clarity.
     * Creates a 1px thin line with 4px vertical spacing.
     * </p>
     */
    public static void renderSettingSeparator() {
        imgui.ImVec2 cursorPos = ImGui.getCursorScreenPos();
        float availWidth = ImGui.getContentRegionAvailX();
        imgui.ImDrawList drawList = ImGui.getWindowDrawList();

        // Draw thin separator line with subtle color
        int separatorColor = ImGui.colorConvertFloat4ToU32(0.4f, 0.4f, 0.4f, 0.3f);
        drawList.addLine(
                cursorPos.x,
                cursorPos.y,
                cursorPos.x + availWidth,
                cursorPos.y,
                separatorColor,
                1.0f
        );

        // Add vertical spacing
        ImGui.dummy(0, 4.0f);
    }

    // ========================================
    // Settings Controls
    // ========================================

    /**
     * Renders a labeled slider with tooltip and change callback.
     * <p>
     * Creates a consistent slider control with:
     * - Label with (?) tooltip
     * - Slider widget with custom formatting
     * - Callback on value change
     * </p>
     *
     * @param label      the setting label text
     * @param tooltip    the tooltip text shown on (?) hover
     * @param value      the ImFloat value holder
     * @param min        minimum slider value
     * @param max        maximum slider value
     * @param format     slider display format (e.g., "%.2f", "%.2f deg/px")
     * @param onChanged  callback invoked when value changes
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
     * <p>
     * Creates a consistent checkbox control with:
     * - Checkbox widget
     * - Label with (?) tooltip on same line
     * - Callback on value change
     * </p>
     *
     * @param label      the setting label text
     * @param tooltip    the tooltip text shown on (?) hover
     * @param value      the ImBoolean value holder
     * @param onChanged  callback invoked when value changes
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
     * <p>
     * Creates a consistent dropdown control with:
     * - Label with (?) tooltip
     * - Combo box widget with items array
     * - Callback on selection change
     * </p>
     *
     * @param label      the setting label text
     * @param tooltip    the tooltip text shown on (?) hover
     * @param items      array of dropdown items
     * @param selected   the ImInt selected index holder
     * @param width      combo box width (0 for auto-width)
     * @param onChanged  callback invoked when selection changes
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
     * <p>
     * Creates a simple button with optional sizing and click callback.
     * </p>
     *
     * @param label     the button label text
     * @param width     button width (0 for auto-width)
     * @param height    button height (0 for auto-height)
     * @param onClick   callback invoked when button is clicked
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
