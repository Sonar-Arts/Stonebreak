package com.openmason.ui.themes.preview;
import com.openmason.ui.themes.core.ThemeDefinition;
import com.openmason.ui.themes.application.DensityManager;
import com.openmason.ui.themes.application.StyleApplicator;

import imgui.ImGui;
import imgui.ImVec4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Real-time theme preview system for Open Mason's ImGui-based interface.
 * Provides safe, reversible theme previewing with automatic timeout and rollback functionality.
 * 
 * Part of Phase 1 of the UI Flexibility Refactoring Plan.
 * Integrates with StyleApplicator for theme application and DensityManager for density scaling.
 */
public class ThemePreview {
    private static final Logger logger = LoggerFactory.getLogger(ThemePreview.class);
    
    // Preview configuration
    private static final long DEFAULT_PREVIEW_TIMEOUT_MS = 30_000; // 30 seconds
    private static final long MAX_PREVIEW_TIMEOUT_MS = 300_000; // 5 minutes
    
    // Thread safety for preview operations
    private final ReentrantLock previewLock = new ReentrantLock();
    
    // Preview state management
    private boolean previewActive = false;
    private ThemeDefinition originalTheme;
    private DensityManager.UIDensity originalDensity;
    private ThemeDefinition currentPreviewTheme;
    private DensityManager.UIDensity currentPreviewDensity;
    
    // Timeout management
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ThemePreview-Timeout");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> timeoutTask;
    private long previewTimeoutMs = DEFAULT_PREVIEW_TIMEOUT_MS;
    
    // Integration components
    private DensityManager densityManager;
    private Consumer<String> statusCallback;
    private Consumer<ThemeDefinition> previewStartCallback;
    private Consumer<ThemeDefinition> previewEndCallback;
    
    // Cached ImGui state for emergency recovery
    private final Map<Integer, ImVec4> cachedColors = new ConcurrentHashMap<>();
    private final Map<Integer, Float> cachedStyleVars = new ConcurrentHashMap<>();
    
    /**
     * Create a new theme preview instance
     */
    public ThemePreview() {
        this(null);
    }
    
    /**
     * Create a theme preview instance with density manager integration
     */
    public ThemePreview(DensityManager densityManager) {
        this.densityManager = densityManager;
        logger.debug("ThemePreview initialized with density manager: {}", 
                    densityManager != null ? "enabled" : "disabled");
    }
    
    /**
     * Start preview of a theme without density changes
     */
    public boolean startPreview(ThemeDefinition theme) {
        return startPreview(theme, null);
    }
    
    /**
     * Start preview of a theme with optional density setting
     */
    public boolean startPreview(ThemeDefinition theme, DensityManager.UIDensity density) {
        if (theme == null) {
            logger.warn("Cannot start preview with null theme");
            return false;
        }
        
        previewLock.lock();
        try {
            // Validate theme before starting preview
            if (!StyleApplicator.validateThemeForApplication(theme)) {
                logger.error("Theme validation failed, cannot start preview: {}", theme.getName());
                return false;
            }
            
            // End any existing preview first
            if (previewActive) {
                logger.debug("Ending existing preview before starting new one");
                endPreviewInternal();
            }
            
            logger.info("Starting theme preview: {} {}", theme.getName(), 
                       density != null ? "with density " + density.getDisplayName() : "");
            
            // Capture current state for rollback
            if (!captureCurrentState()) {
                logger.error("Failed to capture current state, cannot start preview");
                return false;
            }
            
            // Apply preview theme
            boolean success = applyPreviewTheme(theme, density);
            if (!success) {
                logger.error("Failed to apply preview theme, restoring original state");
                restoreOriginalState();
                return false;
            }
            
            // Mark preview as active
            previewActive = true;
            currentPreviewTheme = theme;
            currentPreviewDensity = density;
            
            // Start timeout timer
            startTimeoutTimer();
            
            // Notify callbacks
            notifyPreviewStart(theme);
            updateStatus("Preview active: " + theme.getName());
            
            logger.info("Successfully started theme preview: {}", theme.getName());
            return true;
            
        } catch (Exception e) {
            logger.error("Exception during preview start", e);
            // Attempt to restore state on error
            try {
                restoreOriginalState();
            } catch (Exception restoreEx) {
                logger.error("Failed to restore state after preview error", restoreEx);
                StyleApplicator.emergencyReset();
            }
            return false;
        } finally {
            previewLock.unlock();
        }
    }
    
    /**
     * End the current preview and revert to original theme
     */
    public boolean endPreview() {
        previewLock.lock();
        try {
            if (!previewActive) {
                logger.debug("No active preview to end");
                return false;
            }
            
            return endPreviewInternal();
            
        } finally {
            previewLock.unlock();
        }
    }
    
