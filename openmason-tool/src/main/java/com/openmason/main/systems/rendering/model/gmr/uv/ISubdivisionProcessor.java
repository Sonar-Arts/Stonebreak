package com.openmason.main.systems.rendering.model.gmr.uv;

import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;
import com.openmason.main.systems.rendering.model.gmr.mapping.IUniqueVertexMapper;
import org.joml.Vector3f;

/**
 * Interface for edge subdivision operations.
 * Handles splitting triangles along edges while preserving face IDs and UV coordinates.
 */
public interface ISubdivisionProcessor {

    /**
     * Result of a subdivision operation.
     */
    record SubdivisionResult(
        int firstNewVertexIndex,
        int newVertexCount,
        int[] newIndices,
        int[] newTriangleToFaceId,
        boolean success,
        String errorMessage
    ) {
        public static SubdivisionResult success(int firstNewVertexIndex, int newVertexCount,
                                                 int[] newIndices, int[] newTriangleToFaceId) {
            return new SubdivisionResult(firstNewVertexIndex, newVertexCount, newIndices, newTriangleToFaceId, true, null);
        }

        public static SubdivisionResult failure(String errorMessage) {
            return new SubdivisionResult(-1, 0, null, null, false, errorMessage);
        }
    }

    /**
     * Apply edge subdivision using endpoint positions.
     * Finds ALL mesh vertex pairs at the endpoint positions and splits ALL triangles
     * that use any of these edge pairs.
     *
     * @param midpointPosition Position of the new midpoint vertex
     * @param endpoint1 Position of first edge endpoint
     * @param endpoint2 Position of second edge endpoint
     * @param vertexManager Vertex data manager for reading/updating vertex data
     * @param faceMapper Face mapper for reading/updating face mappings
     * @param currentVertexCount Current number of vertices
     * @return SubdivisionResult with new vertex info and updated arrays
     */
    SubdivisionResult applyEdgeSubdivision(
        Vector3f midpointPosition,
        Vector3f endpoint1,
        Vector3f endpoint2,
        IVertexDataManager vertexManager,
        ITriangleFaceMapper faceMapper,
        int currentVertexCount
    );

    /**
     * Apply edge subdivision at an arbitrary parametric position using unique vertex indices.
     * Splits all triangles sharing the edge, placing the new vertex at {@code lerp(posA, posB, t)}.
     *
     * @param uniqueVertexA First unique vertex index of the edge
     * @param uniqueVertexB Second unique vertex index of the edge
     * @param t Parametric position along the edge (0 = vertexA, 1 = vertexB)
     * @param vertexManager Vertex data manager for reading/updating vertex data
     * @param faceMapper Face mapper for reading/updating face mappings
     * @param uniqueMapper Unique vertex mapper for resolving unique to mesh indices
     * @param currentVertexCount Current number of vertices
     * @return SubdivisionResult with new vertex info and updated arrays
     */
    SubdivisionResult applyEdgeSubdivisionAtParameter(
        int uniqueVertexA, int uniqueVertexB, float t,
        IVertexDataManager vertexManager,
        ITriangleFaceMapper faceMapper,
        IUniqueVertexMapper uniqueMapper,
        int currentVertexCount
    );

    /**
     * Check if two vertices form an edge in any triangle.
     *
     * @param v1 First vertex index
     * @param v2 Second vertex index
     * @param indices Triangle indices array
     * @return true if the vertices form an edge
     */
    boolean isEdgeInMesh(int v1, int v2, int[] indices);

    /**
     * Find if a triangle contains a specific edge.
     *
     * @param i0 Triangle vertex 0
     * @param i1 Triangle vertex 1
     * @param i2 Triangle vertex 2
     * @param e1 Edge endpoint 1
     * @param e2 Edge endpoint 2
     * @return Edge position (0, 1, or 2) if found, -1 if not found
     */
    int findEdgeInTriangle(int i0, int i1, int i2, int e1, int e2);
}
