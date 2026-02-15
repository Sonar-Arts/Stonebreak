package com.openmason.main.systems.rendering.model;

import com.openmason.main.systems.rendering.api.BaseRenderer;
import com.openmason.main.systems.rendering.api.GeometryData;
import com.openmason.main.systems.rendering.api.RenderPass;
import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import com.openmason.main.systems.rendering.model.gmr.core.VertexDataManager;
import com.openmason.main.systems.rendering.model.gmr.extraction.GMREdgeExtractor;
import com.openmason.main.systems.rendering.model.gmr.extraction.GMRFaceExtractor;
import com.openmason.main.systems.rendering.model.gmr.geometry.GeometryDataBuilder;
import com.openmason.main.systems.rendering.model.gmr.geometry.IGeometryDataBuilder;
import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;
import com.openmason.main.systems.rendering.model.gmr.mapping.IUniqueVertexMapper;
import com.openmason.main.systems.rendering.model.gmr.mapping.TriangleFaceMapper;
import com.openmason.main.systems.rendering.model.gmr.mapping.UniqueVertexMapper;
import com.openmason.main.systems.rendering.model.gmr.notification.IMeshChangeNotifier;
import com.openmason.main.systems.rendering.model.gmr.notification.MeshChangeNotifier;
import com.openmason.main.systems.rendering.model.gmr.topology.MeshTopology;
import com.openmason.main.systems.rendering.model.gmr.topology.MeshTopologyBuilder;
import com.openmason.main.systems.rendering.model.gmr.uv.*;
import com.openmason.main.systems.rendering.model.io.omo.OMOFormat;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import org.joml.Vector3f;

import java.util.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;

/**
 * Generic model renderer supporting arbitrary geometry.
 * supports any vertex count.
 * Extends BaseRenderer for consistent initialization and rendering.
 *
 * Features:
 * - Multi-part model support
 * - Dynamic vertex updates for editing
 * - Arbitrary vertex counts
 * - OMO format loading support
 *
 * Architecture (SOLID principles):
 * - IVertexDataManager: Vertex position management
 * - IUniqueVertexMapper: Index-based unique vertex lookup
 * - IMeshChangeNotifier: Observer pattern for mesh changes
 * - ITriangleFaceMapper: Triangle-to-face mapping
 * - ISubdivisionProcessor: Edge subdivision operations
 * - IGeometryDataBuilder: Interleaved data construction
 * - IUVCoordinateGenerator: UV texture coordinate generation
 * - IModelStateManager: Model parts, dimensions, UV mode state
 */
public class GenericModelRenderer extends BaseRenderer {

    // Subsystems (dependency injection via interfaces)
    private final IVertexDataManager vertexManager;
    private final IUniqueVertexMapper uniqueMapper;
    private final IMeshChangeNotifier changeNotifier;
    private final ITriangleFaceMapper faceMapper;
    private final ISubdivisionProcessor subdivisionProcessor;
    private final IGeometryDataBuilder geometryBuilder;
    private final IUVCoordinateGenerator uvGenerator;
    private final IModelStateManager stateManager;

    // Per-face texture system (replaces legacy UVMode switching)
    private final FaceTextureManager faceTextureManager;

    // Extractors (SOLID: extraction logic separated from renderer)
    private final GMRFaceExtractor faceExtractor;
    private final GMREdgeExtractor edgeExtractor;

    // Texture state (kept inline per KISS - only 3 lines)
    private int textureId = 0;
    private boolean useTexture = false;

    // Topology index for O(1) adjacency queries
    private MeshTopology topology;

    // Per-face material draw batches for multi-texture rendering
    private record MaterialDrawBatch(int textureId, int indexOffset, int indexCount) {}
    private List<MaterialDrawBatch> drawBatches = List.of();
    private boolean drawBatchesDirty = true;

    // Cached identity matrix to avoid per-frame allocation in setUniforms
    private static final org.joml.Matrix4f IDENTITY_MATRIX = new org.joml.Matrix4f();

    /**
     * Create a GenericModelRenderer with default subsystems.
     */
    public GenericModelRenderer() {
        this(new FaceTextureManager());
    }

    /**
     * Create a GenericModelRenderer with a specific FaceTextureManager.
     * Convenience constructor for dependency injection of the texture manager.
     */
    private GenericModelRenderer(FaceTextureManager faceTextureManager) {
        this(
            new VertexDataManager(),
            new UniqueVertexMapper(),
            new MeshChangeNotifier(),
            new TriangleFaceMapper(),
            new SubdivisionProcessor(),
            new GeometryDataBuilder(),
            new PerFaceUVCoordinateGenerator(faceTextureManager),
            new ModelStateManager(),
            new GMRFaceExtractor(),
            new GMREdgeExtractor(),
            faceTextureManager
        );
    }

    /**
     * Create a GenericModelRenderer with custom subsystems (for testing/extension).
     */
    public GenericModelRenderer(
            IVertexDataManager vertexManager,
            IUniqueVertexMapper uniqueMapper,
            IMeshChangeNotifier changeNotifier,
            ITriangleFaceMapper faceMapper,
            ISubdivisionProcessor subdivisionProcessor,
            IGeometryDataBuilder geometryBuilder,
            IUVCoordinateGenerator uvGenerator,
            IModelStateManager stateManager,
            GMRFaceExtractor faceExtractor,
            GMREdgeExtractor edgeExtractor,
            FaceTextureManager faceTextureManager) {
        this.vertexManager = vertexManager;
        this.uniqueMapper = uniqueMapper;
        this.changeNotifier = changeNotifier;
        this.faceMapper = faceMapper;
        this.subdivisionProcessor = subdivisionProcessor;
        this.geometryBuilder = geometryBuilder;
        this.uvGenerator = uvGenerator;
        this.stateManager = stateManager;
        this.faceExtractor = faceExtractor;
        this.edgeExtractor = edgeExtractor;
        this.faceTextureManager = faceTextureManager != null ? faceTextureManager : new FaceTextureManager();
        logger.debug("GenericModelRenderer created with subsystems");
    }

    @Override
    public String getDebugName() {
        return "GenericModelRenderer";
    }

    @Override
    public RenderPass getRenderPass() {
        return RenderPass.SCENE;
    }

    // =========================================================================
    // MODEL LOADING & GEOMETRY MANAGEMENT
    // =========================================================================

    // REMOVED: loadFromDimensions() and rebuildFromCachedDimensions()
    // GenericModelRenderer should NOT generate geometry - it only loads and renders topology.
    // Provide explicit mesh data via loadMeshData().

