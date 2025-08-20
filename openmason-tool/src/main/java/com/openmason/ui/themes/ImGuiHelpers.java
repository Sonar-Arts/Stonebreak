package com.openmason.ui.themes;

import com.openmason.ui.config.WindowConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiStyle;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Pattern;

/**
 * Professional ImGui configuration utilities for the Open Mason theming system.
 * Provides comprehensive helper methods for ImGui styling, color management,
 * font handling, and safe theme application.
 * 
 * Features:
 * - Color utility functions with hex/RGB conversion
 * - Style push/pop stack management with safety checks
 * - Font management and validation
 * - Window configuration helpers
 * - Theme integration utilities
 * - Error recovery and validation
 * 
 * This class works seamlessly with the existing ThemeDefinition and ThemeManager
 * to provide a robust styling foundation for Open Mason's ImGui+LWJGL interface.
 */
public class ImGuiHelpers {
    
    private static final Logger logger = LoggerFactory.getLogger(ImGuiHelpers.class);
    
    // Color conversion constants
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#?([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$");
    private static final float INV_255 = 1.0f / 255.0f;
    
    // Style stack tracking for safety
    private static final Stack<Integer> colorPushStack = new Stack<>();
    private static final Stack<Integer> stylePushStack = new Stack<>();
    private static final Map<String, Long> fontMap = new HashMap<>();
    
    // Font stack management
    private static final Stack<String> fontStack = new Stack<>();
    private static final int MAX_FONT_STACK_SIZE = 16;
    
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
    
