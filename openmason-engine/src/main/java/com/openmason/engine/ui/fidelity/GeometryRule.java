package com.openmason.engine.ui.fidelity;

/**
 * How closely a migrated screen's rects must match the legacy oracle (#283 contract, #296).
 */
public enum GeometryRule {

    /**
     * Screens whose legacy maths is float centring (pause): 0 px, compared to 0.001 so float
     * noise of a different but exact computation order passes.
     */
    FLOAT_EXACT,

    /**
     * Screens that centre with truncating integer division (furnace): up to 1 px on an axis whose
     * framebuffer size is odd (legacy {@code /2} truncates, Yoga rounds half up), 0 px otherwise.
     */
    INTEGER_CENTRED;

    static final double EPSILON = 0.001;

    /** Allowed absolute difference of an x or width value at framebuffer width {@code size} (same for y). */
    public double tolerance(int framebufferSize) {
        return this == INTEGER_CENTRED && (framebufferSize & 1) == 1 ? 1 + EPSILON : EPSILON;
    }
}