    /**
     * Set UV mapping mode.
     *
     * @param uvMode The UV mode (ignored — per-face UV is the only mode going forward)
     * @deprecated Per-face UV is the only mode. Use {@link #getFaceTextureManager()} for
     *             per-face texture assignment. This method is a no-op retained for API compatibility.
     */
    @Deprecated
    public void setUVMode(UVMode uvMode) {
        logger.debug("setUVMode({}) called — ignored, per-face UV is the only mode", uvMode);
        if (uvMode != null) {
            stateManager.setUVMode(uvMode);
        }
    }

    /**
     * Get the current UV mode.
     *
     * @deprecated Per-face UV is the only mode. Use {@link #getFaceTextureManager()} instead.
     */
    @Deprecated
    public UVMode getUVMode() {
        return stateManager.getUVMode();
    }

    // =========================================================================
    // VERTEX POSITION MANAGEMENT (delegated to IVertexDataManager)
    // =========================================================================

    /**
     * Update a vertex position by its global index.
     * Also updates ALL other mesh vertices at the same geometric position.
     */
    public void updateVertexPosition(int globalIndex, Vector3f position) {
        float[] vertices = vertexManager.getVertices();
        if (vertices == null || globalIndex < 0) {
            return;
        }

        int offset = globalIndex * 3;
        if (offset + 2 >= vertices.length) {
            logger.warn("Vertex index {} out of bounds", globalIndex);
            return;
        }

        // Get unique index and affected mesh indices
        int uniqueIndex = uniqueMapper.getUniqueIndexForMeshVertex(globalIndex);
        int[] affectedMeshIndices;

        if (uniqueIndex >= 0) {
            affectedMeshIndices = uniqueMapper.getMeshIndicesForUniqueVertex(uniqueIndex);
        } else {
            Vector3f oldPosition = new Vector3f(vertices[offset], vertices[offset + 1], vertices[offset + 2]);
            List<Integer> verticesList = vertexManager.findMeshVerticesAtPosition(oldPosition, 0.001f);
            affectedMeshIndices = verticesList.stream().mapToInt(Integer::intValue).toArray();
        }

        // Update ALL vertices at this position
        vertexManager.updateVertexPositions(affectedMeshIndices, position);

        // Rebuild and upload to GPU
        float[] interleavedData = geometryBuilder.buildInterleavedData(
            vertexManager.getVertices(), vertexManager.getTexCoords());
        updateVBO(interleavedData);

        // Notify listeners
        if (uniqueIndex >= 0 && changeNotifier.getListenerCount() > 0) {
            changeNotifier.notifyVertexPositionChanged(uniqueIndex, position, affectedMeshIndices);
        }

        logger.trace("Updated {} mesh vertices for unique vertex {} to ({}, {}, {})",
            affectedMeshIndices.length, uniqueIndex, position.x, position.y, position.z);
    }

    /**
     * Update all vertex positions at once.
     */
    public void updateVertexPositions(float[] positions) {
        if (!initialized) {
            logger.warn("Cannot update vertex positions: renderer not initialized");
            return;
        }

        float[] currentVertices = vertexManager.getVertices();
        if (positions == null || currentVertices == null) {
            return;
        }

        int expectedLength = currentVertices.length;
        int updateLength = Math.min(positions.length, expectedLength);
        int inputVertexCount = positions.length / 3;
        int meshVertexCount = currentVertices.length / 3;

        try {
            if (inputVertexCount < meshVertexCount) {
                // Post-subdivision: update by position matching
                for (int i = 0; i < inputVertexCount; i++) {
                    int offset = i * 3;
                    Vector3f oldPos = new Vector3f(currentVertices[offset], currentVertices[offset + 1], currentVertices[offset + 2]);
                    Vector3f newPos = new Vector3f(positions[offset], positions[offset + 1], positions[offset + 2]);

                    if (oldPos.equals(newPos, 0.0001f)) {
                        continue;
                    }

                    List<Integer> verticesAtPos = vertexManager.findMeshVerticesAtPosition(oldPos, 0.001f);
                    vertexManager.updateVertexPositions(
                        verticesAtPos.stream().mapToInt(Integer::intValue).toArray(), newPos);
                }
            } else {
                // Direct copy
                float[] vertices = vertexManager.getVertices();
                System.arraycopy(positions, 0, vertices, 0, updateLength);
            }

            // Upload to GPU
            float[] interleavedData = geometryBuilder.buildInterleavedData(
                vertexManager.getVertices(), vertexManager.getTexCoords());
            updateVBO(interleavedData);

            // Rebuild unique vertex mapping to stay in sync
            uniqueMapper.buildMapping(vertexManager.getVertices());

            // Rebuild topology to reflect new vertex positions
            this.topology = MeshTopologyBuilder.build(
                vertexManager.getVertices(), vertexManager.getIndices(),
                faceMapper, uniqueMapper);
            changeNotifier.notifyTopologyRebuilt(topology);
            changeNotifier.notifyGeometryRebuilt();

            logger.trace("Updated {} of {} vertex positions", updateLength / 3, currentVertices.length / 3);
        } catch (Exception e) {
            logger.error("Error updating vertex positions", e);
        }
    }

    /**
     * Get vertex position by global index.
     */
    public Vector3f getVertexPosition(int globalIndex) {
        return vertexManager.getVertexPosition(globalIndex);
    }

    /**
     * Get total vertex count across all parts.
     */
    public int getTotalVertexCount() {
        return vertexManager.getTotalVertexCount();
    }

    /**
     * Get all current mesh vertex positions.
     */
    public float[] getAllMeshVertexPositions() {
        return vertexManager.getAllMeshVertexPositions();
    }

    /**
     * Find all mesh vertex indices at a given position.
     */
    public List<Integer> findMeshVerticesAtPosition(Vector3f position, float epsilon) {
        return vertexManager.findMeshVerticesAtPosition(position, epsilon);
    }

    // =========================================================================
    // TRIANGLE & FACE QUERIES (delegated to ITriangleFaceMapper)
    // =========================================================================

    /**
     * Get the current triangle count.
     */
    public int getTriangleCount() {
        int[] indices = vertexManager.getIndices();
        return indices != null ? indices.length / 3 : 0;
    }

    /**
     * Get the current triangle indices.
     */
    public int[] getTriangleIndices() {
        int[] indices = vertexManager.getIndices();
        return indices != null ? indices.clone() : null;
    }

