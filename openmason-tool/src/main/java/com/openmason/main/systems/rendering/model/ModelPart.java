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
     * Create a cube part with standard 8 vertices.
     *
     * @param name Part name
     * @param origin Part origin (center point)
     * @param size Size in each axis (width, height, depth)
     * @return A ModelPart representing a cube
     */
    public static ModelPart createCube(String name, Vector3f origin, Vector3f size) {
        float hw = size.x / 2.0f; // half width
        float hh = size.y / 2.0f; // half height
        float hd = size.z / 2.0f; // half depth

        // 8 unique vertices for a cube
        // Standard ordering: back-bottom-left, back-bottom-right, front-bottom-right, front-bottom-left,
        //                   back-top-left, back-top-right, front-top-right, front-top-left
        float[] vertices = {
                -hw, -hh, -hd,  // 0: back-bottom-left
                 hw, -hh, -hd,  // 1: back-bottom-right
                 hw, -hh,  hd,  // 2: front-bottom-right
                -hw, -hh,  hd,  // 3: front-bottom-left
                -hw,  hh, -hd,  // 4: back-top-left
                 hw,  hh, -hd,  // 5: back-top-right
                 hw,  hh,  hd,  // 6: front-top-right
                -hw,  hh,  hd   // 7: front-top-left
        };

        // Simple UV coordinates for each vertex (for basic rendering)
        // Real UV mapping would come from texture atlas
        float[] texCoords = {
                0.0f, 0.0f,  // 0
                1.0f, 0.0f,  // 1
                1.0f, 0.0f,  // 2
                0.0f, 0.0f,  // 3
                0.0f, 1.0f,  // 4
                1.0f, 1.0f,  // 5
                1.0f, 1.0f,  // 6
                0.0f, 1.0f   // 7
        };

        // Indices for 6 faces (2 triangles each = 36 indices)
        // Note: This is for expanded mesh format (24 vertices)
        // For 8-vertex format, you'd need different indexing
        int[] indices = {
                // Front face (3, 2, 6, 7)
                3, 2, 6,  6, 7, 3,
                // Back face (1, 0, 4, 5)
                1, 0, 4,  4, 5, 1,
                // Top face (7, 6, 5, 4)
                7, 6, 5,  5, 4, 7,
                // Bottom face (0, 1, 2, 3)
                0, 1, 2,  2, 3, 0,
                // Right face (2, 1, 5, 6)
                2, 1, 5,  5, 6, 2,
                // Left face (0, 3, 7, 4)
                0, 3, 7,  7, 4, 0
        };

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
