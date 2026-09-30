package com.openmason.main.systems.themes.application;

import com.openmason.main.systems.themes.core.ThemeDefinition;
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
                logger.trace("Density already set to: {}", density.getDisplayName());
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
            logger.trace("No density scaling needed for theme: {}", theme.getName());
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

            // Scale additional float style vars
            scaleThemeStyleVar(scaledTheme, ImGuiStyleVar.ScrollbarSize, scale);
            scaleThemeStyleVar(scaledTheme, ImGuiStyleVar.GrabMinSize, scale);
            scaleThemeStyleVar(scaledTheme, ImGuiStyleVar.IndentSpacing, scale);

            // Scale Vec2 style vars (padding, spacing)
            scaleThemeStyleVarVec2(scaledTheme, ImGuiStyleVar.WindowPadding, scale);
            scaleThemeStyleVarVec2(scaledTheme, ImGuiStyleVar.FramePadding, scale);
            scaleThemeStyleVarVec2(scaledTheme, ImGuiStyleVar.ItemSpacing, scale);
            scaleThemeStyleVarVec2(scaledTheme, ImGuiStyleVar.ItemInnerSpacing, scale);
            scaleThemeStyleVarVec2(scaledTheme, ImGuiStyleVar.CellPadding, scale);

            logger.trace("Applied density scaling {}x to theme: {}", scale, theme.getName());
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
     * Apply the current density's text scale to the ImGui context.
     *
     * <p>Only the font scale is set here. Spacing, padding, rounding and border
     * sizes are scaled by {@link #applyDensityToTheme} and applied with the
     * theme; this used to also push ~14 style vars that were never popped, so
     * each density change grew ImGui's style-var stack. Mortar/Skija surfaces
     * read the same font scale ({@code MortarTheme.currentScale()}), so the
     * whole UI follows density together.</p>
     */
    private void applyDensityToImGui() {
        if (!StyleApplicator.isImGuiContextValid()) {
            logger.warn("Cannot apply density - ImGui context is invalid");
            return;
        }

        try {
            float scale = currentDensity.getScaleFactor();
            ImGui.getIO().setFontGlobalScale(scale);
            logger.debug("Applied density text scale {}x to ImGui context", scale);
        } catch (Exception e) {
            logger.error("Failed to apply density scaling to ImGui", e);
        }
    }

    /**
     * Re-apply the current density to the ImGui context even when it has not
     * changed — e.g. once the context exists at startup, since
     * {@link #setDensity} ignores a density equal to the current one.
     */
    public void reapply() {
        densityLock.lock();
        try {
            applyDensityToImGui();
        } finally {
            densityLock.unlock();
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
     * Scale a theme's Vec2 style variable
     */
    private void scaleThemeStyleVarVec2(ThemeDefinition theme, int styleVar, float scale) {
        float[] currentValue = theme.getStyleVarVec2(styleVar);
        if (currentValue != null && currentValue.length == 2) {
            theme.setStyleVarVec2(styleVar, currentValue[0] * scale, currentValue[1] * scale);
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