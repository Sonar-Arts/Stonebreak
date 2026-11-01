package com.openmason.engine.rendering.shadow;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Computes per-cascade light-space matrices for cascaded sun shadows.
 *
 * <p>Each cascade fits a texel-snapped orthographic volume around the bounding
 * sphere of a camera-frustum slice. Bounding spheres (rather than tight boxes)
 * keep the ortho extents constant as the camera rotates, and snapping the sphere
 * center to shadow-map texel increments in light space stops shadow edges from
 * shimmering as the camera translates.
 *
 * <p>Stateless apart from scratch vectors; render-thread only.
 */
public final class CascadeCalculator {

    private static final float CASCADE_NEAR = 0.3f;

    // Scratch objects — avoid per-frame allocation.
    private final Matrix4f cameraWorld = new Matrix4f();
    private final Vector3f corner = new Vector3f();
    private final Vector3f centerLight = new Vector3f();
    private final Vector3f lookTarget = new Vector3f();
    private final Vector3f up = new Vector3f();

    /**
     * Recomputes every cascade in {@code cascades}.
     *
     * @param cascades   output array, length {@link ShadowSettings#CASCADE_COUNT}
     * @param viewMatrix camera view matrix
     * @param projMatrix camera perspective projection (fov/aspect are extracted from it)
     * @param sunDirection normalized direction from the scene toward the sun
     * @param settings   shadow tunables
     */
    public void update(ShadowCascade[] cascades, Matrix4f viewMatrix, Matrix4f projMatrix,
                       Vector3f sunDirection, ShadowSettings settings) {
        update(cascades, viewMatrix, projMatrix, sunDirection, settings, null);
    }

    /**
     * Recomputes the cascades selected by {@code updateMask} (null = all). Skipped
     * cascades keep their previous matrices, which stay consistent with the depth
     * already rendered into their map layer — callers that stagger cascade updates
     * across frames must also skip re-rendering the same cascades.
     */
    public void update(ShadowCascade[] cascades, Matrix4f viewMatrix, Matrix4f projMatrix,
                       Vector3f sunDirection, ShadowSettings settings, boolean[] updateMask) {
        // Extract fov/aspect from the projection: m11 = 1/tan(fovY/2), aspect = m11/m00.
        float tanHalfFovY = 1.0f / projMatrix.m11();
        float aspect = projMatrix.m11() / projMatrix.m00();

        viewMatrix.invert(cameraWorld);

        // Light "view" looks along the direction light travels (away from the sun).
        // Degenerate up vector when the sun is at zenith — fall back to +Z.
        lookTarget.set(sunDirection).negate();
        if (Math.abs(sunDirection.y) > 0.99f) {
            up.set(0, 0, 1);
        } else {
            up.set(0, 1, 0);
        }

        float texel;
        for (int i = 0; i < cascades.length; i++) {
            if (updateMask != null && !updateMask[i]) {
                continue;
            }
            ShadowCascade c = cascades[i];
            float near = i == 0 ? CASCADE_NEAR : settings.splitFar(i - 1);
            float far = settings.splitFar(i);
            c.splitFar = far;

            // Bounding sphere of the frustum slice: center on the view axis between
            // the two planes, radius to the far plane's corner. Computing it in view
            // space keeps the radius rotation-invariant.
            float farHalfH = far * tanHalfFovY;
            float farHalfW = farHalfH * aspect;
            float nearHalfH = near * tanHalfFovY;
            float nearHalfW = nearHalfH * aspect;

            // Optimal center depth along the axis minimizing max corner distance.
            float farCornerSq = farHalfW * farHalfW + farHalfH * farHalfH;
            float nearCornerSq = nearHalfW * nearHalfW + nearHalfH * nearHalfH;
            float centerZ = (far * far + farCornerSq - near * near - nearCornerSq)
                    / (2.0f * (far - near));
            centerZ = Math.max(near, Math.min(far, centerZ));
            float radius = (float) Math.sqrt(
                    (far - centerZ) * (far - centerZ) + farCornerSq);
            radius = Math.max(radius, (float) Math.sqrt(
                    (centerZ - near) * (centerZ - near) + nearCornerSq));
            c.radius = radius;

            // Slice center in world space: camera position + forward * centerZ.
            corner.set(0, 0, -centerZ);
            cameraWorld.transformPosition(corner, c.centerWorld);

            c.lightView.setLookAt(
                    0, 0, 0,
                    lookTarget.x, lookTarget.y, lookTarget.z,
                    up.x, up.y, up.z);

            // Snap the light-space center to texel increments so the ortho window
            // moves in whole texels — otherwise shadow edges crawl as the camera pans.
            texel = (2.0f * radius) / settings.resolution();
            c.texelWorldSize = texel;
            c.lightView.transformPosition(c.centerWorld, centerLight);
            float snappedX = (float) Math.floor(centerLight.x / texel) * texel;
            float snappedY = (float) Math.floor(centerLight.y / texel) * texel;

            // In light view space the scene lies along -Z; centerLight.z is negative
            // for points in front. Extend the near plane toward the sun by
            // casterBackup so off-screen casters still write depth.
            float zNear = -centerLight.z - radius - settings.casterBackup();
            float zFar = -centerLight.z + radius;

            c.lightProj.setOrtho(
                    snappedX - radius, snappedX + radius,
                    snappedY - radius, snappedY + radius,
                    zNear, zFar);
            c.lightProj.mul(c.lightView, c.lightViewProj);
        }
    }
}
