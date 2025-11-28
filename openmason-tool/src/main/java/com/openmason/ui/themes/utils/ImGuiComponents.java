package com.openmason.ui.themes.utils;

import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;

import java.util.function.Consumer;

/**
 * Reusable ImGui UI component library for Open Mason.
 *
 * <p>Provides styled, theme-aware UI components for preferences windows, properties panels,
 * and other UI contexts throughout the application. This class serves as a complement to
 * {@link ImGuiHelpers} by providing higher-level UI building blocks, while ImGuiHelpers
 * focuses on infrastructure concerns (context management, fonts, cleanup).</p>
 *
 * <p><strong>Usage Contexts:</strong></p>
 * <ul>
 *   <li>Preferences Windows - Section headers, settings controls, layout management</li>
 *   <li>Properties Panels - Compact headers, input controls, visual separators</li>
 *   <li>Any UI requiring consistent styled components</li>
 * </ul>
 *
 * <p><strong>Integration with Theme System:</strong></p>
 * <p>This component library is positioned within the {@code themes/utils} package to enable
 * future integration with {@code ThemeManager} and {@code DensityManager}. While currently
 * using fixed colors and spacing, the architecture supports future enhancements to
 * dynamically pull accent colors and density-scaled spacing from the active theme.</p>
 *
 * <p><strong>Design Philosophy:</strong></p>
 * <ul>
 *   <li><strong>Single Responsibility:</strong> Provides UI components only - no business logic</li>
 *   <li><strong>Consistency:</strong> Ensures uniform styling across all Open Mason interfaces</li>
 *   <li><strong>Extensibility:</strong> Ready for theme integration and density-aware layouts</li>
 *   <li><strong>Simplicity:</strong> Static methods for ease of use and backward compatibility</li>
 * </ul>
 *
 * @see ImGuiHelpers
 * @see com.openmason.ui.themes.core.ThemeManager
 * @see com.openmason.ui.themes.density.DensityManager
 */
public class ImGuiComponents {

    // Private constructor to prevent instantiation (utility class)
    private ImGuiComponents() {
    }

    // ========================================
    // Section Headers
    // ========================================

