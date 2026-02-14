package com.openmason.main.systems.rendering.model.gmr.uv;

import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;
import com.openmason.main.systems.rendering.model.gmr.topology.MeshTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Processor for creating a new face from selected vertices.
 * The complement of face deletion: deleting a face creates a hole;
 * face creation fills holes or builds new geometry from existing vertices.
 *
 * <p>No new vertices are created — only the index array and triangle-to-face mapping grow.
 * The selected vertices must already exist in the mesh (e.g., boundary vertices left
 * after a face deletion).
 *
 * <p>The user controls winding order via selection order (Blender's F key behavior).
 * A separate "Flip Normals" operation can correct mistakes.
 *
 * <p>Fan-triangulates the polygon from the first selected vertex:
 * {@code (v0,v1,v2), (v0,v2,v3), ..., (v0,v(N-1),vN)}
 *
 * <p>Topology must be rebuilt via {@code MeshTopologyBuilder} after applying the result.
 */
public final class FaceCreationProcessor {

    private static final Logger logger = LoggerFactory.getLogger(FaceCreationProcessor.class);

    /**
     * Result of a face creation operation.
     *
     * @param newIndices           Updated triangle index array (replaces the old one entirely)
     * @param newTriangleToFaceId  Updated triangle-to-face-ID mapping array
     * @param newFaceCount         Face ID upper bound after creation (for iteration)
     * @param newFaceId            The face ID assigned to the newly created face
     * @param success              Whether the operation succeeded
     * @param errorMessage         Error description on failure
     */
    public record FaceCreationResult(
        int[] newIndices,
        int[] newTriangleToFaceId,
        int newFaceCount,
        int newFaceId,
        boolean success,
        String errorMessage
    ) {
        public static FaceCreationResult success(int[] newIndices, int[] newTriangleToFaceId,
                                                  int newFaceCount, int newFaceId) {
            return new FaceCreationResult(newIndices, newTriangleToFaceId, newFaceCount, newFaceId, true, null);
        }

        public static FaceCreationResult failure(String errorMessage) {
            return new FaceCreationResult(null, null, 0, -1, false, errorMessage);
        }
    }

    /**
     * Create a new face from selected unique vertices.
     * Vertices must be provided in the desired winding order (selection order).
     *
     * @param selectedUniqueVertices Unique vertex indices forming the polygon, in winding order
     * @param topology               Current mesh topology for vertex resolution
     * @param vertexManager          Vertex data manager for index array access
     * @param faceMapper             Face mapper for triangle-to-face ID lookups
     * @return FaceCreationResult with updated indices and face mapping
     */
    public FaceCreationResult createFace(
            int[] selectedUniqueVertices,
            MeshTopology topology,
            IVertexDataManager vertexManager,
            ITriangleFaceMapper faceMapper) {

        // --- Validate inputs ---
        if (topology == null) {
            return FaceCreationResult.failure("Topology not available");
        }
        if (vertexManager == null || faceMapper == null) {
            return FaceCreationResult.failure("Invalid parameters: null manager or mapper");
        }
        if (selectedUniqueVertices == null || selectedUniqueVertices.length < 3) {
            return FaceCreationResult.failure("Face creation requires at least 3 selected vertices");
        }

        // Check for duplicate vertices in the selection
        Set<Integer> uniqueCheck = new HashSet<>();
        for (int uniqueIdx : selectedUniqueVertices) {
            if (!uniqueCheck.add(uniqueIdx)) {
                return FaceCreationResult.failure(
                    "Duplicate vertex " + uniqueIdx + " in selection");
            }
        }

        // --- Resolve unique vertex indices to mesh vertex indices ---
        Map<Integer, Integer> uniqueToMesh = resolveUniqueToMeshIndices(
            selectedUniqueVertices, topology);

        if (uniqueToMesh.size() != selectedUniqueVertices.length) {
            return FaceCreationResult.failure(
                "Could not resolve mesh indices for all selected vertices");
        }

        // --- Get existing data ---
        int[] oldIndices = vertexManager.getIndices();
        int oldTriangleCount = (oldIndices != null) ? oldIndices.length / 3 : 0;

        // --- Allocate new face ID ---
        int newFaceId = faceMapper.getFaceIdUpperBound();

        // --- Fan-triangulate the new polygon ---
        List<Integer> newTriangleIndices = new ArrayList<>();
        List<Integer> newTriangleFaceIds = new ArrayList<>();

        fanTriangulate(selectedUniqueVertices, uniqueToMesh, newFaceId,
            newTriangleIndices, newTriangleFaceIds);

        if (newTriangleIndices.isEmpty()) {
            return FaceCreationResult.failure("Fan triangulation produced no triangles");
        }

        // --- Build combined index and face-ID arrays ---
        int oldIndexCount = (oldIndices != null) ? oldIndices.length : 0;
        int[] combinedIndices = new int[oldIndexCount + newTriangleIndices.size()];
        int[] combinedFaceIds = new int[oldTriangleCount + newTriangleFaceIds.size()];

        // Copy existing indices
        if (oldIndices != null) {
            System.arraycopy(oldIndices, 0, combinedIndices, 0, oldIndexCount);
        }

        // Copy existing face IDs
        int[] oldFaceIds = faceMapper.getMappingCopy();
        if (oldFaceIds != null) {
            System.arraycopy(oldFaceIds, 0, combinedFaceIds, 0,
                Math.min(oldFaceIds.length, oldTriangleCount));
        }

        // Append new triangle indices
        for (int i = 0; i < newTriangleIndices.size(); i++) {
            combinedIndices[oldIndexCount + i] = newTriangleIndices.get(i);
        }

        // Append new face IDs
        for (int i = 0; i < newTriangleFaceIds.size(); i++) {
            combinedFaceIds[oldTriangleCount + i] = newTriangleFaceIds.get(i);
        }

        int newFaceCount = newFaceId + 1;

        logger.info("Face creation complete: face {} created from {} vertices, "
                + "{} -> {} triangles, face ID upper bound {}",
            newFaceId, selectedUniqueVertices.length,
            oldTriangleCount, combinedIndices.length / 3, newFaceCount);

        return FaceCreationResult.success(combinedIndices, combinedFaceIds, newFaceCount, newFaceId);
    }

    /**
     * Resolve unique vertex indices to mesh vertex indices for the new face.
     *
     * <p>Each unique vertex may map to multiple mesh vertices (one per adjacent face,
     * with potentially different UVs). For a newly created face we pick the first
     * available mesh index — UV assignment for the new face is a separate concern.
     *
     * @param uniqueVertices Unique vertex indices to resolve
     * @param topology       Topology providing the unique-to-mesh mapping
     * @return Map from unique vertex index to a mesh vertex index
     */
    private Map<Integer, Integer> resolveUniqueToMeshIndices(
            int[] uniqueVertices, MeshTopology topology) {

        Map<Integer, Integer> mapping = new HashMap<>();

        for (int uniqueIdx : uniqueVertices) {
            int[] meshIndices = topology.getMeshIndicesForUniqueVertex(uniqueIdx);
            if (meshIndices == null || meshIndices.length == 0) {
                logger.error("No mesh indices found for unique vertex {}", uniqueIdx);
                continue;
            }
            mapping.put(uniqueIdx, meshIndices[0]);
        }

        return mapping;
    }

    /**
     * Fan-triangulate a polygon from its first vertex, emitting mesh-level indices.
     *
     * <p>Polygon {@code [v0, v1, v2, ..., vK]} produces triangles:
     * {@code (v0,v1,v2), (v0,v2,v3), ..., (v0,v(K-1),vK)}
     *
     * @param uniqueVertices   Unique vertex indices forming the polygon (in winding order)
     * @param uniqueToMeshMap  Mapping from unique vertex index to a mesh vertex index
     * @param faceId           Face ID to assign to all produced triangles
     * @param outIndices       Accumulator for mesh-level triangle indices
     * @param outFaceIds       Accumulator for triangle-to-face-ID entries
     */
    private void fanTriangulate(int[] uniqueVertices, Map<Integer, Integer> uniqueToMeshMap,
                                int faceId, List<Integer> outIndices, List<Integer> outFaceIds) {

        int v0Unique = uniqueVertices[0];
        Integer v0Mesh = uniqueToMeshMap.get(v0Unique);
        if (v0Mesh == null) {
            logger.error("No mesh index found for fan hub vertex {} — cannot triangulate", v0Unique);
            return;
        }

        for (int i = 1; i < uniqueVertices.length - 1; i++) {
            int viUnique = uniqueVertices[i];
            int vjUnique = uniqueVertices[i + 1];

            Integer viMesh = uniqueToMeshMap.get(viUnique);
            Integer vjMesh = uniqueToMeshMap.get(vjUnique);

            if (viMesh == null || vjMesh == null) {
                logger.error("Missing mesh index for unique vertex {} or {} — skipping triangle",
                    viUnique, vjUnique);
                continue;
            }

            outIndices.add(v0Mesh);
            outIndices.add(viMesh);
            outIndices.add(vjMesh);
            outFaceIds.add(faceId);
        }
    }
}
