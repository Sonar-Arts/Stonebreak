package com.openmason.ui.preferences;

import com.openmason.ui.components.textureCreator.TextureCreatorImGui;
import com.openmason.ui.components.textureCreator.TextureCreatorPreferences;
import com.openmason.ui.properties.PropertyPanelImGui;
import com.openmason.ui.themes.application.DensityManager;
import com.openmason.ui.themes.core.ThemeDefinition;
import com.openmason.ui.themes.core.ThemeManager;
import com.openmason.ui.ViewportController;
import com.openmason.ui.viewport.util.SnappingUtil;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified preferences page renderer for all Open Mason tools.
 * <p>
 * Consolidates Model Viewer, Texture Editor, and Common preference pages into a single
 * KISS-compliant class following DRY principles.
 * </p>
 * <p>
 * Architecture:
 * - Single class with page-specific render methods
 * - Shared state synchronization logic
 * - Consistent reset button pattern
 * - Real-time updates for all settings
 * </p>
 */
public class UnifiedPreferencesPageRenderer {

    private static final Logger logger = LoggerFactory.getLogger(UnifiedPreferencesPageRenderer.class);

    // Model Viewer constants
    private static final float MIN_CAMERA_SENSITIVITY = 0.1f;
    private static final float MAX_CAMERA_SENSITIVITY = 10.0f;
    private static final float MIN_VERTEX_POINT_SIZE = 1.0f;
    private static final float MAX_VERTEX_POINT_SIZE = 15.0f;

    // Grid snapping increment options (based on STANDARD_BLOCK_SIZE = 1.0)
    // Ordered from coarse to fine, with recommended default (1/2 Block) providing
    // good visual alignment with the 1.0 unit grid (2 snaps per grid square)
    private static final String[] GRID_SNAPPING_INCREMENT_NAMES = {
        "1 Block (1.0)",        // Coarsest - 1 snap per grid square
        "1/2 Block (0.5)",      // Recommended default - 2 snaps per grid square
        "1/4 Block (0.25)",     // Fine - 4 snaps per grid square
        "1/8 Block (0.125)",    // Very fine - 8 snaps per grid square
        "1/16 Block (0.0625)"   // Ultra fine - 16 snaps per grid square
    };
    private static final float[] GRID_SNAPPING_INCREMENT_VALUES = {
        SnappingUtil.SNAP_FULL_BLOCK,      // 1.0
        SnappingUtil.SNAP_HALF_BLOCK,      // 0.5 (default)
        SnappingUtil.SNAP_QUARTER_BLOCK,   // 0.25
        0.125f,                             // 1/8 block
        0.0625f                             // 1/16 block
    };

    // Model Viewer ImGui state holders
    private final ImFloat cameraMouseSensitivity = new ImFloat();
    private final ImBoolean compactPropertiesMode = new ImBoolean();
    private final ImInt gridSnappingIncrementIndex = new ImInt();
    private final ImFloat vertexPointSize = new ImFloat();

    // Texture Editor ImGui state holders
    private final ImFloat gridOpacitySlider = new ImFloat();
    private final ImFloat cubeNetOverlayOpacitySlider = new ImFloat();
    private final ImFloat rotationSpeedSlider = new ImFloat();
    private final ImBoolean skipTransparentPixelsCheckbox = new ImBoolean();

    // Common ImGui state holders
    private final ImInt themeIndex = new ImInt();
    private final ImInt densityIndex = new ImInt();

    // Dependencies
    private final PreferencesManager preferencesManager;
    private final ThemeManager themeManager;
    private TextureCreatorImGui textureCreatorImGui; // Mutable - can be set after construction

    // Component references for real-time updates
    private final ViewportController viewport;
    private final PropertyPanelImGui propertyPanel;

    /**
     * Creates a new unified preferences page renderer.
     *
     * @param preferencesManager   the preferences manager for persistence
     * @param themeManager         the theme manager for appearance settings
     * @param textureCreatorImGui  the texture creator instance (for accessing preferences)
     * @param viewport             the 3D viewport for camera updates (can be null)
     * @param propertyPanel        the property panel for UI updates (can be null)
     */
    public UnifiedPreferencesPageRenderer(PreferencesManager preferencesManager,
                                          ThemeManager themeManager,
                                          TextureCreatorImGui textureCreatorImGui,
                                          ViewportController viewport,
                                          PropertyPanelImGui propertyPanel) {
        this.preferencesManager = preferencesManager;
        this.themeManager = themeManager;
        this.textureCreatorImGui = textureCreatorImGui;
        this.viewport = viewport;
        this.propertyPanel = propertyPanel;
        logger.debug("Unified preferences page renderer created");
    }

