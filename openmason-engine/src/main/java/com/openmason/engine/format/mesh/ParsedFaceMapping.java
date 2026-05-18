package com.openmason.engine.format.mesh;

/**
 * Per-face UV mapping data extracted from an OMO/SBO file.
 *
 * @param faceId             face identifier
 * @param materialId         material this face uses
 * @param u0                 UV region left
 * @param v0                 UV region top
 * @param u1                 UV region right
 * @param v1                 UV region bottom
 * @param uvRotationDegrees  rotation in degrees (0, 90, 180, 270)
 * @param autoResize         editor may auto-rescale on geometry change (default true)
 */
public record ParsedFaceMapping(
        int faceId,
        int materialId,
        float u0, float v0,
        float u1, float v1,
        int uvRotationDegrees,
        boolean autoResize
) {
    /** Backward-compatible constructor: defaults {@code autoResize} to true. */
    public ParsedFaceMapping(int faceId, int materialId,
                             float u0, float v0, float u1, float v1,
                             int uvRotationDegrees) {
        this(faceId, materialId, u0, v0, u1, v1, uvRotationDegrees, true);
    }
}
