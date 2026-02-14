package com.openmason.main.systems.rendering.model.gmr.topology;

import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure microservice coordinator composing 15 focused topology sub-services.
 * Built by {@link MeshTopologyBuilder} from GMR's flat vertex/index arrays.
 *
 * <p>Contains zero domain logic — all computation is delegated to sub-services.
 * The constructor performs pure field assignment with no computation, allocation, or loops.
 *
 * <p>Callers can access sub-services directly for focused work:
 * <ul>
 *   <li>{@link #indexMappingQuery()} — index space translations (mesh/unique/triangle)</li>
 *   <li>{@link #topologyMetadataQuery()} — uniform topology, vertex counts, offsets</li>
 *   <li>{@link #elementAdjacencyQuery()} — vertex/edge/face adjacency lookups</li>
 *   <li>{@link #faceGeometryCache()} — face normals, centroids, areas with dirty tracking</li>
 *   <li>{@link #dihedralAngleCache()} — per-edge dihedral angles with dirty tracking</li>
 *   <li>{@link #vertexNormalCache()} — per-vertex smooth normals with dirty tracking</li>
 *   <li>{@link #edgeClassifier()} — edge classification, auto-sharp, crease weights</li>
 *   <li>{@link #vertexClassifier()} — valence, boundary, interior, pole queries</li>
 *   <li>{@link #vertexRingQuery()} — connected vertices and ordered vertex ring traversal</li>
 *   <li>{@link #vertexAdjacencyQuery()} — vertex-to-vertex connectivity and neighbor counts</li>
 *   <li>{@link #faceEdgeTraversal()} — winding-aware directed edge traversal within faces</li>
 *   <li>{@link #edgeLoopTracer()} — edge loop and edge ring tracing across quad faces</li>
 *   <li>{@link #faceLoopTracer()} — face loop tracing across quad faces</li>
 *   <li>{@link #faceIslandDetector()} — connected component (island) detection for faces</li>
 *   <li>{@link #vertexBoundaryWalker()} — boundary edge chain walking from boundary vertices</li>
 *   <li>{@link MeshGeometry} — stateless geometry math (static utility)</li>
 * </ul>
 *
 * <p>All delegation methods are retained for backward compatibility.
 */
public class MeshTopology {

    // Core topology data (retained for getEdge/getEdgeByVertices/getEdgeCount/getFace/getFaceCount)
    private final MeshEdge[] edges;
    private final MeshFace[] faces;
    private final Map<Long, Integer> edgeKeyToId;

    // 6 new sub-services
    private final IndexMappingQuery indexMappingQuery;
    private final TopologyMetadataQuery topologyMetadataQuery;
    private final ElementAdjacencyQuery elementAdjacencyQuery;
    private final FaceGeometryCache faceGeometryCache;
    private final DihedralAngleCache dihedralAngleCache;
    private final VertexNormalCache vertexNormalCache;

    // 9 existing sub-services
    private final EdgeClassifier edgeClassifier;
    private final VertexClassifier vertexClassifier;
    private final FaceEdgeTraversal faceEdgeTraversal;
    private final EdgeLoopTracer edgeLoopTracer;
    private final FaceLoopTracer faceLoopTracer;
    private final FaceIslandDetector faceIslandDetector;
    private final VertexRingQuery vertexRingQuery;
    private final VertexAdjacencyQuery vertexAdjacencyQuery;
    private final VertexBoundaryWalker vertexBoundaryWalker;

    /**
     * Package-private constructor used by {@link MeshTopologyBuilder}.
     * Pure field assignment — no computation, no allocation, no loops.
     */
    MeshTopology(MeshEdge[] edges, MeshFace[] faces, Map<Long, Integer> edgeKeyToId,
                 IndexMappingQuery indexMappingQuery,
                 TopologyMetadataQuery topologyMetadataQuery,
                 ElementAdjacencyQuery elementAdjacencyQuery,
                 FaceGeometryCache faceGeometryCache,
                 DihedralAngleCache dihedralAngleCache,
                 VertexNormalCache vertexNormalCache,
                 EdgeClassifier edgeClassifier,
                 VertexClassifier vertexClassifier,
                 FaceEdgeTraversal faceEdgeTraversal,
                 EdgeLoopTracer edgeLoopTracer,
                 FaceLoopTracer faceLoopTracer,
                 FaceIslandDetector faceIslandDetector,
                 VertexRingQuery vertexRingQuery,
                 VertexAdjacencyQuery vertexAdjacencyQuery,
                 VertexBoundaryWalker vertexBoundaryWalker) {
        this.edges = edges;
        this.faces = faces;
        this.edgeKeyToId = edgeKeyToId;
        this.indexMappingQuery = indexMappingQuery;
        this.topologyMetadataQuery = topologyMetadataQuery;
        this.elementAdjacencyQuery = elementAdjacencyQuery;
        this.faceGeometryCache = faceGeometryCache;
        this.dihedralAngleCache = dihedralAngleCache;
        this.vertexNormalCache = vertexNormalCache;
        this.edgeClassifier = edgeClassifier;
        this.vertexClassifier = vertexClassifier;
        this.faceEdgeTraversal = faceEdgeTraversal;
        this.edgeLoopTracer = edgeLoopTracer;
        this.faceLoopTracer = faceLoopTracer;
        this.faceIslandDetector = faceIslandDetector;
        this.vertexRingQuery = vertexRingQuery;
        this.vertexAdjacencyQuery = vertexAdjacencyQuery;
        this.vertexBoundaryWalker = vertexBoundaryWalker;
    }

    // =========================================================================
    // SUB-SERVICE ACCESSORS
    // =========================================================================

    /** @return The index mapping query service (never null) */
    public IndexMappingQuery indexMappingQuery() {
        return indexMappingQuery;
    }

    /** @return The topology metadata query service (never null) */
    public TopologyMetadataQuery topologyMetadataQuery() {
        return topologyMetadataQuery;
    }

    /** @return The element adjacency query service (never null) */
    public ElementAdjacencyQuery elementAdjacencyQuery() {
        return elementAdjacencyQuery;
    }

    /** @return The face geometry cache (never null) */
    public FaceGeometryCache faceGeometryCache() {
        return faceGeometryCache;
    }

    /** @return The dihedral angle cache (never null) */
    public DihedralAngleCache dihedralAngleCache() {
        return dihedralAngleCache;
    }

    /** @return The vertex normal cache (never null) */
    public VertexNormalCache vertexNormalCache() {
        return vertexNormalCache;
    }

    /** @return The edge classifier (never null) */
    public EdgeClassifier edgeClassifier() {
        return edgeClassifier;
    }

    /** @return The vertex classifier (never null) */
    public VertexClassifier vertexClassifier() {
        return vertexClassifier;
    }

    /** @return The face-edge traversal service (never null) */
    public FaceEdgeTraversal faceEdgeTraversal() {
        return faceEdgeTraversal;
    }

    /** @return The edge loop tracer (never null) */
    public EdgeLoopTracer edgeLoopTracer() {
        return edgeLoopTracer;
    }

    /** @return The face loop tracer (never null) */
    public FaceLoopTracer faceLoopTracer() {
        return faceLoopTracer;
    }

    /** @return The face island detector (never null) */
    public FaceIslandDetector faceIslandDetector() {
        return faceIslandDetector;
    }

    /** @return The vertex ring query service (never null) */
    public VertexRingQuery vertexRingQuery() {
        return vertexRingQuery;
    }

    /** @return The vertex adjacency query service (never null) */
    public VertexAdjacencyQuery vertexAdjacencyQuery() {
        return vertexAdjacencyQuery;
    }

    /** @return The vertex boundary walker (never null) */
    public VertexBoundaryWalker vertexBoundaryWalker() {
        return vertexBoundaryWalker;
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

    /** @see DihedralAngleCache#getDihedralAngle(int) */
    public float getDihedralAngle(int edgeId) {
        return dihedralAngleCache.getDihedralAngle(edgeId);
    }

    // =========================================================================
    // EDGE CLASSIFICATION (delegation to EdgeClassifier)
    // =========================================================================

    /** @see EdgeClassifier#get(int) */
    public EdgeClassification getEdgeClassification(int edgeId) {
        dihedralAngleCache.ensureClean(edgeId);
        return edgeClassifier.get(edgeId);
    }

    /** @see EdgeClassifier#getKind(int) */
    public EdgeKind getEdgeKind(int edgeId) {
        dihedralAngleCache.ensureClean(edgeId);
        return edgeClassifier.getKind(edgeId);
    }

    /** @see EdgeClassifier#isSharp(int) */
    public boolean isEdgeSharp(int edgeId) {
        dihedralAngleCache.ensureClean(edgeId);
        return edgeClassifier.isSharp(edgeId);
    }

    /** @see EdgeClassifier#isSeam(int) */
    public boolean isEdgeSeam(int edgeId) {
        dihedralAngleCache.ensureClean(edgeId);
        return edgeClassifier.isSeam(edgeId);
    }

    /** @see EdgeClassifier#getCreaseWeight(int) */
    public float getEdgeCreaseWeight(int edgeId) {
        dihedralAngleCache.ensureClean(edgeId);
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
     * @param thresholdRadians New threshold in radians (clamped to [0, pi])
     */
    public void setAutoSharpThreshold(float thresholdRadians) {
        for (int i = 0; i < edges.length; i++) {
            dihedralAngleCache.ensureClean(i);
        }
        edgeClassifier.setThreshold(thresholdRadians, dihedralAngleCache.angles());
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

    /** @see FaceGeometryCache#getFaceNormal(int) */
    public Vector3f getFaceNormal(int faceId) {
        return faceGeometryCache.getFaceNormal(faceId);
    }

    /** @see FaceGeometryCache#getFaceCentroid(int) */
    public Vector3f getFaceCentroid(int faceId) {
        return faceGeometryCache.getFaceCentroid(faceId);
    }

    /** @see FaceGeometryCache#getFaceArea(int) */
    public float getFaceArea(int faceId) {
        return faceGeometryCache.getFaceArea(faceId);
    }

    /** @see FaceGeometryCache#isFacePlanar(int, float) */
    public boolean isFacePlanar(int faceId, float tolerance) {
        return faceGeometryCache.isFacePlanar(faceId, tolerance);
    }

    // =========================================================================
    // VERTEX NORMAL QUERIES
    // =========================================================================

    /** @see VertexNormalCache#getVertexNormal(int) */
    public Vector3f getVertexNormal(int uniqueVertexIdx) {
        return vertexNormalCache.getVertexNormal(uniqueVertexIdx);
    }

    // =========================================================================
    // ADJACENCY QUERIES
    // =========================================================================

    /** @see ElementAdjacencyQuery#getEdgesForVertex(int) */
    public List<Integer> getEdgesForVertex(int uniqueVertexIdx) {
        return elementAdjacencyQuery.getEdgesForVertex(uniqueVertexIdx);
    }

    /** @see ElementAdjacencyQuery#getFacesForVertex(int) */
    public List<Integer> getFacesForVertex(int uniqueVertexIdx) {
        return elementAdjacencyQuery.getFacesForVertex(uniqueVertexIdx);
    }

    /** @see ElementAdjacencyQuery#getAdjacentFaces(int) */
    public List<Integer> getAdjacentFaces(int faceId) {
        return elementAdjacencyQuery.getAdjacentFaces(faceId);
    }

    /** @see ElementAdjacencyQuery#getSharedEdge(int, int) */
    public MeshEdge getSharedEdge(int faceIdA, int faceIdB) {
        return elementAdjacencyQuery.getSharedEdge(faceIdA, faceIdB);
    }

    // =========================================================================
    // CONVENIENCE QUERIES
    // =========================================================================

    /** @see ElementAdjacencyQuery#getFacesForEdge(int) */
    public int[] getFacesForEdge(int edgeId) {
        return elementAdjacencyQuery.getFacesForEdge(edgeId);
    }

    /** @see ElementAdjacencyQuery#getOtherFace(int, int) */
    public int getOtherFace(int edgeId, int knownFaceId) {
        return elementAdjacencyQuery.getOtherFace(edgeId, knownFaceId);
    }

    /** @see ElementAdjacencyQuery#getOppositeEdge(int, int) */
    public int getOppositeEdge(int faceId, int edgeId) {
        return elementAdjacencyQuery.getOppositeEdge(faceId, edgeId);
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

    /** @see TopologyMetadataQuery#isUniformTopology() */
    public boolean isUniformTopology() {
        return topologyMetadataQuery.isUniformTopology();
    }

    /** @see TopologyMetadataQuery#getUniformVerticesPerFace() */
    public int getUniformVerticesPerFace() {
        return topologyMetadataQuery.getUniformVerticesPerFace();
    }

    /** @see TopologyMetadataQuery#getVerticesPerFace() */
    public int[] getVerticesPerFace() {
        return topologyMetadataQuery.getVerticesPerFace();
    }

    /** @see TopologyMetadataQuery#computeFacePositionOffsets() */
    public int[] computeFacePositionOffsets() {
        return topologyMetadataQuery.computeFacePositionOffsets();
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
    // VERTEX BOUNDARY WALK (delegation to VertexBoundaryWalker)
    // =========================================================================

    /** @see VertexBoundaryWalker#traceBoundaryFromVertex(int) */
    public List<Integer> traceBoundaryFromVertex(int uniqueVertexIdx) {
        return vertexBoundaryWalker.traceBoundaryFromVertex(uniqueVertexIdx);
    }

    // =========================================================================
    // VERTEX MAPPING QUERIES
    // =========================================================================

    /** @see IndexMappingQuery#getUniqueIndexForMeshVertex(int) */
    public int getUniqueIndexForMeshVertex(int meshIdx) {
        return indexMappingQuery.getUniqueIndexForMeshVertex(meshIdx);
    }

    /** @see IndexMappingQuery#getMeshIndicesForUniqueVertex(int) */
    public int[] getMeshIndicesForUniqueVertex(int uniqueIdx) {
        return indexMappingQuery.getMeshIndicesForUniqueVertex(uniqueIdx);
    }

    /** @see IndexMappingQuery#getUniqueVertexCount() */
    public int getUniqueVertexCount() {
        return indexMappingQuery.getUniqueVertexCount();
    }

    // =========================================================================
    // TRIANGLE-FACE MAPPING QUERIES
    // =========================================================================

    /** @see IndexMappingQuery#getOriginalFaceIdForTriangle(int) */
    public int getOriginalFaceIdForTriangle(int triIdx) {
        return indexMappingQuery.getOriginalFaceIdForTriangle(triIdx);
    }

    /** @see IndexMappingQuery#getTriangleCount() */
    public int getTriangleCount() {
        return indexMappingQuery.getTriangleCount();
    }

    // =========================================================================
    // VERTEX MOVE — DIRTY MARKING (coordinator method)
    // =========================================================================

    /**
     * Mark all caches dirty for faces adjacent to a moved vertex.
     * Face geometry, vertex normals, dihedral angles, and edge classifications
     * will be lazily recomputed on the next query.
     *
     * @param uniqueVertexIndex The unique vertex that moved
     * @param vertices          Current vertex positions (x,y,z interleaved)
     */
    public void onVertexPositionChanged(int uniqueVertexIndex, float[] vertices) {
        faceGeometryCache.updateVerticesRef(vertices);
        List<Integer> affectedFaces = elementAdjacencyQuery.getFacesForVertex(uniqueVertexIndex);
        for (int faceId : affectedFaces) {
            faceGeometryCache.markFaceDirty(faceId);

            MeshFace face = faces[faceId];
            for (int v : face.vertexIndices()) {
                vertexNormalCache.markDirty(v);
            }
            for (int eid : face.edgeIds()) {
                dihedralAngleCache.markEdgeDirty(eid);
            }
        }
    }
}
