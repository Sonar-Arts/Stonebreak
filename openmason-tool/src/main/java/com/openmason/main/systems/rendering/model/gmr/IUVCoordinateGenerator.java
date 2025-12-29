package com.openmason.main.systems.rendering.model.gmr;

import com.openmason.main.systems.rendering.model.UVMode;

/**
 * Interface for generating UV texture coordinates.
 * Supports different UV mapping modes for cube-based models.
 *
 * Implementations handle the mapping between 3D geometry and 2D texture space,
 * supporting both flat (per-face) and cube net (unwrapped) layouts.
 */
public interface IUVCoordinateGenerator {

    /**
     * Generate UV coordinates for the given mode and vertex count.
     *
     * @param mode UV mapping mode (FLAT or CUBE_NET)
     * @param vertexCount Number of vertices to generate UVs for
     * @return UV coordinates array (u,v interleaved), length = vertexCount * 2
     */
    float[] generateUVs(UVMode mode, int vertexCount);

    /**
     * Generate cube net UV coordinates (64x48 Minecraft-style layout).
     * Each face maps to a specific region of the texture.
     *
     * @param vertexCount Number of vertices (typically 24 for a cube)
     * @return UV coordinates for cube net layout
     */
    float[] generateCubeNetUVs(int vertexCount);

    /**
     * Generate flat UV coordinates (entire texture per face).
     * Each face maps to the full 0-1 UV range.
     *
     * @param vertexCount Number of vertices (typically 24 for a cube)
     * @return UV coordinates for flat layout
     */
    float[] generateFlatUVs(int vertexCount);

    /**
     * Get the UV bounds for a specific face in cube net mode.
     * Face order: Front(0), Back(1), Left(2), Right(3), Top(4), Bottom(5)
     *
     * @param faceIndex Face index (0-5)
     * @return UV bounds as {u1, v1, u2, v2}, or null if invalid
     */
    float[] getCubeNetFaceBounds(int faceIndex);
}
