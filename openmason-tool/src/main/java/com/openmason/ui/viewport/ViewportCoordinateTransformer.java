package com.openmason.ui.viewport;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinate system transformer for Canvas-based LWJGL input handling.
 * 
 * This class handles the coordinate system differences between:
 * - JavaFX Scene coordinates (top-left origin, Y down)
 * - JavaFX Canvas coordinates (top-left origin, Y down)
 * - OpenGL/LWJGL coordinates (bottom-left origin, Y up)
 * - 3D World coordinates (model space)
 * 
 * Key transformations:
 * - Screen to Canvas coordinate conversion
 * - Canvas to 3D world coordinate projection
 * - Ray casting for 3D model interaction
 * - View/projection matrix synchronization
 * 
 * This ensures that mouse interactions work identically to the previous
 * JavaFX 3D system while using LWJGL rendering backend.
 */
public class ViewportCoordinateTransformer {

    /**
     * Placeholder class to replace JavaFX Canvas.
     */
    static class PlaceholderCanvas {
        public double getWidth() { return 800; }
        public double getHeight() { return 600; }
    }
    
    private static final Logger logger = LoggerFactory.getLogger(ViewportCoordinateTransformer.class);
    
    private PlaceholderCanvas canvas;
    private Matrix4f viewMatrix = new Matrix4f().identity();
    private Matrix4f projectionMatrix = new Matrix4f();
    private Matrix4f modelMatrix = new Matrix4f().identity();
    
    // Cached inverse matrices for performance
    private Matrix4f invViewMatrix = new Matrix4f();
    private Matrix4f invProjectionMatrix = new Matrix4f();
    private Matrix4f invModelViewProjectionMatrix = new Matrix4f();
    private boolean matricesValid = false;
    
    // Viewport dimensions
    private float viewportWidth = 800.0f;
    private float viewportHeight = 600.0f;
    
    /**
     * Create coordinate transformer for the given Canvas.
     */
    public ViewportCoordinateTransformer(PlaceholderCanvas canvas) {
        this.canvas = canvas;
        updateViewportDimensions();
        logger.debug("ViewportCoordinateTransformer created for Canvas: {}x{}", 
                    canvas.getWidth(), canvas.getHeight());
    }
    
    /**
     * Update viewport dimensions from Canvas.
     */
    private void updateViewportDimensions() {
        if (canvas != null) {
            this.viewportWidth = (float) canvas.getWidth();
            this.viewportHeight = (float) canvas.getHeight();
            invalidateMatrices();
        }
    }
    
    /**
     * Set view matrix from camera system.
     */
    public void setViewMatrix(Matrix4f viewMatrix) {
        if (viewMatrix != null) {
            this.viewMatrix.set(viewMatrix);
            invalidateMatrices();
        }
    }
    
    /**
     * Set projection matrix from rendering system.
     */
    public void setProjectionMatrix(Matrix4f projectionMatrix) {
        if (projectionMatrix != null) {
            this.projectionMatrix.set(projectionMatrix);
            invalidateMatrices();
        }
    }
    
    /**
     * Set model matrix for specific model transformations.
     */
    public void setModelMatrix(Matrix4f modelMatrix) {
        if (modelMatrix != null) {
            this.modelMatrix.set(modelMatrix);
            invalidateMatrices();
        }
    }
    
    /**
     * Invalidate cached matrices when source matrices change.
     */
    private void invalidateMatrices() {
        matricesValid = false;
    }
    
    /**
     * Update cached inverse matrices for coordinate transformations.
     */
    private void updateCachedMatrices() {
        if (!matricesValid) {
            try {
                // Calculate inverse view matrix
                viewMatrix.invert(invViewMatrix);
                
                // Calculate inverse projection matrix
                projectionMatrix.invert(invProjectionMatrix);
                
                // Calculate combined inverse MVP matrix
                Matrix4f mvpMatrix = new Matrix4f(projectionMatrix)
                    .mul(viewMatrix)
                    .mul(modelMatrix);
                mvpMatrix.invert(invModelViewProjectionMatrix);
                
                matricesValid = true;
                
            } catch (Exception e) {
                logger.warn("Failed to update cached matrices", e);
                // Use identity matrices as fallback
                invViewMatrix.identity();
                invProjectionMatrix.identity();
                invModelViewProjectionMatrix.identity();
            }
        }
    }
    
