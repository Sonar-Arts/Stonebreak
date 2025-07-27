package com.openmason.camera;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Professional arc-ball camera system for 3D model inspection and navigation.
 * 
 * Provides industry-standard camera controls with smooth interpolation and preset configurations.
 * Features spherical coordinate navigation, constraint enforcement, and professional-grade
 * user interaction patterns commonly found in 3D modeling software.
 * 
 * Key Features:
 * - Arc-ball rotation with spherical coordinates (azimuth/elevation)
 * - Smooth zoom with exponential scaling and distance constraints
 * - Pan controls using camera-relative coordinate system
 * - Camera preset system for standard viewing angles
 * - Quaternion-based smooth interpolation for all movements
 * - Auto-fit functionality for optimal model framing
 * - Professional keyboard shortcuts and mouse controls
 * 
 * Architecture:
 * - Spherical coordinates (azimuth, elevation, distance) for intuitive navigation
 * - Target-based camera system (always looking at a specific point)
 * - Smooth interpolation targets for fluid camera movement
 * - Constraint system for zoom and elevation limits
 * - Matrix-based view calculations with proper up vector handling
 */
public class ArcBallCamera {
    
    private static final Logger logger = LoggerFactory.getLogger(ArcBallCamera.class);
    
    // Camera state (current values)
    private Vector3f target;                    // Look-at target (model center)
    private float distance;                     // Distance from target
    private float azimuth;                      // Horizontal rotation (degrees)
    private float elevation;                    // Vertical rotation (degrees)
    private float fov;                          // Field of view (degrees)
    
    // Smooth interpolation targets
    private Vector3f targetTarget;              // Target for smooth panning
    private float targetDistance;               // Target distance for smooth zooming
    private float targetAzimuth;               // Target azimuth for smooth rotation
    private float targetElevation;             // Target elevation for smooth rotation
    private float targetFov;                   // Target field of view
    
    // Camera constraints
    private static final float MIN_DISTANCE = 0.1f;
    private static final float MAX_DISTANCE = 100.0f;
    private static final float MIN_ELEVATION = -89.0f;
    private static final float MAX_ELEVATION = 89.0f;
    private static final float MIN_FOV = 5.0f;
    private static final float MAX_FOV = 120.0f;
    private static final float DEFAULT_FOV = 45.0f;
    
    // Interpolation settings
    private static final float INTERPOLATION_SPEED = 8.0f;      // Higher = faster transition
    private static final float ZOOM_SENSITIVITY = 1.15f;        // Zoom factor per scroll step
    private static final float ROTATION_SENSITIVITY = 0.3f;     // Degrees per pixel
    private static final float PAN_SENSITIVITY = 0.002f;        // World units per pixel
    private static final float INTERPOLATION_THRESHOLD = 0.001f; // Stop interpolating below this
    
    // Cached matrices and vectors for performance
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Vector3f cameraPosition = new Vector3f();
    private final Vector3f cameraUp = new Vector3f(0, 1, 0);
    private final Vector3f tempVector = new Vector3f();
    
    // Animation and interpolation state
    private boolean isAnimating = false;
    private long lastUpdateTime = 0;
    
    /**
     * Camera preset enumeration for standard viewing angles.
     * Provides industry-standard views commonly used in 3D modeling software.
     */
    public enum CameraPreset {
        FRONT(0, 0, "Front View"),
        BACK(180, 0, "Back View"),
        LEFT(-90, 0, "Left View"),
        RIGHT(90, 0, "Right View"),
        TOP(0, 90, "Top View"),
        BOTTOM(0, -90, "Bottom View"),
        ISOMETRIC(45, 35.264f, "Isometric View"),
        PERSPECTIVE(-30, 20, "Perspective View");
        
        public final float azimuth;
        public final float elevation;
        public final String displayName;
        
        CameraPreset(float azimuth, float elevation, String displayName) {
            this.azimuth = azimuth;
            this.elevation = elevation;
            this.displayName = displayName;
        }
    }
    
