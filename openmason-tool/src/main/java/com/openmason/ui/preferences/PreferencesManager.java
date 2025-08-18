package com.openmason.ui.preferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Manages OpenMason preferences with automatic persistence.
 * Stores settings in a properties file similar to how imgui.ini stores layout.
 */
public class PreferencesManager {
    
    private static final Logger logger = LoggerFactory.getLogger(PreferencesManager.class);
    
    private static final String PREFERENCES_FILE = "openmason-tool/preferences.properties";
    private static final String CAMERA_MOUSE_SENSITIVITY_KEY = "camera.mouse.sensitivity";
    
    // Default values
    private static final float DEFAULT_CAMERA_MOUSE_SENSITIVITY = 3.0f;
    
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
        properties.setProperty(CAMERA_MOUSE_SENSITIVITY_KEY, String.valueOf(DEFAULT_CAMERA_MOUSE_SENSITIVITY));
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
}