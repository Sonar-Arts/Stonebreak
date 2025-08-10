package com.openmason.ui.viewport;

import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;
import java.util.function.BiConsumer;

/**
 * ImGui-compatible 3D scene management for OpenGL rendering.
 * 
 * Manages OpenGL-based 3D scene setup without JavaFX dependencies.
 * Designed for use with ImGui framebuffers and Dear ImGui rendering pipeline.
 * 
 * Responsible for:
 * - OpenGL framebuffer management for ImGui integration
 * - Camera state management for OpenGL rendering
 * - 3D scene component lifecycle
 * - ImGui texture presentation
 * - Viewport state management
 */
public class ViewportSceneManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewportSceneManager.class);
    
    // OpenGL framebuffer components for ImGui
    private OpenGLFrameBuffer frameBuffer;
    private ImGuiViewport3D viewportRenderer;
    
    // Scene dimensions
    private double sceneWidth = 800.0;
    private double sceneHeight = 600.0;
    
    // Camera management (using JOML for OpenGL compatibility)
    private final Vector3f cameraPosition = new Vector3f(0, 0, 5);
    private final Vector3f cameraTarget = new Vector3f(0, 0, 0);
    private final Vector3f cameraUp = new Vector3f(0, 1, 0);
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f projectionMatrix = new Matrix4f();
    
    // Scene state
    private boolean sceneInitialized = false;
    private boolean gridVisible = true;
    private boolean axesVisible = true;
    
    // State tracking to prevent logging spam
    private Object lastGridElements = null;
    private Object lastAxesElements = null;
    
    // Callbacks
    private BiConsumer<Double, Double> dimensionChangeCallback;
    private Runnable sceneUpdateCallback;
    
    // Performance tracking
    private final Map<String, Object> performanceMetrics = new HashMap<>();
    
    
    /**
     * Initialize the ImGui-compatible scene manager.
     */
    public void initialize() {
        if (sceneInitialized) {
            logger.warn("ViewportSceneManager already initialized");
            return;
        }
        
        try {
            logger.info("Initializing ImGui-compatible ViewportSceneManager...");
            
            // Initialize OpenGL framebuffer for ImGui texture rendering
            frameBuffer = new OpenGLFrameBuffer((int)sceneWidth, (int)sceneHeight);
            // initialize() is called automatically in constructor
            
            // Initialize viewport renderer
            viewportRenderer = new ImGuiViewport3D();
            // No initialize() method needed - initialization handled in constructor
            
            // Initialize camera matrices
            updateCameraMatrices();
            
            sceneInitialized = true;
            logger.info("ViewportSceneManager initialized successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize ViewportSceneManager", e);
            throw new RuntimeException("Scene initialization failed", e);
        }
    }
    
    /**
     * Update camera view and projection matrices.
     */
    private void updateCameraMatrices() {
        // Update view matrix
        viewMatrix.setLookAt(
            cameraPosition.x, cameraPosition.y, cameraPosition.z,
            cameraTarget.x, cameraTarget.y, cameraTarget.z,
            cameraUp.x, cameraUp.y, cameraUp.z
        );
        
        // Update projection matrix
        float aspectRatio = (float)(sceneWidth / sceneHeight);
        projectionMatrix.setPerspective(
            (float)Math.toRadians(45.0), // FOV
            aspectRatio,
            0.1f, // Near plane
            100.0f // Far plane
        );
    }
    
    /**
     * Render the 3D scene to the ImGui texture.
     */
    public void renderScene() {
        if (!sceneInitialized || viewportRenderer == null) {
            return;
        }
        
        try {
            // Bind framebuffer for rendering
            frameBuffer.bind();
            
            // Clear the framebuffer
            frameBuffer.clear(0.1f, 0.1f, 0.1f, 1.0f, 1.0f);
            
            // Update camera matrices
            updateCameraMatrices();
            
            // Render 3D content
            viewportRenderer.render();
            
            // Render grid if visible
            if (gridVisible) {
                renderGrid();
            }
            
            // Render axes if visible
            if (axesVisible) {
                renderAxes();
            }
            
            // Unbind framebuffer
            frameBuffer.unbind();
            
            // Update performance metrics
            updatePerformanceMetrics();
            
        } catch (Exception e) {
            logger.warn("Error during scene rendering", e);
        }
    }
    
    /**
     * Render the grid overlay.
     */
    private void renderGrid() {
        // Grid rendering implementation would go here
        // For now, this is a placeholder
    }
    
    /**
     * Render the coordinate axes overlay.
     */
    private void renderAxes() {
        // Axes rendering implementation would go here
        // For now, this is a placeholder
    }
    
    /**
     * Update performance metrics for monitoring.
     */
    private void updatePerformanceMetrics() {
        performanceMetrics.put("lastRenderTime", System.currentTimeMillis());
        performanceMetrics.put("sceneWidth", sceneWidth);
        performanceMetrics.put("sceneHeight", sceneHeight);
        performanceMetrics.put("cameraPosition", cameraPosition.toString());
    }
    
    /**
     * Handle viewport resize.
     */
    public void handleResize(double width, double height) {
        if (width <= 0 || height <= 0) return;
        
        sceneWidth = width;
        sceneHeight = height;
        
        // Resize framebuffer
        if (frameBuffer != null) {
            frameBuffer.resize((int)width, (int)height);
        }
        
        // Update camera projection matrix
        updateCameraMatrices();
        
        // Notify callback
        if (dimensionChangeCallback != null) {
            dimensionChangeCallback.accept(width, height);
        }
        
        logger.debug("Scene resized to {}x{}", width, height);
    }
    
    /**
     * Get the OpenGL texture ID for ImGui rendering.
     */
    public int getTextureId() {
        return frameBuffer != null ? frameBuffer.getColorTextureID() : 0;
    }
    
    /**
     * Clean up resources.
     */
    public void cleanup() {
        try {
            if (viewportRenderer != null) {
                viewportRenderer.dispose();
                viewportRenderer = null;
            }
            
            if (frameBuffer != null) {
                frameBuffer.cleanup();
                frameBuffer = null;
            }
            
            sceneInitialized = false;
            logger.debug("ViewportSceneManager cleaned up");
            
        } catch (Exception e) {
            logger.warn("Error during cleanup", e);
        }
    }
    
    // Getters and setters
    public double getSceneWidth() { return sceneWidth; }
    public double getSceneHeight() { return sceneHeight; }
    
    public boolean isGridVisible() { return gridVisible; }
    public void setGridVisible(boolean visible) { 
        this.gridVisible = visible; 
        logger.debug("Grid visibility: {}", visible);
    }
    
    public boolean isAxesVisible() { return axesVisible; }
    public void setAxesVisible(boolean visible) { 
        this.axesVisible = visible; 
        logger.debug("Axes visibility: {}", visible);
    }
    
    public Vector3f getCameraPosition() { return new Vector3f(cameraPosition); }
    public void setCameraPosition(Vector3f position) { 
        this.cameraPosition.set(position); 
        updateCameraMatrices();
    }
    
    public Vector3f getCameraTarget() { return new Vector3f(cameraTarget); }
    public void setCameraTarget(Vector3f target) { 
        this.cameraTarget.set(target); 
        updateCameraMatrices();
    }
    
    public Matrix4f getViewMatrix() { return new Matrix4f(viewMatrix); }
    public Matrix4f getProjectionMatrix() { return new Matrix4f(projectionMatrix); }
    
    public void setDimensionChangeCallback(BiConsumer<Double, Double> callback) {
        this.dimensionChangeCallback = callback;
    }
    
    public void setSceneUpdateCallback(Runnable callback) {
        this.sceneUpdateCallback = callback;
    }
    
    public Map<String, Object> getPerformanceMetrics() {
        return new HashMap<>(performanceMetrics);
    }
    
    /**
     * Get scene state for debugging.
     */
    public String getSceneStateString() {
        return String.format("Scene: %dx%d, Camera: %s->%s, Grid: %s, Axes: %s", 
            (int)sceneWidth, (int)sceneHeight, 
            cameraPosition.toString(), cameraTarget.toString(),
            gridVisible, axesVisible);
    }
    
    /**
     * Set grid elements for debug rendering.
     * @param gridElements Group containing grid visualization elements
     */
    public void setGridElements(Object gridElements) {
        // This would integrate with the actual rendering system
        // Only log when the state actually changes to prevent spam
        if (lastGridElements != gridElements) {
            if (gridElements != null) {
                logger.debug("Grid elements set for rendering");
            } else {
                logger.debug("Grid elements cleared");
            }
            lastGridElements = gridElements;
        }
    }
    
    /**
     * Set axes elements for debug rendering.
     * @param axesElements Group containing axes visualization elements  
     */
    public void setAxesElements(Object axesElements) {
        // This would integrate with the actual rendering system
        // Only log when the state actually changes to prevent spam
        if (lastAxesElements != axesElements) {
            if (axesElements != null) {
                logger.debug("Axes elements set for rendering");
            } else {
                logger.debug("Axes elements cleared");
            }
            lastAxesElements = axesElements;
        }
    }

    /**
     * Dispose of scene resources.
     */
    public void dispose() {
        if (frameBuffer != null) {
            frameBuffer.cleanup();
        }
        if (viewportRenderer != null) {
            viewportRenderer.dispose();
        }
        sceneInitialized = false;
        logger.debug("ViewportSceneManager disposed");
    }
    
}