    /**
     * Creates a new ArcBall camera with default positioning.
     * Initializes camera to look at origin from a reasonable distance.
     */
    public ArcBallCamera() {
        // Initialize camera state
        target = new Vector3f(0, 0, 0);
        distance = 5.0f;
        azimuth = 45.0f;
        elevation = 20.0f;
        fov = DEFAULT_FOV;
        
        // Initialize interpolation targets
        targetTarget = new Vector3f(target);
        targetDistance = distance;
        targetAzimuth = azimuth;
        targetElevation = elevation;
        targetFov = fov;
        
        lastUpdateTime = System.currentTimeMillis();
        
        logger.debug("ArcBall camera initialized - Target: {}, Distance: {}, Azimuth: {}°, Elevation: {}°", 
                    target, distance, azimuth, elevation);
    }
    
    /**
     * Updates camera interpolation and calculates current matrices.
     * Should be called every frame to ensure smooth camera movement.
     * 
     * @param deltaTime Time since last frame in seconds
     */
    public void update(float deltaTime) {
        boolean wasAnimating = isAnimating;
        isAnimating = false;
        
        // Smooth interpolation for all camera parameters
        if (interpolateFloat(targetDistance, distance, deltaTime)) {
            distance = lerp(distance, targetDistance, INTERPOLATION_SPEED * deltaTime);
            isAnimating = true;
        }
        
        if (interpolateAngle(targetAzimuth, azimuth, deltaTime)) {
            azimuth = lerpAngle(azimuth, targetAzimuth, INTERPOLATION_SPEED * deltaTime);
            isAnimating = true;
        }
        
        if (interpolateFloat(targetElevation, elevation, deltaTime)) {
            elevation = lerp(elevation, targetElevation, INTERPOLATION_SPEED * deltaTime);
            isAnimating = true;
        }
        
        if (interpolateVector(targetTarget, target, deltaTime)) {
            target.lerp(targetTarget, INTERPOLATION_SPEED * deltaTime);
            isAnimating = true;
        }
        
        if (interpolateFloat(targetFov, fov, deltaTime)) {
            fov = lerp(fov, targetFov, INTERPOLATION_SPEED * deltaTime);
            isAnimating = true;
        }
        
        // Log animation state changes
        if (wasAnimating && !isAnimating) {
            logger.debug("Camera animation completed");
        }
        
        // Update cached matrices
        updateViewMatrix();
    }
    
    /**
     * Calculates and returns the current view matrix.
     * 
     * @return View matrix for current camera state
     */
    public Matrix4f getViewMatrix() {
        return new Matrix4f(viewMatrix);
    }
    
    /**
     * Calculates and returns projection matrix for given viewport dimensions.
     * 
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @param nearPlane Near clipping plane distance
     * @param farPlane Far clipping plane distance
     * @return Projection matrix for current camera state
     */
    public Matrix4f getProjectionMatrix(int viewportWidth, int viewportHeight, float nearPlane, float farPlane) {
        float aspectRatio = (float) viewportWidth / (float) Math.max(viewportHeight, 1);
        projectionMatrix.identity().perspective(
            (float) Math.toRadians(fov),
            aspectRatio,
            nearPlane,
            farPlane
        );
        return new Matrix4f(projectionMatrix);
    }
    
    /**
     * Gets the current camera position in world space.
     * 
     * @return Current camera position
     */
    public Vector3f getCameraPosition() {
        return new Vector3f(cameraPosition);
    }
    
    /**
     * Gets the current look-at target position.
     * 
     * @return Current target position
     */
    public Vector3f getTarget() {
        return new Vector3f(target);
    }
    
    /**
     * Handles mouse rotation input (typically left mouse button drag).
     * 
     * @param deltaX Horizontal mouse movement in pixels
     * @param deltaY Vertical mouse movement in pixels
     */
    public void rotate(float deltaX, float deltaY) {
        targetAzimuth -= deltaX * ROTATION_SENSITIVITY;
        targetElevation = Math.max(MIN_ELEVATION, 
                         Math.min(MAX_ELEVATION, targetElevation - deltaY * ROTATION_SENSITIVITY));
        
        // Wrap azimuth to stay within 0-360 range
        while (targetAzimuth < 0) targetAzimuth += 360;
        while (targetAzimuth >= 360) targetAzimuth -= 360;
        
        logger.trace("Camera rotation - Azimuth: {}°, Elevation: {}°", targetAzimuth, targetElevation);
    }
    
