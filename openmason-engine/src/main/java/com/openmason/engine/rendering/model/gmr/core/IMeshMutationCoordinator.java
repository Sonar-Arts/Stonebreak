package com.openmason.engine.rendering.model.gmr.core;

import org.joml.Vector3f;

/**
 * Interface for mesh structural mutation operations.
 * Handles subdivision, edge insertion, face creation/deletion, and vertex updates.
 * Each mutation applies its processor, updates shared state, then triggers rebuild.
 */
public interface IMeshMutationCoordinator {

    /**
     * Update a vertex position by its global index.
     * Also updates ALL other mesh vertices at the same geometric position.
     *
     * @param globalIndex The vertex index
     * @param position    The new position
     */
    void updateVertexPosition(int globalIndex, Vector3f position);

    /**
     * Update all vertex positions at once.
     *
     * @param positions New vertex positions array (x,y,z interleaved)
     */
    void updateVertexPositions(float[] positions);

    /**
     * Apply edge subdivision using endpoint positions.
     *
     * @param midpointPosition Position of the new midpoint vertex
     * @param endpoint1        Position of first edge endpoint
     * @param endpoint2        Position of second edge endpoint
     * @return First new vertex index, or -1 on failure
     */
    int applyEdgeSubdivisionByPosition(Vector3f midpointPosition, Vector3f endpoint1, Vector3f endpoint2);

    /**
     * Apply edge subdivision at an arbitrary parametric position using unique vertex indices.
     *
     * @param uniqueVertexA First unique vertex index of the edge
     * @param uniqueVertexB Second unique vertex index of the edge
     * @param t             Parametric position along the edge (0 = vertexA, 1 = vertexB)
     * @return Unique vertex index of the newly created vertex, or -1 on failure
     */
    int subdivideEdgeAtParameter(int uniqueVertexA, int uniqueVertexB, float t);

    /**
     * Insert an edge between two unique vertices, splitting shared faces.
     *
     * @param uniqueVertexA First unique vertex index
     * @param uniqueVertexB Second unique vertex index
     * @return true if the edge was inserted successfully
     */
    boolean insertEdgeBetweenVertices(int uniqueVertexA, int uniqueVertexB);

    /**
     * Delete a face from the mesh by removing all its triangles.
     *
     * @param faceId Face ID to delete
     * @return true if the face was deleted successfully
     */
    boolean deleteFace(int faceId);

    /**
     * Create a new face from selected unique vertices using the default material.
     *
     * @param selectedUniqueVertices Unique vertex indices forming the polygon, in winding order
     * @return true if the face was created successfully
     */
    boolean createFaceFromVertices(int[] selectedUniqueVertices);

    /**
     * Create a new face from selected unique vertices with a specific material.
     *
     * @param selectedUniqueVertices Unique vertex indices forming the polygon, in winding order
     * @param activeMaterialId       Material ID to assign to the new face
     * @return true if the face was created successfully
     */
    boolean createFaceFromVertices(int[] selectedUniqueVertices, int activeMaterialId);
}
