package com.openmason.app;

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
public class AppConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);
    
    // Configuration file paths
    private static final String CONFIG_DIR = ".openmason";
    private static final String CONFIG_FILE = "config.properties";
    private static final String DEFAULT_CONFIG_RESOURCE = "/config/default.properties";
    
    // Default configuration values
    private static final String DEFAULT_STONEBREAK_PROJECT_PATH = "../stonebreak-game";
    private static final String DEFAULT_MODELS_PATH = "src/main/resources/models";
    private static final String DEFAULT_TEXTURES_PATH = "src/main/resources/textures";
    private static final boolean DEFAULT_AUTO_SAVE_ENABLED = true;
    private static final int DEFAULT_AUTO_SAVE_INTERVAL = 300; // 5 minutes
    private static final boolean DEFAULT_DARK_THEME_ENABLED = true;
    
    private Properties properties;
    private Path configFilePath;
    
    /**
     * Initialize application configuration.
     */
    public AppConfig() {
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
            
            logger.info("Configuration initialized successfully");
            
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
            logger.info("Loaded configuration from: {}", configFilePath);
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
        
        // Performance settings
        properties.setProperty("performance.max.texture.cache", "512"); // MB
        properties.setProperty("performance.max.model.cache", "128");   // MB
        properties.setProperty("performance.vsync.enabled", "true");
        
        logger.info("Loaded default configuration");
    }
    
    /**
     * Save current configuration to file.
     */
    public void saveConfiguration() {
        try {
            if (configFilePath != null) {
                Files.createDirectories(configFilePath.getParent());
                
                try (var output = Files.newOutputStream(configFilePath)) {
                    properties.store(output, "OpenMason Configuration");
                    logger.debug("Configuration saved to: {}", configFilePath);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to save configuration", e);
        }
    }
    
    // Getter methods for configuration values
    
    public String getStonebreakProjectPath() {
        return properties.getProperty("stonebreak.project.path", DEFAULT_STONEBREAK_PROJECT_PATH);
    }
    
    public String getStonebreakModelsPath() {
        return properties.getProperty("stonebreak.models.path", DEFAULT_MODELS_PATH);
    }
    
    public String getStonebreakTexturesPath() {
        return properties.getProperty("stonebreak.textures.path", DEFAULT_TEXTURES_PATH);
    }
    
    public boolean isAutoSaveEnabled() {
        return Boolean.parseBoolean(properties.getProperty("app.auto.save.enabled", String.valueOf(DEFAULT_AUTO_SAVE_ENABLED)));
    }
    
    public int getAutoSaveInterval() {
        return Integer.parseInt(properties.getProperty("app.auto.save.interval", String.valueOf(DEFAULT_AUTO_SAVE_INTERVAL)));
    }
    
    public boolean isDarkThemeEnabled() {
        return Boolean.parseBoolean(properties.getProperty("app.dark.theme.enabled", String.valueOf(DEFAULT_DARK_THEME_ENABLED)));
    }
    
    public int getLastWindowWidth() {
        return Integer.parseInt(properties.getProperty("ui.last.window.width", "1600"));
    }
    
    public int getLastWindowHeight() {
        return Integer.parseInt(properties.getProperty("ui.last.window.height", "1000"));
    }
    
    public boolean wasLastWindowMaximized() {
        return Boolean.parseBoolean(properties.getProperty("ui.last.window.maximized", "false"));
    }
    
    public int getMaxTextureCacheSize() {
        return Integer.parseInt(properties.getProperty("performance.max.texture.cache", "512"));
    }
    
    public int getMaxModelCacheSize() {
        return Integer.parseInt(properties.getProperty("performance.max.model.cache", "128"));
    }
    
    public boolean isVSyncEnabled() {
        return Boolean.parseBoolean(properties.getProperty("performance.vsync.enabled", "true"));
    }
    
    // Setter methods for configuration values
    
    public void setStonebreakProjectPath(String path) {
        properties.setProperty("stonebreak.project.path", path);
    }
    
    public void setAutoSaveEnabled(boolean enabled) {
        properties.setProperty("app.auto.save.enabled", String.valueOf(enabled));
    }
    
    public void setAutoSaveInterval(int interval) {
        properties.setProperty("app.auto.save.interval", String.valueOf(interval));
    }
    
    public void setDarkThemeEnabled(boolean enabled) {
        properties.setProperty("app.dark.theme.enabled", String.valueOf(enabled));
    }
    
    public void setLastWindowSize(int width, int height, boolean maximized) {
        properties.setProperty("ui.last.window.width", String.valueOf(width));
        properties.setProperty("ui.last.window.height", String.valueOf(height));
        properties.setProperty("ui.last.window.maximized", String.valueOf(maximized));
    }
    
    public void setMaxTextureCacheSize(int sizeMB) {
        properties.setProperty("performance.max.texture.cache", String.valueOf(sizeMB));
    }
    
    public void setMaxModelCacheSize(int sizeMB) {
        properties.setProperty("performance.max.model.cache", String.valueOf(sizeMB));
    }
    
    public void setVSyncEnabled(boolean enabled) {
        properties.setProperty("performance.vsync.enabled", String.valueOf(enabled));
    }
    
    /**
     * Get the full path to Stonebreak models directory.
     */
    public Path getFullStonebreakModelsPath() {
        return Paths.get(getStonebreakProjectPath()).resolve(getStonebreakModelsPath());
    }
    
    /**
     * Get the full path to Stonebreak textures directory.
     */
    public Path getFullStonebreakTexturesPath() {
        return Paths.get(getStonebreakProjectPath()).resolve(getStonebreakTexturesPath());
    }
}