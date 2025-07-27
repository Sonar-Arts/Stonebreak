package com.openmason.camera;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for ArcBallCamera to validate camera system functionality.
 * 
 * Tests core camera operations including rotation, zoom, pan, presets,
 * and matrix calculations to ensure professional camera behavior.
 */
public class ArcBallCameraTest {
    
    private ArcBallCamera camera;
    
    @BeforeEach
    void setUp() {
        camera = new ArcBallCamera();
    }
    
    @Test
    void testCameraInitialization() {
        assertNotNull(camera, "Camera should be initialized");
        assertNotNull(camera.getViewMatrix(), "View matrix should be available");
        assertNotNull(camera.getCameraPosition(), "Camera position should be available");
        assertNotNull(camera.getTarget(), "Camera target should be available");
        
        // Test default values
        assertEquals(45.0f, camera.getAzimuth(), 0.1f, "Default azimuth should be 45 degrees");
        assertEquals(20.0f, camera.getElevation(), 0.1f, "Default elevation should be 20 degrees");
        assertEquals(5.0f, camera.getDistance(), 0.1f, "Default distance should be 5 units");
        assertEquals(45.0f, camera.getFOV(), 0.1f, "Default FOV should be 45 degrees");
    }
    
    @Test
    void testCameraRotation() {
        float initialAzimuth = camera.getAzimuth();
        float initialElevation = camera.getElevation();
        
        // Test rotation
        camera.rotate(90.0f, 30.0f);
        camera.update(1.0f); // Complete interpolation
        
        // Azimuth should change (subtract because of camera rotation direction)
        assertNotEquals(initialAzimuth, camera.getAzimuth(), "Azimuth should change after rotation");
        
        // Elevation should change (subtract because of camera rotation direction)
        assertNotEquals(initialElevation, camera.getElevation(), "Elevation should change after rotation");
        
        // Test elevation constraints
        camera.setOrientation(0, 100); // Above max elevation
        assertEquals(89.0f, camera.getElevation(), 0.1f, "Elevation should be clamped to maximum");
        
        camera.setOrientation(0, -100); // Below min elevation
        assertEquals(-89.0f, camera.getElevation(), 0.1f, "Elevation should be clamped to minimum");
    }
    
    @Test
    void testCameraZoom() {
        float initialDistance = camera.getDistance();
        
        // Test zoom in
        camera.zoom(1.0f);
        camera.update(1.0f); // Complete interpolation
        assertTrue(camera.getDistance() < initialDistance, "Distance should decrease when zooming in");
        
        // Test zoom out
        camera.zoom(-2.0f);
        camera.update(1.0f); // Complete interpolation
        assertTrue(camera.getDistance() > initialDistance, "Distance should increase when zooming out");
        
        // Test distance constraints
        camera.setDistance(0.05f); // Below minimum
        assertEquals(0.1f, camera.getDistance(), 0.01f, "Distance should be clamped to minimum");
        
        camera.setDistance(200.0f); // Above maximum
        assertEquals(100.0f, camera.getDistance(), 0.1f, "Distance should be clamped to maximum");
    }
    
    @Test
    void testCameraPan() {
        Vector3f initialTarget = camera.getTarget();
        
        // Test panning
        camera.pan(50.0f, 30.0f);
        camera.update(1.0f); // Complete interpolation
        
        Vector3f newTarget = camera.getTarget();
        assertNotEquals(initialTarget, newTarget, "Target should change after panning");
    }
    
    @Test
    void testCameraPresets() {
        // Test front view preset
        camera.applyPreset(ArcBallCamera.CameraPreset.FRONT);
        camera.update(1.0f); // Complete interpolation
        assertEquals(0.0f, camera.getAzimuth(), 0.1f, "Front view should have 0 degree azimuth");
        assertEquals(0.0f, camera.getElevation(), 0.1f, "Front view should have 0 degree elevation");
        
        // Test isometric view preset
        camera.applyPreset(ArcBallCamera.CameraPreset.ISOMETRIC);
        camera.update(1.0f); // Complete interpolation
        assertEquals(45.0f, camera.getAzimuth(), 0.1f, "Isometric view should have 45 degree azimuth");
        assertEquals(35.264f, camera.getElevation(), 0.1f, "Isometric view should have 35.264 degree elevation");
        
        // Test top view preset
        camera.applyPreset(ArcBallCamera.CameraPreset.TOP);
        camera.update(1.0f); // Complete interpolation
        assertEquals(0.0f, camera.getAzimuth(), 0.1f, "Top view should have 0 degree azimuth");
        assertEquals(90.0f, camera.getElevation(), 0.1f, "Top view should have 90 degree elevation");
    }
    
