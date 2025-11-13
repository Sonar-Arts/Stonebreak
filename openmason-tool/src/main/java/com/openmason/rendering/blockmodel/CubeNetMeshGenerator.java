package com.openmason.rendering.blockmodel;

/**
 * Generates cube meshes with proper UV mapping for 64x48 cube net textures.
 *
 * <p>Cube Net Layout (64x48 pixels):
 * <pre>
 * Column: 0      1       2       3
 * Row 0:  [ ]   [TOP]   [ ]     [ ]
 * Row 1:  [LEFT][FRONT][RIGHT] [BACK]
 * Row 2:  [ ]   [BOTTOM][ ]     [ ]
 * </pre>
 *
 * <p>Each face is 16x16 pixels:
 * <ul>
 *   <li>TOP: (16, 0) to (32, 16) → UV: (0.25, 0.0) to (0.5, 0.333)</li>
 *   <li>LEFT: (0, 16) to (16, 32) → UV: (0.0, 0.333) to (0.25, 0.667)</li>
 *   <li>FRONT: (16, 16) to (32, 32) → UV: (0.25, 0.333) to (0.5, 0.667)</li>
 *   <li>RIGHT: (32, 16) to (48, 32) → UV: (0.5, 0.333) to (0.75, 0.667)</li>
 *   <li>BACK: (48, 16) to (64, 32) → UV: (0.75, 0.333) to (1.0, 0.667)</li>
 *   <li>BOTTOM: (16, 32) to (32, 48) → UV: (0.25, 0.667) to (0.5, 1.0)</li>
 * </ul>
 *
 * <p>Face orientation follows standard OpenGL conventions:
 * <ul>
 *   <li>Front face: +Z direction</li>
 *   <li>Back face: -Z direction</li>
 *   <li>Right face: +X direction</li>
 *   <li>Left face: -X direction</li>
 *   <li>Top face: +Y direction</li>
 *   <li>Bottom face: -Y direction</li>
 * </ul>
 *
 * <p>UV coordinates follow OpenGL convention where (0,0) is bottom-left and (1,1) is top-right.
 * Textures are expected to be loaded with vertical flip to match this convention.
 *
 * <p>Design Principles:
 * <ul>
 *   <li>DRY: Single source of truth for cube net mesh generation</li>
 *   <li>KISS: Simple, focused utility class</li>
 *   <li>SOLID: Single responsibility - only generates cube net meshes</li>
 * </ul>
 *
 * @since 1.0
 */
public class CubeNetMeshGenerator {

    // Cube net dimensions (standard for Minecraft-style cube nets)
    private static final int TEXTURE_WIDTH = 64;
    private static final int TEXTURE_HEIGHT = 48;
    private static final int FACE_SIZE = 16;

    // UV inset to prevent texture bleeding (half pixel in each direction)
    // This pulls UV coordinates slightly inward from pixel boundaries
    private static final float UV_INSET_U = 0.5f / TEXTURE_WIDTH;   // 0.0078125
    private static final float UV_INSET_V = 0.5f / TEXTURE_HEIGHT;  // 0.0104167

    // Mesh properties
    private static final int VERTICES_PER_FACE = 4;
    private static final int FACES = 6;
    private static final int TOTAL_VERTICES = VERTICES_PER_FACE * FACES;
    private static final int FLOATS_PER_VERTEX = 5; // x, y, z, u, v
    private static final int INDICES_PER_FACE = 6;  // 2 triangles * 3 vertices
    private static final int TOTAL_INDICES = INDICES_PER_FACE * FACES;

    // UV coordinates for each face in 64x48 cube net with inset applied
    // Format: {u1, v1, u2, v2} (top-left to bottom-right in texture space)
    // Inset prevents texture bleeding by avoiding exact pixel boundaries
    private static final float[] TOP_UV = {
        0.25f + UV_INSET_U,
        0.0f + UV_INSET_V,
        0.5f - UV_INSET_U,
        0.333f - UV_INSET_V
    };
    private static final float[] LEFT_UV = {
        0.0f + UV_INSET_U,
        0.333f + UV_INSET_V,
        0.25f - UV_INSET_U,
        0.667f - UV_INSET_V
    };
    private static final float[] FRONT_UV = {
        0.25f + UV_INSET_U,
        0.333f + UV_INSET_V,
        0.5f - UV_INSET_U,
        0.667f - UV_INSET_V
    };
    private static final float[] RIGHT_UV = {
        0.5f + UV_INSET_U,
        0.333f + UV_INSET_V,
        0.75f - UV_INSET_U,
        0.667f - UV_INSET_V
    };
    private static final float[] BACK_UV = {
        0.75f + UV_INSET_U,
        0.333f + UV_INSET_V,
        1.0f - UV_INSET_U,
        0.667f - UV_INSET_V
    };
    private static final float[] BOTTOM_UV = {
        0.25f + UV_INSET_U,
        0.667f + UV_INSET_V,
        0.5f - UV_INSET_U,
        1.0f - UV_INSET_V
    };

