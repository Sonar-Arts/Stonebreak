package com.openmason.ui.viewport.gizmo.interaction;

/**
 * Represents the axis or plane constraint for gizmo transformations.
 * Used to constrain movement, rotation, or scaling to specific axes or planes.
 *
 * <p>Follows SOLID principles by providing a clear, focused enumeration
 * that encapsulates axis constraint behavior.
 */
public enum AxisConstraint {
    /**
     * No constraint - free transformation
     */
    NONE,

    /**
     * Constraint to X axis only (red)
     */
    X,

    /**
     * Constraint to Y axis only (green)
     */
    Y,

    /**
     * Constraint to Z axis only (blue)
     */
    Z,

    /**
     * Constraint to XY plane (horizontal plane)
     */
    XY,

    /**
     * Constraint to XZ plane (ground plane)
     */
    XZ,

    /**
     * Constraint to YZ plane (vertical plane)
     */
    YZ,

    /**
     * Screen-space constraint (for rotation in camera view plane)
     */
    SCREEN;

    /**
     * Check if this constraint is a single axis (X, Y, or Z).
     *
     * @return true if single axis constraint, false otherwise
     */
    public boolean isSingleAxis() {
        return this == X || this == Y || this == Z;
    }

    /**
     * Check if this constraint is a plane (XY, XZ, or YZ).
     *
     * @return true if plane constraint, false otherwise
     */
    public boolean isPlane() {
        return this == XY || this == XZ || this == YZ;
    }

}
