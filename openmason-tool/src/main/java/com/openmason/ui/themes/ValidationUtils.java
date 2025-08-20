package com.openmason.ui.themes;

import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Comprehensive validation utilities for the Open Mason theming system.
 * Part of Phase 1 UI Flexibility Refactoring Plan.
 * 
 * Provides common validation functions used across multiple theme components
 * including color validation, theme definition validation, file path validation,
 * and generic validation helpers.
 * 
 * Features:
 * - Color validation (hex, RGB, ImVec4, bounds checking)
 * - Theme definition validation with detailed error reporting
 * - File path and filename validation with security checks
 * - ImGui-specific validation (color IDs, style variables)
 * - Generic validation helpers for common patterns
 * - Performance-optimized with compiled regex patterns
 * - Thread-safe and stateless design
 * - Comprehensive logging for debugging
 * 
 * This class is used by ThemeDefinition, WindowConfig, ImGuiHelpers,
 * and other theme system components to ensure data integrity and security.
 */
public final class ValidationUtils {
    
    private static final Logger logger = LoggerFactory.getLogger(ValidationUtils.class);
    
    // ================================
    // Color Validation Constants
    // ================================
    
    // Hex color patterns (compiled for performance)
    private static final Pattern HEX_COLOR_PATTERN_6 = Pattern.compile("^#?([A-Fa-f0-9]{6})$");
    private static final Pattern HEX_COLOR_PATTERN_8 = Pattern.compile("^#?([A-Fa-f0-9]{8})$");
    private static final Pattern HEX_COLOR_PATTERN_3 = Pattern.compile("^#?([A-Fa-f0-9]{3})$");
    private static final Pattern HEX_COLOR_PATTERN_4 = Pattern.compile("^#?([A-Fa-f0-9]{4})$");
    
    // Color component bounds
    public static final float COLOR_MIN = 0.0f;
    public static final float COLOR_MAX = 1.0f;
    public static final int RGB_MIN = 0;
    public static final int RGB_MAX = 255;
    
    // ================================
    // File Validation Constants
    // ================================
    
