package com.openmason.main.systems.rendering.model.gmr.uv;

import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;
import com.openmason.main.systems.rendering.model.gmr.topology.MeshTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Processor for deleting a face from the mesh while preserving boundary edges and vertices.
 * Removes all triangles belonging to the target face from the index array,
 * then rebuilds the triangle-to-face mapping without the deleted face's entries.
 *
 * <p>No vertices are removed — vertices remain even if they only bordered the deleted face.
 * Orphan vertex cleanup is a separate concern. This is critical for face creation — the
 * boundary vertices of a deleted face are exactly the vertices needed to build new faces
 * in the opening.
 *
 * <p>Edges that were shared between the deleted face and neighbors become boundary edges
 * after topology rebuild. Edges that belonged only to the deleted face are naturally
 * excluded from the rebuilt topology since no remaining triangles reference them.
 *
 * <p>Topology must be rebuilt via {@code MeshTopologyBuilder} after applying the result.
 */
public final class FaceDeletionProcessor {

    private static final Logger logger = LoggerFactory.getLogger(FaceDeletionProcessor.class);

    /**
     * Result of a face deletion operation.
     *
     * @param newIndices           Updated triangle index array (replaces the old one entirely)
     * @param newTriangleToFaceId  Updated triangle-to-face-ID mapping array
     * @param newFaceCount         Face ID upper bound after deletion (for iteration)
     * @param success              Whether the operation succeeded
     * @param errorMessage         Error description on failure
     */
    public record FaceDeletionResult(
        int[] newIndices,
        int[] newTriangleToFaceId,
        int newFaceCount,
        boolean success,
        String errorMessage
    ) {
        public static FaceDeletionResult success(int[] newIndices, int[] newTriangleToFaceId, int newFaceCount) {
            return new FaceDeletionResult(newIndices, newTriangleToFaceId, newFaceCount, true, null);
        }

        public static FaceDeletionResult failure(String errorMessage) {
            return new FaceDeletionResult(null, null, 0, false, errorMessage);
        }
    }

    /**
     * Delete a face from the mesh by removing all its triangles from the index array.
     *
     * @param targetFaceId      Face ID to delete
     * @param topology          Current mesh topology for validation and adjacency queries
     * @param vertexManager     Vertex data manager for index array access
     * @param faceMapper        Face mapper for triangle-to-face ID lookups
     * @param faceTextureManager Face texture manager for UV cleanup (nullable)
     * @return FaceDeletionResult with updated indices and face mapping
     */
    public FaceDeletionResult deleteFace(
            int targetFaceId,
            MeshTopology topology,
            IVertexDataManager vertexManager,
            ITriangleFaceMapper faceMapper,
            IFaceTextureManager faceTextureManager) {

        if (topology == null) {
            return FaceDeletionResult.failure("Topology not available");
        }
        if (vertexManager == null || faceMapper == null) {
            return FaceDeletionResult.failure("Invalid parameters: null manager or mapper");
        }
        if (topology.getFace(targetFaceId) == null) {
            return FaceDeletionResult.failure("Face " + targetFaceId + " does not exist");
        }

        int[] oldIndices = vertexManager.getIndices();
        if (oldIndices == null || oldIndices.length == 0) {
            return FaceDeletionResult.failure("No index data available");
        }

        int oldTriangleCount = oldIndices.length / 3;
        int removedTriangles = 0;

        // Build new index and face-ID arrays, excluding triangles from the target face
        List<Integer> newIndices = new ArrayList<>();
        List<Integer> newFaceIds = new ArrayList<>();
        int maxRetainedFaceId = -1;

        for (int t = 0; t < oldTriangleCount; t++) {
            int faceId = faceMapper.getOriginalFaceIdForTriangle(t);

            if (faceId == targetFaceId) {
                removedTriangles++;
                continue;
            }

            newIndices.add(oldIndices[t * 3]);
            newIndices.add(oldIndices[t * 3 + 1]);
            newIndices.add(oldIndices[t * 3 + 2]);
            newFaceIds.add(faceId);

            if (faceId > maxRetainedFaceId) {
                maxRetainedFaceId = faceId;
            }
        }

        if (removedTriangles == 0) {
            return FaceDeletionResult.failure("No triangles found for face " + targetFaceId);
        }

        // Face ID upper bound: maxRetainedFaceId + 1 (accounts for gaps from deletion)
        int newFaceCount = maxRetainedFaceId + 1;

        int[] newIndicesArray = newIndices.stream().mapToInt(Integer::intValue).toArray();
        int[] newFaceIdArray = newFaceIds.stream().mapToInt(Integer::intValue).toArray();

        // Remove the deleted face's UV mapping
        if (faceTextureManager != null) {
            faceTextureManager.removeFaceMapping(targetFaceId);
        }

        logger.info("Face deletion complete: face {} removed, {} -> {} triangles, face ID upper bound {}",
            targetFaceId, oldTriangleCount, newIndicesArray.length / 3, newFaceCount);

        return FaceDeletionResult.success(newIndicesArray, newFaceIdArray, newFaceCount);
    }
}