    /**
     * Renders the selected preference page.
     *
     * @param page the page to render
     */
    public void render(PreferencesState.PreferencePage page) {
        switch (page) {
            case MODEL_VIEWER:
                renderModelViewerPage();
                break;
            case TEXTURE_EDITOR:
                renderTextureEditorPage();
                break;
            case COMMON:
                renderCommonPage();
                break;
            default:
                ImGui.text("Unknown page: " + page);
                break;
        }
    }

    // ========================================
    // Model Viewer Page
    // ========================================

    private void renderModelViewerPage() {
        // Sync state from preferences
        syncModelViewerState();

        // Camera Settings Section
        PreferencesPageRenderer.renderSectionHeader("Camera Settings");
        renderCameraSettings();

        PreferencesPageRenderer.addSectionSeparator();

        // Grid Settings Section
        PreferencesPageRenderer.renderSectionHeader("Grid Settings");
        renderGridSettings();

        PreferencesPageRenderer.addSectionSeparator();

        // UI Settings Section
        PreferencesPageRenderer.renderSectionHeader("UI Settings");
        renderUISettings();

        PreferencesPageRenderer.addSectionSeparator();

        // Add extra spacing for visual consistency
        ImGui.spacing();

        // Reset button
        PreferencesPageRenderer.renderButton(
                "Reset to Defaults",
                150.0f,
                0.0f,
                this::resetModelViewerToDefaults
        );
    }

    private void renderCameraSettings() {
        PreferencesPageRenderer.renderSliderSetting(
                "Camera Drag Speed",
                "Controls how fast the camera rotates when dragging with the mouse.\n" +
                        "Higher values = faster camera movement.\n" +
                        "Default: 3.0",
                cameraMouseSensitivity,
                MIN_CAMERA_SENSITIVITY,
                MAX_CAMERA_SENSITIVITY,
                "%.1f",
                this::onCameraSensitivityChanged
        );
    }

    private void renderGridSettings() {
        PreferencesPageRenderer.renderComboBoxSetting(
                "Grid Snapping Increment",
                "Controls the snapping precision when grid snapping is enabled.\n" +
                        "Smaller increments allow for finer positioning control.\n" +
                        "Enable grid snapping in the Viewport Controls window.\n" +
                        "Default: 1/16 Block",
                GRID_SNAPPING_INCREMENT_NAMES,
                gridSnappingIncrementIndex,
                200.0f,
                this::onGridSnappingIncrementChanged
        );

        PreferencesPageRenderer.renderSliderSetting(
                "Vertex Point Size",
                "Controls the size of vertex points when 'Show Mesh' is enabled.\n" +
                        "Larger values make vertices more visible.\n" +
                        "Enable mesh display (vertices + edges) with the 'Mesh' checkbox in the viewport toolbar.\n" +
                        "Default: 5.0",
                vertexPointSize,
                MIN_VERTEX_POINT_SIZE,
                MAX_VERTEX_POINT_SIZE,
                "%.1f",
                this::onVertexPointSizeChanged
        );
    }

    private void renderUISettings() {
        PreferencesPageRenderer.renderCheckboxSetting(
                "Compact Properties Panel",
                "When enabled, shows only essential controls in the properties panel.\n" +
                        "When disabled, shows all available options with expanded sections.\n" +
                        "Default: Enabled",
                compactPropertiesMode,
                this::onCompactModeChanged
        );
    }

    private void onCameraSensitivityChanged(Float newValue) {
        // Clamp value to valid range
        float clampedValue = Math.max(MIN_CAMERA_SENSITIVITY,
                Math.min(MAX_CAMERA_SENSITIVITY, newValue));

        // Save to preferences
        preferencesManager.setCameraMouseSensitivity(clampedValue);

        // Apply to viewport in real-time
        if (viewport != null && viewport.getCamera() != null) {
            viewport.getCamera().setMouseSensitivity(clampedValue);
            logger.debug("Camera mouse sensitivity updated in real-time: {}", clampedValue);
        } else {
            logger.debug("Camera mouse sensitivity saved (viewport will apply on next load): {}", clampedValue);
        }
    }

