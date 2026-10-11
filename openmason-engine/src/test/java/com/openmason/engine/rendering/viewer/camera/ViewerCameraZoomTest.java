package com.openmason.engine.rendering.viewer.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Close zoom on block-scale models: the orbit reaches below one texel (1/16 block)
 * and the near plane follows it so the orbited model is never clipped.
 */
class ViewerCameraZoomTest {

    @Test
    void zoomInReachesBelowOneTexel() {
        ViewerCamera camera = new ViewerCamera();
        for (int i = 0; i < 1000; i++) {
            camera.zoom(-1.0f);
        }
        assertEquals(CameraMath.MIN_DISTANCE, camera.getDistance(), 1e-6f);
        assertTrue(camera.getDistance() < 1.0f / 16.0f);
    }

    @Test
    void nearPlaneStaysInFrontOfTheTarget() {
        ViewerCamera camera = new ViewerCamera();
        camera.setDistance(CameraMath.MIN_DISTANCE);
        assertTrue(camera.nearPlane() < camera.getDistance() * 0.1f);

        camera.setDistance(15.0f);
        assertEquals(CameraMath.MAX_NEAR_PLANE, camera.nearPlane(), 1e-6f);
    }

    @Test
    void projectionFollowsDistanceWithoutAnExplicitInvalidate() {
        ViewerCamera camera = new ViewerCamera();
        camera.setDistance(15.0f);
        float farZoomNear = projectedNear(camera);

        camera.setDistance(CameraMath.MIN_DISTANCE);
        float closeZoomNear = projectedNear(camera);

        assertEquals(CameraMath.MAX_NEAR_PLANE, farZoomNear, 1e-4f);
        assertEquals(CameraMath.nearPlaneForDistance(CameraMath.MIN_DISTANCE), closeZoomNear, 1e-5f);
    }

    @Test
    void firstPersonKeepsTheFixedNearPlane() {
        ViewerCamera camera = new ViewerCamera();
        camera.setDistance(CameraMath.MIN_DISTANCE);
        camera.setCameraMode(ViewerCamera.CameraMode.FIRST_PERSON);
        assertEquals(CameraMath.MAX_NEAR_PLANE, camera.nearPlane(), 1e-6f);
    }

    /** Recovers the near distance from a GL perspective matrix: m32 / (m22 - 1). */
    private static float projectedNear(ViewerCamera camera) {
        var m = camera.getProjectionMatrix();
        return m.m32() / (m.m22() - 1.0f);
    }
}
