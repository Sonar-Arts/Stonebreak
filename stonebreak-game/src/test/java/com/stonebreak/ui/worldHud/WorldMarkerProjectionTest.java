package com.stonebreak.ui.worldHud;

import com.stonebreak.rendering.UI.components.WorldMarker;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The world HUD's projection contract (#301): {@code proj × view} to window device pixels, nothing
 * behind the camera or outside the view, no occlusion test.
 */
class WorldMarkerProjectionTest {

    private static final Matrix4f VP = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.1f, 500f)
        .mul(new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0));

    @Test
    void aPointStraightAheadLandsOnTheWindowCentre() {
        assertArrayEquals(new float[]{960f, 540f}, WorldMarker.project(VP, 0, 0, -10, 1920, 1080), 1e-3f);
    }

    @Test
    void pointsBehindTheCameraOrOutsideTheViewAreLeftOut() {
        assertNull(WorldMarker.project(VP, 0, 0, 10, 1920, 1080), "behind");
        assertNull(WorldMarker.project(VP, 50, 0, -10, 1920, 1080), "far right");
        assertNull(WorldMarker.project(VP, 0, -50, -10, 1920, 1080), "below");
    }
}
