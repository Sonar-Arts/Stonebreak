package com.openmason.main.systems.rendering.model.gmr.uv;

import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;
import com.openmason.main.systems.rendering.model.gmr.topology.MeshEdge;
import com.openmason.main.systems.rendering.model.gmr.topology.MeshFace;
import com.openmason.main.systems.rendering.model.gmr.topology.MeshTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Processor for inserting an edge between two existing vertices on the same face.
 * Splits each qualifying shared face into two sub-polygons along the new edge,
 * then fan-triangulates each sub-polygon.
 *
 * <p>No new vertices are created — only the index array and triangle-to-face mapping change.
 * Vertex positions, texture coordinates, and vertex count remain untouched.
 *
 * <p>This is the "J key" / "connect vertices" operation from Blender.
 */
public final class EdgeInsertionProcessor {

    private static final Logger logger = LoggerFactory.getLogger(EdgeInsertionProcessor.class);

    /**
     * Result of an edge insertion operation.
     *
     * @param newIndices           Updated triangle index array (replaces the old one entirely)
     * @param newTriangleToFaceId  Updated triangle-to-face-ID mapping array
     * @param newFaceCount         Total face count after insertion
     * @param success              Whether the operation succeeded
     * @param errorMessage         Error description on failure
     */
    public record InsertionResult(
        int[] newIndices,
        int[] newTriangleToFaceId,
        int newFaceCount,
        boolean success,
        String errorMessage
    ) {
        public static InsertionResult success(int[] newIndices, int[] newTriangleToFaceId, int newFaceCount) {
            return new InsertionResult(newIndices, newTriangleToFaceId, newFaceCount, true, null);
        }

        public static InsertionResult failure(String errorMessage) {
            return new InsertionResult(null, null, 0, false, errorMessage);
        }
    }

