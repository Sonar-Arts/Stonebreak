package com.openmason.main.systems.viewport.util;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenProjectionUtilTest {

    private static final float EPS = 1e-3f;
    private static final int WIDTH = 200;
    private static final int HEIGHT = 100;

    @Test
    void identityMvpMapsNdcToScreen() {
        Matrix4f identity = new Matrix4f();

        // NDC origin → viewport center
        Vector2f center = ScreenProjectionUtil.projectToScreen(new Vector3f(0, 0, 0), identity, WIDTH, HEIGHT);
        assertNotNull(center);
        assertEquals(WIDTH / 2f, center.x, EPS);
        assertEquals(HEIGHT / 2f, center.y, EPS);

        // NDC top-right (+1, +1) → (width, 0): screen Y is flipped
        Vector2f topRight = ScreenProjectionUtil.projectToScreen(new Vector3f(1, 1, 0), identity, WIDTH, HEIGHT);
        assertNotNull(topRight);
        assertEquals(WIDTH, topRight.x, EPS);
        assertEquals(0f, topRight.y, EPS);

        // NDC bottom-left (−1, −1) → (0, height)
        Vector2f bottomLeft = ScreenProjectionUtil.projectToScreen(new Vector3f(-1, -1, 0), identity, WIDTH, HEIGHT);
        assertNotNull(bottomLeft);
        assertEquals(0f, bottomLeft.x, EPS);
        assertEquals(HEIGHT, bottomLeft.y, EPS);
    }

    @Test
    void pointBehindCameraReturnsNull() {
        // Perspective camera looks down −Z, so a point at +Z is behind it (clip.w <= 0)
        Matrix4f perspective = new Matrix4f().perspective((float) Math.toRadians(60), 2f, 0.1f, 100f);

        assertNull(ScreenProjectionUtil.projectToScreen(new Vector3f(0, 0, 5), perspective, WIDTH, HEIGHT));
        assertNotNull(ScreenProjectionUtil.projectToScreen(new Vector3f(0, 0, -5), perspective, WIDTH, HEIGHT));
    }

    @Test
    void pointOnViewAxisProjectsToCenter() {
        Matrix4f perspective = new Matrix4f().perspective((float) Math.toRadians(60), 2f, 0.1f, 100f);

        Vector2f screen = ScreenProjectionUtil.projectToScreen(new Vector3f(0, 0, -5), perspective, WIDTH, HEIGHT);
        assertNotNull(screen);
        assertEquals(WIDTH / 2f, screen.x, EPS);
        assertEquals(HEIGHT / 2f, screen.y, EPS);
    }

    @Test
    void withDepthVariantMatchesScreenPositionAndReportsDepthOrder() {
        Matrix4f perspective = new Matrix4f().perspective((float) Math.toRadians(60), 2f, 0.1f, 100f);

        Vector3f near = ScreenProjectionUtil.projectToScreenWithDepth(0, 0, -1f, perspective, WIDTH, HEIGHT);
        Vector3f far = ScreenProjectionUtil.projectToScreenWithDepth(0, 0, -50f, perspective, WIDTH, HEIGHT);
        assertNotNull(near);
        assertNotNull(far);

        // Same screen position as the Vector2f variant
        Vector2f nearScreen = ScreenProjectionUtil.projectToScreen(new Vector3f(0, 0, -1f), perspective, WIDTH, HEIGHT);
        assertNotNull(nearScreen);
        assertEquals(nearScreen.x, near.x, EPS);
        assertEquals(nearScreen.y, near.y, EPS);

        // Closer points have smaller NDC depth
        assertTrue(near.z < far.z);
    }

    @Test
    void nullInputsReturnNull() {
        assertNull(ScreenProjectionUtil.projectToScreen(null, new Matrix4f(), WIDTH, HEIGHT));
        assertNull(ScreenProjectionUtil.projectToScreen(new Vector3f(), null, WIDTH, HEIGHT));
        assertNull(ScreenProjectionUtil.projectToScreenWithDepth(0, 0, 0, null, WIDTH, HEIGHT));
    }
}
