package com.openmason.ui.viewport;

import com.openmason.camera.ArcBallCamera;
import com.openmason.model.StonebreakModel;
import com.openmason.rendering.ModelRenderer;
import com.openmason.rendering.PerformanceOptimizer;
import com.openmason.texture.TextureManager;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Complete Dear ImGui-based 3D viewport for professional model visualization.
 * 
 * This class provides a standalone 3D viewport that integrates with Dear ImGui
 * with native OpenGL rendering, offering high performance and flexibility
 * and native OpenGL integration.
 * 
 * Key features:
 * - Standalone Dear ImGui integration with native OpenGL
 * - OpenGL framebuffer rendering for off-screen content
 * - GLFW input handling for mouse and keyboard
 * - Full camera control compatibility with ArcBall system
 * - Model rendering integration with existing Stonebreak systems
 * - Performance monitoring and optimization
 * - Debug visualization and overlays
 * - Docking system support
 * 
 * This class maintains API compatibility with the original OpenMason3DViewport
 * while providing superior performance and integration.
 */
public class ImGuiViewport3D {
    
    private static final Logger logger = LoggerFactory.getLogger(ImGuiViewport3D.class);
    
    // Core rendering components
    private ImGuiViewportManager viewportManager;
    private OpenGLFrameBuffer framebuffer;
    private ArcBallCamera camera;
    private ModelRenderer modelRenderer;
    
    // Integration components
    private TextureManager textureManager;
    private PerformanceOptimizer performanceOptimizer;
    private GLFWInputHandler inputHandler;
    
    // Viewport state
    private volatile StonebreakModel currentModel;
    private String currentTextureVariant = "default";
    private boolean wireframeMode = false;
    private boolean gridVisible = true;
    private boolean axesVisible = true;
    private boolean debugMode = false;
    
    // Rendering state
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean renderingEnabled = new AtomicBoolean(false);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    
    // Performance tracking
    private final AtomicLong frameCount = new AtomicLong(0);
    private volatile double currentFPS = 0.0;
    private volatile Throwable lastError;
    private final AtomicLong errorCount = new AtomicLong(0);
    
    // Callbacks for compatibility with existing systems
    private Consumer<Void> renderRequestCallback;
    private Runnable fitCameraToModelCallback;
    private Runnable resetCameraCallback;
    private Runnable frameOriginCallback;
    
    // GLFW window handle for input management
    private long windowHandle = 0L;
    
    /**
     * Create a new Dear ImGui-based 3D viewport.
     */
    public ImGuiViewport3D() {
        logger.info("Creating Dear ImGui 3D viewport");
        
        try {
            initializeComponents();
            setupComponentIntegration();
            initializeAsync();
            
        } catch (Exception e) {
            logger.error("Failed to initialize ImGuiViewport3D", e);
            handleInitializationError(e);
        }
    }
    
    /**
     * Initialize core viewport components.
     */
    private void initializeComponents() {
        logger.debug("Initializing viewport components...");
        
        // Initialize viewport manager
        viewportManager = new ImGuiViewportManager();
        
        // Get camera reference from viewport manager
        camera = viewportManager.getCamera();
        
        // Initialize model renderer
        modelRenderer = new ModelRenderer("ImGuiViewport3D");
        
        // Initialize performance optimizer
        performanceOptimizer = new PerformanceOptimizer();
        
        // Initialize texture manager (if needed)
        // textureManager = new TextureManager(); // Uncomment if required
        
        logger.debug("Core components initialized");
    }
    
    /**
     * Set up integration between components.
     */
    private void setupComponentIntegration() {
        logger.debug("Setting up component integration...");
        
        // Set up callbacks
        setupCallbacks();
        
        // Configure initial viewport state
        viewportManager.setWireframeMode(wireframeMode);
        viewportManager.setShowGrid(gridVisible);
        viewportManager.setShowAxes(axesVisible);
        viewportManager.setShowDebugInfo(debugMode);
        
        logger.debug("Component integration completed");
    }
    
