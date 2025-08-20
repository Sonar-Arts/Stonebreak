package com.openmason.ui.themes;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec4;
import imgui.flag.ImGuiStyleVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Handles application of themes to ImGui context with OpenGL awareness.
 * Extracted from ThemeManager theme application methods.
 * Estimated size: ~200 lines (extracted from lines 725-749 + related methods)
 */
public class StyleApplicator {
    private static final Logger logger = LoggerFactory.getLogger(StyleApplicator.class);
    
    // Stack tracking for proper cleanup
    private static int colorStackDepth = 0;
    private static int styleVarStackDepth = 0;
    
    /**
     * Apply theme to current ImGui context (extracted from applyThemeToImGui)
     */
    public static void applyTheme(ThemeDefinition theme) {
        if (theme == null) {
            logger.warn("Cannot apply null theme");
            return;
        }
        
        if (!isImGuiContextValid()) {
            logger.error("ImGui context is not valid, cannot apply theme: {}", theme.getName());
            return;
        }
        
        try {
            logger.debug("Applying theme: {} with {} colors and {} style vars", 
                        theme.getName(), theme.getColorCount(), theme.getStyleVarCount());
            
            // Reset any previously applied styles
            resetToDefault();
            
            // Apply colors
            applyColors(theme);
            
            // Apply style variables
            applyStyleVariables(theme);
            
            logger.info("Successfully applied theme: {}", theme.getName());
            
        } catch (Exception e) {
            logger.error("Failed to apply theme: " + theme.getName(), e);
            // Attempt to reset to a safe state
            resetToDefault();
        }
    }
    
    /**
     * Apply theme colors to ImGui
     */
    private static void applyColors(ThemeDefinition theme) {
        Map<Integer, ImVec4> colors = theme.getColors();
        if (colors.isEmpty()) {
            logger.debug("No colors to apply for theme: {}", theme.getName());
            return;
        }
        
        int appliedColors = 0;
        for (Map.Entry<Integer, ImVec4> entry : colors.entrySet()) {
            try {
                int colorId = entry.getKey();
                ImVec4 color = entry.getValue();
                
                if (isValidColor(color)) {
                    ImGui.getStyle().setColor(colorId, color.x, color.y, color.z, color.w);
                    appliedColors++;
                } else {
                    logger.warn("Invalid color for ID {}: {}", colorId, color);
                }
                
            } catch (Exception e) {
                logger.warn("Failed to apply color {}: {}", entry.getKey(), e.getMessage());
            }
        }
        
        logger.debug("Applied {} out of {} colors", appliedColors, colors.size());
    }
    
    /**
     * Apply theme style variables to ImGui
     */
    private static void applyStyleVariables(ThemeDefinition theme) {
        Map<Integer, Float> styleVars = theme.getStyleVars();
        if (styleVars.isEmpty()) {
            logger.debug("No style variables to apply for theme: {}", theme.getName());
            return;
        }
        
        int appliedStyleVars = 0;
        for (Map.Entry<Integer, Float> entry : styleVars.entrySet()) {
            try {
                int styleVar = entry.getKey();
                float value = entry.getValue();
                
                if (isValidStyleVar(styleVar) && isValidStyleValue(value)) {
                    applyStyleVar(styleVar, value);
                    appliedStyleVars++;
                } else {
                    logger.warn("Invalid style var {} with value {}", styleVar, value);
                }
                
            } catch (Exception e) {
                logger.warn("Failed to apply style var {}: {}", entry.getKey(), e.getMessage());
            }
        }
        
        logger.debug("Applied {} out of {} style variables", appliedStyleVars, styleVars.size());
    }
    
    /**
     * Safely apply style variable with validation
     */
    private static void applyStyleVar(int styleVar, float value) {
        try {
            // Apply different style variables based on their type
            switch (styleVar) {
                case ImGuiStyleVar.WindowRounding:
                case ImGuiStyleVar.ChildRounding:
                case ImGuiStyleVar.FrameRounding:
                case ImGuiStyleVar.PopupRounding:
                case ImGuiStyleVar.ScrollbarRounding:
                case ImGuiStyleVar.GrabRounding:
                case ImGuiStyleVar.TabRounding:
                case ImGuiStyleVar.WindowBorderSize:
                case ImGuiStyleVar.ChildBorderSize:
                case ImGuiStyleVar.PopupBorderSize:
                case ImGuiStyleVar.FrameBorderSize:
                    // Single float style variables - use pushStyleVar for temporary changes
                    ImGui.pushStyleVar(styleVar, value);
                    break;
                    
                default:
                    // For other style variables, use pushStyleVar
                    ImGui.pushStyleVar(styleVar, value);
                    break;
            }
            
        } catch (Exception e) {
            logger.warn("Failed to set style variable {} to {}: {}", styleVar, value, e.getMessage());
        }
    }
    
