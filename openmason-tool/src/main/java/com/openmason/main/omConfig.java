package com.openmason.main;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Application configuration management for OpenMason.
 * Handles loading and saving of application settings, preferences, and project paths.
 */
public class omConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(omConfig.class);
    
    // Configuration file paths
    private static final String CONFIG_DIR = ".openmason";
    private static final String CONFIG_FILE = "config.properties";
    
    // Default configuration values
    private static final String DEFAULT_STONEBREAK_PROJECT_PATH = "../stonebreak-game";
    private static final String DEFAULT_MODELS_PATH = "src/main/resources/models";
    private static final String DEFAULT_TEXTURES_PATH = "src/main/resources/textures";
    private static final boolean DEFAULT_AUTO_SAVE_ENABLED = true;
    private static final int DEFAULT_AUTO_SAVE_INTERVAL = 300; // 5 minutes
    private static final boolean DEFAULT_DARK_THEME_ENABLED = true;
    private static final float DEFAULT_VERTEX_POINT_SIZE = 5.0f;
    
    private final Properties properties;
    private Path configFilePath;
    
    /**
     * Initialize application configuration.
     */
    public omConfig() {
        properties = new Properties();
        initializeConfiguration();
    }
    
    /**
     * Initialize configuration by loading from file or creating default.
     */
    private void initializeConfiguration() {
        try {
            // Determine config file path
            Path userHome = Paths.get(System.getProperty("user.home"));
            Path configDir = userHome.resolve(CONFIG_DIR);
            configFilePath = configDir.resolve(CONFIG_FILE);
            
            // Create config directory if it doesn't exist
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
                logger.info("Created configuration directory: {}", configDir);
            }
            
            // Load configuration
            if (Files.exists(configFilePath)) {
                loadConfigurationFromFile();
            } else {
                loadDefaultConfiguration();
                saveConfiguration(); // Save default config to file
            }

        } catch (Exception e) {
            logger.error("Failed to initialize configuration, using defaults", e);
            loadDefaultConfiguration();
        }
    }
    
    /**
     * Load configuration from user config file.
     */
    private void loadConfigurationFromFile() throws IOException {
        try (InputStream input = Files.newInputStream(configFilePath)) {
            properties.load(input);
        }
    }
    
    /**
     * Load default configuration values.
     */
    private void loadDefaultConfiguration() {
        properties.clear();
        
        // Project paths
        properties.setProperty("stonebreak.project.path", DEFAULT_STONEBREAK_PROJECT_PATH);
        properties.setProperty("stonebreak.models.path", DEFAULT_MODELS_PATH);
        properties.setProperty("stonebreak.textures.path", DEFAULT_TEXTURES_PATH);
        
        // Application settings
        properties.setProperty("app.auto.save.enabled", String.valueOf(DEFAULT_AUTO_SAVE_ENABLED));
        properties.setProperty("app.auto.save.interval", String.valueOf(DEFAULT_AUTO_SAVE_INTERVAL));
        properties.setProperty("app.dark.theme.enabled", String.valueOf(DEFAULT_DARK_THEME_ENABLED));
        
        // UI settings
        properties.setProperty("ui.last.window.width", "1600");
        properties.setProperty("ui.last.window.height", "1000");
        properties.setProperty("ui.last.window.maximized", "false");

        // Viewport settings
        properties.setProperty("viewport.vertex.point.size", String.valueOf(DEFAULT_VERTEX_POINT_SIZE));

        // Performance settings
        properties.setProperty("performance.max.texture.cache", "512"); // MB
        properties.setProperty("performance.max.model.cache", "128");   // MB
        properties.setProperty("performance.vsync.enabled", "true");
    }
    
    /**
     * Save current configuration to file.
     * Note: This is called frequently during window resize, so logging is at TRACE level.
     */
    public void saveConfiguration() {
        try {
            if (configFilePath != null) {
                Files.createDirectories(configFilePath.getParent());

                try (var output = Files.newOutputStream(configFilePath)) {
                    properties.store(output, "OpenMason Configuration");
                    logger.trace("Configuration saved to: {}", configFilePath);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to save configuration", e);
        }
    }
    
    // Getter methods for configuration values
    
    public int getLastWindowWidth() {
        return Integer.parseInt(properties.getProperty("ui.last.window.width", "1600"));
    }
    
    public int getLastWindowHeight() {
        return Integer.parseInt(properties.getProperty("ui.last.window.height", "1000"));
    }
    
    public boolean isVSyncEnabled() {
        return Boolean.parseBoolean(properties.getProperty("performance.vsync.enabled", "true"));
    }

    public float getVertexPointSize() {
        return Float.parseFloat(properties.getProperty("viewport.vertex.point.size", String.valueOf(DEFAULT_VERTEX_POINT_SIZE)));
    }

    // Setter methods for configuration values
    
    public void setLastWindowSize(int width, int height, boolean maximized) {
        properties.setProperty("ui.last.window.width", String.valueOf(width));
        properties.setProperty("ui.last.window.height", String.valueOf(height));
        properties.setProperty("ui.last.window.maximized", String.valueOf(maximized));
    }

    public void setVertexPointSize(float size) {
        properties.setProperty("viewport.vertex.point.size", String.valueOf(size));
    }
}