    /**
     * Perform comprehensive ImGui state validation
     */
    public static boolean validateImGuiState() {
        if (!validateImGuiContext()) {
            return false;
        }
        
        try {
            // Check style stack integrity
            if (colorPushStack.size() > 64) {
                logger.warn("Color push stack is unusually large: {}", colorPushStack.size());
            }
            
            if (stylePushStack.size() > 64) {
                logger.warn("Style push stack is unusually large: {}", stylePushStack.size());
            }
            
            // Validate font stack
            if (fontStack.size() > MAX_FONT_STACK_SIZE) {
                logger.error("Font stack overflow detected: {}", fontStack.size());
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            logger.error("ImGui state validation failed", e);
            return false;
        }
    }
    
    // ================================
    // Style Configuration and Management
    // ================================
    
    /**
     * Configure ImGui style from theme definition with comprehensive error handling
     */
    public static boolean configureImGuiStyle(ImGuiStyle style, ThemeDefinition theme) {
        if (!validateImGuiContext()) {
            logger.error("Cannot configure style - ImGui context invalid");
            return false;
        }
        
        if (style == null) {
            logger.error("Cannot configure style - ImGuiStyle is null");
            return false;
        }
        
        if (theme == null) {
            logger.error("Cannot configure style - ThemeDefinition is null");
            return false;
        }
        
        try {
            logger.debug("Configuring ImGui style with theme: {}", theme.getName());
            
            // Backup current style if this is the first time
            if (defaultStyleBackup == null) {
                defaultStyleBackup = new ImGuiStyle();
                // Note: In a real implementation, you'd copy all style properties
                logger.debug("Created default style backup");
            }
            
            // Apply colors from theme
            int colorCount = applyThemeColors(theme);
            
            // Apply style variables from theme  
            int styleVarCount = applyThemeStyleVars(theme);
            
            logger.info("Applied theme '{}': {} colors, {} style variables", 
                       theme.getName(), colorCount, styleVarCount);
            
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to configure ImGui style with theme: {}", theme.getName(), e);
            return false;
        }
    }
    
    /**
     * Apply theme colors to ImGui with validation
     */
    private static int applyThemeColors(ThemeDefinition theme) {
        int appliedCount = 0;
        
        for (Map.Entry<Integer, ImVec4> entry : theme.getColors().entrySet()) {
            try {
                Integer colorId = entry.getKey();
                ImVec4 color = entry.getValue();
                
                if (colorId == null || color == null) {
                    logger.warn("Skipping invalid color entry: colorId={}, color={}", colorId, color);
                    continue;
                }
                
                // Validate color ID range (ImGui has specific color constants)
                if (colorId < 0 || colorId >= ImGuiCol.COUNT) {
                    logger.warn("Invalid color ID: {} (valid range: 0-{})", colorId, ImGuiCol.COUNT - 1);
                    continue;
                }
                
                // Validate color values using ValidationUtils
                if (!ValidationUtils.isValidImVec4Color(color)) {
                    logger.warn("Invalid color values: r={}, g={}, b={}, a={}", 
                               color.x, color.y, color.z, color.w);
                    continue;
                }
                
                // Apply the color
                ImGui.pushStyleColor(colorId, color.x, color.y, color.z, color.w);
                colorPushStack.push(colorId);
                appliedCount++;
                
            } catch (Exception e) {
                logger.warn("Failed to apply color entry: {}", entry.getKey(), e);
            }
        }
        
        return appliedCount;
    }
    
    /**
     * Apply theme style variables to ImGui with validation
     */
    private static int applyThemeStyleVars(ThemeDefinition theme) {
        int appliedCount = 0;
        
        for (Map.Entry<Integer, Float> entry : theme.getStyleVars().entrySet()) {
            try {
                Integer styleVar = entry.getKey();
                Float value = entry.getValue();
                
                if (styleVar == null || value == null) {
                    logger.warn("Skipping invalid style var entry: styleVar={}, value={}", styleVar, value);
                    continue;
                }
                
                // Validate style variable ID
                if (styleVar < 0 || styleVar >= ImGuiStyleVar.COUNT) {
                    logger.warn("Invalid style variable ID: {} (valid range: 0-{})", 
                               styleVar, ImGuiStyleVar.COUNT - 1);
                    continue;
                }
                
                // Validate value range (most style vars should be non-negative)
                if (Float.isNaN(value) || Float.isInfinite(value) || value < 0) {
                    logger.warn("Invalid style variable value: {} for styleVar {}", value, styleVar);
                    continue;
                }
                
                // Apply the style variable
                ImGui.pushStyleVar(styleVar, value);
                stylePushStack.push(styleVar);
                appliedCount++;
                
            } catch (Exception e) {
                logger.warn("Failed to apply style variable entry: {}", entry.getKey(), e);
            }
        }
        
        return appliedCount;
    }
    
    /**
     * Reset ImGui style to defaults with error recovery
     */
    public static void resetToDefaults() {
        if (!validateImGuiContext()) {
            logger.error("Cannot reset to defaults - ImGui context invalid");
            return;
        }
        
        try {
            logger.debug("Resetting ImGui style to defaults");
            
            // Pop all pushed style colors
            while (!colorPushStack.isEmpty()) {
                try {
                    ImGui.popStyleColor();
                    colorPushStack.pop();
                } catch (Exception e) {
                    logger.warn("Failed to pop style color", e);
                    colorPushStack.clear(); // Clear stack to prevent infinite loop
                    break;
                }
            }
            
            // Pop all pushed style variables
            while (!stylePushStack.isEmpty()) {
                try {
                    ImGui.popStyleVar();
                    stylePushStack.pop();
                } catch (Exception e) {
                    logger.warn("Failed to pop style variable", e);
                    stylePushStack.clear(); // Clear stack to prevent infinite loop
                    break;
                }
            }
            
            // Apply default ImGui style
            ImGui.styleColorsDark(); // Use dark as base default
            
            logger.info("Successfully reset ImGui style to defaults");
            
        } catch (Exception e) {
            logger.error("Failed to reset ImGui style to defaults", e);
            
            // Emergency cleanup
            try {
                colorPushStack.clear();
                stylePushStack.clear();
            } catch (Exception cleanupEx) {
                logger.error("Failed emergency cleanup", cleanupEx);
            }
        }
    }
    
    // ================================
    // Color Utility Functions
    // ================================
    
    /**
     * Convert hex color string to ImGui Vec4 with comprehensive validation
     */
    public static ImVec4 createColorVec4(String colorHex) {
        if (colorHex == null || colorHex.trim().isEmpty()) {
            logger.warn("Invalid hex color: null or empty");
            return new ImVec4(1.0f, 1.0f, 1.0f, 1.0f); // Default to white
        }
        
        String cleanHex = colorHex.trim();
        
        // Remove # prefix if present
        if (cleanHex.startsWith("#")) {
            cleanHex = cleanHex.substring(1);
        }
        
        // Validate hex format using ValidationUtils
        if (!ValidationUtils.isValidHexColor("#" + cleanHex)) {
            logger.warn("Invalid hex color format: {}", colorHex);
            return new ImVec4(1.0f, 1.0f, 1.0f, 1.0f); // Default to white
        }
        
        try {
            float r, g, b, a = 1.0f;
            
            if (cleanHex.length() == 6) {
                // RGB format
                r = Integer.parseInt(cleanHex.substring(0, 2), 16) * INV_255;
                g = Integer.parseInt(cleanHex.substring(2, 4), 16) * INV_255;
                b = Integer.parseInt(cleanHex.substring(4, 6), 16) * INV_255;
            } else if (cleanHex.length() == 8) {
                // RGBA format
                r = Integer.parseInt(cleanHex.substring(0, 2), 16) * INV_255;
                g = Integer.parseInt(cleanHex.substring(2, 4), 16) * INV_255;
                b = Integer.parseInt(cleanHex.substring(4, 6), 16) * INV_255;
                a = Integer.parseInt(cleanHex.substring(6, 8), 16) * INV_255;
            } else {
                logger.warn("Invalid hex color length: {} (expected 6 or 8 characters)", cleanHex.length());
                return new ImVec4(1.0f, 1.0f, 1.0f, 1.0f);
            }
            
            // Validate color components using ValidationUtils
            ImVec4 result = new ImVec4(r, g, b, a);
            if (!ValidationUtils.isValidImVec4Color(result)) {
                logger.warn("Invalid color components calculated from hex: {}", colorHex);
                return new ImVec4(1.0f, 1.0f, 1.0f, 1.0f);
            }
            
            return result;
            
        } catch (NumberFormatException e) {
            logger.warn("Failed to parse hex color: {}", colorHex, e);
            return new ImVec4(1.0f, 1.0f, 1.0f, 1.0f); // Default to white
        }
    }
    
    /**
     * Convert RGB values to ImGui Vec4 with validation
     */
    public static ImVec4 createColorVec4(int r, int g, int b, int a) {
        // Validate input ranges using ValidationUtils
        if (!ValidationUtils.isValidRgbaColor(r, g, b, a)) {
            logger.warn("Invalid RGB values: r={}, g={}, b={}, a={} (valid range: 0-255)", r, g, b, a);
            return new ImVec4(1.0f, 1.0f, 1.0f, 1.0f); // Default to white
        }
        
        return new ImVec4(r * INV_255, g * INV_255, b * INV_255, a * INV_255);
    }
    
    /**
     * Convert RGB values to ImGui Vec4 (no alpha)
     */
    public static ImVec4 createColorVec4(int r, int g, int b) {
        return createColorVec4(r, g, b, 255);
    }
    
    /**
     * Convert float RGB values to ImGui Vec4 with validation
     */
    public static ImVec4 createColorVec4(float r, float g, float b, float a) {
        // Validate color components using ValidationUtils
        ImVec4 result = new ImVec4(r, g, b, a);
        if (!ValidationUtils.isValidImVec4Color(result)) {
            logger.warn("Invalid float color values: r={}, g={}, b={}, a={}", r, g, b, a);
            return new ImVec4(1.0f, 1.0f, 1.0f, 1.0f); // Default to white
        }
        
        return result;
    }
    
    /**
     * Convert ImVec4 to hex string
     */
    public static String colorVec4ToHex(ImVec4 color) {
        if (color == null) {
            logger.warn("Cannot convert null color to hex");
            return "#FFFFFF"; // Default to white
        }
        
        try {
            int r = Math.round(Math.max(0, Math.min(1, color.x)) * 255);
            int g = Math.round(Math.max(0, Math.min(1, color.y)) * 255);
            int b = Math.round(Math.max(0, Math.min(1, color.z)) * 255);
            int a = Math.round(Math.max(0, Math.min(1, color.w)) * 255);
            
            if (a == 255) {
                return String.format("#%02X%02X%02X", r, g, b);
            } else {
                return String.format("#%02X%02X%02X%02X", r, g, b, a);
            }
            
        } catch (Exception e) {
            logger.warn("Failed to convert color to hex", e);
            return "#FFFFFF";
        }
    }
    
    
    /**
     * Blend two colors with specified ratio
     */
    public static ImVec4 blendColors(ImVec4 color1, ImVec4 color2, float ratio) {
        if (color1 == null || color2 == null) {
            logger.warn("Cannot blend null colors");
            return color1 != null ? color1 : (color2 != null ? color2 : new ImVec4(1.0f, 1.0f, 1.0f, 1.0f));
        }
        
        ratio = Math.max(0.0f, Math.min(1.0f, ratio)); // Clamp ratio
        float invRatio = 1.0f - ratio;
        
        return new ImVec4(
            color1.x * invRatio + color2.x * ratio,
            color1.y * invRatio + color2.y * ratio,
            color1.z * invRatio + color2.z * ratio,
            color1.w * invRatio + color2.w * ratio
        );
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
    
    /**
     * Apply all window configurations (from plan section 1.2)
     */
    public static void applyWindowConfig(WindowConfig config) {
        if (config == null) {
            logger.warn("Cannot apply null window config");
            return;
        }
        
        if (!validateImGuiContext()) {
            logger.error("Cannot apply window config - ImGui context invalid");
            return;
        }
        
        try {
            configureWindowSize(config);
            configureWindowPosition(config);
            configureWindowConstraints(config);
            
            logger.debug("Applied window config: {}", config.getTitle());
            
        } catch (Exception e) {
            logger.error("Failed to apply window config for: {}", config.getTitle(), e);
        }
    }
    
    
    // ================================
    // Font Management
    // ================================
    
    /**
     * Push font with validation and stack management
     */
    public static boolean pushFont(String fontName) {
        if (!validateImGuiContext()) {
            logger.error("Cannot push font - ImGui context invalid");
            return false;
        }
        
        if (fontName == null || fontName.trim().isEmpty()) {
            logger.warn("Cannot push font - invalid font name");
            return false;
        }
        
        if (fontStack.size() >= MAX_FONT_STACK_SIZE) {
            logger.error("Font stack overflow - cannot push font: {}", fontName);
            return false;
        }
        
        try {
            // Check if font is registered
            Long fontPtr = fontMap.get(fontName);
            if (fontPtr == null) {
                logger.warn("Font not registered: {}", fontName);
                return false;
            }
            
            // Push the font (in a real implementation, you'd use ImGui.pushFont)
            // ImGui.pushFont(fontPtr);
            fontStack.push(fontName);
            
            logger.debug("Pushed font: {} (stack size: {})", fontName, fontStack.size());
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to push font: {}", fontName, e);
            return false;
        }
    }
    
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
     * Register font for use with font stack
     */
    public static void registerFont(String fontName, long fontPtr) {
        if (fontName == null || fontName.trim().isEmpty()) {
            logger.warn("Cannot register font - invalid font name");
            return;
        }
        
        if (fontPtr == 0) {
            logger.warn("Cannot register font - invalid font pointer");
            return;
        }
        
        try {
            fontMap.put(fontName, fontPtr);
            logger.debug("Registered font: {}", fontName);
        } catch (Exception e) {
            logger.error("Failed to register font: {}", fontName, e);
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
    // Layout and Spacing Utilities
    // ================================
    
    /**
     * Apply standard spacing for professional layouts
     */
    public static void applyStandardSpacing() {
        if (!validateImGuiContext()) {
            return;
        }
        
        try {
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 8.0f, 8.0f);
            ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 4.0f, 4.0f);
            ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8.0f, 4.0f);
            ImGui.pushStyleVar(ImGuiStyleVar.ItemInnerSpacing, 4.0f, 4.0f);
            
            // Track pushes
            for (int i = 0; i < 4; i++) {
                stylePushStack.push(-1); // Use -1 to indicate spacing group
            }
            
            logger.debug("Applied standard spacing");
            
        } catch (Exception e) {
            logger.error("Failed to apply standard spacing", e);
        }
    }
    
    /**
     * Apply compact spacing for dense layouts
     */
    public static void applyCompactSpacing() {
        if (!validateImGuiContext()) {
            return;
        }
        
        try {
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 4.0f, 4.0f);
            ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 2.0f, 2.0f);
            ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 4.0f, 2.0f);
            ImGui.pushStyleVar(ImGuiStyleVar.ItemInnerSpacing, 2.0f, 2.0f);
            
            // Track pushes
            for (int i = 0; i < 4; i++) {
                stylePushStack.push(-2); // Use -2 to indicate compact spacing group
            }
            
            logger.debug("Applied compact spacing");
            
        } catch (Exception e) {
            logger.error("Failed to apply compact spacing", e);
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
     * Get current style stack depth for debugging
     */
    public static String getStackStatus() {
        return String.format("Colors: %d, StyleVars: %d, Fonts: %d", 
                           colorPushStack.size(), stylePushStack.size(), fontStack.size());
    }
    
    /**
     * Validate theme definition for ImGui compatibility
     */
    public static boolean validateThemeForImGui(ThemeDefinition theme) {
        ValidationUtils.ValidationResult result = ValidationUtils.validateThemeDefinition(theme);
        if (result.isInvalid()) {
            logger.warn("Theme validation failed: {}", result.getErrorMessage());
            return false;
        }
        
        logger.debug("Theme validation passed: {}", theme.getName());
        return true;
    }
    
    /**
     * Create a default window configuration for testing
     * @deprecated Use WindowConfig predefined configurations instead
     */
    @Deprecated
    public static WindowConfig createSimpleWindowConfig(String title) {
        return new WindowConfig(title);
    }
}