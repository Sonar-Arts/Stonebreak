package com.openmason.ui.themes;

import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.Preferences;

/**
 * Professional theme customization system with multiple color schemes,
 * UI density options, real-time preview, and custom theme creation.
 */
public class ThemeManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ThemeManager.class);
    
    // Singleton instance
    private static ThemeManager instance;
    
    // Properties
    private final ObjectProperty<Theme> currentTheme = new SimpleObjectProperty<>();
    private final ObjectProperty<UIDensity> currentDensity = new SimpleObjectProperty<>(UIDensity.STANDARD);
    private final BooleanProperty previewMode = new SimpleBooleanProperty(false);
    private final ObservableList<Theme> availableThemes = FXCollections.observableArrayList();
    private final ObservableList<Scene> registeredScenes = FXCollections.observableArrayList();
    
    // Internal state
    private final Map<String, Theme> themeRegistry = new ConcurrentHashMap<>();
    private final List<ThemeChangeListener> listeners = new ArrayList<>();
    private Preferences preferences;
    private Theme previewTheme;
    
    /**
     * UI density options for professional customization
     */
    public enum UIDensity {
        COMPACT("Compact", "Smaller controls and reduced spacing", 0.85),
        STANDARD("Standard", "Normal control sizes and spacing", 1.0),
        SPACIOUS("Spacious", "Larger controls and increased spacing", 1.15);
        
        private final String displayName;
        private final String description;
        private final double scaleFactor;
        
        UIDensity(String displayName, String description, double scaleFactor) {
            this.displayName = displayName;
            this.description = description;
            this.scaleFactor = scaleFactor;
        }
        
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public double getScaleFactor() { return scaleFactor; }
    }
    
    /**
     * Theme change listener interface
     */
    public interface ThemeChangeListener {
        void onThemeChanged(Theme oldTheme, Theme newTheme);
        void onDensityChanged(UIDensity oldDensity, UIDensity newDensity);
        void onPreviewModeChanged(boolean previewMode);
    }
    
    /**
     * Theme definition class
     */
    public static class Theme {
        private final String id;
        private final String name;
        private final String description;
        private final ThemeType type;
        private final Map<String, Color> colors;
        private final Map<String, String> cssVariables;
        private final List<String> stylesheets;
        private boolean readOnly;
        
        public Theme(String id, String name, String description, ThemeType type) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.type = type;
            this.colors = new HashMap<>();
            this.cssVariables = new HashMap<>();
            this.stylesheets = new ArrayList<>();
            this.readOnly = false;
        }
        
        // Getters
        public String getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public ThemeType getType() { return type; }
        public Map<String, Color> getColors() { return colors; }
        public Map<String, String> getCssVariables() { return cssVariables; }
        public List<String> getStylesheets() { return stylesheets; }
        public boolean isReadOnly() { return readOnly; }
        
        // Setters
        public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
        
        // Color management
        public void setColor(String name, Color color) {
            if (!readOnly) {
                colors.put(name, color);
            }
        }
        
        public Color getColor(String name) {
            return colors.get(name);
        }
        
        public void setCssVariable(String name, String value) {
            if (!readOnly) {
                cssVariables.put(name, value);
            }
        }
        
        public String getCssVariable(String name) {
            return cssVariables.get(name);
        }
        
        public void addStylesheet(String stylesheetPath) {
            if (!readOnly && !stylesheets.contains(stylesheetPath)) {
                stylesheets.add(stylesheetPath);
            }
        }
        
        @Override
        public String toString() {
            return name;
        }
    }
    
    /**
     * Theme types
     */
    public enum ThemeType {
        BUILT_IN("Built-in"),
        USER_CUSTOM("Custom"),
        IMPORTED("Imported");
        
        private final String displayName;
        
        ThemeType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() { return displayName; }
    }
    
    private ThemeManager() {
        this.preferences = Preferences.userRoot().node(this.getClass().getName());
        initializeBuiltInThemes();
        loadUserThemes();
        loadSavedConfiguration();
    }
    
    public static synchronized ThemeManager getInstance() {
        if (instance == null) {
            instance = new ThemeManager();
        }
        return instance;
    }
    
    /**
     * Initialize built-in themes
     */
    private void initializeBuiltInThemes() {
        // Dark Theme (default)
        Theme darkTheme = createDarkTheme();
        registerTheme(darkTheme);
        
        // Light Theme
        Theme lightTheme = createLightTheme();
        registerTheme(lightTheme);
        
        // High Contrast Theme
        Theme highContrastTheme = createHighContrastTheme();
        registerTheme(highContrastTheme);
        
        // Blue Theme
        Theme blueTheme = createBlueTheme();
        registerTheme(blueTheme);
        
        // Set default theme
        if (currentTheme.get() == null) {
            currentTheme.set(darkTheme);
        }
        
        logger.info("Initialized {} built-in themes", themeRegistry.size());
    }
    
    /**
     * Create dark theme (OpenMason default)
     */
    private Theme createDarkTheme() {
        Theme theme = new Theme("dark", "Dark", "Professional dark theme for extended usage", ThemeType.BUILT_IN);
        theme.setReadOnly(true);
        
        // Primary colors
        theme.setColor("background-primary", Color.web("#2b2b2b"));
        theme.setColor("background-secondary", Color.web("#3c3c3c"));
        theme.setColor("background-tertiary", Color.web("#1e1e1e"));
        theme.setColor("background-elevated", Color.web("#404040"));
        
        // Text colors
        theme.setColor("text-primary", Color.web("#ffffff"));
        theme.setColor("text-secondary", Color.web("#e0e0e0"));
        theme.setColor("text-disabled", Color.web("#6a6a6a"));
        theme.setColor("text-muted", Color.web("#b0b0b0"));
        
        // Accent colors
        theme.setColor("accent-primary", Color.web("#0099ff"));
        theme.setColor("accent-hover", Color.web("#0077cc"));
        theme.setColor("accent-success", Color.web("#5cdb5c"));
        theme.setColor("accent-warning", Color.web("#ffb84d"));
        theme.setColor("accent-error", Color.web("#ff6b6b"));
        
        // Borders
        theme.setColor("border-primary", Color.web("#555555"));
        theme.setColor("border-secondary", Color.web("#404040"));
        theme.setColor("border-focus", Color.web("#007ACC"));
        
        theme.addStylesheet("/css/dark-theme.css");
        
        return theme;
    }
    
    /**
     * Create light theme
     */
    private Theme createLightTheme() {
        Theme theme = new Theme("light", "Light", "Clean light theme for bright environments", ThemeType.BUILT_IN);
        theme.setReadOnly(true);
        
        // Primary colors
        theme.setColor("background-primary", Color.web("#ffffff"));
        theme.setColor("background-secondary", Color.web("#f5f5f5"));
        theme.setColor("background-tertiary", Color.web("#e8e8e8"));
        theme.setColor("background-elevated", Color.web("#ffffff"));
        
        // Text colors
        theme.setColor("text-primary", Color.web("#333333"));
        theme.setColor("text-secondary", Color.web("#666666"));
        theme.setColor("text-disabled", Color.web("#cccccc"));
        theme.setColor("text-muted", Color.web("#888888"));
        
        // Accent colors
        theme.setColor("accent-primary", Color.web("#0066cc"));
        theme.setColor("accent-hover", Color.web("#0052a3"));
        theme.setColor("accent-success", Color.web("#28a745"));
        theme.setColor("accent-warning", Color.web("#ffc107"));
        theme.setColor("accent-error", Color.web("#dc3545"));
        
        // Borders
        theme.setColor("border-primary", Color.web("#dddddd"));
        theme.setColor("border-secondary", Color.web("#eeeeee"));
        theme.setColor("border-focus", Color.web("#0066cc"));
        
        theme.addStylesheet("/css/light-theme.css");
        
        return theme;
    }
    
    /**
     * Create high contrast theme for accessibility
     */
    private Theme createHighContrastTheme() {
        Theme theme = new Theme("high-contrast", "High Contrast", 
                               "High contrast theme for accessibility", ThemeType.BUILT_IN);
        theme.setReadOnly(true);
        
        // Primary colors - maximum contrast
        theme.setColor("background-primary", Color.web("#000000"));
        theme.setColor("background-secondary", Color.web("#1a1a1a"));
        theme.setColor("background-tertiary", Color.web("#000000"));
        theme.setColor("background-elevated", Color.web("#2a2a2a"));
        
        // Text colors - high contrast
        theme.setColor("text-primary", Color.web("#ffffff"));
        theme.setColor("text-secondary", Color.web("#f0f0f0"));
        theme.setColor("text-disabled", Color.web("#808080"));
        theme.setColor("text-muted", Color.web("#cccccc"));
        
        // Accent colors - high visibility
        theme.setColor("accent-primary", Color.web("#00ff00"));
        theme.setColor("accent-hover", Color.web("#00cc00"));
        theme.setColor("accent-success", Color.web("#00ff00"));
        theme.setColor("accent-warning", Color.web("#ffff00"));
        theme.setColor("accent-error", Color.web("#ff0000"));
        
        // Borders - high contrast
        theme.setColor("border-primary", Color.web("#ffffff"));
        theme.setColor("border-secondary", Color.web("#cccccc"));
        theme.setColor("border-focus", Color.web("#00ff00"));
        
        theme.addStylesheet("/css/high-contrast-theme.css");
        
        return theme;
    }
    
    /**
     * Create blue theme
     */
    private Theme createBlueTheme() {
        Theme theme = new Theme("blue", "Blue", "Professional blue theme", ThemeType.BUILT_IN);
        theme.setReadOnly(true);
        
        // Primary colors - blue tinted
        theme.setColor("background-primary", Color.web("#1e2a3a"));
        theme.setColor("background-secondary", Color.web("#2a3b4d"));
        theme.setColor("background-tertiary", Color.web("#151e2a"));
        theme.setColor("background-elevated", Color.web("#354a5f"));
        
        // Text colors
        theme.setColor("text-primary", Color.web("#ffffff"));
        theme.setColor("text-secondary", Color.web("#c8d4e0"));
        theme.setColor("text-disabled", Color.web("#6a7a8a"));
        theme.setColor("text-muted", Color.web("#9db0c0"));
        
        // Accent colors - blue scheme
        theme.setColor("accent-primary", Color.web("#4d9fff"));
        theme.setColor("accent-hover", Color.web("#3380e6"));
        theme.setColor("accent-success", Color.web("#5cdb5c"));
        theme.setColor("accent-warning", Color.web("#ffb84d"));
        theme.setColor("accent-error", Color.web("#ff6b6b"));
        
        // Borders
        theme.setColor("border-primary", Color.web("#5a6a7a"));
        theme.setColor("border-secondary", Color.web("#4a5a6a"));
        theme.setColor("border-focus", Color.web("#4d9fff"));
        
        theme.addStylesheet("/css/blue-theme.css");
        
        return theme;
    }
    
    /**
     * Register a theme in the system
     */
    public void registerTheme(Theme theme) {
        themeRegistry.put(theme.getId(), theme);
        if (!availableThemes.contains(theme)) {
            availableThemes.add(theme);
        }
        logger.debug("Registered theme: {}", theme.getName());
    }
    
    /**
     * Apply a theme to all registered scenes
     */
    public void applyTheme(String themeId) {
        Theme theme = themeRegistry.get(themeId);
        if (theme == null) {
            logger.warn("Unknown theme: {}", themeId);
            return;
        }
        
        applyTheme(theme);
    }
    
    /**
     * Apply a theme to all registered scenes
     */
    public void applyTheme(Theme theme) {
        Theme oldTheme = currentTheme.get();
        
        if (!previewMode.get()) {
            currentTheme.set(theme);
        } else {
            previewTheme = theme;
        }
        
        // Apply theme to all registered scenes
        Platform.runLater(() -> {
            for (Scene scene : registeredScenes) {
                applyThemeToScene(scene, theme);
            }
        });
        
        // Save configuration if not in preview mode
        if (!previewMode.get()) {
            saveConfiguration();
        }
        
        // Notify listeners
        notifyThemeChanged(oldTheme, theme);
        
        logger.info("Applied theme: {} to {} scenes", theme.getName(), registeredScenes.size());
    }
    
    /**
     * Apply theme to a specific scene
     */
    private void applyThemeToScene(Scene scene, Theme theme) {
        // Clear existing theme stylesheets
        scene.getStylesheets().removeIf(stylesheet -> 
            stylesheet.contains("theme.css") || 
            stylesheet.contains("dark-theme.css") || 
            stylesheet.contains("light-theme.css") ||
            stylesheet.contains("high-contrast-theme.css") ||
            stylesheet.contains("blue-theme.css"));
        
        // Add theme stylesheets
        for (String stylesheet : theme.getStylesheets()) {
            try {
                String stylesheetUrl = getClass().getResource(stylesheet).toExternalForm();
                scene.getStylesheets().add(stylesheetUrl);
            } catch (Exception e) {
                logger.warn("Failed to load stylesheet: {}", stylesheet, e);
            }
        }
        
        // Apply density-specific styles
        applyDensityToScene(scene, currentDensity.get());
        
        // Apply runtime CSS variables (if supported)
        applyRuntimeThemeVariables(scene, theme);
    }
    
    /**
     * Apply UI density to a scene
     */
    private void applyDensityToScene(Scene scene, UIDensity density) {
        // Remove existing density classes
        scene.getRoot().getStyleClass().removeAll("compact", "standard", "spacious");
        
        // Add current density class
        switch (density) {
            case COMPACT:
                scene.getRoot().getStyleClass().add("compact");
                break;
            case SPACIOUS:
                scene.getRoot().getStyleClass().add("spacious");
                break;
            case STANDARD:
            default:
                scene.getRoot().getStyleClass().add("standard");
                break;
        }
    }
    
    /**
     * Apply runtime theme variables (CSS custom properties)
     */
    private void applyRuntimeThemeVariables(Scene scene, Theme theme) {
        // In JavaFX, we can't directly set CSS custom properties at runtime
        // This would be implemented using inline styles or dynamic CSS generation
        
        StringBuilder cssBuilder = new StringBuilder();
        cssBuilder.append(".root {\n");
        
        // Convert colors to CSS variables
        for (Map.Entry<String, Color> entry : theme.getColors().entrySet()) {
            String colorName = entry.getKey().replace("_", "-");
            Color color = entry.getValue();
            String colorString = String.format("#%02x%02x%02x",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
            
            cssBuilder.append("  -fx-").append(colorName).append(": ").append(colorString).append(";\n");
        }
        
        // Add custom CSS variables
        for (Map.Entry<String, String> entry : theme.getCssVariables().entrySet()) {
            cssBuilder.append("  -fx-").append(entry.getKey()).append(": ").append(entry.getValue()).append(";\n");
        }
        
        cssBuilder.append("}\n");
        
        // Apply dynamic styles (this is a simplified approach)
        scene.getRoot().setStyle(cssBuilder.toString());
    }
    
    /**
     * Register a scene for theme management
     */
    public void registerScene(Scene scene) {
        if (!registeredScenes.contains(scene)) {
            registeredScenes.add(scene);
            
            // Apply current theme immediately
            if (currentTheme.get() != null) {
                applyThemeToScene(scene, currentTheme.get());
            }
            
            logger.debug("Registered scene for theme management");
        }
    }
    
    /**
     * Unregister a scene from theme management
     */
    public void unregisterScene(Scene scene) {
        registeredScenes.remove(scene);
        logger.debug("Unregistered scene from theme management");
    }
    
    /**
     * Set UI density
     */
    public void setUIDensity(UIDensity density) {
        UIDensity oldDensity = currentDensity.get();
        currentDensity.set(density);
        
        // Apply density to all scenes
        Platform.runLater(() -> {
            for (Scene scene : registeredScenes) {
                applyDensityToScene(scene, density);
            }
        });
        
        saveConfiguration();
        notifyDensityChanged(oldDensity, density);
        
        logger.info("Changed UI density to: {}", density.getDisplayName());
    }
    
    /**
     * Enter preview mode
     */
    public void enterPreviewMode() {
        previewMode.set(true);
        notifyPreviewModeChanged(true);
        logger.debug("Entered theme preview mode");
    }
    
    /**
     * Exit preview mode and revert to original theme
     */
    public void exitPreviewMode() {
        if (previewMode.get()) {
            previewMode.set(false);
            previewTheme = null;
            
            // Revert to current theme
            if (currentTheme.get() != null) {
                Platform.runLater(() -> {
                    for (Scene scene : registeredScenes) {
                        applyThemeToScene(scene, currentTheme.get());
                    }
                });
            }
            
            notifyPreviewModeChanged(false);
            logger.debug("Exited theme preview mode");
        }
    }
    
    /**
     * Apply preview theme
     */
    public void previewTheme(Theme theme) {
        if (!previewMode.get()) {
            enterPreviewMode();
        }
        
        previewTheme = theme;
        
        Platform.runLater(() -> {
            for (Scene scene : registeredScenes) {
                applyThemeToScene(scene, theme);
            }
        });
        
        logger.debug("Previewing theme: {}", theme.getName());
    }
    
    /**
     * Commit preview theme as current
     */
    public void commitPreviewTheme() {
        if (previewMode.get() && previewTheme != null) {
            Theme themeToCommit = previewTheme;
            exitPreviewMode();
            applyTheme(themeToCommit);
            logger.info("Committed preview theme: {}", themeToCommit.getName());
        }
    }
    
    /**
     * Create a new custom theme
     */
    public Theme createCustomTheme(String name, String description, Theme basedOn) {
        String id = "custom_" + System.currentTimeMillis();
        Theme customTheme = new Theme(id, name, description, ThemeType.USER_CUSTOM);
        
        // Copy colors from base theme
        if (basedOn != null) {
            customTheme.getColors().putAll(basedOn.getColors());
            customTheme.getCssVariables().putAll(basedOn.getCssVariables());
        }
        
        registerTheme(customTheme);
        saveUserThemes();
        
        logger.info("Created custom theme: {}", name);
        return customTheme;
    }
    
    /**
     * Delete a custom theme
     */
    public boolean deleteCustomTheme(String themeId) {
        Theme theme = themeRegistry.get(themeId);
        if (theme != null && theme.getType() == ThemeType.USER_CUSTOM) {
            themeRegistry.remove(themeId);
            availableThemes.remove(theme);
            
            // Switch to default theme if deleting current theme
            if (currentTheme.get() == theme) {
                applyTheme("dark");
            }
            
            saveUserThemes();
            logger.info("Deleted custom theme: {}", theme.getName());
            return true;
        }
        return false;
    }
    
    /**
     * Export theme to file
     */
    public void exportTheme(Theme theme, File file) throws IOException {
        Properties props = new Properties();
        
        // Theme metadata
        props.setProperty("theme.id", theme.getId());
        props.setProperty("theme.name", theme.getName());
        props.setProperty("theme.description", theme.getDescription());
        props.setProperty("theme.type", theme.getType().name());
        
        // Colors
        for (Map.Entry<String, Color> entry : theme.getColors().entrySet()) {
            Color color = entry.getValue();
            String colorString = String.format("#%02x%02x%02x",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
            props.setProperty("color." + entry.getKey(), colorString);
        }
        
        // CSS variables
        for (Map.Entry<String, String> entry : theme.getCssVariables().entrySet()) {
            props.setProperty("css." + entry.getKey(), entry.getValue());
        }
        
        // Stylesheets
        for (int i = 0; i < theme.getStylesheets().size(); i++) {
            props.setProperty("stylesheet." + i, theme.getStylesheets().get(i));
        }
        
        try (FileOutputStream fos = new FileOutputStream(file)) {
            props.store(fos, "OpenMason Theme Export - " + theme.getName());
        }
        
        logger.info("Exported theme {} to: {}", theme.getName(), file.getAbsolutePath());
    }
    
    /**
     * Import theme from file
     */
    public Theme importTheme(File file) throws IOException {
        Properties props = new Properties();
        
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        }
        
        String id = props.getProperty("theme.id", "imported_" + System.currentTimeMillis());
        String name = props.getProperty("theme.name", "Imported Theme");
        String description = props.getProperty("theme.description", "Imported theme");
        
        Theme theme = new Theme(id, name, description, ThemeType.IMPORTED);
        
        // Load colors
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("color.")) {
                String colorName = key.substring(6);
                String colorValue = props.getProperty(key);
                try {
                    Color color = Color.web(colorValue);
                    theme.setColor(colorName, color);
                } catch (Exception e) {
                    logger.warn("Invalid color value for {}: {}", colorName, colorValue);
                }
            }
        }
        
        // Load CSS variables
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("css.")) {
                String cssName = key.substring(4);
                String cssValue = props.getProperty(key);
                theme.setCssVariable(cssName, cssValue);
            }
        }
        
        // Load stylesheets
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("stylesheet.")) {
                String stylesheet = props.getProperty(key);
                theme.addStylesheet(stylesheet);
            }
        }
        
        registerTheme(theme);
        saveUserThemes();
        
        logger.info("Imported theme {} from: {}", name, file.getAbsolutePath());
        return theme;
    }
    
    /**
     * Save user themes and configuration
     */
    private void saveUserThemes() {
        // Implementation would save custom themes to preferences or file
        try {
            // Save theme configurations
            preferences.flush();
        } catch (Exception e) {
            logger.error("Failed to save user themes", e);
        }
    }
    
    /**
     * Load user themes
     */
    private void loadUserThemes() {
        // Implementation would load custom themes from preferences or file
        try {
            // Load theme configurations
        } catch (Exception e) {
            logger.error("Failed to load user themes", e);
        }
    }
    
    /**
     * Save current configuration
     */
    private void saveConfiguration() {
        try {
            if (currentTheme.get() != null) {
                preferences.put("current.theme", currentTheme.get().getId());
            }
            preferences.put("current.density", currentDensity.get().name());
            preferences.flush();
        } catch (Exception e) {
            logger.error("Failed to save theme configuration", e);
        }
    }
    
    /**
     * Load saved configuration
     */
    private void loadSavedConfiguration() {
        try {
            String savedThemeId = preferences.get("current.theme", "dark");
            Theme savedTheme = themeRegistry.get(savedThemeId);
            if (savedTheme != null) {
                currentTheme.set(savedTheme);
            }
            
            String savedDensity = preferences.get("current.density", "STANDARD");
            try {
                currentDensity.set(UIDensity.valueOf(savedDensity));
            } catch (IllegalArgumentException e) {
                currentDensity.set(UIDensity.STANDARD);
            }
            
            logger.info("Loaded saved theme configuration: {} / {}", 
                       currentTheme.get().getName(), currentDensity.get().getDisplayName());
        } catch (Exception e) {
            logger.error("Failed to load saved configuration", e);
        }
    }
    
    // Property getters
    public Theme getCurrentTheme() { return currentTheme.get(); }
    public ObjectProperty<Theme> currentThemeProperty() { return currentTheme; }
    
    public UIDensity getCurrentDensity() { return currentDensity.get(); }
    public ObjectProperty<UIDensity> currentDensityProperty() { return currentDensity; }
    
    public boolean isPreviewMode() { return previewMode.get(); }
    public BooleanProperty previewModeProperty() { return previewMode; }
    
    public ObservableList<Theme> getAvailableThemes() { return availableThemes; }
    
    public Theme getTheme(String themeId) { return themeRegistry.get(themeId); }
    
    // Listener management
    public void addThemeChangeListener(ThemeChangeListener listener) {
        listeners.add(listener);
    }
    
    public void removeThemeChangeListener(ThemeChangeListener listener) {
        listeners.remove(listener);
    }
    
    private void notifyThemeChanged(Theme oldTheme, Theme newTheme) {
        for (ThemeChangeListener listener : listeners) {
            try {
                listener.onThemeChanged(oldTheme, newTheme);
            } catch (Exception e) {
                logger.error("Error notifying theme change listener", e);
            }
        }
    }
    
    private void notifyDensityChanged(UIDensity oldDensity, UIDensity newDensity) {
        for (ThemeChangeListener listener : listeners) {
            try {
                listener.onDensityChanged(oldDensity, newDensity);
            } catch (Exception e) {
                logger.error("Error notifying density change listener", e);
            }
        }
    }
    
    private void notifyPreviewModeChanged(boolean previewMode) {
        for (ThemeChangeListener listener : listeners) {
            try {
                listener.onPreviewModeChanged(previewMode);
            } catch (Exception e) {
                logger.error("Error notifying preview mode change listener", e);
            }
        }
    }
}