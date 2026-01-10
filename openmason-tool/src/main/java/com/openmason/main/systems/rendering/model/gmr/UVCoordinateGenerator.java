package com.openmason.main.systems.rendering.model.gmr;

import com.openmason.main.systems.rendering.model.UVMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of IUVCoordinateGenerator.
 *
 * <p><strong>DEPRECATION NOTICE:</strong> This class contains hardcoded cube-specific logic
 * that should not exist. Cubes should receive no special treatment in texture assignment.
 *
 * <p><strong>Future Direction:</strong> Replace with a flexible texture assignment system that:
 * <ul>
 *   <li>Supports arbitrary geometry (not locked to cube topology)</li>
 *   <li>Allows per-face texture atlas coordinate specification</li>
 *   <li>Supports texture wrapping, tiling, and transformation</li>
 *   <li>Eliminates hardcoded layout constants (CUBE_NET_FACE_BOUNDS, etc.)</li>
 * </ul>
 *
 * <p>Current implementation generates UV coordinates for cube-based models with support for
 * flat (per-face) and cube net (unwrapped) layouts. This is legacy functionality only.
 *
 * <p>Face order for standard 24-vertex cubes (LEGACY):
 * Front(0-3), Back(4-7), Left(8-11), Right(12-15), Top(16-19), Bottom(20-23)
 *
 * @deprecated Cube-specific implementation. Will be replaced with flexible texture system.
 */
@Deprecated
public class UVCoordinateGenerator implements IUVCoordinateGenerator {

    private static final Logger logger = LoggerFactory.getLogger(UVCoordinateGenerator.class);

    // Cube net layout constants (64x48 Minecraft-style)
    private static final float V_ROW_1 = 16.0f / 48.0f;  // 0.333...
    private static final float V_ROW_2 = 32.0f / 48.0f;  // 0.666...

    // Face UV bounds for cube net: {u1, v1, u2, v2}
    // Order matches face order: Front, Back, Left, Right, Top, Bottom
    private static final float[][] CUBE_NET_FACE_BOUNDS = {
        {0.25f, V_ROW_1, 0.5f, V_ROW_2},   // Front
        {0.75f, V_ROW_1, 1.0f, V_ROW_2},   // Back
        {0.0f, V_ROW_1, 0.25f, V_ROW_2},   // Left
        {0.5f, V_ROW_1, 0.75f, V_ROW_2},   // Right
        {0.25f, 0.0f, 0.5f, V_ROW_1},      // Top
        {0.25f, V_ROW_2, 0.5f, 1.0f}       // Bottom
    };

    // Flat UV coordinates per face (BL, BR, TR, TL vertex order)
    private static final float[][] FLAT_FACE_UVS = {
        {0, 1, 1, 1, 1, 0, 0, 0},           // Front
        {1, 1, 0, 1, 0, 0, 1, 0},           // Back (mirrored)
        {0, 1, 1, 1, 1, 0, 0, 0},           // Left
        {1, 1, 0, 1, 0, 0, 1, 0},           // Right (mirrored)
        {0, 1, 1, 1, 1, 0, 0, 0},           // Top
        {0, 0, 1, 0, 1, 1, 0, 1}            // Bottom
    };

    // Vertex UV offsets within face bounds: {u_offset, v_offset} where 0=min, 1=max
    // Order: BL, BR, TR, TL
    private static final float[][] VERTEX_UV_OFFSETS = {
        {0, 1}, // BL: u=u1, v=v2
        {1, 1}, // BR: u=u2, v=v2
        {1, 0}, // TR: u=u2, v=v1
        {0, 0}  // TL: u=u1, v=v1
    };

    /**
     * @deprecated Assumes cube topology. Replace with per-face texture coordinate specification.
     */
    @Override
    @Deprecated
    public float[] generateUVs(UVMode mode, int vertexCount) {
        if (mode == null) {
            logger.warn("UV mode is null, defaulting to FLAT");
            return generateFlatUVs(vertexCount);
        }

        return switch (mode) {
            case FLAT -> generateFlatUVs(vertexCount);
            case CUBE_NET -> generateCubeNetUVs(vertexCount);
        };
    }

