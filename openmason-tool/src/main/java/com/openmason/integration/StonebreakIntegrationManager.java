package com.openmason.integration;

import com.openmason.ui.viewport.OpenMason3DViewport;
import com.openmason.texture.TextureManager;
import com.openmason.model.ModelManager;
import javafx.application.Platform;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.ArrayList;

/**
 * Central integration manager for all Stonebreak integration features.
 * Coordinates live preview, validation, hot-reload, and asset pipeline systems.
 */
public class StonebreakIntegrationManager {
    private static final Logger logger = LoggerFactory.getLogger(StonebreakIntegrationManager.class);
    
    private final OpenMason3DViewport viewport;
    private final TextureManager textureManager;
    private final ModelManager modelManager;
    
    // Integration systems
    private LivePreviewSystem livePreviewSystem;
    private TextureValidationSystem validationSystem;
    private AssetPipelineIntegration assetPipeline;
    
    // System state
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean integrationActive = new AtomicBoolean(false);
    
    // Integration listeners
    private final List<IntegrationListener> integrationListeners = new ArrayList<>();
    
    public StonebreakIntegrationManager(OpenMason3DViewport viewport, 
                                      TextureManager textureManager, 
                                      ModelManager modelManager) {
        this.viewport = viewport;
        this.textureManager = textureManager;
        this.modelManager = modelManager;
    }
    
    /**
     * Initializes all Stonebreak integration systems.
     */
    public CompletableFuture<IntegrationStatus> initializeIntegration() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (initialized.get()) {
                    logger.warn("Stonebreak integration already initialized");
                    return new IntegrationStatus(true, "Already initialized");
                }
                
                logger.info("Initializing Stonebreak integration systems...");
                
                IntegrationStatus status = new IntegrationStatus();
                
                // Initialize live preview system
                try {
                    livePreviewSystem = new LivePreviewSystem(viewport, textureManager, modelManager);
                    status.addSuccess("Live preview system initialized");
                    logger.info("Live preview system initialized");
                } catch (Exception e) {
                    status.addError("Failed to initialize live preview system: " + e.getMessage());
                    logger.error("Failed to initialize live preview system", e);
                }
                
                // Initialize validation system
                try {
                    validationSystem = new TextureValidationSystem(viewport);
                    status.addSuccess("Texture validation system initialized");
                    logger.info("Texture validation system initialized");
                } catch (Exception e) {
                    status.addError("Failed to initialize validation system: " + e.getMessage());
                    logger.error("Failed to initialize validation system", e);
                }
                
                // Initialize asset pipeline
                try {
                    assetPipeline = new AssetPipelineIntegration(textureManager, modelManager);
                    status.addSuccess("Asset pipeline integration initialized");
                    logger.info("Asset pipeline integration initialized");
                } catch (Exception e) {
                    status.addError("Failed to initialize asset pipeline: " + e.getMessage());
                    logger.error("Failed to initialize asset pipeline", e);
                }
                
                // Set up system interactions
                setupSystemInteractions();
                
                initialized.set(true);
                
                // Notify listeners
                notifyIntegrationListeners(IntegrationEventType.INITIALIZED, status);
                
                logger.info("Stonebreak integration initialization completed: {} successes, {} errors",
                    status.getSuccessCount(), status.getErrorCount());
                