    /**
     * Renders a prominent section header with blue accent border and background.
     *
     * <p>This header style is designed for major sections in preferences windows,
     * featuring a 4px bright blue accent bar on the left, a dark gray-blue background,
     * and a prominent separator line below.</p>
     *
     * <p><strong>Visual Appearance:</strong></p>
     * <ul>
     *   <li>4px blue accent bar (left side)</li>
     *   <li>Dark gray-blue background box (sized to text)</li>
     *   <li>8px padding around text</li>
     *   <li>Prominent separator line below header</li>
     * </ul>
     *
     * @param title The section title text to display
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
     *
     * <p>This header style is optimized for properties panels and smaller UI contexts,
     * featuring a thinner 2px blue accent bar, smaller padding, and bold text rendering
     * achieved through double-pass rendering with slight offset.</p>
     *
     * <p><strong>Visual Appearance:</strong></p>
     * <ul>
     *   <li>2px blue accent bar (left side, thinner than section header)</li>
     *   <li>Dark gray-blue background extending to available width (max 250px)</li>
     *   <li>4px padding (smaller than section header)</li>
     *   <li>Bold text effect via double-pass rendering</li>
     * </ul>
     *
     * @param title The section title text to display
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
     *
     * <p>Used for sub-sections within larger sections. The bold effect is achieved
     * through double-pass rendering with a 0.5px offset, a common technique for
     * creating bold text when true bold fonts are unavailable.</p>
     *
     * <p><strong>Visual Appearance:</strong></p>
     * <ul>
     *   <li>Bold text via double-pass rendering (0.5px offset)</li>
     *   <li>No background or accent bar</li>
     *   <li>Standard spacing below</li>
     * </ul>
     *
     * @param title The sub-header text to display
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
     *
     * <p>Used below section headers and between major UI sections. Features a thicker
     * 2px line with moderate opacity gray color and 8px vertical spacing below.</p>
     *
     * <p><strong>Visual Appearance:</strong></p>
     * <ul>
     *   <li>2px thick line (thicker than standard separator)</li>
     *   <li>Gray color with 60% opacity</li>
     *   <li>Full width of available content region</li>
     *   <li>8px vertical spacing below</li>
     * </ul>
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
     *
     * <p>Used between individual settings or smaller UI sections. Features a thin 1px line
     * with subtle gray color and 4px vertical spacing below.</p>
     *
     * <p><strong>Visual Appearance:</strong></p>
     * <ul>
     *   <li>1px thin line (standard separator thickness)</li>
     *   <li>Gray color with 30% opacity (more subtle)</li>
     *   <li>Full width of available content region</li>
     *   <li>4px vertical spacing below</li>
     * </ul>
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
     *
     * <p>Standard pattern for numeric settings in preferences and properties panels.
     * Label appears above the slider with a "(?)" help icon that shows a tooltip on hover.
     * The callback is invoked whenever the slider value changes.</p>
     *
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * ImFloat fovValue = new ImFloat(90.0f);
     * ImGuiComponents.renderSliderSetting(
     *     "Field of View",
     *     "Adjusts the camera's field of view angle",
     *     fovValue,
     *     60.0f, 120.0f,
     *     "%.1f°",
     *     newValue -> preferences.setFov(newValue)
     * );
     * }</pre>
     *
     * @param label The setting label displayed above the slider
     * @param tooltip Help text shown when hovering over the "(?)" icon
     * @param value ImFloat reference to the current value
     * @param min Minimum slider value
     * @param max Maximum slider value
     * @param format Printf-style format string for value display (e.g., "%.2f", "%.0f%%")
     * @param onChanged Callback invoked with the new value when slider changes
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
     *
     * <p>Standard pattern for boolean settings in preferences and properties panels.
     * Checkbox appears on the left, followed by the label and a "(?)" help icon that
     * shows a tooltip on hover. The callback is invoked whenever the checkbox state changes.</p>
     *
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * ImBoolean enableVsync = new ImBoolean(true);
     * ImGuiComponents.renderCheckboxSetting(
     *     "Enable VSync",
     *     "Synchronizes rendering with monitor refresh rate",
     *     enableVsync,
     *     newValue -> preferences.setVsyncEnabled(newValue)
     * );
     * }</pre>
     *
     * @param label The setting label displayed next to the checkbox
     * @param tooltip Help text shown when hovering over the "(?)" icon
     * @param value ImBoolean reference to the current state
     * @param onChanged Callback invoked with the new state when checkbox changes
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
     *
     * <p>Standard pattern for enumerated/multi-choice settings in preferences and
     * properties panels. Label appears above the dropdown with a "(?)" help icon that
     * shows a tooltip on hover. The callback is invoked whenever the selection changes.</p>
     *
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * String[] qualityLevels = {"Low", "Medium", "High", "Ultra"};
     * ImInt selectedQuality = new ImInt(2); // "High"
     * ImGuiComponents.renderComboBoxSetting(
     *     "Graphics Quality",
     *     "Adjusts overall graphics quality preset",
     *     qualityLevels,
     *     selectedQuality,
     *     200.0f,
     *     newIndex -> preferences.setGraphicsQuality(qualityLevels[newIndex])
     * );
     * }</pre>
     *
     * @param label The setting label displayed above the dropdown
     * @param tooltip Help text shown when hovering over the "(?)" icon
     * @param items Array of string options to display in the dropdown
     * @param selected ImInt reference to the currently selected index
     * @param width Width of the combo box in pixels (0 for auto-width)
     * @param onChanged Callback invoked with the new selected index when selection changes
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
     *
     * <p>Standard button component with configurable size. Invokes the provided
     * callback when clicked.</p>
     *
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * ImGuiComponents.renderButton(
     *     "Apply Changes",
     *     150.0f, 0.0f,
     *     () -> applyPreferences()
     * );
     * }</pre>
     *
     * @param label The button label text
     * @param width Button width in pixels (0 for auto-width based on label)
     * @param height Button height in pixels (0 for default height)
     * @param onClick Callback invoked when the button is clicked
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
     *
     * <p>Inserts two spacing units (approximately 8-16px depending on UI scale).
     * Used to create visual separation between related settings groups.</p>
     */
    public static void addSpacing() {
        ImGui.spacing();
        ImGui.spacing();
    }

    /**
     * Adds a standard separator between major sections.
     *
     * <p>Inserts spacing, a horizontal separator line, and more spacing to create
     * clear visual separation between major UI sections. Uses ImGui's default
     * separator style.</p>
     */
    public static void addSectionSeparator() {
        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();
    }
}