    /**
     * @deprecated Hardcoded cube-specific UV generation. No special treatment for cubes
     *             should exist. Replace with flexible per-face texture assignment.
     */
    @Override
    @Deprecated
    public float[] generateFlatUVs(int vertexCount) {
        float[] texCoords = new float[vertexCount * 2];

        for (int i = 0; i < vertexCount; i++) {
            int faceIndex = i / 4;  // Which face (0-5 for standard cube)
            int vertInFace = i % 4; // Which vertex in face (0-3)

            if (faceIndex < 6) {
                texCoords[i * 2] = FLAT_FACE_UVS[faceIndex][vertInFace * 2];
                texCoords[i * 2 + 1] = FLAT_FACE_UVS[faceIndex][vertInFace * 2 + 1];
            } else {
                // Extra vertices from subdivision - use center
                texCoords[i * 2] = 0.5f;
                texCoords[i * 2 + 1] = 0.5f;
            }
        }

        return texCoords;
    }

    /**
     * @deprecated Hardcoded cube-specific UV generation with Minecraft-style 64x48 layout.
     *             No special treatment for cubes should exist. Replace with flexible
     *             per-face texture assignment using atlas coordinates.
     */
    @Override
    @Deprecated
    public float[] generateCubeNetUVs(int vertexCount) {
        float[] texCoords = new float[vertexCount * 2];

        for (int i = 0; i < vertexCount; i++) {
            int faceIndex = i / 4;
            int vertInFace = i % 4;

            if (faceIndex < 6) {
                float[] bounds = CUBE_NET_FACE_BOUNDS[faceIndex];
                float[] offsets = VERTEX_UV_OFFSETS[vertInFace];

                float u = bounds[0] + offsets[0] * (bounds[2] - bounds[0]);
                float v = bounds[1] + offsets[1] * (bounds[3] - bounds[1]);

                texCoords[i * 2] = u;
                texCoords[i * 2 + 1] = v;
            } else {
                // Extra vertices from subdivision - use center of cube net
                texCoords[i * 2] = 0.375f;
                texCoords[i * 2 + 1] = 0.5f;
            }
        }

        return texCoords;
    }

    /**
     * @deprecated Hardcoded cube face bounds. No special treatment for cubes should exist.
     *             Replace with texture atlas coordinate lookup for arbitrary faces.
     */
    @Override
    @Deprecated
    public float[] getCubeNetFaceBounds(int faceIndex) {
        if (faceIndex < 0 || faceIndex >= CUBE_NET_FACE_BOUNDS.length) {
            return null;
        }
        return CUBE_NET_FACE_BOUNDS[faceIndex].clone();
    }

