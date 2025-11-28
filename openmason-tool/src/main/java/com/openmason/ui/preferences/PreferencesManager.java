package com.openmason.ui.preferences;

import com.openmason.ui.viewport.util.SnappingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Manages OpenMason preferences with automatic persistence.
 * Stores settings in a properties file similar to how imgui.ini stores layout.
 */
public class PreferencesManager {
    
    private static final Logger logger = LoggerFactory.getLogger(PreferencesManager.class);
    
    private static final String PREFERENCES_FILE = "openmason-tool/preferences.properties";

    // 3D Model Viewer preferences
    private static final String CAMERA_MOUSE_SENSITIVITY_KEY = "camera.mouse.sensitivity";
    private static final String GRID_SNAPPING_ENABLED_KEY = "viewport.grid.snapping.enabled";
    private static final String GRID_SNAPPING_INCREMENT_KEY = "viewport.grid.snapping.increment";

    // Texture Creator preferences
    private static final String TEXTURE_EDITOR_GRID_OPACITY_KEY = "texture.editor.grid.opacity";
    private static final String TEXTURE_EDITOR_CUBE_NET_OVERLAY_OPACITY_KEY = "texture.editor.cube.net.overlay.opacity";
    private static final String TEXTURE_EDITOR_COLOR_HISTORY_KEY = "texture.editor.color.history";
    private static final String TEXTURE_EDITOR_ROTATION_SPEED_KEY = "texture.editor.rotation.speed";
    private static final String TEXTURE_EDITOR_SKIP_TRANSPARENT_PASTE_KEY = "texture.editor.skip.transparent.paste";
    private static final String TEXTURE_EDITOR_SHAPE_FILL_MODE_KEY = "texture.editor.shape.fill.mode";

    // Default values - 3D Model Viewer
    private static final float DEFAULT_CAMERA_MOUSE_SENSITIVITY = 3.0f;
    private static final boolean DEFAULT_GRID_SNAPPING_ENABLED = false;
    // Default grid snapping: Half block (0.5 units) = 2 snap positions per visual grid square
    // This provides good balance between precision and visual alignment with the 1.0 unit grid
    private static final float DEFAULT_GRID_SNAPPING_INCREMENT = SnappingUtil.SNAP_HALF_BLOCK;

    // Default values - Texture Creator
    private static final float DEFAULT_TEXTURE_GRID_OPACITY = 0.5f;
    private static final float DEFAULT_CUBE_NET_OVERLAY_OPACITY = 0.5f;
    private static final String DEFAULT_COLOR_HISTORY = ""; // Empty list
    private static final float DEFAULT_ROTATION_SPEED = 0.5f; // 0.5 degrees per pixel
    private static final boolean DEFAULT_SKIP_TRANSPARENT_PASTE = true; // Skip transparent pixels by default
    private static final boolean DEFAULT_SHAPE_FILL_MODE = true; // Filled shapes by default
    
    private final Properties properties;
    private final Path preferencesPath;
    
    public PreferencesManager() {
        this.properties = new Properties();
        this.preferencesPath = Paths.get(PREFERENCES_FILE);
        loadPreferences();
    }
    
    /**
     * Load preferences from file. Creates default preferences if file doesn't exist.
     */
    private void loadPreferences() {
        try {
            if (Files.exists(preferencesPath)) {
                try (InputStream input = Files.newInputStream(preferencesPath)) {
                    properties.load(input);
                    // logger.info("Loaded preferences from: {}", preferencesPath);
                }
            } else {
                // Create default preferences
                setDefaults();
                savePreferences();
                // logger.info("Created default preferences file: {}", preferencesPath);
            }
        } catch (IOException e) {
            logger.error("Failed to load preferences from: {}", preferencesPath, e);
            setDefaults();
        }
    }
    