                return status;
                
            } catch (Exception e) {
                logger.error("Failed to initialize Stonebreak integration", e);
                IntegrationStatus errorStatus = new IntegrationStatus();
                errorStatus.addError("Integration initialization failed: " + e.getMessage());
                return errorStatus;
            }
        });
    }
    
    /**
     * Starts all integration systems for active use.
     */
    public CompletableFuture<IntegrationStatus> startIntegration() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!initialized.get()) {
                    throw new IllegalStateException("Integration systems not initialized");
                }
                
                if (integrationActive.get()) {
                    logger.warn("Stonebreak integration already active");
                    return new IntegrationStatus(true, "Already active");
                }
                
                logger.info("Starting Stonebreak integration systems...");
                
                IntegrationStatus status = new IntegrationStatus();
                
                // Start live preview system
                if (livePreviewSystem != null) {
                    try {
                        livePreviewSystem.startLivePreview().get();
                        status.addSuccess("Live preview system started");
                        logger.info("Live preview system started");
                    } catch (Exception e) {
                        status.addError("Failed to start live preview system: " + e.getMessage());
                        logger.error("Failed to start live preview system", e);
                    }
                }
                
                // Validation system is always active (no explicit start needed)
                if (validationSystem != null) {
                    status.addSuccess("Texture validation system active");
                }
                
                // Asset pipeline is always available (no explicit start needed)
                if (assetPipeline != null) {
                    status.addSuccess("Asset pipeline integration active");
                }
                
                integrationActive.set(true);
                
                // Update viewport integration status (method not implemented yet)
                Platform.runLater(() -> {
                    // viewport.setStonebreakIntegrationActive(true);
                    logger.debug("Stonebreak integration started - viewport update pending");
                });
                
                // Notify listeners
                notifyIntegrationListeners(IntegrationEventType.STARTED, status);
                
                logger.info("Stonebreak integration started successfully");
                
                return status;
                
            } catch (Exception e) {
                logger.error("Failed to start Stonebreak integration", e);
                IntegrationStatus errorStatus = new IntegrationStatus();
                errorStatus.addError("Integration startup failed: " + e.getMessage());
                return errorStatus;
            }
        });
    }
    
    /**
     * Stops all integration systems.
     */
    public CompletableFuture<IntegrationStatus> stopIntegration() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!integrationActive.get()) {
                    logger.warn("Stonebreak integration not active");
                    return new IntegrationStatus(true, "Not active");
                }
                
                logger.info("Stopping Stonebreak integration systems...");
                
                IntegrationStatus status = new IntegrationStatus();
                
                // Stop live preview system
                if (livePreviewSystem != null) {
                    try {
                        livePreviewSystem.stopLivePreview().get();
                        status.addSuccess("Live preview system stopped");
                        logger.info("Live preview system stopped");
                    } catch (Exception e) {
                        status.addError("Failed to stop live preview system: " + e.getMessage());
                        logger.error("Failed to stop live preview system", e);
                    }
                }
                
                integrationActive.set(false);
                
                // Update viewport integration status (method not implemented yet)
                Platform.runLater(() -> {
                    // viewport.setStonebreakIntegrationActive(false);
                    logger.debug("Stonebreak integration stopped - viewport update pending");
                });
                
                // Notify listeners
                notifyIntegrationListeners(IntegrationEventType.STOPPED, status);
                
                logger.info("Stonebreak integration stopped");
                
                return status;
                
            } catch (Exception e) {
                logger.error("Failed to stop Stonebreak integration", e);
                IntegrationStatus errorStatus = new IntegrationStatus();
                errorStatus.addError("Integration shutdown failed: " + e.getMessage());
                return errorStatus;
            }
        });
    }
    
    /**
     * Sets up interactions between integration systems.
     */
    private void setupSystemInteractions() {
        // Connect validation system to live preview
        if (validationSystem != null && livePreviewSystem != null) {
            validationSystem.addValidationListener(result -> {
                logger.debug("Validation completed for {}: {}", result.getVariantName(), result.getSummary());
                // Could trigger viewport updates or other actions based on validation
            });
        }
        
        // Connect asset pipeline to validation
        if (assetPipeline != null && validationSystem != null) {
            assetPipeline.addPipelineListener((operation, eventType) -> {
                if (eventType == AssetPipelineIntegration.PipelineEventType.COMPLETED) {
                    logger.debug("Pipeline operation completed: {}", operation.getName());
                    // Could trigger validation refresh
                }
            });
        }
    }
    
    /**
     * Validates current texture definitions.
     */
    public CompletableFuture<AssetPipelineIntegration.ValidationSummary> validateCurrentAssets() {
        if (!initialized.get() || assetPipeline == null) {
            CompletableFuture<AssetPipelineIntegration.ValidationSummary> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Asset pipeline not available"));
            return future;
        }
        
        return assetPipeline.validateAllAssets();
    }
    
    /**
     * Exports current textures to the game directory.
     */
    public CompletableFuture<AssetPipelineIntegration.PipelineResult> exportTexturesToGame() {
        if (!initialized.get() || assetPipeline == null) {
            CompletableFuture<AssetPipelineIntegration.PipelineResult> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Asset pipeline not available"));
            return future;
        }
        
        List<String> variants = List.of("default_cow", "angus_cow", "highland_cow", "jersey_cow");
        return assetPipeline.exportTexturesToGame(variants);
    }
    
    /**
     * Synchronizes assets between OpenMason and game directories.
     */
    public CompletableFuture<AssetPipelineIntegration.PipelineResult> synchronizeAssets(boolean dryRun) {
        if (!initialized.get() || assetPipeline == null) {
            CompletableFuture<AssetPipelineIntegration.PipelineResult> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Asset pipeline not available"));
            return future;
        }
        
        return assetPipeline.synchronizeAssets(dryRun);
    }
    
    /**
     * Triggers manual reload of live preview.
     */
    public void triggerManualReload() {
        if (livePreviewSystem != null && livePreviewSystem.isActive()) {
            livePreviewSystem.triggerManualReload();
        } else {
            logger.warn("Live preview system not active, cannot trigger manual reload");
        }
    }
    
    /**
     * Notifies integration listeners of events.
     */
    private void notifyIntegrationListeners(IntegrationEventType eventType, IntegrationStatus status) {
        for (IntegrationListener listener : integrationListeners) {
            try {
                listener.onIntegrationEvent(eventType, status);
            } catch (Exception e) {
                logger.error("Error notifying integration listener", e);
            }
        }
    }
    
    // Getters and system access
    public LivePreviewSystem getLivePreviewSystem() { return livePreviewSystem; }
    public TextureValidationSystem getValidationSystem() { return validationSystem; }
    public AssetPipelineIntegration getAssetPipeline() { return assetPipeline; }
    
    public boolean isInitialized() { return initialized.get(); }
    public boolean isIntegrationActive() { return integrationActive.get(); }
    
    // Listener management
    public void addIntegrationListener(IntegrationListener listener) {
        integrationListeners.add(listener);
    }
    
    public void removeIntegrationListener(IntegrationListener listener) {
        integrationListeners.remove(listener);
    }
    
    // Configuration methods
    public void setAutoReloadEnabled(boolean enabled) {
        if (livePreviewSystem != null) {
            livePreviewSystem.setAutoReloadEnabled(enabled);
        }
    }
    
    public void setReloadDelayMs(int delayMs) {
        if (livePreviewSystem != null) {
            livePreviewSystem.setReloadDelayMs(delayMs);
        }
    }
    
    // Nested classes
    
    public static class IntegrationStatus {
        private final List<String> successes = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final boolean successful;
        private final String message;
        
        public IntegrationStatus() {
            this.successful = true;
            this.message = "";
        }
        
        public IntegrationStatus(boolean successful, String message) {
            this.successful = successful;
            this.message = message;
        }
        
        public void addSuccess(String message) { successes.add(message); }
        public void addError(String message) { errors.add(message); }
        public void addWarning(String message) { warnings.add(message); }
        
        public List<String> getSuccesses() { return new ArrayList<>(successes); }
        public List<String> getErrors() { return new ArrayList<>(errors); }
        public List<String> getWarnings() { return new ArrayList<>(warnings); }
        
        public int getSuccessCount() { return successes.size(); }
        public int getErrorCount() { return errors.size(); }
        public int getWarningCount() { return warnings.size(); }
        
        public boolean isSuccessful() { return successful && errors.isEmpty(); }
        public String getMessage() { return message; }
        
        public String getSummary() {
            if (!isSuccessful()) {
                return "❌ " + getErrorCount() + " errors" + (getWarningCount() > 0 ? ", " + getWarningCount() + " warnings" : "");
            } else if (getWarningCount() > 0) {
                return "⚠️ " + getSuccessCount() + " successes, " + getWarningCount() + " warnings";
            } else {
                return "✅ " + getSuccessCount() + " systems ready";
            }
        }
    }
    
    public interface IntegrationListener {
        void onIntegrationEvent(IntegrationEventType eventType, IntegrationStatus status);
    }
    
    public enum IntegrationEventType {
        INITIALIZED, STARTED, STOPPED, ERROR
    }
}