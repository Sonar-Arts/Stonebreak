package com.openmason.main.systems.rendering.model;

import com.openmason.main.systems.rendering.api.BaseRenderer;
import com.openmason.main.systems.rendering.api.GeometryData;
import com.openmason.main.systems.rendering.api.RenderPass;
import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import com.openmason.main.systems.rendering.model.gmr.*;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;

/**
 * Generic model renderer supporting arbitrary geometry.
 * NOT locked to 8-vertex cube topology - supports any vertex count.
 * Extends BaseRenderer for consistent initialization and rendering.
 *
 * Features:
 * - Multi-part model support
 * - Dynamic vertex updates for editing
 * - Arbitrary vertex counts (not cube-locked)
 * - OMO format loading support
 *
 * Architecture:
 * - Delegates to subsystems for specific responsibilities (SOLID principles)
 * - IVertexDataManager: Vertex position management
 * - IUniqueVertexMapper: Index-based unique vertex lookup
 * - IMeshChangeNotifier: Observer pattern for mesh changes
 * - ITriangleFaceMapper: Triangle-to-face mapping
 * - ISubdivisionProcessor: Edge subdivision operations
 * - IGeometryDataBuilder: Interleaved data construction
 */
public class GenericModelRenderer extends BaseRenderer {

    // Multi-part model data
    private final List<ModelPart> parts = new ArrayList<>();

    // Subsystems (dependency injection via interfaces)
    private final IVertexDataManager vertexManager;
    private final IUniqueVertexMapper uniqueMapper;
    private final IMeshChangeNotifier changeNotifier;
    private final ITriangleFaceMapper faceMapper;
    private final ISubdivisionProcessor subdivisionProcessor;
    private final IGeometryDataBuilder geometryBuilder;

    // Texture state
    private int textureId = 0;
    private boolean useTexture = false;

    // UV mapping mode
    private UVMode currentUVMode = UVMode.FLAT;

    // Cached model dimensions for UV mode switching
    private int cachedWidth = 0;
    private int cachedHeight = 0;
    private int cachedDepth = 0;
    private double cachedOriginX = 0;
    private double cachedOriginY = 0;
    private double cachedOriginZ = 0;

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
            new GeometryDataBuilder()
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
            IGeometryDataBuilder geometryBuilder) {
        this.vertexManager = vertexManager;
        this.uniqueMapper = uniqueMapper;
        this.changeNotifier = changeNotifier;
        this.faceMapper = faceMapper;
        this.subdivisionProcessor = subdivisionProcessor;
        this.geometryBuilder = geometryBuilder;
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

    /**
     * Load model from OMO format document dimensions.
     * Creates a cube based on the geometry dimensions in the document.
     */
    public void loadFromDimensions(int width, int height, int depth,
                                   double originX, double originY, double originZ) {
        cachedWidth = width;
        cachedHeight = height;
        cachedDepth = depth;
        cachedOriginX = originX;
        cachedOriginY = originY;
        cachedOriginZ = originZ;

        rebuildFromCachedDimensions();
    }

    /**
     * Rebuild geometry from cached dimensions using current UV mode.
     */
    private void rebuildFromCachedDimensions() {
        parts.clear();

        final float PIXELS_PER_UNIT = 16.0f;

        Vector3f origin = new Vector3f(
            (float) cachedOriginX / PIXELS_PER_UNIT,
            (float) cachedOriginY / PIXELS_PER_UNIT,
            (float) cachedOriginZ / PIXELS_PER_UNIT
        );

        Vector3f size = new Vector3f(
            cachedWidth / PIXELS_PER_UNIT,
            cachedHeight / PIXELS_PER_UNIT,
            cachedDepth / PIXELS_PER_UNIT
        );

        ModelPart cubePart = ModelPart.createCube("main", origin, size, currentUVMode);
        parts.add(cubePart);

        rebuildGeometry();
        logger.info("Created model from dimensions: {}x{}x{} pixels -> {}x{}x{} units at ({}, {}, {}) with UV mode: {}",
                cachedWidth, cachedHeight, cachedDepth, size.x, size.y, size.z, origin.x, origin.y, origin.z, currentUVMode);
    }

    /**
     * Set UV mapping mode and regenerate geometry.
     */
    public void setUVMode(UVMode uvMode) {
        if (uvMode == null || uvMode == currentUVMode) {
            return;
        }

        logger.info("Changing UV mode from {} to {}", currentUVMode, uvMode);
        currentUVMode = uvMode;

        if (cachedWidth > 0 && cachedHeight > 0 && cachedDepth > 0) {
            rebuildFromCachedDimensions();
        }
    }

    /**
     * Rebuild geometry from current parts.
     */
    private void rebuildGeometry() {
        if (parts.isEmpty()) {
            vertexManager.setData(null, null, null);
            faceMapper.clear();
            uniqueMapper.clear();
            vertexCount = 0;
            indexCount = 0;
            return;
        }

        // Calculate total sizes
        int totalVertices = 0;
        int totalIndices = 0;
        for (ModelPart part : parts) {
            totalVertices += part.getVertexCount();
            totalIndices += part.getIndexCount();
        }

        // Allocate arrays
        float[] vertices = new float[totalVertices * 3];
        float[] texCoords = new float[totalVertices * 2];
        int[] indices = totalIndices > 0 ? new int[totalIndices] : null;

        // Copy data from parts
        int vertexOffset = 0;
        int indexOffset = 0;
        int baseVertex = 0;

        for (ModelPart part : parts) {
            if (part.vertices() != null) {
                System.arraycopy(part.vertices(), 0, vertices, vertexOffset * 3, part.vertices().length);
            }
            if (part.texCoords() != null) {
                System.arraycopy(part.texCoords(), 0, texCoords, vertexOffset * 2, part.texCoords().length);
            }
            if (part.indices() != null && indices != null) {
                for (int i = 0; i < part.indices().length; i++) {
                    indices[indexOffset + i] = part.indices()[i] + baseVertex;
                }
                indexOffset += part.indices().length;
            }
            baseVertex += part.getVertexCount();
            vertexOffset += part.getVertexCount();
        }

        // Update subsystems
        vertexManager.setData(vertices, texCoords, indices);
        vertexCount = totalVertices;
        indexCount = totalIndices;

        // Initialize face mapping
        if (indices != null) {
            faceMapper.initializeStandardMapping(indices.length / 3);
        } else {
            faceMapper.clear();
        }

        // Update GPU buffers if initialized
        if (initialized) {
            float[] interleavedData = geometryBuilder.buildInterleavedData(vertices, texCoords);
            updateVBO(interleavedData);
            if (indices != null) {
                updateEBO(indices);
            }
        }

        // Build unique vertex mapping
        uniqueMapper.buildMapping(vertices);

        // Notify listeners
        changeNotifier.notifyGeometryRebuilt();

        logger.debug("Rebuilt geometry: {} vertices, {} indices, {} unique positions",
            vertexCount, indexCount, uniqueMapper.getUniqueVertexCount());
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
        if (vertices != null && vertices.length >= 24) {
            logger.info("GenericModelRenderer first 8 vertices:");
            for (int i = 0; i < 8 && i * 3 + 2 < vertices.length; i++) {
                logger.info("  v{}: ({}, {}, {})", i, vertices[i * 3], vertices[i * 3 + 1], vertices[i * 3 + 2]);
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
        shader.setMat4("uModelMatrix", modelMatrix);
    }
}
