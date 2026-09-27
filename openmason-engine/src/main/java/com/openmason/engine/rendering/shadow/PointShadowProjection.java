package com.openmason.engine.rendering.shadow;

import org.joml.Matrix4f;
import org.joml.Vector3fc;

/** Six perspective views in +X, -X, +Y, -Y, +Z, -Z order. No GL dependencies. */
public final class PointShadowProjection {
    public static final int FACE_COUNT = 6;
    public static final float NEAR = 0.05f;

    private PointShadowProjection() {}

    public static Matrix4f projection(float radius, Matrix4f out) {
        return out.setPerspective((float) Math.PI / 2f, 1f, NEAR, radius);
    }

    public static Matrix4f view(Vector3fc position, int face, Matrix4f out) {
        float x = position.x(), y = position.y(), z = position.z();
        return switch (face) {
            case 0 -> out.setLookAt(x, y, z, x + 1, y, z, 0, -1, 0);
            case 1 -> out.setLookAt(x, y, z, x - 1, y, z, 0, -1, 0);
            case 2 -> out.setLookAt(x, y, z, x, y + 1, z, 0, 0, 1);
            case 3 -> out.setLookAt(x, y, z, x, y - 1, z, 0, 0, -1);
            case 4 -> out.setLookAt(x, y, z, x, y, z + 1, 0, -1, 0);
            case 5 -> out.setLookAt(x, y, z, x, y, z - 1, 0, -1, 0);
            default -> throw new IllegalArgumentException("Invalid point-shadow face: " + face);
        };
    }
}
