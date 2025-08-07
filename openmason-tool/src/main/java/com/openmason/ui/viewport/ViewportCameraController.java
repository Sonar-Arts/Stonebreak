package com.openmason.ui.viewport;

import com.openmason.camera.ArcBallCamera;
import com.openmason.model.StonebreakModel;
import com.openmason.model.stonebreak.StonebreakModelDefinition;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Manages camera operations and transformations for the 3D viewport.
 * 
 * Responsible for:
 * - Camera initialization and configuration
 * - Camera movement and rotation operations
 * - View and projection matrix management
 * - Camera presets and auto-framing
 * - Coordinate system transformations
 */
public class ViewportCameraController {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewportCameraController.class);
    
    private ArcBallCamera camera;
    private ViewportSceneManager sceneManager;
    
    // Matrix management
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f modelMatrix = new Matrix4f();
    
    // Camera configuration
    private double canvasWidth = 800.0;
    private double canvasHeight = 600.0;
    private static final float NEAR_PLANE = 0.1f;
    private static final float FAR_PLANE = 1000.0f;
    private static final float DEFAULT_FOV = 60.0f;
    
    // Callbacks
    private Consumer<Void> renderRequestCallback;
    
    /**
     * Initialize camera controller.
     */
    public void initialize(ViewportSceneManager sceneManager) {
        this.sceneManager = sceneManager;
        initializeCamera();
        logger.debug("ViewportCameraController initialized");
    }
    
    /**
     * Initialize the ArcBall camera system.
     */
    private void initializeCamera() {
        try {
            camera = new ArcBallCamera();
            
            // Set default camera position for optimal viewing of origin and grid
            camera.setTarget(new Vector3f(0f, 0f, 0f)); // Always look at origin
            camera.setDistance(25.0f); // Increase distance to see more of the grid
            camera.setOrientation(135.0f, 35.0f); // Azimuth 135° for good grid visibility, elevation 35° for overhead view
            
            // Update matrices
            updateProjectionMatrix();
            updateViewMatrix();
            
            logger.info("ArcBall camera initialized: distance={}, target=({},{},{})", 
                camera.getDistance(), 
                camera.getTarget().x, camera.getTarget().y, camera.getTarget().z);
                
            // Force initial update to apply the default position
            camera.update(0.1f);
            
            // Log calculated camera position for verification
            Matrix4f viewMatrix = camera.getViewMatrix();
            Matrix4f invViewMatrix = new Matrix4f(viewMatrix).invert();
            float x = invViewMatrix.m30();
            float y = invViewMatrix.m31(); 
            float z = invViewMatrix.m32();
            logger.info("Initial camera position calculated: ({:.2f}, {:.2f}, {:.2f})", x, y, z);
                
        } catch (Exception e) {
            logger.error("Failed to initialize camera", e);
            throw new RuntimeException("Camera initialization failed", e);
        }
    }
    
    /**
     * Update the projection matrix based on current viewport dimensions.
     */
    public void updateProjectionMatrix() {
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            logger.warn("Invalid canvas dimensions for projection matrix: {}x{}", canvasWidth, canvasHeight);
            return;
        }
        
        float aspectRatio = (float) (canvasWidth / canvasHeight);
        float fovRadians = (float) Math.toRadians(DEFAULT_FOV);
        
        projectionMatrix.identity()
            .perspective(fovRadians, aspectRatio, NEAR_PLANE, FAR_PLANE);
        
        logger.trace("Projection matrix updated: FOV={}, aspect={}, near={}, far={}", 
            DEFAULT_FOV, aspectRatio, NEAR_PLANE, FAR_PLANE);
    }
    
    /**
     * Update the view matrix from camera state.
     */
    public void updateViewMatrix() {
        if (camera == null) {
            logger.warn("Cannot update view matrix: camera is null");
            return;
        }
        
        // Update camera interpolation for smooth animation
        camera.update(0.016f); // ~60 FPS delta time
        
        viewMatrix.set(camera.getViewMatrix());
        
        // Update JavaFX camera position if scene manager is available
        if (sceneManager != null) {
            sceneManager.updateCameraFromMatrix(viewMatrix);
        }
        
        logger.trace("View matrix updated from camera state");
    }
    
    /**
     * Update canvas dimensions and recalculate projection matrix.
     */
    public void updateCanvasDimensions(double width, double height) {
        if (width > 0 && height > 0) {
            this.canvasWidth = width;
            this.canvasHeight = height;
            updateProjectionMatrix();
            
            logger.debug("Canvas dimensions updated: {}x{}", width, height);
        }
    }
    
    /**
     * Reset camera to default position and orientation.
     */
    public void resetCamera() {
        if (camera == null) {
            logger.warn("Cannot reset camera: camera is null");
            return;
        }
        
        camera.setTarget(new Vector3f(0f, 0f, 0f));
        camera.setDistance(25.0f);
        camera.setOrientation(135.0f, 35.0f); // Same as default position
        
        updateViewMatrix();
        requestRender();
        
        logger.debug("Camera reset to default position");
    }
    
    /**
     * Frame the origin (0,0,0) with appropriate zoom.
     */
    public void frameOrigin() {
        frameOrigin(2.0f); // Default size
    }
    
    /**
     * Frame the origin with a specific model size.
     */
    public void frameOrigin(float modelSize) {
        if (camera == null) {
            logger.warn("Cannot frame origin: camera is null");
            return;
        }
        
        // Calculate appropriate distance based on model size
        float distance = Math.max(modelSize * 2.5f, 5.0f);
        
        camera.setTarget(new Vector3f(0f, 0f, 0f));
        camera.setDistance(distance);
        
        updateViewMatrix();
        requestRender();
        
        logger.debug("Framed origin with distance: {} (model size: {})", distance, modelSize);
    }
    
    /**
     * Automatically fit camera to view the current model.
     */
    public void fitCameraToModel(StonebreakModel model) {
        if (camera == null) {
            logger.warn("Cannot fit camera: camera is null");
            return;
        }
        
        if (model == null) {
            logger.debug("No model to fit camera to, using default framing");
            frameOrigin();
            return;
        }
        
        try {
            // Calculate model bounds
            float modelHeight = calculateModelHeight(model);
            float modelWidth = calculateModelWidth(model);
            float modelDepth = calculateModelDepth(model);
            
            // Calculate bounding sphere radius
            float maxDimension = Math.max(Math.max(modelWidth, modelHeight), modelDepth);
            float boundingRadius = maxDimension * 0.6f; // Slight padding
            
            // Calculate optimal camera distance
            float fovRadians = (float) Math.toRadians(DEFAULT_FOV);
            float distance = boundingRadius / (float) Math.tan(fovRadians / 2.0);
            distance = Math.max(distance * 1.2f, 3.0f); // Add padding and minimum distance
            
            // Set camera position
            camera.setTarget(new Vector3f(0f, modelHeight * 0.25f, 0f)); // Slightly above center
            camera.setDistance(distance);
            
            updateViewMatrix();
            requestRender();
            
            logger.debug("Fit camera to model '{}': distance={}, target=({},{},{})", 
                model.getVariantName(), distance, 0f, modelHeight * 0.25f, 0f);
                
        } catch (Exception e) {
            logger.error("Failed to fit camera to model: " + model.getVariantName(), e);
            frameOrigin();
        }
    }
    
    /**
     * Apply a camera preset.
     */
    public void applyCameraPreset(ArcBallCamera.CameraPreset preset) {
        if (camera == null || preset == null) {
            logger.warn("Cannot apply camera preset: camera={}, preset={}", camera, preset);
            return;
        }
        
        try {
            camera.applyPreset(preset);
            updateViewMatrix();
            requestRender();
            
            logger.debug("Applied camera preset: {}", preset);
            
        } catch (Exception e) {
            logger.error("Failed to apply camera preset: " + preset, e);
        }
    }
    
    /**
     * Calculate model height from definition.
     */
    private float calculateModelHeight(StonebreakModel model) {
        if (model == null) return 2.0f;
        
        try {
            StonebreakModelDefinition.CowModelDefinition definition = model.getModelDefinition();
            if (definition == null) return 2.0f;
            
            StonebreakModelDefinition.ModelParts parts = definition.getParts();
            if (parts == null) return 2.0f;
            
            float minY = Float.MAX_VALUE;
            float maxY = Float.MIN_VALUE;
            
            // Check all parts for Y bounds
            for (StonebreakModelDefinition.ModelPart part : getAllParts(parts)) {
                if (part == null) continue;
                
                Vector3f translation = part.getPositionVector();
                Vector3f size = part.getSizeVector();
                
                if (translation != null && size != null) {
                    
                    float y = translation.y;
                    float height = size.y;
                    
                    minY = Math.min(minY, y - height / 2);
                    maxY = Math.max(maxY, y + height / 2);
                }
            }
            
            return (minY != Float.MAX_VALUE && maxY != Float.MIN_VALUE) ? 
                Math.abs(maxY - minY) : 2.0f;
                
        } catch (Exception e) {
            logger.warn("Failed to calculate model height", e);
            return 2.0f;
        }
    }
    
    /**
     * Calculate model width from definition.
     */
    private float calculateModelWidth(StonebreakModel model) {
        if (model == null) return 2.0f;
        
        try {
            StonebreakModelDefinition.CowModelDefinition definition = model.getModelDefinition();
            if (definition == null) return 2.0f;
            
            StonebreakModelDefinition.ModelParts parts = definition.getParts();
            if (parts == null) return 2.0f;
            
            float minX = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE;
            
            // Check all parts for X bounds
            for (StonebreakModelDefinition.ModelPart part : getAllParts(parts)) {
                if (part == null) continue;
                
                Vector3f translation = part.getPositionVector();
                Vector3f size = part.getSizeVector();
                
                if (translation != null && size != null) {
                    
                    float x = translation.x;
                    float width = size.x;
                    
                    minX = Math.min(minX, x - width / 2);
                    maxX = Math.max(maxX, x + width / 2);
                }
            }
            
            return (minX != Float.MAX_VALUE && maxX != Float.MIN_VALUE) ? 
                Math.abs(maxX - minX) : 2.0f;
                
        } catch (Exception e) {
            logger.warn("Failed to calculate model width", e);
            return 2.0f;
        }
    }
    
    /**
     * Calculate model depth from definition.
     */
    private float calculateModelDepth(StonebreakModel model) {
        if (model == null) return 2.0f;
        
        try {
            StonebreakModelDefinition.CowModelDefinition definition = model.getModelDefinition();
            if (definition == null) return 2.0f;
            
            StonebreakModelDefinition.ModelParts parts = definition.getParts();
            if (parts == null) return 2.0f;
            
            float minZ = Float.MAX_VALUE;
            float maxZ = Float.MIN_VALUE;
            
            // Check all parts for Z bounds
            for (StonebreakModelDefinition.ModelPart part : getAllParts(parts)) {
                if (part == null) continue;
                
                Vector3f translation = part.getPositionVector();
                Vector3f size = part.getSizeVector();
                
                if (translation != null && size != null) {
                    
                    float z = translation.z;
                    float depth = size.z;
                    
                    minZ = Math.min(minZ, z - depth / 2);
                    maxZ = Math.max(maxZ, z + depth / 2);
                }
            }
            
            return (minZ != Float.MAX_VALUE && maxZ != Float.MIN_VALUE) ? 
                Math.abs(maxZ - minZ) : 2.0f;
                
        } catch (Exception e) {
            logger.warn("Failed to calculate model depth", e);
            return 2.0f;
        }
    }
    
    /**
     * Get all model parts as a list for iteration.
     */
    private java.util.List<StonebreakModelDefinition.ModelPart> getAllParts(StonebreakModelDefinition.ModelParts parts) {
        java.util.List<StonebreakModelDefinition.ModelPart> allParts = new java.util.ArrayList<>();
        
        if (parts.getHead() != null) allParts.add(parts.getHead());
        if (parts.getBody() != null) allParts.add(parts.getBody());
        if (parts.getLegs() != null) allParts.addAll(parts.getLegs());
        if (parts.getUdder() != null) allParts.add(parts.getUdder());
        if (parts.getTail() != null) allParts.add(parts.getTail());
        
        return allParts;
    }
    
    /**
     * Project 3D point to 2D screen coordinates.
     */
    public boolean project3DTo2D(Vector3f worldPoint, Vector3f screenPoint) {
        if (worldPoint == null || screenPoint == null) {
            return false;
        }
        
        try {
            // Create MVP matrix
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix);
            mvpMatrix.mul(viewMatrix);
            mvpMatrix.mul(modelMatrix);
            
            // Transform point
            Vector3f transformedPoint = new Vector3f(worldPoint);
            mvpMatrix.transformPosition(transformedPoint);
            
            // Convert to screen coordinates
            screenPoint.x = (transformedPoint.x + 1.0f) * 0.5f * (float) canvasWidth;
            screenPoint.y = (1.0f - transformedPoint.y) * 0.5f * (float) canvasHeight;
            screenPoint.z = transformedPoint.z;
            
            return true;
            
        } catch (Exception e) {
            logger.trace("Failed to project 3D point to 2D", e);
            return false;
        }
    }
    
    /**
     * Request a render update via callback.
     */
    private void requestRender() {
        if (renderRequestCallback != null) {
            renderRequestCallback.accept(null);
        }
    }
    
    // Getters and Setters
    public ArcBallCamera getCamera() {
        return camera;
    }
    
    public Matrix4f getViewMatrix() {
        updateViewMatrix();
        return new Matrix4f(viewMatrix);
    }
    
    public Matrix4f getProjectionMatrix() {
        return new Matrix4f(projectionMatrix);
    }
    
    public Matrix4f getModelMatrix() {
        return new Matrix4f(modelMatrix);
    }
    
    public void setModelMatrix(Matrix4f matrix) {
        if (matrix != null) {
            this.modelMatrix.set(matrix);
        }
    }
    
    public double getCanvasWidth() {
        return canvasWidth;
    }
    
    public double getCanvasHeight() {
        return canvasHeight;
    }
    
    public void setRenderRequestCallback(Consumer<Void> callback) {
        this.renderRequestCallback = callback;
    }
    
    /**
     * Get camera state for debugging.
     */
    public String getCameraState() {
        if (camera == null) {
            return "Camera: null";
        }
        
        Vector3f target = camera.getTarget();
        return String.format("Camera: distance=%.2f, target=(%.2f,%.2f,%.2f), canvas=%.0fx%.0f",
            camera.getDistance(), target.x, target.y, target.z, canvasWidth, canvasHeight);
    }
}