    private void onCompactModeChanged(Boolean newValue) {
        // Save to preferences
        preferencesManager.setPropertiesCompactMode(newValue);

        // Apply to property panel in real-time
        if (propertyPanel != null) {
            propertyPanel.setCompactMode(newValue);
            logger.debug("Compact properties mode updated in real-time: {}", newValue);
        } else {
            logger.debug("Compact properties mode saved (panel will apply on next load): {}", newValue);
        }
    }

    private void onGridSnappingIncrementChanged(Integer newIndex) {
        // Validate index
        if (newIndex < 0 || newIndex >= GRID_SNAPPING_INCREMENT_VALUES.length) {
            logger.warn("Invalid grid snapping increment index: {}", newIndex);
            return;
        }

        // Get the selected increment value
        float newIncrement = GRID_SNAPPING_INCREMENT_VALUES[newIndex];

        // Save to preferences (persists to disk)
        preferencesManager.setGridSnappingIncrement(newIncrement);

        // Apply immediately to viewport (real-time update)
        if (viewport != null) {
            viewport.setGridSnappingIncrement(newIncrement);
            logger.debug("Grid snapping increment applied to viewport: {} ({})",
                        GRID_SNAPPING_INCREMENT_NAMES[newIndex], newIncrement);
        } else {
            logger.debug("Grid snapping increment saved to preferences: {} ({}) - will apply on next viewport creation",
                        GRID_SNAPPING_INCREMENT_NAMES[newIndex], newIncrement);
        }
    }

    private void onVertexPointSizeChanged(Float newValue) {
        // Clamp value to valid range
        float clampedValue = Math.max(MIN_VERTEX_POINT_SIZE,
                Math.min(MAX_VERTEX_POINT_SIZE, newValue));

        // Save to AppConfig (persists to disk)
        try {
            com.openmason.app.AppConfig appConfig = new com.openmason.app.AppConfig();
            appConfig.setVertexPointSize(clampedValue);
            appConfig.saveConfiguration();
        } catch (Exception e) {
            logger.error("Failed to save vertex point size to AppConfig", e);
        }

        // Apply to viewport in real-time
        if (viewport != null) {
            viewport.setVertexPointSize(clampedValue);
            logger.debug("Vertex point size updated in real-time: {}", clampedValue);
        } else {
            logger.debug("Vertex point size saved (viewport will apply on next load): {}", clampedValue);
        }
    }

    private void resetModelViewerToDefaults() {
        // Reset camera sensitivity
        preferencesManager.setCameraMouseSensitivity(3.0f);
        if (viewport != null && viewport.getCamera() != null) {
            viewport.getCamera().setMouseSensitivity(3.0f);
        }

        // Reset grid snapping increment (default: 1/16 block = 0.0625)
        preferencesManager.setGridSnappingIncrement(0.0625f);

        // Reset compact mode
        preferencesManager.setPropertiesCompactMode(true);
        if (propertyPanel != null) {
            propertyPanel.setCompactMode(true);
        }

        // Reset vertex point size (default: 5.0)
        try {
            com.openmason.app.AppConfig appConfig = new com.openmason.app.AppConfig();
            appConfig.setVertexPointSize(5.0f);
            appConfig.saveConfiguration();
            if (viewport != null) {
                viewport.setVertexPointSize(5.0f);
            }
        } catch (Exception e) {
            logger.error("Failed to reset vertex point size", e);
        }

        logger.info("Model Viewer preferences reset to defaults");
    }

    private void syncModelViewerState() {
        cameraMouseSensitivity.set(preferencesManager.getCameraMouseSensitivity());
        compactPropertiesMode.set(preferencesManager.getPropertiesCompactMode());

        // Sync grid snapping increment
        float currentIncrement = preferencesManager.getGridSnappingIncrement();
        int index = findGridSnappingIncrementIndex(currentIncrement);
        gridSnappingIncrementIndex.set(index);

        // Sync vertex point size from AppConfig
        // Note: Using AppConfig directly since PreferencesManager doesn't handle vertex point size yet
        try {
            com.openmason.app.AppConfig appConfig = new com.openmason.app.AppConfig();
            vertexPointSize.set(appConfig.getVertexPointSize());
        } catch (Exception e) {
            logger.warn("Failed to sync vertex point size, using default: 5.0", e);
            vertexPointSize.set(5.0f);
        }
    }

