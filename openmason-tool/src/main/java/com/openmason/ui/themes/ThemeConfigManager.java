package com.openmason.ui.themes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Manages theme configuration persistence using Theme.ini file.
 *
 * This class handles loading and saving theme preferences including:
 * - Current active theme ID
 * - Current UI density setting
 * - List of available themes (for reference)
 *
 * The configuration is stored in an INI-style properties file at:
 * openmason-tool/Theme.ini
 *
 * Thread-safe operations ensure configuration consistency.
 */
public class ThemeConfigManager {

    private static final Logger logger = LoggerFactory.getLogger(ThemeConfigManager.class);

    // Configuration file location
    private static final String CONFIG_FILE = "openmason-tool/Theme.ini";

    // Configuration keys
    private static final String KEY_CURRENT_THEME = "current.theme";
    private static final String KEY_CURRENT_DENSITY = "current.density";
    private static final String KEY_AVAILABLE_THEMES = "available.themes";

    // Default values
    private static final String DEFAULT_THEME = "dark";
    private static final String DEFAULT_DENSITY = "NORMAL";
    private static final String DEFAULT_AVAILABLE_THEMES = "dark,light,high-contrast,blue";

    private final Properties properties;
    private final Path configPath;

    /**
     * Initialize the theme configuration manager.
     * Loads existing configuration or creates default configuration.
     */
    public ThemeConfigManager() {
        this.properties = new Properties();
        this.configPath = Paths.get(CONFIG_FILE);
        loadConfiguration();
    }

    /**
     * Load configuration from Theme.ini file.
     * Creates default configuration if file doesn't exist.
     */
    private void loadConfiguration() {
        try {
            if (Files.exists(configPath)) {
                try (InputStream input = Files.newInputStream(configPath)) {
                    properties.load(input);
                    logger.info("Loaded theme configuration from: {}", configPath);
                }
            } else {
                // Create default configuration
                setDefaults();
                saveConfiguration();
                logger.info("Created default Theme.ini file: {}", configPath);
            }
        } catch (IOException e) {
            logger.error("Failed to load theme configuration from: {}", configPath, e);
            setDefaults();
        }
    }

    /**
     * Save current configuration to Theme.ini file.
     */
    public void saveConfiguration() {
        try {
            // Ensure directory exists
            Files.createDirectories(configPath.getParent());

            // Write configuration with comments
            try (OutputStream output = Files.newOutputStream(configPath)) {
                // Java Properties doesn't support multi-line comments easily,
                // so we'll use store() method which adds a timestamp comment
                properties.store(output,
                    "OpenMason Theme Configuration\n" +
                    "# This file stores your theme preferences\n" +
                    "# \n" +
                    "# Available themes: " + properties.getProperty(KEY_AVAILABLE_THEMES, DEFAULT_AVAILABLE_THEMES) + "\n" +
                    "# Current theme: " + properties.getProperty(KEY_CURRENT_THEME, DEFAULT_THEME) + "\n" +
                    "# Current density: " + properties.getProperty(KEY_CURRENT_DENSITY, DEFAULT_DENSITY));
                logger.debug("Saved theme configuration to: {}", configPath);
            }
        } catch (IOException e) {
            logger.error("Failed to save theme configuration to: {}", configPath, e);
        }
    }

    /**
     * Set default configuration values.
     */
    private void setDefaults() {
        properties.setProperty(KEY_CURRENT_THEME, DEFAULT_THEME);
        properties.setProperty(KEY_CURRENT_DENSITY, DEFAULT_DENSITY);
        properties.setProperty(KEY_AVAILABLE_THEMES, DEFAULT_AVAILABLE_THEMES);
        logger.debug("Set default theme configuration values");
    }

    /**
     * Get the current theme ID from configuration.
     *
     * @return Theme ID (e.g., "dark", "light", "high-contrast", "blue")
     */
    public String getCurrentThemeId() {
        String themeId = properties.getProperty(KEY_CURRENT_THEME, DEFAULT_THEME);
        logger.debug("Retrieved current theme ID: {}", themeId);
        return themeId;
    }

    /**
     * Set the current theme ID in configuration and save.
     *
     * @param themeId Theme ID to set as current
     */
    public void setCurrentThemeId(String themeId) {
        if (themeId == null || themeId.trim().isEmpty()) {
            logger.warn("Attempted to set null or empty theme ID, using default");
            themeId = DEFAULT_THEME;
        }

        properties.setProperty(KEY_CURRENT_THEME, themeId);
        saveConfiguration();
        logger.info("Set current theme ID to: {}", themeId);
    }

    /**
     * Get the current UI density setting from configuration.
     *
     * @return UI density name (e.g., "COMPACT", "NORMAL", "COMFORTABLE", "SPACIOUS")
     */
    public String getCurrentDensity() {
        String density = properties.getProperty(KEY_CURRENT_DENSITY, DEFAULT_DENSITY);
        logger.debug("Retrieved current density: {}", density);
        return density;
    }

    /**
     * Set the current UI density in configuration and save.
     *
     * @param density UI density name to set as current
     */
    public void setCurrentDensity(String density) {
        if (density == null || density.trim().isEmpty()) {
            logger.warn("Attempted to set null or empty density, using default");
            density = DEFAULT_DENSITY;
        }

        properties.setProperty(KEY_CURRENT_DENSITY, density);
        saveConfiguration();
        logger.info("Set current density to: {}", density);
    }

    /**
     * Get list of available theme IDs.
     *
     * @return List of theme IDs
     */
    public List<String> getAvailableThemes() {
        String themesStr = properties.getProperty(KEY_AVAILABLE_THEMES, DEFAULT_AVAILABLE_THEMES);
        List<String> themes = Arrays.asList(themesStr.split(","));
        logger.debug("Retrieved available themes: {}", themes);
        return themes;
    }

    /**
     * Update the list of available themes (for reference).
     * This is typically called when new themes are registered.
     *
     * @param themes List of theme IDs
     */
    public void setAvailableThemes(List<String> themes) {
        if (themes == null || themes.isEmpty()) {
            logger.warn("Attempted to set null or empty theme list, keeping current");
            return;
        }

        String themesStr = String.join(",", themes);
        properties.setProperty(KEY_AVAILABLE_THEMES, themesStr);
        saveConfiguration();
        logger.debug("Updated available themes to: {}", themesStr);
    }

    /**
     * Reset configuration to defaults.
     */
    public void resetToDefaults() {
        properties.clear();
        setDefaults();
        saveConfiguration();
        logger.info("Reset theme configuration to defaults");
    }

    /**
     * Get the path to the configuration file.
     *
     * @return Path to Theme.ini
     */
    public Path getConfigPath() {
        return configPath;
    }

    /**
     * Check if configuration file exists.
     *
     * @return true if Theme.ini exists
     */
    public boolean configFileExists() {
        return Files.exists(configPath);
    }

    /**
     * Get a snapshot of all configuration properties.
     * Useful for debugging and diagnostics.
     *
     * @return String representation of all properties
     */
    public String getConfigurationSnapshot() {
        StringBuilder snapshot = new StringBuilder();
        snapshot.append("Theme Configuration:\n");
        snapshot.append("  File: ").append(configPath.toAbsolutePath()).append("\n");
        snapshot.append("  Current Theme: ").append(getCurrentThemeId()).append("\n");
        snapshot.append("  Current Density: ").append(getCurrentDensity()).append("\n");
        snapshot.append("  Available Themes: ").append(getAvailableThemes()).append("\n");
        return snapshot.toString();
    }
}
