package com.openmason.ui.viewport;

import com.openmason.model.StonebreakModel;
import com.stonebreak.model.ModelDefinition;


import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * LWJGL renderer that renders to a Canvas.
 * 
 * This is the core bridge between LWJGL OpenGL rendering and Canvas.
 * It creates an OpenGL context, renders 3D content using LWJGL/Stonebreak systems,
 * and presents the results to the Canvas.
 * 
 * Key features:
 * - Canvas-based OpenGL context management
 * - Integration with Stonebreak's rendering pipeline
 * - Model rendering with proper coordinate system
 * - Debug visualization (grid, axes)
 * - Performance monitoring
 */
public class LWJGLCanvasRenderer {

    /**
     * Placeholder classes to replace JavaFX dependencies.
     */
    static class PlaceholderCanvas {
        public double getWidth() { return 800; }
        public double getHeight() { return 600; }
        public PlaceholderGraphicsContext getGraphicsContext2D() { return new PlaceholderGraphicsContext(); }
    }

    static class PlaceholderGraphicsContext {
        public void clearRect(double x, double y, double width, double height) { }
        public void setFill(PlaceholderColor color) { }
        public void fillText(String text, double x, double y) { }
        public void setStroke(PlaceholderColor color) { }
        public void strokeLine(double x1, double y1, double x2, double y2) { }
        public void strokeRect(double x, double y, double width, double height) { }
        public void setLineWidth(double width) { }
        public void fillRect(double x, double y, double width, double height) { }
    }

    static class PlaceholderColor {
        public static final PlaceholderColor BLACK = new PlaceholderColor();
        public static final PlaceholderColor WHITE = new PlaceholderColor();
        public static final PlaceholderColor RED = new PlaceholderColor();
        public static final PlaceholderColor GREEN = new PlaceholderColor();
        public static final PlaceholderColor BLUE = new PlaceholderColor();
        public static final PlaceholderColor YELLOW = new PlaceholderColor();
        public static final PlaceholderColor GRAY = new PlaceholderColor();
        public static final PlaceholderColor LIGHTGRAY = new PlaceholderColor();
        public static final PlaceholderColor LIGHTBLUE = new PlaceholderColor();
        
        public static PlaceholderColor rgb(int r, int g, int b) { return new PlaceholderColor(); }
    }

    static abstract class AnimationTimer {
        public abstract void handle(long now);
        public void start() { }
        public void stop() { }
    }
    
    private static final Logger logger = LoggerFactory.getLogger(LWJGLCanvasRenderer.class);
    
    private final PlaceholderCanvas canvas;
    private AnimationTimer renderLoop;
    
    // Rendering state
    private StonebreakModel currentModel;
    private Matrix4f viewMatrix = new Matrix4f().identity();
    private Matrix4f projectionMatrix = new Matrix4f();
    private String currentTextureVariant = "default";
    private boolean wireframeMode = false;
    private boolean gridVisible = true;
    private boolean axesVisible = true;
    
    // Real 3D rendering components
    private GLFWContextManager contextManager;
    private OpenGLFrameBuffer realFrameBuffer;
    private ImGuiViewport3D real3DViewport;
    private boolean using3DRendering = false;
    
    // Performance tracking
    private long lastFrameTime = 0;
    private int frameCount = 0;
    private double currentFPS = 0.0;
    
    // Initialization state
    private volatile boolean initialized = false;
    private volatile boolean disposed = false;
    
    /**
     * Create LWJGL renderer for the given Canvas.
     */
    public LWJGLCanvasRenderer(PlaceholderCanvas canvas) {
        this.canvas = canvas;
        logger.info("LWJGLCanvasRenderer created for Canvas: {}x{}", canvas.getWidth(), canvas.getHeight());
    }
    