    /**
     * Find the index of a grid snapping increment value.
     * Returns the closest match if exact value not found.
     */
    private int findGridSnappingIncrementIndex(float value) {
        // Find exact match or closest match
        int closestIndex = 0;
        float closestDiff = Math.abs(GRID_SNAPPING_INCREMENT_VALUES[0] - value);

        for (int i = 1; i < GRID_SNAPPING_INCREMENT_VALUES.length; i++) {
            float diff = Math.abs(GRID_SNAPPING_INCREMENT_VALUES[i] - value);
            if (diff < closestDiff) {
                closestDiff = diff;
                closestIndex = i;
            }
        }

        return closestIndex;
    }

    // ========================================
    // Texture Editor Page
    // ========================================

    private void renderTextureEditorPage() {
        TextureCreatorPreferences preferences = getTexturePreferences();
        if (preferences == null) {
            ImGui.text("No preferences available");
            return;
        }

        // Sync state from preferences
        syncTextureEditorState(preferences);

        // Grid Overlay Section
        PreferencesPageRenderer.renderSectionHeader("Grid Overlay");
        ImGui.indent();
        renderGridSettings(preferences);
        ImGui.unindent();

        PreferencesPageRenderer.addSectionSeparator();

        // Cube Net Reference Section
        PreferencesPageRenderer.renderSectionHeader("Cube Net Reference (64x48)");
        ImGui.indent();
        renderCubeNetSettings(preferences);
        ImGui.unindent();

        PreferencesPageRenderer.addSectionSeparator();

        // Tool Behavior Section
        PreferencesPageRenderer.renderSectionHeader("Tool Behavior");
        ImGui.indent();
        renderToolBehaviorSettings(preferences);
        ImGui.unindent();

        PreferencesPageRenderer.addSectionSeparator();

        // Add extra spacing for visual consistency
        ImGui.spacing();

        // Reset button
        PreferencesPageRenderer.renderButton(
                "Reset to Defaults",
                150.0f,
                0.0f,
                () -> resetTextureEditorToDefaults(preferences)
        );
    }

