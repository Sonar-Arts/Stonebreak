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
        // IMPORTANT: Face order MUST match ModelDefinition.ModelPart.getVerticesAtOrigin()
        // Order: Front, Back, Left, Right, Top, Bottom
        float[] vertices = {
            // FRONT face (facing +Z) - vertices 0-3
            -hw, -hh,  hd,  // 0: bottom-left
             hw, -hh,  hd,  // 1: bottom-right
             hw,  hh,  hd,  // 2: top-right
            -hw,  hh,  hd,  // 3: top-left

            // BACK face (facing -Z) - vertices 4-7
            -hw, -hh, -hd,  // 4: bottom-left
             hw, -hh, -hd,  // 5: bottom-right
             hw,  hh, -hd,  // 6: top-right
            -hw,  hh, -hd,  // 7: top-left

            // LEFT face (facing -X) - vertices 8-11
            -hw, -hh, -hd,  // 8: bottom-back
            -hw, -hh,  hd,  // 9: bottom-front
            -hw,  hh,  hd,  // 10: top-front
            -hw,  hh, -hd,  // 11: top-back

            // RIGHT face (facing +X) - vertices 12-15
             hw, -hh, -hd,  // 12: bottom-back
             hw, -hh,  hd,  // 13: bottom-front
             hw,  hh,  hd,  // 14: top-front
             hw,  hh, -hd,  // 15: top-back

            // TOP face (facing +Y) - vertices 16-19
            -hw,  hh, -hd,  // 16: back-left
             hw,  hh, -hd,  // 17: back-right
             hw,  hh,  hd,  // 18: front-right
            -hw,  hh,  hd,  // 19: front-left

            // BOTTOM face (facing -Y) - vertices 20-23
            -hw, -hh, -hd,  // 20: back-left
             hw, -hh, -hd,  // 21: back-right
             hw, -hh,  hd,  // 22: front-right
            -hw, -hh,  hd   // 23: front-left
        };

        // Texture coordinates for each vertex (u, v interleaved)
        // Order matches vertices: Front, Back, Left, Right, Top, Bottom
        float[] texCoords = {
            // FRONT face UVs (vertices 0-3)
            FRONT_UV[0], FRONT_UV[3],  // 0: bottom-left
            FRONT_UV[2], FRONT_UV[3],  // 1: bottom-right
            FRONT_UV[2], FRONT_UV[1],  // 2: top-right
            FRONT_UV[0], FRONT_UV[1],  // 3: top-left

            // BACK face UVs (vertices 4-7)
            BACK_UV[0], BACK_UV[3],    // 4: bottom-left
            BACK_UV[2], BACK_UV[3],    // 5: bottom-right
            BACK_UV[2], BACK_UV[1],    // 6: top-right
            BACK_UV[0], BACK_UV[1],    // 7: top-left

            // LEFT face UVs (vertices 8-11)
            LEFT_UV[0], LEFT_UV[3],    // 8: bottom-back
            LEFT_UV[2], LEFT_UV[3],    // 9: bottom-front
            LEFT_UV[2], LEFT_UV[1],    // 10: top-front
            LEFT_UV[0], LEFT_UV[1],    // 11: top-back

            // RIGHT face UVs (vertices 12-15)
            RIGHT_UV[0], RIGHT_UV[3],  // 12: bottom-back
            RIGHT_UV[2], RIGHT_UV[3],  // 13: bottom-front
            RIGHT_UV[2], RIGHT_UV[1],  // 14: top-front
            RIGHT_UV[0], RIGHT_UV[1],  // 15: top-back

            // TOP face UVs (vertices 16-19)
            TOP_UV[0], TOP_UV[3],      // 16: back-left
            TOP_UV[2], TOP_UV[3],      // 17: back-right
            TOP_UV[2], TOP_UV[1],      // 18: front-right
            TOP_UV[0], TOP_UV[1],      // 19: front-left

            // BOTTOM face UVs (vertices 20-23)
            BOTTOM_UV[0], BOTTOM_UV[1],  // 20: back-left
            BOTTOM_UV[2], BOTTOM_UV[1],  // 21: back-right
            BOTTOM_UV[2], BOTTOM_UV[3],  // 22: front-right
            BOTTOM_UV[0], BOTTOM_UV[3]   // 23: front-left
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
