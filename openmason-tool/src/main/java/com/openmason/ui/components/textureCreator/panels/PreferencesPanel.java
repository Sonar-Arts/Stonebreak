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
 * Provides UI controls for adjusting rendering preferences like grid opacity
 * and quadrant overlay opacity.
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
     * Render preferences panel.
     *
     * @param preferences texture creator preferences
     */
    public void render(TextureCreatorPreferences preferences) {
        if (preferences == null) {
            ImGui.text("No preferences available");
            return;
        }

        // Sync ImGui state with preferences
        gridOpacitySlider.set(preferences.getGridOpacity());
        cubeNetOverlayOpacitySlider.set(preferences.getCubeNetOverlayOpacity());
        rotationSpeedSlider.set(preferences.getRotationSpeed());
        skipTransparentPixelsCheckbox.set(preferences.isSkipTransparentPixelsOnPaste());

        // === Grid Rendering Section ===
        if (ImGui.collapsingHeader("Grid Rendering")) {
            ImGui.spacing();
            ImGui.indent();

            // Grid Opacity Slider
            ImGui.text("Grid Opacity");
            ImGui.sameLine();
            ImGui.textDisabled("(?)");
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Controls the opacity of the grid overlay.\n" +
                                "Includes both minor grid lines (every pixel) and major lines (every 4th pixel).\n" +
                                "Grid only visible when zoomed in 3x or more.");
            }

            if (ImGui.sliderFloat("##gridOpacity", gridOpacitySlider.getData(),
                                 TextureCreatorPreferences.MIN_OPACITY,
                                 TextureCreatorPreferences.MAX_OPACITY,
                                 "%.2f")) {
                preferences.setGridOpacity(gridOpacitySlider.get());
            }

            ImGui.spacing();
            ImGui.unindent();
        }

        ImGui.spacing();

        // === Cube Net Overlay Section ===
        if (ImGui.collapsingHeader("Cube Net Overlay")) {
            ImGui.spacing();
            ImGui.indent();

            // Cube Net Overlay Opacity Slider
            ImGui.text("Overlay Opacity");
            ImGui.sameLine();
            ImGui.textDisabled("(?)");
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Controls opacity of face labels and boundaries for 64x48 cube net textures.\n" +
                                "Shows TOP, LEFT, FRONT, RIGHT, BACK, BOTTOM face regions.\n" +
                                "Only visible when editing 64x48 canvases.");
            }

            if (ImGui.sliderFloat("##cubeNetOverlayOpacity", cubeNetOverlayOpacitySlider.getData(),
                                 TextureCreatorPreferences.MIN_OPACITY,
                                 TextureCreatorPreferences.MAX_OPACITY,
                                 "%.2f")) {
                preferences.setCubeNetOverlayOpacity(cubeNetOverlayOpacitySlider.get());
            }

            ImGui.spacing();
            ImGui.unindent();
        }

        ImGui.spacing();

        // === Move Tool Settings Section ===
        if (ImGui.collapsingHeader("Move Tool")) {
            ImGui.spacing();
            ImGui.indent();

            // Rotation Speed Slider
            ImGui.text("Rotation Speed");
            ImGui.sameLine();
            ImGui.textDisabled("(?)");
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Controls rotation speed when using the move tool's rotate handle.\n" +
                                "Higher values = faster rotation.\n" +
                                "Default: 0.5 degrees per pixel of mouse movement");
            }

            if (ImGui.sliderFloat("##rotationSpeed", rotationSpeedSlider.getData(),
                                 TextureCreatorPreferences.MIN_ROTATION_SPEED,
                                 TextureCreatorPreferences.MAX_ROTATION_SPEED,
                                 "%.2f deg/px")) {
                preferences.setRotationSpeed(rotationSpeedSlider.get());
            }

            ImGui.spacing();
            ImGui.unindent();
        }

        ImGui.spacing();

        // === Copy/Paste Behavior Section ===
        if (ImGui.collapsingHeader("Copy/Paste Behavior")) {
            ImGui.spacing();
            ImGui.indent();

            // Skip Transparent Pixels Checkbox
            if (ImGui.checkbox("Skip Transparent Pixels on Paste/Move", skipTransparentPixelsCheckbox)) {
                preferences.setSkipTransparentPixelsOnPaste(skipTransparentPixelsCheckbox.get());
            }

            ImGui.sameLine();
            ImGui.textDisabled("(?)");
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("When enabled, fully transparent pixels (alpha = 0) won't overwrite existing pixels\n" +
                                "during paste or move operations. When disabled, transparent pixels will clear\n" +
                                "the destination, allowing you to erase with transparent selections.");
            }

            ImGui.spacing();
            ImGui.unindent();
        }

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        // === Reset to Defaults Button ===
        if (ImGui.button("Reset to Defaults", 150, 0)) {
            preferences.resetToDefaults();
            logger.info("Preferences reset to defaults");
        }

        ImGui.sameLine();
        ImGui.textDisabled("Restore all settings to their default values");
    }
}
