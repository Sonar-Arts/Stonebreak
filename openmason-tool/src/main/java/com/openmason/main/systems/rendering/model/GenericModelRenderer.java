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
import com.openmason.main.systems.rendering.model.gmr.uv.*;
import com.openmason.main.systems.rendering.model.io.omo.OMOFormat;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import org.joml.Vector3f;

import java.util.List;

import static org.lwjgl.opengl.GL11.*;
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

    // Extractors (SOLID: extraction logic separated from renderer)
    private final GMRFaceExtractor faceExtractor;
    private final GMREdgeExtractor edgeExtractor;

    // Texture state (kept inline per KISS - only 3 lines)
    private int textureId = 0;
    private boolean useTexture = false;

    // Cached identity matrix to avoid per-frame allocation in setUniforms
    private static final org.joml.Matrix4f IDENTITY_MATRIX = new org.joml.Matrix4f();

    /**
     * Create a GenericModelRenderer with default subsystems.
     */
    public GenericModelRenderer() {
        this(
            new VertexDataManager(),
            new UniqueVertexMapper(),
            new MeshChangeNotifier(),
            new TriangleFaceMapper(),
            new SubdivisionProcessor(),
            new GeometryDataBuilder(),
            new UVCoordinateGenerator(),
            new ModelStateManager(),
            new GMRFaceExtractor(),
            new GMREdgeExtractor()
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
            GMREdgeExtractor edgeExtractor) {
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
    // Use LegacyGeometryGenerator for legacy BlockModel support, or provide explicit mesh data via loadMeshData().

    /**
     * Set UV mapping mode and update texture coordinates for existing geometry.
     * This preserves geometry modifications (vertex positions) while updating texture coordinates.
     * Works best with unsubdivided 24-vertex cubes; subdivided meshes use approximation.
     *
     * Note: This only works if geometry is already loaded. For new models, provide
     * mesh data with the correct UV mode via loadMeshData().
     *
     * @param uvMode The new UV mode
     */
    public void setUVMode(UVMode uvMode) {
        if (uvMode == null || uvMode == stateManager.getUVMode()) {
            return;
        }

        logger.info("Updating UV mode from {} to {} (preserving geometry)", stateManager.getUVMode(), uvMode);
        stateManager.setUVMode(uvMode);

        float[] vertices = vertexManager.getVertices();
        float[] texCoords = vertexManager.getTexCoords();

        if (vertices == null || texCoords == null) {
            logger.warn("Cannot update UVs: no vertex data");
            return;
        }

        int currentVertexCount = vertices.length / 3;

        // Generate new UV coordinates based on the UV mode
        float[] newTexCoords = uvGenerator.generateUVs(uvMode, currentVertexCount);

        // Update texture coordinates in vertex manager
        for (int i = 0; i < currentVertexCount && i * 2 + 1 < newTexCoords.length; i++) {
            vertexManager.setTexCoordDirect(i, newTexCoords[i * 2], newTexCoords[i * 2 + 1]);
        }

        // Update GPU buffers
        if (initialized) {
            float[] updatedTexCoords = vertexManager.getTexCoords();
            float[] interleavedData = geometryBuilder.buildInterleavedData(vertices, updatedTexCoords);
            updateVBO(interleavedData);
        }

        logger.info("UV mode updated to {} for {} vertices", uvMode, currentVertexCount);
    }

    /**
     * Get the current UV mode.
     */
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
     */
    public int getOriginalFaceIdForTriangle(int triangleIndex) {
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
     */
    public int[] getMeshIndicesForUniqueVertex(int uniqueIndex) {
        return uniqueMapper.getMeshIndicesForUniqueVertex(uniqueIndex);
    }

    /**
     * Get the unique vertex index for a given mesh vertex.
     */
    public int getUniqueIndexForMeshVertex(int meshIndex) {
        return uniqueMapper.getUniqueIndexForMeshVertex(meshIndex);
    }

    /**
     * Get all unique vertex positions as an array.
     */
    public float[] getAllUniqueVertexPositions() {
        return uniqueMapper.getAllUniqueVertexPositions(vertexManager.getVertices());
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

        // Rebuild unique vertex mapping
        uniqueMapper.buildMapping(vertexManager.getVertices());

        // Notify listeners
        changeNotifier.notifyGeometryRebuilt();

        logger.debug("Applied subdivision: added {} vertices (first: {}), indices {} -> {}, unique: {}",
            result.newVertexCount(), result.firstNewVertexIndex(),
            (result.newIndices().length - result.newVertexCount() * 6) / 3,
            indexCount, uniqueMapper.getUniqueVertexCount());

        return result.firstNewVertexIndex();
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

        // Update GPU buffers if initialized
        if (initialized) {
            float[] interleavedData = geometryBuilder.buildInterleavedData(
                vertexManager.getVertices(), vertexManager.getTexCoords());
            updateVBO(interleavedData);
            if (vertexManager.getIndices() != null) {
                updateEBO(vertexManager.getIndices());
            }
        }

        // Notify listeners
        changeNotifier.notifyGeometryRebuilt();

        logger.info("Loaded custom mesh data: {} vertices, {} triangles, {} unique positions, uvMode={}",
                vertexCount, indexCount / 3, uniqueMapper.getUniqueVertexCount(), stateManager.getUVMode());
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

        // Bind texture if available
        if (useTexture && textureId > 0) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, textureId);
            shader.setBool("uUseTexture", true);
            shader.setInt("uTexture", 0);
        } else {
            shader.setBool("uUseTexture", false);
        }

        // Draw
        if (indexCount > 0 && ebo != 0) {
            glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
        } else {
            glDrawArrays(GL_TRIANGLES, 0, vertexCount);
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
     * Extract face positions from current mesh data.
     * Each face is represented as 4 vertices (quad) with 12 floats per face.
     * This data is used by FaceRenderer for overlay rendering.
     *
     * SOLID: Delegates to GMRFaceExtractor for extraction logic (Single Responsibility).
     *
     * @return Array of face vertex positions [v0x,v0y,v0z, v1x,v1y,v1z, v2x,v2y,v2z, v3x,v3y,v3z, ...]
     *         or empty array if no face data available
     * @deprecated Use {@link #extractFaceData()} for topology-aware extraction
     */
    @Deprecated
    public float[] extractFacePositions() {
        return faceExtractor.extractFacePositions(
            vertexManager.getVertices(),
            vertexManager.getIndices(),
            faceMapper
        );
    }

    /**
     * Extract face data with full topology information.
     * Returns structured result with per-face vertex counts and offsets,
     * supporting any face topology (triangles, quads, n-gons).
     *
     * @return FaceExtractionResult with positions and topology info, or null if no data
     */
    public GMRFaceExtractor.FaceExtractionResult extractFaceData() {
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
     *
     * SOLID: Delegates to GMREdgeExtractor for extraction logic (Single Responsibility).
     *
     * @return Array of edge endpoint positions [x1,y1,z1, x2,y2,z2, ...]
     *         or empty array if no edge data available
     */
    public float[] extractEdgePositions() {
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
