package com.openmason.engine.rendering.model.gmr.topology;

import com.openmason.engine.rendering.model.gmr.editable.EditableFace;
import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.RenderMesh;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Static factory that builds {@link MeshTopology} from the authoritative
 * {@link EditableMesh} and its derived {@link RenderMesh}.
 *
 * <p>Face loops and winding come from the editable mesh VERBATIM — nothing is
 * reconstructed from triangles, so the legacy boundary-walk/insertion-order
 * winding hazards and the "drop fan diagonals" edge-filter heuristic are gone:
 * every edge in the topology is a real polygon-boundary edge, including hole
 * outlines on multi-face open meshes (which the legacy filter wrongly dropped).
 *
 * <p>Index-mapping data ("unique" = editable vertex id, "mesh" = render corner
 * index) comes from the render mesh's exact corner maps — no epsilon welding.
 *
 * <p>Thread-safe: stateless factory methods.
 */
public final class MeshTopologyBuilder {

    private static final Logger logger = LoggerFactory.getLogger(MeshTopologyBuilder.class);

    private MeshTopologyBuilder() {
        // Utility class
    }

    /**
     * Build a MeshTopology.
     *
     * @param mesh       Authoritative mesh (loops + winding)
     * @param renderMesh Derived render mesh (corner maps, triangle→face, positions)
     * @return Immutable MeshTopology, or null for an empty mesh
     */
    public static MeshTopology build(EditableMesh mesh, RenderMesh renderMesh) {
        if (mesh == null || renderMesh == null || mesh.faceCount() == 0
                || renderMesh.cornerCount() == 0) {
            logger.debug("Cannot build topology: empty mesh");
            return null;
        }

        int faceIdUpperBound = mesh.faceIdUpperBound();
        int uniqueVertexCount = mesh.vertexCount();
        float[] vertices = renderMesh.vertices();
        int triangleCount = renderMesh.triangleCount();

        // Step 1: Collect edges from face loops (undirected, keyed by vertex ids).
        Map<Long, Set<Integer>> edgeToFaceIds = new LinkedHashMap<>();
        Map<Long, int[]> edgeToVertices = new HashMap<>();

        for (EditableFace face : mesh.faces()) {
            int n = face.loopLength();
            for (int i = 0; i < n; i++) {
                int a = face.vertexAt(i);
                int b = face.vertexAt((i + 1) % n);
                long key = MeshEdge.canonicalKey(a, b);
                edgeToFaceIds.computeIfAbsent(key, k -> new HashSet<>()).add(face.faceId());
                edgeToVertices.putIfAbsent(key, new int[]{Math.min(a, b), Math.max(a, b)});
            }
        }

        // Step 2: Build MeshEdge array with stable IDs — every loop edge is real.
        int edgeCount = edgeToFaceIds.size();
        MeshEdge[] edges = new MeshEdge[edgeCount];
        Map<Long, Integer> edgeKeyToId = new HashMap<>(edgeCount * 2);

        int e = 0;
        for (Map.Entry<Long, Set<Integer>> entry : edgeToFaceIds.entrySet()) {
            long key = entry.getKey();
            int[] verts = edgeToVertices.get(key);
            int[] adjacentFaceArray = entry.getValue().stream().mapToInt(Integer::intValue).toArray();
            edges[e] = MeshEdge.of(e, verts[0], verts[1], adjacentFaceArray);
            edgeKeyToId.put(key, e);
            e++;
        }

        // Step 3: Build MeshFace array directly from authoritative loops.
        List<MeshFace> faceList = new ArrayList<>(mesh.faceCount());
        for (EditableFace face : mesh.faces()) {
            int[] loop = face.loop();
            int[] faceEdgeIds = new int[loop.length];
            for (int i = 0; i < loop.length; i++) {
                long edgeKey = MeshEdge.canonicalKey(loop[i], loop[(i + 1) % loop.length]);
                Integer edgeId = edgeKeyToId.get(edgeKey);
                faceEdgeIds[i] = edgeId != null ? edgeId : -1;
            }
            faceList.add(new MeshFace(face.faceId(), loop, faceEdgeIds));
        }
        MeshFace[] faces = faceList.toArray(new MeshFace[0]);

        // Step 4: Build vertex adjacency indices.
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

        // Step 4b: Build face-to-face adjacency from edge adjacency.
        // Sized by faceIdUpperBound (not faces.length) to handle non-contiguous face IDs
        // after face deletion. Gap entries remain as empty sets.
        List<Set<Integer>> faceAdjSets = new ArrayList<>(faceIdUpperBound);
        for (int i = 0; i < faceIdUpperBound; i++) {
            faceAdjSets.add(new HashSet<>());
        }
        Map<Long, Integer> facePairToEdgeId = new HashMap<>();

        for (MeshEdge edge : edges) {
            int[] adjFaces = edge.adjacentFaceIds();
            for (int i = 0; i < adjFaces.length; i++) {
                for (int j = i + 1; j < adjFaces.length; j++) {
                    int fA = adjFaces[i];
                    int fB = adjFaces[j];
                    if (fA >= 0 && fA < faceIdUpperBound && fB >= 0 && fB < faceIdUpperBound) {
                        faceAdjSets.get(fA).add(fB);
                        faceAdjSets.get(fB).add(fA);
                        long pairKey = MeshGeometry.canonicalFacePairKey(fA, fB);
                        facePairToEdgeId.put(pairKey, edge.edgeId());
                    }
                }
            }
        }

        List<List<Integer>> faceToAdjacentFaces = new ArrayList<>(faceIdUpperBound);
        for (int i = 0; i < faceIdUpperBound; i++) {
            faceToAdjacentFaces.add(Collections.unmodifiableList(new ArrayList<>(faceAdjSets.get(i))));
        }

        // Step 5: Detect uniform topology
        boolean uniform = true;
        int firstCount = faces.length > 0 ? faces[0].vertexCount() : 0;
        for (int i = 1; i < faces.length; i++) {
            if (faces[i].vertexCount() != firstCount) {
                uniform = false;
                break;
            }
        }

        // Step 6: Index mapping — exact corner maps from the render mesh.
        int[] meshToUniqueMapping = renderMesh.cornerToVertexId().clone();
        int[][] vertexIdToCorners = renderMesh.vertexIdToCorners();
        int[][] uniqueToMeshIndices = new int[uniqueVertexCount][];
        for (int i = 0; i < uniqueVertexCount; i++) {
            uniqueToMeshIndices[i] = i < vertexIdToCorners.length
                ? vertexIdToCorners[i].clone() : new int[0];
        }
        int[] triangleToFaceId = renderMesh.triangleToFaceId().clone();

        // Step 7: Compute per-face normals, centroids, and areas.
        // Sized by faceIdUpperBound and indexed by face ID to handle gaps from deletion.
        // Gap entries remain null/0.0 (consumers must handle nulls).
        Vector3f[] faceNormals = new Vector3f[faceIdUpperBound];
        Vector3f[] faceCentroids = new Vector3f[faceIdUpperBound];
        float[] faceAreas = new float[faceIdUpperBound];
        for (MeshFace face : faces) {
            int fid = face.faceId();
            int[] verts = face.vertexIndices();
            faceNormals[fid] = MeshGeometry.computeNormal(verts, uniqueToMeshIndices, vertices);
            faceCentroids[fid] = MeshGeometry.computeCentroid(verts, uniqueToMeshIndices, vertices);
            faceAreas[fid] = MeshGeometry.computeArea(verts, uniqueToMeshIndices, vertices);
        }

        // Step 8: Compute per-vertex smooth normals (area-weighted average of adjacent face normals)
        Vector3f[] vertexNormals = new Vector3f[uniqueVertexCount];
        for (int v = 0; v < uniqueVertexCount; v++) {
            vertexNormals[v] = MeshGeometry.computeVertexNormal(
                immutableVertexToFaces.get(v), faceNormals, faceAreas
            );
        }

        // Step 9: Construct all 15 sub-services
        Map<Long, Integer> immutableEdgeKeyToId = Collections.unmodifiableMap(edgeKeyToId);
        List<List<Integer>> immutableFaceToAdjacentFaces = Collections.unmodifiableList(faceToAdjacentFaces);
        Map<Long, Integer> immutableFacePairToEdgeId = Collections.unmodifiableMap(facePairToEdgeId);

        IndexMappingQuery indexMappingQuery = new IndexMappingQuery(
                meshToUniqueMapping, uniqueToMeshIndices, uniqueVertexCount,
                triangleToFaceId, triangleCount);

        TopologyMetadataQuery topologyMetadataQuery = new TopologyMetadataQuery(
                uniform, firstCount, faces);

        // Build sparse face array indexed by face ID for all sub-services that
        // use face IDs as array indices. Gap entries (deleted faces) are null.
        // Sub-services must null-check when accessing by face ID.
        MeshFace[] facesById = new MeshFace[faceIdUpperBound];
        for (MeshFace face : faces) {
            facesById[face.faceId()] = face;
        }

        ElementAdjacencyQuery elementAdjacencyQuery = new ElementAdjacencyQuery(
                edges, facesById, immutableVertexToEdges, immutableVertexToFaces,
                immutableFaceToAdjacentFaces, immutableFacePairToEdgeId);

        // Face geometry cache (root of dirty-tracking chain)
        FaceGeometryCache faceGeometryCache = new FaceGeometryCache(
                facesById, uniqueToMeshIndices, faceNormals, faceCentroids,
                faceAreas, vertices);

        // Edge classifier (needs face normals from FaceGeometryCache)
        EdgeClassifier edgeClassifier = new EdgeClassifier(
                edges, EdgeClassifier.DEFAULT_THRESHOLD, faceNormals);

        // Compute initial dihedral angles for DihedralAngleCache
        float[] dihedralAngles = new float[edges.length];
        for (int i = 0; i < edges.length; i++) {
            int[] adjFaces = edges[i].adjacentFaceIds();
            if (adjFaces.length == 2
                    && adjFaces[0] >= 0 && adjFaces[0] < faceIdUpperBound
                    && adjFaces[1] >= 0 && adjFaces[1] < faceIdUpperBound
                    && faceNormals[adjFaces[0]] != null
                    && faceNormals[adjFaces[1]] != null) {
                dihedralAngles[i] = MeshGeometry.computeDihedralAngle(
                        faceNormals[adjFaces[0]], faceNormals[adjFaces[1]]);
            } else {
                dihedralAngles[i] = Float.NaN;
            }
        }

        DihedralAngleCache dihedralAngleCache = new DihedralAngleCache(
                edges, dihedralAngles, faceGeometryCache, edgeClassifier);

        VertexNormalCache vertexNormalCache = new VertexNormalCache(
                vertexNormals, faceGeometryCache, immutableVertexToFaces);

        VertexClassifier vertexClassifier = new VertexClassifier(
                edges, immutableVertexToEdges, uniform, firstCount);

        FaceEdgeTraversal faceEdgeTraversal = new FaceEdgeTraversal(facesById);

        EdgeLoopTracer edgeLoopTracer = new EdgeLoopTracer(edges, facesById);

        FaceLoopTracer faceLoopTracer = new FaceLoopTracer(edges, facesById);

        FaceIslandDetector faceIslandDetector = new FaceIslandDetector(
                faceIdUpperBound, immutableFaceToAdjacentFaces);

        VertexRingQuery vertexRingQuery = new VertexRingQuery(
                edges, facesById, immutableVertexToEdges, immutableVertexToFaces,
                immutableEdgeKeyToId);

        VertexAdjacencyQuery vertexAdjacencyQuery = new VertexAdjacencyQuery(
                immutableEdgeKeyToId, edges, immutableVertexToEdges);

        VertexBoundaryWalker vertexBoundaryWalker = new VertexBoundaryWalker(
                edges, immutableVertexToEdges);

        MeshTopology topology = new MeshTopology(
                edges, facesById, immutableEdgeKeyToId,
                indexMappingQuery, topologyMetadataQuery, elementAdjacencyQuery,
                faceGeometryCache, dihedralAngleCache, vertexNormalCache,
                edgeClassifier, vertexClassifier, faceEdgeTraversal,
                edgeLoopTracer, faceLoopTracer, faceIslandDetector,
                vertexRingQuery, vertexAdjacencyQuery, vertexBoundaryWalker);

        logger.debug("Built MeshTopology: {} edges, {} faces, {} vertices, {} triangles, uniform={} (vpf={})",
            edgeCount, faces.length, uniqueVertexCount, triangleCount, uniform, firstCount);

        return topology;
    }
}