    /**
     * Get the original face ID for a given triangle index.
     * Routes through topology when available for consistency.
     */
    public int getOriginalFaceIdForTriangle(int triangleIndex) {
        if (topology != null) {
            return topology.getOriginalFaceIdForTriangle(triangleIndex);
        }
        return faceMapper.getOriginalFaceIdForTriangle(triangleIndex);
    }

    /**
     * Get the number of original faces.
     */
    public int getOriginalFaceCount() {
        return faceMapper.getOriginalFaceCount();
    }

    /**
     * Check if triangle-to-face mapping is available.
     */
    public boolean hasTriangleToFaceMapping() {
        return faceMapper.hasMapping();
    }

    // =========================================================================
    // UNIQUE VERTEX MAPPING (delegated to IUniqueVertexMapper)
    // =========================================================================

    /**
     * Get the position of a unique vertex by index.
     */
    public Vector3f getUniqueVertexPosition(int uniqueIndex) {
        return uniqueMapper.getUniqueVertexPosition(uniqueIndex, vertexManager.getVertices());
    }

    /**
     * Get all mesh vertex indices that share a unique geometric position.
     * Routes through topology when available for consistency.
     */
    public int[] getMeshIndicesForUniqueVertex(int uniqueIndex) {
        if (topology != null) {
            return topology.getMeshIndicesForUniqueVertex(uniqueIndex);
        }
        return uniqueMapper.getMeshIndicesForUniqueVertex(uniqueIndex);
    }

    /**
     * Get the unique vertex index for a given mesh vertex.
     * Routes through topology when available for consistency.
     */
    public int getUniqueIndexForMeshVertex(int meshIndex) {
        if (topology != null) {
            return topology.getUniqueIndexForMeshVertex(meshIndex);
        }
        return uniqueMapper.getUniqueIndexForMeshVertex(meshIndex);
    }

    /**
     * Get all unique vertex positions as an array.
     */
    public float[] getAllUniqueVertexPositions() {
        return uniqueMapper.getAllUniqueVertexPositions(vertexManager.getVertices());
    }

    /**
     * Get the current mesh topology index.
     * Provides O(1) adjacency queries for edges, faces, and vertex connectivity.
     *
     * @return The topology index, or null if not yet built
     */
    public MeshTopology getTopology() {
        return topology;
    }

    // =========================================================================
    // CHANGE NOTIFICATION (delegated to IMeshChangeNotifier)
    // =========================================================================

    /**
     * Add a listener to receive mesh change notifications.
     */
    public void addMeshChangeListener(MeshChangeListener listener) {
        changeNotifier.addListener(listener);
    }

    /**
     * Remove a listener from mesh change notifications.
     */
    public void removeMeshChangeListener(MeshChangeListener listener) {
        changeNotifier.removeListener(listener);
    }

    // =========================================================================
    // SUBDIVISION (delegated to ISubdivisionProcessor)
    // =========================================================================

    /**
     * Apply edge subdivision using endpoint positions.
     */
    public int applyEdgeSubdivisionByPosition(Vector3f midpointPosition, Vector3f endpoint1, Vector3f endpoint2) {
        if (!initialized || midpointPosition == null || endpoint1 == null || endpoint2 == null) {
            logger.warn("Cannot apply subdivision: invalid parameters");
            return -1;
        }

        // Log first 8 mesh vertex positions for debugging
        float[] vertices = vertexManager.getVertices();
        if (vertices != null && vertices.length >= 24 && logger.isTraceEnabled()) {
            logger.trace("GenericModelRenderer first 8 vertices:");
            for (int i = 0; i < 8 && i * 3 + 2 < vertices.length; i++) {
                logger.trace("  v{}: ({}, {}, {})", i, vertices[i * 3], vertices[i * 3 + 1], vertices[i * 3 + 2]);
            }
        }

        // Apply subdivision via processor
        ISubdivisionProcessor.SubdivisionResult result = subdivisionProcessor.applyEdgeSubdivision(
            midpointPosition, endpoint1, endpoint2, vertexManager, faceMapper, vertexCount);

        if (!result.success()) {
            logger.warn("Subdivision failed: {}", result.errorMessage());
            return -1;
        }

        // Update state
        vertexCount += result.newVertexCount();
        vertexManager.setIndices(result.newIndices());
        indexCount = result.newIndices().length;
        faceMapper.setMapping(result.newTriangleToFaceId());

        // Rebuild GPU buffers
        float[] interleavedData = geometryBuilder.buildInterleavedData(
            vertexManager.getVertices(), vertexManager.getTexCoords());
        updateVBO(interleavedData);
        updateEBO(result.newIndices());
        drawBatchesDirty = true;

        // Rebuild unique vertex mapping
        uniqueMapper.buildMapping(vertexManager.getVertices());

        // Rebuild topology index
        this.topology = MeshTopologyBuilder.build(
            vertexManager.getVertices(), vertexManager.getIndices(),
            faceMapper, uniqueMapper);

        // Notify listeners
        changeNotifier.notifyTopologyRebuilt(topology);
        changeNotifier.notifyGeometryRebuilt();

        logger.debug("Applied subdivision: added {} vertices (first: {}), indices {} -> {}, unique: {}",
            result.newVertexCount(), result.firstNewVertexIndex(),
            (result.newIndices().length - result.newVertexCount() * 6) / 3,
            indexCount, uniqueMapper.getUniqueVertexCount());

        return result.firstNewVertexIndex();
    }