    /**
     * Handles mouse zoom input (typically scroll wheel).
     * 
     * @param scrollDelta Scroll wheel delta (positive = zoom in, negative = zoom out)
     */
    public void zoom(float scrollDelta) {
        float zoomFactor = (float) Math.pow(ZOOM_SENSITIVITY, scrollDelta);
        targetDistance = Math.max(MIN_DISTANCE, 
                        Math.min(MAX_DISTANCE, targetDistance * zoomFactor));
        
        logger.trace("Camera zoom - Distance: {}", targetDistance);
    }
    
    /**
     * Handles mouse panning input (typically right mouse button drag).
     * Pan movement is relative to current camera orientation.
     * 
     * @param deltaX Horizontal mouse movement in pixels
     * @param deltaY Vertical mouse movement in pixels
     */
    public void pan(float deltaX, float deltaY) {
        Vector3f right = calculateRightVector();
        Vector3f up = calculateUpVector();
        
        // Calculate pan offset relative to current camera distance
        Vector3f panOffset = new Vector3f()
            .add(new Vector3f(right).mul(deltaX * PAN_SENSITIVITY * distance))
            .add(new Vector3f(up).mul(-deltaY * PAN_SENSITIVITY * distance));
        
        targetTarget.add(panOffset);
        
        logger.trace("Camera pan - Target: {}", targetTarget);
    }
    
    /**
     * Changes field of view (zoom alternative).
     * 
     * @param fovDelta Change in field of view in degrees
     */
    public void changeFOV(float fovDelta) {
        targetFov = Math.max(MIN_FOV, Math.min(MAX_FOV, targetFov + fovDelta));
        logger.trace("Camera FOV change - FOV: {}°", targetFov);
    }
    
    /**
     * Applies a camera preset with smooth transition.
     * 
     * @param preset The camera preset to apply
     */
    public void applyPreset(CameraPreset preset) {
        targetAzimuth = preset.azimuth;
        targetElevation = preset.elevation;
        
        // Adjust distance for certain presets
        if (preset == CameraPreset.ISOMETRIC) {
            targetDistance = Math.max(targetDistance, 3.0f); // Ensure good isometric view
        }
        
        logger.info("Applied camera preset: {} (Azimuth: {}°, Elevation: {}°)", 
                   preset.displayName, preset.azimuth, preset.elevation);
    }
    
