package com.openmason.main.systems.menus.preferences;

import com.openmason.main.omConfig;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorImGui;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorPreferences;
import com.openmason.main.systems.menus.panes.propertyPane.PropertyPanelImGui;
import com.openmason.main.systems.themes.application.DensityManager;
import com.openmason.main.systems.themes.core.ThemeDefinition;
import com.openmason.main.systems.themes.core.ThemeManager;
import com.openmason.main.systems.themes.utils.ImGuiComponents;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.viewport.util.SnappingUtil;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified preferences page renderer for all Open Mason tools.
 * <p>
 * Uses a deferred-apply model: slider and combo changes only update local ImGui state.
 * Changes are persisted and applied to live systems only when {@link #applyAllSettings()} is called
 * (triggered by the OK or Apply buttons in {@link PreferencesWindow}).
 * </p>
 * <p>
 * Architecture:
 * - Single class with page-specific render methods
 * - Deferred persistence via OK/Apply buttons
 * - State synced from persistence on window open
 * - Per-page Reset to Defaults updates local state only (applied on OK/Apply)
 * </p>
 */
public class PreferencesPageRenderer {

    private static final Logger logger = LoggerFactory.getLogger(PreferencesPageRenderer.class);

    // Model Editor constants
    private static final float MIN_CAMERA_SENSITIVITY = 0.1f;
    private static final float MAX_CAMERA_SENSITIVITY = 10.0f;
    private static final float MIN_PAN_SENSITIVITY = 0.1f;
    private static final float MAX_PAN_SENSITIVITY = 5.0f;
    private static final float MIN_VERTEX_POINT_SIZE = 1.0f;
    private static final float MAX_VERTEX_POINT_SIZE = 15.0f;

    // Grid snapping increment options (based on STANDARD_BLOCK_SIZE = 1.0)
    private static final String[] GRID_SNAPPING_INCREMENT_NAMES = {
        "1 Block (1.0)",
        "1/2 Block (0.5)",
        "1/4 Block (0.25)",
        "1/8 Block (0.125)",
        "1/16 Block (0.0625)"
    };
    private static final float[] GRID_SNAPPING_INCREMENT_VALUES = {
        SnappingUtil.SNAP_FULL_BLOCK,
        SnappingUtil.SNAP_HALF_BLOCK,
        SnappingUtil.SNAP_QUARTER_BLOCK,
        0.125f,
        0.0625f
    };

    // Model Editor ImGui state holders
    private final ImFloat cameraMouseSensitivity = new ImFloat();
    private final ImFloat cameraPanSensitivity = new ImFloat();
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
    private TextureCreatorImGui textureCreatorImGui;

    // Component references for applying settings
    private final ViewportController viewport;
    private final PropertyPanelImGui propertyPanel;

    // Keybind management
    private final com.openmason.main.systems.keybinds.KeybindRegistry keybindRegistry;
    private final com.openmason.main.systems.menus.preferences.keybinds.KeyCaptureDialog keyCaptureDialog;
    private final com.openmason.main.systems.menus.preferences.keybinds.ConflictWarningDialog conflictDialog;

    /**
     * Creates a new unified preferences page renderer.
     *
     * @param preferencesManager   the preferences manager for persistence
     * @param themeManager         the theme manager for appearance settings
     * @param textureCreatorImGui  the texture creator instance (for accessing preferences)
     * @param viewport             the 3D viewport for camera updates (can be null)
     * @param propertyPanel        the property panel for UI updates (can be null)
     */
    public PreferencesPageRenderer(PreferencesManager preferencesManager,
                                   ThemeManager themeManager,
                                   TextureCreatorImGui textureCreatorImGui,
                                   ViewportController viewport,
                                   PropertyPanelImGui propertyPanel) {
        this.preferencesManager = preferencesManager;
        this.themeManager = themeManager;
        this.textureCreatorImGui = textureCreatorImGui;
        this.viewport = viewport;
        this.propertyPanel = propertyPanel;

        this.keybindRegistry = com.openmason.main.systems.keybinds.KeybindRegistry.getInstance();
        this.keyCaptureDialog = new com.openmason.main.systems.menus.preferences.keybinds.KeyCaptureDialog();
        this.conflictDialog = new com.openmason.main.systems.menus.preferences.keybinds.ConflictWarningDialog();

        logger.debug("Unified preferences page renderer created");
    }

    // ========================================
    // Lifecycle — Sync & Apply
    // ========================================

    /**
     * Called when the preferences window opens.
     * Loads all ImGui state holders from persisted values so sliders/combos
     * reflect the current saved settings.
     */
    public void onWindowOpened() {
        syncModelEditorState();

        TextureCreatorPreferences texPrefs = getTexturePreferences();
        if (texPrefs != null) {
            syncTextureEditorState(texPrefs);
        }

        syncCommonState();

        logger.debug("All preference pages synced from persistence");
    }

    /**
     * Persists all current ImGui state to disk and applies to live systems.
     * Called by the Apply and OK buttons.
     */
    public void applyAllSettings() {
        applyModelEditorSettings();
        applyTextureEditorSettings();
        applyCommonSettings();

        // Migrate the file so it contains every known key.
        // Adds defaults for any missing keys without overwriting existing values.
        preferencesManager.migrateFile();

        logger.info("All preferences applied and saved");
    }

    // ========================================
    // Page Rendering
    // ========================================

    /**
     * Renders the selected preference page.
     * State is NOT synced per-frame — only on window open via {@link #onWindowOpened()}.
     */
    public void render(PreferencesState.PreferencePage page) {
        switch (page) {
            case MODEL_EDITOR:
                renderModelEditorPage();
                break;
            case TEXTURE_EDITOR:
                renderTextureEditorPage();
                break;
            case KEYBINDS:
                renderKeybindsPage();
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
    // Model Editor Page
    // ========================================

    private void renderModelEditorPage() {
        // Camera Settings Section
        ImGuiComponents.renderSectionHeader("Camera Settings");
        renderCameraSettings();

        ImGuiComponents.addSectionSeparator();

        // Grid Settings Section
        ImGuiComponents.renderSectionHeader("Grid Settings");
        renderGridSettings();

        ImGuiComponents.addSectionSeparator();

        ImGui.spacing();

        ImGuiComponents.renderButton(
                "Reset to Defaults",
                150.0f,
                0.0f,
                this::resetModelEditorToDefaults
        );
    }

    private void renderCameraSettings() {
        ImGuiComponents.renderSubHeader("Orbit");
        ImGuiComponents.renderSettingSeparator();

        ImGuiComponents.renderSliderSetting(
                "Orbit Speed",
                "Controls how fast the camera orbits when dragging with the left mouse button.\n" +
                        "Higher values = faster rotation.\n" +
                        "Default: 3.0",
                cameraMouseSensitivity,
                MIN_CAMERA_SENSITIVITY,
                MAX_CAMERA_SENSITIVITY,
                "%.1f",
                v -> {} // Deferred — applied on OK/Apply
        );

        ImGuiComponents.addSpacing();

        ImGuiComponents.renderSubHeader("Pan");
        ImGuiComponents.renderSettingSeparator();

        ImGuiComponents.renderSliderSetting(
                "Pan Speed",
                "Controls how fast the camera pans when dragging with the middle mouse button.\n" +
                        "Pan speed also scales with zoom distance for consistent feel.\n" +
                        "Default: 1.0",
                cameraPanSensitivity,
                MIN_PAN_SENSITIVITY,
                MAX_PAN_SENSITIVITY,
                "%.1f",
                v -> {} // Deferred — applied on OK/Apply
        );
    }

    private void renderGridSettings() {
        ImGuiComponents.renderComboBoxSetting(
                "Grid Snapping Increment",
                "Controls the snapping precision when grid snapping is enabled.\n" +
                        "Smaller increments allow for finer positioning control.\n" +
                        "Enable grid snapping in the Viewport Controls window.\n" +
                        "Default: 1/16 Block",
                GRID_SNAPPING_INCREMENT_NAMES,
                gridSnappingIncrementIndex,
                200.0f,
                v -> {} // Deferred — applied on OK/Apply
        );

        ImGuiComponents.renderSliderSetting(
                "Vertex Point Size",
                "Controls the size of vertex points when 'Show Mesh' is enabled.\n" +
                        "Larger values make vertices more visible.\n" +
                        "Enable mesh display (vertices + edges) with the 'Mesh' checkbox in the viewport toolbar.\n" +
                        "Default: 5.0",
                vertexPointSize,
                MIN_VERTEX_POINT_SIZE,
                MAX_VERTEX_POINT_SIZE,
                "%.1f",
                v -> {} // Deferred — applied on OK/Apply
        );
    }

    private void applyModelEditorSettings() {
        // Camera orbit speed
        float orbitSpeed = Math.max(MIN_CAMERA_SENSITIVITY,
                Math.min(MAX_CAMERA_SENSITIVITY, cameraMouseSensitivity.get()));
        preferencesManager.setCameraMouseSensitivity(orbitSpeed);
        if (viewport != null && viewport.getCamera() != null) {
            viewport.getCamera().setMouseSensitivity(orbitSpeed);
        }

        // Camera pan speed
        float panSpeed = Math.max(MIN_PAN_SENSITIVITY,
                Math.min(MAX_PAN_SENSITIVITY, cameraPanSensitivity.get()));
        preferencesManager.setCameraPanSensitivity(panSpeed);
        if (viewport != null && viewport.getCamera() != null) {
            viewport.getCamera().setPanSensitivity(panSpeed);
        }

        // Grid snapping increment
        int snapIndex = gridSnappingIncrementIndex.get();
        if (snapIndex >= 0 && snapIndex < GRID_SNAPPING_INCREMENT_VALUES.length) {
            float increment = GRID_SNAPPING_INCREMENT_VALUES[snapIndex];
            preferencesManager.setGridSnappingIncrement(increment);
            if (viewport != null) {
                viewport.setGridSnappingIncrement(increment);
            }
        }

        // Vertex point size
        float pointSize = Math.max(MIN_VERTEX_POINT_SIZE,
                Math.min(MAX_VERTEX_POINT_SIZE, vertexPointSize.get()));
        try {
            omConfig config = new omConfig();
            config.setVertexPointSize(pointSize);
            config.saveConfiguration();
        } catch (Exception e) {
            logger.error("Failed to save vertex point size", e);
        }
        if (viewport != null) {
            viewport.setVertexPointSize(pointSize);
        }

        logger.debug("Model Editor settings applied");
    }

    /**
     * Resets Model Editor ImGui state to defaults.
     * Does NOT persist — changes take effect on OK/Apply.
     */
    private void resetModelEditorToDefaults() {
        cameraMouseSensitivity.set(3.0f);
        cameraPanSensitivity.set(1.0f);
        gridSnappingIncrementIndex.set(findGridSnappingIncrementIndex(0.0625f));
        vertexPointSize.set(5.0f);
        logger.debug("Model Editor preferences reset to defaults (pending Apply)");
    }

    private void syncModelEditorState() {
        cameraMouseSensitivity.set(preferencesManager.getCameraMouseSensitivity());
        cameraPanSensitivity.set(preferencesManager.getCameraPanSensitivity());

        float currentIncrement = preferencesManager.getGridSnappingIncrement();
        gridSnappingIncrementIndex.set(findGridSnappingIncrementIndex(currentIncrement));

        try {
            omConfig config = new omConfig();
            vertexPointSize.set(config.getVertexPointSize());
        } catch (Exception e) {
            logger.warn("Failed to sync vertex point size, using default: 5.0", e);
            vertexPointSize.set(5.0f);
        }
    }

    private int findGridSnappingIncrementIndex(float value) {
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

        // Grid Overlay Section
        ImGuiComponents.renderSectionHeader("Grid Overlay");
        ImGui.indent();
        ImGuiComponents.renderSliderSetting(
                "Grid Opacity",
                "Controls the opacity of the pixel grid overlay (toggleable with 'G' key).\n" +
                        "Includes both minor grid lines (every pixel) and major lines (every 4th pixel).\n" +
                        "Grid only visible when zoomed in 3x or more.",
                gridOpacitySlider,
                TextureCreatorPreferences.MIN_OPACITY,
                TextureCreatorPreferences.MAX_OPACITY,
                "%.2f",
                v -> {} // Deferred
        );
        ImGui.unindent();

        ImGuiComponents.addSectionSeparator();

        // Cube Net Reference Section
        ImGuiComponents.renderSectionHeader("Cube Net Reference (64x48)");
        ImGui.indent();
        ImGuiComponents.renderSliderSetting(
                "Reference Opacity",
                "Controls opacity of the cube-net reference overlay (independent of grid).\n" +
                        "Shows face labels (TOP, LEFT, FRONT, RIGHT, BACK, BOTTOM) and boundaries.\n" +
                        "Always visible under pixels, renders over grid when enabled.\n" +
                        "Only appears when editing 64x48 canvases.",
                cubeNetOverlayOpacitySlider,
                TextureCreatorPreferences.MIN_OPACITY,
                TextureCreatorPreferences.MAX_OPACITY,
                "%.2f",
                v -> {} // Deferred
        );
        ImGui.unindent();

        ImGuiComponents.addSectionSeparator();

        // Tool Behavior Section
        ImGuiComponents.renderSectionHeader("Tool Behavior");
        ImGui.indent();

        ImGuiComponents.renderSubHeader("Move Tool");
        ImGuiComponents.renderSettingSeparator();

        ImGuiComponents.renderSliderSetting(
                "Rotation Speed",
                "Controls rotation speed when using the move tool's rotate handle.\n" +
                        "Higher values = faster rotation.\n" +
                        "Default: 0.5 degrees per pixel of mouse movement",
                rotationSpeedSlider,
                TextureCreatorPreferences.MIN_ROTATION_SPEED,
                TextureCreatorPreferences.MAX_ROTATION_SPEED,
                "%.2f deg/px",
                v -> {} // Deferred
        );

        ImGuiComponents.addSpacing();

        ImGuiComponents.renderSubHeader("Paste/Move Operations");
        ImGuiComponents.renderSettingSeparator();

        ImGuiComponents.renderCheckboxSetting(
                "Skip Transparent Pixels",
                "When enabled, fully transparent pixels (alpha = 0) won't overwrite existing pixels\n" +
                        "during paste or move operations. When disabled, transparent pixels will clear\n" +
                        "the destination, allowing you to erase with transparent selections.",
                skipTransparentPixelsCheckbox,
                v -> {} // Deferred
        );

        ImGui.unindent();

        ImGuiComponents.addSectionSeparator();

        ImGui.spacing();

        ImGuiComponents.renderButton(
                "Reset to Defaults",
                150.0f,
                0.0f,
                this::resetTextureEditorToDefaults
        );
    }

    private void applyTextureEditorSettings() {
        TextureCreatorPreferences prefs = getTexturePreferences();
        if (prefs == null) {
            return;
        }

        prefs.setGridOpacity(gridOpacitySlider.get());
        prefs.setCubeNetOverlayOpacity(cubeNetOverlayOpacitySlider.get());
        prefs.setRotationSpeed(rotationSpeedSlider.get());
        prefs.setSkipTransparentPixelsOnPaste(skipTransparentPixelsCheckbox.get());

        logger.debug("Texture Editor settings applied");
    }

    private void resetTextureEditorToDefaults() {
        gridOpacitySlider.set(0.5f);
        cubeNetOverlayOpacitySlider.set(0.5f);
        rotationSpeedSlider.set(0.5f);
        skipTransparentPixelsCheckbox.set(true);
        logger.debug("Texture Editor preferences reset to defaults (pending Apply)");
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
        // Appearance Settings Section
        ImGuiComponents.renderSectionHeader("Appearance");
        renderAppearanceSettings();

        ImGuiComponents.addSectionSeparator();

        ImGui.spacing();

        ImGuiComponents.renderButton(
                "Reset to Defaults",
                150.0f,
                0.0f,
                this::resetCommonToDefaults
        );
    }

    private void renderAppearanceSettings() {
        String[] themeNames = themeManager.getAvailableThemes().stream()
                .map(ThemeDefinition::getName)
                .toArray(String[]::new);

        ImGuiComponents.renderComboBoxSetting(
                "Theme",
                "Select the color theme for Open Mason.\n" +
                        "Themes define the overall look and color scheme of the interface.\n" +
                        "Applied when OK or Apply is clicked.",
                themeNames,
                themeIndex,
                200.0f,
                v -> {} // Deferred
        );

        String[] densityNames = new String[DensityManager.UIDensity.values().length];
        int idx = 0;
        for (DensityManager.UIDensity density : DensityManager.UIDensity.values()) {
            densityNames[idx++] = density.getDisplayName();
        }

        ImGuiComponents.renderComboBoxSetting(
                "UI Density",
                "Controls the spacing and size of UI elements.\n" +
                        "Compact: Minimal padding, more content visible\n" +
                        "Normal: Balanced spacing\n" +
                        "Comfortable: Generous spacing\n" +
                        "Spacious: Maximum spacing for accessibility\n" +
                        "Applied when OK or Apply is clicked.",
                densityNames,
                densityIndex,
                200.0f,
                v -> {} // Deferred
        );
    }

    private void applyCommonSettings() {
        // Theme
        int tIdx = themeIndex.get();
        if (tIdx >= 0 && tIdx < themeManager.getAvailableThemes().size()) {
            ThemeDefinition selectedTheme = themeManager.getAvailableThemes().get(tIdx);
            themeManager.applyTheme(selectedTheme);
        }

        // Density
        int dIdx = densityIndex.get();
        if (dIdx >= 0 && dIdx < DensityManager.UIDensity.values().length) {
            DensityManager.UIDensity selectedDensity = DensityManager.UIDensity.values()[dIdx];
            themeManager.setUIDensity(selectedDensity);
        }

        logger.debug("Common settings applied");
    }

    private void resetCommonToDefaults() {
        themeIndex.set(0);
        densityIndex.set(DensityManager.UIDensity.NORMAL.ordinal());
        logger.debug("Common preferences reset to defaults (pending Apply)");
    }

    private void syncCommonState() {
        ThemeDefinition currentTheme = themeManager.getCurrentTheme();
        if (currentTheme != null) {
            for (int i = 0; i < themeManager.getAvailableThemes().size(); i++) {
                if (themeManager.getAvailableThemes().get(i).getId().equals(currentTheme.getId())) {
                    themeIndex.set(i);
                    break;
                }
            }
        }

        DensityManager.UIDensity currentDensity = themeManager.getCurrentDensity();
        densityIndex.set(currentDensity.ordinal());
    }

    // ========================================
    // Keybinds Page (immediate — dialog-based)
    // ========================================

    private void renderKeybindsPage() {
        keyCaptureDialog.render();
        conflictDialog.render();

        java.util.Set<String> categories = keybindRegistry.getAllCategories();

        for (String category : categories) {
            ImGuiComponents.renderSectionHeader(category);
            ImGui.indent();

            java.util.List<com.openmason.main.systems.keybinds.KeybindAction> actions =
                    keybindRegistry.getActionsByCategory(category);

            for (com.openmason.main.systems.keybinds.KeybindAction action : actions) {
                renderKeybindRow(action);
            }

            ImGui.unindent();
            ImGuiComponents.addSectionSeparator();
        }

        ImGui.spacing();
        ImGuiComponents.renderButton(
                "Reset All to Defaults",
                150.0f,
                0.0f,
                this::resetAllKeybinds
        );
    }

    private void renderKeybindRow(com.openmason.main.systems.keybinds.KeybindAction action) {
        float labelWidth = 250.0f;
        float keyWidth = 150.0f;
        float buttonWidth = 80.0f;

        ImGui.text(action.getDisplayName());
        ImGui.sameLine(labelWidth);

        com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey currentKey =
                keybindRegistry.getKeybind(action.getId());
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.2f, 0.2f, 0.25f, 1.0f);
        ImGui.button(currentKey.getDisplayName() + "##key_" + action.getId(), keyWidth, 0);
        ImGui.popStyleColor();

        ImGui.sameLine();
        if (ImGui.button("Rebind##rebind_" + action.getId(), buttonWidth, 0)) {
            startKeybindCapture(action);
        }

        ImGui.sameLine();
        boolean isCustomized = keybindRegistry.isCustomized(action.getId());
        if (isCustomized) {
            if (ImGui.button("Reset##reset_" + action.getId(), 60, 0)) {
                resetKeybind(action.getId());
            }
        } else {
            ImGui.dummy(60, 0);
        }
    }

    private void startKeybindCapture(com.openmason.main.systems.keybinds.KeybindAction action) {
        keyCaptureDialog.startCapture(
                action.getId(),
                action.getDisplayName(),
                () -> onKeyCaptured(action),
                () -> logger.debug("Key capture cancelled for: {}", action.getId())
        );
    }

    private void onKeyCaptured(com.openmason.main.systems.keybinds.KeybindAction action) {
        com.openmason.main.systems.menus.textureCreator.keyboard.ShortcutKey newKey =
                keyCaptureDialog.getCapturedKey();

        if (newKey == null) {
            logger.warn("No key captured for action: {}", action.getId());
            return;
        }

        com.openmason.main.systems.keybinds.ConflictResult conflict =
                keybindRegistry.checkConflict(action.getId(), newKey);

        if (conflict.hasConflict()) {
            com.openmason.main.systems.keybinds.KeybindAction conflictingAction =
                    conflict.getConflictingAction();
            conflictDialog.show(
                    action.getDisplayName(),
                    conflictingAction.getDisplayName(),
                    newKey.getDisplayName(),
                    () -> {
                        keybindRegistry.setKeybind(conflictingAction.getId(), null);
                        keybindRegistry.setKeybind(action.getId(), newKey);
                        preferencesManager.setKeybind(action.getId(), newKey);
                        logger.info("Keybind reassigned: {} -> {}",
                                action.getId(), newKey.getDisplayName());
                    },
                    () -> logger.debug("Conflict reassignment cancelled")
            );
        } else {
            keybindRegistry.setKeybind(action.getId(), newKey);
            preferencesManager.setKeybind(action.getId(), newKey);
            logger.info("Keybind set: {} -> {}", action.getId(), newKey.getDisplayName());
        }
    }

    private void resetKeybind(String actionId) {
        keybindRegistry.resetToDefault(actionId);
        preferencesManager.clearKeybind(actionId);
        logger.info("Keybind reset to default: {}", actionId);
    }

    private void resetAllKeybinds() {
        keybindRegistry.resetAllToDefaults();

        for (com.openmason.main.systems.keybinds.KeybindAction action : keybindRegistry.getAllActions()) {
            preferencesManager.clearKeybind(action.getId());
        }

        logger.info("All keybinds reset to defaults");
    }

    // ========================================
    // Lifecycle Methods
    // ========================================

    /**
     * Sets the TextureCreatorImGui instance for texture editor preferences.
     */
    public void setTextureCreatorImGui(TextureCreatorImGui textureCreatorImGui) {
        this.textureCreatorImGui = textureCreatorImGui;
        logger.debug("TextureCreatorImGui reference updated");
    }
}