    /**
     * Insert an edge between two unique vertices, splitting all shared faces
     * where the vertices are non-adjacent.
     *
     * @param uniqueVertexA     First unique vertex index
     * @param uniqueVertexB     Second unique vertex index
     * @param topology          Current mesh topology for adjacency queries
     * @param vertexManager     Vertex data manager for index array access
     * @param faceMapper        Face mapper for triangle-to-face ID lookups
     * @param faceTextureManager Face texture manager for UV propagation (nullable)
     * @return InsertionResult with updated indices and face mapping
     */
    public InsertionResult insertEdge(
            int uniqueVertexA, int uniqueVertexB,
            MeshTopology topology,
            IVertexDataManager vertexManager,
            ITriangleFaceMapper faceMapper,
            IFaceTextureManager faceTextureManager) {

        if (topology == null) {
            return InsertionResult.failure("Topology not available");
        }
        if (vertexManager == null || faceMapper == null) {
            return InsertionResult.failure("Invalid parameters: null manager or mapper");
        }
        if (uniqueVertexA == uniqueVertexB) {
            return InsertionResult.failure("Cannot insert edge: same vertex selected twice");
        }

        // Find shared faces between the two vertices
        List<Integer> facesA = topology.elementAdjacencyQuery().getFacesForVertex(uniqueVertexA);
        List<Integer> facesB = topology.elementAdjacencyQuery().getFacesForVertex(uniqueVertexB);

        Set<Integer> facesOfB = new HashSet<>(facesB);
        List<Integer> sharedFaceIds = new ArrayList<>();
        for (int faceId : facesA) {
            if (facesOfB.contains(faceId)) {
                sharedFaceIds.add(faceId);
            }
        }

        if (sharedFaceIds.isEmpty()) {
            return InsertionResult.failure("Vertices do not share a common face");
        }

        // Check for non-adjacency: if an edge already exists between them, skip that face
        MeshEdge existingEdge = topology.getEdgeByVertices(uniqueVertexA, uniqueVertexB);

        // Collect faces where the vertices are non-adjacent (no shared edge in that face)
        // Reject triangles (< 4 vertices) since any two vertices in a triangle are adjacent
        List<Integer> qualifyingFaceIds = new ArrayList<>();
        for (int faceId : sharedFaceIds) {
            MeshFace face = topology.getFace(faceId);
            if (face == null || face.vertexCount() < 4) {
                continue;
            }

            if (existingEdge != null && face.containsEdge(existingEdge.edgeId())) {
                logger.debug("Skipping face {} — edge already exists between vertices {} and {}",
                    faceId, uniqueVertexA, uniqueVertexB);
                continue;
            }

            qualifyingFaceIds.add(faceId);
        }

        if (qualifyingFaceIds.isEmpty()) {
            return InsertionResult.failure("Edge already exists between the selected vertices in all shared faces");
        }

        // Build the set of face IDs being split for fast lookup
        Set<Integer> splittingFaceIds = new HashSet<>(qualifyingFaceIds);

        // Get current data
        int[] oldIndices = vertexManager.getIndices();
        if (oldIndices == null || oldIndices.length == 0) {
            return InsertionResult.failure("No index data available");
        }

        int oldTriangleCount = oldIndices.length / 3;

        // Determine the next face ID to assign
        int nextFaceId = faceMapper.getFaceIdUpperBound();

        // Build new index and face-ID arrays
        List<Integer> newIndices = new ArrayList<>();
        List<Integer> newFaceIds = new ArrayList<>();

        // Track which faces have already been processed (split) to avoid
        // re-emitting their old triangles when encountered later in the loop
        Set<Integer> processedFaceIds = new HashSet<>();

        for (int t = 0; t < oldTriangleCount; t++) {
            int faceId = faceMapper.getOriginalFaceIdForTriangle(t);

            if (!splittingFaceIds.contains(faceId)) {
                // Not a face being split — keep original triangle
                newIndices.add(oldIndices[t * 3]);
                newIndices.add(oldIndices[t * 3 + 1]);
                newIndices.add(oldIndices[t * 3 + 2]);
                newFaceIds.add(faceId);
                continue;
            }

            // This triangle belongs to a face being split.
            // If already processed, skip (old triangles replaced by new split triangles).
            if (processedFaceIds.contains(faceId)) {
                continue;
            }

            // Mark as processed so subsequent triangles of this face are skipped
            processedFaceIds.add(faceId);

            // Get the face polygon from topology
            MeshFace face = topology.getFace(faceId);
            int[] polyUniqueVerts = face.vertexIndices();
            int polyLen = polyUniqueVerts.length;

            // Find positions of A and B in the polygon winding
            int posA = -1;
            int posB = -1;
            for (int p = 0; p < polyLen; p++) {
                if (polyUniqueVerts[p] == uniqueVertexA) posA = p;
                if (polyUniqueVerts[p] == uniqueVertexB) posB = p;
            }

            if (posA < 0 || posB < 0) {
                logger.warn("Face {} does not contain both vertices — re-emitting original triangles", faceId);
                reemitFaceTriangles(faceId, oldIndices, faceMapper, oldTriangleCount, newIndices, newFaceIds);
                continue;
            }

            // Ensure posI < posJ for consistent splitting
            int posI = Math.min(posA, posB);
            int posJ = Math.max(posA, posB);

            // Build sub-polygon A: vertices from posI to posJ inclusive
            List<Integer> subPolyA = new ArrayList<>();
            for (int p = posI; p <= posJ; p++) {
                subPolyA.add(polyUniqueVerts[p]);
            }

            // Build sub-polygon B: vertices from posJ wrapping to posI inclusive
            List<Integer> subPolyB = new ArrayList<>();
            for (int p = posJ; p != posI; p = (p + 1) % polyLen) {
                subPolyB.add(polyUniqueVerts[p]);
            }
            subPolyB.add(polyUniqueVerts[posI]);

            // Collect all mesh indices used in this face's original triangles
            // to build a unique-to-mesh lookup for this face (scans all triangles for robustness)
            int[] meshIndicesForFace = collectMeshIndicesForFace(faceId, oldIndices, faceMapper, oldTriangleCount);

            // Build unique -> mesh index mapping for this face.
            // For vertices with multiple mesh instances (UV seams), we pick the first mesh index
            // found in this face's triangles, which preserves correct UVs for most cases.
            Map<Integer, Integer> uniqueToMeshForFace = new HashMap<>();
            for (int meshIdx : meshIndicesForFace) {
                int uniqueIdx = topology.getUniqueIndexForMeshVertex(meshIdx);
                if (uniqueIdx >= 0 && !uniqueToMeshForFace.containsKey(uniqueIdx)) {
                    uniqueToMeshForFace.put(uniqueIdx, meshIdx);
                }
            }

            // Fan-triangulate sub-polygon A (keeps original face ID)
            fanTriangulate(subPolyA, uniqueToMeshForFace, faceId, newIndices, newFaceIds);

            // Fan-triangulate sub-polygon B (gets new face ID)
            int newFaceIdForB = nextFaceId++;
            fanTriangulate(subPolyB, uniqueToMeshForFace, newFaceIdForB, newIndices, newFaceIds);

            // Propagate UV data from parent face to both children
            if (faceTextureManager != null) {
                SplitUVParams splitParams = computeSplitUVParams(
                    uniqueVertexA, uniqueVertexB, polyUniqueVerts, topology, vertexManager);
                faceTextureManager.propagateSplitUV(
                    faceId, faceId, newFaceIdForB,
                    splitParams.t(), splitParams.horizontal());
            }

            logger.debug("Split face {} ({}-gon) into face {} ({}-gon) and face {} ({}-gon)",
                faceId, polyLen, faceId, subPolyA.size(), newFaceIdForB, subPolyB.size());
        }

        // Convert to arrays
        int[] newIndicesArray = newIndices.stream().mapToInt(Integer::intValue).toArray();
        int[] newFaceIdArray = newFaceIds.stream().mapToInt(Integer::intValue).toArray();
        int totalFaceCount = nextFaceId;

        logger.info("Edge insertion complete: {} -> {} triangles, {} -> {} faces",
            oldTriangleCount, newIndicesArray.length / 3,
            faceMapper.getOriginalFaceCount(), totalFaceCount);

        return InsertionResult.success(newIndicesArray, newFaceIdArray, totalFaceCount);
    }

