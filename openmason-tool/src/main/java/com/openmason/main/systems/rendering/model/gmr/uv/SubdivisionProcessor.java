package com.openmason.main.systems.rendering.model.gmr.uv;

import com.openmason.main.systems.rendering.model.gmr.GMRConstants;
import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;
import com.openmason.main.systems.rendering.model.gmr.mapping.IUniqueVertexMapper;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of ISubdivisionProcessor.
 * Handles edge subdivision operations, splitting triangles along edges
 * while preserving face IDs and UV coordinates.
 */
public class SubdivisionProcessor implements ISubdivisionProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SubdivisionProcessor.class);
    private static final float ENDPOINT_EPSILON = GMRConstants.SUBDIVISION_TOLERANCE;

    @Override
    public SubdivisionResult applyEdgeSubdivision(
            Vector3f midpointPosition,
            Vector3f endpoint1,
            Vector3f endpoint2,
            IVertexDataManager vertexManager,
            ITriangleFaceMapper faceMapper,
            int currentVertexCount) {

        if (midpointPosition == null || endpoint1 == null || endpoint2 == null) {
            return SubdivisionResult.failure("Invalid parameters: null positions");
        }

        float[] vertices = vertexManager.getVertices();
        float[] texCoords = vertexManager.getTexCoords();
        int[] indices = vertexManager.getIndices();

        if (vertices == null || indices == null) {
            return SubdivisionResult.failure("No geometry loaded");
        }

        logger.info("=== SUBDIVISION DEBUG ===");
        logger.info("Looking for endpoints: ({},{},{}) and ({},{},{})",
            endpoint1.x, endpoint1.y, endpoint1.z, endpoint2.x, endpoint2.y, endpoint2.z);

        // Find ALL mesh vertices at endpoint positions
        List<Integer> vertices1 = vertexManager.findMeshVerticesAtPosition(endpoint1, ENDPOINT_EPSILON);
        List<Integer> vertices2 = vertexManager.findMeshVerticesAtPosition(endpoint2, ENDPOINT_EPSILON);

        logger.debug("Found {} vertices at endpoint1, {} vertices at endpoint2",
            vertices1.size(), vertices2.size());

        if (vertices1.isEmpty() || vertices2.isEmpty()) {
            return SubdivisionResult.failure(String.format(
                "Edge endpoints not found in mesh. endpoint1 found: %d, endpoint2 found: %d",
                vertices1.size(), vertices2.size()));
        }

        // Collect ALL valid mesh edge pairs
        List<int[]> validEdgePairs = new ArrayList<>();
        for (int v1 : vertices1) {
            for (int v2 : vertices2) {
                if (isEdgeInMesh(v1, v2, indices)) {
                    validEdgePairs.add(new int[]{v1, v2});
                    logger.debug("Found valid edge pair: ({}, {})", v1, v2);
                }
            }
        }

        if (validEdgePairs.isEmpty()) {
            return SubdivisionResult.failure(String.format(
                "No valid edge found in mesh. vertices1: %s, vertices2: %s",
                vertices1, vertices2));
        }

        logger.debug("Found {} mesh edge pairs for geometric edge", validEdgePairs.size());

        // Count triangles to split
        int triangleCount = indices.length / 3;
        int trianglesToSplit = 0;

        for (int t = 0; t < triangleCount; t++) {
            int i0 = indices[t * 3];
            int i1 = indices[t * 3 + 1];
            int i2 = indices[t * 3 + 2];

            for (int[] pair : validEdgePairs) {
                if (findEdgeInTriangle(i0, i1, i2, pair[0], pair[1]) >= 0) {
                    trianglesToSplit++;
                    break;
                }
            }
        }

        if (trianglesToSplit == 0) {
            return SubdivisionResult.failure("No triangles found to split");
        }

        // Expand vertex arrays
        int firstNewVertexIndex = currentVertexCount;
        vertexManager.expandVertexArrays(trianglesToSplit);

        // Split triangles
        List<Integer> newIndices = new ArrayList<>();
        List<Integer> newTriangleToFaceId = new ArrayList<>();
        int currentNewVertex = firstNewVertexIndex;

        for (int t = 0; t < triangleCount; t++) {
            int originalFaceId = faceMapper.hasMapping() ? faceMapper.getOriginalFaceIdForTriangle(t) : (t / 2);
            int i0 = indices[t * 3];
            int i1 = indices[t * 3 + 1];
            int i2 = indices[t * 3 + 2];

            // Check if this triangle contains ANY of the edge pairs
            int edgePos = -1;
            int[] matchedPair = null;

            for (int[] pair : validEdgePairs) {
                edgePos = findEdgeInTriangle(i0, i1, i2, pair[0], pair[1]);
                if (edgePos >= 0) {
                    matchedPair = pair;
                    break;
                }
            }

            if (edgePos >= 0 && matchedPair != null) {
                // Split this triangle
                int oppositeVertex;
                int e1, e2;

                switch (edgePos) {
                    case 0: // Edge i0-i1
                        oppositeVertex = i2;
                        e1 = i0;
                        e2 = i1;
                        break;
                    case 1: // Edge i1-i2
                        oppositeVertex = i0;
                        e1 = i1;
                        e2 = i2;
                        break;
                    case 2: // Edge i2-i0
                        oppositeVertex = i1;
                        e1 = i2;
                        e2 = i0;
                        break;
                    default:
                        newIndices.add(i0);
                        newIndices.add(i1);
                        newIndices.add(i2);
                        newTriangleToFaceId.add(originalFaceId);
                        continue;
                }

                // Add new vertex at midpoint position
                vertexManager.setVertexPositionDirect(currentNewVertex,
                    midpointPosition.x, midpointPosition.y, midpointPosition.z);

                // Interpolate UV from this triangle's edge vertices
                float u1 = 0, v1 = 0, u2 = 0, v2 = 0;
                float[] currentTexCoords = vertexManager.getTexCoords();
                if (currentTexCoords != null) {
                    if (e1 * 2 + 1 < currentTexCoords.length) {
                        u1 = currentTexCoords[e1 * 2];
                        v1 = currentTexCoords[e1 * 2 + 1];
                    }
                    if (e2 * 2 + 1 < currentTexCoords.length) {
                        u2 = currentTexCoords[e2 * 2];
                        v2 = currentTexCoords[e2 * 2 + 1];
                    }
                }
                vertexManager.setTexCoordDirect(currentNewVertex, (u1 + u2) / 2.0f, (v1 + v2) / 2.0f);

                // Create 2 new triangles
                newIndices.add(e1);
                newIndices.add(currentNewVertex);
                newIndices.add(oppositeVertex);
                newTriangleToFaceId.add(originalFaceId);

                newIndices.add(currentNewVertex);
                newIndices.add(e2);
                newIndices.add(oppositeVertex);
                newTriangleToFaceId.add(originalFaceId);

                logger.debug("Split triangle {} ({},{},{}) on edge ({},{}) -> 2 triangles, new vertex {}",
                    t, i0, i1, i2, e1, e2, currentNewVertex);

                currentNewVertex++;
            } else {
                // Keep original triangle
                newIndices.add(i0);
                newIndices.add(i1);
                newIndices.add(i2);
                newTriangleToFaceId.add(originalFaceId);
            }
        }

        // Convert to arrays
        int[] newIndicesArray = newIndices.stream().mapToInt(Integer::intValue).toArray();
        int[] newFaceIdArray = newTriangleToFaceId.stream().mapToInt(Integer::intValue).toArray();

        logger.debug("Subdivision complete: added {} vertices, {} -> {} triangles",
            trianglesToSplit, triangleCount, newIndicesArray.length / 3);

        return SubdivisionResult.success(firstNewVertexIndex, trianglesToSplit, newIndicesArray, newFaceIdArray);
    }

    @Override
    public SubdivisionResult applyEdgeSubdivisionAtParameter(
            int uniqueVertexA, int uniqueVertexB, float t,
            IVertexDataManager vertexManager,
            ITriangleFaceMapper faceMapper,
            IUniqueVertexMapper uniqueMapper,
            int currentVertexCount) {

        if (uniqueMapper == null || !uniqueMapper.hasMapping()) {
            return SubdivisionResult.failure("Unique vertex mapper not available");
        }

        float[] vertices = vertexManager.getVertices();
        int[] indices = vertexManager.getIndices();

        if (vertices == null || indices == null) {
            return SubdivisionResult.failure("No geometry loaded");
        }

        // Resolve unique vertex indices to mesh vertex indices
        int[] meshIndicesA = uniqueMapper.getMeshIndicesForUniqueVertex(uniqueVertexA);
        int[] meshIndicesB = uniqueMapper.getMeshIndicesForUniqueVertex(uniqueVertexB);

        if (meshIndicesA.length == 0 || meshIndicesB.length == 0) {
            return SubdivisionResult.failure(String.format(
                "Cannot resolve unique vertices: A(%d)=%d mesh, B(%d)=%d mesh",
                uniqueVertexA, meshIndicesA.length, uniqueVertexB, meshIndicesB.length));
        }

        // Compute interpolated position: pos = posA * (1-t) + posB * t
        Vector3f posA = uniqueMapper.getUniqueVertexPosition(uniqueVertexA, vertices);
        Vector3f posB = uniqueMapper.getUniqueVertexPosition(uniqueVertexB, vertices);

        if (posA == null || posB == null) {
            return SubdivisionResult.failure("Cannot get vertex positions");
        }

        Vector3f interpolatedPosition = new Vector3f(
            posA.x * (1f - t) + posB.x * t,
            posA.y * (1f - t) + posB.y * t,
            posA.z * (1f - t) + posB.z * t
        );

        logger.debug("Parameterized subdivision: unique vertices ({}, {}), t={}, pos=({},{},{})",
            uniqueVertexA, uniqueVertexB, t,
            interpolatedPosition.x, interpolatedPosition.y, interpolatedPosition.z);

        // Collect ALL valid mesh edge pairs
        List<int[]> validEdgePairs = new ArrayList<>();
        for (int mA : meshIndicesA) {
            for (int mB : meshIndicesB) {
                if (isEdgeInMesh(mA, mB, indices)) {
                    validEdgePairs.add(new int[]{mA, mB});
                }
            }
        }

        if (validEdgePairs.isEmpty()) {
            return SubdivisionResult.failure("No valid edge found between unique vertices");
        }

        // Count triangles to split
        int triangleCount = indices.length / 3;
        int trianglesToSplit = 0;

        for (int tri = 0; tri < triangleCount; tri++) {
            int i0 = indices[tri * 3];
            int i1 = indices[tri * 3 + 1];
            int i2 = indices[tri * 3 + 2];

            for (int[] pair : validEdgePairs) {
                if (findEdgeInTriangle(i0, i1, i2, pair[0], pair[1]) >= 0) {
                    trianglesToSplit++;
                    break;
                }
            }
        }

        if (trianglesToSplit == 0) {
            return SubdivisionResult.failure("No triangles found to split");
        }

        // Expand vertex arrays
        int firstNewVertexIndex = currentVertexCount;
        vertexManager.expandVertexArrays(trianglesToSplit);

        // Split triangles
        List<Integer> newIndices = new ArrayList<>();
        List<Integer> newTriangleToFaceId = new ArrayList<>();
        int currentNewVertex = firstNewVertexIndex;

        for (int tri = 0; tri < triangleCount; tri++) {
            int originalFaceId = faceMapper.hasMapping() ? faceMapper.getOriginalFaceIdForTriangle(tri) : (tri / 2);
            int i0 = indices[tri * 3];
            int i1 = indices[tri * 3 + 1];
            int i2 = indices[tri * 3 + 2];

            int edgePos = -1;
            for (int[] pair : validEdgePairs) {
                edgePos = findEdgeInTriangle(i0, i1, i2, pair[0], pair[1]);
                if (edgePos >= 0) {
                    break;
                }
            }

            if (edgePos >= 0) {
                int oppositeVertex;
                int e1, e2;

                switch (edgePos) {
                    case 0: oppositeVertex = i2; e1 = i0; e2 = i1; break;
                    case 1: oppositeVertex = i0; e1 = i1; e2 = i2; break;
                    case 2: oppositeVertex = i1; e1 = i2; e2 = i0; break;
                    default:
                        newIndices.add(i0); newIndices.add(i1); newIndices.add(i2);
                        newTriangleToFaceId.add(originalFaceId);
                        continue;
                }

                // Set new vertex at interpolated position
                vertexManager.setVertexPositionDirect(currentNewVertex,
                    interpolatedPosition.x, interpolatedPosition.y, interpolatedPosition.z);

                // Interpolate UV with parameter t
                float[] currentTexCoords = vertexManager.getTexCoords();
                if (currentTexCoords != null) {
                    float u1 = 0, v1 = 0, u2 = 0, v2 = 0;
                    if (e1 * 2 + 1 < currentTexCoords.length) {
                        u1 = currentTexCoords[e1 * 2];
                        v1 = currentTexCoords[e1 * 2 + 1];
                    }
                    if (e2 * 2 + 1 < currentTexCoords.length) {
                        u2 = currentTexCoords[e2 * 2];
                        v2 = currentTexCoords[e2 * 2 + 1];
                    }

                    // Determine interpolation direction: e1 maps to which unique vertex?
                    int uniqueE1 = uniqueMapper.getUniqueIndexForMeshVertex(e1);
                    float localT;
                    if (uniqueE1 == uniqueVertexA) {
                        localT = t; // e1 is A, e2 is B → interpolate from e1 toward e2
                    } else {
                        localT = 1f - t; // e1 is B, e2 is A → reverse direction
                    }

                    vertexManager.setTexCoordDirect(currentNewVertex,
                        u1 * (1f - localT) + u2 * localT,
                        v1 * (1f - localT) + v2 * localT);
                }

                // Create 2 new triangles
                newIndices.add(e1);
                newIndices.add(currentNewVertex);
                newIndices.add(oppositeVertex);
                newTriangleToFaceId.add(originalFaceId);

                newIndices.add(currentNewVertex);
                newIndices.add(e2);
                newIndices.add(oppositeVertex);
                newTriangleToFaceId.add(originalFaceId);

                currentNewVertex++;
            } else {
                newIndices.add(i0);
                newIndices.add(i1);
                newIndices.add(i2);
                newTriangleToFaceId.add(originalFaceId);
            }
        }

        int[] newIndicesArray = newIndices.stream().mapToInt(Integer::intValue).toArray();
        int[] newFaceIdArray = newTriangleToFaceId.stream().mapToInt(Integer::intValue).toArray();

        logger.debug("Parameterized subdivision complete: added {} vertices, {} -> {} triangles",
            trianglesToSplit, triangleCount, newIndicesArray.length / 3);

        return SubdivisionResult.success(firstNewVertexIndex, trianglesToSplit, newIndicesArray, newFaceIdArray);
    }

    @Override
    public boolean isEdgeInMesh(int v1, int v2, int[] indices) {
        if (indices == null) {
            return false;
        }

        int triangleCount = indices.length / 3;
        for (int t = 0; t < triangleCount; t++) {
            int i0 = indices[t * 3];
            int i1 = indices[t * 3 + 1];
            int i2 = indices[t * 3 + 2];

            if (findEdgeInTriangle(i0, i1, i2, v1, v2) >= 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int findEdgeInTriangle(int i0, int i1, int i2, int e1, int e2) {
        // Check edge i0-i1
        if ((i0 == e1 && i1 == e2) || (i0 == e2 && i1 == e1)) {
            return 0;
        }
        // Check edge i1-i2
        if ((i1 == e1 && i2 == e2) || (i1 == e2 && i2 == e1)) {
            return 1;
        }
        // Check edge i2-i0
        if ((i2 == e1 && i0 == e2) || (i2 == e2 && i0 == e1)) {
            return 2;
        }
        return -1;
    }
}
