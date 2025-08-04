package com.openmason.ui;

import com.openmason.texture.TextureVariantManager;
import com.openmason.texture.TextureVariantManager.CachedVariantInfo;
import com.openmason.ui.viewport.OpenMason3DViewport;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.paint.Color;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Controller for the properties panel.
 * Handles texture variant selection, model transforms, animation controls, and statistics.
 * Provides real-time property updates with 3D viewport integration.
 */
public class PropertyPanelController implements Initializable {
    
    private static final Logger logger = LoggerFactory.getLogger(PropertyPanelController.class);
    
    // FXML Controls - injected via MainController integration
    @FXML private ComboBox<String> cmbTextureVariant;
    @FXML private Slider sliderRotationX;
    @FXML private Slider sliderRotationY;
    @FXML private Slider sliderRotationZ;
    @FXML private TextField txtRotationX;
    @FXML private TextField txtRotationY;
    @FXML private TextField txtRotationZ;
    @FXML private Slider sliderScale;
    @FXML private TextField txtScale;
    @FXML private ComboBox<String> cmbAnimation;
    @FXML private Button btnPlayAnimation;
    @FXML private Button btnPauseAnimation;
    @FXML private Button btnStopAnimation;
    @FXML private Slider sliderAnimationTime;
    @FXML private Label lblPartCount;
    @FXML private Label lblVertexCount;
    @FXML private Label lblTriangleCount;
    @FXML private Label lblTextureVariants;
    @FXML private Button btnValidateProperties;
    @FXML private Button btnResetProperties;
    
    // State Management
    private TextureVariantManager textureManager;
    private OpenMason3DViewport viewport3D;
    private String currentModelName = null;
    private boolean initialized = false;
    
    // UI Properties
    private final ObservableList<String> availableVariants = FXCollections.observableArrayList();
    private final StringProperty selectedVariant = new SimpleStringProperty();
    private final StringProperty statusMessage = new SimpleStringProperty("Ready");
    private final BooleanProperty loadingInProgress = new SimpleBooleanProperty(false);
    
    // Performance tracking
    private long lastSwitchTime = 0;
    private int switchCount = 0;
    
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        logger.info("Initializing PropertyPanelController...");
        