    private void renderGridSettings(TextureCreatorPreferences preferences) {
        PreferencesPageRenderer.renderSliderSetting(
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
    }

    private void renderCubeNetSettings(TextureCreatorPreferences preferences) {
        PreferencesPageRenderer.renderSliderSetting(
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
    }

    private void renderToolBehaviorSettings(TextureCreatorPreferences preferences) {
        // Move Tool Settings
        PreferencesPageRenderer.renderSubHeader("Move Tool");
        PreferencesPageRenderer.renderSettingSeparator();

        PreferencesPageRenderer.renderSliderSetting(
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

        PreferencesPageRenderer.addSpacing();

        // Paste/Move Operations Settings
        PreferencesPageRenderer.renderSubHeader("Paste/Move Operations");
        PreferencesPageRenderer.renderSettingSeparator();

        PreferencesPageRenderer.renderCheckboxSetting(
                "Skip Transparent Pixels",
                "When enabled, fully transparent pixels (alpha = 0) won't overwrite existing pixels\n" +
                        "during paste or move operations. When disabled, transparent pixels will clear\n" +
                        "the destination, allowing you to erase with transparent selections.",
                skipTransparentPixelsCheckbox,
                preferences::setSkipTransparentPixelsOnPaste
        );
    }

    private void resetTextureEditorToDefaults(TextureCreatorPreferences preferences) {
        preferences.resetToDefaults();
        logger.info("Texture Editor preferences reset to defaults");
    }

    private void syncTextureEditorState(TextureCreatorPreferences preferences) {
        gridOpacitySlider.set(preferences.getGridOpacity());
        cubeNetOverlayOpacitySlider.set(preferences.getCubeNetOverlayOpacity());
        rotationSpeedSlider.set(preferences.getRotationSpeed());
        skipTransparentPixelsCheckbox.set(preferences.isSkipTransparentPixelsOnPaste());
    }

    private TextureCreatorPreferences getTexturePreferences() {
        if (textureCreatorImGui != null) {
            return textureCreatorImGui.getPreferences();
        }
        return null;
    }

    // ========================================
    // Common Page
    // ========================================

    private void renderCommonPage() {
        // Sync state from current theme/density
        syncCommonState();

        // Appearance Settings Section
        PreferencesPageRenderer.renderSectionHeader("Appearance");
        renderAppearanceSettings();

        PreferencesPageRenderer.addSectionSeparator();

        // Add extra spacing for visual consistency
        ImGui.spacing();

        // Reset button
        PreferencesPageRenderer.renderButton(
                "Reset to Defaults",
                150.0f,
                0.0f,
                this::resetCommonToDefaults
        );
    }

    private void renderAppearanceSettings() {
        // Theme selection
        String[] themeNames = themeManager.getAvailableThemes().stream()
                .map(ThemeDefinition::getName)
                .toArray(String[]::new);

        PreferencesPageRenderer.renderComboBoxSetting(
                "Theme",
                "Select the color theme for Open Mason.\n" +
                        "Themes define the overall look and color scheme of the interface.\n" +
                        "Changes apply immediately.",
                themeNames,
                themeIndex,
                200.0f,
                this::onThemeChanged
        );

        // UI Density selection
        String[] densityNames = new String[DensityManager.UIDensity.values().length];
        int idx = 0;
        for (DensityManager.UIDensity density : DensityManager.UIDensity.values()) {
            densityNames[idx++] = density.getDisplayName();
        }

        PreferencesPageRenderer.renderComboBoxSetting(
                "UI Density",
                "Controls the spacing and size of UI elements.\n" +
                        "Compact: Minimal padding, more content visible\n" +
                        "Normal: Balanced spacing\n" +
                        "Comfortable: Generous spacing\n" +
                        "Spacious: Maximum spacing for accessibility\n" +
                        "Changes apply immediately.",
                densityNames,
                densityIndex,
                200.0f,
                this::onDensityChanged
        );
    }

    private void onThemeChanged(Integer newIndex) {
        if (newIndex < 0 || newIndex >= themeManager.getAvailableThemes().size()) {
            logger.warn("Invalid theme index: {}", newIndex);
            return;
        }

        ThemeDefinition selectedTheme = themeManager.getAvailableThemes().get(newIndex);
        themeManager.applyTheme(selectedTheme);

        logger.info("Theme changed to: {}", selectedTheme.getName());
    }

    private void onDensityChanged(Integer newIndex) {
        if (newIndex < 0 || newIndex >= DensityManager.UIDensity.values().length) {
            logger.warn("Invalid density index: {}", newIndex);
            return;
        }

        DensityManager.UIDensity selectedDensity = DensityManager.UIDensity.values()[newIndex];
        themeManager.setUIDensity(selectedDensity);

        logger.info("UI Density changed to: {}", selectedDensity.getDisplayName());
    }

    private void resetCommonToDefaults() {
        // Reset to default theme (first available theme, typically "Dark Professional")
        if (!themeManager.getAvailableThemes().isEmpty()) {
            ThemeDefinition defaultTheme = themeManager.getAvailableThemes().get(0);
            themeManager.applyTheme(defaultTheme);
        }

        // Reset to default density (NORMAL)
        themeManager.setUIDensity(DensityManager.UIDensity.NORMAL);

        logger.info("Common preferences reset to defaults");
    }

    private void syncCommonState() {
        // Find current theme index
        ThemeDefinition currentTheme = themeManager.getCurrentTheme();
        if (currentTheme != null) {
            for (int i = 0; i < themeManager.getAvailableThemes().size(); i++) {
                if (themeManager.getAvailableThemes().get(i).getId().equals(currentTheme.getId())) {
                    themeIndex.set(i);
                    break;
                }
            }
        }

        // Get current density index
        DensityManager.UIDensity currentDensity = themeManager.getCurrentDensity();
        densityIndex.set(currentDensity.ordinal());
    }

    // ========================================
    // Lifecycle Methods
    // ========================================

    /**
     * Sets the TextureCreatorImGui instance for texture editor preferences.
     * <p>
     * This allows the texture creator interface to be wired up after construction,
     * which is necessary because MainImGuiInterface is created before TextureCreatorImGui.
     * </p>
     *
     * @param textureCreatorImGui the texture creator instance (can be null)
     */
    public void setTextureCreatorImGui(TextureCreatorImGui textureCreatorImGui) {
        this.textureCreatorImGui = textureCreatorImGui;
        logger.debug("TextureCreatorImGui reference updated for real-time preference updates");
    }
}
