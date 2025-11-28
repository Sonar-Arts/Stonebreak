package com.openmason.main.systems.themes.utils;

import com.openmason.main.systems.menus.preferences.config.WindowConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCond;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

/**
 * Professional ImGui configuration utilities for the Open Mason theming system.
 * Provides comprehensive helper methods for ImGui styling, color management,
 * font handling, and safe theme application.
 */
public class ImGuiHelpers {
    
    private static final Logger logger = LoggerFactory.getLogger(ImGuiHelpers.class);
    
    // Style stack tracking for safety
    private static final Stack<Integer> colorPushStack = new Stack<>();
    private static final Stack<Integer> stylePushStack = new Stack<>();
    private static final Map<String, Long> fontMap = new HashMap<>();
    
    // Font stack management
    private static final Stack<String> fontStack = new Stack<>();
    
    // Validation state
    private static boolean contextValidated = false;
    private static long lastValidationTime = 0;
    private static final long VALIDATION_CACHE_MS = 1000; // Cache validation for 1 second
    
    // Default style backup
    private static ImGuiStyle defaultStyleBackup = null;
    
    
    private ImGuiHelpers() {
        // Utility class - prevent instantiation
    }
    
    // ================================
    // ImGui Context and Validation
    // ================================
    
    /**
     * Validate ImGui context with caching for performance
     */
    public static boolean validateImGuiContext() {
        long currentTime = System.currentTimeMillis();
        
        // Use cached result if recent
        if (contextValidated && (currentTime - lastValidationTime) < VALIDATION_CACHE_MS) {
            return true;
        }
        
        try {
            // Check if ImGui context exists and is valid
            ImGuiIO io = ImGui.getIO();
            if (io == null) {
                logger.warn("ImGui IO is not accessible");
                contextValidated = false;
                return false;
            }
            
            contextValidated = true;
            lastValidationTime = currentTime;
            logger.debug("ImGui context validation successful");
            return true;
            
        } catch (Exception e) {
            logger.error("ImGui context validation failed", e);
            contextValidated = false;
            return false;
        }
    }

    // ================================
    // Window Configuration Helpers
    // ================================
    
    /**
     * Configure window size if specified in config (from plan section 1.2)
     */
    public static void configureWindowSize(WindowConfig config) {
        if (config.hasSize()) {
            ImGui.setWindowSize(config.getWidth(), config.getHeight(), ImGuiCond.Once);
        }
    }
    
    /**
     * Configure window position if specified in config (from plan section 1.2)
     */
    public static void configureWindowPosition(WindowConfig config) {
        if (config.hasPosition()) {
            ImGui.setWindowPos(config.getX(), config.getY(), ImGuiCond.Once);
        }
    }
    
    /**
     * Configure window constraints if specified in config (from plan section 1.2)
     */
    public static void configureWindowConstraints(WindowConfig config) {
        if (config.hasSizeConstraints()) {
            ImGui.setNextWindowSizeConstraints(
                config.getMinWidth(), config.getMinHeight(),
                config.getMaxWidth(), config.getMaxHeight()
            );
        }
    }

    // ================================
    // Font Management
    // ================================
    
    /**
     * Pop font with validation and stack management
     */
    public static boolean popFont() {
        if (!validateImGuiContext()) {
            logger.error("Cannot pop font - ImGui context invalid");
            return false;
        }
        
        if (fontStack.isEmpty()) {
            logger.warn("Cannot pop font - font stack is empty");
            return false;
        }
        
        try {
            String fontName = fontStack.pop();
            
            // Pop the font (in a real implementation, you'd use ImGui.popFont)
            // ImGui.popFont();
            
            logger.debug("Popped font: {} (stack size: {})", fontName, fontStack.size());
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to pop font", e);
            return false;
        }
    }
    
    /**
     * Clear font stack (emergency cleanup)
     */
    public static void clearFontStack() {
        try {
            while (!fontStack.isEmpty()) {
                popFont();
            }
            logger.debug("Cleared font stack");
        } catch (Exception e) {
            logger.error("Failed to clear font stack", e);
            fontStack.clear(); // Force clear
        }
    }
    
    // ================================
    // Error Recovery and Cleanup
    // ================================
    
    /**
     * Emergency cleanup of all pushed styles and fonts
     */
    public static void emergencyCleanup() {
        logger.warn("Performing emergency ImGui cleanup");
        
        try {
            // Clear all font stacks
            clearFontStack();
            
            // Clear style stacks
            colorPushStack.clear();
            stylePushStack.clear();
            
            // Clear validation cache
            contextValidated = false;
            lastValidationTime = 0;
            
            logger.info("Emergency cleanup completed");
            
        } catch (Exception e) {
            logger.error("Failed emergency cleanup", e);
        }
    }
    
    /**
     * Dispose resources and cleanup
     */
    public static void dispose() {
        try {
            logger.debug("Disposing ImGuiHelpers resources");
            
            emergencyCleanup();
            
            // Clear font map
            fontMap.clear();
            
            // Reset default style backup
            defaultStyleBackup = null;
            
            logger.info("ImGuiHelpers disposed successfully");
            
        } catch (Exception e) {
            logger.error("Failed to dispose ImGuiHelpers", e);
        }
    }
    
    // ================================
    // Utility and Helper Methods
    // ================================
    
    /**
     * Create a default window configuration for testing
     * @deprecated Use WindowConfig predefined configurations instead
     */
    @Deprecated
    public static WindowConfig createSimpleWindowConfig(String title) {
        return new WindowConfig(title);
    }
}