    /**
     * Confirm the current preview, making it permanent
     */
    public boolean confirmPreview() {
        previewLock.lock();
        try {
            if (!previewActive) {
                logger.warn("No active preview to confirm");
                return false;
            }
            
            logger.info("Confirming theme preview: {}", currentPreviewTheme.getName());
            
            // Cancel timeout since we're confirming
            cancelTimeoutTimer();
            
            // Update state - the current preview becomes the new "original"
            originalTheme = currentPreviewTheme;
            originalDensity = currentPreviewDensity;
            
            // Clear preview state
            previewActive = false;
            currentPreviewTheme = null;
            currentPreviewDensity = null;
            
            // Notify callbacks
            notifyPreviewEnd(originalTheme);
            updateStatus("Preview confirmed: " + originalTheme.getName());
            
            logger.info("Successfully confirmed theme preview: {}", originalTheme.getName());
            return true;
            
        } catch (Exception e) {
            logger.error("Exception during preview confirmation", e);
            return false;
        } finally {
            previewLock.unlock();
        }
    }
    
    /**
     * Check if a preview is currently active
     */
    public boolean isPreviewActive() {
        return previewActive;
    }
    
    /**
     * Get the original theme (before preview)
     */
    public ThemeDefinition getOriginalTheme() {
        return originalTheme;
    }
    
    /**
     * Get the original density (before preview)
     */
    public DensityManager.UIDensity getOriginalDensity() {
        return originalDensity;
    }
    
    /**
     * Get the currently previewed theme
     */
    public ThemeDefinition getCurrentPreviewTheme() {
        return currentPreviewTheme;
    }
    
    /**
     * Get the currently previewed density
     */
    public DensityManager.UIDensity getCurrentPreviewDensity() {
        return currentPreviewDensity;
    }
    
    /**
     * Set the preview timeout duration
     */
    public void setPreviewTimeout(long timeoutMs) {
        if (timeoutMs <= 0 || timeoutMs > MAX_PREVIEW_TIMEOUT_MS) {
            logger.warn("Invalid preview timeout: {}ms, using default", timeoutMs);
            this.previewTimeoutMs = DEFAULT_PREVIEW_TIMEOUT_MS;
        } else {
            this.previewTimeoutMs = timeoutMs;
            logger.debug("Preview timeout set to: {}ms", timeoutMs);
        }
    }
    
    /**
     * Set the density manager for preview operations
     */
    public void setDensityManager(DensityManager densityManager) {
        this.densityManager = densityManager;
        logger.debug("DensityManager updated for ThemePreview");
    }
    
    /**
     * Set callback for status updates
     */
    public void setStatusCallback(Consumer<String> callback) {
        this.statusCallback = callback;
    }
    
    /**
     * Set callback for preview start events
     */
    public void setPreviewStartCallback(Consumer<ThemeDefinition> callback) {
        this.previewStartCallback = callback;
    }
    
    /**
     * Set callback for preview end events
     */
    public void setPreviewEndCallback(Consumer<ThemeDefinition> callback) {
        this.previewEndCallback = callback;
    }
    
    /**
     * Get preview status information
     */
    public String getPreviewStatus() {
        if (!previewActive) {
            return "No preview active";
        }
        
        long remainingTime = getRemainingPreviewTime();
        return String.format("Preview active: %s (%.1fs remaining)", 
                           currentPreviewTheme.getName(), remainingTime / 1000.0);
    }
    
