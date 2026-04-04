package com.openmason.main.systems.rendering.model.gmr.uv;

import com.openmason.main.systems.rendering.model.UVMode;
import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;

/**
 * Interface for generating UV texture coordinates.
 *
 * <p>Implementations handle the mapping between 3D geometry and 2D texture space.
 * The primary method is {@link #generatePerFaceUVs}, which computes UVs from
 * per-face texture mappings for arbitrary geometry. The legacy mode-based methods
 * are deprecated and exist only for backwards compatibility with cube-specific code.
 *
 * @see PerFaceUVCoordinateGenerator
 * @see FaceTextureMapping
 */
public interface IUVCoordinateGenerator {

    // ── Per-face UV generation (primary API) ─────────────────────────────────

    /**
     * Generate UV coordinates using per-face texture mappings.
     *
     * <p>For each face, looks up its {@link FaceTextureMapping} and interpolates
     * vertex positions within the face's UV region. Supports arbitrary geometry
     * with any face count and layout.
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Implementations that support per-face UVs (e.g. {@link PerFaceUVCoordinateGenerator})
     * must override this method.
     *
     * @param vertices   Vertex positions (x,y,z interleaved)
     * @param indices    Triangle indices
     * @param faceMapper Triangle-to-face mapping
     * @return UV coordinates array (u,v interleaved), length = (vertices.length / 3) * 2
     * @throws UnsupportedOperationException if this implementation does not support per-face UVs
     */
    default float[] generatePerFaceUVs(float[] vertices, int[] indices, ITriangleFaceMapper faceMapper) {
        throw new UnsupportedOperationException(
            "Per-face UV generation not supported by " + getClass().getSimpleName());
    }

    // ── Legacy mode-based methods (deprecated) ───────────────────────────────

    /**
     * Generate UV coordinates for the given mode and vertex count.
     *
     * <p><strong>LEGACY:</strong> This method assumes cube topology (6 faces, 24 vertices).
     * Should be replaced with a more flexible texture assignment system.
     *
     * @param mode UV mapping mode (FLAT or CUBE_NET)
     * @param vertexCount Number of vertices to generate UVs for
     * @return UV coordinates array (u,v interleaved), length = vertexCount * 2
     * @deprecated Use {@link #generatePerFaceUVs} instead
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
     * @deprecated Use {@link #generatePerFaceUVs} instead
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
     * @deprecated Use {@link #generatePerFaceUVs} instead
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
     * @deprecated Use {@link #generatePerFaceUVs} instead
     */
    @Deprecated
    float[] getCubeNetFaceBounds(int faceIndex);
}
