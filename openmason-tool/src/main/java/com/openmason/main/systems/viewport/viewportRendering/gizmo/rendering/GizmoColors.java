package com.openmason.main.systems.viewport.viewportRendering.gizmo.rendering;

import org.joml.Vector3f;

/**
 * Centralized color constants for the transform gizmo.
 * All axis colors, plane colors, and special colors are defined here
 * to ensure visual consistency across all gizmo modes and geometry classes.
 */
public final class GizmoColors {

    // Axis colors (slightly desaturated for a modern, professional look)
    public static final Vector3f X_AXIS = new Vector3f(0.9f, 0.2f, 0.2f);
    public static final Vector3f Y_AXIS = new Vector3f(0.2f, 0.85f, 0.2f);
    public static final Vector3f Z_AXIS = new Vector3f(0.3f, 0.4f, 0.95f);

    // Plane handle colors (blends of their two axis colors)
    public static final Vector3f XY_PLANE = new Vector3f(0.85f, 0.8f, 0.15f);
    public static final Vector3f XZ_PLANE = new Vector3f(0.8f, 0.2f, 0.75f);
    public static final Vector3f YZ_PLANE = new Vector3f(0.15f, 0.8f, 0.8f);

    // Center / uniform scale color
    public static final Vector3f CENTER = new Vector3f(0.9f, 0.9f, 0.9f);

    private GizmoColors() {
        throw new AssertionError("GizmoColors is a utility class and should not be instantiated");
    }

    /**
     * Returns the axis color for a given index (0=X, 1=Y, 2=Z).
     */
    public static Vector3f axisColor(int index) {
        return switch (index) {
            case 0 -> new Vector3f(X_AXIS);
            case 1 -> new Vector3f(Y_AXIS);
            case 2 -> new Vector3f(Z_AXIS);
            default -> throw new IllegalArgumentException("Axis index must be 0, 1, or 2");
        };
    }

    /**
     * Returns the plane color for a given index (0=XY, 1=XZ, 2=YZ).
     */
    public static Vector3f planeColor(int index) {
        return switch (index) {
            case 0 -> new Vector3f(XY_PLANE);
            case 1 -> new Vector3f(XZ_PLANE);
            case 2 -> new Vector3f(YZ_PLANE);
            default -> throw new IllegalArgumentException("Plane index must be 0, 1, or 2");
        };
    }
}
