package com.openmason.ui.themes;

import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.Preferences;
import java.util.function.Consumer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Professional Dear ImGui theme customization system with multiple color schemes,
 * UI density options, real-time preview, and custom theme creation.
 * Converted from JavaFX to Dear ImGui architecture.
 */
public class ThemeManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ThemeManager.class);
    
    // Singleton instance
    private static ThemeManager instance;
    
    // State properties
    private ImGuiTheme currentTheme;
    private UIDensity currentDensity = UIDensity.STANDARD;
    private boolean previewMode = false;
    private final List<ImGuiTheme> availableThemes = new ArrayList<>();
    
    // Internal state
    private final Map<String, ImGuiTheme> themeRegistry = new ConcurrentHashMap<>();
    private final List<ThemeChangeListener> listeners = new ArrayList<>();
    private Preferences preferences;
    private ImGuiTheme previewTheme;
    private ImGuiTheme originalTheme; // For rollback
    
    // Jackson mapper for JSON serialization
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Configuration paths
    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.openmason";
    private static final String THEMES_FILE = CONFIG_DIR + "/themes.json";
    private static final String CONFIG_FILE = CONFIG_DIR + "/theme_config.json";
    
    /**
     * UI density options for professional customization with Dear ImGui scaling
     */
    public enum UIDensity {
        COMPACT("Compact", "Smaller controls and reduced spacing", 0.85f),
        STANDARD("Standard", "Normal control sizes and spacing", 1.0f),
        SPACIOUS("Spacious", "Larger controls and increased spacing", 1.15f);
        
        private final String displayName;
        private final String description;
        private final float scaleFactor;
        
        UIDensity(String displayName, String description, float scaleFactor) {
            this.displayName = displayName;
            this.description = description;
            this.scaleFactor = scaleFactor;
        }
        
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public float getScaleFactor() { return scaleFactor; }
    }
    
    /**
     * Theme change listener interface
     */
    public interface ThemeChangeListener {
        void onThemeChanged(ImGuiTheme oldTheme, ImGuiTheme newTheme);
        void onDensityChanged(UIDensity oldDensity, UIDensity newDensity);
        void onPreviewModeChanged(boolean previewMode);
    }
    
    /**
     * Dear ImGui Theme definition class with JSON serialization support
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImGuiTheme {
        @JsonProperty("id")
        private String id;
        
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("description")
        private String description;
        
        @JsonProperty("type")
        private ThemeType type;
        
        @JsonProperty("colors")
        private final Map<Integer, ImVec4> colors = new HashMap<>();
        
        @JsonProperty("style_vars")
        private final Map<Integer, Float> styleVars = new HashMap<>();
        
        @JsonProperty("read_only")
        private boolean readOnly;
        
        // Default constructor for Jackson
        public ImGuiTheme() {}
        
        public ImGuiTheme(String id, String name, String description, ThemeType type) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.type = type;
            this.readOnly = false;
        }
        
        // Getters
        public String getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public ThemeType getType() { return type; }
        public Map<Integer, ImVec4> getColors() { return colors; }
        public Map<Integer, Float> getStyleVars() { return styleVars; }
        public boolean isReadOnly() { return readOnly; }
        
        // Setters
        public void setId(String id) { this.id = id; }
        public void setName(String name) { this.name = name; }
        public void setDescription(String description) { this.description = description; }
        public void setType(ThemeType type) { this.type = type; }
        public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
        
        // Color management
        public void setColor(int colorId, ImVec4 color) {
            if (!readOnly) {
                colors.put(colorId, new ImVec4(color.x, color.y, color.z, color.w));
            }
        }
        
        public void setColor(int colorId, float r, float g, float b, float a) {
            if (!readOnly) {
                colors.put(colorId, new ImVec4(r, g, b, a));
            }
        }
        
        public ImVec4 getColor(int colorId) {
            return colors.get(colorId);
        }
        
        // Style variable management
        public void setStyleVar(int styleVar, float value) {
            if (!readOnly) {
                styleVars.put(styleVar, value);
            }
        }
        
        public Float getStyleVar(int styleVar) {
            return styleVars.get(styleVar);
        }
        
        // Create a deep copy of this theme
        public ImGuiTheme copy() {
            ImGuiTheme copy = new ImGuiTheme(this.id + "_copy", this.name + " (Copy)", this.description, ThemeType.USER_CUSTOM);
            for (Map.Entry<Integer, ImVec4> entry : this.colors.entrySet()) {
                ImVec4 color = entry.getValue();
                copy.setColor(entry.getKey(), color.x, color.y, color.z, color.w);
            }
            for (Map.Entry<Integer, Float> entry : this.styleVars.entrySet()) {
                copy.setStyleVar(entry.getKey(), entry.getValue());
            }
            return copy;
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
        ensureConfigDirectory();
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
     * Ensure configuration directory exists
     */
    private void ensureConfigDirectory() {
        try {
            Path configPath = Paths.get(CONFIG_DIR);
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath);
                logger.info("Created configuration directory: {}", CONFIG_DIR);
            }
        } catch (Exception e) {
            logger.error("Failed to create configuration directory", e);
        }
    }
    
    /**
     * Initialize built-in Dear ImGui themes
     */
    private void initializeBuiltInThemes() {
        // Dark Theme (default)
        ImGuiTheme darkTheme = createDarkTheme();
        registerTheme(darkTheme);
        
        // Light Theme
        ImGuiTheme lightTheme = createLightTheme();
        registerTheme(lightTheme);
        
        // High Contrast Theme
        ImGuiTheme highContrastTheme = createHighContrastTheme();
        registerTheme(highContrastTheme);
        
        // Blue Theme
        ImGuiTheme blueTheme = createBlueTheme();
        registerTheme(blueTheme);
        
        // Set default theme
        if (currentTheme == null) {
            currentTheme = darkTheme;
        }
        
        logger.info("Initialized {} built-in Dear ImGui themes", themeRegistry.size());
    }
    
    /**
     * Create professional dark theme for Dear ImGui (OpenMason default)
     */
    private ImGuiTheme createDarkTheme() {
        ImGuiTheme theme = new ImGuiTheme("dark", "Dark", "Professional dark theme for extended usage", ThemeType.BUILT_IN);
        theme.setReadOnly(true);
        
        // Window colors
        theme.setColor(ImGuiCol.WindowBg, 0.11f, 0.11f, 0.11f, 1.00f);           // #1c1c1c
        theme.setColor(ImGuiCol.ChildBg, 0.13f, 0.13f, 0.13f, 1.00f);            // #212121
        theme.setColor(ImGuiCol.PopupBg, 0.08f, 0.08f, 0.08f, 0.94f);            // #141414
        
        // Frame colors (inputs, buttons)
        theme.setColor(ImGuiCol.FrameBg, 0.23f, 0.23f, 0.23f, 1.00f);            // #3a3a3a
        theme.setColor(ImGuiCol.FrameBgHovered, 0.28f, 0.28f, 0.28f, 1.00f);     // #474747
        theme.setColor(ImGuiCol.FrameBgActive, 0.33f, 0.33f, 0.33f, 1.00f);      // #545454
        
        // Title bar
        theme.setColor(ImGuiCol.TitleBg, 0.12f, 0.12f, 0.12f, 1.00f);            // #1e1e1e
        theme.setColor(ImGuiCol.TitleBgActive, 0.16f, 0.16f, 0.16f, 1.00f);      // #292929
        theme.setColor(ImGuiCol.TitleBgCollapsed, 0.08f, 0.08f, 0.08f, 0.51f);   // #141414
        
        // Menu bar
        theme.setColor(ImGuiCol.MenuBarBg, 0.14f, 0.14f, 0.14f, 1.00f);          // #242424
        
        // Scrollbar
        theme.setColor(ImGuiCol.ScrollbarBg, 0.02f, 0.02f, 0.02f, 0.53f);        // #050505
        theme.setColor(ImGuiCol.ScrollbarGrab, 0.31f, 0.31f, 0.31f, 1.00f);      // #4f4f4f
        theme.setColor(ImGuiCol.ScrollbarGrabHovered, 0.41f, 0.41f, 0.41f, 1.00f); // #696969
        theme.setColor(ImGuiCol.ScrollbarGrabActive, 0.51f, 0.51f, 0.51f, 1.00f);  // #828282
        
        // Check mark
        theme.setColor(ImGuiCol.CheckMark, 0.00f, 0.60f, 1.00f, 1.00f);          // #0099ff (accent blue)
        
        // Slider
        theme.setColor(ImGuiCol.SliderGrab, 0.00f, 0.60f, 1.00f, 1.00f);         // #0099ff
        theme.setColor(ImGuiCol.SliderGrabActive, 0.00f, 0.47f, 0.80f, 1.00f);   // #0077cc
        
        // Button
        theme.setColor(ImGuiCol.Button, 0.00f, 0.60f, 1.00f, 0.40f);             // #0099ff with alpha
        theme.setColor(ImGuiCol.ButtonHovered, 0.00f, 0.60f, 1.00f, 1.00f);      // #0099ff
        theme.setColor(ImGuiCol.ButtonActive, 0.00f, 0.47f, 0.80f, 1.00f);       // #0077cc
        
        // Header (collapsing headers, selectables)
        theme.setColor(ImGuiCol.Header, 0.00f, 0.60f, 1.00f, 0.31f);             // #0099ff with alpha
        theme.setColor(ImGuiCol.HeaderHovered, 0.00f, 0.60f, 1.00f, 0.80f);      // #0099ff with alpha
        theme.setColor(ImGuiCol.HeaderActive, 0.00f, 0.60f, 1.00f, 1.00f);       // #0099ff
        
        // Separator
        theme.setColor(ImGuiCol.Separator, 0.33f, 0.33f, 0.33f, 0.50f);          // #545454
        theme.setColor(ImGuiCol.SeparatorHovered, 0.00f, 0.60f, 1.00f, 0.78f);   // #0099ff
        theme.setColor(ImGuiCol.SeparatorActive, 0.00f, 0.60f, 1.00f, 1.00f);    // #0099ff
        
        // Resize grip
        theme.setColor(ImGuiCol.ResizeGrip, 0.00f, 0.60f, 1.00f, 0.20f);         // #0099ff
        theme.setColor(ImGuiCol.ResizeGripHovered, 0.00f, 0.60f, 1.00f, 0.67f);  // #0099ff
        theme.setColor(ImGuiCol.ResizeGripActive, 0.00f, 0.60f, 1.00f, 0.95f);   // #0099ff
        
        // Tab
        theme.setColor(ImGuiCol.Tab, 0.18f, 0.35f, 0.58f, 0.86f);                // Dark blue
        theme.setColor(ImGuiCol.TabHovered, 0.00f, 0.60f, 1.00f, 0.80f);         // #0099ff
        theme.setColor(ImGuiCol.TabActive, 0.20f, 0.41f, 0.68f, 1.00f);          // Active blue
        theme.setColor(ImGuiCol.TabUnfocused, 0.07f, 0.10f, 0.15f, 0.97f);       // Unfocused
        theme.setColor(ImGuiCol.TabUnfocusedActive, 0.14f, 0.26f, 0.42f, 1.00f); // Unfocused active
        
        // Plot colors
        theme.setColor(ImGuiCol.PlotLines, 0.61f, 0.61f, 0.61f, 1.00f);          // Light gray
        theme.setColor(ImGuiCol.PlotLinesHovered, 1.00f, 0.43f, 0.35f, 1.00f);   // Orange
        theme.setColor(ImGuiCol.PlotHistogram, 0.90f, 0.70f, 0.00f, 1.00f);      // Yellow
        theme.setColor(ImGuiCol.PlotHistogramHovered, 1.00f, 0.60f, 0.00f, 1.00f); // Orange
        
        // Table
        theme.setColor(ImGuiCol.TableHeaderBg, 0.19f, 0.19f, 0.20f, 1.00f);      // #303134
        theme.setColor(ImGuiCol.TableBorderStrong, 0.31f, 0.31f, 0.35f, 1.00f);  // #4f4f59
        theme.setColor(ImGuiCol.TableBorderLight, 0.23f, 0.23f, 0.25f, 1.00f);   // #3a3a40
        theme.setColor(ImGuiCol.TableRowBg, 0.00f, 0.00f, 0.00f, 0.00f);         // Transparent
        theme.setColor(ImGuiCol.TableRowBgAlt, 1.00f, 1.00f, 1.00f, 0.06f);      // White with alpha
        
        // Text
        theme.setColor(ImGuiCol.Text, 1.00f, 1.00f, 1.00f, 1.00f);               // White
        theme.setColor(ImGuiCol.TextDisabled, 0.42f, 0.42f, 0.42f, 1.00f);       // Gray
        
        // Border
        theme.setColor(ImGuiCol.Border, 0.33f, 0.33f, 0.33f, 0.50f);             // #545454
        theme.setColor(ImGuiCol.BorderShadow, 0.00f, 0.00f, 0.00f, 0.00f);       // Transparent
        
        // Style variables for professional look
        theme.setStyleVar(ImGuiStyleVar.WindowRounding, 6.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.ScrollbarRounding, 9.0f);
        theme.setStyleVar(ImGuiStyleVar.GrabRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.TabRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.WindowBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameBorderSize, 0.0f);
        
        return theme;
    }
    
    /**
     * Create clean light theme for Dear ImGui
     */
    private ImGuiTheme createLightTheme() {
        ImGuiTheme theme = new ImGuiTheme("light", "Light", "Clean light theme for bright environments", ThemeType.BUILT_IN);
        theme.setReadOnly(true);
        
        // Window colors
        theme.setColor(ImGuiCol.WindowBg, 0.96f, 0.96f, 0.96f, 1.00f);           // #f5f5f5
        theme.setColor(ImGuiCol.ChildBg, 0.98f, 0.98f, 0.98f, 1.00f);            // #fafafa
        theme.setColor(ImGuiCol.PopupBg, 1.00f, 1.00f, 1.00f, 0.98f);            // White
        
        // Frame colors
        theme.setColor(ImGuiCol.FrameBg, 0.91f, 0.91f, 0.91f, 1.00f);            // #e8e8e8
        theme.setColor(ImGuiCol.FrameBgHovered, 0.86f, 0.86f, 0.86f, 1.00f);     // #dbdbdb
        theme.setColor(ImGuiCol.FrameBgActive, 0.81f, 0.81f, 0.81f, 1.00f);      // #cfcfcf
        
        // Title bar
        theme.setColor(ImGuiCol.TitleBg, 0.88f, 0.88f, 0.88f, 1.00f);            // #e0e0e0
        theme.setColor(ImGuiCol.TitleBgActive, 0.83f, 0.83f, 0.83f, 1.00f);      // #d4d4d4
        theme.setColor(ImGuiCol.TitleBgCollapsed, 0.94f, 0.94f, 0.94f, 0.51f);   // #f0f0f0
        
        // Menu bar
        theme.setColor(ImGuiCol.MenuBarBg, 0.93f, 0.93f, 0.93f, 1.00f);          // #ededed
        
        // Scrollbar
        theme.setColor(ImGuiCol.ScrollbarBg, 0.89f, 0.89f, 0.89f, 0.53f);        // #e3e3e3
        theme.setColor(ImGuiCol.ScrollbarGrab, 0.69f, 0.69f, 0.69f, 1.00f);      // #b0b0b0
        theme.setColor(ImGuiCol.ScrollbarGrabHovered, 0.59f, 0.59f, 0.59f, 1.00f); // #969696
        theme.setColor(ImGuiCol.ScrollbarGrabActive, 0.49f, 0.49f, 0.49f, 1.00f);  // #7d7d7d
        
        // Check mark
        theme.setColor(ImGuiCol.CheckMark, 0.00f, 0.40f, 0.80f, 1.00f);          // #0066cc
        
        // Slider
        theme.setColor(ImGuiCol.SliderGrab, 0.00f, 0.40f, 0.80f, 1.00f);         // #0066cc
        theme.setColor(ImGuiCol.SliderGrabActive, 0.00f, 0.32f, 0.64f, 1.00f);   // #0052a3
        
        // Button
        theme.setColor(ImGuiCol.Button, 0.00f, 0.40f, 0.80f, 0.40f);             // #0066cc with alpha
        theme.setColor(ImGuiCol.ButtonHovered, 0.00f, 0.40f, 0.80f, 1.00f);      // #0066cc
        theme.setColor(ImGuiCol.ButtonActive, 0.00f, 0.32f, 0.64f, 1.00f);       // #0052a3
        
        // Header
        theme.setColor(ImGuiCol.Header, 0.00f, 0.40f, 0.80f, 0.31f);             // #0066cc with alpha
        theme.setColor(ImGuiCol.HeaderHovered, 0.00f, 0.40f, 0.80f, 0.80f);      // #0066cc with alpha
        theme.setColor(ImGuiCol.HeaderActive, 0.00f, 0.40f, 0.80f, 1.00f);       // #0066cc
        
        // Separator
        theme.setColor(ImGuiCol.Separator, 0.67f, 0.67f, 0.67f, 0.50f);          // #ababab
        theme.setColor(ImGuiCol.SeparatorHovered, 0.00f, 0.40f, 0.80f, 0.78f);   // #0066cc
        theme.setColor(ImGuiCol.SeparatorActive, 0.00f, 0.40f, 0.80f, 1.00f);    // #0066cc
        
        // Resize grip
        theme.setColor(ImGuiCol.ResizeGrip, 0.00f, 0.40f, 0.80f, 0.20f);         // #0066cc
        theme.setColor(ImGuiCol.ResizeGripHovered, 0.00f, 0.40f, 0.80f, 0.67f);  // #0066cc
        theme.setColor(ImGuiCol.ResizeGripActive, 0.00f, 0.40f, 0.80f, 0.95f);   // #0066cc
        
        // Tab
        theme.setColor(ImGuiCol.Tab, 0.76f, 0.80f, 0.84f, 0.86f);                // Light blue gray
        theme.setColor(ImGuiCol.TabHovered, 0.00f, 0.40f, 0.80f, 0.80f);         // #0066cc
        theme.setColor(ImGuiCol.TabActive, 0.68f, 0.78f, 0.88f, 1.00f);          // Active light blue
        theme.setColor(ImGuiCol.TabUnfocused, 0.92f, 0.93f, 0.94f, 0.97f);       // Very light gray
        theme.setColor(ImGuiCol.TabUnfocusedActive, 0.74f, 0.82f, 0.90f, 1.00f); // Light blue
        
        // Plot colors
        theme.setColor(ImGuiCol.PlotLines, 0.39f, 0.39f, 0.39f, 1.00f);          // Dark gray
        theme.setColor(ImGuiCol.PlotLinesHovered, 1.00f, 0.43f, 0.35f, 1.00f);   // Orange
        theme.setColor(ImGuiCol.PlotHistogram, 0.90f, 0.70f, 0.00f, 1.00f);      // Yellow
        theme.setColor(ImGuiCol.PlotHistogramHovered, 1.00f, 0.60f, 0.00f, 1.00f); // Orange
        
        // Table
        theme.setColor(ImGuiCol.TableHeaderBg, 0.78f, 0.87f, 0.98f, 1.00f);      // Light blue
        theme.setColor(ImGuiCol.TableBorderStrong, 0.57f, 0.57f, 0.64f, 1.00f);  // Dark gray
        theme.setColor(ImGuiCol.TableBorderLight, 0.68f, 0.68f, 0.74f, 1.00f);   // Light gray
        theme.setColor(ImGuiCol.TableRowBg, 0.00f, 0.00f, 0.00f, 0.00f);         // Transparent
        theme.setColor(ImGuiCol.TableRowBgAlt, 0.30f, 0.30f, 0.30f, 0.09f);      // Dark with alpha
        
        // Text
        theme.setColor(ImGuiCol.Text, 0.20f, 0.20f, 0.20f, 1.00f);               // #333333
        theme.setColor(ImGuiCol.TextDisabled, 0.60f, 0.60f, 0.60f, 1.00f);       // #999999
        
        // Border
        theme.setColor(ImGuiCol.Border, 0.73f, 0.73f, 0.73f, 0.50f);             // #bababa
        theme.setColor(ImGuiCol.BorderShadow, 0.00f, 0.00f, 0.00f, 0.00f);       // Transparent
        
        // Style variables
        theme.setStyleVar(ImGuiStyleVar.WindowRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildRounding, 2.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameRounding, 2.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupRounding, 2.0f);
        theme.setStyleVar(ImGuiStyleVar.ScrollbarRounding, 6.0f);
        theme.setStyleVar(ImGuiStyleVar.GrabRounding, 2.0f);
        theme.setStyleVar(ImGuiStyleVar.TabRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.WindowBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameBorderSize, 0.0f);
        
        return theme;
    }
    
    /**
     * Create high contrast theme for accessibility
     */
    private ImGuiTheme createHighContrastTheme() {
        ImGuiTheme theme = new ImGuiTheme("high-contrast", "High Contrast", 
                                         "High contrast theme for accessibility", ThemeType.BUILT_IN);
        theme.setReadOnly(true);
        
        // Window colors - maximum contrast
        theme.setColor(ImGuiCol.WindowBg, 0.00f, 0.00f, 0.00f, 1.00f);           // Black
        theme.setColor(ImGuiCol.ChildBg, 0.05f, 0.05f, 0.05f, 1.00f);            // Very dark gray
        theme.setColor(ImGuiCol.PopupBg, 0.00f, 0.00f, 0.00f, 1.00f);            // Black
        
        // Frame colors - high contrast
        theme.setColor(ImGuiCol.FrameBg, 0.15f, 0.15f, 0.15f, 1.00f);            // Dark gray
        theme.setColor(ImGuiCol.FrameBgHovered, 0.25f, 0.25f, 0.25f, 1.00f);     // Medium gray
        theme.setColor(ImGuiCol.FrameBgActive, 0.35f, 0.35f, 0.35f, 1.00f);      // Light gray
        
        // Title bar
        theme.setColor(ImGuiCol.TitleBg, 0.00f, 0.00f, 0.00f, 1.00f);            // Black
        theme.setColor(ImGuiCol.TitleBgActive, 0.10f, 0.10f, 0.10f, 1.00f);      // Very dark gray
        theme.setColor(ImGuiCol.TitleBgCollapsed, 0.00f, 0.00f, 0.00f, 0.51f);   // Black
        
        // Menu bar
        theme.setColor(ImGuiCol.MenuBarBg, 0.05f, 0.05f, 0.05f, 1.00f);          // Very dark gray
        
        // Scrollbar
        theme.setColor(ImGuiCol.ScrollbarBg, 0.02f, 0.02f, 0.02f, 0.53f);        // Almost black
        theme.setColor(ImGuiCol.ScrollbarGrab, 0.80f, 0.80f, 0.80f, 1.00f);      // Light gray
        theme.setColor(ImGuiCol.ScrollbarGrabHovered, 0.90f, 0.90f, 0.90f, 1.00f); // Very light gray
        theme.setColor(ImGuiCol.ScrollbarGrabActive, 1.00f, 1.00f, 1.00f, 1.00f);  // White
        
        // Check mark - high visibility green
        theme.setColor(ImGuiCol.CheckMark, 0.00f, 1.00f, 0.00f, 1.00f);          // Bright green
        
        // Slider
        theme.setColor(ImGuiCol.SliderGrab, 0.00f, 1.00f, 0.00f, 1.00f);         // Bright green
        theme.setColor(ImGuiCol.SliderGrabActive, 0.00f, 0.80f, 0.00f, 1.00f);   // Dark green
        
        // Button
        theme.setColor(ImGuiCol.Button, 0.00f, 1.00f, 0.00f, 0.40f);             // Green with alpha
        theme.setColor(ImGuiCol.ButtonHovered, 0.00f, 1.00f, 0.00f, 1.00f);      // Bright green
        theme.setColor(ImGuiCol.ButtonActive, 0.00f, 0.80f, 0.00f, 1.00f);       // Dark green
        
        // Header
        theme.setColor(ImGuiCol.Header, 0.00f, 1.00f, 0.00f, 0.31f);             // Green with alpha
        theme.setColor(ImGuiCol.HeaderHovered, 0.00f, 1.00f, 0.00f, 0.80f);      // Green with alpha
        theme.setColor(ImGuiCol.HeaderActive, 0.00f, 1.00f, 0.00f, 1.00f);       // Bright green
        
        // Separator
        theme.setColor(ImGuiCol.Separator, 1.00f, 1.00f, 1.00f, 0.50f);          // White
        theme.setColor(ImGuiCol.SeparatorHovered, 0.00f, 1.00f, 0.00f, 0.78f);   // Green
        theme.setColor(ImGuiCol.SeparatorActive, 0.00f, 1.00f, 0.00f, 1.00f);    // Bright green
        
        // Resize grip
        theme.setColor(ImGuiCol.ResizeGrip, 0.00f, 1.00f, 0.00f, 0.20f);         // Green
        theme.setColor(ImGuiCol.ResizeGripHovered, 0.00f, 1.00f, 0.00f, 0.67f);  // Green
        theme.setColor(ImGuiCol.ResizeGripActive, 0.00f, 1.00f, 0.00f, 0.95f);   // Bright green
        
        // Tab
        theme.setColor(ImGuiCol.Tab, 0.58f, 0.58f, 0.58f, 0.86f);                // Gray
        theme.setColor(ImGuiCol.TabHovered, 0.00f, 1.00f, 0.00f, 0.80f);         // Green
        theme.setColor(ImGuiCol.TabActive, 0.68f, 0.68f, 0.68f, 1.00f);          // Light gray
        theme.setColor(ImGuiCol.TabUnfocused, 0.15f, 0.15f, 0.15f, 0.97f);       // Dark gray
        theme.setColor(ImGuiCol.TabUnfocusedActive, 0.42f, 0.42f, 0.42f, 1.00f); // Medium gray
        
        // Plot colors
        theme.setColor(ImGuiCol.PlotLines, 1.00f, 1.00f, 1.00f, 1.00f);          // White
        theme.setColor(ImGuiCol.PlotLinesHovered, 1.00f, 1.00f, 0.00f, 1.00f);   // Yellow
        theme.setColor(ImGuiCol.PlotHistogram, 1.00f, 1.00f, 0.00f, 1.00f);      // Yellow
        theme.setColor(ImGuiCol.PlotHistogramHovered, 1.00f, 0.00f, 0.00f, 1.00f); // Red
        
        // Table
        theme.setColor(ImGuiCol.TableHeaderBg, 0.19f, 0.19f, 0.20f, 1.00f);      // Dark gray
        theme.setColor(ImGuiCol.TableBorderStrong, 1.00f, 1.00f, 1.00f, 1.00f);  // White
        theme.setColor(ImGuiCol.TableBorderLight, 0.80f, 0.80f, 0.80f, 1.00f);   // Light gray
        theme.setColor(ImGuiCol.TableRowBg, 0.00f, 0.00f, 0.00f, 0.00f);         // Transparent
        theme.setColor(ImGuiCol.TableRowBgAlt, 1.00f, 1.00f, 1.00f, 0.06f);      // White with alpha
        
        // Text - maximum contrast
        theme.setColor(ImGuiCol.Text, 1.00f, 1.00f, 1.00f, 1.00f);               // White
        theme.setColor(ImGuiCol.TextDisabled, 0.50f, 0.50f, 0.50f, 1.00f);       // Medium gray
        
        // Border
        theme.setColor(ImGuiCol.Border, 1.00f, 1.00f, 1.00f, 0.50f);             // White
        theme.setColor(ImGuiCol.BorderShadow, 0.00f, 0.00f, 0.00f, 0.00f);       // Transparent
        
        // Style variables - sharp edges for accessibility
        theme.setStyleVar(ImGuiStyleVar.WindowRounding, 0.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildRounding, 0.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameRounding, 0.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupRounding, 0.0f);
        theme.setStyleVar(ImGuiStyleVar.ScrollbarRounding, 0.0f);
        theme.setStyleVar(ImGuiStyleVar.GrabRounding, 0.0f);
        theme.setStyleVar(ImGuiStyleVar.TabRounding, 0.0f);
        theme.setStyleVar(ImGuiStyleVar.WindowBorderSize, 2.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildBorderSize, 2.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupBorderSize, 2.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameBorderSize, 1.0f);
        
        return theme;
    }
    
    /**
     * Create professional blue theme for Dear ImGui
     */
    private ImGuiTheme createBlueTheme() {
        ImGuiTheme theme = new ImGuiTheme("blue", "Blue", "Professional blue theme", ThemeType.BUILT_IN);
        theme.setReadOnly(true);
        
        // Window colors - blue tinted
        theme.setColor(ImGuiCol.WindowBg, 0.12f, 0.16f, 0.23f, 1.00f);           // #1e2a3a
        theme.setColor(ImGuiCol.ChildBg, 0.16f, 0.23f, 0.30f, 1.00f);            // #2a3b4d
        theme.setColor(ImGuiCol.PopupBg, 0.08f, 0.12f, 0.16f, 0.94f);            // #151e2a
        
        // Frame colors
        theme.setColor(ImGuiCol.FrameBg, 0.21f, 0.29f, 0.37f, 1.00f);            // #354a5f
        theme.setColor(ImGuiCol.FrameBgHovered, 0.25f, 0.35f, 0.45f, 1.00f);     // Lighter blue
        theme.setColor(ImGuiCol.FrameBgActive, 0.29f, 0.41f, 0.53f, 1.00f);      // Even lighter blue
        
        // Title bar
        theme.setColor(ImGuiCol.TitleBg, 0.10f, 0.14f, 0.20f, 1.00f);            // Dark blue
        theme.setColor(ImGuiCol.TitleBgActive, 0.16f, 0.23f, 0.30f, 1.00f);      // Medium blue
        theme.setColor(ImGuiCol.TitleBgCollapsed, 0.08f, 0.12f, 0.16f, 0.51f);   // Very dark blue
        
        // Menu bar
        theme.setColor(ImGuiCol.MenuBarBg, 0.14f, 0.20f, 0.26f, 1.00f);          // Blue gray
        
        // Scrollbar
        theme.setColor(ImGuiCol.ScrollbarBg, 0.02f, 0.02f, 0.02f, 0.53f);        // Dark
        theme.setColor(ImGuiCol.ScrollbarGrab, 0.35f, 0.42f, 0.48f, 1.00f);      // Blue gray
        theme.setColor(ImGuiCol.ScrollbarGrabHovered, 0.45f, 0.52f, 0.58f, 1.00f); // Lighter blue gray
        theme.setColor(ImGuiCol.ScrollbarGrabActive, 0.55f, 0.62f, 0.68f, 1.00f);  // Even lighter
        
        // Check mark
        theme.setColor(ImGuiCol.CheckMark, 0.30f, 0.60f, 1.00f, 1.00f);          // #4d9fff
        
        // Slider
        theme.setColor(ImGuiCol.SliderGrab, 0.30f, 0.60f, 1.00f, 1.00f);         // #4d9fff
        theme.setColor(ImGuiCol.SliderGrabActive, 0.20f, 0.50f, 0.90f, 1.00f);   // #3380e6
        
        // Button
        theme.setColor(ImGuiCol.Button, 0.30f, 0.60f, 1.00f, 0.40f);             // #4d9fff with alpha
        theme.setColor(ImGuiCol.ButtonHovered, 0.30f, 0.60f, 1.00f, 1.00f);      // #4d9fff
        theme.setColor(ImGuiCol.ButtonActive, 0.20f, 0.50f, 0.90f, 1.00f);       // #3380e6
        
        // Header
        theme.setColor(ImGuiCol.Header, 0.30f, 0.60f, 1.00f, 0.31f);             // #4d9fff with alpha
        theme.setColor(ImGuiCol.HeaderHovered, 0.30f, 0.60f, 1.00f, 0.80f);      // #4d9fff with alpha
        theme.setColor(ImGuiCol.HeaderActive, 0.30f, 0.60f, 1.00f, 1.00f);       // #4d9fff
        
        // Separator
        theme.setColor(ImGuiCol.Separator, 0.35f, 0.42f, 0.48f, 0.50f);          // Blue gray
        theme.setColor(ImGuiCol.SeparatorHovered, 0.30f, 0.60f, 1.00f, 0.78f);   // #4d9fff
        theme.setColor(ImGuiCol.SeparatorActive, 0.30f, 0.60f, 1.00f, 1.00f);    // #4d9fff
        
        // Resize grip
        theme.setColor(ImGuiCol.ResizeGrip, 0.30f, 0.60f, 1.00f, 0.20f);         // #4d9fff
        theme.setColor(ImGuiCol.ResizeGripHovered, 0.30f, 0.60f, 1.00f, 0.67f);  // #4d9fff
        theme.setColor(ImGuiCol.ResizeGripActive, 0.30f, 0.60f, 1.00f, 0.95f);   // #4d9fff
        
        // Tab
        theme.setColor(ImGuiCol.Tab, 0.18f, 0.35f, 0.58f, 0.86f);                // Dark blue
        theme.setColor(ImGuiCol.TabHovered, 0.30f, 0.60f, 1.00f, 0.80f);         // #4d9fff
        theme.setColor(ImGuiCol.TabActive, 0.20f, 0.41f, 0.68f, 1.00f);          // Active blue
        theme.setColor(ImGuiCol.TabUnfocused, 0.07f, 0.10f, 0.15f, 0.97f);       // Very dark blue
        theme.setColor(ImGuiCol.TabUnfocusedActive, 0.14f, 0.26f, 0.42f, 1.00f); // Unfocused active
        
        // Plot colors
        theme.setColor(ImGuiCol.PlotLines, 0.78f, 0.83f, 0.88f, 1.00f);          // Light blue gray
        theme.setColor(ImGuiCol.PlotLinesHovered, 1.00f, 0.43f, 0.35f, 1.00f);   // Orange
        theme.setColor(ImGuiCol.PlotHistogram, 0.90f, 0.70f, 0.00f, 1.00f);      // Yellow
        theme.setColor(ImGuiCol.PlotHistogramHovered, 1.00f, 0.60f, 0.00f, 1.00f); // Orange
        
        // Table
        theme.setColor(ImGuiCol.TableHeaderBg, 0.19f, 0.19f, 0.20f, 1.00f);      // Dark
        theme.setColor(ImGuiCol.TableBorderStrong, 0.35f, 0.42f, 0.48f, 1.00f);  // Blue gray
        theme.setColor(ImGuiCol.TableBorderLight, 0.25f, 0.32f, 0.38f, 1.00f);   // Dark blue gray
        theme.setColor(ImGuiCol.TableRowBg, 0.00f, 0.00f, 0.00f, 0.00f);         // Transparent
        theme.setColor(ImGuiCol.TableRowBgAlt, 1.00f, 1.00f, 1.00f, 0.06f);      // White with alpha
        
        // Text
        theme.setColor(ImGuiCol.Text, 1.00f, 1.00f, 1.00f, 1.00f);               // White
        theme.setColor(ImGuiCol.TextDisabled, 0.42f, 0.48f, 0.54f, 1.00f);       // Blue gray
        
        // Border
        theme.setColor(ImGuiCol.Border, 0.35f, 0.42f, 0.48f, 0.50f);             // Blue gray
        theme.setColor(ImGuiCol.BorderShadow, 0.00f, 0.00f, 0.00f, 0.00f);       // Transparent
        
        // Style variables
        theme.setStyleVar(ImGuiStyleVar.WindowRounding, 5.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.ScrollbarRounding, 8.0f);
        theme.setStyleVar(ImGuiStyleVar.GrabRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.TabRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.WindowBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameBorderSize, 0.0f);
        
        return theme;
    }
    
    /**
     * Register a theme in the system
     */
    public void registerTheme(ImGuiTheme theme) {
        themeRegistry.put(theme.getId(), theme);
        if (!availableThemes.contains(theme)) {
            availableThemes.add(theme);
        }
        logger.debug("Registered Dear ImGui theme: {}", theme.getName());
    }
    
    /**
     * Apply a theme to Dear ImGui
     */
    public void applyTheme(String themeId) {
        ImGuiTheme theme = themeRegistry.get(themeId);
        if (theme == null) {
            logger.warn("Unknown theme: {}", themeId);
            return;
        }
        
        applyTheme(theme);
    }
    
    /**
     * Apply a theme to Dear ImGui
     */
    public void applyTheme(ImGuiTheme theme) {
        ImGuiTheme oldTheme = currentTheme;
        
        if (!previewMode) {
            currentTheme = theme;
        } else {
            previewTheme = theme;
        }
        
        // Apply theme colors to Dear ImGui
        applyThemeToImGui(theme);
        
        // Save configuration if not in preview mode
        if (!previewMode) {
            saveConfiguration();
        }
        
        // Notify listeners
        notifyThemeChanged(oldTheme, theme);
        
        logger.info("Applied Dear ImGui theme: {}", theme.getName());
    }
    
    /**
     * Apply theme colors and style variables to Dear ImGui
     */
    private void applyThemeToImGui(ImGuiTheme theme) {
        // Apply colors
        for (Map.Entry<Integer, ImVec4> entry : theme.getColors().entrySet()) {
            ImVec4 color = entry.getValue();
            ImGui.pushStyleColor(entry.getKey(), color.x, color.y, color.z, color.w);
        }
        
        // Apply style variables
        for (Map.Entry<Integer, Float> entry : theme.getStyleVars().entrySet()) {
            ImGui.pushStyleVar(entry.getKey(), entry.getValue());
        }
        
        // Apply UI density scaling
        float scale = currentDensity.getScaleFactor();
        if (scale != 1.0f) {
            ImGui.getStyle().scaleAllSizes(scale);
        }
        
        logger.debug("Applied {} colors and {} style variables to Dear ImGui", 
                    theme.getColors().size(), theme.getStyleVars().size());
    }
    
    /**
     * Reset Dear ImGui style to defaults (used before applying new theme)
     */
    public void resetImGuiStyle() {
        // Pop all style colors and variables that might have been pushed
        // Note: In a real implementation, you'd need to track how many items were pushed
        try {
            // Reset to default style
            ImGui.styleColorsDark(); // Use as base, then override with theme
        } catch (Exception e) {
            logger.warn("Failed to reset Dear ImGui style", e);
        }
    }
    
    /**
     * Set UI density (affects Dear ImGui scaling)
     */
    public void setUIDensity(UIDensity density) {
        UIDensity oldDensity = currentDensity;
        currentDensity = density;
        
        // Re-apply current theme with new density
        if (currentTheme != null) {
            applyThemeToImGui(currentTheme);
        }
        
        saveConfiguration();
        notifyDensityChanged(oldDensity, density);
        
        logger.info("Changed UI density to: {}", density.getDisplayName());
    }
    
    /**
     * Enter preview mode
     */
    public void enterPreviewMode() {
        if (!previewMode) {
            previewMode = true;
            originalTheme = currentTheme; // Store for rollback
            notifyPreviewModeChanged(true);
            logger.debug("Entered theme preview mode");
        }
    }
    
    /**
     * Exit preview mode and revert to original theme
     */
    public void exitPreviewMode() {
        if (previewMode) {
            previewMode = false;
            previewTheme = null;
            
            // Revert to original theme
            if (originalTheme != null) {
                applyThemeToImGui(originalTheme);
                currentTheme = originalTheme;
                originalTheme = null;
            }
            
            notifyPreviewModeChanged(false);
            logger.debug("Exited theme preview mode");
        }
    }
    
    /**
     * Apply preview theme
     */
    public void previewTheme(ImGuiTheme theme) {
        if (!previewMode) {
            enterPreviewMode();
        }
        
        previewTheme = theme;
        applyThemeToImGui(theme);
        
        logger.debug("Previewing Dear ImGui theme: {}", theme.getName());
    }
    
    /**
     * Commit preview theme as current
     */
    public void commitPreviewTheme() {
        if (previewMode && previewTheme != null) {
            ImGuiTheme themeToCommit = previewTheme;
            previewMode = false;
            originalTheme = null;
            currentTheme = themeToCommit;
            previewTheme = null;
            saveConfiguration();
            logger.info("Committed preview theme: {}", themeToCommit.getName());
        }
    }
    
    /**
     * Create a new custom theme based on an existing theme
     */
    public ImGuiTheme createCustomTheme(String name, String description, ImGuiTheme basedOn) {
        String id = "custom_" + System.currentTimeMillis();
        ImGuiTheme customTheme = new ImGuiTheme(id, name, description, ThemeType.USER_CUSTOM);
        
        // Copy colors and style vars from base theme
        if (basedOn != null) {
            for (Map.Entry<Integer, ImVec4> entry : basedOn.getColors().entrySet()) {
                ImVec4 color = entry.getValue();
                customTheme.setColor(entry.getKey(), color.x, color.y, color.z, color.w);
            }
            for (Map.Entry<Integer, Float> entry : basedOn.getStyleVars().entrySet()) {
                customTheme.setStyleVar(entry.getKey(), entry.getValue());
            }
        }
        
        registerTheme(customTheme);
        saveUserThemes();
        
        logger.info("Created custom Dear ImGui theme: {}", name);
        return customTheme;
    }
    
    /**
     * Delete a custom theme
     */
    public boolean deleteCustomTheme(String themeId) {
        ImGuiTheme theme = themeRegistry.get(themeId);
        if (theme != null && theme.getType() == ThemeType.USER_CUSTOM) {
            themeRegistry.remove(themeId);
            availableThemes.remove(theme);
            
            // Switch to default theme if deleting current theme
            if (currentTheme == theme) {
                applyTheme("dark");
            }
            
            saveUserThemes();
            logger.info("Deleted custom theme: {}", theme.getName());
            return true;
        }
        return false;
    }
    
    /**
     * Export theme to JSON file
     */
    public void exportTheme(ImGuiTheme theme, File file) throws IOException {
        try {
            objectMapper.writeValue(file, theme);
            logger.info("Exported Dear ImGui theme {} to: {}", theme.getName(), file.getAbsolutePath());
        } catch (Exception e) {
            logger.error("Failed to export theme", e);
            throw new IOException("Failed to export theme: " + e.getMessage(), e);
        }
    }
    
    /**
     * Import theme from JSON file
     */
    public ImGuiTheme importTheme(File file) throws IOException {
        try {
            ImGuiTheme theme = objectMapper.readValue(file, ImGuiTheme.class);
            
            // Ensure unique ID for imported themes
            theme.setId("imported_" + System.currentTimeMillis());
            theme.setType(ThemeType.IMPORTED);
            
            registerTheme(theme);
            saveUserThemes();
            
            logger.info("Imported Dear ImGui theme {} from: {}", theme.getName(), file.getAbsolutePath());
            return theme;
        } catch (Exception e) {
            logger.error("Failed to import theme", e);
            throw new IOException("Failed to import theme: " + e.getMessage(), e);
        }
    }
    
    /**
     * Save user themes to JSON file
     */
    private void saveUserThemes() {
        try {
            List<ImGuiTheme> userThemes = new ArrayList<>();
            for (ImGuiTheme theme : availableThemes) {
                if (theme.getType() == ThemeType.USER_CUSTOM || theme.getType() == ThemeType.IMPORTED) {
                    userThemes.add(theme);
                }
            }
            
            if (!userThemes.isEmpty()) {
                objectMapper.writeValue(new File(THEMES_FILE), userThemes);
                logger.debug("Saved {} user themes to {}", userThemes.size(), THEMES_FILE);
            }
        } catch (Exception e) {
            logger.error("Failed to save user themes", e);
        }
    }
    
    /**
     * Load user themes from JSON file
     */
    private void loadUserThemes() {
        try {
            File themesFile = new File(THEMES_FILE);
            if (themesFile.exists()) {
                ImGuiTheme[] themes = objectMapper.readValue(themesFile, ImGuiTheme[].class);
                for (ImGuiTheme theme : themes) {
                    registerTheme(theme);
                }
                logger.info("Loaded {} user themes from {}", themes.length, THEMES_FILE);
            }
        } catch (Exception e) {
            logger.error("Failed to load user themes", e);
        }
    }
    
    /**
     * Configuration data class for JSON serialization
     */
    private static class ThemeConfig {
        @JsonProperty("current_theme")
        public String currentTheme;
        
        @JsonProperty("current_density")
        public String currentDensity;
    }
    
    /**
     * Save current configuration to JSON file
     */
    private void saveConfiguration() {
        try {
            ThemeConfig config = new ThemeConfig();
            if (currentTheme != null) {
                config.currentTheme = currentTheme.getId();
            }
            config.currentDensity = currentDensity.name();
            
            objectMapper.writeValue(new File(CONFIG_FILE), config);
            logger.debug("Saved theme configuration");
        } catch (Exception e) {
            logger.error("Failed to save theme configuration", e);
        }
    }
    
    /**
     * Load saved configuration from JSON file
     */
    private void loadSavedConfiguration() {
        try {
            File configFile = new File(CONFIG_FILE);
            if (configFile.exists()) {
                ThemeConfig config = objectMapper.readValue(configFile, ThemeConfig.class);
                
                if (config.currentTheme != null) {
                    ImGuiTheme savedTheme = themeRegistry.get(config.currentTheme);
                    if (savedTheme != null) {
                        currentTheme = savedTheme;
                    }
                }
                
                if (config.currentDensity != null) {
                    try {
                        currentDensity = UIDensity.valueOf(config.currentDensity);
                    } catch (IllegalArgumentException e) {
                        currentDensity = UIDensity.STANDARD;
                    }
                }
                
                logger.info("Loaded saved theme configuration: {} / {}", 
                           currentTheme != null ? currentTheme.getName() : "none", 
                           currentDensity.getDisplayName());
            }
        } catch (Exception e) {
            logger.error("Failed to load saved configuration", e);
        }
    }
    
    // Property getters
    public ImGuiTheme getCurrentTheme() { return currentTheme; }
    public UIDensity getCurrentDensity() { return currentDensity; }
    public boolean isPreviewMode() { return previewMode; }
    public List<ImGuiTheme> getAvailableThemes() { return new ArrayList<>(availableThemes); }
    public ImGuiTheme getTheme(String themeId) { return themeRegistry.get(themeId); }
    
    // Convenience methods for theme access
    public ImGuiTheme getDarkTheme() { return themeRegistry.get("dark"); }
    public ImGuiTheme getLightTheme() { return themeRegistry.get("light"); }
    public ImGuiTheme getHighContrastTheme() { return themeRegistry.get("high-contrast"); }
    public ImGuiTheme getBlueTheme() { return themeRegistry.get("blue"); }
    
    // Listener management
    public void addThemeChangeListener(ThemeChangeListener listener) {
        listeners.add(listener);
    }
    
    public void removeThemeChangeListener(ThemeChangeListener listener) {
        listeners.remove(listener);
    }
    
    private void notifyThemeChanged(ImGuiTheme oldTheme, ImGuiTheme newTheme) {
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
    
    /**
     * Set theme change callback (for backward compatibility)
     */
    public void setOnThemeChanged(Consumer<ImGuiTheme> callback) {
        if (callback != null) {
            addThemeChangeListener(new ThemeChangeListener() {
                @Override
                public void onThemeChanged(ImGuiTheme oldTheme, ImGuiTheme newTheme) {
                    callback.accept(newTheme);
                }
                
                @Override
                public void onDensityChanged(UIDensity oldDensity, UIDensity newDensity) {
                    // No-op for this callback type
                }
                
                @Override
                public void onPreviewModeChanged(boolean previewMode) {
                    // No-op for this callback type
                }
            });
        }
    }
    
    /**
     * Apply theme to ImGui system (alias for applyTheme for consistency)
     */
    public void applyImGuiTheme(ImGuiTheme theme) {
        applyTheme(theme);
    }
    
    /**
     * Apply theme by ID to ImGui system (alias for applyTheme for consistency)
     */
    public void applyImGuiTheme(String themeId) {
        applyTheme(themeId);
    }
    
    /**
     * Get theme statistics and information
     */
    public String getThemeStatistics() {
        int builtInCount = (int) availableThemes.stream()
                .filter(theme -> theme.getType() == ThemeType.BUILT_IN)
                .count();
        int customCount = (int) availableThemes.stream()
                .filter(theme -> theme.getType() == ThemeType.USER_CUSTOM)
                .count();
        int importedCount = (int) availableThemes.stream()
                .filter(theme -> theme.getType() == ThemeType.IMPORTED)
                .count();
                
        return String.format("Themes: %d total (%d built-in, %d custom, %d imported). " +
                           "Current: %s (%s density). Preview mode: %s",
                           availableThemes.size(), builtInCount, customCount, importedCount,
                           currentTheme != null ? currentTheme.getName() : "none",
                           currentDensity.getDisplayName(),
                           isPreviewMode() ? "on" : "off");
    }
    
    /**
     * Validate theme integrity
     */
    public boolean validateTheme(ImGuiTheme theme) {
        if (theme == null) {
            logger.warn("Theme validation failed: theme is null");
            return false;
        }
        
        if (theme.getId() == null || theme.getId().trim().isEmpty()) {
            logger.warn("Theme validation failed: invalid ID");
            return false;
        }
        
        if (theme.getName() == null || theme.getName().trim().isEmpty()) {
            logger.warn("Theme validation failed: invalid name");
            return false;
        }
        
        if (theme.getColors().isEmpty() && theme.getStyleVars().isEmpty()) {
            logger.warn("Theme validation failed: no colors or style variables defined");
            return false;
        }
        
        logger.debug("Theme validation passed: {}", theme.getName());
        return true;
    }
    
    /**
     * Initialize theme system for ImGui application
     */
    public void initializeForImGui() {
        try {
            // Apply current theme to ImGui
            if (currentTheme != null) {
                applyThemeToImGui(currentTheme);
            }
            
            // Set up density scaling
            if (currentDensity != UIDensity.STANDARD) {
                setUIDensity(currentDensity);
            }
            
            logger.info("Theme system initialized for ImGui: theme={}, density={}",
                       currentTheme != null ? currentTheme.getName() : "default",
                       currentDensity.getDisplayName());
                       
        } catch (Exception e) {
            logger.error("Failed to initialize theme system for ImGui", e);
        }
    }
    
    /**
     * Cleanup and dispose theme system resources
     */
    public void dispose() {
        try {
            // Save current configuration before disposing
            saveConfiguration();
            
            // Clear listeners
            listeners.clear();
            
            // Reset state
            currentTheme = null;
            previewTheme = null;
            originalTheme = null;
            previewMode = false;
            
            // Clear theme registry
            themeRegistry.clear();
            availableThemes.clear();
            
            logger.info("Theme manager disposed and resources cleaned up");
            
        } catch (Exception e) {
            logger.error("Error during theme manager disposal", e);
        }
    }
}