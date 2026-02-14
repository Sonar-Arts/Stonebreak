package com.openmason.main.systems.rendering.model.gmr.topology;

import org.joml.Vector3f;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thin coordinator composing focused topology services into a unified index.
 * Built by {@link MeshTopologyBuilder} from GMR's flat vertex/index arrays.
 *
 * <p>Callers can access sub-services directly for focused work:
 * <ul>
 *   <li>{@link #edgeClassifier()} — edge classification, auto-sharp, crease weights</li>
 *   <li>{@link #vertexClassifier()} — valence, boundary, interior, pole queries</li>
 *   <li>{@link #vertexRingQuery()} — connected vertices and ordered vertex ring traversal</li>
 *   <li>{@link #vertexAdjacencyQuery()} — vertex-to-vertex connectivity and neighbor counts</li>
 *   <li>{@link #faceEdgeTraversal()} — winding-aware directed edge traversal within faces</li>
 *   <li>{@link #edgeLoopTracer()} — edge loop and edge ring tracing across quad faces</li>
 *   <li>{@link #faceLoopTracer()} — face loop tracing across quad faces</li>
 *   <li>{@link #faceIslandDetector()} — connected component (island) detection for faces</li>
 *   <li>{@link MeshGeometry} — stateless geometry math (static utility)</li>
 * </ul>
 *
 * <p>All delegation methods are retained for backward compatibility.
 */
public class MeshTopology {

    // Core topology data
    private final MeshEdge[] edges;
    private final MeshFace[] faces;
    private final Map<Long, Integer> edgeKeyToId;
    private final List<List<Integer>> vertexToEdges;
    private final List<List<Integer>> vertexToFaces;
    private final List<List<Integer>> faceToAdjacentFaces;
    private final Map<Long, Integer> facePairToEdgeId;
    private final boolean uniformTopology;
    private final int uniformVerticesPerFace;

    // Embedded mapper data
    private final int[] meshToUniqueMapping;
    private final int[][] uniqueToMeshIndices;
    private final int uniqueVertexCount;
    private final int[] triangleToFaceId;
    private final int triangleCount;

    // Face geometry cache (normals, centroids, areas)
    private final Vector3f[] faceNormals;
    private final Vector3f[] faceCentroids;
    private final float[] faceAreas;

    // Vertex normal cache
    private final Vector3f[] vertexNormals;

    // Dihedral angles (per-edge, radians)
    private final float[] dihedralAngles;

    // Lazy recomputation dirty flags
    private final boolean[] faceDirty;
    private final boolean[] vertexNormalDirty;
    private final boolean[] edgeDirty;
    private float[] verticesRef;

    // Composed sub-services
    private final EdgeClassifier edgeClassifier;
    private final VertexClassifier vertexClassifier;
    private final FaceEdgeTraversal faceEdgeTraversal;
    private final EdgeLoopTracer edgeLoopTracer;
    private final FaceLoopTracer faceLoopTracer;
    private final FaceIslandDetector faceIslandDetector;
    private final VertexRingQuery vertexRingQuery;
    private final VertexAdjacencyQuery vertexAdjacencyQuery;

    /**
     * Package-private constructor used by MeshTopologyBuilder.
     */
    MeshTopology(MeshEdge[] edges, MeshFace[] faces,
                 Vector3f[] faceNormals, Vector3f[] faceCentroids, float[] faceAreas,
                 Vector3f[] vertexNormals,
                 Map<Long, Integer> edgeKeyToId,
                 List<List<Integer>> vertexToEdges,
                 List<List<Integer>> vertexToFaces,
                 List<List<Integer>> faceToAdjacentFaces,
                 Map<Long, Integer> facePairToEdgeId,
                 boolean uniformTopology, int uniformVerticesPerFace,
                 int[] meshToUniqueMapping, int[][] uniqueToMeshIndices,
                 int uniqueVertexCount,
                 int[] triangleToFaceId, int triangleCount,
                 float autoSharpThresholdRadians,
                 float[] vertices) {
        this.edges = edges;
        this.faces = faces;
        this.faceNormals = faceNormals;
        this.faceCentroids = faceCentroids;
        this.faceAreas = faceAreas;
        this.vertexNormals = vertexNormals;
        this.edgeKeyToId = edgeKeyToId;
        this.vertexToEdges = vertexToEdges;
        this.vertexToFaces = vertexToFaces;
        this.faceToAdjacentFaces = faceToAdjacentFaces;
        this.facePairToEdgeId = facePairToEdgeId;
        this.uniformTopology = uniformTopology;
        this.uniformVerticesPerFace = uniformVerticesPerFace;
        this.meshToUniqueMapping = meshToUniqueMapping;
        this.uniqueToMeshIndices = uniqueToMeshIndices;
        this.uniqueVertexCount = uniqueVertexCount;
        this.triangleToFaceId = triangleToFaceId;
        this.triangleCount = triangleCount;
        this.verticesRef = vertices;
        this.faceDirty = new boolean[faces.length];
        this.vertexNormalDirty = new boolean[uniqueVertexCount];
        this.edgeDirty = new boolean[edges.length];

        // Compute initial dihedral angles
        this.dihedralAngles = new float[edges.length];
        for (int i = 0; i < edges.length; i++) {
            dihedralAngles[i] = computeDihedralAngleForEdge(edges[i]);
        }

        // Compose sub-services
        this.edgeClassifier = new EdgeClassifier(edges, autoSharpThresholdRadians, faceNormals);
        this.vertexClassifier = new VertexClassifier(edges, vertexToEdges, uniformTopology, uniformVerticesPerFace);
        this.faceEdgeTraversal = new FaceEdgeTraversal(faces);
        this.edgeLoopTracer = new EdgeLoopTracer(edges, faces);
        this.faceLoopTracer = new FaceLoopTracer(edges, faces);
        this.faceIslandDetector = new FaceIslandDetector(faces.length, faceToAdjacentFaces);
        this.vertexRingQuery = new VertexRingQuery(edges, faces, vertexToEdges, vertexToFaces, edgeKeyToId);
        this.vertexAdjacencyQuery = new VertexAdjacencyQuery(edgeKeyToId, edges, vertexToEdges);
    }

    // =========================================================================
    // SUB-SERVICE ACCESSORS
    // =========================================================================

    /**
     * Get the edge classifier for direct access to edge classification,
     * auto-sharp threshold, and crease weight operations.
     *
     * @return The edge classifier (never null)
     */
    public EdgeClassifier edgeClassifier() {
        return edgeClassifier;
    }

    /**
     * Get the vertex classifier for direct access to valence,
     * boundary, interior, and pole queries.
     *
     * @return The vertex classifier (never null)
     */
    public VertexClassifier vertexClassifier() {
        return vertexClassifier;
    }

    /**
     * Get the face-edge traversal service for winding-aware directed
     * edge traversal within faces.
     *
     * @return The face-edge traversal service (never null)
     */
    public FaceEdgeTraversal faceEdgeTraversal() {
        return faceEdgeTraversal;
    }

    /**
     * Get the edge loop tracer for edge loop and edge ring
     * traversal across quad faces.
     *
     * @return The edge loop tracer (never null)
     */
    public EdgeLoopTracer edgeLoopTracer() {
        return edgeLoopTracer;
    }

    /**
     * Get the face loop tracer for face loop traversal
     * across quad faces.
     *
     * @return The face loop tracer (never null)
     */
    public FaceLoopTracer faceLoopTracer() {
        return faceLoopTracer;
    }

    /**
     * Get the face island detector for connected component queries
     * over the face adjacency graph.
     *
     * @return The face island detector (never null)
     */
    public FaceIslandDetector faceIslandDetector() {
        return faceIslandDetector;
    }

    /**
     * Get the vertex ring query service for connected vertex lists
     * and ordered vertex ring traversal.
     *
     * @return The vertex ring query service (never null)
     */
    public VertexRingQuery vertexRingQuery() {
        return vertexRingQuery;
    }

    /**
     * Get the vertex adjacency query service for vertex-to-vertex
     * connectivity checks and neighbor counts.
     *
     * @return The vertex adjacency query service (never null)
     */
    public VertexAdjacencyQuery vertexAdjacencyQuery() {
        return vertexAdjacencyQuery;
    }

    // =========================================================================
    // EDGE QUERIES
    // =========================================================================

    /**
     * Get an edge by its stable ID.
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return The edge, or null if out of range
     */
    public MeshEdge getEdge(int edgeId) {
        if (edgeId < 0 || edgeId >= edges.length) {
            return null;
        }
        return edges[edgeId];
    }

    /**
     * Get an edge by its two vertex endpoints (O(1) via hash map).
     *
     * @param v0 First vertex index
     * @param v1 Second vertex index
     * @return The edge, or null if no edge connects these vertices
     */
    public MeshEdge getEdgeByVertices(int v0, int v1) {
        long key = MeshEdge.canonicalKey(v0, v1);
        Integer id = edgeKeyToId.get(key);
        return id != null ? edges[id] : null;
    }

    /** @return Total number of edges */
    public int getEdgeCount() {
        return edges.length;
    }

    /**
     * Get the dihedral angle at an edge in radians.
     * Lazily recomputes when dirtied by a vertex move.
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return Angle in radians (0..π), or {@code Float.NaN} if undefined or out of range
     */
    public float getDihedralAngle(int edgeId) {
        if (edgeId < 0 || edgeId >= edges.length) {
            return Float.NaN;
        }
        ensureEdgeClean(edgeId);
        return dihedralAngles[edgeId];
    }

    // =========================================================================
    // EDGE CLASSIFICATION (delegation to EdgeClassifier)
    // =========================================================================

    /** @see EdgeClassifier#get(int) */
    public EdgeClassification getEdgeClassification(int edgeId) {
        ensureEdgeClean(edgeId);
        return edgeClassifier.get(edgeId);
    }

    /** @see EdgeClassifier#getKind(int) */
    public EdgeKind getEdgeKind(int edgeId) {
        ensureEdgeClean(edgeId);
        return edgeClassifier.getKind(edgeId);
    }

    /** @see EdgeClassifier#isSharp(int) */
    public boolean isEdgeSharp(int edgeId) {
        ensureEdgeClean(edgeId);
        return edgeClassifier.isSharp(edgeId);
    }

    /** @see EdgeClassifier#isSeam(int) */
    public boolean isEdgeSeam(int edgeId) {
        ensureEdgeClean(edgeId);
        return edgeClassifier.isSeam(edgeId);
    }

    /** @see EdgeClassifier#getCreaseWeight(int) */
    public float getEdgeCreaseWeight(int edgeId) {
        ensureEdgeClean(edgeId);
        return edgeClassifier.getCreaseWeight(edgeId);
    }

    /** @see EdgeClassifier#isOpen(int) */
    public boolean isOpenEdge(int edgeId) {
        return edgeClassifier.isOpen(edgeId);
    }

    /** @see EdgeClassifier#isManifold(int) */
    public boolean isManifoldEdge(int edgeId) {
        return edgeClassifier.isManifold(edgeId);
    }

    /** @see EdgeClassifier#isNonManifold(int) */
    public boolean isNonManifoldEdge(int edgeId) {
        return edgeClassifier.isNonManifold(edgeId);
    }

    /** @see EdgeClassifier#setSharp(int, boolean) */
    public void setEdgeSharp(int edgeId, boolean sharp) {
        edgeClassifier.setSharp(edgeId, sharp);
    }

    /** @see EdgeClassifier#setSeam(int, boolean) */
    public void setEdgeSeam(int edgeId, boolean seam) {
        edgeClassifier.setSeam(edgeId, seam);
    }

    /** @see EdgeClassifier#setCreaseWeight(int, float) */
    public void setEdgeCreaseWeight(int edgeId, float creaseWeight) {
        edgeClassifier.setCreaseWeight(edgeId, creaseWeight);
    }

    /** @see EdgeClassifier#getThresholdRadians() */
    public float getAutoSharpThresholdRadians() {
        return edgeClassifier.getThresholdRadians();
    }

    /** @see EdgeClassifier#getThresholdDegrees() */
    public float getAutoSharpThresholdDegrees() {
        return edgeClassifier.getThresholdDegrees();
    }

    /**
     * Update the auto-sharp threshold and reclassify all edges.
     *
     * @param thresholdRadians New threshold in radians (clamped to [0, π])
     */
    public void setAutoSharpThreshold(float thresholdRadians) {
        // Ensure all edges are clean before bulk reclassification
        for (int i = 0; i < edges.length; i++) {
            ensureEdgeClean(i);
        }
        edgeClassifier.setThreshold(thresholdRadians, dihedralAngles);
    }

    /**
     * Update the auto-sharp threshold (in degrees) and reclassify all edges.
     *
     * @param thresholdDegrees New threshold in degrees (clamped to [0, 180])
     */
    public void setAutoSharpThresholdDegrees(float thresholdDegrees) {
        setAutoSharpThreshold((float) Math.toRadians(
            Math.clamp(thresholdDegrees, 0.0f, 180.0f)
        ));
    }

    /** @see EdgeClassifier#countByKind(EdgeKind) */
    public int countEdgesByKind(EdgeKind kind) {
        return edgeClassifier.countByKind(kind);
    }

    /** @see EdgeClassifier#countSharp() */
    public int countSharpEdges() {
        return edgeClassifier.countSharp();
    }

    // =========================================================================
    // FACE QUERIES
    // =========================================================================

    /**
     * Get a face by its ID.
     *
     * @param faceId Face identifier
     * @return The face, or null if out of range
     */
    public MeshFace getFace(int faceId) {
        if (faceId < 0 || faceId >= faces.length) {
            return null;
        }
        return faces[faceId];
    }

    /** @return Total number of faces */
    public int getFaceCount() {
        return faces.length;
    }

    /**
     * Get the cached normal for a face. Lazily recomputes if dirty.
     *
     * @param faceId Face identifier
     * @return The face normal (unit vector), or null if out of range
     */
    public Vector3f getFaceNormal(int faceId) {
        if (faceId < 0 || faceId >= faceNormals.length) {
            return null;
        }
        ensureFaceClean(faceId);
        return faceNormals[faceId];
    }

    /**
     * Get the cached centroid for a face. Lazily recomputes if dirty.
     *
     * @param faceId Face identifier
     * @return The face centroid, or null if out of range
     */
    public Vector3f getFaceCentroid(int faceId) {
        if (faceId < 0 || faceId >= faceCentroids.length) {
            return null;
        }
        ensureFaceClean(faceId);
        return faceCentroids[faceId];
    }

    /**
     * Get the cached area for a face. Lazily recomputes if dirty.
     *
     * @param faceId Face identifier
     * @return The face area, or {@code Float.NaN} if out of range
     */
    public float getFaceArea(int faceId) {
        if (faceId < 0 || faceId >= faceAreas.length) {
            return Float.NaN;
        }
        ensureFaceClean(faceId);
        return faceAreas[faceId];
    }

    /**
     * Check whether a face is planar within a given tolerance.
     * Measures the maximum distance from any vertex to the plane defined
     * by the face normal and centroid.
     *
     * <p>Trivially true for triangles (3 vertices are always coplanar).
     * For quads and n-gons, returns true when the maximum vertex-to-plane
     * distance is at most {@code tolerance}.
     *
     * @param faceId    Face identifier
     * @param tolerance Maximum allowed distance from the face plane (must be &ge; 0)
     * @return true if all vertices lie within tolerance of the face plane,
     *         or false if out of range or vertex data is unavailable
     * @see MeshGeometry#computeMaxDistanceToPlane(int[], int[][], float[], Vector3f, Vector3f)
     */
    public boolean isFacePlanar(int faceId, float tolerance) {
        if (faceId < 0 || faceId >= faces.length || verticesRef == null) {
            return false;
        }
        MeshFace face = faces[faceId];
        if (face.vertexCount() <= 3) {
            return true;
        }
        ensureFaceClean(faceId);
        float maxDist = MeshGeometry.computeMaxDistanceToPlane(
                face.vertexIndices(), uniqueToMeshIndices, verticesRef,
                faceNormals[faceId], faceCentroids[faceId]);
        return maxDist <= tolerance;
    }

    // =========================================================================
    // VERTEX NORMAL QUERIES
    // =========================================================================

    /**
     * Get the smooth (area-weighted) normal for a unique vertex.
     * Lazily recomputes if dirty.
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return The vertex normal (unit vector), or null if out of range
     */
    public Vector3f getVertexNormal(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexNormals.length) {
            return null;
        }
        ensureVertexNormalClean(uniqueVertexIdx);
        return vertexNormals[uniqueVertexIdx];
    }

    // =========================================================================
    // ADJACENCY QUERIES
    // =========================================================================

    /**
     * Get all edge IDs connected to a unique vertex.
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return Unmodifiable list of edge IDs, or empty list if out of range
     */
    public List<Integer> getEdgesForVertex(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexToEdges.size()) {
            return Collections.emptyList();
        }
        return vertexToEdges.get(uniqueVertexIdx);
    }

    /**
     * Get all face IDs adjacent to a unique vertex.
     *
     * @param uniqueVertexIdx Unique vertex index
     * @return Unmodifiable list of face IDs, or empty list if out of range
     */
    public List<Integer> getFacesForVertex(int uniqueVertexIdx) {
        if (uniqueVertexIdx < 0 || uniqueVertexIdx >= vertexToFaces.size()) {
            return Collections.emptyList();
        }
        return vertexToFaces.get(uniqueVertexIdx);
    }

    /**
     * Get all face IDs adjacent to a given face (sharing an edge).
     *
     * @param faceId Face identifier
     * @return Unmodifiable list of neighbor face IDs, or empty list if out of range
     */
    public List<Integer> getAdjacentFaces(int faceId) {
        if (faceId < 0 || faceId >= faceToAdjacentFaces.size()) {
            return Collections.emptyList();
        }
        return faceToAdjacentFaces.get(faceId);
    }

    /**
     * Get the edge shared by two adjacent faces.
     *
     * @param faceIdA First face identifier
     * @param faceIdB Second face identifier
     * @return The shared edge, or null if the faces are not adjacent
     */
    public MeshEdge getSharedEdge(int faceIdA, int faceIdB) {
        long key = MeshGeometry.canonicalFacePairKey(faceIdA, faceIdB);
        Integer edgeId = facePairToEdgeId.get(key);
        return edgeId != null ? edges[edgeId] : null;
    }

    // =========================================================================
    // CONVENIENCE QUERIES
    // =========================================================================

    /**
     * Get the face IDs adjacent to an edge.
     * Convenience wrapper around {@code getEdge(edgeId).adjacentFaceIds()}.
     *
     * @param edgeId Edge identifier (0..edgeCount-1)
     * @return Array of adjacent face IDs, or empty array if out of range
     */
    public int[] getFacesForEdge(int edgeId) {
        if (edgeId < 0 || edgeId >= edges.length) {
            return new int[0];
        }
        return edges[edgeId].adjacentFaceIds();
    }

    /**
     * Get the face on the other side of an edge from a known face.
     * Only works for manifold edges (exactly 2 adjacent faces).
     *
     * @param edgeId      Edge identifier (0..edgeCount-1)
     * @param knownFaceId The face we're coming from
     * @return The other face ID, or -1 if the edge is boundary, non-manifold,
     *         out of range, or knownFaceId is not adjacent
     */
    public int getOtherFace(int edgeId, int knownFaceId) {
        if (edgeId < 0 || edgeId >= edges.length) {
            return -1;
        }
        int[] adjFaces = edges[edgeId].adjacentFaceIds();
        if (adjFaces == null || adjFaces.length != 2) {
            return -1;
        }
        if (adjFaces[0] == knownFaceId) return adjFaces[1];
        if (adjFaces[1] == knownFaceId) return adjFaces[0];
        return -1;
    }

    /**
     * Get the edge across a quad face from the given edge (the opposite edge).
     * Only meaningful for quad faces (4 vertices/edges).
     *
     * <p>In a quad with edges [e0, e1, e2, e3], the opposite of e0 is e2,
     * the opposite of e1 is e3, etc.
     *
     * @param faceId Face identifier (must be a quad)
     * @param edgeId Edge identifier (must belong to this face)
     * @return The opposite edge ID, or -1 if the face is not a quad,
     *         the edge is not in the face, or either ID is out of range
     */
    public int getOppositeEdge(int faceId, int edgeId) {
        if (faceId < 0 || faceId >= faces.length) {
            return -1;
        }
        MeshFace face = faces[faceId];
        if (face.vertexCount() != 4) {
            return -1;
        }
        int[] faceEdges = face.edgeIds();
        if (faceEdges == null) {
            return -1;
        }
        for (int i = 0; i < faceEdges.length; i++) {
            if (faceEdges[i] == edgeId) {
                return faceEdges[(i + 2) % 4];
            }
        }
        return -1;
    }

    // =========================================================================
    // DIRECTED EDGE TRAVERSAL (delegation to FaceEdgeTraversal)
    // =========================================================================

    /** @see FaceEdgeTraversal#getNextEdgeInFace(int, int) */
    public int getNextEdgeInFace(int faceId, int edgeId) {
        return faceEdgeTraversal.getNextEdgeInFace(faceId, edgeId);
    }

    /** @see FaceEdgeTraversal#getPrevEdgeInFace(int, int) */
    public int getPrevEdgeInFace(int faceId, int edgeId) {
        return faceEdgeTraversal.getPrevEdgeInFace(faceId, edgeId);
    }

    /** @see FaceEdgeTraversal#getDirectedVertices(int, int) */
    public int[] getDirectedVertices(int faceId, int edgeId) {
        return faceEdgeTraversal.getDirectedVertices(faceId, edgeId);
    }

    // =========================================================================
    // EDGE LOOP / RING TRACING (delegation to EdgeLoopTracer)
    // =========================================================================

    /** @see EdgeLoopTracer#traceEdgeLoop(int) */
    public List<Integer> traceEdgeLoop(int startEdgeId) {
        return edgeLoopTracer.traceEdgeLoop(startEdgeId);
    }

    /** @see EdgeLoopTracer#traceEdgeRing(int) */
    public List<Integer> traceEdgeRing(int startEdgeId) {
        return edgeLoopTracer.traceEdgeRing(startEdgeId);
    }

    // =========================================================================
    // FACE LOOP TRACING (delegation to FaceLoopTracer)
    // =========================================================================

    /** @see FaceLoopTracer#traceFaceLoop(int, int) */
    public List<Integer> traceFaceLoop(int startFaceId, int directionEdgeId) {
        return faceLoopTracer.traceFaceLoop(startFaceId, directionEdgeId);
    }

    // =========================================================================
    // FACE ISLAND DETECTION (delegation to FaceIslandDetector)
    // =========================================================================

    /** @see FaceIslandDetector#getIslands() */
    public List<Set<Integer>> getFaceIslands() {
        return faceIslandDetector.getIslands();
    }

    /** @see FaceIslandDetector#getIslandCount() */
    public int getFaceIslandCount() {
        return faceIslandDetector.getIslandCount();
    }

    /** @see FaceIslandDetector#getIslandForFace(int) */
    public Set<Integer> getFaceIslandForFace(int faceId) {
        return faceIslandDetector.getIslandForFace(faceId);
    }

    // =========================================================================
    // TOPOLOGY QUERIES
    // =========================================================================

    /** @return true if all faces have the same vertex count */
    public boolean isUniformTopology() {
        return uniformTopology;
    }

    /** @return Vertices per face if uniform, or -1 if mixed */
    public int getUniformVerticesPerFace() {
        return uniformTopology ? uniformVerticesPerFace : -1;
    }

    /** @return Array of vertex counts per face */
    public int[] getVerticesPerFace() {
        int[] result = new int[faces.length];
        for (int i = 0; i < faces.length; i++) {
            result[i] = faces[i].vertexCount();
        }
        return result;
    }

    /** @return Array of float offsets into a packed face positions array */
    public int[] computeFacePositionOffsets() {
        int[] offsets = new int[faces.length];
        int cumulative = 0;
        for (int i = 0; i < faces.length; i++) {
            offsets[i] = cumulative;
            cumulative += faces[i].vertexCount() * 3;
        }
        return offsets;
    }

    // =========================================================================
    // VERTEX CLASSIFICATION (delegation to VertexClassifier)
    // =========================================================================

    /** @see VertexClassifier#getValence(int) */
    public int getVertexValence(int uniqueVertexIdx) {
        return vertexClassifier.getValence(uniqueVertexIdx);
    }

    /** @see VertexClassifier#isBoundary(int) */
    public boolean isBoundaryVertex(int uniqueVertexIdx) {
        return vertexClassifier.isBoundary(uniqueVertexIdx);
    }

    /** @see VertexClassifier#isInterior(int) */
    public boolean isInteriorVertex(int uniqueVertexIdx) {
        return vertexClassifier.isInterior(uniqueVertexIdx);
    }

    /** @see VertexClassifier#isPole(int) */
    public boolean isPoleVertex(int uniqueVertexIdx) {
        return vertexClassifier.isPole(uniqueVertexIdx);
    }

    // =========================================================================
    // VERTEX RING QUERIES (delegation to VertexRingQuery)
    // =========================================================================

    /** @see VertexRingQuery#getConnectedVertices(int) */
    public List<Integer> getConnectedVertices(int uniqueVertexIdx) {
        return vertexRingQuery.getConnectedVertices(uniqueVertexIdx);
    }

    /** @see VertexRingQuery#getOrderedVertexRing(int) */
    public List<Integer> getOrderedVertexRing(int uniqueVertexIdx) {
        return vertexRingQuery.getOrderedVertexRing(uniqueVertexIdx);
    }

    // =========================================================================
    // VERTEX ADJACENCY QUERIES (delegation to VertexAdjacencyQuery)
    // =========================================================================

    /** @see VertexAdjacencyQuery#areVerticesConnected(int, int) */
    public boolean areVerticesConnected(int v0, int v1) {
        return vertexAdjacencyQuery.areVerticesConnected(v0, v1);
    }

    /** @see VertexAdjacencyQuery#getNeighborCount(int) */
    public int getVertexNeighborCount(int uniqueVertexIdx) {
        return vertexAdjacencyQuery.getNeighborCount(uniqueVertexIdx);
    }

    // =========================================================================
    // VERTEX MAPPING QUERIES
    // =========================================================================

    /**
     * Get the unique vertex index for a given mesh vertex index.
     *
     * @param meshIdx Mesh vertex index
     * @return Unique vertex index, or -1 if out of range
     */
    public int getUniqueIndexForMeshVertex(int meshIdx) {
        if (meshToUniqueMapping == null || meshIdx < 0 || meshIdx >= meshToUniqueMapping.length) {
            return -1;
        }
        return meshToUniqueMapping[meshIdx];
    }

    /**
     * Get all mesh vertex indices that share the same unique geometric position.
     *
     * @param uniqueIdx Unique vertex index
     * @return Clone of mesh indices array, or empty array if out of range
     */
    public int[] getMeshIndicesForUniqueVertex(int uniqueIdx) {
        if (uniqueToMeshIndices == null || uniqueIdx < 0 || uniqueIdx >= uniqueToMeshIndices.length) {
            return new int[0];
        }
        return uniqueToMeshIndices[uniqueIdx].clone();
    }

    /** @return Number of unique geometric vertex positions */
    public int getUniqueVertexCount() {
        return uniqueVertexCount;
    }

    // =========================================================================
    // TRIANGLE-FACE MAPPING QUERIES
    // =========================================================================

    /**
     * Get the original face ID for a given triangle index.
     *
     * @param triIdx Triangle index (0-based)
     * @return Original face ID, or -1 if out of range
     */
    public int getOriginalFaceIdForTriangle(int triIdx) {
        if (triangleToFaceId == null || triIdx < 0 || triIdx >= triangleToFaceId.length) {
            return -1;
        }
        return triangleToFaceId[triIdx];
    }

    /** @return Total number of triangles in the mesh */
    public int getTriangleCount() {
        return triangleCount;
    }

    // =========================================================================
    // VERTEX MOVE — DIRTY MARKING
    // =========================================================================

    /**
     * Mark all faces adjacent to a moved vertex as dirty.
     * Normals, centroids, areas, vertex normals, dihedral angles, and edge
     * classifications will be lazily recomputed on the next query.
     *
     * @param uniqueVertexIndex The unique vertex that moved
     * @param vertices          Current vertex positions (x,y,z interleaved)
     */
    public void onVertexPositionChanged(int uniqueVertexIndex, float[] vertices) {
        this.verticesRef = vertices;
        List<Integer> affectedFaceIds = getFacesForVertex(uniqueVertexIndex);
        for (int faceId : affectedFaceIds) {
            if (faceId >= 0 && faceId < faceDirty.length) {
                faceDirty[faceId] = true;

                MeshFace face = faces[faceId];

                for (int v : face.vertexIndices()) {
                    if (v >= 0 && v < vertexNormalDirty.length) {
                        vertexNormalDirty[v] = true;
                    }
                }

                for (int eid : face.edgeIds()) {
                    if (eid >= 0 && eid < edgeDirty.length) {
                        edgeDirty[eid] = true;
                    }
                }
            }
        }
    }

    // =========================================================================
    // LAZY RECOMPUTATION
    // =========================================================================

    private void ensureFaceClean(int faceId) {
        if (faceId >= 0 && faceId < faceDirty.length && faceDirty[faceId] && verticesRef != null) {
            int[] verts = faces[faceId].vertexIndices();
            faceNormals[faceId] = MeshGeometry.computeNormal(verts, uniqueToMeshIndices, verticesRef);
            faceCentroids[faceId] = MeshGeometry.computeCentroid(verts, uniqueToMeshIndices, verticesRef);
            faceAreas[faceId] = MeshGeometry.computeArea(verts, uniqueToMeshIndices, verticesRef);
            faceDirty[faceId] = false;
        }
    }

    private void ensureEdgeClean(int edgeId) {
        if (edgeId < 0 || edgeId >= edgeDirty.length || !edgeDirty[edgeId]) {
            return;
        }
        MeshEdge edge = edges[edgeId];
        for (int faceId : edge.adjacentFaceIds()) {
            ensureFaceClean(faceId);
        }
        dihedralAngles[edgeId] = computeDihedralAngleForEdge(edge);
        edgeClassifier.reclassify(edgeId, dihedralAngles[edgeId]);
        edgeDirty[edgeId] = false;
    }

    private void ensureVertexNormalClean(int uniqueVertexIdx) {
        if (!vertexNormalDirty[uniqueVertexIdx]) {
            return;
        }
        List<Integer> adjacentFaceIds = getFacesForVertex(uniqueVertexIdx);
        for (int faceId : adjacentFaceIds) {
            ensureFaceClean(faceId);
        }
        vertexNormals[uniqueVertexIdx] = MeshGeometry.computeVertexNormal(adjacentFaceIds, faceNormals, faceAreas);
        vertexNormalDirty[uniqueVertexIdx] = false;
    }

    private float computeDihedralAngleForEdge(MeshEdge edge) {
        int[] adjFaces = edge.adjacentFaceIds();
        if (adjFaces.length != 2) {
            return Float.NaN;
        }
        return MeshGeometry.computeDihedralAngle(faceNormals[adjFaces[0]], faceNormals[adjFaces[1]]);
    }
}