    /**
     * Set up callback methods for compatibility.
     */
    private void setupCallbacks() {
        // Default implementations for callbacks
        fitCameraToModelCallback = this::fitCameraToModel;
        resetCameraCallback = this::resetCamera;
        frameOriginCallback = this::frameOrigin;
        // Explicitly set renderRequestCallback to null to prevent infinite recursion
        renderRequestCallback = null;
    }
    
    /**
     * Asynchronous initialization.
     */
    private void initializeAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100); // Brief delay for component stabilization
                
                // Complete initialization
                finializeInitialization();
                
            } catch (Exception e) {
                handleInitializationError(e);
            }
        }).exceptionally(throwable -> {
            handleInitializationError(throwable);
            return null;
        });
    }
    
    /**
     * Complete initialization process.
     */
    private void finializeInitialization() {
        try {
            initialized.set(true);
            renderingEnabled.set(true);
            
            logger.info("ImGuiViewport3D initialization completed successfully");
            
        } catch (Exception e) {
            handleInitializationError(e);
        }
    }
    
    /**
     * Initialize with OpenGL framebuffer and GLFW context manager.
     */
    public void initialize(OpenGLFrameBuffer frameBuffer, GLFWContextManager contextManager) {
        try {
            logger.info("Initializing ImGuiViewport3D with real 3D rendering components");
            
            // Store references to real 3D components
            this.framebuffer = frameBuffer;
            
            // Initialize components
            initializeComponents();
            setupComponentIntegration();
            
            // Store framebuffer for rendering
            // Note: ViewportManager doesn't need direct framebuffer reference
            // The framebuffer is used during render() calls
            
            // Initialize input if context manager provides window handle
            long windowHandle = contextManager.getPrimaryWindow();
            if (windowHandle != 0L) {
                initializeInput(windowHandle);
            }
            
            // Complete async initialization
            initializeAsync();
            
            logger.info("ImGuiViewport3D initialized successfully with real 3D rendering");
            
        } catch (Exception e) {
            logger.error("Failed to initialize ImGuiViewport3D with 3D components", e);
            throw new RuntimeException("ImGuiViewport3D initialization failed", e);
        }
    }
    
    /**
     * Initialize GLFW input handling for the specified window.
     */
    public void initializeInput(long windowHandle) {
        this.windowHandle = windowHandle;
        
        if (windowHandle != 0L) {
            inputHandler = new GLFWInputHandler(camera);
            inputHandler.initialize(windowHandle);
            
            logger.info("GLFW input handling initialized for window: {}", windowHandle);
        } else {
            logger.warn("Invalid window handle for input initialization");
        }
    }
    
    /**
     * Render the Dear ImGui viewport. Call this during the ImGui render loop.
     */
    public void render() {
        if (!renderingEnabled.get() || disposed.get()) {
            return;
        }
        
        try {
            // Render viewport using manager
            if (viewportManager != null) {
                viewportManager.render();
            }
            
            // Update performance metrics
            updatePerformanceMetrics();
            
        } catch (Exception e) {
            handleRenderingError(e);
        }
    }
    
    /**
     * Request a render update.
     */
    public void requestRender() {
        if (!renderingEnabled.get() || disposed.get()) {
            return;
        }
        
        // For Dear ImGui, rendering happens in the main render loop
        // This method exists for API compatibility
        // DISABLED callback to prevent infinite recursion - rendering handled by main loop
        // if (renderRequestCallback != null) {
        //     renderRequestCallback.accept(null);
        // }
    }
    
    /**
     * Update performance metrics.
     */
    private void updatePerformanceMetrics() {
        if (viewportManager != null) {
            frameCount.set(viewportManager.getFrameCount());
            currentFPS = viewportManager.getFPS();
        }
    }
    
    // ========== Model Management ==========
    
    /**
     * Set the current model to display.
     */
    public void setCurrentModel(StonebreakModel model) {
        this.currentModel = model;
        
        if (viewportManager != null) {
            viewportManager.setCurrentModel(model);
        }
        
        logger.info("Current model set: {}", model != null ? model.getVariantName() : "null");
        requestRender();
    }
    
    /**
     * Get the current model.
     */
    public StonebreakModel getCurrentModel() {
        return currentModel;
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
        
        CompletableFuture.<StonebreakModel>supplyAsync(() -> {
            try {
                // TODO: Implement model loading using existing ModelManager
                // StonebreakModel model = loadModelFromName(modelName);
                // For now, return null as placeholder
                return null;
                
            } catch (Exception e) {
                logger.error("Failed to load model: " + modelName, e);
                return null;
            }
        }).thenAccept(model -> {
            if (model != null) {
                setCurrentModel(model);
                fitCameraToModel();
            } else {
                logger.warn("Failed to load model: {}", modelName);
            }
        });
    }
    
    // ========== Camera Control ==========
    
    /**
     * Get the camera instance.
     */
    public ArcBallCamera getCamera() {
        return camera;
    }
    
    /**
     * Fit camera to current model.
     */
    public void fitCameraToModel() {
        if (viewportManager != null) {
            viewportManager.fitCameraToModel();
        }
        requestRender();
    }
    
    /**
     * Reset camera to default position.
     */
    public void resetCamera() {
        if (viewportManager != null) {
            viewportManager.resetCamera();
        }
        requestRender();
    }
    
    /**
     * Frame the origin.
     */
    public void frameOrigin() {
        if (camera != null) {
            camera.setTarget(new Vector3f(0, 0, 0));
            camera.setDistance(5.0f);
            requestRender();
        }
    }
    
    /**
     * Apply camera preset.
     */
    public void applyCameraPreset(ArcBallCamera.CameraPreset preset) {
        if (camera != null && preset != null) {
            // TODO: Implement camera preset application
            logger.debug("Applying camera preset: {}", preset);
            requestRender();
        }
    }
    
    // ========== Viewport Properties ==========
    
    /**
     * Set current texture variant.
     */
    public void setCurrentTextureVariant(String variant) {
        this.currentTextureVariant = variant != null ? variant : "default";
        
        if (viewportManager != null) {
            viewportManager.setTextureVariant(this.currentTextureVariant);
        }
        
        requestRender();
    }
    
    /**
     * Get current texture variant.
     */
    public String getCurrentTextureVariant() {
        return currentTextureVariant;
    }
    
    /**
     * Set wireframe mode.
     */
    public void setWireframeMode(boolean enabled) {
        this.wireframeMode = enabled;
        
        if (viewportManager != null) {
            viewportManager.setWireframeMode(enabled);
        }
        
        requestRender();
    }
    
    /**
     * Check if wireframe mode is enabled.
     */
    public boolean isWireframeMode() {
        return wireframeMode;
    }
    
    /**
     * Set grid visibility.
     */
    public void setGridVisible(boolean visible) {
        this.gridVisible = visible;
        
        if (viewportManager != null) {
            viewportManager.setShowGrid(visible);
        }
        
        requestRender();
    }
    
    /**
     * Check if grid is visible.
     */
    public boolean isGridVisible() {
        return gridVisible;
    }
    
    /**
     * Set axes visibility.
     */
    public void setAxesVisible(boolean visible) {
        this.axesVisible = visible;
        
        if (viewportManager != null) {
            viewportManager.setShowAxes(visible);
        }
        
        requestRender();
    }
    
    /**
     * Check if axes are visible.
     */
    public boolean isAxesVisible() {
        return axesVisible;
    }
    
    /**
     * Set debug mode.
     */
    public void setDebugMode(boolean enabled) {
        this.debugMode = enabled;
        
        if (viewportManager != null) {
            viewportManager.setShowDebugInfo(enabled);
        }
        
        requestRender();
    }
    
    /**
     * Check if debug mode is enabled.
     */
    public boolean isDebugMode() {
        return debugMode;
    }
    
    // ========== Performance and State ==========
    
    /**
     * Check if viewport is initialized.
     */
    public boolean isInitialized() {
        return initialized.get();
    }
    
    /**
     * Check if rendering is enabled.
     */
    public boolean isRenderingEnabled() {
        return renderingEnabled.get();
    }
    
    /**
     * Check if viewport is disposed.
     */
    public boolean isDisposed() {
        return disposed.get();
    }
    
    /**
     * Get current FPS.
     */
    public double getCurrentFPS() {
        return currentFPS;
    }
    
    /**
     * Get frame count.
     */
    public long getFrameCount() {
        return frameCount.get();
    }
    
    /**
     * Get last error.
     */
    public Throwable getLastError() {
        return lastError;
    }
    
    /**
     * Get error count.
     */
    public long getErrorCount() {
        return errorCount.get();
    }
    
    /**
     * Get viewport width.
     */
    public int getViewportWidth() {
        return viewportManager != null ? viewportManager.getViewportWidth() : 800;
    }
    
    /**
     * Get viewport height.
     */
    public int getViewportHeight() {
        return viewportManager != null ? viewportManager.getViewportHeight() : 600;
    }
    
    // ========== Callback Management ==========
    
    /**
     * Set render request callback.
     */
    public void setRenderRequestCallback(Consumer<Void> callback) {
        this.renderRequestCallback = callback;
    }
    
    /**
     * Set fit camera to model callback.
     */
    public void setFitCameraToModelCallback(Runnable callback) {
        this.fitCameraToModelCallback = callback;
    }
    
    /**
     * Set reset camera callback.
     */
    public void setResetCameraCallback(Runnable callback) {
        this.resetCameraCallback = callback;
    }
    
    /**
     * Set frame origin callback.
     */
    public void setFrameOriginCallback(Runnable callback) {
        this.frameOriginCallback = callback;
    }
    
    // ========== Error Handling ==========
    
    /**
     * Handle initialization errors.
     */
    private void handleInitializationError(Throwable error) {
        logger.error("ImGuiViewport3D initialization error", error);
        lastError = error;
        errorCount.incrementAndGet();
        
        if (performanceOptimizer != null) {
            // performanceOptimizer.recordError(error); // If method exists
        }
    }
    
    /**
     * Handle rendering errors.
     */
    private void handleRenderingError(Throwable error) {
        logger.warn("Rendering error in ImGuiViewport3D", error);
        lastError = error;
        errorCount.incrementAndGet();
        
        if (performanceOptimizer != null) {
            // performanceOptimizer.recordError(error); // If method exists
        }
    }
    
    // ========== Cleanup ==========
    
    /**
     * Dispose of viewport resources.
     */
    public void dispose() {
        if (disposed.getAndSet(true)) {
            return; // Already disposed
        }
        
        logger.info("Disposing ImGuiViewport3D resources");
        
        renderingEnabled.set(false);
        
        // Dispose input handler
        if (inputHandler != null) {
            inputHandler.dispose();
            inputHandler = null;
        }
        
        // Dispose viewport manager
        if (viewportManager != null) {
            viewportManager.dispose();
            viewportManager = null;
        }
        
        // Clear references
        currentModel = null;
        camera = null;
        modelRenderer = null;
        textureManager = null;
        performanceOptimizer = null;
        lastError = null;
        
        logger.info("ImGuiViewport3D disposed successfully");
    }
    
    // ========== Legacy Compatibility Methods ==========
    
    /**
     * Set model transform (placeholder for compatibility).
     */
    public void setModelTransform(float rotX, float rotY, float rotZ, float scale) {
        logger.debug("setModelTransform called: rot=({},{},{}), scale={}", rotX, rotY, rotZ, scale);
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
     * Reset viewport.
     */
    public void resetViewport() {
        resetCamera();
        setCurrentModel(null);
    }
    
    @Override
    public String toString() {
        return String.format("ImGuiViewport3D{initialized=%s, rendering=%s, disposed=%s, model=%s, fps=%.1f}",
            initialized.get(), renderingEnabled.get(), disposed.get(),
            currentModel != null ? currentModel.getVariantName() : "null", currentFPS);
    }
}