        try {
            // Initialize TextureVariantManager
            textureManager = TextureVariantManager.getInstance();
            
            // Set up UI bindings - these will be connected when MainController sets the controls
            setupUIBindings();
            
            // Initialize async to avoid blocking UI thread
            Platform.runLater(() -> {
                initializeAsync().whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        logger.error("Failed to initialize PropertyPanelController", throwable);
                        Platform.runLater(() -> statusMessage.set("Initialization failed: " + throwable.getMessage()));
                    } else {
                        logger.info("PropertyPanelController initialized successfully");
                        Platform.runLater(() -> statusMessage.set("Ready"));
                    }
                });
            });
            
        } catch (Exception e) {
            logger.error("Error during PropertyPanelController initialization", e);
            Platform.runLater(() -> statusMessage.set("Initialization error: " + e.getMessage()));
        }
    }
    
    /**
     * Load available texture variants for current model.
     * Phase 5 Implementation - Real texture variant loading with caching.
     */
    public void loadTextureVariants(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            logger.warn("Cannot load texture variants for null or empty model name");
            return;
        }
        
        logger.info("Loading texture variants for model: {}", modelName);
        this.currentModelName = modelName;
        
        Platform.runLater(() -> {
            loadingInProgress.set(true);
            statusMessage.set("Loading texture variants for " + modelName + "...");
        });
        
        // Load variants asynchronously using TextureVariantManager
        CompletableFuture.runAsync(() -> {
            try {
                // For cow models, load all 4 variants
                if (modelName.toLowerCase().contains("cow")) {
                    List<String> cowVariants = Arrays.asList("default", "angus", "highland", "jersey");
                    
                    // Use TextureVariantManager batch loading for optimal performance
                    textureManager.loadMultipleVariantsAsync(cowVariants, false, 
                        new TextureVariantManager.VariantCallback() {
                            @Override
                            public void onSuccess(CachedVariantInfo variantInfo) {
                                // Update UI when all variants are loaded
                                updateVariantComboBox(cowVariants);
                                updateModelStatistics();
                                Platform.runLater(() -> {
                                    loadingInProgress.set(false);
                                    statusMessage.set("Loaded " + cowVariants.size() + " texture variants");
                                });
                            }
                            
                            @Override
                            public void onError(String variantName, Throwable error) {
                                logger.error("Failed to load variant: {}", variantName, error);
                                Platform.runLater(() -> {
                                    loadingInProgress.set(false);
                                    statusMessage.set("Error loading variant: " + variantName);
                                });
                            }
                            
                            @Override
                            public void onProgress(String operation, int current, int total, String details) {
                                Platform.runLater(() -> {
                                    statusMessage.set(String.format("Loading variants... %d/%d", current, total));
                                });
                            }
                        }).whenComplete((result, throwable) -> {
                            if (throwable == null) {
                                logger.info("Successfully loaded {} texture variants for model: {}", 
                                    result.size(), modelName);
                            } else {
                                logger.error("Failed to load texture variants for model: {}", modelName, throwable);
                            }
                        });
                        
                } else {
                    // For non-cow models, just set default
                    Platform.runLater(() -> {
                        availableVariants.clear();
                        availableVariants.add("Default");
                        if (cmbTextureVariant != null) {
                            cmbTextureVariant.setValue("Default");
                        }
                        loadingInProgress.set(false);
                        statusMessage.set("Model type not supported for texture variants");
                    });
                }
                
            } catch (Exception e) {
                logger.error("Error loading texture variants for model: {}", modelName, e);
                Platform.runLater(() -> {
                    loadingInProgress.set(false);
                    statusMessage.set("Failed to load texture variants: " + e.getMessage());
                });
            }
        });
    }
    
    /**
     * Switch to a different texture variant.
     * Phase 5 Implementation - Real-time texture variant switching with performance monitoring.
     */
    public void switchTextureVariant(String variantName) {
        if (variantName == null || variantName.isEmpty()) {
            logger.warn("Cannot switch to null or empty texture variant");
            return;
        }
        
        long startTime = System.currentTimeMillis();
        switchCount++;
        
        logger.info("Switching to texture variant: {} (switch #{})", variantName, switchCount);
        
        Platform.runLater(() -> {
            loadingInProgress.set(true);
            statusMessage.set("Switching to " + variantName + " variant...");
        });
        
        // Use TextureVariantManager for optimized switching
        CompletableFuture.runAsync(() -> {
            try {
                String variantLower = variantName.toLowerCase();
                
                // Switch variant using TextureVariantManager (with caching for <200ms performance)
                boolean success = textureManager.switchToVariant(variantLower);
                
                if (success) {
                    // Update viewport if connected
                    if (viewport3D != null) {
                        Platform.runLater(() -> {
                            try {
                                viewport3D.setCurrentTextureVariant(variantLower);
                                viewport3D.requestRender();
                            } catch (Exception e) {
                                logger.error("Failed to update viewport with new texture variant", e);
                            }
                        });
                    }
                    
                    // Update UI
                    Platform.runLater(() -> {
                        selectedVariant.set(variantName);
                        if (cmbTextureVariant != null && !variantName.equals(cmbTextureVariant.getValue())) {
                            cmbTextureVariant.setValue(variantName);
                        }
                        
                        long switchTime = System.currentTimeMillis() - startTime;
                        lastSwitchTime = switchTime;
                        
                        loadingInProgress.set(false);
                        statusMessage.set(String.format("Switched to %s variant (%dms)", variantName, switchTime));
                        
                        // Update performance statistics
                        updatePerformanceStatistics();
                        
                        logger.info("Successfully switched to variant '{}' in {}ms", variantName, switchTime);
                        
                        if (switchTime > 200) {
                            logger.warn("Texture variant switch took {}ms (target: <200ms)", switchTime);
                        }
                    });
                    
                } else {
                    Platform.runLater(() -> {
                        loadingInProgress.set(false);
                        statusMessage.set("Failed to switch to variant: " + variantName);
                    });
                    logger.error("TextureVariantManager failed to switch to variant: {}", variantName);
                }
                
            } catch (Exception e) {
                logger.error("Error switching to texture variant: {}", variantName, e);
                Platform.runLater(() -> {
                    loadingInProgress.set(false);
                    statusMessage.set("Error switching variant: " + e.getMessage());
                });
            }
        });
    }
    
    /**
     * Update model transform properties.
     * TODO: Implement in Phase 6
     */
    public void updateModelTransform(float rotX, float rotY, float rotZ, float scale) {
        logger.debug("Updating model transform: rot=({}, {}, {}), scale={}", rotX, rotY, rotZ, scale);
        // Implementation will:
        // - Apply rotation to model transformation matrix
        // - Apply uniform scaling
        // - Update 3D viewport in real-time
        // - Maintain smooth interpolation for professional feel
    }
    
    /**
     * Set current animation state.
     * TODO: Implement in Phase 6
     */
    public void setAnimation(String animationName, float time) {
        logger.debug("Setting animation: name='{}', time={}", animationName, time);
        // Implementation will:
        // - Load animation from JSON model definition
        // - Apply animation transforms using ModelLoader.getAnimatedParts()
        // - Update 3D viewport with animated model parts
        // - Support IDLE, WALKING, GRAZING animations
    }
    
    /**
     * Calculate and display model statistics.
     * TODO: Implement in Phase 6
     */
    public void updateModelStatistics(String modelName) {
        logger.debug("Updating model statistics for: {}", modelName);
        // Implementation will:
        // - Count model parts from JSON definition
        // - Calculate total vertices (24 per part × part count)
        // - Calculate total triangles (12 per part × part count)
        // - Count available texture variants
        // - Display in statistics labels
    }
    
    /**
     * Validate current model and properties.
     * TODO: Implement in Phase 6
     */
    public void validateModel() {
        logger.info("Validating model properties...");
        // Implementation will:
        // - Check coordinate system accuracy using CoordinateSystemValidator
        // - Validate texture atlas coordinates within 0-15 bounds
        // - Verify all required face mappings exist
        // - Check vertex generation produces correct count
        // - Display validation results with user-friendly messages
    }
    
    /**
     * Reset all properties to default values.
     * TODO: Implement in Phase 6
     */
    public void resetProperties() {
        logger.info("Resetting properties to defaults...");
        // Implementation will:
        // - Reset rotation sliders to 0
        // - Reset scale slider to 1.0
        // - Set texture variant to "default"
        // - Stop any playing animations
        // - Update 3D viewport to reflect reset state
    }
    
    /**
     * Handle real-time slider updates for smooth interaction.
     * TODO: Implement in Phase 6
     */
    public void setupSliderBindings() {
        logger.debug("Setting up slider bindings...");
        // Implementation will:
        // - Bind rotation sliders to text fields for precise input
        // - Add value change listeners for real-time updates
        // - Implement smooth interpolation for professional feel
        // - Add input validation and bounds checking
    }
    
    /**
     * Start animation playback.
     * TODO: Implement in Phase 6
     */
    public void playAnimation() {
        logger.info("Starting animation playback...");
        // Implementation will:
        // - Start animation timeline with proper timing
        // - Update animation time slider continuously
        // - Apply frame interpolation for smooth animation
        // - Enable pause/stop controls
    }
    
    /**
     * Pause animation playback.
     * TODO: Implement in Phase 6
     */
    public void pauseAnimation() {
        logger.info("Pausing animation playback...");
        // Implementation will pause timeline at current frame
    }
    
    /**
     * Stop animation playback and reset to frame 0.
     * TODO: Implement in Phase 6
     */
    public void stopAnimation() {
        logger.info("Stopping animation playback...");
        // Implementation will:
        // - Stop animation timeline
        // - Reset to first frame
        // - Reset animation time slider to 0
        // - Apply IDLE animation state
    }
    
    /**
     * Set animation time manually via slider.
     * TODO: Implement in Phase 6
     */
    public void setAnimationTime(float time) {
        logger.debug("Setting animation time: {}", time);
        // Implementation will:
        // - Pause automatic playback if active
        // - Apply animation at specific time
        // - Update 3D viewport with interpolated pose
    }
    
    // Phase 5 Helper Methods - UI Integration Support
    
    /**
     * Initialize async operations for PropertyPanelController.
     */
    private CompletableFuture<Void> initializeAsync() {
        return CompletableFuture.runAsync(() -> {
            try {
                // Initialize TextureVariantManager if not already done
                if (textureManager != null && !textureManager.getPerformanceStats().get("initialized").equals(true)) {
                    textureManager.initializeAsync(null).get();
                }
                
                initialized = true;
                logger.info("PropertyPanelController async initialization complete");
                
            } catch (Exception e) {
                logger.error("Failed to initialize PropertyPanelController async", e);
                throw new RuntimeException("Async initialization failed", e);
            }
        });
    }
    
    /**
     * Set up UI property bindings and listeners.
     */
    private void setupUIBindings() {
        // Bind properties for reactive UI updates
        selectedVariant.addListener((obs, oldValue, newValue) -> {
            if (newValue != null && !newValue.equals(oldValue)) {
                logger.debug("Selected variant changed from '{}' to '{}'", oldValue, newValue);
            }
        });
        
        loadingInProgress.addListener((obs, oldValue, newValue) -> {
            logger.debug("Loading progress changed: {}", newValue);
        });
        
        statusMessage.addListener((obs, oldValue, newValue) -> {
            logger.debug("Status message changed: {}", newValue);
        });
    }
    
    /**
     * Update the texture variant ComboBox with available variants.
     */
    private void updateVariantComboBox(List<String> variants) {
        Platform.runLater(() -> {
            availableVariants.clear();
            
            // Capitalize first letter for display
            List<String> displayVariants = variants.stream()
                .map(this::capitalizeFirst)
                .collect(java.util.stream.Collectors.toList());
                
            availableVariants.addAll(displayVariants);
            
            if (cmbTextureVariant != null) {
                cmbTextureVariant.setItems(availableVariants);
                if (!displayVariants.isEmpty()) {
                    cmbTextureVariant.setValue(displayVariants.get(0));
                }
            }
            
            logger.debug("Updated variant ComboBox with {} variants: {}", variants.size(), displayVariants);
        });
    }
    
    /**
     * Update model statistics labels with current model information.
     */
    private void updateModelStatistics() {
        if (currentModelName == null) {
            return;
        }
        
        Platform.runLater(() -> {
            try {
                // For cow models, we know the statistics based on JSON definition
                if (currentModelName.toLowerCase().contains("cow")) {
                    // Standard cow model has 14 parts (head, body, 4 legs, tail, udder, ears)
                    int partCount = 14;
                    int vertexCount = partCount * 24; // 24 vertices per cubic part
                    int triangleCount = partCount * 12; // 12 triangles per cubic part
                    int variantCount = availableVariants.size();
                    
                    if (lblPartCount != null) lblPartCount.setText("Parts: " + partCount);
                    if (lblVertexCount != null) lblVertexCount.setText("Vertices: " + vertexCount);
                    if (lblTriangleCount != null) lblTriangleCount.setText("Triangles: " + triangleCount);
                    if (lblTextureVariants != null) lblTextureVariants.setText("Texture Variants: " + variantCount);
                    
                    logger.debug("Updated model statistics: parts={}, vertices={}, triangles={}, variants={}", 
                        partCount, vertexCount, triangleCount, variantCount);
                } else {
                    // Unknown model type
                    if (lblPartCount != null) lblPartCount.setText("Parts: Unknown");
                    if (lblVertexCount != null) lblVertexCount.setText("Vertices: Unknown");
                    if (lblTriangleCount != null) lblTriangleCount.setText("Triangles: Unknown");
                    if (lblTextureVariants != null) lblTextureVariants.setText("Texture Variants: " + availableVariants.size());
                }
                
            } catch (Exception e) {
                logger.error("Error updating model statistics", e);
            }
        });
    }
    
    /**
     * Update performance statistics and display them.
     */
    private void updatePerformanceStatistics() {
        if (textureManager == null) {
            return;
        }
        
        try {
            Map<String, Object> perfStats = textureManager.getPerformanceStats();
            
            logger.debug("Performance Stats: Last switch={}ms, Total switches={}, Cache hits={}, Hit rate={}", 
                lastSwitchTime, switchCount, perfStats.get("cacheHits"), perfStats.get("hitRate"));
                
            // Could display in status message or dedicated performance panel
            if (lastSwitchTime > 0) {
                Platform.runLater(() -> {
                    String perfMessage = String.format("Performance: %dms (target: <200ms), Cache: %s hit rate", 
                        lastSwitchTime, perfStats.get("hitRate"));
                    // This could be displayed in a performance label if we add one to the UI
                    logger.debug("Performance update: {}", perfMessage);
                });
            }
            
        } catch (Exception e) {
            logger.error("Error updating performance statistics", e);
        }
    }
    
    /**
     * Capitalize the first letter of a string for display.
     */
    private String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }
    
    // Public API Methods for MainController Integration
    
    /**
     * Set the 3D viewport reference for integration.
     */
    public void setViewport3D(OpenMason3DViewport viewport) {
        this.viewport3D = viewport;
        logger.info("3D viewport reference set for PropertyPanelController");
    }
    
    /**
     * Set the FXML control references from MainController.
     * This allows the PropertyPanelController to work with controls defined in the FXML.
     */
    public void setControlReferences(ComboBox<String> cmbTextureVariant, 
                                   Label lblPartCount, Label lblVertexCount, 
                                   Label lblTriangleCount, Label lblTextureVariants) {
        this.cmbTextureVariant = cmbTextureVariant;
        this.lblPartCount = lblPartCount;
        this.lblVertexCount = lblVertexCount;
        this.lblTriangleCount = lblTriangleCount;
        this.lblTextureVariants = lblTextureVariants;
        
        // Set up ComboBox listener for texture variant changes
        if (this.cmbTextureVariant != null) {
            this.cmbTextureVariant.setOnAction(event -> {
                String selectedVariant = this.cmbTextureVariant.getValue();
                if (selectedVariant != null && !selectedVariant.equals(this.selectedVariant.get())) {
                    switchTextureVariant(selectedVariant);
                }
            });
        }
        
        logger.info("Control references set for PropertyPanelController");
    }
    
    /**
     * Get current status message for UI binding.
     */
    public StringProperty statusMessageProperty() {
        return statusMessage;
    }
    
    /**
     * Get loading progress property for UI binding.
     */
    public BooleanProperty loadingInProgressProperty() {
        return loadingInProgress;
    }
    
    /**
     * Get selected variant property for UI binding.
     */
    public StringProperty selectedVariantProperty() {
        return selectedVariant;
    }
    
    /**
     * Check if PropertyPanelController is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Get performance metrics for monitoring.
     */
    public Map<String, Object> getPerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("switchCount", switchCount);
        metrics.put("lastSwitchTime", lastSwitchTime);
        metrics.put("initialized", initialized);
        metrics.put("currentModel", currentModelName);
        metrics.put("availableVariants", availableVariants.size());
        
        if (textureManager != null) {
            metrics.putAll(textureManager.getPerformanceStats());
        }
        
        return metrics;
    }
}