    /**
     * Generate cube net UV coordinates for ModelPart creation.
     * Uses the same layout as generateCubeNetUVs but returns the specific
     * UV array format expected by ModelPart (matching vertex order).
     *
     * <p><strong>LEGACY:</strong> Hardcoded 24-vertex cube layout. No special treatment
     * for cubes should exist in future texture systems.
     *
     * @return UV coordinates array for 24-vertex cube
     * @deprecated Cube-specific method. Replace with flexible texture coordinate specification.
     */
    @Deprecated
    public float[] generateCubeNetTexCoords() {
        return new float[] {
            // FRONT face UVs (vertices 0-3)
            CUBE_NET_FACE_BOUNDS[0][0], CUBE_NET_FACE_BOUNDS[0][3],  // BL
            CUBE_NET_FACE_BOUNDS[0][2], CUBE_NET_FACE_BOUNDS[0][3],  // BR
            CUBE_NET_FACE_BOUNDS[0][2], CUBE_NET_FACE_BOUNDS[0][1],  // TR
            CUBE_NET_FACE_BOUNDS[0][0], CUBE_NET_FACE_BOUNDS[0][1],  // TL

            // BACK face UVs (vertices 4-7)
            CUBE_NET_FACE_BOUNDS[1][0], CUBE_NET_FACE_BOUNDS[1][3],
            CUBE_NET_FACE_BOUNDS[1][2], CUBE_NET_FACE_BOUNDS[1][3],
            CUBE_NET_FACE_BOUNDS[1][2], CUBE_NET_FACE_BOUNDS[1][1],
            CUBE_NET_FACE_BOUNDS[1][0], CUBE_NET_FACE_BOUNDS[1][1],

            // LEFT face UVs (vertices 8-11)
            CUBE_NET_FACE_BOUNDS[2][0], CUBE_NET_FACE_BOUNDS[2][3],
            CUBE_NET_FACE_BOUNDS[2][2], CUBE_NET_FACE_BOUNDS[2][3],
            CUBE_NET_FACE_BOUNDS[2][2], CUBE_NET_FACE_BOUNDS[2][1],
            CUBE_NET_FACE_BOUNDS[2][0], CUBE_NET_FACE_BOUNDS[2][1],

            // RIGHT face UVs (vertices 12-15)
            CUBE_NET_FACE_BOUNDS[3][0], CUBE_NET_FACE_BOUNDS[3][3],
            CUBE_NET_FACE_BOUNDS[3][2], CUBE_NET_FACE_BOUNDS[3][3],
            CUBE_NET_FACE_BOUNDS[3][2], CUBE_NET_FACE_BOUNDS[3][1],
            CUBE_NET_FACE_BOUNDS[3][0], CUBE_NET_FACE_BOUNDS[3][1],

            // TOP face UVs (vertices 16-19)
            CUBE_NET_FACE_BOUNDS[4][0], CUBE_NET_FACE_BOUNDS[4][3],
            CUBE_NET_FACE_BOUNDS[4][2], CUBE_NET_FACE_BOUNDS[4][3],
            CUBE_NET_FACE_BOUNDS[4][2], CUBE_NET_FACE_BOUNDS[4][1],
            CUBE_NET_FACE_BOUNDS[4][0], CUBE_NET_FACE_BOUNDS[4][1],

            // BOTTOM face UVs (vertices 20-23)
            CUBE_NET_FACE_BOUNDS[5][0], CUBE_NET_FACE_BOUNDS[5][1],
            CUBE_NET_FACE_BOUNDS[5][2], CUBE_NET_FACE_BOUNDS[5][1],
            CUBE_NET_FACE_BOUNDS[5][2], CUBE_NET_FACE_BOUNDS[5][3],
            CUBE_NET_FACE_BOUNDS[5][0], CUBE_NET_FACE_BOUNDS[5][3]
        };
    }

    /**
     * Generate flat UV coordinates for ModelPart creation.
     * Each face maps to the full 0-1 UV range.
     *
     * <p><strong>LEGACY:</strong> Hardcoded 24-vertex cube layout. No special treatment
     * for cubes should exist in future texture systems.
     *
     * @return UV coordinates array for 24-vertex cube
     * @deprecated Cube-specific method. Replace with flexible texture coordinate specification.
     */
    @Deprecated
    public float[] generateFlatTexCoords() {
        return new float[] {
            // FRONT face UVs (vertices 0-3)
            0.0f, 1.0f,  // BL
            1.0f, 1.0f,  // BR
            1.0f, 0.0f,  // TR
            0.0f, 0.0f,  // TL

            // BACK face UVs (vertices 4-7) - mirrored
            1.0f, 1.0f,
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,

            // LEFT face UVs (vertices 8-11)
            0.0f, 1.0f,
            1.0f, 1.0f,
            1.0f, 0.0f,
            0.0f, 0.0f,

            // RIGHT face UVs (vertices 12-15) - mirrored
            1.0f, 1.0f,
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,

            // TOP face UVs (vertices 16-19)
            0.0f, 1.0f,
            1.0f, 1.0f,
            1.0f, 0.0f,
            0.0f, 0.0f,

            // BOTTOM face UVs (vertices 20-23)
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f
        };
    }
}
