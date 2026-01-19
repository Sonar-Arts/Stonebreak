package com.openmason.main.systems.rendering.model.gmr.uv;

import com.openmason.main.systems.rendering.model.UVMode;

/**
 * Interface for generating UV texture coordinates.
 *
 * <p><strong>DEPRECATION NOTICE:</strong> This interface contains cube-specific assumptions
 * that should not be hardcoded. Future texture assignment systems should:
 * <ul>
 *   <li>Support arbitrary geometry (not just cubes)</li>
 *   <li>Allow per-face texture specification</li>
 *   <li>Use atlas coordinates instead of hardcoded layouts</li>
 *   <li>Support texture wrapping and tiling modes</li>
 * </ul>
 *
 * <p>Implementations handle the mapping between 3D geometry and 2D texture space,
 * currently supporting flat (per-face) and cube net (unwrapped) layouts for cube-based models.
 */
public interface IUVCoordinateGenerator {

    /**
     * Generate UV coordinates for the given mode and vertex count.
     *
     * <p><strong>LEGACY:</strong> This method assumes cube topology (6 faces, 24 vertices).
     * Should be replaced with a more flexible texture assignment system.
     *
     * @param mode UV mapping mode (FLAT or CUBE_NET)
     * @param vertexCount Number of vertices to generate UVs for
     * @return UV coordinates array (u,v interleaved), length = vertexCount * 2
     * @deprecated Will be replaced with per-face texture coordinate specification in future versions
     */
    @Deprecated
    float[] generateUVs(UVMode mode, int vertexCount);

    /**
     * Generate cube net UV coordinates (64x48 Minecraft-style layout).
     * Each face maps to a specific region of the texture.
     *
     * <p><strong>LEGACY:</strong> Hardcoded cube-specific layout. No special treatment for cubes
     * should exist in future texture systems.
     *
     * @param vertexCount Number of vertices (typically 24 for a cube)
     * @return UV coordinates for cube net layout
     * @deprecated Cube-specific method. Will be replaced with flexible texture assignment system.
     */
    @Deprecated
    float[] generateCubeNetUVs(int vertexCount);

    /**
     * Generate flat UV coordinates (entire texture per face).
     * Each face maps to the full 0-1 UV range.
     *
     * <p><strong>LEGACY:</strong> Assumes cube topology with 6 faces. No special treatment for
     * cubes should exist in future texture systems.
     *
     * @param vertexCount Number of vertices (typically 24 for a cube)
     * @return UV coordinates for flat layout
     * @deprecated Cube-specific method. Will be replaced with flexible texture assignment system.
     */
    @Deprecated
    float[] generateFlatUVs(int vertexCount);

    /**
     * Get the UV bounds for a specific face in cube net mode.
     * Face order: Front(0), Back(1), Left(2), Right(3), Top(4), Bottom(5)
     *
     * <p><strong>LEGACY:</strong> Hardcoded cube face layout. No special treatment for cubes
     * should exist in future texture systems.
     *
     * @param faceIndex Face index (0-5)
     * @return UV bounds as {u1, v1, u2, v2}, or null if invalid
     * @deprecated Cube-specific method. Will be replaced with flexible texture assignment system.
     */
    @Deprecated
    float[] getCubeNetFaceBounds(int faceIndex);
}