    /**
     * Initialize the LWJGL renderer and OpenGL context.
     */
    public void initialize() {
        if (initialized) {
            logger.warn("LWJGLCanvasRenderer already initialized");
            return;
        }
        
        try {
            logger.info("Initializing LWJGL Canvas renderer...");
            
            // Initialize real 3D rendering - no fallback
            if (!initializeReal3DRendering()) {
                throw new RuntimeException("Failed to initialize 3D rendering - 2D fallback has been removed");
            }
            
            logger.info("3D rendering initialized successfully");
            
            // Set up projection matrix
            updateProjectionMatrix();
            
            // Start render loop
            startRenderLoop();
            
            initialized = true;
            logger.info("LWJGL Canvas renderer initialized successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize LWJGL Canvas renderer", e);
            throw new RuntimeException("LWJGL renderer initialization failed", e);
        }
    }
    
    /**
     * Try to initialize real 3D OpenGL rendering instead of software fallback.
     */
    private boolean initializeReal3DRendering() {
        try {
            logger.info("Attempting to initialize real 3D OpenGL rendering...");
            
            // Check if we're in a graphics-capable environment
            if (!isGraphicsEnvironmentAvailable()) {
                logger.info("Graphics environment not available, skipping 3D initialization");
                return false;
            }
            
            // SAFETY: Initialize GLFW with proper error handling
            this.contextManager = new GLFWContextManager();
            if (!safeInitializeGLFW()) {
                logger.warn("GLFW initialization failed safely");
                return false;
            }
            
            // Create proper OpenGL framebuffer for 3D rendering
            int width = (int)canvas.getWidth();
            int height = (int)canvas.getHeight();
            
            if (!safeInitializeFramebuffer(width, height)) {
                logger.warn("Framebuffer initialization failed safely");
                cleanupPartial3D();
                return false;
            }
            
            // Create ImGui 3D viewport for real 3D rendering
            if (!safeInitializeViewport()) {
                logger.warn("3D Viewport initialization failed safely");
                cleanupPartial3D();
                return false;
            }
            
            this.using3DRendering = true;
            logger.info("Real 3D OpenGL rendering initialized successfully: {}x{} framebuffer", width, height);
            return true;
            
        } catch (Throwable t) {
            // Catch ANY exception including native crashes
            logger.error("Critical error during 3D initialization - falling back to software mode", t);
            cleanupPartial3D();
            return false;
        }
    }
    