    /**
     * Automatically frames the camera to show a bounding box optimally.
     * 
     * @param min Minimum corner of bounding box
     * @param max Maximum corner of bounding box
     */
    public void frameObject(Vector3f min, Vector3f max) {
        // Calculate center and size of bounding box
        Vector3f center = new Vector3f(min).add(max).mul(0.5f);
        Vector3f size = new Vector3f(max).sub(min);
        float maxDimension = Math.max(Math.max(size.x, size.y), size.z);
        
        // Set target to center of object
        targetTarget.set(center);
        
        // Calculate optimal distance based on field of view and object size
        float halfFov = (float) Math.toRadians(fov * 0.5f);
        float optimalDistance = (maxDimension * 0.6f) / (float) Math.tan(halfFov);
        optimalDistance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, optimalDistance));
        
        targetDistance = optimalDistance;
        
        logger.info("Framed object - Center: {}, Distance: {}", center, optimalDistance);
    }
    
    /**
     * Resets camera to default position and orientation.
     */
    public void reset() {
        targetTarget.set(0, 0, 0);
        targetDistance = 5.0f;
        targetAzimuth = 45.0f;
        targetElevation = 20.0f;
        targetFov = DEFAULT_FOV;
        
        logger.info("Camera reset to default position");
    }
    
    /**
     * Sets camera target position immediately (no interpolation).
     * 
     * @param newTarget New target position
     */
    public void setTarget(Vector3f newTarget) {
        target.set(newTarget);
        targetTarget.set(newTarget);
    }
    
    /**
     * Sets camera distance immediately (no interpolation).
     * 
     * @param newDistance New distance from target
     */
    public void setDistance(float newDistance) {
        distance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, newDistance));
        targetDistance = distance;
    }
    
    /**
     * Sets camera orientation immediately (no interpolation).
     * 
     * @param newAzimuth New azimuth angle in degrees
     * @param newElevation New elevation angle in degrees
     */
    public void setOrientation(float newAzimuth, float newElevation) {
        azimuth = normalizeAngle(newAzimuth);
        elevation = Math.max(MIN_ELEVATION, Math.min(MAX_ELEVATION, newElevation));
        targetAzimuth = azimuth;
        targetElevation = elevation;
    }
    
    /**
     * Checks if camera is currently animating/interpolating.
     * 
     * @return True if camera is moving, false if stationary
     */
    public boolean isAnimating() {
        return isAnimating;
    }
    
    /**
     * Gets current camera distance from target.
     * 
     * @return Current distance
     */
    public float getDistance() {
        return distance;
    }
    
    /**
     * Gets current azimuth angle.
     * 
     * @return Current azimuth in degrees
     */
    public float getAzimuth() {
        return azimuth;
    }
    
    /**
     * Gets current elevation angle.
     * 
     * @return Current elevation in degrees
     */
    public float getElevation() {
        return elevation;
    }
    
    /**
     * Gets current field of view.
     * 
     * @return Current FOV in degrees
     */
    public float getFOV() {
        return fov;
    }
    
    // Private helper methods
    
    /**
     * Updates the view matrix based on current camera state.
     */
    private void updateViewMatrix() {
        // Calculate camera position from spherical coordinates
        calculateCameraPosition();
        
        // Create view matrix using lookAt
        viewMatrix.identity().lookAt(cameraPosition, target, cameraUp);
    }
    
    /**
     * Calculates camera position from spherical coordinates.
     */
    private void calculateCameraPosition() {
        float azimuthRad = (float) Math.toRadians(azimuth);
        float elevationRad = (float) Math.toRadians(elevation);
        
        // Convert spherical to Cartesian coordinates
        float cosElevation = (float) Math.cos(elevationRad);
        cameraPosition.x = target.x + distance * cosElevation * (float) Math.sin(azimuthRad);
        cameraPosition.y = target.y + distance * (float) Math.sin(elevationRad);
        cameraPosition.z = target.z + distance * cosElevation * (float) Math.cos(azimuthRad);
    }
    
    /**
     * Calculates the right vector for camera-relative movement.
     */
    private Vector3f calculateRightVector() {
        Vector3f forward = new Vector3f(target).sub(cameraPosition).normalize();
        return new Vector3f(forward).cross(cameraUp).normalize();
    }
    
    /**
     * Calculates the up vector for camera-relative movement.
     */
    private Vector3f calculateUpVector() {
        Vector3f forward = new Vector3f(target).sub(cameraPosition).normalize();
        Vector3f right = calculateRightVector();
        return new Vector3f(right).cross(forward).normalize();
    }
    
    /**
     * Linear interpolation for float values.
     */
    private float lerp(float a, float b, float t) {
        return a + (b - a) * Math.min(t, 1.0f);
    }
    
    /**
     * Angular interpolation that handles wraparound correctly.
     */
    private float lerpAngle(float from, float to, float t) {
        float difference = to - from;
        
        // Handle wraparound
        if (difference > 180) {
            difference -= 360;
        } else if (difference < -180) {
            difference += 360;
        }
        
        return normalizeAngle(from + difference * Math.min(t, 1.0f));
    }
    
    /**
     * Normalizes angle to 0-360 range.
     */
    private float normalizeAngle(float angle) {
        while (angle < 0) angle += 360;
        while (angle >= 360) angle -= 360;
        return angle;
    }
    
    /**
     * Checks if float interpolation should continue.
     */
    private boolean interpolateFloat(float target, float current, float deltaTime) {
        return Math.abs(target - current) > INTERPOLATION_THRESHOLD;
    }
    
    /**
     * Checks if angle interpolation should continue.
     */
    private boolean interpolateAngle(float target, float current, float deltaTime) {
        float difference = Math.abs(target - current);
        if (difference > 180) difference = 360 - difference;
        return difference > INTERPOLATION_THRESHOLD;
    }
    
    /**
     * Checks if vector interpolation should continue.
     */
    private boolean interpolateVector(Vector3f target, Vector3f current, float deltaTime) {
        return target.distance(current) > INTERPOLATION_THRESHOLD;
    }
}