    /**
     * Apply edge subdivision at an arbitrary parametric position using unique vertex indices.
     * The new vertex is placed at {@code lerp(posA, posB, t)} along the edge.
     *
     * @param uniqueVertexA First unique vertex index of the edge
     * @param uniqueVertexB Second unique vertex index of the edge
     * @param t Parametric position along the edge (0 = vertexA, 1 = vertexB)
     * @return Unique vertex index of the newly created vertex, or -1 on failure
     */
    public int subdivideEdgeAtParameter(int uniqueVertexA, int uniqueVertexB, float t) {
        if (!initialized) {
            logger.warn("Cannot apply parameterized subdivision: renderer not initialized");
            return -1;
        }

        ISubdivisionProcessor.SubdivisionResult result = subdivisionProcessor.applyEdgeSubdivisionAtParameter(
            uniqueVertexA, uniqueVertexB, t, vertexManager, faceMapper, uniqueMapper, vertexCount);

        if (!result.success()) {
            logger.warn("Parameterized subdivision failed: {}", result.errorMessage());
            return -1;
        }

        // Update state
        vertexCount += result.newVertexCount();
        vertexManager.setIndices(result.newIndices());
        indexCount = result.newIndices().length;
        faceMapper.setMapping(result.newTriangleToFaceId());

        // Rebuild GPU buffers
        float[] interleavedData = geometryBuilder.buildInterleavedData(
            vertexManager.getVertices(), vertexManager.getTexCoords());
        updateVBO(interleavedData);
        updateEBO(result.newIndices());
        drawBatchesDirty = true;

        // Rebuild unique vertex mapping
        uniqueMapper.buildMapping(vertexManager.getVertices());

        // Rebuild topology index
        this.topology = MeshTopologyBuilder.build(
            vertexManager.getVertices(), vertexManager.getIndices(),
            faceMapper, uniqueMapper);

        // Find unique index of the new vertex
        int newUniqueIndex = uniqueMapper.getUniqueIndexForMeshVertex(result.firstNewVertexIndex());

        // Notify listeners
        changeNotifier.notifyTopologyRebuilt(topology);
        changeNotifier.notifyGeometryRebuilt();

        logger.debug("Applied parameterized subdivision (t={}): unique vertices ({}, {}) -> new unique vertex {}",
            t, uniqueVertexA, uniqueVertexB, newUniqueIndex);

        return newUniqueIndex;
    }

    // =========================================================================
    // EDGE INSERTION (face splitting between two existing vertices)
    // =========================================================================

    /**
     * Insert an edge between two unique vertices, splitting shared faces.
     * No new vertices are created — only the index array and face mapping change.
     *
     * @param uniqueVertexA First unique vertex index
     * @param uniqueVertexB Second unique vertex index
     * @return true if the edge was inserted successfully
     */
    public boolean insertEdgeBetweenVertices(int uniqueVertexA, int uniqueVertexB) {
        if (!initialized) {
            logger.warn("Cannot insert edge: renderer not initialized");
            return false;
        }
        if (topology == null) {
            logger.warn("Cannot insert edge: topology not available");
            return false;
        }

        EdgeInsertionProcessor processor = new EdgeInsertionProcessor();
        EdgeInsertionProcessor.InsertionResult result = processor.insertEdge(
            uniqueVertexA, uniqueVertexB, topology, vertexManager, faceMapper, faceTextureManager);

        if (!result.success()) {
            logger.warn("Edge insertion failed: {}", result.errorMessage());
            return false;
        }

        // Update index array and face mapping
        vertexManager.setIndices(result.newIndices());
        indexCount = result.newIndices().length;
        faceMapper.setMapping(result.newTriangleToFaceId());

        // Rebuild GPU buffers (VBO unchanged — no new vertices; EBO updated)
        updateEBO(result.newIndices());
        drawBatchesDirty = true;

        // Rebuild unique vertex mapping (vertex array unchanged, but rebuild for consistency)
        uniqueMapper.buildMapping(vertexManager.getVertices());

        // Rebuild topology index
        this.topology = MeshTopologyBuilder.build(
            vertexManager.getVertices(), vertexManager.getIndices(),
            faceMapper, uniqueMapper);

        // Notify listeners
        changeNotifier.notifyTopologyRebuilt(topology);
        changeNotifier.notifyGeometryRebuilt();

        logger.debug("Inserted edge between unique vertices {} and {}: {} triangles, {} faces",
            uniqueVertexA, uniqueVertexB,
            result.newIndices().length / 3, result.newFaceCount());

        return true;
    }

    // =========================================================================
    // FACE DELETION (remove selected face)
    // =========================================================================

    /**
     * Delete a face from the mesh by removing all its triangles.
     * No vertices are removed — boundary vertices remain for face creation.
     *
     * @param faceId Face ID to delete
     * @return true if the face was deleted successfully
     */
    public boolean deleteFace(int faceId) {
        if (!initialized) {
            logger.warn("Cannot delete face: renderer not initialized");
            return false;
        }
        if (topology == null) {
            logger.warn("Cannot delete face: topology not available");
            return false;
        }

        FaceDeletionProcessor processor = new FaceDeletionProcessor();
        FaceDeletionProcessor.FaceDeletionResult result = processor.deleteFace(
            faceId, topology, vertexManager, faceMapper, faceTextureManager);

        if (!result.success()) {
            logger.warn("Face deletion failed: {}", result.errorMessage());
            return false;
        }

        // Update index array and face mapping
        vertexManager.setIndices(result.newIndices());
        indexCount = result.newIndices().length;
        faceMapper.setMapping(result.newTriangleToFaceId());

        // Rebuild GPU buffers (VBO unchanged — no vertices removed; EBO updated)
        updateEBO(result.newIndices());
        drawBatchesDirty = true;

        // Rebuild unique vertex mapping (vertex array unchanged, but rebuild for consistency)
        uniqueMapper.buildMapping(vertexManager.getVertices());

        // Rebuild topology
        this.topology = MeshTopologyBuilder.build(
            vertexManager.getVertices(), vertexManager.getIndices(),
            faceMapper, uniqueMapper);

        // Notify listeners
        changeNotifier.notifyTopologyRebuilt(topology);
        changeNotifier.notifyGeometryRebuilt();

        logger.debug("Deleted face {}: {} triangles, {} faces remaining",
            faceId, result.newIndices().length / 3, result.newFaceCount());

        return true;
    }

    // =========================================================================
    // FACE CREATION (from selected vertices)
    // =========================================================================

    /**
     * Create a new face from selected unique vertices using the default material.
     * Vertices must be in the desired winding order (selection order controls normals).
     * No new vertices are created — only the index array and face mapping grow.
     *
     * @param selectedUniqueVertices Unique vertex indices forming the polygon, in winding order
     * @return true if the face was created successfully
     */
    public boolean createFaceFromVertices(int[] selectedUniqueVertices) {
        return createFaceFromVertices(selectedUniqueVertices, MaterialDefinition.DEFAULT.materialId());
    }

