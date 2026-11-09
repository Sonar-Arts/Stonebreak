package com.stonebreak.ui.mainMenu.showcase;

import com.stonebreak.battle.camera.CameraFrame;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The main menu's Resume move and the preview's orbit around the player. */
class ResumeTransitionTest {

    private static final float EPS = 1e-3f;
    private static final Vector3f FEET = new Vector3f(10f, 64f, -5f);

    @Test
    void startsOnTheOrbitAndEndsInTheEye() {
        CameraFrame orbit = new CameraFrame(new Vector3f(14f, 66.7f, -3f), new Vector3f(10f, 65.1f, -5f), 0f, 60f);
        CameraFrame eye = ResumeTransition.firstPerson(FEET, 1.44f, 30f, -10f, 70f);
        ResumeTransition t = new ResumeTransition(orbit, eye, 1.4f);

        assertNear(orbit.eye(), t.frame().eye(), "t=0 is the orbit's eye");
        assertEquals(60f, t.frame().fovDeg(), EPS);
        assertDirection(orbit, t.frame(), "t=0 looks where the orbit looked");

        for (int i = 0; i < 100 && !t.finished(); i++) {
            t.tick(0.05f);
        }
        assertTrue(t.finished());
        assertNear(new Vector3f(FEET).add(0f, 1.44f, 0f), t.frame().eye(), "t=1 is the first-person eye");
        assertEquals(70f, t.frame().fovDeg(), EPS, "t=1 is the gameplay field of view");
        assertDirection(eye, t.frame(), "t=1 looks along the saved yaw and pitch");
    }

    @Test
    void theMenuIsGoneBeforeTheCameraArrives() {
        CameraFrame orbit = new CameraFrame(new Vector3f(0f, 2f, 4f), new Vector3f(0f, 1f, 0f), 0f, 60f);
        ResumeTransition t = new ResumeTransition(orbit, ResumeTransition.firstPerson(new Vector3f(), 1.44f, 90f, 0f, 70f), 1f);
        assertEquals(0f, t.menuExit(), EPS);
        for (int i = 0; i < 12; i++) {
            t.tick(0.05f); // frames, as the game ticks it: 0.6 s of a 1 s move
        }
        assertEquals(1f, t.menuExit(), EPS);
        assertFalse(t.finished());
    }

    @Test
    void facingThePlayerNeverLooksThroughTheEye() {
        // The orbit faces the player head-on and Resume turns the view right round: a lerped
        // look-at point would pass through the eye half way. The view must stay well defined.
        CameraFrame orbit = new CameraFrame(new Vector3f(0f, 1.44f, -4f), new Vector3f(0f, 1.44f, 0f), 0f, 60f);
        CameraFrame eye = ResumeTransition.firstPerson(new Vector3f(), 1.44f, -90f, 0f, 70f); // faces -z, at the camera
        ResumeTransition t = new ResumeTransition(orbit, eye, 1f);
        for (int i = 0; i < 20; i++) {
            t.tick(0.05f);
            CameraFrame f = t.frame();
            assertTrue(f.target().distance(f.eye()) > 1f, "the look-at point stays clear of the eye");
        }
    }

    @Test
    void thePreviewOrbitCirclesTheChestAndOpensOnTheFace() {
        FlyoverShot orbit = MenuShowcase.previewOrbit(FEET, 0f); // facing +x
        Vector3f chest = new Vector3f(FEET).add(0f, MenuShowcase.PREVIEW_LOOK_HEIGHT, 0f);
        assertTrue(orbit.loop());
        for (float s : new float[]{0f, 30f, 61f, 119f}) {
            CameraFrame f = orbit.evaluate(s, 0f);
            assertNear(chest, f.target(), "the orbit looks at the chest");
            float horizontal = (float) Math.hypot(f.eye().x - chest.x, f.eye().z - chest.z);
            assertEquals(MenuShowcase.PREVIEW_ORBIT_RADIUS, horizontal, 1e-2f);
            assertEquals(MenuShowcase.PREVIEW_ORBIT_HEIGHT, f.eye().y - chest.y, 1e-2f);
            assertEquals(MenuShowcase.PREVIEW_ORBIT_DISTANCE, f.eye().distance(chest), 1e-2f,
                "the camera keeps its distance from the player");
        }
        CameraFrame first = orbit.evaluate(0f, 0f);
        assertTrue(first.eye().x > chest.x, "the first frame is in front of the player, who faces +x");
        assertNear(first.eye(), orbit.evaluate(MenuShowcase.PREVIEW_ORBIT_SECONDS, 0f).eye(), "one lap loops seamlessly");
    }

    private static void assertNear(Vector3f expected, Vector3f actual, String what) {
        assertTrue(expected.distance(actual) < 1e-2f, what + ": expected " + expected + ", was " + actual);
    }

    private static void assertDirection(CameraFrame expected, CameraFrame actual, String what) {
        Vector3f a = new Vector3f(expected.target()).sub(expected.eye()).normalize();
        Vector3f b = new Vector3f(actual.target()).sub(actual.eye()).normalize();
        assertTrue(a.dot(b) > 0.9999f, what + ": expected " + a + ", was " + b);
    }
}
