package com.openmason.main.systems.rendering.model.gmr.parts;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Immutable local transform for a model part.
 * Represents position offset, rotation (Euler), and scale relative to the part's origin.
 *
 * <p>Transform order: Translate to origin → Scale → Rotate → Translate by position → Translate back from origin.
 *
 * @param origin   Pivot point for rotation and scale
 * @param position Translation offset from origin
 * @param rotation Euler rotation in degrees (X, Y, Z)
 * @param scale    Non-uniform scale factors
 */
public record PartTransform(
        Vector3f origin,
        Vector3f position,
        Vector3f rotation,
        Vector3f scale
) {

    /**
     * Create an identity transform at the given origin.
     * No translation, no rotation, unit scale.
     *
     * @param origin Pivot point
     * @return Identity transform
     */
    public static PartTransform identity(Vector3f origin) {
        return new PartTransform(
                new Vector3f(origin),
                new Vector3f(0, 0, 0),
                new Vector3f(0, 0, 0),
                new Vector3f(1, 1, 1)
        );
    }

    /**
     * Create an identity transform at the world origin (0, 0, 0).
     *
     * @return Identity transform at world origin
     */
    public static PartTransform identity() {
        return identity(new Vector3f(0, 0, 0));
    }

    /**
     * Build a 4x4 transformation matrix from this transform.
     * Order: translate(-origin) → scale → rotateX → rotateY → rotateZ → translate(origin + position)
     *
     * @return Combined transformation matrix
     */
    public Matrix4f toMatrix() {
        return new Matrix4f()
                .translate(origin.x + position.x, origin.y + position.y, origin.z + position.z)
                .rotateX((float) Math.toRadians(rotation.x))
                .rotateY((float) Math.toRadians(rotation.y))
                .rotateZ((float) Math.toRadians(rotation.z))
                .scale(scale.x, scale.y, scale.z)
                .translate(-origin.x, -origin.y, -origin.z);
    }

    /**
     * Check if this transform is the identity (no translation, no rotation, unit scale).
     *
     * @return true if this is effectively an identity transform
     */
    public boolean isIdentity() {
        return position.x == 0 && position.y == 0 && position.z == 0
                && rotation.x == 0 && rotation.y == 0 && rotation.z == 0
                && scale.x == 1 && scale.y == 1 && scale.z == 1;
    }

    /**
     * Create a new transform with the position offset by the given delta.
     *
     * @param delta Translation delta
     * @return New transform with updated position
     */
    public PartTransform withTranslation(Vector3f delta) {
        return new PartTransform(
                new Vector3f(origin),
                new Vector3f(position).add(delta),
                new Vector3f(rotation),
                new Vector3f(scale)
        );
    }

    /**
     * Create a new transform with rotation offset by the given Euler delta (degrees).
     *
     * @param eulerDelta Rotation delta in degrees
     * @return New transform with updated rotation
     */
    public PartTransform withRotation(Vector3f eulerDelta) {
        return new PartTransform(
                new Vector3f(origin),
                new Vector3f(position),
                new Vector3f(rotation).add(eulerDelta),
                new Vector3f(scale)
        );
    }

    /**
     * Create a new transform with scale multiplied by the given factors.
     *
     * @param scaleFactors Scale multipliers per axis
     * @return New transform with updated scale
     */
    public PartTransform withScale(Vector3f scaleFactors) {
        return new PartTransform(
                new Vector3f(origin),
                new Vector3f(position),
                new Vector3f(rotation),
                new Vector3f(scale).mul(scaleFactors)
        );
    }
}
