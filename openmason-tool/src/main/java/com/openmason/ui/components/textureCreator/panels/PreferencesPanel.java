package com.openmason.ui.components.textureCreator.panels;

import com.openmason.ui.components.textureCreator.TextureCreatorPreferences;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Preferences panel for texture creator settings.
 *
 * Reorganized with KISS, YAGNI, DRY, and SOLID principles:
 * - Simple 2-section structure (Canvas & Overlays, Tool Behavior)
 * - Always-expanded layout for easy access
 * - Indented sub-settings for visual hierarchy
 * - DRY helper methods for consistent UI patterns
 *
 * Follows SOLID principles:
 * - Single Responsibility: Only handles preferences UI rendering
 * - Delegates state management to TextureCreatorPreferences
 *
 * @author Open Mason Team
 */
public class PreferencesPanel {

    private static final Logger logger = LoggerFactory.getLogger(PreferencesPanel.class);

    // ImGui state holders for sliders and checkboxes (required by ImGui API)
    private final ImFloat gridOpacitySlider = new ImFloat();
    private final ImFloat cubeNetOverlayOpacitySlider = new ImFloat();
    private final ImFloat rotationSpeedSlider = new ImFloat();
    private final ImBoolean skipTransparentPixelsCheckbox = new ImBoolean();

    /**
     * Create preferences panel.
     */
    public PreferencesPanel() {
        logger.debug("Preferences panel created");
    }

    /**
     * Render preferences panel with reorganized structure.
     *
     * @param preferences texture creator preferences
     */
    public void render(TextureCreatorPreferences preferences) {
        if (preferences == null) {
            ImGui.text("No preferences available");
            return;
        }

        // Sync ImGui state with preferences
        syncStateFromPreferences(preferences);

        // Section 1: Grid Overlay
        renderSectionHeader("Grid Overlay");
        ImGui.indent();
        renderGridSettings(preferences);
        ImGui.unindent();

        ImGui.spacing();
        ImGui.spacing();

        // Section 2: Cube Net Reference (64x48)
        renderSectionHeader("Cube Net Reference (64x48)");
        ImGui.indent();
        renderCubeNetSettings(preferences);
        ImGui.unindent();

        ImGui.spacing();
        ImGui.spacing();

        // Section 3: Tool Behavior
        renderSectionHeader("Tool Behavior");
        ImGui.indent();
        renderMoveToolSettings(preferences);
        renderPasteMoveSettings(preferences);
        ImGui.unindent();

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // Reset button
        renderResetButton(preferences);
    }

    // ========================================
    // Section Renderers
    // ========================================

    /**
     * Render grid overlay settings.
     */
    private void renderGridSettings(TextureCreatorPreferences preferences) {
        renderSliderSetting(
            "Grid Opacity",
            "Controls the opacity of the pixel grid overlay (toggleable with 'G' key).\n" +
            "Includes both minor grid lines (every pixel) and major lines (every 4th pixel).\n" +
            "Grid only visible when zoomed in 3x or more.",
            gridOpacitySlider,
            TextureCreatorPreferences.MIN_OPACITY,
            TextureCreatorPreferences.MAX_OPACITY,
            "%.2f",
            preferences::setGridOpacity
        );

        ImGui.spacing();
    }

    /**
     * Render cube net overlay settings.
     */
    private void renderCubeNetSettings(TextureCreatorPreferences preferences) {
        renderSliderSetting(
            "Reference Opacity",
            "Controls opacity of the cube-net reference overlay (independent of grid).\n" +
            "Shows face labels (TOP, LEFT, FRONT, RIGHT, BACK, BOTTOM) and boundaries.\n" +
            "Always visible under pixels, renders over grid when enabled.\n" +
            "Only appears when editing 64x48 canvases.",
            cubeNetOverlayOpacitySlider,
            TextureCreatorPreferences.MIN_OPACITY,
            TextureCreatorPreferences.MAX_OPACITY,
            "%.2f",
            preferences::setCubeNetOverlayOpacity
        );

        ImGui.spacing();
    }

