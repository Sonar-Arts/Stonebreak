package com.openmason.main.systems.rendering.model.miscComponents;

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
    private static final int TEXTURE_HEIGHT = 48;

    // No UV inset needed with GL_NEAREST filtering (no interpolation = no bleeding)
    // This gives full 16x16 pixel coverage per face with no edge cutoff
    private static final float UV_INSET_U = 0.0f;  // No inset - full pixel coverage
    private static final float UV_INSET_V = 0.0f;  // No inset - full pixel coverage

    // Precise V-coordinate calculations for 64x48 cube net
    // Row 0: Y = 0 to 16 (top face)
    // Row 1: Y = 16 to 32 (left, front, right, back faces)
    // Row 2: Y = 32 to 48 (bottom face)
    private static final float V_ROW_1 = 16.0f / (float)TEXTURE_HEIGHT;  // 0.333333... (row 1 starts)
    private static final float V_ROW_2 = 32.0f / (float)TEXTURE_HEIGHT;  // 0.666666... (row 2 starts)

    // Mesh properties
    private static final int VERTICES_PER_FACE = 4;
    private static final int FACES = 6;
    private static final int INDICES_PER_FACE = 6;  // 2 triangles * 3 vertices
    private static final int TOTAL_INDICES = INDICES_PER_FACE * FACES;

    // UV coordinates for each face in 64x48 cube net with inset applied
    // Format: {u1, v1, u2, v2} (top-left to bottom-right in texture space)
    // Now using precise V calculations instead of 0.333f and 0.667f
    private static final float[] TOP_UV = {
        0.25f + UV_INSET_U,
        0.0f + UV_INSET_V,
        0.5f - UV_INSET_U,
        V_ROW_1 - UV_INSET_V    // Precise: 16/48 instead of 0.333f
    };
    private static final float[] LEFT_UV = {
        0.0f + UV_INSET_U,
        V_ROW_1 + UV_INSET_V,   // Precise: 16/48 instead of 0.333f
        0.25f - UV_INSET_U,
        V_ROW_2 - UV_INSET_V    // Precise: 32/48 instead of 0.667f
    };
    private static final float[] FRONT_UV = {
        0.25f + UV_INSET_U,
        V_ROW_1 + UV_INSET_V,   // Precise: 16/48 instead of 0.333f
        0.5f - UV_INSET_U,
        V_ROW_2 - UV_INSET_V    // Precise: 32/48 instead of 0.667f
    };
    private static final float[] RIGHT_UV = {
        0.5f + UV_INSET_U,
        V_ROW_1 + UV_INSET_V,   // Precise: 16/48 instead of 0.333f
        0.75f - UV_INSET_U,
        V_ROW_2 - UV_INSET_V    // Precise: 32/48 instead of 0.667f
    };
    private static final float[] BACK_UV = {
        0.75f + UV_INSET_U,
        V_ROW_1 + UV_INSET_V,   // Precise: 16/48 instead of 0.333f
        1.0f - UV_INSET_U,
        V_ROW_2 - UV_INSET_V    // Precise: 32/48 instead of 0.667f
    };
    private static final float[] BOTTOM_UV = {
        0.25f + UV_INSET_U,
        V_ROW_2 + UV_INSET_V,   // Precise: 32/48 instead of 0.667f
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
            // Fixed: UV v1/v2 swapped to match game orientation (top of texture at -Z back)
            -0.5f, -0.5f, -0.5f,  BOTTOM_UV[0], BOTTOM_UV[1], // back-left (top-left)
             0.5f, -0.5f, -0.5f,  BOTTOM_UV[2], BOTTOM_UV[1], // back-right (top-right)
             0.5f, -0.5f,  0.5f,  BOTTOM_UV[2], BOTTOM_UV[3], // front-right (bottom-right)
            -0.5f, -0.5f,  0.5f,  BOTTOM_UV[0], BOTTOM_UV[3], // front-left (bottom-left)

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
            indices[idx++] = baseVertex;
            indices[idx++] = baseVertex + 1;
            indices[idx++] = baseVertex + 2;

            // Triangle 2: vertices 2, 3, 0
            indices[idx++] = baseVertex + 2;
            indices[idx++] = baseVertex + 3;
            indices[idx++] = baseVertex;
        }

        return indices;
    }
}