    /**
     * Save preferences to file.
     */
    public void savePreferences() {
        try {
            // Ensure directory exists
            Files.createDirectories(preferencesPath.getParent());
            
            try (OutputStream output = Files.newOutputStream(preferencesPath)) {
                properties.store(output, "OpenMason Preferences - Generated automatically");
                // logger.info("Saved preferences to: {}", preferencesPath);
            }
        } catch (IOException e) {
            logger.error("Failed to save preferences to: {}", preferencesPath, e);
        }
    }
    
    /**
     * Set default preference values.
     */
    private void setDefaults() {
        // 3D Model Viewer defaults
        properties.setProperty(CAMERA_MOUSE_SENSITIVITY_KEY, String.valueOf(DEFAULT_CAMERA_MOUSE_SENSITIVITY));
        properties.setProperty(GRID_SNAPPING_ENABLED_KEY, String.valueOf(DEFAULT_GRID_SNAPPING_ENABLED));
        properties.setProperty(GRID_SNAPPING_INCREMENT_KEY, String.valueOf(DEFAULT_GRID_SNAPPING_INCREMENT));

        // Texture Creator defaults
        properties.setProperty(TEXTURE_EDITOR_GRID_OPACITY_KEY, String.valueOf(DEFAULT_TEXTURE_GRID_OPACITY));
        properties.setProperty(TEXTURE_EDITOR_CUBE_NET_OVERLAY_OPACITY_KEY, String.valueOf(DEFAULT_CUBE_NET_OVERLAY_OPACITY));
        properties.setProperty(TEXTURE_EDITOR_COLOR_HISTORY_KEY, DEFAULT_COLOR_HISTORY);
        properties.setProperty(TEXTURE_EDITOR_ROTATION_SPEED_KEY, String.valueOf(DEFAULT_ROTATION_SPEED));
        properties.setProperty(TEXTURE_EDITOR_SKIP_TRANSPARENT_PASTE_KEY, String.valueOf(DEFAULT_SKIP_TRANSPARENT_PASTE));
        properties.setProperty(TEXTURE_EDITOR_SHAPE_FILL_MODE_KEY, String.valueOf(DEFAULT_SHAPE_FILL_MODE));
    }
    
    // Camera Settings
    
    /**
     * Get camera mouse sensitivity setting.
     */
    public float getCameraMouseSensitivity() {
        String value = properties.getProperty(CAMERA_MOUSE_SENSITIVITY_KEY);
        if (value != null) {
            try {
                return Float.parseFloat(value);
            } catch (NumberFormatException e) {
                logger.warn("Invalid camera mouse sensitivity value: {}, using default", value);
            }
        }
        return DEFAULT_CAMERA_MOUSE_SENSITIVITY;
    }
    
    /**
     * Set camera mouse sensitivity setting.
     */
    public void setCameraMouseSensitivity(float sensitivity) {
        properties.setProperty(CAMERA_MOUSE_SENSITIVITY_KEY, String.valueOf(sensitivity));
        savePreferences();
    }
    
    /**
     * Reset camera settings to defaults.
     */
    public void resetCameraToDefaults() {
        properties.setProperty(CAMERA_MOUSE_SENSITIVITY_KEY, String.valueOf(DEFAULT_CAMERA_MOUSE_SENSITIVITY));
        properties.setProperty(GRID_SNAPPING_ENABLED_KEY, String.valueOf(DEFAULT_GRID_SNAPPING_ENABLED));
        properties.setProperty(GRID_SNAPPING_INCREMENT_KEY, String.valueOf(DEFAULT_GRID_SNAPPING_INCREMENT));
        savePreferences();
    }

    // Grid Snapping Settings

    /**
     * Set grid snapping enabled setting.
     */
    public void setGridSnappingEnabled(boolean enabled) {
        properties.setProperty(GRID_SNAPPING_ENABLED_KEY, String.valueOf(enabled));
        savePreferences();
    }

    /**
     * Get grid snapping increment setting.
     */
    public float getGridSnappingIncrement() {
        String value = properties.getProperty(GRID_SNAPPING_INCREMENT_KEY);
        if (value != null) {
            try {
                return Float.parseFloat(value);
            } catch (NumberFormatException e) {
                logger.warn("Invalid grid snapping increment value: {}, using default", value);
            }
        }
        return DEFAULT_GRID_SNAPPING_INCREMENT;
    }

