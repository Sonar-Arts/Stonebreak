package com.openmason.main.systems.rendering.core.shaders;

/**
 * Enumeration of shader types used in the viewport.
 * Each type represents a different shader program with specific capabilities.
 */
public enum ShaderType {
    /**
     * Basic shader for simple geometry (grid, test cube).
     * Uniforms: uMVPMatrix, uColor
     */
    BASIC,

    /**
     * Advanced shader with per-part transformation matrices and texture support.
     * Used for models with individual part positioning.
     * Uniforms: uMVPMatrix, uModelMatrix, uColor, uTexture, uUseTexture
     */
    MATRIX,

    /**
     * Dedicated shader for transform gizmo rendering (isolated from model pipeline).
     * Uniforms: uMVPMatrix, uColor
     */
    GIZMO,

    /**
     * Infinite grid shader for procedurally generated grid with distance-based fading and atmospheric fog.
     * Uniforms: uViewMatrix, uProjectionMatrix, uCameraPosition, uGridScale,
     *           uGridLineWidth, uFadeDistance, uMaxDistance, uPrimaryColor,
     *           uSecondaryColor, uAxisXColor, uAxisZColor, uFogColor
     */
    INFINITE_GRID,

    /**
     * Face selection shader with alpha transparency support for overlay rendering.
     * Uniforms: uMVPMatrix, uColor, uAlpha
     */
    FACE
}