    /**
     * Render move tool settings.
     */
    private void renderMoveToolSettings(TextureCreatorPreferences preferences) {
        renderSubHeader("Move Tool");
        renderSettingSeparator();
        ImGui.indent();

        renderSliderSetting(
            "Rotation Speed",
            "Controls rotation speed when using the move tool's rotate handle.\n" +
            "Higher values = faster rotation.\n" +
            "Default: 0.5 degrees per pixel of mouse movement",
            rotationSpeedSlider,
            TextureCreatorPreferences.MIN_ROTATION_SPEED,
            TextureCreatorPreferences.MAX_ROTATION_SPEED,
            "%.2f deg/px",
            preferences::setRotationSpeed
        );

        ImGui.unindent();
        ImGui.spacing();
    }

    /**
     * Render paste/move operation settings.
     */
    private void renderPasteMoveSettings(TextureCreatorPreferences preferences) {
        renderSubHeader("Paste/Move Operations");
        renderSettingSeparator();
        ImGui.indent();

        renderCheckboxSetting(
            "Skip Transparent Pixels",
            "When enabled, fully transparent pixels (alpha = 0) won't overwrite existing pixels\n" +
            "during paste or move operations. When disabled, transparent pixels will clear\n" +
            "the destination, allowing you to erase with transparent selections.",
            skipTransparentPixelsCheckbox,
            preferences::setSkipTransparentPixelsOnPaste
        );

        ImGui.unindent();
    }

    /**
     * Render reset to defaults button.
     */
    private void renderResetButton(TextureCreatorPreferences preferences) {
        if (ImGui.button("Reset to Defaults", 150, 0)) {
            preferences.resetToDefaults();
            logger.info("Preferences reset to defaults");
        }
    }

    // ========================================
    // DRY Helper Methods
    // ========================================

    /**
     * Render a section header with visible blue colored border and compact darker gray-blue background.
     * DRY: Consistent section header styling across all sections.
     *
     * @param title section title
     */
    private void renderSectionHeader(String title) {
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
     * Render a prominent separator line.
     * DRY: Big separator between section headers and sub-headers.
     */
    private void renderBigSeparator() {
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

        // Add more vertical spacing
        ImGui.dummy(0, 8.0f);
    }

    /**
     * Render a sub-header with emphasized styling.
     * DRY: Consistent sub-header styling for visual hierarchy.
     *
     * @param title sub-header title
     */
    private void renderSubHeader(String title) {
        // Simulate bold by rendering text twice with slight offset (common technique)
        imgui.ImVec2 cursorPos = ImGui.getCursorScreenPos();

        // Draw shadow/thickness effect by rendering text multiple times
        ImGui.text(title);

        // Draw again slightly offset to create bold effect
        ImGui.setCursorScreenPos(cursorPos.x + 0.5f, cursorPos.y);
        ImGui.text(title);

        // Reset cursor to after text
        ImGui.setCursorScreenPos(cursorPos.x, cursorPos.y);
        ImGui.dummy(ImGui.calcTextSize(title).x, ImGui.calcTextSize(title).y);

        ImGui.spacing();
    }

    /**
     * Render a clear separator between sub-header and settings.
     * DRY: Consistent separator styling for visual clarity.
     */
    private void renderSettingSeparator() {
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

    /**
     * Render a slider setting with label, tooltip, and value callback.
     * DRY: Reusable slider component with consistent styling.
     *
     * @param label setting label
     * @param tooltip help text for (?) hover
     * @param value ImFloat value holder
     * @param min minimum value
     * @param max maximum value
     * @param format slider format string
     * @param onChanged callback when value changes
     */
    private void renderSliderSetting(String label, String tooltip, ImFloat value,
                                     float min, float max, String format,
                                     java.util.function.Consumer<Float> onChanged) {
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
     * Render a checkbox setting with label, tooltip, and value callback.
     * DRY: Reusable checkbox component with consistent styling.
     *
     * @param label setting label
     * @param tooltip help text for (?) hover
     * @param value ImBoolean value holder
     * @param onChanged callback when value changes
     */
    private void renderCheckboxSetting(String label, String tooltip, ImBoolean value,
                                       java.util.function.Consumer<Boolean> onChanged) {
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
     * Sync ImGui state from preferences.
     * Keeps ImGui widgets in sync with underlying preference values.
     */
    private void syncStateFromPreferences(TextureCreatorPreferences preferences) {
        gridOpacitySlider.set(preferences.getGridOpacity());
        cubeNetOverlayOpacitySlider.set(preferences.getCubeNetOverlayOpacity());
        rotationSpeedSlider.set(preferences.getRotationSpeed());
        skipTransparentPixelsCheckbox.set(preferences.isSkipTransparentPixelsOnPaste());
    }
}
