package com.openmason.main.systems.rendering.model.gmr.topology;

import com.openmason.main.systems.rendering.model.gmr.extraction.FaceTriangleQuery;
import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;
import com.openmason.main.systems.rendering.model.gmr.mapping.IUniqueVertexMapper;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Static factory that builds {@link MeshTopology} from GMR's flat vertex/index arrays.
 *
 * <p>Centralizes the edge-building logic previously duplicated in
 * {@code EdgeRenderer.rebuildFromModel()} (lines 613-672). The algorithm:
 * <ol>
 *   <li>Iterates triangles from {@code indices[]} + {@link ITriangleFaceMapper}</li>
 *   <li>For each triangle edge, computes canonical key and collects face IDs</li>
 *   <li>Uses {@link FaceTriangleQuery#extractFaceVertexIndices} per face for ordered polygon vertices</li>
 *   <li>Filters edges: keeps boundary (2+ faces) and open-mesh outline edges</li>
 *   <li>Assigns stable edgeId (0..N-1), builds adjacency indices</li>
 *   <li>Returns immutable {@link MeshTopology}</li>
 * </ol>
 *
 * <p>Thread-safe: stateless factory methods.
 */
public final class MeshTopologyBuilder {

    private static final Logger logger = LoggerFactory.getLogger(MeshTopologyBuilder.class);

    private MeshTopologyBuilder() {
        // Utility class
    }

    /**
     * Build a MeshTopology from GMR's flat arrays.
     *
     * @param vertices     Vertex positions (x,y,z interleaved)
     * @param indices      Triangle index buffer
     * @param faceMapper   Triangle-to-face mapping
     * @param uniqueMapper Unique vertex mapping
     * @return Immutable MeshTopology, or null if input is invalid
     */
    public static MeshTopology build(float[] vertices, int[] indices,
                                     ITriangleFaceMapper faceMapper,
                                     IUniqueVertexMapper uniqueMapper) {
        if (vertices == null || vertices.length == 0 ||
            indices == null || indices.length == 0 ||
            faceMapper == null || !faceMapper.hasMapping() ||
            uniqueMapper == null || !uniqueMapper.hasMapping()) {
            logger.debug("Cannot build topology: missing input data");
            return null;
        }

        int triangleCount = indices.length / 3;
        int originalFaceCount = faceMapper.getOriginalFaceCount();
        int faceIdUpperBound = faceMapper.getFaceIdUpperBound();

        // Step 1: Build edge -> face ID sets (same logic as EdgeRenderer.rebuildFromModel)
        Map<Long, Set<Integer>> edgeToFaceIds = new HashMap<>();
        Map<Long, int[]> edgeToVertices = new HashMap<>();

        for (int t = 0; t < triangleCount; t++) {
            int i0 = indices[t * 3];
            int i1 = indices[t * 3 + 1];
            int i2 = indices[t * 3 + 2];

            int u0 = uniqueMapper.getUniqueIndexForMeshVertex(i0);
            int u1 = uniqueMapper.getUniqueIndexForMeshVertex(i1);
            int u2 = uniqueMapper.getUniqueIndexForMeshVertex(i2);

            int faceId = faceMapper.getOriginalFaceIdForTriangle(t);

            trackEdgeFace(edgeToFaceIds, edgeToVertices, u0, u1, faceId);
            trackEdgeFace(edgeToFaceIds, edgeToVertices, u1, u2, faceId);
            trackEdgeFace(edgeToFaceIds, edgeToVertices, u2, u0, faceId);
        }

        // Step 2: Filter edges - keep boundary (shared by 2+ faces) and open-mesh outline edges
        List<long[]> filteredEdgeKeys = new ArrayList<>(); // [canonicalKey]
        for (Map.Entry<Long, Set<Integer>> entry : edgeToFaceIds.entrySet()) {
            int faceUsageCount = entry.getValue().size();
            if (faceUsageCount > 1) {
                // Boundary edge between different faces - always keep
                filteredEdgeKeys.add(new long[] { entry.getKey() });
            } else {
                // Single-face edge: keep on single-face meshes (open outline),
                // skip on multi-face meshes (internal diagonal from fan triangulation)
                if (originalFaceCount <= 1) {
                    filteredEdgeKeys.add(new long[] { entry.getKey() });
                }
            }
        }

        // Step 3: Build MeshEdge array with stable IDs
        int edgeCount = filteredEdgeKeys.size();
        MeshEdge[] edges = new MeshEdge[edgeCount];
        Map<Long, Integer> edgeKeyToId = new HashMap<>(edgeCount * 2);

        for (int e = 0; e < edgeCount; e++) {
            long key = filteredEdgeKeys.get(e)[0];
            int[] verts = edgeToVertices.get(key);
            Set<Integer> faceIds = edgeToFaceIds.get(key);

            int[] adjacentFaceArray = faceIds.stream().mapToInt(Integer::intValue).toArray();
            edges[e] = MeshEdge.of(e, verts[0], verts[1], adjacentFaceArray);
            edgeKeyToId.put(key, e);
        }

        // Step 4: Build MeshFace array with vertex indices and edge IDs
        // Use FaceTriangleQuery to get ordered polygon vertices per face
        List<MeshFace> faceList = new ArrayList<>();
        for (int faceId = 0; faceId < faceIdUpperBound; faceId++) {
            List<Integer> triangles = FaceTriangleQuery.findTrianglesForFace(faceId, indices, faceMapper);
            if (triangles.isEmpty()) {
                continue;
            }

            Integer[] meshVertexIndices = FaceTriangleQuery.extractFaceVertexIndices(triangles, indices);

            // Convert mesh vertex indices to unique vertex indices
            int[] uniqueVertexIndices = new int[meshVertexIndices.length];
            for (int i = 0; i < meshVertexIndices.length; i++) {
                uniqueVertexIndices[i] = uniqueMapper.getUniqueIndexForMeshVertex(meshVertexIndices[i]);
            }

            // Find edge IDs for this face's outline
            int[] faceEdgeIds = new int[uniqueVertexIndices.length];
            for (int i = 0; i < uniqueVertexIndices.length; i++) {
                int v0 = uniqueVertexIndices[i];
                int v1 = uniqueVertexIndices[(i + 1) % uniqueVertexIndices.length];
                long edgeKey = MeshEdge.canonicalKey(v0, v1);
                Integer edgeId = edgeKeyToId.get(edgeKey);
                faceEdgeIds[i] = edgeId != null ? edgeId : -1;
            }

            faceList.add(new MeshFace(faceId, uniqueVertexIndices, faceEdgeIds));
        }

        MeshFace[] faces = faceList.toArray(new MeshFace[0]);

        // Step 5: Build vertex adjacency indices
        int uniqueVertexCount = uniqueMapper.getUniqueVertexCount();
        List<List<Integer>> vertexToEdges = new ArrayList<>(uniqueVertexCount);
        List<List<Integer>> vertexToFaces = new ArrayList<>(uniqueVertexCount);
        for (int i = 0; i < uniqueVertexCount; i++) {
            vertexToEdges.add(new ArrayList<>());
            vertexToFaces.add(new ArrayList<>());
        }

        for (MeshEdge edge : edges) {
            if (edge.vertexA() >= 0 && edge.vertexA() < uniqueVertexCount) {
                vertexToEdges.get(edge.vertexA()).add(edge.edgeId());
            }
            if (edge.vertexB() >= 0 && edge.vertexB() < uniqueVertexCount) {
                vertexToEdges.get(edge.vertexB()).add(edge.edgeId());
            }
        }

        for (MeshFace face : faces) {
            for (int v : face.vertexIndices()) {
                if (v >= 0 && v < uniqueVertexCount) {
                    List<Integer> faceIdsForVertex = vertexToFaces.get(v);
                    if (!faceIdsForVertex.contains(face.faceId())) {
                        faceIdsForVertex.add(face.faceId());
                    }
                }
            }
        }

        // Make adjacency lists unmodifiable
        List<List<Integer>> immutableVertexToEdges = new ArrayList<>(uniqueVertexCount);
        List<List<Integer>> immutableVertexToFaces = new ArrayList<>(uniqueVertexCount);
        for (int i = 0; i < uniqueVertexCount; i++) {
            immutableVertexToEdges.add(Collections.unmodifiableList(vertexToEdges.get(i)));
            immutableVertexToFaces.add(Collections.unmodifiableList(vertexToFaces.get(i)));
        }

        // Step 6: Detect uniform topology
        boolean uniform = true;
        int firstCount = faces.length > 0 ? faces[0].vertexCount() : 0;
        for (int i = 1; i < faces.length; i++) {
            if (faces[i].vertexCount() != firstCount) {
                uniform = false;
                break;
            }
        }

        // Step 7: Extract mapper data into topology
        int meshVertexCount = vertices.length / 3;

        // Copy mesh-to-unique mapping
        int[] meshToUniqueMapping = new int[meshVertexCount];
        for (int i = 0; i < meshVertexCount; i++) {
            meshToUniqueMapping[i] = uniqueMapper.getUniqueIndexForMeshVertex(i);
        }

        // Copy unique-to-mesh mapping
        int[][] uniqueToMeshIndices = new int[uniqueVertexCount][];
        for (int i = 0; i < uniqueVertexCount; i++) {
            int[] meshIndices = uniqueMapper.getMeshIndicesForUniqueVertex(i);
            uniqueToMeshIndices[i] = meshIndices != null ? meshIndices.clone() : new int[0];
        }

        // Copy triangle-to-face mapping
        int[] triangleToFaceId = faceMapper.getMappingCopy();

        // Step 8: Compute per-face normals
        Vector3f[] faceNormals = new Vector3f[faces.length];
        for (int i = 0; i < faces.length; i++) {
            faceNormals[i] = MeshTopology.computeNormal(
                    faces[i].vertexIndices(), uniqueToMeshIndices, vertices);
        }

        MeshTopology topology = new MeshTopology(
            edges, faces, faceNormals,
            Collections.unmodifiableMap(edgeKeyToId),
            immutableVertexToEdges,
            immutableVertexToFaces,
            uniform, firstCount,
            meshToUniqueMapping, uniqueToMeshIndices, uniqueVertexCount,
            triangleToFaceId, triangleCount
        );

        logger.debug("Built MeshTopology: {} edges, {} faces, {} unique verts, {} triangles, uniform={} (vpf={})",
            edgeCount, faces.length, uniqueVertexCount, triangleCount, uniform, firstCount);

        return topology;
    }

    /**
     * Track which faces use an edge. Same canonical key approach as EdgeRenderer.
     */
    private static void trackEdgeFace(Map<Long, Set<Integer>> edgeToFaceIds,
                                       Map<Long, int[]> edgeToVertices,
                                       int u0, int u1, int faceId) {
        if (u0 < 0 || u1 < 0 || u0 == u1) {
            return;
        }
        int min = Math.min(u0, u1);
        int max = Math.max(u0, u1);
        long key = ((long) min << 32) | (max & 0xFFFFFFFFL);

        edgeToFaceIds.computeIfAbsent(key, k -> new HashSet<>()).add(faceId);
        edgeToVertices.putIfAbsent(key, new int[] { min, max });
    }
}
