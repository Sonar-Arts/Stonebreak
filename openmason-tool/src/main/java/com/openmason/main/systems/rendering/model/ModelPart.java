package com.openmason.main.systems.rendering.model;

import org.joml.Vector3f;

/**
 * Defines a single part of a multi-part model.
 * Not locked to cube topology - supports arbitrary geometry.
 *
 * @param name Unique identifier for this part
 * @param origin The origin point of this part (pivot for transforms)
 * @param vertices Vertex positions array (x, y, z interleaved)
 * @param texCoords Texture coordinates array (u, v interleaved)
 * @param indices Index array for indexed drawing (null for non-indexed)
 */
public record ModelPart(
        String name,
        Vector3f origin,
        float[] vertices,
        float[] texCoords,
        int[] indices
) {
    /**
     * Create a cube part with proper 24-vertex format for cube net textures.
     * Uses expanded mesh format (4 vertices per face) to support proper UV mapping.
     *
     * @param name Part name
     * @param origin Part origin (center point)
     * @param size Size in each axis (width, height, depth)
     * @return A ModelPart representing a cube with cube net UV coordinates
     */
    public static ModelPart createCube(String name, Vector3f origin, Vector3f size) {
        float hw = size.x / 2.0f; // half width
        float hh = size.y / 2.0f; // half height
        float hd = size.z / 2.0f; // half depth

        // Cube net UV coordinates for 64x48 texture (Minecraft-style layout)
        // Row heights: 16/48 = 0.333..., 32/48 = 0.666...
        float V_ROW_1 = 16.0f / 48.0f;  // 0.333333...
        float V_ROW_2 = 32.0f / 48.0f;  // 0.666666...

        // Face UV bounds: {u1, v1, u2, v2} (top-left to bottom-right in texture space)
        float[] TOP_UV = {0.25f, 0.0f, 0.5f, V_ROW_1};
        float[] LEFT_UV = {0.0f, V_ROW_1, 0.25f, V_ROW_2};
        float[] FRONT_UV = {0.25f, V_ROW_1, 0.5f, V_ROW_2};
        float[] RIGHT_UV = {0.5f, V_ROW_1, 0.75f, V_ROW_2};
        float[] BACK_UV = {0.75f, V_ROW_1, 1.0f, V_ROW_2};
        float[] BOTTOM_UV = {0.25f, V_ROW_2, 0.5f, 1.0f};

        // 24 vertices (4 per face × 6 faces) - expanded format for proper UV mapping
        // Format: x, y, z (3 floats per vertex)
        float[] vertices = {
            // FRONT face (facing +Z) - 4 vertices
            -hw, -hh,  hd,  // 0: bottom-left
             hw, -hh,  hd,  // 1: bottom-right
             hw,  hh,  hd,  // 2: top-right
            -hw,  hh,  hd,  // 3: top-left

            // BACK face (facing -Z) - 4 vertices
             hw, -hh, -hd,  // 4: bottom-left (flipped)
            -hw, -hh, -hd,  // 5: bottom-right
            -hw,  hh, -hd,  // 6: top-right
             hw,  hh, -hd,  // 7: top-left

            // TOP face (facing +Y) - 4 vertices
            -hw,  hh,  hd,  // 8: front-left
             hw,  hh,  hd,  // 9: front-right
             hw,  hh, -hd,  // 10: back-right
            -hw,  hh, -hd,  // 11: back-left

            // BOTTOM face (facing -Y) - 4 vertices
            -hw, -hh, -hd,  // 12: back-left
             hw, -hh, -hd,  // 13: back-right
             hw, -hh,  hd,  // 14: front-right
            -hw, -hh,  hd,  // 15: front-left

            // RIGHT face (facing +X) - 4 vertices
             hw, -hh,  hd,  // 16: bottom-front
             hw, -hh, -hd,  // 17: bottom-back
             hw,  hh, -hd,  // 18: top-back
             hw,  hh,  hd,  // 19: top-front

            // LEFT face (facing -X) - 4 vertices
            -hw, -hh, -hd,  // 20: bottom-back
            -hw, -hh,  hd,  // 21: bottom-front
            -hw,  hh,  hd,  // 22: top-front
            -hw,  hh, -hd   // 23: top-back
        };

        // Texture coordinates for each vertex (u, v interleaved)
        float[] texCoords = {
            // FRONT face UVs
            FRONT_UV[0], FRONT_UV[3],  // 0: bottom-left
            FRONT_UV[2], FRONT_UV[3],  // 1: bottom-right
            FRONT_UV[2], FRONT_UV[1],  // 2: top-right
            FRONT_UV[0], FRONT_UV[1],  // 3: top-left

            // BACK face UVs
            BACK_UV[0], BACK_UV[3],    // 4: bottom-left
            BACK_UV[2], BACK_UV[3],    // 5: bottom-right
            BACK_UV[2], BACK_UV[1],    // 6: top-right
            BACK_UV[0], BACK_UV[1],    // 7: top-left

            // TOP face UVs
            TOP_UV[0], TOP_UV[1],      // 8: front-left
            TOP_UV[2], TOP_UV[1],      // 9: front-right
            TOP_UV[2], TOP_UV[3],      // 10: back-right
            TOP_UV[0], TOP_UV[3],      // 11: back-left

            // BOTTOM face UVs
            BOTTOM_UV[0], BOTTOM_UV[1],  // 12: back-left
            BOTTOM_UV[2], BOTTOM_UV[1],  // 13: back-right
            BOTTOM_UV[2], BOTTOM_UV[3],  // 14: front-right
            BOTTOM_UV[0], BOTTOM_UV[3],  // 15: front-left

            // RIGHT face UVs
            RIGHT_UV[0], RIGHT_UV[3],  // 16: bottom-front
            RIGHT_UV[2], RIGHT_UV[3],  // 17: bottom-back
            RIGHT_UV[2], RIGHT_UV[1],  // 18: top-back
            RIGHT_UV[0], RIGHT_UV[1],  // 19: top-front

            // LEFT face UVs
            LEFT_UV[0], LEFT_UV[3],    // 20: bottom-back
            LEFT_UV[2], LEFT_UV[3],    // 21: bottom-front
            LEFT_UV[2], LEFT_UV[1],    // 22: top-front
            LEFT_UV[0], LEFT_UV[1]     // 23: top-back
        };

        // Indices for 6 faces (2 triangles each = 36 indices)
        // Counter-clockwise winding for front-facing polygons
        int[] indices = new int[36];
        int idx = 0;
        for (int face = 0; face < 6; face++) {
            int baseVertex = face * 4;
            // Triangle 1: vertices 0, 1, 2
            indices[idx++] = baseVertex;
            indices[idx++] = baseVertex + 1;
            indices[idx++] = baseVertex + 2;
            // Triangle 2: vertices 2, 3, 0
            indices[idx++] = baseVertex + 2;
            indices[idx++] = baseVertex + 3;
            indices[idx++] = baseVertex;
        }

        return new ModelPart(name, origin, vertices, texCoords, indices);
    }

    /**
     * Create a part from raw vertex data.
     *
     * @param name Part name
     * @param vertices Raw vertex positions
     * @param texCoords Raw texture coordinates
     * @param indices Raw indices (can be null for non-indexed)
     * @return A ModelPart with the given geometry
     */
    public static ModelPart createFromVertices(String name, float[] vertices, float[] texCoords, int[] indices) {
        return new ModelPart(name, new Vector3f(0, 0, 0), vertices, texCoords, indices);
    }

    /**
     * Get vertex count.
     *
     * @return Number of vertices
     */
    public int getVertexCount() {
        return vertices != null ? vertices.length / 3 : 0;
    }

    /**
     * Get index count.
     *
     * @return Number of indices, or 0 if non-indexed
     */
    public int getIndexCount() {
        return indices != null ? indices.length : 0;
    }

    /**
     * Check if this part uses indexed drawing.
     *
     * @return true if indexed
     */
    public boolean isIndexed() {
        return indices != null && indices.length > 0;
    }

    /**
     * Get position of a specific vertex.
     *
     * @param index Vertex index
     * @return Vertex position, or null if invalid
     */
    public Vector3f getVertexPosition(int index) {
        if (vertices == null || index < 0 || index >= getVertexCount()) {
            return null;
        }
        int offset = index * 3;
        return new Vector3f(vertices[offset], vertices[offset + 1], vertices[offset + 2]);
    }

    /**
     * Create a new ModelPart with an updated vertex position.
     * Since records are immutable, this creates a copy.
     *
     * @param index Vertex index to update
     * @param position New position
     * @return New ModelPart with updated vertex, or this if invalid
     */
    public ModelPart withUpdatedVertex(int index, Vector3f position) {
        if (vertices == null || index < 0 || index >= getVertexCount()) {
            return this;
        }

        float[] newVertices = vertices.clone();
        int offset = index * 3;
        newVertices[offset] = position.x;
        newVertices[offset + 1] = position.y;
        newVertices[offset + 2] = position.z;

        return new ModelPart(name, origin, newVertices, texCoords, indices);
    }
}