    /**
     * Set grid snapping increment setting.
     */
    public void setGridSnappingIncrement(float increment) {
        properties.setProperty(GRID_SNAPPING_INCREMENT_KEY, String.valueOf(increment));
        savePreferences();
    }

    // Texture Creator Settings

    /**
     * Get texture editor grid opacity setting.
     * Controls both minor grid lines and major grid lines (every 4th).
     */
    public float getTextureEditorGridOpacity() {
        String value = properties.getProperty(TEXTURE_EDITOR_GRID_OPACITY_KEY);
        if (value != null) {
            try {
                return Float.parseFloat(value);
            } catch (NumberFormatException e) {
                logger.warn("Invalid texture editor grid opacity value: {}, using default", value);
            }
        }
        return DEFAULT_TEXTURE_GRID_OPACITY;
    }

    /**
     * Set texture editor grid opacity setting.
     * Controls both minor grid lines and major grid lines (every 4th).
     */
    public void setTextureEditorGridOpacity(float opacity) {
        properties.setProperty(TEXTURE_EDITOR_GRID_OPACITY_KEY, String.valueOf(opacity));
        savePreferences();
    }

    /**
     * Get texture editor cube net overlay opacity setting.
     * Controls the opacity of face labels and boundaries for 64x48 cube net textures.
     */
    public float getTextureEditorCubeNetOverlayOpacity() {
        String value = properties.getProperty(TEXTURE_EDITOR_CUBE_NET_OVERLAY_OPACITY_KEY);
        if (value != null) {
            try {
                return Float.parseFloat(value);
            } catch (NumberFormatException e) {
                logger.warn("Invalid cube net overlay opacity value: {}, using default", value);
            }
        }
        return DEFAULT_CUBE_NET_OVERLAY_OPACITY;
    }

    /**
     * Set texture editor cube net overlay opacity setting.
     * Controls the opacity of face labels and boundaries for 64x48 cube net textures.
     */
    public void setTextureEditorCubeNetOverlayOpacity(float opacity) {
        properties.setProperty(TEXTURE_EDITOR_CUBE_NET_OVERLAY_OPACITY_KEY, String.valueOf(opacity));
        savePreferences();
    }

    /**
     * Get texture editor rotation speed setting.
     * Controls how many degrees of rotation per pixel of mouse movement.
     * Higher values = faster rotation.
     */
    public float getTextureEditorRotationSpeed() {
        String value = properties.getProperty(TEXTURE_EDITOR_ROTATION_SPEED_KEY);
        if (value != null) {
            try {
                return Float.parseFloat(value);
            } catch (NumberFormatException e) {
                logger.warn("Invalid rotation speed value: {}, using default", value);
            }
        }
        return DEFAULT_ROTATION_SPEED;
    }

    /**
     * Set texture editor rotation speed setting.
     * Controls how many degrees of rotation per pixel of mouse movement.
     * Higher values = faster rotation.
     */
    public void setTextureEditorRotationSpeed(float speed) {
        properties.setProperty(TEXTURE_EDITOR_ROTATION_SPEED_KEY, String.valueOf(speed));
        savePreferences();
    }

    /**
     * Get texture editor skip transparent pixels on paste/move setting.
     * When enabled, fully transparent pixels (alpha = 0) won't overwrite existing pixels.
     * When disabled, transparent pixels will clear the destination.
     */
    public boolean getTextureEditorSkipTransparentPixelsOnPaste() {
        String value = properties.getProperty(TEXTURE_EDITOR_SKIP_TRANSPARENT_PASTE_KEY);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return DEFAULT_SKIP_TRANSPARENT_PASTE;
    }

