package com.openmason.main.systems.rendering.model.blockmodel;

/**
 * Generates cube mesh with flat texture UV mapping.
 */
public class FlatTextureMeshGenerator {

    private static final int FLOATS_PER_VERTEX = 5; // x, y, z, u, v
    private static final int VERTICES_PER_FACE = 4;

    /**
     * Generates vertex data with flat texture UV mapping.
     */
    public static float[] generateVertices() {
        float[] vertices = new float[6 * VERTICES_PER_FACE * FLOATS_PER_VERTEX];
        int offset = 0;

        // Front face (+Z)
        offset = addQuad(vertices, offset,
            -0.5f,  0.5f,  0.5f,  // Top-left
             0.5f,  0.5f,  0.5f,  // Top-right
             0.5f, -0.5f,  0.5f,  // Bottom-right
            -0.5f, -0.5f,  0.5f   // Bottom-left
        );

        // Back face (-Z)
        offset = addQuad(vertices, offset,
             0.5f,  0.5f, -0.5f,  // Top-left
            -0.5f,  0.5f, -0.5f,  // Top-right
            -0.5f, -0.5f, -0.5f,  // Bottom-right
             0.5f, -0.5f, -0.5f   // Bottom-left
        );

        // Top face (+Y)
        // Fixed: Start at front-left to match game orientation (top of texture at +Z front)
        offset = addQuad(vertices, offset,
            -0.5f,  0.5f,  0.5f,  // Top-left (front-left)
             0.5f,  0.5f,  0.5f,  // Top-right (front-right)
             0.5f,  0.5f, -0.5f,  // Bottom-right (back-right)
            -0.5f,  0.5f, -0.5f   // Bottom-left (back-left)
        );

        // Bottom face (-Y)
        // Fixed: Start at back-left to match game orientation (top of texture at -Z back)
        offset = addQuad(vertices, offset,
            -0.5f, -0.5f, -0.5f,  // Top-left (back-left)
             0.5f, -0.5f, -0.5f,  // Top-right (back-right)
             0.5f, -0.5f,  0.5f,  // Bottom-right (front-right)
            -0.5f, -0.5f,  0.5f   // Bottom-left (front-left)
        );

        // Right face (+X)
        offset = addQuad(vertices, offset,
             0.5f,  0.5f,  0.5f,  // Top-left
             0.5f,  0.5f, -0.5f,  // Top-right
             0.5f, -0.5f, -0.5f,  // Bottom-right
             0.5f, -0.5f,  0.5f   // Bottom-left
        );

        // Left face (-X)
        offset = addQuad(vertices, offset,
            -0.5f,  0.5f, -0.5f,  // Top-left
            -0.5f,  0.5f,  0.5f,  // Top-right
            -0.5f, -0.5f,  0.5f,  // Bottom-right
            -0.5f, -0.5f, -0.5f   // Bottom-left
        );

        return vertices;
    }

    /**
     * Adds a quad to the vertex array with simple 0-1 UV coordinates.
     */
    private static int addQuad(float[] vertices, int offset,
                               float x0, float y0, float z0,
                               float x1, float y1, float z1,
                               float x2, float y2, float z2,
                               float x3, float y3, float z3) {
        // Top-left (U=0, V=0)
        vertices[offset++] = x0;
        vertices[offset++] = y0;
        vertices[offset++] = z0;
        vertices[offset++] = 0.0f;  // U
        vertices[offset++] = 0.0f;  // V

        // Top-right (U=1, V=0)
        vertices[offset++] = x1;
        vertices[offset++] = y1;
        vertices[offset++] = z1;
        vertices[offset++] = 1.0f;  // U
        vertices[offset++] = 0.0f;  // V

        // Bottom-right (U=1, V=1)
        vertices[offset++] = x2;
        vertices[offset++] = y2;
        vertices[offset++] = z2;
        vertices[offset++] = 1.0f;  // U
        vertices[offset++] = 1.0f;  // V

        // Bottom-left (U=0, V=1)
        vertices[offset++] = x3;
        vertices[offset++] = y3;
        vertices[offset++] = z3;
        vertices[offset++] = 0.0f;  // U
        vertices[offset++] = 1.0f;  // V

        return offset;
    }
}