    /**
     * Check if graphics environment is available for OpenGL.
     */
    private boolean isGraphicsEnvironmentAvailable() {
        try {
            // Check if we're in headless mode
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                logger.info("Running in headless mode - 3D rendering not available");
                return false;
            }
            
            // Check system properties
            String osName = System.getProperty("os.name", "").toLowerCase();
            logger.debug("Operating system: {}", osName);
            
            return true;
            
        } catch (Exception e) {
            logger.warn("Unable to determine graphics environment", e);
            return false;
        }
    }
    
    /**
     * Safely initialize GLFW with proper error handling.
     */
    private boolean safeInitializeGLFW() {
        try {
            logger.debug("Initializing GLFW context manager...");
            this.contextManager.initialize();
            logger.debug("GLFW initialized successfully");
            return true;
            
        } catch (Exception e) {
            logger.warn("GLFW initialization failed: {}", e.getMessage());
            this.contextManager = null;
            return false;
        }
    }
    
    /**
     * Safely initialize OpenGL framebuffer.
     */
    private boolean safeInitializeFramebuffer(int width, int height) {
        try {
            logger.debug("Creating OpenGL framebuffer: {}x{}", width, height);
            this.realFrameBuffer = new OpenGLFrameBuffer(width, height);
            logger.debug("Framebuffer created successfully");
            return true;
            
        } catch (Exception e) {
            logger.warn("Framebuffer initialization failed: {}", e.getMessage());
            this.realFrameBuffer = null;
            return false;
        }
    }
    
    /**
     * Safely initialize 3D viewport.
     */
    private boolean safeInitializeViewport() {
        try {
            logger.debug("Creating ImGui 3D viewport...");
            this.real3DViewport = new ImGuiViewport3D();
            this.real3DViewport.initialize(realFrameBuffer, contextManager);
            
            // Configure 3D viewport with current settings
            this.real3DViewport.setGridVisible(gridVisible);
            this.real3DViewport.setAxesVisible(axesVisible);
            this.real3DViewport.setWireframeMode(wireframeMode);
            if (currentModel != null) {
                this.real3DViewport.setCurrentModel(currentModel);
            }
            
            logger.debug("3D viewport initialized successfully");
            return true;
            
        } catch (Exception e) {
            logger.warn("3D viewport initialization failed: {}", e.getMessage());
            this.real3DViewport = null;
            return false;
        }
    }
    
    /**
     * Clean up partial 3D initialization.
     */
    private void cleanupPartial3D() {
        try {
            if (real3DViewport != null) {
                real3DViewport.dispose();
                real3DViewport = null;
            }
            
            if (realFrameBuffer != null) {
                realFrameBuffer.cleanup();
                realFrameBuffer = null;
            }
            
            if (contextManager != null) {
                contextManager.cleanup();
                contextManager = null;
            }
            
            using3DRendering = false;
            logger.debug("Partial 3D initialization cleaned up");
            
        } catch (Exception e) {
            logger.warn("Error during partial 3D cleanup", e);
        }
    }
    
    // Old 2D fallback initialization methods removed
    
    /**
     * Update projection matrix based on Canvas dimensions.
     */
    private void updateProjectionMatrix() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        
        if (width > 0 && height > 0) {
            float aspect = (float)(width / height);
            projectionMatrix.setPerspective((float)Math.toRadians(60.0f), aspect, 0.1f, 1000.0f);
            
            // Projection matrix handled by 3D viewport directly
            if (real3DViewport != null) {
                // Real 3D viewport manages its own projection matrix
                logger.debug("Projection matrix delegated to 3D viewport");
            }
            
            logger.debug("Projection matrix updated for Canvas: {}x{}, aspect: {}", width, height, aspect);
        }
    }
    
    /**
     * Start the rendering loop.
     */
    private void startRenderLoop() {
        renderLoop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (!disposed) {
                    renderFrame();
                    updatePerformanceMetrics(now);
                }
            }
        };
        renderLoop.start();
        
        logger.info("Render loop started");
    }
    
    /**
     * Render a single frame.
     */
    public void renderFrame() {
        if (!initialized || disposed) {
            return;
        }
        
        try {
            // Always use 3D rendering - no fallback
            if (real3DViewport != null) {
                render3DFrame();
            } else {
                logger.error("3D viewport not initialized - cannot render");
                return;
            }
            
            frameCount++;
            
        } catch (Exception e) {
            logger.error("Error rendering 3D frame", e);
        }
    }
    
    /**
     * Render a frame using real 3D OpenGL rendering.
     */
    private void render3DFrame() {
        try {
            // Bind the 3D framebuffer for rendering
            if (realFrameBuffer != null) {
                realFrameBuffer.bind();
                
                // Clear with dark background
                realFrameBuffer.clear(0.1f, 0.1f, 0.1f, 1.0f, 1.0f);
                
                // Render the 3D scene
                if (real3DViewport != null) {
                    real3DViewport.render();
                }
                
                // Unbind framebuffer
                realFrameBuffer.unbind();
                
                // TODO: Copy framebuffer texture to Canvas
                // For now, we'll log that 3D rendering occurred
                logger.debug("3D frame rendered to {}x{} framebuffer", 
                           realFrameBuffer.getWidth(), realFrameBuffer.getHeight());
            }
            
        } catch (Exception e) {
            logger.error("Error rendering 3D frame", e);
        }
    }
    
    // 2D fallback methods removed - using 3D only
    
    /**
     * Update performance metrics.
     */
    private void updatePerformanceMetrics(long now) {
        if (lastFrameTime > 0) {
            long frameTime = now - lastFrameTime;
            double fps = 1_000_000_000.0 / frameTime;
            
            // Smooth FPS calculation
            currentFPS = currentFPS * 0.9 + fps * 0.1;
        }
        lastFrameTime = now;
    }
    
    /**
     * Handle Canvas resize.
     */
    public void resize(int width, int height) {
        if (width > 0 && height > 0) {
            updateProjectionMatrix();
            logger.debug("Canvas resized to: {}x{}", width, height);
        }
    }
    
    /**
     * Request a render update.
     */
    public void requestRender() {
        // Rendering happens automatically via AnimationTimer
        // This method exists for API compatibility
    }
    
    // ========== Model and Rendering State Management ==========
    
    public void setCurrentModel(StonebreakModel model) {
        this.currentModel = model;
        
        // Update real 3D viewport if available
        if (using3DRendering && real3DViewport != null) {
            real3DViewport.setCurrentModel(model);
        }
        
        logger.info("Current model set: {}", model != null ? model.getVariantName() : "null");
    }
    
    public void clearModel() {
        this.currentModel = null;
        logger.info("Model cleared");
    }
    
    public void setViewMatrix(Matrix4f viewMatrix) {
        if (viewMatrix != null) {
            this.viewMatrix.set(viewMatrix);
        }
    }
    
    public void setProjectionMatrix(Matrix4f projectionMatrix) {
        if (projectionMatrix != null) {
            this.projectionMatrix.set(projectionMatrix);
        }
    }
    
    public void setTextureVariant(String variant) {
        this.currentTextureVariant = variant != null ? variant : "default";
    }
    
    public void setWireframeMode(boolean enabled) {
        this.wireframeMode = enabled;
        
        // Update real 3D viewport if available
        if (using3DRendering && real3DViewport != null) {
            real3DViewport.setWireframeMode(enabled);
        }
    }
    
    public void setGridVisible(boolean visible) {
        this.gridVisible = visible;
        
        // Update real 3D viewport if available
        if (using3DRendering && real3DViewport != null) {
            real3DViewport.setGridVisible(visible);
        }
    }
    
    public void setAxesVisible(boolean visible) {
        this.axesVisible = visible;
        
        // Update real 3D viewport if available
        if (using3DRendering && real3DViewport != null) {
            real3DViewport.setAxesVisible(visible);
        }
    }
    
    public void setupLighting() {
        // Configure lighting parameters
        logger.debug("Lighting configured for Canvas renderer");
    }
    
    // ========== Getters ==========
    
    public StonebreakModel getCurrentModel() { return currentModel; }
    public boolean isWireframeMode() { return wireframeMode; }
    public boolean isGridVisible() { return gridVisible; }
    public boolean isAxesVisible() { return axesVisible; }
    public String getCurrentTextureVariant() { return currentTextureVariant; }
    public double getCurrentFPS() { return currentFPS; }
    public int getFrameCount() { return frameCount; }
    public boolean isInitialized() { return initialized; }
    public boolean isDisposed() { return disposed; }
    
    /**
     * Dispose of renderer resources.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        
        logger.info("Disposing LWJGL Canvas renderer");
        
        disposed = true;
        
        if (renderLoop != null) {
            renderLoop.stop();
            renderLoop = null;
        }
        
        // Dispose real 3D components if initialized
        if (using3DRendering) {
            if (real3DViewport != null) {
                real3DViewport.dispose();
                real3DViewport = null;
            }
            
            if (realFrameBuffer != null) {
                realFrameBuffer.cleanup();
                realFrameBuffer = null;
            }
            
            if (contextManager != null) {
                contextManager.cleanup();
                contextManager = null;
            }
            
            logger.info("Real 3D rendering components disposed");
        }
        
        // Old fallback components removed
        
        currentModel = null;
        
        logger.info("LWJGL Canvas renderer disposed");
    }
}