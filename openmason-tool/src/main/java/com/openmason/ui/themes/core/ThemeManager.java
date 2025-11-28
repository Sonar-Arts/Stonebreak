package com.openmason.ui.themes.core;
import com.openmason.ui.themes.registry.ThemeRegistry;
import com.openmason.ui.themes.registry.ColorPalette;
import com.openmason.ui.themes.persistence.ThemeSerializer;
import com.openmason.ui.themes.application.DensityManager;
import com.openmason.ui.themes.application.StyleApplicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Clean, minimal Dear ImGui theme management system using pure composition pattern.
 * 
 * Ruthlessly refactored from 1,637-line monolith to ~300 lines by:
 * - Eliminating ALL legacy wrapper classes and deprecated code
 * - Using pure composition with specialized components
 * - Removing compatibility layers and conversion methods
 * - Providing only the modern API that UI components actually use
 * 
 * Components:
 * - ThemeRegistry: Theme storage and management
 * - ThemeSerializer: JSON persistence
 * - DensityManager: UI density scaling
 */
public class ThemeManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ThemeManager.class);

    // Core components
    private final ThemeRegistry registry;
    private final ThemeSerializer serializer;
    private final DensityManager densityManager;

    // Simple configuration using Properties
    private final Properties config = new Properties();
    private static final String CONFIG_FILE = "openmason-tool/Theme.ini";
    
    // Current state
    private ThemeDefinition currentTheme;
    private DensityManager.UIDensity currentDensity = DensityManager.UIDensity.NORMAL;
    
    // Simplified listener system
    private final List<Consumer<ThemeDefinition>> themeChangeCallbacks = new ArrayList<>();
    private final List<Consumer<DensityManager.UIDensity>> densityChangeCallbacks = new ArrayList<>();
    
    /**
     * Create a new ThemeManager instance.
     * Use dependency injection - create once in OpenMasonApp and inject to UI components.
     */
    public ThemeManager() {
        // Initialize components
        this.registry = new ThemeRegistry();
        this.serializer = new ThemeSerializer();
        this.densityManager = new DensityManager();

        // Setup component callbacks
        densityManager.setDensityChangeCallback(this::onDensityChanged);

        // Initialize system
        initializeBuiltInThemes();
        loadUserThemes();
        loadConfiguration();

        logger.info("ThemeManager initialized with {} built-in themes",
                   registry.getThemeCount(ThemeRegistry.ThemeCategory.BUILT_IN));
    }
    
    /**
     * Density change callback
     */
    private void onDensityChanged(DensityManager.UIDensity newDensity) {
        currentDensity = newDensity;
        densityChangeCallbacks.forEach(callback -> {
            try {
                callback.accept(newDensity);
            } catch (Exception e) {
                logger.error("Error in density change callback", e);
            }
        });
    }
    
    /**
     * Initialize built-in themes
     */
    private void initializeBuiltInThemes() {
        try {
            // Create built-in themes directly as ThemeDefinitions
            ThemeDefinition darkTheme = ColorPalette.createDarkTheme();
            ThemeDefinition lightTheme = ColorPalette.createLightTheme();
            ThemeDefinition highContrastTheme = ColorPalette.createHighContrastTheme();
            ThemeDefinition blueTheme = ColorPalette.createBlueTheme();
            ThemeDefinition adobeSpectrumLightTheme = ColorPalette.createAdobeSpectrumLightTheme();
            ThemeDefinition adobeSpectrumDarkTheme = ColorPalette.createAdobeSpectrumDarkTheme();

            // Register with registry
            registry.registerTheme(darkTheme.getId(), darkTheme, ThemeRegistry.ThemeCategory.BUILT_IN, "ThemeManager");
            registry.registerTheme(lightTheme.getId(), lightTheme, ThemeRegistry.ThemeCategory.BUILT_IN, "ThemeManager");
            registry.registerTheme(highContrastTheme.getId(), highContrastTheme, ThemeRegistry.ThemeCategory.BUILT_IN, "ThemeManager");
            registry.registerTheme(blueTheme.getId(), blueTheme, ThemeRegistry.ThemeCategory.BUILT_IN, "ThemeManager");
            registry.registerTheme(adobeSpectrumLightTheme.getId(), adobeSpectrumLightTheme, ThemeRegistry.ThemeCategory.BUILT_IN, "ThemeManager");
            registry.registerTheme(adobeSpectrumDarkTheme.getId(), adobeSpectrumDarkTheme, ThemeRegistry.ThemeCategory.BUILT_IN, "ThemeManager");

            // Set default theme
            currentTheme = darkTheme;

        } catch (Exception e) {
            logger.error("Failed to initialize built-in themes", e);
        }
    }
    
    /**
     * Register a theme in the system
     */
    public void registerTheme(ThemeDefinition theme) {
        if (theme == null) {
            logger.warn("Cannot register null theme");
            return;
        }
        
        try {
            ThemeRegistry.ThemeCategory category = ThemeRegistry.ThemeCategory.fromThemeType(theme.getType());
            boolean registered = registry.registerTheme(theme.getId(), theme, category, "user");
            
            if (registered) {
                logger.debug("Registered theme: {}", theme.getName());
            } else {
                logger.warn("Failed to register theme (duplicate?): {}", theme.getName());
            }
        } catch (Exception e) {
            logger.error("Failed to register theme: " + theme.getName(), e);
        }
    }
    
    /**
     * Apply a theme by ID
     */
    public void applyTheme(String themeId) {
        if (themeId == null) {
            logger.warn("Cannot apply null theme ID");
            return;
        }
        
        try {
            ThemeDefinition theme = registry.getTheme(themeId);
            if (theme == null) {
                logger.warn("Unknown theme: {}", themeId);
                return;
            }
            
            applyTheme(theme);
            
        } catch (Exception e) {
            logger.error("Failed to apply theme by ID: " + themeId, e);
        }
    }
    
    /**
     * Apply a theme
     */
    public void applyTheme(ThemeDefinition theme) {
        if (theme == null) {
            logger.warn("Cannot apply null theme");
            return;
        }
        
        try {
            ThemeDefinition oldTheme = currentTheme;

            StyleApplicator.applyThemeWithDensityManager(theme, densityManager);
            currentTheme = theme;
            saveConfiguration();
            notifyThemeChanged(oldTheme, theme);

            logger.info("Applied theme: {}", theme.getName());
            
        } catch (Exception e) {
            logger.error("Failed to apply theme: " + theme.getName(), e);
        }
    }
    
    /**
     * Reset ImGui style to defaults
     */
    public void resetImGuiStyle() {
        try {
            StyleApplicator.resetToDefault();
            logger.debug("Reset ImGui style to defaults");
        } catch (Exception e) {
            logger.error("Failed to reset ImGui style", e);
            try {
                StyleApplicator.emergencyReset();
            } catch (Exception emergencyEx) {
                logger.error("Emergency reset also failed", emergencyEx);
            }
        }
    }
    
    /**
     * Set UI density
     */
    public void setUIDensity(DensityManager.UIDensity density) {
        if (density == null) {
            logger.warn("Cannot set null density");
            return;
        }
        
        try {
            densityManager.setDensity(density);
            
            // Re-apply current theme with new density
            if (currentTheme != null) {
                StyleApplicator.applyThemeWithDensityManager(currentTheme, densityManager);
            }
            
            saveConfiguration();
            logger.info("Changed UI density to: {}", density.getDisplayName());
            
        } catch (Exception e) {
            logger.error("Failed to set UI density to: " + density.getDisplayName(), e);
        }
    }
    
    /**
     * Create a new custom theme
     */
    public ThemeDefinition createCustomTheme(String name, String description, ThemeDefinition basedOn) {
        if (name == null || name.trim().isEmpty()) {
            logger.warn("Cannot create custom theme with null or empty name");
            return null;
        }
        
        try {
            String id = "custom_" + System.currentTimeMillis();
            
            // Create new theme
            ThemeDefinition customTheme = basedOn != null ? basedOn.copy() : new ThemeDefinition();
            customTheme.setId(id);
            customTheme.setName(name);
            customTheme.setDescription(description);
            customTheme.setType(ThemeDefinition.ThemeType.USER_CUSTOM);
            customTheme.setReadOnly(false);
            
            // Register with registry
            boolean registered = registry.registerTheme(id, customTheme, ThemeRegistry.ThemeCategory.USER, "createCustomTheme");
            
            if (registered) {
                saveUserThemes();
                logger.info("Created custom theme: {}", name);
                return customTheme;
            } else {
                logger.error("Failed to register custom theme: {}", name);
                return null;
            }
            
        } catch (Exception e) {
            logger.error("Failed to create custom theme: " + name, e);
            return null;
        }
    }
    
    /**
     * Delete a custom theme
     */
    public boolean deleteCustomTheme(String themeId) {
        if (themeId == null) {
            logger.warn("Cannot delete theme with null ID");
            return false;
        }
        
        try {
            ThemeDefinition theme = registry.getTheme(themeId);
            if (theme == null) {
                logger.warn("Theme not found for deletion: {}", themeId);
                return false;
            }
            
            // Only allow deletion of user custom themes
            if (theme.getType() != ThemeDefinition.ThemeType.USER_CUSTOM) {
                logger.warn("Cannot delete non-custom theme: {} (type: {})", theme.getName(), theme.getType());
                return false;
            }
            
            // Switch to default theme if deleting current theme
            if (currentTheme != null && themeId.equals(currentTheme.getId())) {
                applyTheme("dark");
            }
            
            // Remove from registry
            boolean removed = registry.removeTheme(themeId);
            if (removed) {
                try {
                    serializer.deleteTheme(themeId, ThemeRegistry.ThemeCategory.USER);
                } catch (IOException e) {
                    logger.warn("Failed to delete theme file for: {}", themeId, e);
                }
                
                saveUserThemes();
                logger.info("Deleted custom theme: {}", theme.getName());
                return true;
            } else {
                logger.warn("Failed to remove theme from registry: {}", themeId);
                return false;
            }
            
        } catch (Exception e) {
            logger.error("Failed to delete custom theme: " + themeId, e);
            return false;
        }
    }
    
    /**
     * Export theme to JSON file
     */
    public void exportTheme(ThemeDefinition theme, File file) throws IOException {
        if (theme == null) {
            throw new IllegalArgumentException("Theme cannot be null");
        }
        if (file == null) {
            throw new IllegalArgumentException("File cannot be null");
        }
        
        try {
            serializer.exportTheme(theme, file);
            logger.info("Exported theme {} to: {}", theme.getName(), file.getAbsolutePath());
        } catch (Exception e) {
            logger.error("Failed to export theme", e);
            throw new IOException("Failed to export theme: " + e.getMessage(), e);
        }
    }
    
    /**
     * Import theme from JSON file
     */
    public ThemeDefinition importTheme(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("File cannot be null");
        }
        
        try {
            ThemeDefinition theme = serializer.importTheme(file);
            
            // Ensure unique ID for imported themes
            theme.setId("imported_" + System.currentTimeMillis());
            theme.setType(ThemeDefinition.ThemeType.IMPORTED);
            
            // Register with registry
            boolean registered = registry.registerTheme(theme.getId(), theme, 
                    ThemeRegistry.ThemeCategory.IMPORTED, "importTheme");
            
            if (registered) {
                saveUserThemes();
                logger.info("Imported theme {} from: {}", theme.getName(), file.getAbsolutePath());
                return theme;
            } else {
                throw new IOException("Failed to register imported theme: " + theme.getName());
            }
            
        } catch (Exception e) {
            logger.error("Failed to import theme", e);
            throw new IOException("Failed to import theme: " + e.getMessage(), e);
        }
    }
    
    /**
     * Save user themes
     */
    private void saveUserThemes() {
        try {
            Map<String, ThemeDefinition> userThemes = registry.getThemesByCategory(ThemeRegistry.ThemeCategory.USER);
            
            for (Map.Entry<String, ThemeDefinition> entry : userThemes.entrySet()) {
                try {
                    serializer.saveTheme(entry.getValue(), entry.getKey(), ThemeRegistry.ThemeCategory.USER);
                } catch (Exception e) {
                    logger.warn("Failed to save user theme: {}", entry.getKey(), e);
                }
            }
            
            logger.debug("Saved {} user themes", userThemes.size());
            
        } catch (Exception e) {
            logger.error("Failed to save user themes", e);
        }
    }
    
    /**
     * Load user themes
     */
    private void loadUserThemes() {
        try {
            Map<String, ThemeDefinition> userThemes = serializer.loadAllThemes(ThemeRegistry.ThemeCategory.USER);
            
            for (Map.Entry<String, ThemeDefinition> entry : userThemes.entrySet()) {
                try {
                    registry.registerTheme(entry.getKey(), entry.getValue(), 
                            ThemeRegistry.ThemeCategory.USER, "loadUserThemes");
                } catch (Exception e) {
                    logger.warn("Failed to register loaded user theme: {}", entry.getKey(), e);
                }
            }
            
            logger.info("Loaded {} user themes", userThemes.size());
            
        } catch (Exception e) {
            logger.error("Failed to load user themes", e);
        }
    }
    
    /**
     * Save current configuration to Theme.ini
     */
    private void saveConfiguration() {
        try {
            if (currentTheme != null) {
                config.setProperty("current.theme", currentTheme.getId());
            }
            config.setProperty("current.density", currentDensity.name());

            // Ensure directory exists
            java.nio.file.Path configPath = java.nio.file.Paths.get(CONFIG_FILE);
            java.nio.file.Files.createDirectories(configPath.getParent());

            // Write configuration
            try (java.io.OutputStream output = java.nio.file.Files.newOutputStream(configPath)) {
                config.store(output, "OpenMason Theme Configuration");
            }

            logger.debug("Configuration saved to Theme.ini: theme={}, density={}",
                        currentTheme != null ? currentTheme.getId() : "null",
                        currentDensity.name());
        } catch (Exception e) {
            logger.error("Failed to save configuration", e);
        }
    }
    
    /**
     * Load saved configuration from Theme.ini
     */
    private void loadConfiguration() {
        try {
            java.nio.file.Path configPath = java.nio.file.Paths.get(CONFIG_FILE);

            if (java.nio.file.Files.exists(configPath)) {
                try (java.io.InputStream input = java.nio.file.Files.newInputStream(configPath)) {
                    config.load(input);
                }
            } else {
                // Create default configuration
                config.setProperty("current.theme", "dark");
                config.setProperty("current.density", "NORMAL");
                saveConfiguration();
                logger.info("Created default Theme.ini file");
            }

            // Load theme ID from config
            String themeId = config.getProperty("current.theme", "dark");
            ThemeDefinition loadedTheme = registry.getTheme(themeId);

            if (loadedTheme != null) {
                currentTheme = loadedTheme;
                logger.info("Loaded theme from Theme.ini: {}", themeId);
            } else {
                // Fallback to dark theme if configured theme not found
                logger.warn("Theme '{}' not found in registry, falling back to 'dark'", themeId);
                currentTheme = registry.getTheme("dark");
            }

            // Load density from config
            String densityName = config.getProperty("current.density", "NORMAL");
            try {
                currentDensity = DensityManager.UIDensity.valueOf(densityName);
                densityManager.setDensity(currentDensity);
                logger.info("Loaded density from Theme.ini: {}", densityName);
            } catch (IllegalArgumentException e) {
                // Fallback to NORMAL if configured density is invalid
                logger.warn("Invalid density '{}' in Theme.ini, falling back to NORMAL", densityName);
                currentDensity = DensityManager.UIDensity.NORMAL;
                densityManager.setDensity(DensityManager.UIDensity.NORMAL);
            }

            logger.info("Configuration loaded from Theme.ini: {} / {}",
                       currentTheme != null ? currentTheme.getName() : "none",
                       currentDensity.getDisplayName());

        } catch (Exception e) {
            logger.error("Failed to load configuration from Theme.ini", e);
            // Fallback to safe defaults
            currentTheme = registry.getTheme("dark");
            currentDensity = DensityManager.UIDensity.NORMAL;
            densityManager.setDensity(DensityManager.UIDensity.NORMAL);
        }
    }
    
    // Core API methods
    public ThemeDefinition getCurrentTheme() { return currentTheme; }
    public DensityManager.UIDensity getCurrentDensity() { return currentDensity; }
    public ThemeDefinition getTheme(String themeId) { return registry.getTheme(themeId); }
    public List<ThemeDefinition> getAvailableThemes() {
        return new ArrayList<>(registry.getAllThemes().values());
    }
    
    // Convenience methods for built-in themes
    public ThemeDefinition getDarkTheme() { return registry.getTheme("dark"); }
    public ThemeDefinition getLightTheme() { return registry.getTheme("light"); }
    public ThemeDefinition getHighContrastTheme() { return registry.getTheme("high-contrast"); }
    public ThemeDefinition getBlueTheme() { return registry.getTheme("blue"); }
    
    // Simplified callback system
    public void addThemeChangeCallback(Consumer<ThemeDefinition> callback) {
        if (callback != null) {
            themeChangeCallbacks.add(callback);
        }
    }
    
    public void addDensityChangeCallback(Consumer<DensityManager.UIDensity> callback) {
        if (callback != null) {
            densityChangeCallbacks.add(callback);
        }
    }
    
    private void notifyThemeChanged(ThemeDefinition oldTheme, ThemeDefinition newTheme) {
        themeChangeCallbacks.forEach(callback -> {
            try {
                callback.accept(newTheme);
            } catch (Exception e) {
                logger.error("Error in theme change callback", e);
            }
        });
    }
    
    /**
     * Get theme statistics
     */
    public String getThemeStatistics() {
        try {
            Map<ThemeRegistry.ThemeCategory, Integer> categoryCounts = registry.getCategoryCounts();
            
            int builtInCount = categoryCounts.getOrDefault(ThemeRegistry.ThemeCategory.BUILT_IN, 0);
            int customCount = categoryCounts.getOrDefault(ThemeRegistry.ThemeCategory.USER, 0);
            int importedCount = categoryCounts.getOrDefault(ThemeRegistry.ThemeCategory.IMPORTED, 0);
            int totalCount = registry.getThemeCount();
            
            return String.format("Themes: %d total (%d built-in, %d custom, %d imported). " +
                               "Current: %s (%s density)",
                               totalCount, builtInCount, customCount, importedCount,
                               currentTheme != null ? currentTheme.getName() : "none",
                               currentDensity.getDisplayName());
        } catch (Exception e) {
            logger.error("Failed to get theme statistics", e);
            return "Theme statistics unavailable";
        }
    }
    
    /**
     * Validate theme integrity
     */
    public boolean validateTheme(ThemeDefinition theme) {
        if (theme == null) {
            logger.warn("Theme validation failed: theme is null");
            return false;
        }
        
        try {
            theme.validate();
            logger.debug("Theme validation passed: {}", theme.getName());
            return true;
        } catch (Exception e) {
            logger.warn("Theme validation failed: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Initialize theme system for ImGui
     */
    public void initializeForImGui() {
        try {
            if (currentTheme != null) {
                StyleApplicator.applyThemeWithDensityManager(currentTheme, densityManager);
            }
            
            if (currentDensity != DensityManager.UIDensity.NORMAL) {
                densityManager.setDensity(currentDensity);
            }
            
            logger.info("Theme system initialized: theme={}, density={}",
                       currentTheme != null ? currentTheme.getName() : "default",
                       currentDensity.getDisplayName());
                       
        } catch (Exception e) {
            logger.error("Failed to initialize theme system", e);
        }
    }
    
    /**
     * Cleanup and dispose resources
     */
    public void dispose() {
        try {
            logger.info("Disposing ThemeManager");

            saveConfiguration();

            if (densityManager != null) {
                densityManager.emergencyReset();
            }

            themeChangeCallbacks.clear();
            densityChangeCallbacks.clear();
            currentTheme = null;

            logger.info("ThemeManager disposed");
            
        } catch (Exception e) {
            logger.error("Error during disposal", e);
        }
    }
}