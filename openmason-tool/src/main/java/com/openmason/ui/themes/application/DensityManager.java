package com.openmason.ui.themes.application;

import com.openmason.ui.themes.core.ThemeDefinition;
import imgui.ImGui;
import imgui.flag.ImGuiStyleVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Manages UI density scaling for the Open Mason theming system.
 * Provides smooth density scaling for different screen sizes and user preferences
 * with seamless integration to ImGui styling and the existing theme system.
 */
public class DensityManager {
    private static final Logger logger = LoggerFactory.getLogger(DensityManager.class);
    
    // Thread safety for density changes
    private final ReentrantLock densityLock = new ReentrantLock();
    
    // Current density state
    private UIDensity currentDensity = UIDensity.NORMAL;
    private boolean densityTransitionInProgress = false;
    
    // Cached base values for scaling calculations
    private float baseFontSize = 13.0f;
    private float baseItemSpacing = 8.0f;
    private float baseWindowPadding = 8.0f;
    private float baseFramePadding = 4.0f;
    
    // Change listeners
    private Consumer<UIDensity> densityChangeCallback;
    
    /**
     * UI density levels with scale factors optimized for ImGui
     */
    public enum UIDensity {
        COMPACT("Compact", "Smaller controls for power users", 0.8f),
        NORMAL("Normal", "Standard interface density", 1.0f),
        COMFORTABLE("Comfortable", "Larger controls for better readability", 1.2f),
        SPACIOUS("Spacious", "Maximum spacing for large screens", 1.5f);
        
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
     * Set the current UI density with thread-safe application
     */
    public void setDensity(UIDensity density) {
        if (density == null) {
            logger.warn("Cannot set null density");
            return;
        }
        
        densityLock.lock();
        try {
            if (currentDensity == density) {
                logger.debug("Density already set to: {}", density.getDisplayName());
                return;
            }
            
            UIDensity previousDensity = currentDensity;
            densityTransitionInProgress = true;
            
            logger.info("Changing UI density from {} to {}", 
                       previousDensity.getDisplayName(), density.getDisplayName());
            
            currentDensity = density;
            
            // Apply density to current ImGui context if available
            if (StyleApplicator.isImGuiContextValid()) {
                applyDensityToImGui();
            }
            
            // Notify listeners
            if (densityChangeCallback != null) {
                try {
                    densityChangeCallback.accept(density);
                } catch (Exception e) {
                    logger.error("Error in density change callback", e);
                }
            }
            
            densityTransitionInProgress = false;
            
            logger.info("Successfully applied density: {} (scale: {}x)", 
                       density.getDisplayName(), density.getScaleFactor());
                       
        } catch (Exception e) {
            logger.error("Failed to set density to " + density.getDisplayName(), e);
            densityTransitionInProgress = false;
        } finally {
            densityLock.unlock();
        }
    }
    
    /**
     * Get the current UI density
     */
    public UIDensity getCurrentDensity() {
        return currentDensity;
    }
    
    /**
     * Apply density scaling to a theme definition (creates scaled copy)
     */
    public ThemeDefinition applyDensityToTheme(ThemeDefinition theme) {
        if (theme == null) {
            logger.warn("Cannot apply density to null theme");
            return null;
        }
        
        if (currentDensity == UIDensity.NORMAL) {
            logger.debug("No density scaling needed for theme: {}", theme.getName());
            return theme;
        }
        
        densityLock.lock();
        try {
            ThemeDefinition scaledTheme = theme.copy();
            scaledTheme.setId(theme.getId() + "_density_" + currentDensity.name().toLowerCase());
            scaledTheme.setName(theme.getName() + " (" + currentDensity.getDisplayName() + ")");
            
            float scale = currentDensity.getScaleFactor();
            
            // Scale size-related style variables
            scaleThemeStyleVar(scaledTheme, ImGuiStyleVar.WindowRounding, scale);
            scaleThemeStyleVar(scaledTheme, ImGuiStyleVar.ChildRounding, scale);
            scaleThemeStyleVar(scaledTheme, ImGuiStyleVar.FrameRounding, scale);
            scaleThemeStyleVar(scaledTheme, ImGuiStyleVar.PopupRounding, scale);
            scaleThemeStyleVar(scaledTheme, ImGuiStyleVar.ScrollbarRounding, scale);
            scaleThemeStyleVar(scaledTheme, ImGuiStyleVar.GrabRounding, scale);
            scaleThemeStyleVar(scaledTheme, ImGuiStyleVar.TabRounding, scale);
            
            // Scale border sizes with minimum threshold
            scaleThemeStyleVarWithMin(scaledTheme, ImGuiStyleVar.WindowBorderSize, scale, 0.5f);
            scaleThemeStyleVarWithMin(scaledTheme, ImGuiStyleVar.ChildBorderSize, scale, 0.5f);
            scaleThemeStyleVarWithMin(scaledTheme, ImGuiStyleVar.PopupBorderSize, scale, 0.5f);
            scaleThemeStyleVarWithMin(scaledTheme, ImGuiStyleVar.FrameBorderSize, scale, 0.0f);
            
            logger.debug("Applied density scaling {}x to theme: {}", scale, theme.getName());
            return scaledTheme;
            
        } catch (Exception e) {
            logger.error("Failed to apply density to theme: " + theme.getName(), e);
            return theme;
        } finally {
            densityLock.unlock();
        }
    }
    
    /**
     * Calculate scaled padding based on base padding and current density
     */
    public float calculatePadding(float basePadding) {
        if (basePadding < 0) {
            logger.warn("Invalid base padding: {}", basePadding);
            return 0.0f;
        }
        return basePadding * currentDensity.getScaleFactor();
    }
    
