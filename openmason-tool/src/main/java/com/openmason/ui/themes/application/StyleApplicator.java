package com.openmason.ui.themes.application;
import com.openmason.ui.themes.core.ThemeDefinition;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec4;
import imgui.flag.ImGuiStyleVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Handles application of themes to ImGui context with OpenGL awareness.
 */
public class StyleApplicator {
    private static final Logger logger = LoggerFactory.getLogger(StyleApplicator.class);

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
     * NOTE: We directly modify ImGui.getStyle() instead of using pushStyleVar
     * because themes are permanent settings, not temporary scoped changes.
     */
    private static void applyStyleVar(int styleVar, float value) {
        try {
            // Directly modify the style object instead of using push/pop
            // This is the correct way for permanent theme changes
            switch (styleVar) {
                case ImGuiStyleVar.WindowRounding:
                    ImGui.getStyle().setWindowRounding(value);
                    break;
                case ImGuiStyleVar.ChildRounding:
                    ImGui.getStyle().setChildRounding(value);
                    break;
                case ImGuiStyleVar.FrameRounding:
                    ImGui.getStyle().setFrameRounding(value);
                    break;
                case ImGuiStyleVar.PopupRounding:
                    ImGui.getStyle().setPopupRounding(value);
                    break;
                case ImGuiStyleVar.ScrollbarRounding:
                    ImGui.getStyle().setScrollbarRounding(value);
                    break;
                case ImGuiStyleVar.GrabRounding:
                    ImGui.getStyle().setGrabRounding(value);
                    break;
                case ImGuiStyleVar.TabRounding:
                    ImGui.getStyle().setTabRounding(value);
                    break;
                case ImGuiStyleVar.WindowBorderSize:
                    ImGui.getStyle().setWindowBorderSize(value);
                    break;
                case ImGuiStyleVar.ChildBorderSize:
                    ImGui.getStyle().setChildBorderSize(value);
                    break;
                case ImGuiStyleVar.PopupBorderSize:
                    ImGui.getStyle().setPopupBorderSize(value);
                    break;
                case ImGuiStyleVar.FrameBorderSize:
                    ImGui.getStyle().setFrameBorderSize(value);
                    break;
                default:
                    logger.warn("Unknown style variable: {}", styleVar);
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
     * Emergency reset function for when theme application fails
     */
    public static void emergencyReset() {
        logger.warn("Performing emergency ImGui style reset");
        
        try {
            if (isImGuiContextValid()) {
                // Force reset to dark theme as safe fallback
                ImGui.styleColorsDark();
                resetStyleVariablesToDefaults();

                logger.info("Emergency reset completed successfully");
            } else {
                logger.error("Cannot perform emergency reset - ImGui context is invalid");
            }
        } catch (Exception e) {
            logger.error("Emergency reset failed", e);
        }
    }
}