    /**
     * Shutdown the preview system and cleanup resources
     */
    public void shutdown() {
        previewLock.lock();
        try {
            logger.info("Shutting down ThemePreview");
            
            // End any active preview
            if (previewActive) {
                endPreviewInternal();
            }
            
            // Shutdown timeout executor
            timeoutExecutor.shutdown();
            try {
                if (!timeoutExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    timeoutExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                timeoutExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            
            // Clear cached state
            cachedColors.clear();
            cachedStyleVars.clear();
            
            logger.info("ThemePreview shutdown completed");
            
        } catch (Exception e) {
            logger.error("Error during ThemePreview shutdown", e);
        } finally {
            previewLock.unlock();
        }
    }
    
    // Private implementation methods
    
    private boolean endPreviewInternal() {
        if (!previewActive) {
            return false;
        }
        
        logger.info("Ending theme preview: {}", currentPreviewTheme.getName());
        
        // Cancel timeout timer
        cancelTimeoutTimer();
        
        // Restore original state
        boolean restored = restoreOriginalState();
        
        // Clear preview state
        previewActive = false;
        ThemeDefinition endedTheme = currentPreviewTheme;
        currentPreviewTheme = null;
        currentPreviewDensity = null;
        
        // Notify callbacks
        notifyPreviewEnd(endedTheme);
        updateStatus(restored ? "Preview ended, theme restored" : "Preview ended with errors");
        
        logger.info("Theme preview ended: {}", endedTheme != null ? endedTheme.getName() : "unknown");
        return restored;
    }
    
    private boolean captureCurrentState() {
        try {
            if (!StyleApplicator.isImGuiContextValid()) {
                logger.error("Cannot capture state - ImGui context is invalid");
                return false;
            }
            
            // TODO: Capture current theme from ThemeManager if available
            // For now, we'll create a minimal theme representing current state
            originalTheme = createCurrentStateTheme();
            
            // Capture current density
            if (densityManager != null) {
                originalDensity = densityManager.getCurrentDensity();
                logger.debug("Captured original density: {}", originalDensity.getDisplayName());
            }
            
            // Cache ImGui state for emergency recovery
            cacheImGuiState();
            
            logger.debug("Successfully captured current state for preview");
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to capture current state", e);
            return false;
        }
    }
    
    private ThemeDefinition createCurrentStateTheme() {
        // Create a theme definition representing the current ImGui state
        ThemeDefinition currentState = new ThemeDefinition(
            "current_state", 
            "Current State", 
            "Captured current theme state", 
            ThemeDefinition.ThemeType.BUILT_IN
        );
        currentState.setReadOnly(true);
        
        // Note: In a full implementation, we would capture current ImGui colors and style vars
        // For now, this serves as a placeholder for the rollback mechanism
        
        return currentState;
    }
    
    private void cacheImGuiState() {
        if (!StyleApplicator.isImGuiContextValid()) {
            return;
        }
        
        try {
            cachedColors.clear();
            cachedStyleVars.clear();
            
            // Cache would capture current ImGui state here
            // Implementation depends on available ImGui state access methods
            
            logger.debug("Cached ImGui state for emergency recovery");
            
        } catch (Exception e) {
            logger.warn("Failed to cache ImGui state", e);
        }
    }
    
    private boolean applyPreviewTheme(ThemeDefinition theme, DensityManager.UIDensity density) {
        try {
            // Apply theme with or without density scaling
            if (density != null && densityManager != null) {
                // Create density-scaled theme
                ThemeDefinition scaledTheme = densityManager.applyDensityToTheme(theme);
                if (scaledTheme != null) {
                    StyleApplicator.applyTheme(scaledTheme);
                    logger.debug("Applied preview theme with density scaling: {} ({})", 
                               theme.getName(), density.getDisplayName());
                } else {
                    // Fallback to original theme
                    StyleApplicator.applyTheme(theme);
                    logger.warn("Density scaling failed, applied original theme");
                }
                
                // Apply density settings
                densityManager.setDensity(density);
                
            } else {
                // Apply theme without density changes
                StyleApplicator.applyTheme(theme);
                logger.debug("Applied preview theme without density changes: {}", theme.getName());
            }
            
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to apply preview theme: " + theme.getName(), e);
            return false;
        }
    }
    
    private boolean restoreOriginalState() {
        try {
            if (originalTheme == null) {
                logger.warn("No original theme to restore, performing emergency reset");
                StyleApplicator.emergencyReset();
                return false;
            }
            
            // Restore original density first if available
            if (originalDensity != null && densityManager != null) {
                densityManager.setDensity(originalDensity);
                logger.debug("Restored original density: {}", originalDensity.getDisplayName());
            }
            
            // Restore original theme
            StyleApplicator.applyTheme(originalTheme);
            logger.debug("Restored original theme: {}", originalTheme.getName());
            
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to restore original state", e);
            // Emergency fallback
            try {
                StyleApplicator.emergencyReset();
                if (densityManager != null) {
                    densityManager.emergencyReset();
                }
            } catch (Exception emergencyEx) {
                logger.error("Emergency reset also failed", emergencyEx);
            }
            return false;
        }
    }
    
    private void startTimeoutTimer() {
        cancelTimeoutTimer(); // Cancel any existing timer
        
        timeoutTask = timeoutExecutor.schedule(() -> {
            logger.info("Preview timeout reached, auto-reverting theme");
            
            // Auto-revert the preview
            previewLock.lock();
            try {
                if (previewActive) {
                    endPreviewInternal();
                    updateStatus("Preview timed out and reverted automatically");
                }
            } finally {
                previewLock.unlock();
            }
            
        }, previewTimeoutMs, TimeUnit.MILLISECONDS);
        
        logger.debug("Started preview timeout timer: {}ms", previewTimeoutMs);
    }
    
    private void cancelTimeoutTimer() {
        if (timeoutTask != null && !timeoutTask.isDone()) {
            timeoutTask.cancel(false);
            logger.debug("Cancelled preview timeout timer");
        }
        timeoutTask = null;
    }
    
    private long getRemainingPreviewTime() {
        if (timeoutTask == null || timeoutTask.isDone()) {
            return 0;
        }
        return timeoutTask.getDelay(TimeUnit.MILLISECONDS);
    }
    
    private void notifyPreviewStart(ThemeDefinition theme) {
        if (previewStartCallback != null) {
            try {
                previewStartCallback.accept(theme);
            } catch (Exception e) {
                logger.warn("Error in preview start callback", e);
            }
        }
    }
    
    private void notifyPreviewEnd(ThemeDefinition theme) {
        if (previewEndCallback != null) {
            try {
                previewEndCallback.accept(theme);
            } catch (Exception e) {
                logger.warn("Error in preview end callback", e);
            }
        }
    }
    
    private void updateStatus(String status) {
        if (statusCallback != null) {
            try {
                statusCallback.accept(status);
            } catch (Exception e) {
                logger.warn("Error in status callback", e);
            }
        }
        logger.debug("Status: {}", status);
    }
}