    /**
     * Create a new face from selected unique vertices with a specific material.
     * Vertices must be in the desired winding order (selection order controls normals).
     * No new vertices are created — only the index array and face mapping grow.
     *
     * @param selectedUniqueVertices Unique vertex indices forming the polygon, in winding order
     * @param activeMaterialId       Material ID to assign to the new face
     * @return true if the face was created successfully
     */
    public boolean createFaceFromVertices(int[] selectedUniqueVertices, int activeMaterialId) {
        if (!initialized) {
            logger.warn("Cannot create face: renderer not initialized");
            return false;
        }
        if (topology == null) {
            logger.warn("Cannot create face: topology not available");
            return false;
        }

        FaceCreationProcessor processor = new FaceCreationProcessor();
        FaceCreationProcessor.FaceCreationResult result = processor.createFace(
            selectedUniqueVertices, topology, vertexManager, faceMapper,
            faceTextureManager, activeMaterialId);

        if (!result.success()) {
            logger.warn("Face creation failed: {}", result.errorMessage());
            return false;
        }

        // Update index array and face mapping
        vertexManager.setIndices(result.newIndices());
        indexCount = result.newIndices().length;
        faceMapper.setMapping(result.newTriangleToFaceId());

        // Rebuild GPU buffers (VBO unchanged — no new vertices; EBO updated)
        updateEBO(result.newIndices());
        drawBatchesDirty = true;

        // Rebuild unique vertex mapping (vertex array unchanged, but rebuild for consistency)
        uniqueMapper.buildMapping(vertexManager.getVertices());

        // Rebuild topology
        this.topology = MeshTopologyBuilder.build(
            vertexManager.getVertices(), vertexManager.getIndices(),
            faceMapper, uniqueMapper);

        // Notify listeners
        changeNotifier.notifyTopologyRebuilt(topology);
        changeNotifier.notifyGeometryRebuilt();

        logger.debug("Created face {} from {} vertices: {} triangles, {} faces",
            result.newFaceId(), selectedUniqueVertices.length,
            result.newIndices().length / 3, result.newFaceCount());

        return true;
    }

    // =========================================================================
    // TEXTURE MANAGEMENT
    // =========================================================================

    /**
     * Set the texture for rendering.
     */
    public void setTexture(int textureId) {
        this.textureId = textureId;
        this.useTexture = textureId > 0;
    }

    /**
     * Assign a material to a specific face.
     * Updates the face's texture mapping to use the full region of the specified material.
     *
     * @param faceId     Face identifier
     * @param materialId Material to assign (must be registered with the FaceTextureManager)
     */
    public void setFaceMaterial(int faceId, int materialId) {
        faceTextureManager.assignDefaultMapping(faceId, materialId);
        regenerateUVsAndUpload();
        drawBatchesDirty = true;
    }

    /**
     * Get the face texture manager for direct per-face UV control.
     * Provides access to material registration, face mapping queries, and
     * programmatic UV assignment.
     *
     * @return The face texture manager owned by this renderer
     */
    public FaceTextureManager getFaceTextureManager() {
        return faceTextureManager;
    }

    /**
     * Regenerate UV coordinates for faces with non-default materials and upload to GPU.
     * Preserves existing UVs for default-material faces so the global texture continues
     * to render correctly on faces that were not explicitly assigned a material.
     *
     * <p>Before generating UVs, duplicates any mesh vertices shared between faces
     * with different materials. This prevents UV coordinate conflicts where the
     * per-face UV generator would overwrite a shared vertex's UV with values
     * appropriate for only one of the faces.
     */
    private void regenerateUVsAndUpload() {
        float[] vertices = vertexManager.getVertices();
        int[] indices = vertexManager.getIndices();
        float[] existingTexCoords = vertexManager.getTexCoords();
        if (vertices == null || indices == null) {
            return;
        }

        // Duplicate shared vertices at material boundaries to avoid UV conflicts
        boolean verticesDuplicated = duplicateSharedUVSeamVertices();
        if (verticesDuplicated) {
            vertices = vertexManager.getVertices();
            indices = vertexManager.getIndices();
            existingTexCoords = vertexManager.getTexCoords();
            vertexCount = vertices.length / 3;

            // Rebuild topology so subsequent operations see the duplicated vertices
            uniqueMapper.buildMapping(vertices);
            this.topology = MeshTopologyBuilder.build(vertices, indices, faceMapper, uniqueMapper);
        }

        // Generate per-face UVs (conflict-free after vertex duplication)
        float[] generatedTexCoords = uvGenerator.generatePerFaceUVs(vertices, indices, faceMapper);

        // Merge: apply generated UVs to faces with non-default materials or
        // split sub-regions; preserve existing UVs for unsplit default-material faces
        float[] finalTexCoords;
        if (existingTexCoords != null && existingTexCoords.length == generatedTexCoords.length) {
            finalTexCoords = existingTexCoords.clone();
            int triangleCount = indices.length / 3;
            for (int t = 0; t < triangleCount; t++) {
                int faceId = faceMapper.getOriginalFaceIdForTriangle(t);
                if (faceId < 0) continue;
                FaceTextureMapping mapping = faceTextureManager.getFaceMapping(faceId);
                if (mapping != null
                    && (mapping.materialId() != MaterialDefinition.DEFAULT.materialId()
                        || !mapping.uvRegion().equals(FaceTextureMapping.FULL_REGION))) {
                    for (int v = 0; v < 3; v++) {
                        int idx = indices[t * 3 + v];
                        finalTexCoords[idx * 2] = generatedTexCoords[idx * 2];
                        finalTexCoords[idx * 2 + 1] = generatedTexCoords[idx * 2 + 1];
                    }
                }
            }
        } else {
            finalTexCoords = generatedTexCoords;
        }

        vertexManager.setData(vertices, finalTexCoords, indices);

        if (initialized) {
            float[] interleavedData = geometryBuilder.buildInterleavedData(vertices, finalTexCoords);
            updateVBO(interleavedData);
        }
    }

