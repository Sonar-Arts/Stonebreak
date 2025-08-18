package com.openmason.texture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

/**
 * Simplified UI-focused texture variant management system for OpenMason.
 * 
 * Provides a UI-friendly bridge to the TextureManager for property panel integration
 * and basic variant switching functionality.
 */
public class TextureVariantManager {
    
    private static final Logger logger = LoggerFactory.getLogger(TextureVariantManager.class);
    
    // UI integration properties
    private String currentVariant = "default";
    private boolean loadingInProgress = false;
    private String loadingStatus = "";
    
    // Performance tracking
    private int switchCount = 0;
    private long lastSwitchTime = 0;
    
    // Singleton instance for UI integration
    private static final TextureVariantManager INSTANCE = new TextureVariantManager();
    
    private TextureVariantManager() {
        // Private constructor for singleton
    }
    
    /**
     * Get singleton instance.
     */
    public static TextureVariantManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Initialize the variant manager.
     */
    public void initialize() {
        // logger.info("Initializing TextureVariantManager...");
        TextureManager.initialize();
        // logger.info("TextureVariantManager initialization complete");
    }
    
    /**
     * Switch to a specific texture variant.
     * 
     * @param variantName The variant to switch to
     * @return true if successful, false otherwise
     */
    public boolean switchToVariant(String variantName) {
        if (variantName == null || variantName.isEmpty()) {
            logger.warn("Cannot switch to null or empty variant name");
            return false;
        }
        
        long startTime = System.currentTimeMillis();
        loadingInProgress = true;
        loadingStatus = "Switching to " + variantName + "...";
        
        try {
            // Validate the variant exists
            if (!TextureManager.validateVariant(variantName)) {
                logger.warn("Invalid variant: {}", variantName);
                loadingStatus = "Invalid variant: " + variantName;
                return false;
            }
            
            // Load variant info to ensure it's cached
            TextureManager.TextureVariantInfo info = TextureManager.getVariantInfo(variantName);
            if (info == null) {
                logger.error("Failed to load variant info for: {}", variantName);
                loadingStatus = "Failed to load variant: " + variantName;
                return false;
            }
            
            // Update current variant
            currentVariant = variantName;
            switchCount++;
            lastSwitchTime = System.currentTimeMillis() - startTime;
            
            loadingStatus = "Switched to " + variantName + " (" + lastSwitchTime + "ms)";
            // logger.info("Successfully switched to variant '{}' in {}ms", variantName, lastSwitchTime);
            
            return true;
            
        } catch (Exception e) {
            logger.error("Error switching to variant: {}", variantName, e);
            loadingStatus = "Error: " + e.getMessage();
            return false;
        } finally {
            loadingInProgress = false;
        }
    }
    
    /**
     * Get the current variant name.
     */
    public String getCurrentVariant() {
        return currentVariant;
    }
    
    /**
     * Check if loading is in progress.
     */
    public boolean isLoadingInProgress() {
        return loadingInProgress;
    }
    
    /**
     * Get the current loading status message.
     */
    public String getLoadingStatus() {
        return loadingStatus;
    }
    
    /**
     * Get list of available variants.
     */
    public List<String> getAvailableVariants() {
        return TextureManager.getAvailableVariants();
    }
    
    /**
     * Validate a variant name.
     */
    public boolean isValidVariant(String variantName) {
        return TextureManager.validateVariant(variantName);
    }
    
    /**
     * Get variant information.
     */
    public TextureManager.TextureVariantInfo getVariantInfo(String variantName) {
        return TextureManager.getVariantInfo(variantName);
    }
    
    /**
     * Get performance statistics for UI display.
     */
    public Map<String, Object> getPerformanceStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("initialized", true);
        stats.put("currentVariant", currentVariant);
        stats.put("switchCount", switchCount);
        stats.put("lastSwitchTime", lastSwitchTime);
        stats.put("loadingInProgress", loadingInProgress);
        stats.put("loadingStatus", loadingStatus);
        stats.put("availableVariants", getAvailableVariants().size());
        return stats;
    }
    
    /**
     * Reset performance counters.
     */
    public void resetPerformanceCounters() {
        switchCount = 0;
        lastSwitchTime = 0;
        // logger.debug("Performance counters reset");
    }
    
    /**
     * Clear caches and reset state.
     */
    public void clearCache() {
        TextureManager.clearCache();
        currentVariant = "default";
        switchCount = 0;
        lastSwitchTime = 0;
        loadingInProgress = false;
        loadingStatus = "";
        // logger.info("TextureVariantManager cache cleared");
    }
}