    /**
     * Apply current density scaling directly to ImGui context
     */
    private void applyDensityToImGui() {
        if (!StyleApplicator.isImGuiContextValid()) {
            logger.warn("Cannot apply density - ImGui context is invalid");
            return;
        }
        
        try {
            float scale = currentDensity.getScaleFactor();
            
            // Apply font scaling
            ImGui.getIO().setFontGlobalScale(scale);
            
            // Scale padding and spacing using ImGui style variables
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, calculatePadding(baseWindowPadding), calculatePadding(baseWindowPadding));
            ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, calculatePadding(baseFramePadding), calculatePadding(baseFramePadding));
            ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, calculatePadding(baseItemSpacing), calculatePadding(baseItemSpacing));
            
            // Scale window and frame elements
            scaleImGuiStyleVar(ImGuiStyleVar.WindowRounding, scale);
            scaleImGuiStyleVar(ImGuiStyleVar.ChildRounding, scale);
            scaleImGuiStyleVar(ImGuiStyleVar.FrameRounding, scale);
            scaleImGuiStyleVar(ImGuiStyleVar.PopupRounding, scale);
            scaleImGuiStyleVar(ImGuiStyleVar.ScrollbarRounding, scale);
            scaleImGuiStyleVar(ImGuiStyleVar.GrabRounding, scale);
            scaleImGuiStyleVar(ImGuiStyleVar.TabRounding, scale);
            
            // Scale border sizes with proper minimums
            scaleImGuiStyleVarWithMin(ImGuiStyleVar.WindowBorderSize, scale, 0.5f);
            scaleImGuiStyleVarWithMin(ImGuiStyleVar.ChildBorderSize, scale, 0.5f);
            scaleImGuiStyleVarWithMin(ImGuiStyleVar.PopupBorderSize, scale, 0.5f);
            scaleImGuiStyleVarWithMin(ImGuiStyleVar.FrameBorderSize, scale, 0.0f);
            
            logger.debug("Applied density scaling {}x to ImGui context", scale);
            
        } catch (Exception e) {
            logger.error("Failed to apply density scaling to ImGui", e);
        }
    }
    
    /**
     * Scale a theme's style variable
     */
    private void scaleThemeStyleVar(ThemeDefinition theme, int styleVar, float scale) {
        Float currentValue = theme.getStyleVar(styleVar);
        if (currentValue != null) {
            float scaledValue = currentValue * scale;
            theme.setStyleVar(styleVar, scaledValue);
        }
    }
    
    /**
     * Scale a theme's style variable with minimum value
     */
    private void scaleThemeStyleVarWithMin(ThemeDefinition theme, int styleVar, float scale, float minValue) {
        Float currentValue = theme.getStyleVar(styleVar);
        if (currentValue != null) {
            float scaledValue = Math.max(minValue, currentValue * scale);
            theme.setStyleVar(styleVar, scaledValue);
        }
    }
    
    /**
     * Scale an ImGui style variable
     */
    private void scaleImGuiStyleVar(int styleVar, float scale) {
        try {
            // Use ImGui.pushStyleVar to temporarily apply scaled values
            float baseValue = getDefaultStyleVarValue(styleVar);
            float scaledValue = baseValue * scale;
            ImGui.pushStyleVar(styleVar, scaledValue);
        } catch (Exception e) {
            logger.debug("Failed to scale ImGui style var {}: {}", styleVar, e.getMessage());
        }
    }
    
    /**
     * Scale an ImGui style variable with minimum value
     */
    private void scaleImGuiStyleVarWithMin(int styleVar, float scale, float minValue) {
        try {
            // Use ImGui.pushStyleVar to temporarily apply scaled values with minimum
            float baseValue = getDefaultStyleVarValue(styleVar);
            float scaledValue = Math.max(minValue, baseValue * scale);
            ImGui.pushStyleVar(styleVar, scaledValue);
        } catch (Exception e) {
            logger.debug("Failed to scale ImGui style var {} with min: {}", styleVar, e.getMessage());
        }
    }
    
    /**
     * Get default style variable value
     */
    private float getDefaultStyleVarValue(int styleVar) {
        // Return reasonable default values for common style variables
        switch (styleVar) {
            case ImGuiStyleVar.WindowRounding: return 6.0f;
            case ImGuiStyleVar.ChildRounding: return 3.0f;
            case ImGuiStyleVar.FrameRounding: return 3.0f;
            case ImGuiStyleVar.PopupRounding: return 3.0f;
            case ImGuiStyleVar.ScrollbarRounding: return 9.0f;
            case ImGuiStyleVar.GrabRounding: return 3.0f;
            case ImGuiStyleVar.TabRounding: return 4.0f;
            default: return 1.0f;
        }
    }
    
    /**
     * Set density change callback
     */
    public void setDensityChangeCallback(Consumer<UIDensity> callback) {
        this.densityChangeCallback = callback;
    }
    
    /**
     * Emergency reset for when density application fails
     */
    public void emergencyReset() {
        logger.warn("Performing emergency density reset");
        
        densityLock.lock();
        try {
            currentDensity = UIDensity.NORMAL;
            densityTransitionInProgress = false;
            
            // Reset base values to safe defaults
            baseFontSize = 13.0f;
            baseItemSpacing = 8.0f;
            baseWindowPadding = 8.0f;
            baseFramePadding = 4.0f;
            
            // Apply normal density to ImGui if available
            if (StyleApplicator.isImGuiContextValid()) {
                applyDensityToImGui();
            }
            
            logger.info("Emergency density reset completed");
            
        } catch (Exception e) {
            logger.error("Emergency density reset failed", e);
        } finally {
            densityLock.unlock();
        }
    }
}