    /**
     * Generates cube vertices with positions and UV coordinates for cube net texture.
     * Cube is 1x1x1 centered at origin.
     *
     * <p>Vertex format: x, y, z, u, v (5 floats per vertex)
     * <p>Total vertices: 24 (4 per face * 6 faces)
     * <p>Total floats: 120 (24 * 5)
     *
     * @return vertex data array with positions and UV coordinates
     */
    public static float[] generateVertices() {
        return new float[] {
            // FRONT face (facing +Z) - 4 vertices
            // Uses FRONT texture from cube net (16,16) to (32,32)
            -0.5f, -0.5f,  0.5f,  FRONT_UV[0], FRONT_UV[3],  // bottom-left
             0.5f, -0.5f,  0.5f,  FRONT_UV[2], FRONT_UV[3],  // bottom-right
             0.5f,  0.5f,  0.5f,  FRONT_UV[2], FRONT_UV[1],  // top-right
            -0.5f,  0.5f,  0.5f,  FRONT_UV[0], FRONT_UV[1],  // top-left

            // BACK face (facing -Z) - 4 vertices
            // Uses BACK texture from cube net (48,16) to (64,32)
             0.5f, -0.5f, -0.5f,  BACK_UV[0], BACK_UV[3],   // bottom-left (flipped for back face)
            -0.5f, -0.5f, -0.5f,  BACK_UV[2], BACK_UV[3],   // bottom-right
            -0.5f,  0.5f, -0.5f,  BACK_UV[2], BACK_UV[1],   // top-right
             0.5f,  0.5f, -0.5f,  BACK_UV[0], BACK_UV[1],   // top-left

            // TOP face (facing +Y) - 4 vertices
            // Uses TOP texture from cube net (16,0) to (32,16)
            -0.5f,  0.5f,  0.5f,  TOP_UV[0], TOP_UV[1],     // front-left
             0.5f,  0.5f,  0.5f,  TOP_UV[2], TOP_UV[1],     // front-right
             0.5f,  0.5f, -0.5f,  TOP_UV[2], TOP_UV[3],     // back-right
            -0.5f,  0.5f, -0.5f,  TOP_UV[0], TOP_UV[3],     // back-left

            // BOTTOM face (facing -Y) - 4 vertices
            // Uses BOTTOM texture from cube net (16,32) to (32,48)
            -0.5f, -0.5f, -0.5f,  BOTTOM_UV[0], BOTTOM_UV[3], // back-left
             0.5f, -0.5f, -0.5f,  BOTTOM_UV[2], BOTTOM_UV[3], // back-right
             0.5f, -0.5f,  0.5f,  BOTTOM_UV[2], BOTTOM_UV[1], // front-right
            -0.5f, -0.5f,  0.5f,  BOTTOM_UV[0], BOTTOM_UV[1], // front-left

            // RIGHT face (facing +X) - 4 vertices
            // Uses RIGHT texture from cube net (32,16) to (48,32)
             0.5f, -0.5f,  0.5f,  RIGHT_UV[0], RIGHT_UV[3],  // bottom-front
             0.5f, -0.5f, -0.5f,  RIGHT_UV[2], RIGHT_UV[3],  // bottom-back
             0.5f,  0.5f, -0.5f,  RIGHT_UV[2], RIGHT_UV[1],  // top-back
             0.5f,  0.5f,  0.5f,  RIGHT_UV[0], RIGHT_UV[1],  // top-front

            // LEFT face (facing -X) - 4 vertices
            // Uses LEFT texture from cube net (0,16) to (16,32)
            -0.5f, -0.5f, -0.5f,  LEFT_UV[0], LEFT_UV[3],   // bottom-back
            -0.5f, -0.5f,  0.5f,  LEFT_UV[2], LEFT_UV[3],   // bottom-front
            -0.5f,  0.5f,  0.5f,  LEFT_UV[2], LEFT_UV[1],   // top-front
            -0.5f,  0.5f, -0.5f,  LEFT_UV[0], LEFT_UV[1]    // top-back
        };
    }

    /**
     * Generates cube indices for indexed drawing.
     * Each face is rendered as 2 triangles (6 indices).
     *
     * <p>Triangle winding order is counter-clockwise for front-facing polygons.
     * <p>Total indices: 36 (6 per face * 6 faces)
     *
     * @return index array for drawing triangles
     */
    public static int[] generateIndices() {
        int[] indices = new int[TOTAL_INDICES];
        int idx = 0;

        for (int face = 0; face < FACES; face++) {
            int baseVertex = face * VERTICES_PER_FACE;

            // Triangle 1: vertices 0, 1, 2
            indices[idx++] = baseVertex + 0;
            indices[idx++] = baseVertex + 1;
            indices[idx++] = baseVertex + 2;

            // Triangle 2: vertices 2, 3, 0
            indices[idx++] = baseVertex + 2;
            indices[idx++] = baseVertex + 3;
            indices[idx++] = baseVertex + 0;
        }

        return indices;
    }

    /**
     * Gets the number of vertices per face.
     * @return vertices per face (always 4)
     */
    public static int getVerticesPerFace() {
        return VERTICES_PER_FACE;
    }

    /**
     * Gets the total number of vertices in the cube mesh.
     * @return total vertices (24 = 4 per face * 6 faces)
     */
    public static int getTotalVertices() {
        return TOTAL_VERTICES;
    }

    /**
     * Gets the number of floats per vertex.
     * @return floats per vertex (5 = position XYZ + texture UV)
     */
    public static int getFloatsPerVertex() {
        return FLOATS_PER_VERTEX;
    }

    /**
     * Gets the total number of indices.
     * @return total indices (36 = 6 per face * 6 faces)
     */
    public static int getTotalIndices() {
        return TOTAL_INDICES;
    }

    /**
     * Gets the number of indices per face.
     * @return indices per face (6 = 2 triangles * 3 vertices)
     */
    public static int getIndicesPerFace() {
        return INDICES_PER_FACE;
    }
}
