package com.openmason.main.systems.rendering.model.gmr.uv;

/**
 * Immutable record storing one face's UV texture assignment.
 *
 * <p>Each face maps to a rectangular region within its assigned material's texture.
 * The {@code uvRegion} defines the texture-space bounds, and {@code uvRotation}
 * allows the region to be oriented for faces with different winding.
 *
 * @param faceId      Stable face identifier (matches topology face IDs)
 * @param materialId  Material this face is assigned to
 * @param uvRegion    Rectangular region within the material's texture (normalized 0..1)
 * @param uvRotation  Rotation applied to the UV mapping
 */
public record FaceTextureMapping(
    int faceId,
    int materialId,
    UVRegion uvRegion,
    UVRotation uvRotation
) {

    /** Full texture region covering the entire 0..1 UV space. */
    public static final UVRegion FULL_REGION = new UVRegion(0.0f, 0.0f, 1.0f, 1.0f);

    /**
     * Rectangular region within a material's texture, defined by two corners.
     *
     * <p>Coordinates are normalized (0..1) within the material texture.
     * {@code (u0, v0)} is the minimum corner, {@code (u1, v1)} is the maximum corner.
     *
     * @param u0 Left edge (minimum U)
     * @param v0 Top edge (minimum V)
     * @param u1 Right edge (maximum U)
     * @param v1 Bottom edge (maximum V)
     */
    public record UVRegion(float u0, float v0, float u1, float v1) {

        /**
         * Width of this region in UV space.
         *
         * @return u1 - u0
         */
        public float width() {
            return u1 - u0;
        }

        /**
         * Height of this region in UV space.
         *
         * @return v1 - v0
         */
        public float height() {
            return v1 - v0;
        }

        /**
         * Compute a proportional sub-region by interpolating within this region.
         *
         * <p>Parametric values in the range 0..1 map to the full extent of
         * this region along each axis.
         *
         * @param tU0 Parametric start along U axis (0..1)
         * @param tV0 Parametric start along V axis (0..1)
         * @param tU1 Parametric end along U axis (0..1)
         * @param tV1 Parametric end along V axis (0..1)
         * @return Sub-region within this region's bounds
         */
        public UVRegion subRegion(float tU0, float tV0, float tU1, float tV1) {
            float w = width();
            float h = height();
            return new UVRegion(
                u0 + tU0 * w,
                v0 + tV0 * h,
                u0 + tU1 * w,
                v0 + tV1 * h
            );
        }
    }

    /**
     * Rotation applied to a face's UV mapping.
     * Supports 90-degree increments for oriented faces.
     */
    public enum UVRotation {
        NONE(0),
        CW_90(90),
        CW_180(180),
        CW_270(270);

        private final int degrees;

        UVRotation(int degrees) {
            this.degrees = degrees;
        }

        /**
         * @return Rotation angle in degrees
         */
        public int degrees() {
            return degrees;
        }

        /**
         * Look up a rotation by degree value.
         *
         * @param degrees Rotation in degrees (0, 90, 180, or 270)
         * @return Matching UVRotation
         * @throws IllegalArgumentException if degrees is not a valid rotation
         */
        public static UVRotation fromDegrees(int degrees) {
            return switch (degrees) {
                case 0 -> NONE;
                case 90 -> CW_90;
                case 180 -> CW_180;
                case 270 -> CW_270;
                default -> throw new IllegalArgumentException(
                    "Invalid UV rotation: " + degrees + " (must be 0, 90, 180, or 270)");
            };
        }
    }

    /**
     * Create a default mapping using the full texture region with no rotation.
     *
     * @param faceId     Face identifier
     * @param materialId Material to assign
     * @return Mapping covering the full (0,0)→(1,1) region
     */
    public static FaceTextureMapping defaultMapping(int faceId, int materialId) {
        return new FaceTextureMapping(faceId, materialId, FULL_REGION, UVRotation.NONE);
    }

    /**
     * Create a mapping derived from a parent face's region after a split.
     *
     * <p>The parametric range {@code (tStart, tEnd)} defines the child's portion
     * along the split axis. For a horizontal split, the U axis is subdivided;
     * for a vertical split, the V axis is subdivided.
     *
     * @param faceId     New face identifier
     * @param parent     Parent mapping to derive from
     * @param tStart     Parametric start of the child's portion (0..1)
     * @param tEnd       Parametric end of the child's portion (0..1)
     * @param horizontal true if split along the U axis, false for V axis
     * @return Mapping with proportional sub-region of the parent
     */
    public static FaceTextureMapping fromParentSplit(int faceId, FaceTextureMapping parent,
                                                      float tStart, float tEnd, boolean horizontal) {
        UVRegion parentRegion = parent.uvRegion();
        UVRegion childRegion;

        if (horizontal) {
            childRegion = parentRegion.subRegion(tStart, 0.0f, tEnd, 1.0f);
        } else {
            childRegion = parentRegion.subRegion(0.0f, tStart, 1.0f, tEnd);
        }

        return new FaceTextureMapping(faceId, parent.materialId(), childRegion, parent.uvRotation());
    }
}
