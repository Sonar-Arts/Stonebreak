package com.openmason.main.systems.rendering.model.gmr;

/**
 * Shared constants for the GMR (Generic Model Renderer) subsystem.
 *
 * Centralizes epsilon values used across multiple GMR classes to ensure
 * consistent floating-point comparison behavior.
 */
public final class GMRConstants {

    private GMRConstants() {
        // Constants class — no instantiation
    }

    /**
     * Epsilon for vertex position matching.
     * Used when comparing vertex positions to determine if two vertices
     * occupy the same geometric location (e.g., spatial hashing, edge updates).
     */
    public static final float VERTEX_POSITION_EPSILON = 0.0001f;

    /**
     * Epsilon for subdivision endpoint matching.
     * Intentionally larger than VERTEX_POSITION_EPSILON to account for
     * cross-system coordinate tolerance when matching subdivision endpoints
     * against mesh vertices.
     */
    public static final float SUBDIVISION_TOLERANCE = 0.01f;
}