    // Filename patterns
    private static final Pattern VALID_FILENAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._\\-\\s]+$");
    private static final Pattern DANGEROUS_FILENAME_PATTERN = Pattern.compile("[\\\\/:\\*\\?\"<>\\|]");
    
    // File extensions
    private static final String THEME_EXTENSION = ".theme.json";
    private static final String JSON_EXTENSION = ".json";
    
    // Path traversal detection
    private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile("\\.\\.[\\\\/]");
    
    // Filename constraints
    public static final int MAX_FILENAME_LENGTH = 255;
    public static final int MAX_PATH_LENGTH = 4096;
    
    // ================================
    // Theme Validation Constants
    // ================================
    
    // Theme name constraints
    public static final int MIN_THEME_NAME_LENGTH = 1;
    public static final int MAX_THEME_NAME_LENGTH = 100;
    public static final int MIN_THEME_ID_LENGTH = 1;
    public static final int MAX_THEME_ID_LENGTH = 50;
    
    // Pattern for valid theme IDs (alphanumeric, underscore, hyphen)
    private static final Pattern THEME_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]+$");
    
    // Prevent instantiation - utility class
    private ValidationUtils() {
        throw new UnsupportedOperationException("ValidationUtils is a utility class and cannot be instantiated");
    }
    
    // ================================
    // Color Validation Methods
    // ================================
    
    /**
     * Validate hex color string with comprehensive format support
     * Supports: #RGB, #RGBA, #RRGGBB, #RRGGBBAA (with or without #)
     */
    public static boolean isValidHexColor(String hexColor) {
        if (isNullOrEmpty(hexColor)) {
            return false;
        }
        
        String trimmed = hexColor.trim();
        
        try {
            return HEX_COLOR_PATTERN_3.matcher(trimmed).matches() ||
                   HEX_COLOR_PATTERN_4.matcher(trimmed).matches() ||
                   HEX_COLOR_PATTERN_6.matcher(trimmed).matches() ||
                   HEX_COLOR_PATTERN_8.matcher(trimmed).matches();
        } catch (Exception e) {
            logger.debug("Hex color validation failed for '{}': {}", hexColor, e.getMessage());
            return false;
        }
    }
    
    /**
     * Validate hex color with detailed error reporting
     */
    public static ValidationResult validateHexColor(String hexColor) {
        if (isNullOrEmpty(hexColor)) {
            return ValidationResult.failure("Hex color cannot be null or empty");
        }
        
        String trimmed = hexColor.trim();
        
        if (!isValidHexColor(trimmed)) {
            return ValidationResult.failure(String.format(
                "Invalid hex color format: '%s'. Expected formats: #RGB, #RGBA, #RRGGBB, #RRGGBBAA", 
                hexColor));
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Validate RGB color components (0-255 range)
     */
    public static boolean isValidRgbColor(int r, int g, int b) {
        return isValidRgbComponent(r) && isValidRgbComponent(g) && isValidRgbComponent(b);
    }
    
    /**
     * Validate RGBA color components (0-255 range)
     */
    public static boolean isValidRgbaColor(int r, int g, int b, int a) {
        return isValidRgbColor(r, g, b) && isValidRgbComponent(a);
    }
    
    /**
     * Validate single RGB component (0-255)
     */
    public static boolean isValidRgbComponent(int component) {
        return component >= RGB_MIN && component <= RGB_MAX;
    }
    
    /**
     * Validate float color component (0.0-1.0)
     */
    public static boolean isValidColorComponent(float component) {
        return !Float.isNaN(component) && !Float.isInfinite(component) && 
               component >= COLOR_MIN && component <= COLOR_MAX;
    }
    
    /**
     * Validate ImVec4 color object
     */
    public static boolean isValidImVec4Color(ImVec4 color) {
        if (color == null) {
            return false;
        }
        
        return isValidColorComponent(color.x) && 
               isValidColorComponent(color.y) && 
               isValidColorComponent(color.z) && 
               isValidColorComponent(color.w);
    }
    
    /**
     * Validate ImVec4 color with detailed error reporting
     */
    public static ValidationResult validateImVec4Color(ImVec4 color) {
        if (color == null) {
            return ValidationResult.failure("Color cannot be null");
        }
        
        if (!isValidColorComponent(color.x)) {
            return ValidationResult.failure(String.format("Invalid red component: %f (expected 0.0-1.0)", color.x));
        }
        
        if (!isValidColorComponent(color.y)) {
            return ValidationResult.failure(String.format("Invalid green component: %f (expected 0.0-1.0)", color.y));
        }
        
        if (!isValidColorComponent(color.z)) {
            return ValidationResult.failure(String.format("Invalid blue component: %f (expected 0.0-1.0)", color.z));
        }
        
        if (!isValidColorComponent(color.w)) {
            return ValidationResult.failure(String.format("Invalid alpha component: %f (expected 0.0-1.0)", color.w));
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Validate ImGui color ID
     */
    public static boolean isValidImGuiColorId(int colorId) {
        return colorId >= 0 && colorId < ImGuiCol.COUNT;
    }
    
    /**
     * Validate ImGui style variable ID
     */
    public static boolean isValidImGuiStyleVar(int styleVar) {
        return styleVar >= 0 && styleVar < ImGuiStyleVar.COUNT;
    }
    
    // ================================
    // File and Path Validation Methods
    // ================================
    
    /**
     * Validate filename for security and compatibility
     */
    public static boolean isValidFilename(String filename) {
        if (isNullOrEmpty(filename)) {
            return false;
        }
        
        String trimmed = filename.trim();
        
        // Check length
        if (trimmed.length() > MAX_FILENAME_LENGTH) {
            return false;
        }
        
        // Check for dangerous characters
        if (DANGEROUS_FILENAME_PATTERN.matcher(trimmed).find()) {
            return false;
        }
        
        // Check for path traversal attempts
        if (PATH_TRAVERSAL_PATTERN.matcher(trimmed).find()) {
            return false;
        }
        
        // Check for reserved names (Windows)
        String upper = trimmed.toUpperCase();
        if (upper.equals("CON") || upper.equals("PRN") || upper.equals("AUX") || upper.equals("NUL") ||
            upper.matches("COM[1-9]") || upper.matches("LPT[1-9]")) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Validate filename with detailed error reporting
     */
    public static ValidationResult validateFilename(String filename) {
        if (isNullOrEmpty(filename)) {
            return ValidationResult.failure("Filename cannot be null or empty");
        }
        
        String trimmed = filename.trim();
        
        if (trimmed.length() > MAX_FILENAME_LENGTH) {
            return ValidationResult.failure(String.format(
                "Filename too long: %d characters (maximum: %d)", 
                trimmed.length(), MAX_FILENAME_LENGTH));
        }
        
        if (DANGEROUS_FILENAME_PATTERN.matcher(trimmed).find()) {
            return ValidationResult.failure(String.format(
                "Filename contains invalid characters: '%s'", filename));
        }
        
        if (PATH_TRAVERSAL_PATTERN.matcher(trimmed).find()) {
            return ValidationResult.failure(String.format(
                "Filename contains path traversal sequence: '%s'", filename));
        }
        
        String upper = trimmed.toUpperCase();
        if (upper.equals("CON") || upper.equals("PRN") || upper.equals("AUX") || upper.equals("NUL") ||
            upper.matches("COM[1-9]") || upper.matches("LPT[1-9]")) {
            return ValidationResult.failure(String.format(
                "Filename is a reserved system name: '%s'", filename));
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Validate file path for security and existence
     */
    public static boolean isValidFilePath(String filePath) {
        if (isNullOrEmpty(filePath)) {
            return false;
        }
        
        try {
            Path path = Paths.get(filePath);
            
            // Check path length
            if (filePath.length() > MAX_PATH_LENGTH) {
                return false;
            }
            
            // Check for path traversal
            if (PATH_TRAVERSAL_PATTERN.matcher(filePath).find()) {
                return false;
            }
            
            // Path should be valid
            return path != null;
            
        } catch (InvalidPathException e) {
            logger.debug("Invalid file path: {}", filePath, e);
            return false;
        }
    }
    
    /**
     * Validate file path with detailed error reporting
     */
    public static ValidationResult validateFilePath(String filePath) {
        if (isNullOrEmpty(filePath)) {
            return ValidationResult.failure("File path cannot be null or empty");
        }
        
        if (filePath.length() > MAX_PATH_LENGTH) {
            return ValidationResult.failure(String.format(
                "File path too long: %d characters (maximum: %d)", 
                filePath.length(), MAX_PATH_LENGTH));
        }
        
        if (PATH_TRAVERSAL_PATTERN.matcher(filePath).find()) {
            return ValidationResult.failure(String.format(
                "File path contains path traversal sequence: '%s'", filePath));
        }
        
        try {
            Path path = Paths.get(filePath);
            if (path == null) {
                return ValidationResult.failure(String.format("Invalid file path: '%s'", filePath));
            }
            
            return ValidationResult.success();
            
        } catch (InvalidPathException e) {
            return ValidationResult.failure(String.format(
                "Invalid file path syntax: '%s' - %s", filePath, e.getMessage()));
        }
    }
    
    /**
     * Validate that file exists and is readable
     */
    public static boolean isValidExistingFile(String filePath) {
        if (!isValidFilePath(filePath)) {
            return false;
        }
        
        try {
            Path path = Paths.get(filePath);
            return Files.exists(path) && Files.isRegularFile(path) && Files.isReadable(path);
        } catch (Exception e) {
            logger.debug("File existence check failed for: {}", filePath, e);
            return false;
        }
    }
    
    /**
     * Validate that directory exists and is accessible
     */
    public static boolean isValidExistingDirectory(String dirPath) {
        if (!isValidFilePath(dirPath)) {
            return false;
        }
        
        try {
            Path path = Paths.get(dirPath);
            return Files.exists(path) && Files.isDirectory(path) && Files.isReadable(path);
        } catch (Exception e) {
            logger.debug("Directory existence check failed for: {}", dirPath, e);
            return false;
        }
    }
    
    /**
     * Validate theme file extension
     */
    public static boolean hasValidThemeExtension(String filename) {
        if (isNullOrEmpty(filename)) {
            return false;
        }
        
        return filename.toLowerCase().endsWith(THEME_EXTENSION) || 
               filename.toLowerCase().endsWith(JSON_EXTENSION);
    }
    
    // ================================
    // Theme Definition Validation Methods
    // ================================
    
    /**
     * Validate theme ID format
     */
    public static boolean isValidThemeId(String themeId) {
        if (isNullOrEmpty(themeId)) {
            return false;
        }
        
        String trimmed = themeId.trim();
        
        return trimmed.length() >= MIN_THEME_ID_LENGTH && 
               trimmed.length() <= MAX_THEME_ID_LENGTH &&
               THEME_ID_PATTERN.matcher(trimmed).matches();
    }
    
    /**
     * Validate theme ID with detailed error reporting
     */
    public static ValidationResult validateThemeId(String themeId) {
        if (isNullOrEmpty(themeId)) {
            return ValidationResult.failure("Theme ID cannot be null or empty");
        }
        
        String trimmed = themeId.trim();
        
        if (trimmed.length() < MIN_THEME_ID_LENGTH) {
            return ValidationResult.failure(String.format(
                "Theme ID too short: %d characters (minimum: %d)", 
                trimmed.length(), MIN_THEME_ID_LENGTH));
        }
        
        if (trimmed.length() > MAX_THEME_ID_LENGTH) {
            return ValidationResult.failure(String.format(
                "Theme ID too long: %d characters (maximum: %d)", 
                trimmed.length(), MAX_THEME_ID_LENGTH));
        }
        
        if (!THEME_ID_PATTERN.matcher(trimmed).matches()) {
            return ValidationResult.failure(String.format(
                "Theme ID contains invalid characters: '%s' (allowed: a-z, A-Z, 0-9, _, -)", themeId));
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Validate theme name
     */
    public static boolean isValidThemeName(String themeName) {
        if (isNullOrEmpty(themeName)) {
            return false;
        }
        
        String trimmed = themeName.trim();
        return trimmed.length() >= MIN_THEME_NAME_LENGTH && 
               trimmed.length() <= MAX_THEME_NAME_LENGTH;
    }
    
    /**
     * Validate theme name with detailed error reporting
     */
    public static ValidationResult validateThemeName(String themeName) {
        if (isNullOrEmpty(themeName)) {
            return ValidationResult.failure("Theme name cannot be null or empty");
        }
        
        String trimmed = themeName.trim();
        
        if (trimmed.length() < MIN_THEME_NAME_LENGTH) {
            return ValidationResult.failure(String.format(
                "Theme name too short: %d characters (minimum: %d)", 
                trimmed.length(), MIN_THEME_NAME_LENGTH));
        }
        
        if (trimmed.length() > MAX_THEME_NAME_LENGTH) {
            return ValidationResult.failure(String.format(
                "Theme name too long: %d characters (maximum: %d)", 
                trimmed.length(), MAX_THEME_NAME_LENGTH));
        }
        
        return ValidationResult.success();
    }
    
    /**
     * Validate complete theme definition
     */
    public static ValidationResult validateThemeDefinition(ThemeDefinition theme) {
        if (theme == null) {
            return ValidationResult.failure("Theme definition cannot be null");
        }
        
        // Validate theme ID
        ValidationResult idResult = validateThemeId(theme.getId());
        if (!idResult.isValid()) {
            return ValidationResult.failure("Invalid theme ID: " + idResult.getErrorMessage());
        }
        
        // Validate theme name
        ValidationResult nameResult = validateThemeName(theme.getName());
        if (!nameResult.isValid()) {
            return ValidationResult.failure("Invalid theme name: " + nameResult.getErrorMessage());
        }
        
        // Validate theme type
        if (theme.getType() == null) {
            return ValidationResult.failure("Theme type cannot be null");
        }
        
        // Validate colors
        for (var entry : theme.getColors().entrySet()) {
            Integer colorId = entry.getKey();
            ImVec4 color = entry.getValue();
            
            if (!isValidImGuiColorId(colorId)) {
                return ValidationResult.failure(String.format(
                    "Invalid ImGui color ID: %d (valid range: 0-%d)", colorId, ImGuiCol.COUNT - 1));
            }
            
            ValidationResult colorResult = validateImVec4Color(color);
            if (!colorResult.isValid()) {
                return ValidationResult.failure(String.format(
                    "Invalid color for ID %d: %s", colorId, colorResult.getErrorMessage()));
            }
        }
        
        // Validate style variables
        for (var entry : theme.getStyleVars().entrySet()) {
            Integer styleVar = entry.getKey();
            Float value = entry.getValue();
            
            if (!isValidImGuiStyleVar(styleVar)) {
                return ValidationResult.failure(String.format(
                    "Invalid ImGui style variable ID: %d (valid range: 0-%d)", 
                    styleVar, ImGuiStyleVar.COUNT - 1));
            }
            
            if (value == null || Float.isNaN(value) || Float.isInfinite(value) || value < 0) {
                return ValidationResult.failure(String.format(
                    "Invalid style variable value for ID %d: %f", styleVar, value));
            }
        }
        
        return ValidationResult.success();
    }
    
    // ================================
    // Generic Validation Helpers
    // ================================
    
    /**
     * Check if string is null or empty/whitespace
     */
    public static boolean isNullOrEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }
    
    /**
     * Check if collection is null or empty
     */
    public static boolean isNullOrEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }
    
    /**
     * Validate string length within bounds
     */
    public static boolean isValidLength(String str, int minLength, int maxLength) {
        if (str == null) {
            return minLength <= 0;
        }
        
        int length = str.length();
        return length >= minLength && length <= maxLength;
    }
    
    /**
     * Validate numeric value within bounds (inclusive)
     */
    public static boolean isInRange(int value, int min, int max) {
        return value >= min && value <= max;
    }
    
    /**
     * Validate numeric value within bounds (inclusive)
     */
    public static boolean isInRange(float value, float min, float max) {
        return !Float.isNaN(value) && !Float.isInfinite(value) && value >= min && value <= max;
    }
    
    /**
     * Validate numeric value within bounds (inclusive)
     */
    public static boolean isInRange(double value, double min, double max) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value >= min && value <= max;
    }
    
    /**
     * Validate that value is positive (> 0)
     */
    public static boolean isPositive(int value) {
        return value > 0;
    }
    
    /**
     * Validate that value is positive (> 0)
     */
    public static boolean isPositive(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value) && value > 0;
    }
    
    /**
     * Validate that value is non-negative (>= 0)
     */
    public static boolean isNonNegative(int value) {
        return value >= 0;
    }
    
    /**
     * Validate that value is non-negative (>= 0)
     */
    public static boolean isNonNegative(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value) && value >= 0;
    }
    
    /**
     * Sanitize string for safe usage (remove dangerous characters)
     */
    public static String sanitizeString(String input) {
        if (isNullOrEmpty(input)) {
            return "";
        }
        
        return input.replaceAll("[\\x00-\\x1F\\x7F]", "") // Remove control characters
                   .replaceAll("[\\\\/:\\*\\?\"<>\\|]", "_") // Replace dangerous characters
                   .trim();
    }
    
    /**
     * Clamp numeric value to specified bounds
     */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
    
    /**
     * Clamp numeric value to specified bounds
     */
    public static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) return min;
        if (Float.isInfinite(value)) return value > 0 ? max : min;
        return Math.max(min, Math.min(max, value));
    }
    
    // ================================
    // Validation Result Class
    // ================================
    
    /**
     * Result of a validation operation with success/failure and error message
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;
        
        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }
        
        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }
        
        public static ValidationResult failure(String errorMessage) {
            return new ValidationResult(false, errorMessage != null ? errorMessage : "Validation failed");
        }
        
        public boolean isValid() {
            return valid;
        }
        
        public boolean isInvalid() {
            return !valid;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
        
        public String getErrorMessageOrDefault(String defaultMessage) {
            return errorMessage != null ? errorMessage : defaultMessage;
        }
        
        @Override
        public String toString() {
            return valid ? "ValidationResult{valid=true}" : 
                          String.format("ValidationResult{valid=false, error='%s'}", errorMessage);
        }
    }
}