    /**
     * Set texture editor skip transparent pixels on paste/move setting.
     * When enabled, fully transparent pixels (alpha = 0) won't overwrite existing pixels.
     * When disabled, transparent pixels will clear the destination.
     */
    public void setTextureEditorSkipTransparentPixelsOnPaste(boolean skip) {
        properties.setProperty(TEXTURE_EDITOR_SKIP_TRANSPARENT_PASTE_KEY, String.valueOf(skip));
        savePreferences();
    }

    /**
     * Reset texture creator settings to defaults.
     */
    public void resetTextureCreatorToDefaults() {
        properties.setProperty(TEXTURE_EDITOR_GRID_OPACITY_KEY, String.valueOf(DEFAULT_TEXTURE_GRID_OPACITY));
        properties.setProperty(TEXTURE_EDITOR_CUBE_NET_OVERLAY_OPACITY_KEY, String.valueOf(DEFAULT_CUBE_NET_OVERLAY_OPACITY));
        properties.setProperty(TEXTURE_EDITOR_COLOR_HISTORY_KEY, DEFAULT_COLOR_HISTORY);
        properties.setProperty(TEXTURE_EDITOR_ROTATION_SPEED_KEY, String.valueOf(DEFAULT_ROTATION_SPEED));
        properties.setProperty(TEXTURE_EDITOR_SKIP_TRANSPARENT_PASTE_KEY, String.valueOf(DEFAULT_SKIP_TRANSPARENT_PASTE));
        properties.setProperty(TEXTURE_EDITOR_SHAPE_FILL_MODE_KEY, String.valueOf(DEFAULT_SHAPE_FILL_MODE));
        savePreferences();
    }

    /**
     * Get texture editor color history.
     * Returns a list of colors (packed RGBA ints) from most recent to oldest.
     *
     * @return list of colors, empty if none
     */
    public List<Integer> getTextureEditorColorHistory() {
        String value = properties.getProperty(TEXTURE_EDITOR_COLOR_HISTORY_KEY);
        List<Integer> colors = new ArrayList<>();

        if (value != null && !value.trim().isEmpty()) {
            // Parse comma-separated list of hex color values
            String[] hexColors = value.split(",");
            for (String hex : hexColors) {
                try {
                    hex = hex.trim();
                    if (!hex.isEmpty()) {
                        // Parse as unsigned integer (hex string without 0x prefix)
                        long colorLong = Long.parseLong(hex, 16);
                        colors.add((int)colorLong);
                    }
                } catch (NumberFormatException e) {
                    logger.warn("Invalid color in history: {}", hex);
                }
            }
        }

        return colors;
    }

    /**
     * Set texture editor color history.
     * Saves a list of colors (packed RGBA ints) as comma-separated hex values.
     * KISS approach: Simple string serialization.
     *
     * @param colors list of colors (packed RGBA ints)
     */
    public void setTextureEditorColorHistory(List<Integer> colors) {
        if (colors == null || colors.isEmpty()) {
            properties.setProperty(TEXTURE_EDITOR_COLOR_HISTORY_KEY, DEFAULT_COLOR_HISTORY);
        } else {
            // Serialize as comma-separated hex values
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < colors.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                // Convert to hex string (8 hex digits for RGBA)
                sb.append(String.format("%08X", colors.get(i)));
            }
            properties.setProperty(TEXTURE_EDITOR_COLOR_HISTORY_KEY, sb.toString());
        }
        savePreferences();
    }

    /**
     * Get texture editor shape tool fill mode.
     * When true, shapes are filled. When false, shapes are outline only.
     */
    public boolean getTextureEditorShapeToolFillMode() {
        String value = properties.getProperty(TEXTURE_EDITOR_SHAPE_FILL_MODE_KEY);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return DEFAULT_SHAPE_FILL_MODE;
    }

    /**
     * Set texture editor shape tool fill mode.
     * When true, shapes are filled. When false, shapes are outline only.
     */
    public void setTextureEditorShapeToolFillMode(boolean fillMode) {
        properties.setProperty(TEXTURE_EDITOR_SHAPE_FILL_MODE_KEY, String.valueOf(fillMode));
        savePreferences();
    }
}