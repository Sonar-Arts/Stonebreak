package com.openmason.ui.viewport;

import com.openmason.rendering.BufferManager;
import com.openmason.rendering.ModelRenderer;
import com.openmason.rendering.OpenGLValidator;
import com.openmason.rendering.PerformanceOptimizer;
import com.openmason.AsyncResourceManager;
import com.openmason.model.StonebreakModel;
import com.openmason.model.ModelManager;
import com.openmason.texture.TextureManager;
import com.openmason.camera.ArcBallCamera;
import com.stonebreak.textures.CowTextureDefinition;
import com.stonebreak.model.ModelDefinition;
import com.openmason.coordinates.ModelCoordinateSystem;
import com.openmason.coordinates.CoordinateSystemIntegration;

// JavaFX 3D Subsystem-based rendering for accurate 3D model visualization

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.layout.StackPane;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Professional 3D viewport using JavaFX 3D Subsystem for accurate model visualization.
 * 
 * This refactored class delegates responsibilities to specialized components:
 * - ViewportSceneManager: JavaFX 3D scene setup and management
 * - ViewportInputHandler: Mouse and keyboard input processing
 * - ViewportModelRenderer: 3D model rendering logic
 * - ViewportDebugRenderer: Debug visualization (axes, grid, labels)
 * - ViewportCameraController: Camera operations and transformations
 * - ViewportPerformanceMonitor: FPS and performance tracking
 * - ViewportPropertyManager: JavaFX property management
 * 
 * Key Features:
 * - Hardware-accelerated JavaFX 3D rendering with SubScene and Group
 * - Proper 3D coordinate transformations using JavaFX Transform system
 * - Accurate model part positioning with Translate, Rotate, and Scale transforms
 * - Material-based color and texture application using PhongMaterial
 * - Professional PerspectiveCamera with depth-accurate 3D visualization
 * - Real-time texture variant switching with material updates
 * - Wireframe and solid rendering modes
 * - Performance monitoring and statistics collection
 * 
 * Architecture:
 * - Extends StackPane containing a SubScene for 3D content
 * - Uses specialized component managers for different responsibilities
 * - Maintains compatibility with existing Phase 2 systems
 * - Direct compatibility with Stonebreak's coordinate system via proper matrix application
 */
public class OpenMason3DViewport extends StackPane {
    
    private static final Logger logger = LoggerFactory.getLogger(OpenMason3DViewport.class);
    
    // Integration with existing Phase 2 systems
    private BufferManager bufferManager;
    private ModelRenderer modelRenderer;
    private ModelManager modelManager; // Unused - kept for compatibility
    private TextureManager textureManager; // Unused - kept for compatibility
    
    // Component managers - the core of the refactored architecture
    private ViewportSceneManager sceneManager;
    private ViewportInputHandler inputHandler;
    private ViewportModelRenderer modelRendererComponent;
    private ViewportDebugRenderer debugRenderer;
    private ViewportCameraController cameraController;
    private ViewportPerformanceMonitor performanceMonitor;
    private ViewportPropertyManager propertyManager;
    
    // Current model state
    private volatile StonebreakModel currentModel;
    
    // Rendering state management
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean renderingEnabled = new AtomicBoolean(false);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    
    // Performance monitoring and optimization
    private PerformanceOptimizer performanceOptimizer;
    
    // Error handling
    private volatile Throwable lastError;
    private final AtomicLong errorCount = new AtomicLong(0);
    
    /**
     * Creates a new OpenMason 3D viewport with component-based architecture.
     */
    public OpenMason3DViewport() {
        super();
        logger.info("Creating OpenMason 3D Viewport with component-based architecture");
        
        try {
            initializeComponents();
            setupComponentIntegration();
            initializeAsync();
            
        } catch (Exception e) {
            logger.error("Failed to initialize OpenMason3DViewport", e);
            handleInitializationError(e);
        }
    }
    
    /**
     * Initialize all component managers.
     */
    private void initializeComponents() {
        logger.debug("Initializing viewport components...");
        
        // Initialize components in dependency order
        sceneManager = new ViewportSceneManager();
        propertyManager = new ViewportPropertyManager();
        performanceMonitor = new ViewportPerformanceMonitor();
        cameraController = new ViewportCameraController();
        inputHandler = new ViewportInputHandler();
        modelRendererComponent = new ViewportModelRenderer();
        debugRenderer = new ViewportDebugRenderer();
        
        logger.debug("All viewport components created");
    }
    