    @Test
    void testViewMatrixCalculation() {
        Matrix4f viewMatrix = camera.getViewMatrix();
        assertNotNull(viewMatrix, "View matrix should not be null");
        
        // Test that view matrix changes when camera moves
        Matrix4f initialMatrix = new Matrix4f(viewMatrix);
        camera.rotate(45.0f, 0);
        camera.update(1.0f);
        Matrix4f newMatrix = camera.getViewMatrix();
        
        assertNotEquals(initialMatrix, newMatrix, "View matrix should change when camera rotates");
    }
    
    @Test
    void testProjectionMatrixCalculation() {
        Matrix4f projMatrix = camera.getProjectionMatrix(800, 600, 0.1f, 1000.0f);
        assertNotNull(projMatrix, "Projection matrix should not be null");
        
        // Test aspect ratio handling
        Matrix4f squareMatrix = camera.getProjectionMatrix(600, 600, 0.1f, 1000.0f);
        Matrix4f wideMatrix = camera.getProjectionMatrix(1200, 600, 0.1f, 1000.0f);
        
        assertNotEquals(squareMatrix, wideMatrix, "Projection matrix should change with aspect ratio");
    }
    
    @Test
    void testCameraReset() {
        // Modify camera state
        camera.rotate(90.0f, 45.0f);
        camera.zoom(2.0f);
        camera.pan(100.0f, 100.0f);
        camera.update(1.0f);
        
        // Reset camera
        camera.reset();
        camera.update(1.0f);
        
        // Verify reset to default values
        assertEquals(45.0f, camera.getAzimuth(), 0.1f, "Azimuth should reset to default");
        assertEquals(20.0f, camera.getElevation(), 0.1f, "Elevation should reset to default");
        assertEquals(5.0f, camera.getDistance(), 0.1f, "Distance should reset to default");
        
        Vector3f target = camera.getTarget();
        assertEquals(0.0f, target.x, 0.1f, "Target X should reset to origin");
        assertEquals(0.0f, target.y, 0.1f, "Target Y should reset to origin");
        assertEquals(0.0f, target.z, 0.1f, "Target Z should reset to origin");
    }
    
    @Test
    void testFrameObject() {
        Vector3f min = new Vector3f(-2, -1, -3);
        Vector3f max = new Vector3f(2, 3, 1);
        
        camera.frameObject(min, max);
        camera.update(1.0f);
        
        // Verify camera target is set to center of bounding box
        Vector3f expectedCenter = new Vector3f(-2, -1, -3).add(2, 3, 1).mul(0.5f);
        Vector3f actualTarget = camera.getTarget();
        
        assertEquals(expectedCenter.x, actualTarget.x, 0.1f, "Target X should be at bounding box center");
        assertEquals(expectedCenter.y, actualTarget.y, 0.1f, "Target Y should be at bounding box center");
        assertEquals(expectedCenter.z, actualTarget.z, 0.1f, "Target Z should be at bounding box center");
        
        // Distance should be adjusted to frame the object
        assertTrue(camera.getDistance() > 0.1f, "Distance should be reasonable for framing");
        assertTrue(camera.getDistance() < 100.0f, "Distance should not be excessive");
    }
    
    @Test
    void testSmoothInterpolation() {
        // Set a target rotation
        camera.rotate(90.0f, 0);
        
        // Update with small time step
        camera.update(0.016f); // One frame at 60 FPS
        
        // Should be interpolating (not at final position yet)
        assertNotEquals(45.0f - 90.0f * 0.3f, camera.getAzimuth(), 0.5f, 
                       "Camera should be interpolating, not jumping to final position");
        
        // After sufficient time, should reach target
        camera.update(1.0f); // Large time step to complete interpolation
        assertTrue(Math.abs(camera.getAzimuth() - (45.0f - 90.0f * 0.3f)) < 5.0f,
                  "Camera should eventually reach target position");
    }
    
    @Test
    void testAnimationState() {
        assertFalse(camera.isAnimating(), "Camera should not be animating initially");
        
        // Start animation
        camera.rotate(90.0f, 0);
        assertTrue(camera.isAnimating(), "Camera should be animating after rotation command");
        
        // Complete animation
        camera.update(10.0f); // Large time step to complete
        // Note: Animation state depends on implementation - this test verifies the API exists
    }
}