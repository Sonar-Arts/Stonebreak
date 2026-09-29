package com.stonebreak.rendering.models.entities;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The UI preview key light (issue #303) is fixed relative to the viewer, so an
 * orbiting preview camera always sees the same lit side regardless of angle —
 * and nothing in it depends on the world's time of day.
 */
class UiPreviewLightingTest {

    private static Matrix4f orbitView(float azimuth, float elevation, float dist) {
        float horiz = dist * (float) Math.cos(elevation);
        return new Matrix4f().setLookAt(
                horiz * (float) Math.sin(azimuth), dist * (float) Math.sin(elevation),
                horiz * (float) Math.cos(azimuth), 0f, 1f, 0f, 0f, 1f, 0f);
    }

    private static Vector3f keyLight(Matrix4f view) {
        return SbeEntityRenderer.uiPreviewKeyLight(view, new Matrix4f(), new Vector3f());
    }

    @Test
    void keyLightKeepsTheSameAngleToTheViewerAsTheCameraOrbits() {
        float elevation = (float) Math.toRadians(12.0);
        Float expectedFacing = null;
        for (int step = 0; step < 16; step++) {
            float azimuth = (float) (step * Math.PI / 8.0);
            Matrix4f view = orbitView(azimuth, elevation, 4f);
            Vector3f light = keyLight(view);
            Vector3f toCamera = new Matrix4f(view).invert().getTranslation(new Vector3f())
                    .sub(0f, 1f, 0f).normalize();

            assertEquals(1f, light.length(), 1e-4f, "key light must be unit length");
            float facing = light.dot(toCamera);
            assertTrue(facing > 0.5f, "key light should come from the viewer's side, was " + facing);
            if (expectedFacing == null) {
                expectedFacing = facing;
            } else {
                assertEquals(expectedFacing, facing, 1e-4f, "lit side drifted at azimuth step " + step);
            }
            assertTrue(light.y > 0f, "key light should come from above");
        }
    }

    @Test
    void nullViewFallsBackToTheCameraSpaceDirection() {
        Vector3f light = SbeEntityRenderer.uiPreviewKeyLight(null, new Matrix4f(), new Vector3f());
        assertEquals(1f, light.length(), 1e-4f);
        assertTrue(light.y > 0f && light.z > 0f);
    }

    @Test
    void previewAmbientIsFullyLit() {
        assertEquals(1f, SbeEntityRenderer.UI_PREVIEW_AMBIENT);
    }
}
