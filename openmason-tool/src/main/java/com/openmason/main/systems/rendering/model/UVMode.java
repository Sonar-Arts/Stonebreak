package com.openmason.main.systems.rendering.model;

/**
 * Defines UV mapping modes for texture application on 3D models.
 *
 * <p><strong>DEPRECATION NOTICE:</strong> This enum contains hardcoded cube-specific assumptions.
 * A future texture system will replace this with:
 * <ul>
 *   <li>Per-face texture atlas coordinate specification</li>
 *   <li>Support for arbitrary geometry (not cube-locked)</li>
 *   <li>Texture transformation (rotation, scale, offset)</li>
 *   <li>Texture wrapping and tiling modes</li>
 * </ul>
 *
 * <p>Current texture formats (LEGACY):
 * <ul>
 *   <li>CUBE_NET: 64x48 cube net layout with 6 faces arranged in cross pattern</li>
 *   <li>FLAT: Simple 0-1 UV mapping where entire texture is applied to each face</li>
 * </ul>
 *
 * @deprecated Will be replaced with flexible texture assignment system in future texture creator upgrade
 */
@Deprecated
public enum UVMode {
    /**
     * Cube net UV mapping for 64x48 (or proportionally scaled) textures.
     *
     * <p>Layout:
     * <pre>
     * Column: 0      1       2       3
     * Row 0:  [ ]   [TOP]   [ ]     [ ]
     * Row 1:  [LEFT][FRONT][RIGHT] [BACK]
     * Row 2:  [ ]   [BOTTOM][ ]     [ ]
     * </pre>
     */
    CUBE_NET,

    /**
     * Flat UV mapping where the entire texture (0-1) is applied to each face.
     * Used for simple 16x16 or single-image textures.
     */
    FLAT;

    /**
     * Auto-detect UV mode based on texture dimensions.
     *
     * @param width texture width in pixels
     * @param height texture height in pixels
     * @return appropriate UV mode for the texture dimensions
     */
    public static UVMode detectFromDimensions(int width, int height) {
        // Cube net format: 4:3 aspect ratio (64x48, 128x96, 256x192, etc.)
        // Each face is square, layout is 4 columns x 3 rows
        if (width > 0 && height > 0) {
            float aspectRatio = (float) width / height;
            // 64/48 = 1.333... Allow some tolerance
            if (Math.abs(aspectRatio - (4.0f / 3.0f)) < 0.01f) {
                return CUBE_NET;
            }
        }

        // Default to flat for square textures or unknown formats
        return FLAT;
    }
}