    /**
     * Duplicate mesh vertices shared between faces with different materials.
     *
     * <p>When an edge splits a face, both sub-faces reuse the same mesh vertices
     * at the split edge. If the sub-faces are later assigned different materials,
     * the per-face UV generator computes conflicting UV values for the shared
     * vertices (each face normalizes within its own bounding box). Since UVs are
     * stored per-vertex, only one face's UVs can win — the other face renders
     * with incorrect UVs.
     *
     * <p>This method detects such conflicts and creates duplicate vertices so each
     * face has independent UV coordinates. The duplication is idempotent: calling
     * it again after vertices have already been split finds no conflicts.
     *
     * @return true if any vertices were duplicated (vertex/index data changed)
     */
    private boolean duplicateSharedUVSeamVertices() {
        int[] indices = vertexManager.getIndices();
        float[] vertices = vertexManager.getVertices();
        if (indices == null || vertices == null) {
            return false;
        }

        int triangleCount = indices.length / 3;

        // Map: mesh vertex index → { materialId → list of (triangle, localVertex) references }
        Map<Integer, Map<Integer, List<int[]>>> vertexMaterialRefs = new HashMap<>();

        for (int t = 0; t < triangleCount; t++) {
            int faceId = faceMapper.getOriginalFaceIdForTriangle(t);
            if (faceId < 0) continue;

            FaceTextureMapping mapping = faceTextureManager.getFaceMapping(faceId);
            int materialId = (mapping != null) ? mapping.materialId() : MaterialDefinition.DEFAULT.materialId();

            for (int v = 0; v < 3; v++) {
                int meshIdx = indices[t * 3 + v];
                vertexMaterialRefs
                    .computeIfAbsent(meshIdx, k -> new HashMap<>())
                    .computeIfAbsent(materialId, k -> new ArrayList<>())
                    .add(new int[]{t, v});
            }
        }

        // Find vertices used by 2+ different materials (need duplication)
        List<Map.Entry<Integer, Map<Integer, List<int[]>>>> conflicts = new ArrayList<>();
        int additionalVertices = 0;
        for (Map.Entry<Integer, Map<Integer, List<int[]>>> entry : vertexMaterialRefs.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.add(entry);
                additionalVertices += entry.getValue().size() - 1;
            }
        }

        if (conflicts.isEmpty()) {
            return false;
        }

        // Expand vertex arrays to accommodate duplicates
        int currentVertexCount = vertices.length / 3;
        vertexManager.expandVertexArrays(additionalVertices);
        vertices = vertexManager.getVertices();

        // Clone indices for modification
        int[] newIndices = indices.clone();
        int nextNewVertex = currentVertexCount;

        for (Map.Entry<Integer, Map<Integer, List<int[]>>> conflict : conflicts) {
            int originalIdx = conflict.getKey();
            boolean first = true;

            for (Map.Entry<Integer, List<int[]>> materialEntry : conflict.getValue().entrySet()) {
                if (first) {
                    first = false;
                    continue; // First material keeps the original vertex
                }

                // Create duplicate vertex at the same position
                int newIdx = nextNewVertex++;
                vertices[newIdx * 3] = vertices[originalIdx * 3];
                vertices[newIdx * 3 + 1] = vertices[originalIdx * 3 + 1];
                vertices[newIdx * 3 + 2] = vertices[originalIdx * 3 + 2];

                // Remap this material's triangle references to the new vertex
                for (int[] triRef : materialEntry.getValue()) {
                    newIndices[triRef[0] * 3 + triRef[1]] = newIdx;
                }
            }
        }

        vertexManager.setIndices(newIndices);