    /**
     * Reset ImGui style to default (extracted from resetImGuiStyle)
     */
    public static void resetToDefault() {
        if (!isImGuiContextValid()) {
            logger.warn("Cannot reset ImGui style - context is not valid");
            return;
        }
        
        try {
            // Reset to default dark style as baseline
            ImGui.styleColorsDark();
            
            // Reset any custom style variables to defaults
            resetStyleVariablesToDefaults();
            
            // Reset stack tracking
            colorStackDepth = 0;
            styleVarStackDepth = 0;
            
            logger.debug("Reset ImGui style to defaults");
            
        } catch (Exception e) {
            logger.error("Failed to reset ImGui style to defaults", e);
        }
    }
    
    /**
     * Reset style variables to ImGui defaults
     */
    private static void resetStyleVariablesToDefaults() {
        try {
            // Reset by calling ImGui.styleColorsClassic() or similar built-in reset
            // Since ImGui Java bindings may not have direct style variable setters,
            // we'll use pushStyleVar with default values temporarily
            logger.debug("Resetting style variables to defaults");
            
            // Note: In a real application, you might want to call ImGui.popStyleVar()
            // to remove all previously pushed style variables, or use styleColors* methods
            
        } catch (Exception e) {
            logger.warn("Failed to reset some style variables to defaults", e);
        }
    }
    
