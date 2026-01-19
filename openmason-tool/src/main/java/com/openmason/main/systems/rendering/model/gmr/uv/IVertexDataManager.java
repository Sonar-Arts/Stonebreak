package com.openmason.main.systems.rendering.model.gmr.uv;

import org.joml.Vector3f;

import java.util.List;

/**
 * Interface for managing mutable vertex position data.
 * Handles vertex position updates, queries, and data storage.
 */
public interface IVertexDataManager {

    /**
     * Set the vertex data arrays.
     *
     * @param vertices Vertex positions (x,y,z interleaved)
     * @param texCoords Texture coordinates (u,v interleaved)
     * @param indices Triangle indices
     */
    void setData(float[] vertices, float[] texCoords, int[] indices);

    /**
     * Get the current vertex positions array.
     *
     * @return Vertex positions (x,y,z interleaved), or null if none
     */
    float[] getVertices();

    /**
     * Get the current texture coordinates array.
     *
     * @return Texture coordinates (u,v interleaved), or null if none
     */
    float[] getTexCoords();

    /**
     * Get the current triangle indices array.
     *
     * @return Triangle indices, or null if none
     */
    int[] getIndices();

    /**
     * Update a single vertex position by index.
     *
     * @param globalIndex The vertex index
     * @param position The new position
     * @return true if successful, false if index invalid
     */
    boolean updateVertexPosition(int globalIndex, Vector3f position);

    /**
     * Update multiple vertex positions at specific indices.
     *
     * @param indices Array of vertex indices to update
     * @param position The new position for all specified vertices
     */
    void updateVertexPositions(int[] indices, Vector3f position);

    /**
     * Get vertex position by global index.
     *
     * @param globalIndex The vertex index
     * @return The vertex position, or null if invalid
     */
    Vector3f getVertexPosition(int globalIndex);

    /**
     * Get total vertex count.
     *
     * @return Number of vertices
     */
    int getTotalVertexCount();

    /**
     * Get all current mesh vertex positions.
     * Returns a COPY to prevent external modification.
     *
     * @return Copy of current vertex positions array, or null if none
     */
    float[] getAllMeshVertexPositions();

    /**
     * Find all mesh vertex indices at a given position.
     *
     * @param position The position to search for
     * @param epsilon Tolerance for position matching
     * @return List of mesh vertex indices at that position
     */
    List<Integer> findMeshVerticesAtPosition(Vector3f position, float epsilon);

    /**
     * Expand vertex arrays to accommodate new vertices.
     *
     * @param additionalVertices Number of additional vertices to add
     */
    void expandVertexArrays(int additionalVertices);

    /**
     * Set a vertex position directly at an offset (for subdivision).
     *
     * @param vertexIndex The vertex index
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    void setVertexPositionDirect(int vertexIndex, float x, float y, float z);

    /**
     * Set texture coordinates directly at an offset (for subdivision).
     *
     * @param vertexIndex The vertex index
     * @param u U coordinate
     * @param v V coordinate
     */
    void setTexCoordDirect(int vertexIndex, float u, float v);

    /**
     * Set the indices array directly.
     *
     * @param indices New indices array
     */
    void setIndices(int[] indices);
}