    /**
     * Set up integration between components.
     */
    private void setupComponentIntegration() {
        logger.debug("Setting up component integration...");
        
        // Initialize scene manager with this viewport as parent
        sceneManager.initializeScene(this);
        
        // Initialize property manager
        propertyManager.initialize();
        
        // Initialize performance monitor
        initializePerformanceMonitoring();
        
        // Initialize camera controller
        cameraController.initialize(sceneManager);
        
        // Initialize input handler
        inputHandler.initialize(cameraController.getCamera());
        inputHandler.setupEventHandlers(sceneManager.getSubScene3D());
        
        // Initialize model renderer
        modelRendererComponent.initialize(sceneManager);
        
        // Initialize debug renderer
        debugRenderer.initialize(sceneManager);
        
        // Set up component callbacks and listeners
        setupComponentCallbacks();
        
        logger.debug("Component integration completed");
    }
    
    /**
     * Set up callbacks between components.
     */
    private void setupComponentCallbacks() {
        // Input handler callbacks
        inputHandler.setRenderRequestCallback(ignored -> requestRender());
        inputHandler.setFitCameraToModelCallback(() -> fitCameraToModel());
        inputHandler.setResetCameraCallback(() -> resetCamera());
        inputHandler.setFrameOriginCallback(() -> frameOrigin());
        
        // Camera controller callbacks
        cameraController.setRenderRequestCallback(ignored -> requestRender());
        
        // Property manager callbacks for viewport updates
        propertyManager.registerPropertyCallback("wireframeMode", value -> {
            modelRendererComponent.setWireframeMode((Boolean) value);
            requestRender();
        });
        
        propertyManager.registerPropertyCallback("gridVisible", value -> {
            debugRenderer.setGridVisible((Boolean) value);
            requestRender();
        });
        
        propertyManager.registerPropertyCallback("axesVisible", value -> {
            debugRenderer.setAxesVisible((Boolean) value);
            requestRender();
        });
        
        propertyManager.registerPropertyCallback("debugMode", value -> {
            debugRenderer.setDebugMode((Boolean) value);
            requestRender();
        });
        
        propertyManager.registerPropertyCallback("coordinateAxesVisible", value -> {
            debugRenderer.setCoordinateAxesVisible((Boolean) value);
            requestRender();
        });
        
        propertyManager.registerPropertyCallback("partLabelsVisible", value -> {
            debugRenderer.setPartLabelsVisible((Boolean) value);
            requestRender();
        });
        
        propertyManager.registerPropertyCallback("currentTextureVariant", value -> {
            modelRendererComponent.setTextureVariant((String) value);
            if (currentModel != null) {
                modelRendererComponent.renderModel(currentModel);
            }
            requestRender();
        });
        
        propertyManager.registerPropertyCallback("cameraControlsEnabled", value -> {
            inputHandler.setCameraControlsEnabled((Boolean) value);
        });
        
        logger.debug("Component callbacks configured");
    }
    
    /**
     * Initialize performance monitoring system.
     */
    private void initializePerformanceMonitoring() {
        try {
            performanceOptimizer = new PerformanceOptimizer();
            performanceMonitor.initialize(performanceOptimizer);
            performanceMonitor.setRenderingEnabled(true);
            
            logger.debug("Performance monitoring initialized");
            
        } catch (Exception e) {
            logger.warn("Failed to initialize performance monitoring", e);
        }
    }
    
