package com.openmason.engine.rendering.viewer.picking;

import com.openmason.engine.rendering.model.ModelBounds;
import com.openmason.engine.rendering.viewer.math.CoordinateSystem;
import com.openmason.engine.rendering.viewer.math.RaycastUtil;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.engine.rendering.viewer.scene.ModelScene;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Optional;

/**
 * Finds which {@link ModelInstance} is under a ray.
 *
 * <p>Broad phase only by default: the ray is transformed into each instance's local space
 * and tested against the model's bounding box. Testing in local space (rather than
 * building a world AABB) keeps the box tight for rotated instances, which is what stops a
 * rotated model from being pickable well outside its silhouette.
 */
public final class ScenePicker {

    /** Pure math, no state — but instantiable so a host can hold one and configure it. */
    public ScenePicker() {
    }

    /** Nearest visible, unlocked instance hit by the ray. */
    public Optional<PickResult> pick(ModelScene scene, CoordinateSystem.Ray worldRay) {
        if (scene == null || worldRay == null) {
            return Optional.empty();
        }

        ModelInstance best = null;
        float bestDistance = Float.POSITIVE_INFINITY;

        for (ModelInstance instance : scene.instances()) {
            if (!instance.isVisible() || instance.isLocked()) {
                continue;
            }

            Matrix4f inverse = new Matrix4f(instance.modelMatrix());
            if (Math.abs(inverse.determinant()) < 1e-9f) {
                continue; // degenerate (zero scale) — nothing to hit
            }
            inverse.invert();

            Vector3f localOrigin = inverse.transformPosition(new Vector3f(worldRay.origin()));
            Vector3f localDirection = inverse.transformDirection(new Vector3f(worldRay.direction()));
            if (localDirection.lengthSquared() < 1e-12f) {
                continue;
            }

            // Direction length changes under a scaled matrix; normalizing keeps the
            // returned t comparable to world-space distances between instances.
            float scaleFactor = localDirection.length();
            localDirection.normalize();

            CoordinateSystem.Ray localRay = new CoordinateSystem.Ray(localOrigin, localDirection);
            ModelBounds bounds = instance.model().bounds();
            float localT = RaycastUtil.intersectRayAABB(localRay, bounds.min(), bounds.max());
            if (Float.isInfinite(localT)) {
                continue;
            }

            float worldT = localT / scaleFactor;
            if (worldT < bestDistance) {
                bestDistance = worldT;
                best = instance;
            }
        }

        if (best == null) {
            return Optional.empty();
        }
        return Optional.of(new PickResult(best, bestDistance, worldRay.getPoint(bestDistance)));
    }

    /** Convenience: build the ray from a viewport pixel, then pick. */
    public Optional<PickResult> pickScreen(ModelScene scene, float screenX, float screenY,
                                           int viewportWidth, int viewportHeight,
                                           Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        CoordinateSystem.Ray ray = CoordinateSystem.createWorldRayFromScreen(
                screenX, screenY, viewportWidth, viewportHeight, viewMatrix, projectionMatrix);
        return pick(scene, ray);
    }
}
