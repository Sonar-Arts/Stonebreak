package com.stonebreak.textures.utils;

import com.stonebreak.textures.mobs.CowTextureDefinition;

/**
 * Utility class for null-safe operations in texture generation.
 * Prevents null pointer exceptions by validating all required fields.
 */
public class NullSafeTextureUtils {
    
    /**
     * Safely get integer value with default fallback.
     */
    public static int safeGetInt(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }
    
    /**
     * Safely get float value with default fallback.
     */
    public static float safeGetFloat(Float value, float defaultValue) {
        return value != null ? value : defaultValue;
    }
    
    /**
     * Safely get boolean value with default fallback.
     */
    public static boolean safeGetBoolean(Boolean value, boolean defaultValue) {
        return value != null ? value : defaultValue;
    }
    
    /**
     * Safely get string value with default fallback.
     */
    public static String safeGetString(String value, String defaultValue) {
        return value != null ? value : defaultValue;
    }
    
    /**
     * Validate that a Size object has valid width and height.
     */
    public static boolean isValidSize(CowTextureDefinition.Size size) {
        return size != null && 
               size.getWidth() != null && size.getWidth() > 0 &&
               size.getHeight() != null && size.getHeight() > 0;
    }
    
    /**
     * Validate that a Position object has valid x and y coordinates.
     */
    public static boolean isValidPosition(CowTextureDefinition.Position position) {
        return position != null && 
               position.getX() != null && position.getX() >= 0 &&
               position.getY() != null && position.getY() >= 0;
    }
    
    /**
     * Validate that facial features have minimum required data.
     */
    public static boolean isValidEyeFeatures(CowTextureDefinition.EyeFeatures eyes) {
        return eyes != null && 
               isValidSize(eyes.getSize()) && 
               isValidPosition(eyes.getPosition());
    }
    
    /**
     * Validate that nose features have minimum required data.
     */
    public static boolean isValidNoseFeatures(CowTextureDefinition.NoseFeatures nose) {
        return nose != null && 
               isValidSize(nose.getSize()) && 
               isValidPosition(nose.getPosition());
    }
    
    /**
     * Validate that mouth features have minimum required data.
     */
    public static boolean isValidMouthFeatures(CowTextureDefinition.MouthFeatures mouth) {
        return mouth != null && 
               isValidSize(mouth.getSize()) && 
               isValidPosition(mouth.getPosition());
    }
    
    /**
     * Validate that a pattern has minimum required data.
     */
    public static boolean isValidPattern(CowTextureDefinition.Pattern pattern) {
        return pattern != null && 
               pattern.getPositions() != null && 
               !pattern.getPositions().isEmpty() &&
               isValidSize(pattern.getSize());
    }
    
    /**
     * Validate that a highlight has minimum required data.
     */
    public static boolean isValidHighlight(CowTextureDefinition.Highlight highlight) {
        return highlight != null && 
               isValidSize(highlight.getSize()) && 
               isValidPosition(highlight.getPosition());
    }
    
    /**
     * Log validation error with context.
     */
    public static void logValidationError(String context, String variantName, String details) {
        System.err.println("[CowTextureGenerator] " + context + " validation failed for " + variantName + 
                          (details != null ? ": " + details : ""));
    }
}