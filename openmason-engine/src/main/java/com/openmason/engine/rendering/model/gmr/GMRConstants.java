package com.openmason.engine.rendering.model.gmr;

/**
 * Shared constants for the GMR (Generic Model Renderer) subsystem.
 *
 * Single home for every floating-point tolerance used across GMR classes.
 * Do not introduce ad-hoc epsilon literals elsewhere — reference these so
 * comparison behavior stays consistent between welding, mutation, topology,
 * and UV code.
 */
public final class GMRConstants {

    private GMRConstants() {
        // Constants class — no instantiation
    }

    /**
     * Tolerance under which two vertex positions are considered the same
     * geometric location (welding, coincident-vertex updates, spatial hashing).
     */
    public static final float POSITION_EPSILON = 1e-4f;

    /** Squared form of {@link #POSITION_EPSILON} for squared-distance comparisons. */
    public static final float POSITION_EPSILON_SQ = POSITION_EPSILON * POSITION_EPSILON;

    /**
     * Tolerance for matching positions that crossed a system boundary
     * (user input, tool-side picking, position-based public APIs).
     * Intentionally looser than {@link #POSITION_EPSILON}.
     */
    public static final float POSITION_MATCH_EPSILON = 1e-3f;

    /**
     * A normal (or direction) with length below this is treated as degenerate
     * and must not be normalized or used as a frame axis.
     */
    public static final float DEGENERATE_NORMAL_EPSILON = 1e-6f;

    /** Squared form of {@link #DEGENERATE_NORMAL_EPSILON}. */
    public static final float DEGENERATE_NORMAL_EPSILON_SQ =
            DEGENERATE_NORMAL_EPSILON * DEGENERATE_NORMAL_EPSILON;

    /** Minimum UV-region extent below which a texture region is degenerate. */
    public static final float UV_REGION_MIN = 1e-6f;
}
