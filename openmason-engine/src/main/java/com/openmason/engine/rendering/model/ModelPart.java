package com.openmason.engine.rendering.model;

import org.joml.Vector3f;

/**
 * Defines a single part of a multi-part model.
 * Not locked to cube topology - supports arbitrary geometry.
 *
 * <p>To construct standard primitive shapes (cube, pyramid, pane, sprite),
 * use {@link com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory}.
 *
 * @param name Unique identifier for this part
 * @param origin The origin point of this part (pivot for transforms)
 * @param vertices Vertex positions array (x, y, z interleaved)
 * @param texCoords Texture coordinates array (u, v interleaved)
 * @param indices Index array for indexed drawing (null for non-indexed)
 * @param trianglesPerFace Topology hint: triangles per logical face (2 for quads, 1 for triangles, null for 1:1 default)
 */
public record ModelPart(
        String name,
        Vector3f origin,
        float[] vertices,
        float[] texCoords,
        int[] indices,
        Integer trianglesPerFace
) {
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

}
