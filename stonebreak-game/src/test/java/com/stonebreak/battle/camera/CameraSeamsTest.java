package com.stonebreak.battle.camera;

import com.openmason.engine.rendering.gl.RenderingConfigurationManager;
import com.stonebreak.player.Camera;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The two engine/game seams the cinematic camera drives: Camera view override and projection FOV. */
class CameraSeamsTest {

    @Test void overrideAnswersFromEyeAndTargetAndClearingRestoresThePlayerView() {
        Camera camera = new Camera();
        camera.setPosition(1f, 2f, 3f);
        camera.setYaw(-90f);
        camera.setPitch(10f);
        Matrix4f before = camera.getViewMatrix();
        Vector3f frontBefore = new Vector3f(camera.getFront());

        camera.setCinematicView(new Vector3f(-9f, 4.5f, 17f), new Vector3f(0f, 1.6f, -2f), 0f);
        assertTrue(camera.isCinematicActive());
        assertEquals(new Vector3f(-9f, 4.5f, 17f), camera.getPosition());
        // The look-at target must project to the centre of the screen.
        Vector4f clip = new Vector4f(0f, 1.6f, -2f, 1f).mul(camera.getViewMatrix());
        assertEquals(0f, clip.x, 1e-4f);
        assertEquals(0f, clip.y, 1e-4f);
        assertTrue(clip.z < 0f, "target must be in front of the camera");

        camera.clearCinematicView();
        assertFalse(camera.isCinematicActive());
        assertEquals(new Vector3f(1f, 2f, 3f), camera.getPosition());
        assertEquals(frontBefore, camera.getFront());
        assertTrue(before.equals(camera.getViewMatrix(), 1e-6f));
    }

    @Test void mouseLookIsIgnoredWhileTheOverrideIsActive() {
        Camera camera = new Camera();
        float yaw = camera.getYaw(), pitch = camera.getPitch();
        camera.setCinematicView(new Vector3f(0f, 5f, 10f), new Vector3f(0f, 0f, 0f), 0f);
        camera.processMouseMovement(250f, -120f);
        camera.clearCinematicView();
        assertEquals(yaw, camera.getYaw());
        assertEquals(pitch, camera.getPitch());
    }

    @Test void basisStaysOrthonormalWithRollAndWhenLookingStraightDown() {
        Camera camera = new Camera();
        for (float roll : new float[]{0f, 12f, -35f}) {
            camera.setCinematicView(new Vector3f(3f, 9f, -4f), new Vector3f(-2f, 1f, 6f), roll);
            assertOrthonormal(camera);
        }
        camera.setCinematicView(new Vector3f(0f, 20f, 0f), new Vector3f(0f, 0f, 0f), 0f);
        assertOrthonormal(camera);
        // Degenerate request (eye == target) must not produce NaNs.
        camera.setCinematicView(new Vector3f(1f, 1f, 1f), new Vector3f(1f, 1f, 1f), 0f);
        assertOrthonormal(camera);
    }

    @Test void rollTiltsTheHorizon() {
        Camera camera = new Camera();
        camera.setCinematicView(new Vector3f(0f, 2f, 10f), new Vector3f(0f, 2f, 0f), 0f);
        assertEquals(1f, camera.getUp().y, 1e-5f);
        camera.setCinematicView(new Vector3f(0f, 2f, 10f), new Vector3f(0f, 2f, 0f), 30f);
        assertEquals(Math.cos(Math.toRadians(30)), camera.getUp().y, 1e-4);
    }

    @Test void fovSetterRebuildsTheSharedProjectionInPlaceAndResets() {
        RenderingConfigurationManager config = new RenderingConfigurationManager(1600, 900);
        Matrix4f shared = config.getProjectionMatrix();
        float defaultScale = shared.m11();

        config.setFieldOfViewDegrees(35f);
        assertSame(shared, config.getProjectionMatrix(), "consumers hold this instance");
        assertEquals(35f, config.getFieldOfViewDegrees());
        assertTrue(shared.m11() > defaultScale, "a narrower FOV magnifies");

        config.setFieldOfViewDegrees(500f);
        assertEquals(120f, config.getFieldOfViewDegrees(), "clamped");
        config.setFieldOfViewDegrees(Float.NaN);
        assertTrue(Float.isFinite(shared.m11()));

        config.setFieldOfViewDegrees(RenderingConfigurationManager.DEFAULT_FOV_DEGREES);
        assertEquals(defaultScale, shared.m11(), 1e-6f);
    }

    private static void assertOrthonormal(Camera camera) {
        Vector3f f = camera.getFront(), u = camera.getUp(), r = camera.getRight();
        for (Vector3f v : new Vector3f[]{f, u, r}) {
            assertTrue(v.isFinite(), "finite basis");
            assertEquals(1f, v.length(), 1e-4f);
        }
        assertEquals(0f, f.dot(u), 1e-4f);
        assertEquals(0f, f.dot(r), 1e-4f);
        assertEquals(0f, u.dot(r), 1e-4f);
    }
}
