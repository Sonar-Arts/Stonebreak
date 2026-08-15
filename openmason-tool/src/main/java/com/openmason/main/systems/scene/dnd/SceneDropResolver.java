package com.openmason.main.systems.scene.dnd;

import com.openmason.engine.rendering.viewer.math.CoordinateSystem;
import com.openmason.engine.rendering.viewer.math.RaycastUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Where a model dropped into the viewport should land.
 *
 * <p>Casts a ray through the cursor onto the ground plane, so a model lands where the
 * user pointed rather than always at the origin.
 */
public final class SceneDropResolver {

    /**
     * Furthest a drop may land from the camera. A ray aimed near the horizon meets the
     * ground plane at an enormous distance; without this a small aiming error could put
     * the instance kilometres away, off-screen and hard to find.
     */
    public static final float MAX_PLACEMENT_DISTANCE = 500.0f;

    private SceneDropResolver() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * @param localX            cursor X relative to the viewport image
     * @param localY            cursor Y relative to the viewport image
     * @param fallbackDistance  how far along the ray to place when the ground plane is
     *                          unusable (camera level with or looking away from it)
     * @return the world position to place at; never null
     */
    public static Vector3f resolve(float localX, float localY,
                                   int viewportWidth, int viewportHeight,
                                   Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                   float groundY, float fallbackDistance) {
        CoordinateSystem.Ray ray = CoordinateSystem.createWorldRayFromScreen(
                localX, localY, viewportWidth, viewportHeight, viewMatrix, projectionMatrix);

        float t = RaycastUtil.intersectRayPlane(ray, new Vector3f(0, groundY, 0), new Vector3f(0, 1, 0));
        if (t > 0.0f && t < MAX_PLACEMENT_DISTANCE && Float.isFinite(t)) {
            return ray.getPoint(t);
        }

        // Degenerate aim: keep the drop in front of the camera at ground level rather
        // than refusing it. The grid origin is the last resort, not the rule.
        Vector3f fallback = ray.getPoint(fallbackDistance);
        fallback.y = groundY;
        return fallback;
    }
}