        logger.debug("Duplicated {} vertices at {} material boundary seams",
            additionalVertices, conflicts.size());
        return true;
    }

    /**
     * Check if any face has a non-default material assigned.
     * Used by {@link #doRender} to choose between fast single-texture path
     * and multi-material batched rendering.
     *
     * @return true if at least one face has a custom material
     */
    private boolean hasCustomMaterials() {
        for (FaceTextureMapping mapping : faceTextureManager.getAllMappings()) {
            if (mapping.materialId() != MaterialDefinition.DEFAULT.materialId()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuild draw batches by grouping triangles by their material's texture ID.
     * Uploads a sorted index array to the EBO and creates batch descriptors
     * for multi-texture rendering.
     *
     * <p>Triangles with no mapping or the default material use textureId 0,
     * which is resolved to the global texture at draw time.
     */
    private void rebuildDrawBatches() {
        int[] indices = vertexManager.getIndices();
        if (indices == null || indices.length == 0) {
            drawBatches = List.of();
            drawBatchesDirty = false;
            return;
        }

        int triangleCount = indices.length / 3;

        // Group triangle indices by material texture ID
        Map<Integer, List<int[]>> textureTriangles = new LinkedHashMap<>();

        for (int t = 0; t < triangleCount; t++) {
            int faceId = faceMapper.getOriginalFaceIdForTriangle(t);
            int texId = 0;

            if (faceId >= 0) {
                FaceTextureMapping mapping = faceTextureManager.getFaceMapping(faceId);
                if (mapping != null) {
                    MaterialDefinition material = faceTextureManager.getMaterial(mapping.materialId());
                    if (material != null && material.textureId() > 0) {
                        texId = material.textureId();
                    }
                }
            }

            int base = t * 3;
            textureTriangles.computeIfAbsent(texId, k -> new ArrayList<>())
                    .add(new int[]{indices[base], indices[base + 1], indices[base + 2]});
        }

        // Build sorted index array and batch descriptors
        int[] sortedIndices = new int[indices.length];
        List<MaterialDrawBatch> batches = new ArrayList<>();
        int offset = 0;

        for (Map.Entry<Integer, List<int[]>> entry : textureTriangles.entrySet()) {
            int texId = entry.getKey();
            List<int[]> triangles = entry.getValue();
            int batchIndexCount = triangles.size() * 3;

            for (int[] tri : triangles) {
                sortedIndices[offset] = tri[0];
                sortedIndices[offset + 1] = tri[1];
                sortedIndices[offset + 2] = tri[2];
                offset += 3;
            }

            batches.add(new MaterialDrawBatch(texId, offset - batchIndexCount, batchIndexCount));
        }

        // Upload sorted indices directly to the EBO. This method is called from doRender()
        // while the VAO is bound — must NOT call updateEBO() which unbinds the EBO
        // (glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0)) and would detach it from the active VAO.
        // Topology queries must always use vertexManager, not the EBO.
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, sortedIndices, GL_DYNAMIC_DRAW);

        drawBatches = batches;
        drawBatchesDirty = false;

        logger.debug("Rebuilt draw batches: {} batch(es) from {} triangles",
                batches.size(), triangleCount);
    }

    // =========================================================================
    // SNAPSHOT RESTORE (for undo/redo)
    // =========================================================================

    /**
     * Restore full mesh state from a snapshot.
     * Replaces vertex data, indices, face mapping, and face texture state.
     * Rebuilds topology, uploads GPU buffers, and fires change notifications.
     *
     * <p>Used by the undo/redo system ({@link com.openmason.main.systems.services.commands.MeshSnapshot}).
     *
     * @param vertices          Vertex positions (x,y,z interleaved)
     * @param texCoords         Texture coordinates (u,v interleaved), may be null
     * @param indices           Triangle indices
     * @param triangleToFaceId  Triangle-to-face mapping
     * @param faceMappings      Per-face texture mappings (faceId → mapping)
     * @param materials         Registered materials (materialId → definition)
     */
    public void restoreFromSnapshot(float[] vertices, float[] texCoords, int[] indices,
                                     int[] triangleToFaceId,
                                     Map<Integer, FaceTextureMapping> faceMappings,
                                     Map<Integer, MaterialDefinition> materials) {
        // Set vertex data
        vertexManager.setData(
            vertices != null ? vertices.clone() : null,
            texCoords != null ? texCoords.clone() : null,
            indices != null ? indices.clone() : null
        );

        // Update counts
        vertexCount = vertices != null ? vertices.length / 3 : 0;
        indexCount = indices != null ? indices.length : 0;

        // Restore face mapping
        if (triangleToFaceId != null && triangleToFaceId.length > 0) {
            faceMapper.setMapping(triangleToFaceId.clone());
        } else {
            faceMapper.clear();
        }

        // Restore face texture state
        faceTextureManager.clear();
        if (materials != null) {
            for (MaterialDefinition mat : materials.values()) {
                if (mat.materialId() != MaterialDefinition.DEFAULT.materialId()) {
                    faceTextureManager.registerMaterial(mat);
                }
            }
        }
        if (faceMappings != null) {
            for (FaceTextureMapping mapping : faceMappings.values()) {
                faceTextureManager.setFaceMapping(mapping);
            }
        }

        // Rebuild unique vertex mapping
        if (vertices != null) {
            uniqueMapper.buildMapping(vertices);
        }

        // Rebuild topology index
        this.topology = MeshTopologyBuilder.build(
            vertexManager.getVertices(), vertexManager.getIndices(),
            faceMapper, uniqueMapper);

        // Update GPU buffers if initialized
        if (initialized) {
            float[] interleavedData = geometryBuilder.buildInterleavedData(
                vertexManager.getVertices(), vertexManager.getTexCoords());
            updateVBO(interleavedData);
            if (vertexManager.getIndices() != null) {
                updateEBO(vertexManager.getIndices());
            }
        }
        drawBatchesDirty = true;

        // Notify listeners
        changeNotifier.notifyTopologyRebuilt(topology);
        changeNotifier.notifyGeometryRebuilt();

        logger.debug("Restored from snapshot: {} vertices, {} triangles",
            vertexCount, indexCount / 3);
    }

    /**
     * Regenerate UV coordinates and upload to GPU.
     * Public entry point for the undo/redo system to refresh UVs after
     * a face texture assignment change.
     */
    public void refreshUVs() {
        regenerateUVsAndUpload();
        drawBatchesDirty = true;
    }

    // =========================================================================
    // MESH DATA LOADING (for .OMO format)
    // =========================================================================

    /**
     * Load mesh state from MeshData (restored from .OMO file).
     * This replaces the current geometry with the loaded data.
     *
     * @param meshData the mesh data to load
     */
    public void loadMeshData(OMOFormat.MeshData meshData) {
        if (meshData == null || !meshData.hasCustomGeometry()) {
            logger.debug("No custom mesh data to load");
            return;
        }

        float[] vertices = meshData.vertices();
        float[] texCoords = meshData.texCoords();
        int[] indices = meshData.indices();
        int[] triangleToFaceId = meshData.triangleToFaceId();
        String uvModeStr = meshData.uvMode();

        // Update UV mode
        if (uvModeStr != null) {
            try {
                stateManager.setUVMode(UVMode.valueOf(uvModeStr));
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown UV mode '{}', defaulting to FLAT", uvModeStr);
                stateManager.setUVMode(UVMode.FLAT);
            }
        }

        // Clear parts (we're loading direct mesh data, not part-based)
        stateManager.clearParts();

        // Set vertex data
        vertexManager.setData(vertices.clone(), texCoords != null ? texCoords.clone() : null, indices != null ? indices.clone() : null);

        // Update counts
        vertexCount = vertices.length / 3;
        indexCount = indices != null ? indices.length : 0;

        // Restore face mapping (1:1 triangle-to-face for all geometry)
        if (triangleToFaceId != null && triangleToFaceId.length > 0) {
            faceMapper.setMapping(triangleToFaceId.clone());
        } else if (indices != null) {
            faceMapper.initializeStandardMapping(indices.length / 3);
        } else {
            faceMapper.clear();
        }

        // Rebuild unique vertex mapping
        uniqueMapper.buildMapping(vertices);

        // Rebuild topology index
        this.topology = MeshTopologyBuilder.build(
            vertexManager.getVertices(), vertexManager.getIndices(),
            faceMapper, uniqueMapper);

        // Update GPU buffers if initialized
        if (initialized) {
            float[] interleavedData = geometryBuilder.buildInterleavedData(
                vertexManager.getVertices(), vertexManager.getTexCoords());
            updateVBO(interleavedData);
            if (vertexManager.getIndices() != null) {
                updateEBO(vertexManager.getIndices());
            }
        }
        drawBatchesDirty = true;

        // Notify listeners
        changeNotifier.notifyTopologyRebuilt(topology);
        changeNotifier.notifyGeometryRebuilt();

        logger.info("Loaded custom mesh data: {} vertices, {} triangles, {} unique positions, uvMode={}",
                vertexCount, indexCount / 3, uniqueMapper.getUniqueVertexCount(), stateManager.getUVMode());
    }

    /**
     * Create a MeshData snapshot from current internal state for saving to .OMO file.
     * Arrays are cloned for safety — the returned MeshData is fully independent.
     *
     * @return MeshData with current vertex/index/face data, or null if no vertex data available
     */
    public OMOFormat.MeshData toMeshData() {
        float[] vertices = vertexManager.getVertices();
        if (vertices == null || vertices.length == 0) {
            logger.warn("No vertex data available to snapshot");
            return null;
        }

        float[] texCoords = vertexManager.getTexCoords();
        int[] indices = vertexManager.getIndices();
        int[] triangleToFaceId = faceMapper.getMappingCopy();
        String uvModeStr = stateManager.getUVMode() != null ? stateManager.getUVMode().name() : "FLAT";

        logger.debug("Created mesh data snapshot: {} vertices, {} indices, uvMode={}",
                vertices.length / 3,
                indices != null ? indices.length : 0,
                uvModeStr);

        return new OMOFormat.MeshData(
                vertices.clone(),
                texCoords != null ? texCoords.clone() : null,
                indices != null ? indices.clone() : null,
                triangleToFaceId,
                uvModeStr);
    }

    /**
     * Get current texture coordinates array.
     * Useful for debugging and mesh data extraction.
     *
     * @return copy of texture coordinates array, or null if none
     */
    public float[] getTexCoords() {
        float[] texCoords = vertexManager.getTexCoords();
        return texCoords != null ? texCoords.clone() : null;
    }

    /**
     * Get current triangle indices array.
     * Useful for debugging and mesh data extraction.
     *
     * @return copy of indices array, or null if none
     */
    public int[] getIndices() {
        int[] indices = vertexManager.getIndices();
        return indices != null ? indices.clone() : null;
    }

    /**
     * Get current face mapping array.
     *
     * @return copy of face mapping, or null if none
     */
    public int[] getTriangleToFaceMapping() {
        return faceMapper.getMappingCopy();
    }

    // =========================================================================
    // OPENGL RENDERING (BaseRenderer implementation)
    // =========================================================================

    @Override
    protected GeometryData createGeometry() {
        float[] vertices = vertexManager.getVertices();
        float[] texCoords = vertexManager.getTexCoords();
        int[] indices = vertexManager.getIndices();

        float[] interleaved = geometryBuilder.buildInterleavedData(vertices, texCoords);
        if (interleaved.length == 0) {
            return GeometryData.nonIndexed(new float[0], 0, geometryBuilder.getStride());
        }

        if (indices != null && indices.length > 0) {
            return GeometryData.indexed(interleaved, indices, vertexCount, geometryBuilder.getStride());
        } else {
            return GeometryData.nonIndexed(interleaved, vertexCount, geometryBuilder.getStride());
        }
    }

    @Override
    protected void configureVertexAttributes() {
        // Position attribute (location = 0): 3 floats
        glVertexAttribPointer(0, geometryBuilder.getPositionComponents(), GL_FLOAT, false,
            geometryBuilder.getStride(), geometryBuilder.getPositionOffset());
        glEnableVertexAttribArray(0);

        // TexCoord attribute (location = 1): 2 floats
        glVertexAttribPointer(1, geometryBuilder.getTexCoordComponents(), GL_FLOAT, false,
            geometryBuilder.getStride(), geometryBuilder.getTexCoordOffset());
        glEnableVertexAttribArray(1);
    }

    @Override
    protected void doRender(ShaderProgram shader, RenderContext context) {
        if (vertexCount == 0) {
            return;
        }

        // Fast path: no custom materials — single texture, one draw call
        if (!hasCustomMaterials()) {
            if (useTexture && textureId > 0) {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, textureId);
                shader.setBool("uUseTexture", true);
                shader.setInt("uTexture", 0);
            } else {
                shader.setBool("uUseTexture", false);
            }

            if (indexCount > 0 && ebo != 0) {
                glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
            } else {
                glDrawArrays(GL_TRIANGLES, 0, vertexCount);
            }
            return;
        }

        // Multi-material path: rebuild batches if dirty, then draw per batch
        if (drawBatchesDirty) {
            rebuildDrawBatches();
        }

        shader.setInt("uTexture", 0);

        for (MaterialDrawBatch batch : drawBatches) {
            int batchTextureId = batch.textureId() > 0 ? batch.textureId() : textureId;

            if (batchTextureId > 0) {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, batchTextureId);
                shader.setBool("uUseTexture", true);
            } else {
                shader.setBool("uUseTexture", false);
            }

            glDrawElements(GL_TRIANGLES, batch.indexCount(), GL_UNSIGNED_INT,
                    (long) batch.indexOffset() * 4L);
        }
    }

    @Override
    protected void setUniforms(ShaderProgram shader, RenderContext context, org.joml.Matrix4f modelMatrix) {
        // BaseRenderer.render() already incorporates modelMatrix into uMVPMatrix (MVP = P*V*M).
        // The MATRIX shader multiplies: gl_Position = uMVPMatrix * uModelMatrix * pos
        // To avoid double-transformation, pass identity for uModelMatrix.
        // This ensures the model transforms at the same rate as the wireframe overlay.
        shader.setMat4("uModelMatrix", IDENTITY_MATRIX);
    }

    // =========================================================================
    // MESH DATA EXTRACTION (for MeshManager and overlay renderers)
    // =========================================================================

    /**
     * Extract face data with full topology information.
     * Returns structured result with per-face vertex counts and offsets,
     * supporting any face topology (triangles, quads, n-gons).
     * Uses pre-computed topology when available (O(1) per face), falls back to
     * triangle scanning (O(T) per face) otherwise.
     *
     * @return FaceExtractionResult with positions and topology info, or null if no data
     */
    public GMRFaceExtractor.FaceExtractionResult extractFaceData() {
        if (topology != null) {
            return faceExtractor.extractFaceData(vertexManager.getVertices(), topology);
        }
        return faceExtractor.extractFaceData(
            vertexManager.getVertices(),
            vertexManager.getIndices(),
            faceMapper
        );
    }

    /**
     * Extract edge positions from current mesh data.
     * Each edge is represented as 2 endpoints with 6 floats per edge.
     * This data is used by EdgeRenderer for overlay rendering.
     * Topology-aware: faces with N vertices contribute N edges.
     * Uses pre-computed topology when available (O(1) per face), falls back to
     * triangle scanning (O(T) per face) otherwise.
     *
     * SOLID: Delegates to GMREdgeExtractor for extraction logic (Single Responsibility).
     *
     * @return Array of edge endpoint positions [x1,y1,z1, x2,y2,z2, ...]
     *         or empty array if no edge data available
     */
    public float[] extractEdgePositions() {
        if (topology != null) {
            return edgeExtractor.extractEdgePositions(vertexManager.getVertices(), topology);
        }
        return edgeExtractor.extractEdgePositions(
            vertexManager.getVertices(),
            vertexManager.getIndices(),
            faceMapper
        );
    }

    /**
     * Get face count from current mesh data.
     *
     * @return Number of faces in the mesh
     */
    public int getFaceCount() {
        return faceMapper.getOriginalFaceCount();
    }

    /**
     * Get edge count from current mesh data.
     * Topology-aware: sums edges per face based on actual face vertex counts.
     *
     * @return Number of edges in the mesh
     */
    public int getEdgeCount() {
        int upperBound = faceMapper.getFaceIdUpperBound();
        int totalEdges = 0;
        for (int i = 0; i < upperBound; i++) {
            totalEdges += faceMapper.getEdgeCountForFace(i);
        }
        return totalEdges;
    }

    /**
     * Get unique vertex count.
     *
     * @return Number of unique geometric vertices
     */
    public int getUniqueVertexCount() {
        return uniqueMapper.getUniqueVertexCount();
    }
}
