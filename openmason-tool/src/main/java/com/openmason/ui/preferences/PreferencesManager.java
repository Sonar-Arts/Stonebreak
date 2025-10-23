package com.openmason.ui.preferences;

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
    private static final String PROPERTIES_COMPACT_MODE_KEY = "ui.properties.compact.mode";

    // Texture Creator preferences
    private static final String TEXTURE_EDITOR_GRID_OPACITY_KEY = "texture.editor.grid.opacity";
    private static final String TEXTURE_EDITOR_CUBE_NET_OVERLAY_OPACITY_KEY = "texture.editor.cube.net.overlay.opacity";
    private static final String TEXTURE_EDITOR_COLOR_HISTORY_KEY = "texture.editor.color.history";

    // Default values - 3D Model Viewer
    private static final float DEFAULT_CAMERA_MOUSE_SENSITIVITY = 3.0f;
    private static final boolean DEFAULT_PROPERTIES_COMPACT_MODE = true;

    // Default values - Texture Creator
    private static final float DEFAULT_TEXTURE_GRID_OPACITY = 0.5f;
    private static final float DEFAULT_CUBE_NET_OVERLAY_OPACITY = 0.5f;
    private static final String DEFAULT_COLOR_HISTORY = ""; // Empty list
    
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
        properties.setProperty(PROPERTIES_COMPACT_MODE_KEY, String.valueOf(DEFAULT_PROPERTIES_COMPACT_MODE));

        // Texture Creator defaults
        properties.setProperty(TEXTURE_EDITOR_GRID_OPACITY_KEY, String.valueOf(DEFAULT_TEXTURE_GRID_OPACITY));
        properties.setProperty(TEXTURE_EDITOR_CUBE_NET_OVERLAY_OPACITY_KEY, String.valueOf(DEFAULT_CUBE_NET_OVERLAY_OPACITY));
        properties.setProperty(TEXTURE_EDITOR_COLOR_HISTORY_KEY, DEFAULT_COLOR_HISTORY);
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
     * Reset all preferences to defaults.
     */
    public void resetToDefaults() {
        properties.clear();
        setDefaults();
        savePreferences();
        // logger.info("Reset all preferences to defaults");
    }
    
    /**
     * Reset camera settings to defaults.
     */
    public void resetCameraToDefaults() {
        properties.setProperty(CAMERA_MOUSE_SENSITIVITY_KEY, String.valueOf(DEFAULT_CAMERA_MOUSE_SENSITIVITY));
        savePreferences();
    }

    // UI Settings

    /**
     * Get properties panel compact mode setting.
     */
    public boolean getPropertiesCompactMode() {
        String value = properties.getProperty(PROPERTIES_COMPACT_MODE_KEY);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return DEFAULT_PROPERTIES_COMPACT_MODE;
    }

    /**
     * Set properties panel compact mode setting.
     */
    public void setPropertiesCompactMode(boolean compact) {
        properties.setProperty(PROPERTIES_COMPACT_MODE_KEY, String.valueOf(compact));
        savePreferences();
    }

    /**
     * Get the path to the preferences file.
     */
    public Path getPreferencesPath() {
        return preferencesPath;
    }

    /**
     * Check if preferences file exists.
     */
    public boolean preferencesFileExists() {
        return Files.exists(preferencesPath);
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
     * Reset texture creator settings to defaults.
     */
    public void resetTextureCreatorToDefaults() {
        properties.setProperty(TEXTURE_EDITOR_GRID_OPACITY_KEY, String.valueOf(DEFAULT_TEXTURE_GRID_OPACITY));
        properties.setProperty(TEXTURE_EDITOR_CUBE_NET_OVERLAY_OPACITY_KEY, String.valueOf(DEFAULT_CUBE_NET_OVERLAY_OPACITY));
        properties.setProperty(TEXTURE_EDITOR_COLOR_HISTORY_KEY, DEFAULT_COLOR_HISTORY);
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
}