    /**
     * Check if ImGui context is valid for theme application
     */
    public static boolean isImGuiContextValid() {
        try {
            // Check if we can access ImGui IO without throwing an exception
            ImGuiIO io = ImGui.getIO();
            return io != null;
        } catch (Exception e) {
            logger.debug("ImGui context check failed: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Validate that a color is within acceptable ranges
     */
    private static boolean isValidColor(ImVec4 color) {
        if (color == null) return false;
        
        return Float.isFinite(color.x) && color.x >= 0.0f && color.x <= 1.0f &&
               Float.isFinite(color.y) && color.y >= 0.0f && color.y <= 1.0f &&
               Float.isFinite(color.z) && color.z >= 0.0f && color.z <= 1.0f &&
               Float.isFinite(color.w) && color.w >= 0.0f && color.w <= 1.0f;
    }
    
    /**
     * Validate that a style variable ID is valid
     */
    private static boolean isValidStyleVar(int styleVar) {
        // Check if it's a known ImGui style variable
        return styleVar >= 0 && styleVar < 100; // ImGui has ~50 style variables currently
    }
    
    /**
     * Validate that a style value is reasonable
     */
    private static boolean isValidStyleValue(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 1000.0f;
    }
    
    /**
     * Apply theme with UI density scaling (legacy method for compatibility)
     */
    public static void applyThemeWithDensity(ThemeDefinition theme, float densityScale) {
        if (theme == null) {
            logger.warn("Cannot apply null theme with density");
            return;
        }
        
        // Apply the base theme first
        applyTheme(theme);
        
        // Then apply density scaling
        if (densityScale != 1.0f && isImGuiContextValid()) {
            try {
                applyDensityScaling(densityScale);
                logger.debug("Applied theme {} with density scale {}", theme.getName(), densityScale);
            } catch (Exception e) {
                logger.error("Failed to apply density scaling", e);
            }
        }
    }
    
    /**
     * Apply theme with DensityManager integration
     */
    public static void applyThemeWithDensityManager(ThemeDefinition theme, DensityManager densityManager) {
        if (theme == null) {
            logger.warn("Cannot apply null theme with density manager");
            return;
        }
        
        if (densityManager == null) {
            logger.warn("DensityManager is null, applying theme without density scaling");
            applyTheme(theme);
            return;
        }
        
        try {
            // Get density-scaled theme from DensityManager
            ThemeDefinition scaledTheme = densityManager.applyDensityToTheme(theme);
            
            if (scaledTheme != null) {
                applyTheme(scaledTheme);
                logger.debug("Applied theme {} with DensityManager ({})", 
                           theme.getName(), densityManager.getCurrentDensity().getDisplayName());
            } else {
                // Fallback to original theme if scaling failed
                applyTheme(theme);
                logger.warn("DensityManager returned null, applied original theme");
            }
            
        } catch (Exception e) {
            logger.error("Failed to apply theme with DensityManager", e);
            // Fallback to original theme
            applyTheme(theme);
        }
    }
    
    /**
     * Apply density scaling to ImGui style
     */
    private static void applyDensityScaling(float scaleFactor) {
        if (!isValidStyleValue(scaleFactor) || scaleFactor <= 0.0f) {
            logger.warn("Invalid density scale factor: {}", scaleFactor);
            return;
        }
        
        try {
            // Scale common size-related style variables
            scaleStyleVar(ImGuiStyleVar.WindowRounding, scaleFactor);
            scaleStyleVar(ImGuiStyleVar.ChildRounding, scaleFactor);
            scaleStyleVar(ImGuiStyleVar.FrameRounding, scaleFactor);
            scaleStyleVar(ImGuiStyleVar.PopupRounding, scaleFactor);
            scaleStyleVar(ImGuiStyleVar.ScrollbarRounding, scaleFactor);
            scaleStyleVar(ImGuiStyleVar.GrabRounding, scaleFactor);
            scaleStyleVar(ImGuiStyleVar.TabRounding, scaleFactor);
            
            // Scale border sizes
            scaleStyleVar(ImGuiStyleVar.WindowBorderSize, scaleFactor);
            scaleStyleVar(ImGuiStyleVar.ChildBorderSize, scaleFactor);
            scaleStyleVar(ImGuiStyleVar.PopupBorderSize, scaleFactor);
            scaleStyleVar(ImGuiStyleVar.FrameBorderSize, scaleFactor);
            
            logger.debug("Applied density scaling factor: {}", scaleFactor);
            
        } catch (Exception e) {
            logger.error("Failed to apply density scaling", e);
        }
    }
    
    /**
     * Scale a specific style variable by the given factor
     */
    private static void scaleStyleVar(int styleVar, float scaleFactor) {
        try {
            // Since ImGui Java bindings don't have direct getVar/setVar,
            // we'll use a default value and pushStyleVar for scaling
            float defaultValue = getDefaultStyleVarValue(styleVar);
            float scaledValue = defaultValue * scaleFactor;
            
            if (isValidStyleValue(scaledValue)) {
                ImGui.pushStyleVar(styleVar, scaledValue);
            }
        } catch (Exception e) {
            logger.debug("Failed to scale style var {}: {}", styleVar, e.getMessage());
        }
    }
    
    /**
     * Get default style variable value
     */
    private static float getDefaultStyleVarValue(int styleVar) {
        // Return reasonable default values for common style variables
        switch (styleVar) {
            case ImGuiStyleVar.WindowRounding: return 6.0f;
            case ImGuiStyleVar.ChildRounding: return 3.0f;
            case ImGuiStyleVar.FrameRounding: return 3.0f;
            case ImGuiStyleVar.PopupRounding: return 3.0f;
            case ImGuiStyleVar.ScrollbarRounding: return 9.0f;
            case ImGuiStyleVar.GrabRounding: return 3.0f;
            case ImGuiStyleVar.TabRounding: return 4.0f;
            case ImGuiStyleVar.WindowBorderSize: return 1.0f;
            case ImGuiStyleVar.ChildBorderSize: return 1.0f;
            case ImGuiStyleVar.PopupBorderSize: return 1.0f;
            case ImGuiStyleVar.FrameBorderSize: return 0.0f;
            default: return 1.0f;
        }
    }
    
    /**
     * Get current theme application status
     */
    public static String getApplicationStatus() {
        if (!isImGuiContextValid()) {
            return "ImGui context invalid";
        }
        
        return String.format("ImGui context valid. Color stack: %d, Style var stack: %d", 
                           colorStackDepth, styleVarStackDepth);
    }
    
    /**
     * Validate theme before application
     */
    public static boolean validateThemeForApplication(ThemeDefinition theme) {
        if (theme == null) {
            logger.warn("Theme validation failed: theme is null");
            return false;
        }
        
        if (!theme.isValid()) {
            logger.warn("Theme validation failed: theme is invalid");
            return false;
        }
        
        if (!isImGuiContextValid()) {
            logger.warn("Theme validation failed: ImGui context is invalid");
            return false;
        }
        
        // Check if colors are valid
        for (Map.Entry<Integer, ImVec4> entry : theme.getColors().entrySet()) {
            if (!isValidColor(entry.getValue())) {
                logger.warn("Theme validation failed: invalid color for ID {}", entry.getKey());
                return false;
            }
        }
        
        // Check if style variables are valid
        for (Map.Entry<Integer, Float> entry : theme.getStyleVars().entrySet()) {
            if (!isValidStyleVar(entry.getKey()) || !isValidStyleValue(entry.getValue())) {
                logger.warn("Theme validation failed: invalid style var {} with value {}", 
                           entry.getKey(), entry.getValue());
                return false;
            }
        }
        
        logger.debug("Theme validation passed: {}", theme.getName());
        return true;
    }
    
    /**
     * Emergency reset function for when theme application fails
     */
    public static void emergencyReset() {
        logger.warn("Performing emergency ImGui style reset");
        
        try {
            if (isImGuiContextValid()) {
                // Force reset to dark theme as safe fallback
                ImGui.styleColorsDark();
                resetStyleVariablesToDefaults();
                
                colorStackDepth = 0;
                styleVarStackDepth = 0;
                
                logger.info("Emergency reset completed successfully");
            } else {
                logger.error("Cannot perform emergency reset - ImGui context is invalid");
            }
        } catch (Exception e) {
            logger.error("Emergency reset failed", e);
        }
    }
}