    /**
     * Re-emit all original triangles belonging to a face (fallback when split fails).
     * Scans from index 0 for robustness against non-contiguous triangle ordering.
     */
    private void reemitFaceTriangles(int faceId, int[] oldIndices,
                                      ITriangleFaceMapper faceMapper, int totalTriangles,
                                      List<Integer> outIndices, List<Integer> outFaceIds) {
        for (int t = 0; t < totalTriangles; t++) {
            if (faceMapper.getOriginalFaceIdForTriangle(t) == faceId) {
                outIndices.add(oldIndices[t * 3]);
                outIndices.add(oldIndices[t * 3 + 1]);
                outIndices.add(oldIndices[t * 3 + 2]);
                outFaceIds.add(faceId);
            }
        }
    }

    /**
     * Collect all mesh vertex indices used in the triangles of a specific face.
     * Scans from index 0 for robustness against non-contiguous triangle ordering.
     */
    private int[] collectMeshIndicesForFace(int faceId, int[] oldIndices,
                                             ITriangleFaceMapper faceMapper, int totalTriangles) {
        List<Integer> meshIndices = new ArrayList<>();
        for (int t = 0; t < totalTriangles; t++) {
            if (faceMapper.getOriginalFaceIdForTriangle(t) == faceId) {
                meshIndices.add(oldIndices[t * 3]);
                meshIndices.add(oldIndices[t * 3 + 1]);
                meshIndices.add(oldIndices[t * 3 + 2]);
            }
        }
        return meshIndices.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Parameters for UV propagation during a face split.
     *
     * @param t          Parametric split position (0..1) along the perpendicular axis
     * @param horizontal true if the split edge runs along V (dividing the U range)
     */
    private record SplitUVParams(float t, boolean horizontal) {}

    /**
     * Compute the parametric split position and axis orientation for UV propagation.
     *
     * <p>Projects the split edge onto the face's dominant 2D plane (using the same
     * axis selection as {@link PerFaceUVCoordinateGenerator}) and determines:
     * <ul>
     *   <li>{@code t}: where the edge midpoint falls along the perpendicular axis (0..1)</li>
     *   <li>{@code horizontal}: whether the edge runs along V (splitting U) or along U (splitting V)</li>
     * </ul>
     */
    private SplitUVParams computeSplitUVParams(
            int uniqueVertexA, int uniqueVertexB,
            int[] polyUniqueVerts,
            MeshTopology topology,
            IVertexDataManager vertexManager) {

        float[] vertices = vertexManager.getVertices();

        // Get positions of the split edge vertices
        int meshA = topology.getMeshIndicesForUniqueVertex(uniqueVertexA)[0];
        int meshB = topology.getMeshIndicesForUniqueVertex(uniqueVertexB)[0];
        float ax = vertices[meshA * 3], ay = vertices[meshA * 3 + 1], az = vertices[meshA * 3 + 2];
        float bx = vertices[meshB * 3], by = vertices[meshB * 3 + 1], bz = vertices[meshB * 3 + 2];

        // Compute face normal from first 3 polygon vertices
        int m0 = topology.getMeshIndicesForUniqueVertex(polyUniqueVerts[0])[0];
        int m1 = topology.getMeshIndicesForUniqueVertex(polyUniqueVerts[1])[0];
        int m2 = topology.getMeshIndicesForUniqueVertex(polyUniqueVerts[2])[0];

        float e1x = vertices[m1 * 3]     - vertices[m0 * 3];
        float e1y = vertices[m1 * 3 + 1] - vertices[m0 * 3 + 1];
        float e1z = vertices[m1 * 3 + 2] - vertices[m0 * 3 + 2];
        float e2x = vertices[m2 * 3]     - vertices[m0 * 3];
        float e2y = vertices[m2 * 3 + 1] - vertices[m0 * 3 + 1];
        float e2z = vertices[m2 * 3 + 2] - vertices[m0 * 3 + 2];

        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;

        float absNx = Math.abs(nx), absNy = Math.abs(ny), absNz = Math.abs(nz);

        // Determine U and V projection axes (mirrors PerFaceUVCoordinateGenerator logic)
        int uAxis = (absNx >= absNy && absNx >= absNz) ? 1 : 0;
        int vAxis = (absNz >= absNx && absNz >= absNy) ? 1 : 2;

        // Compute face bounds along projection axes
        float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE;
        float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
        for (int uv : polyUniqueVerts) {
            int mi = topology.getMeshIndicesForUniqueVertex(uv)[0];
            float u = vertices[mi * 3 + uAxis];
            float v = vertices[mi * 3 + vAxis];
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }

        // Project split edge onto UV axes to determine dominant direction
        float[] posA = {ax, ay, az};
        float[] posB = {bx, by, bz};
        float edgeU = Math.abs(posB[uAxis] - posA[uAxis]);
        float edgeV = Math.abs(posB[vAxis] - posA[vAxis]);

        // Edge runs along V → horizontal split (divides U range); else vertical
        boolean horizontal = (edgeV >= edgeU);

        // Compute t: edge midpoint's parametric position along the perpendicular axis
        float rangeU = maxU - minU;
        float rangeV = maxV - minV;

        float t;
        if (horizontal) {
            float edgeMidU = (posA[uAxis] + posB[uAxis]) / 2.0f;
            t = (rangeU > 0.0001f) ? (edgeMidU - minU) / rangeU : 0.5f;
        } else {
            float edgeMidV = (posA[vAxis] + posB[vAxis]) / 2.0f;
            t = (rangeV > 0.0001f) ? (edgeMidV - minV) / rangeV : 0.5f;
        }

        return new SplitUVParams(Math.clamp(t, 0.0f, 1.0f), horizontal);
    }

    /**
     * Fan-triangulate a polygon from its first vertex, emitting mesh-level indices.
     *
     * <p>Polygon {@code [v0, v1, v2, ..., vK]} produces triangles:
     * {@code (v0,v1,v2), (v0,v2,v3), ..., (v0,v(K-1),vK)}
     *
     * @param polygon           Unique vertex indices forming the polygon
     * @param uniqueToMeshMap   Mapping from unique vertex index to a mesh vertex index for this face
     * @param faceId            Face ID to assign to all produced triangles
     * @param outIndices        Accumulator for mesh-level triangle indices
     * @param outFaceIds        Accumulator for triangle-to-face-ID entries
     */
    private void fanTriangulate(List<Integer> polygon, Map<Integer, Integer> uniqueToMeshMap,
                                 int faceId, List<Integer> outIndices, List<Integer> outFaceIds) {
        if (polygon.size() < 3) {
            logger.warn("Sub-polygon has fewer than 3 vertices — cannot triangulate");
            return;
        }

        int v0Unique = polygon.get(0);
        Integer v0Mesh = uniqueToMeshMap.get(v0Unique);
        if (v0Mesh == null) {
            logger.error("No mesh index found for unique vertex {} — skipping polygon", v0Unique);
            return;
        }

        for (int i = 1; i < polygon.size() - 1; i++) {
            int viUnique = polygon.get(i);
            int vjUnique = polygon.get(i + 1);

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