    /**
     * Asynchronous initialization of Phase 2 integration.
     */
    private void initializeAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100); // Brief delay for JavaFX thread initialization
                
                Platform.runLater(() -> {
                    try {
                        initializePhase2Integration();
                        
                        // Update initial debug visualization
                        debugRenderer.updateDebugVisualization();
                        
                        initialized.set(true);
                        renderingEnabled.set(true);
                        
                        // Initial render
                        requestRender();
                        
                        logger.info("OpenMason3DViewport initialization completed successfully");
                        
                    } catch (Exception e) {
                        handleInitializationError(e);
                    }
                });
                
            } catch (Exception e) {
                handleInitializationError(e);
            }
        }).exceptionally(throwable -> {
            handleInitializationError(throwable);
            return null;
        });
    }
    
    /**
     * Initialize integration with Phase 2 systems.
     */
    private void initializePhase2Integration() {
        try {
            // Initialize buffer manager if needed
            if (bufferManager == null) {
                bufferManager = BufferManager.getInstance();
            }
            
            // Initialize model renderer if needed
            if (modelRenderer == null) {
                modelRenderer = new ModelRenderer("OpenMason3DViewport");
            }
            
            logger.debug("Phase 2 integration initialized");
            
        } catch (Exception e) {
            logger.error("Failed to initialize Phase 2 integration", e);
            throw new RuntimeException("Phase 2 integration failed", e);
        }
    }
    
    /**
     * Request a render update.
     */
    public void requestRender() {
        if (!renderingEnabled.get() || disposed.get()) {
            return;
        }
        
        Platform.runLater(() -> {
            try {
                render3DContent();
                performanceMonitor.updateFrameTiming();
                
            } catch (Exception e) {
                handleRenderingError(e);
            }
        });
    }
    
    /**
     * Render 3D content using component managers.
     */
    private void render3DContent() {
        if (disposed.get()) return;
        
        try {
            // Update camera system
            cameraController.updateViewMatrix();
            
            // Update debug visualization
            debugRenderer.updateDebugVisualization();
            
            // Render model debug info if needed
            if (currentModel != null && debugRenderer.isDebugMode()) {
                debugRenderer.renderModelDebugInfo(currentModel);
            }
            
        } catch (Exception e) {
            logger.warn("Failed to render 3D content", e);
            performanceMonitor.recordError(e);
        }
    }
    
    /**
     * Set the current model to display.
     */
    public void setCurrentModel(StonebreakModel model) {
        this.currentModel = model;
        
        if (model != null) {
            propertyManager.setCurrentModelName(model.getVariantName());
            modelRendererComponent.renderModel(model);
            
            logger.info("Current model set to: {}", model.getVariantName());
        } else {
            propertyManager.setCurrentModelName("");
            modelRendererComponent.clearModel();
            
            logger.info("Current model cleared");
        }
        
        requestRender();
    }
    
    /**
     * Load a model by name.
     */
    public void loadModel(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            logger.info("Loading model: (clearing current model)");
            setCurrentModel(null);
            return;
        }
        
        logger.info("Loading model: {}", modelName);
        
        CompletableFuture.supplyAsync(() -> {
            try {
                // This would typically involve loading from model manager or resources
                // For now, create a placeholder or use existing model loading logic
                StonebreakModel model = loadModelFromName(modelName);
                return model;
                
            } catch (Exception e) {
                logger.error("Failed to load model: " + modelName, e);
                return null;
            }
        }).thenAccept(model -> {
            Platform.runLater(() -> {
                if (model != null) {
                    setCurrentModel(model);
                    fitCameraToModel();
                } else {
                    logger.warn("Failed to load model: {}", modelName);
                }
            });
        });
    }
    
    /**
     * Load model from name (placeholder implementation).
     */
    private StonebreakModel loadModelFromName(String modelName) {
        // This is a placeholder - in actual implementation, this would
        // integrate with the existing model loading system
        logger.debug("Loading model from name: {}", modelName);
        
        // For now, return null to indicate loading failed
        // Real implementation would load from resources or model manager
        return null;
    }
    
    /**
     * Fit camera to current model.
     */
    public void fitCameraToModel() {
        cameraController.fitCameraToModel(currentModel);
    }
    
    /**
     * Reset camera to default position.
     */
    public void resetCamera() {
        cameraController.resetCamera();
    }
    
    /**
     * Frame the origin.
     */
    public void frameOrigin() {
        cameraController.frameOrigin();
    }
    
    /**
     * Force refresh of the grid system - useful for testing grid changes.
     */
    public void refreshGrid() {
        if (debugRenderer != null) {
            debugRenderer.forceGridRefresh();
            requestRender();
        }
    }
    
    /**
     * Apply camera preset.
     */
    public void applyCameraPreset(ArcBallCamera.CameraPreset preset) {
        cameraController.applyCameraPreset(preset);
    }
    
    /**
     * Handle initialization errors.
     */
    private void handleInitializationError(Throwable error) {
        logger.error("OpenMason3DViewport initialization error", error);
        lastError = error;
        errorCount.incrementAndGet();
        performanceMonitor.recordError(error);
    }
    
    /**
     * Handle rendering errors.
     */
    private void handleRenderingError(Throwable error) {
        logger.warn("Rendering error in OpenMason3DViewport", error);
        lastError = error;
        errorCount.incrementAndGet();
        performanceMonitor.recordError(error);
    }
    
    /**
     * Dispose of viewport resources.
     */
    public void dispose() {
        if (disposed.getAndSet(true)) {
            return; // Already disposed
        }
        
        logger.info("Disposing OpenMason3DViewport resources");
        
        renderingEnabled.set(false);
        
        // Dispose components in reverse order
        if (debugRenderer != null) {
            // Debug renderer doesn't have dispose method, but we can clear its state
        }
        
        if (modelRendererComponent != null) {
            modelRendererComponent.clearModel();
        }
        
        if (sceneManager != null) {
            sceneManager.dispose();
        }
        
        if (performanceMonitor != null) {
            performanceMonitor.dispose();
        }
        
        // Clear references
        currentModel = null;
        lastError = null;
        
        logger.info("OpenMason3DViewport disposed successfully");
    }
    
    // ========== Public API Methods (Property Access) ==========
    
    // Model and Texture Properties
    public StringProperty currentModelNameProperty() { return propertyManager.currentModelNameProperty(); }
    public String getCurrentModelName() { return propertyManager.getCurrentModelName(); }
    public void setCurrentModelName(String modelName) { 
        propertyManager.setCurrentModelName(modelName); 
        loadModel(modelName);
    }
    
    public StringProperty currentTextureVariantProperty() { return propertyManager.currentTextureVariantProperty(); }
    public String getCurrentTextureVariant() { return propertyManager.getCurrentTextureVariant(); }
    public void setCurrentTextureVariant(String variant) { propertyManager.setCurrentTextureVariant(variant); }
    
    // Visualization Properties
    public BooleanProperty wireframeModeProperty() { return propertyManager.wireframeModeProperty(); }
    public boolean isWireframeMode() { return propertyManager.isWireframeMode(); }
    public void setWireframeMode(boolean enabled) { propertyManager.setWireframeMode(enabled); }
    
    public BooleanProperty gridVisibleProperty() { return propertyManager.gridVisibleProperty(); }
    public boolean isGridVisible() { return propertyManager.isGridVisible(); }
    public void setGridVisible(boolean visible) { propertyManager.setGridVisible(visible); }
    
    public BooleanProperty axesVisibleProperty() { return propertyManager.axesVisibleProperty(); }
    public boolean isAxesVisible() { return propertyManager.isAxesVisible(); }
    public void setAxesVisible(boolean visible) { propertyManager.setAxesVisible(visible); }
    
    // Debug Properties
    public BooleanProperty debugModeProperty() { return propertyManager.debugModeProperty(); }
    public boolean isDebugMode() { return propertyManager.isDebugMode(); }
    public void setDebugMode(boolean enabled) { propertyManager.setDebugMode(enabled); }
    
    public BooleanProperty coordinateAxesVisibleProperty() { return propertyManager.coordinateAxesVisibleProperty(); }
    public boolean isCoordinateAxesVisible() { return propertyManager.isCoordinateAxesVisible(); }
    public void setCoordinateAxesVisible(boolean visible) { propertyManager.setCoordinateAxesVisible(visible); }
    
    public BooleanProperty partLabelsVisibleProperty() { return propertyManager.partLabelsVisibleProperty(); }
    public boolean isPartLabelsVisible() { return propertyManager.isPartLabelsVisible(); }
    public void setPartLabelsVisible(boolean visible) { propertyManager.setPartLabelsVisible(visible); }
    
    // Performance Properties
    public BooleanProperty performanceOverlayEnabledProperty() { return propertyManager.performanceOverlayEnabledProperty(); }
    public boolean isPerformanceOverlayEnabled() { return propertyManager.isPerformanceOverlayEnabled(); }
    public void setPerformanceOverlayEnabled(boolean enabled) { propertyManager.setPerformanceOverlayEnabled(enabled); }
    
    public BooleanProperty adaptiveQualityEnabledProperty() { return propertyManager.adaptiveQualityEnabledProperty(); }
    public boolean isAdaptiveQualityEnabled() { return propertyManager.isAdaptiveQualityEnabled(); }
    public void setAdaptiveQualityEnabled(boolean enabled) { propertyManager.setAdaptiveQualityEnabled(enabled); }
    
    // Camera Properties
    public BooleanProperty cameraControlsEnabledProperty() { return propertyManager.cameraControlsEnabledProperty(); }
    public boolean areCameraControlsEnabled() { return propertyManager.areCameraControlsEnabled(); }
    public void setCameraControlsEnabled(boolean enabled) { propertyManager.setCameraControlsEnabled(enabled); }
    
    // ========== Getters for Component Access ==========
    
    public ArcBallCamera getCamera() { 
        return cameraController != null ? cameraController.getCamera() : null; 
    }
    
    public StonebreakModel getCurrentModel() { 
        return currentModel; 
    }
    
    public ViewportPerformanceMonitor.RenderingStatistics getStatistics() {
        return performanceMonitor != null ? performanceMonitor.getStatistics() : null;
    }
    
    public PerformanceOptimizer.PerformanceStatistics getPerformanceStatistics() {
        return performanceMonitor != null ? performanceMonitor.getPerformanceStatistics() : null;
    }
    
    public PerformanceOptimizer.PerformanceSummary getPerformanceSummary() {
        return performanceMonitor != null ? performanceMonitor.getPerformanceSummary() : null;
    }
    
    public PerformanceOptimizer getPerformanceOptimizer() {
        return performanceMonitor != null ? performanceMonitor.getPerformanceOptimizer() : null;
    }
    
    // ========== State Properties ==========
    
    public boolean isInitialized() { return initialized.get(); }
    public boolean isRenderingEnabled() { return renderingEnabled.get(); }
    public boolean isDisposed() { return disposed.get(); }
    public Throwable getLastError() { return lastError; }
    public long getErrorCount() { return errorCount.get(); }
    
    // ========== Legacy Methods for Compatibility ==========
    
    /**
     * Set model transform (placeholder for compatibility).
     */
    public void setModelTransform(float rotX, float rotY, float rotZ, float scale) {
        logger.debug("setModelTransform called: rot=({},{},{}), scale={}", rotX, rotY, rotZ, scale);
        // This could be implemented by updating the camera or model renderer
        requestRender();
    }
    
    /**
     * Focus on model (alias for fitCameraToModel).
     */
    public void focusOnModel() {
        fitCameraToModel();
    }
    
    /**
     * Check if model is loaded.
     */
    public boolean hasModelLoaded() {
        return currentModel != null;
    }
    
    /**
     * Reset viewport (reset camera and clear model).
     */
    public void resetViewport() {
        resetCamera();
        setCurrentModel(null);
    }
    
    /**
     * Get viewport metrics.
     */
    public Map<String, Object> getViewportMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        if (sceneManager != null) {
            metrics.put("sceneWidth", sceneManager.getSceneWidth());
            metrics.put("sceneHeight", sceneManager.getSceneHeight());
        }
        
        if (performanceMonitor != null) {
            metrics.put("fps", performanceMonitor.getCurrentFPS());
            metrics.put("frameCount", performanceMonitor.getFrameCount());
            metrics.put("errorCount", performanceMonitor.getErrorCount());
        }
        
        if (cameraController != null) {
            metrics.put("cameraDistance", cameraController.getCamera().getDistance());
        }
        
        metrics.put("initialized", initialized.get());
        metrics.put("renderingEnabled", renderingEnabled.get());
        metrics.put("disposed", disposed.get());
        
        return metrics;
    }
    
    // ========== Performance and Quality Settings ==========
    
    public void setMSAALevel(int level) {
        logger.debug("MSAA level set to: {} (not implemented in JavaFX 3D)", level);
    }
    
    public void setRenderScale(float scale) {
        logger.debug("Render scale set to: {} (not implemented in JavaFX 3D)", scale);
    }
    
    public int getCurrentMSAALevel() {
        return 4; // JavaFX SubScene uses BALANCED antialiasing
    }
    
    public float getCurrentRenderScale() {
        return 1.0f; // JavaFX renders at native resolution
    }
    
    public void setPerformanceDebugMode(boolean debug) {
        if (debugRenderer != null) {
            debugRenderer.setDebugMode(debug);
        }
    }
}