    /**
     * Convert JavaFX scene coordinates to Canvas coordinates.
     * 
     * @param sceneX X coordinate in JavaFX scene space
     * @param sceneY Y coordinate in JavaFX scene space
     * @return Canvas coordinates (still top-left origin, but Canvas-relative)
     */
    public Vector2f screenToCanvas(double sceneX, double sceneY) {
        // For Canvas within a scene, scene coordinates are typically the same as canvas coordinates
        // unless the Canvas is transformed or positioned within the scene
        
        float canvasX = (float) sceneX;
        float canvasY = (float) sceneY;
        
        // Clamp to canvas bounds
        canvasX = Math.max(0, Math.min(canvasX, viewportWidth));
        canvasY = Math.max(0, Math.min(canvasY, viewportHeight));
        
        return new Vector2f(canvasX, canvasY);
    }
    
    /**
     * Convert Canvas coordinates to OpenGL NDC (Normalized Device Coordinates).
     * 
     * @param canvasX X coordinate in Canvas space (0 to width)
     * @param canvasY Y coordinate in Canvas space (0 to height)
     * @return NDC coordinates (-1 to +1, with Y flipped for OpenGL)
     */
    public Vector2f canvasToNDC(float canvasX, float canvasY) {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            logger.warn("Invalid viewport dimensions: {}x{}", viewportWidth, viewportHeight);
            return new Vector2f(0, 0);
        }
        
        // Convert to NDC (-1 to +1)
        float ndcX = (2.0f * canvasX / viewportWidth) - 1.0f;
        float ndcY = 1.0f - (2.0f * canvasY / viewportHeight); // Flip Y axis for OpenGL
        
