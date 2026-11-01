package com.openmason.engine.rendering.shaders;

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
     * Infinite grid shader with continuous power-of-ten LOD, screen-space anti-aliased
     * lines, camera-relative distance fading, and atmospheric fog.
     * Uniforms: uViewMatrix, uProjectionMatrix, uGridScale, uLineWidthPx,
     *           uFadeDistance, uMaxDistance, uMinorColor, uMajorColor,
     *           uAxisXColor, uAxisZColor, uFogColor
     */
    INFINITE_GRID,

    /**
     * Face selection shader with alpha transparency support for overlay rendering.
     * Uniforms: uMVPMatrix, uColor, uAlpha
     */
    FACE,

    /**
     * Vertex point shader rendering circles with outlines via gl_PointCoord.
     * Uniforms: uMVPMatrix, uIntensity, uPointSize
     */
    VERTEX
}