        return new Vector2f(ndcX, ndcY);
    }
    
    /**
     * Create a ray from camera through the given screen point for 3D picking.
     * 
     * @param canvasX X coordinate in Canvas space
     * @param canvasY Y coordinate in Canvas space
     * @return Ray for 3D intersection testing
     */
    public Ray createPickingRay(float canvasX, float canvasY) {
        updateCachedMatrices();
        
        try {
            // Convert to NDC
            Vector2f ndc = canvasToNDC(canvasX, canvasY);
            
            // Create points in clip space
            Vector4f nearPoint = new Vector4f(ndc.x, ndc.y, -1.0f, 1.0f); // Near plane
            Vector4f farPoint = new Vector4f(ndc.x, ndc.y, 1.0f, 1.0f);   // Far plane
            
            // Transform to world space
            invModelViewProjectionMatrix.transform(nearPoint);
            invModelViewProjectionMatrix.transform(farPoint);
            
            // Perspective divide
            if (nearPoint.w != 0) {
                nearPoint.div(nearPoint.w);
            }
            if (farPoint.w != 0) {
                farPoint.div(farPoint.w);
            }
            
            // Create ray
            Vector3f rayOrigin = new Vector3f(nearPoint.x, nearPoint.y, nearPoint.z);
            Vector3f rayEnd = new Vector3f(farPoint.x, farPoint.y, farPoint.z);
            Vector3f rayDirection = new Vector3f(rayEnd).sub(rayOrigin).normalize();
            
            return new Ray(rayOrigin, rayDirection);
            
        } catch (Exception e) {
            logger.warn("Failed to create picking ray for ({}, {})", canvasX, canvasY, e);
            
            // Return default ray pointing forward
            Vector3f origin = new Vector3f(0, 0, 0);
            Vector3f direction = new Vector3f(0, 0, -1);
            return new Ray(origin, direction);
        }
    }
    
    /**
     * Convert Canvas coordinates directly to 3D world coordinates at a specific depth.
     * 
     * @param canvasX X coordinate in Canvas space
     * @param canvasY Y coordinate in Canvas space
     * @param worldZ Z coordinate in world space to project to
     * @return 3D world coordinates
     */
    public Vector3f canvasToWorld(float canvasX, float canvasY, float worldZ) {
        Ray ray = createPickingRay(canvasX, canvasY);
        
        // Find intersection of ray with plane at worldZ
        if (Math.abs(ray.direction.z) < 0.0001f) {
            // Ray is parallel to Z plane, return ray origin
            return new Vector3f(ray.origin);
        }
        
        // Calculate t parameter for intersection with Z plane
        float t = (worldZ - ray.origin.z) / ray.direction.z;
        
        // Calculate world coordinates
        Vector3f worldPos = new Vector3f(ray.origin)
            .add(new Vector3f(ray.direction).mul(t));
        
        return worldPos;
    }
    
    /**
     * Convert 3D world coordinates to Canvas screen coordinates.
     * 
     * @param worldPos 3D world position
     * @return Canvas coordinates, or null if behind camera
     */
    public Vector2f worldToCanvas(Vector3f worldPos) {
        if (worldPos == null) {
            return null;
        }
        
        updateCachedMatrices();
        
        try {
            // Transform world position to clip space
            Vector4f clipPos = new Vector4f(worldPos.x, worldPos.y, worldPos.z, 1.0f);
            
            // Apply model-view-projection transformation
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix)
                .mul(viewMatrix)
                .mul(modelMatrix);
            mvpMatrix.transform(clipPos);
            
            // Check if behind camera (negative w or z)
            if (clipPos.w <= 0) {
                return null; // Behind camera
            }
            
            // Perspective divide
            clipPos.div(clipPos.w);
            
            // Convert from NDC to Canvas coordinates
            float canvasX = (clipPos.x + 1.0f) * viewportWidth * 0.5f;
            float canvasY = (1.0f - clipPos.y) * viewportHeight * 0.5f; // Flip Y back to Canvas space
            
            return new Vector2f(canvasX, canvasY);
            
        } catch (Exception e) {
            logger.warn("Failed to convert world coordinates ({}, {}, {}) to canvas", 
                       worldPos.x, worldPos.y, worldPos.z, e);
            return null;
        }
    }
    
    /**
     * Test if a Canvas coordinate is within the viewport bounds.
     */
    public boolean isInViewport(float canvasX, float canvasY) {
        return canvasX >= 0 && canvasX <= viewportWidth && 
               canvasY >= 0 && canvasY <= viewportHeight;
    }
    
    /**
     * Update transformer when Canvas dimensions change.
     */
    public void updateViewport(double width, double height) {
        if (width > 0 && height > 0) {
            this.viewportWidth = (float) width;
            this.viewportHeight = (float) height;
            invalidateMatrices();
            
            logger.debug("Viewport updated to: {}x{}", width, height);
        }
    }
    
    /**
     * Get current viewport width.
     */
    public float getViewportWidth() {
        return viewportWidth;
    }
    
    /**
     * Get current viewport height.
     */
    public float getViewportHeight() {
        return viewportHeight;
    }
    
    /**
     * Get current view matrix.
     */
    public Matrix4f getViewMatrix() {
        return new Matrix4f(viewMatrix);
    }
    
    /**
     * Get current projection matrix.
     */
    public Matrix4f getProjectionMatrix() {
        return new Matrix4f(projectionMatrix);
    }
    
    /**
     * Generate diagnostic information for coordinate system debugging.
     */
    public String getDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Coordinate Transformer Diagnostics ===\n");
        sb.append("Canvas: ").append(canvas != null ? "valid" : "null").append("\n");
        sb.append("Viewport: ").append(viewportWidth).append("x").append(viewportHeight).append("\n");
        sb.append("Matrices Valid: ").append(matricesValid).append("\n");
        sb.append("View Matrix: ").append(viewMatrix.toString()).append("\n");
        sb.append("Projection Matrix: ").append(projectionMatrix.toString()).append("\n");
        return sb.toString();
    }
    
    /**
     * Simple ray class for 3D picking operations.
     */
    public static class Ray {
        public final Vector3f origin;
        public final Vector3f direction;
        
        public Ray(Vector3f origin, Vector3f direction) {
            this.origin = new Vector3f(origin);
            this.direction = new Vector3f(direction).normalize();
        }
        
        /**
         * Get point along ray at parameter t.
         */
        public Vector3f getPoint(float t) {
            return new Vector3f(origin).add(new Vector3f(direction).mul(t));
        }
        
        /**
         * Test ray intersection with axis-aligned bounding box.
         */
        public boolean intersectsAABB(Vector3f min, Vector3f max) {
            // Implementation of ray-AABB intersection test
            float tMin = Float.NEGATIVE_INFINITY;
            float tMax = Float.POSITIVE_INFINITY;
            
            // Test each axis
            for (int i = 0; i < 3; i++) {
                float rayOrigin = origin.get(i);
                float rayDir = direction.get(i);
                float boxMin = min.get(i);
                float boxMax = max.get(i);
                
                if (Math.abs(rayDir) < 1e-6f) {
                    // Ray is parallel to this axis
                    if (rayOrigin < boxMin || rayOrigin > boxMax) {
                        return false;
                    }
                } else {
                    float t1 = (boxMin - rayOrigin) / rayDir;
                    float t2 = (boxMax - rayOrigin) / rayDir;
                    
                    if (t1 > t2) {
                        float temp = t1;
                        t1 = t2;
                        t2 = temp;
                    }
                    
                    tMin = Math.max(tMin, t1);
                    tMax = Math.min(tMax, t2);
                    
                    if (tMin > tMax) {
                        return false;
                    }
                }
            }
            
            return tMax >= 0; // Hit if intersection is in front of ray origin
        }
        
        @Override
        public String toString() {
            return String.format("Ray{origin=(%f,%f,%f), direction=(%f,%f,%f)}", 
                               origin.x, origin.y, origin.z, 
                               direction.x, direction.y, direction.z);
        }
    }
}