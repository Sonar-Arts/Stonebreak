package com.openmason.main.systems.rendering.model;

import com.openmason.main.systems.rendering.api.BaseRenderer;
import com.openmason.main.systems.rendering.api.GeometryData;
import com.openmason.main.systems.rendering.api.RenderPass;
import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
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
 */
public class GenericModelRenderer extends BaseRenderer {

    // Multi-part model data
    private final List<ModelPart> parts = new ArrayList<>();

    // Current vertex data (mutable for live editing)
    private float[] currentVertices;
    private float[] currentTexCoords;
    private int[] currentIndices;

    // Texture
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

    // Stride: position (3) + texCoord (2) = 5 floats
    private static final int STRIDE = 5 * Float.BYTES;
    private static final int FLOATS_PER_VERTEX = 5;

    /**
     * Create a GenericModelRenderer.
     */
    public GenericModelRenderer() {
        logger.debug("GenericModelRenderer created");
    }

    @Override
    public String getDebugName() {
        return "GenericModelRenderer";
    }

    @Override
    public RenderPass getRenderPass() {
        return RenderPass.SCENE;
    }

    /**
     * Load model from OMO format document dimensions.
     * Creates a cube based on the geometry dimensions in the document.
     *
     * @param width Width of the model
     * @param height Height of the model
     * @param depth Depth of the model
     * @param originX Origin X position
     * @param originY Origin Y position
     * @param originZ Origin Z position
     */
    public void loadFromDimensions(int width, int height, int depth,
                                   double originX, double originY, double originZ) {
        // Cache dimensions for UV mode switching
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

        // Scale from pixel dimensions to world units
        // Convention: 16 pixels = 1 world unit (standard block size)
        // This preserves aspect ratios: 16x16x16 → 1x1x1, 8x16x8 → 0.5x1x0.5, etc.
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
        logger.info("Created model from dimensions: {}x{}x{} pixels → {}x{}x{} units at ({}, {}, {}) with UV mode: {}",
                cachedWidth, cachedHeight, cachedDepth, size.x, size.y, size.z, origin.x, origin.y, origin.z, currentUVMode);
    }

    /**
     * Set UV mapping mode and regenerate geometry.
     *
     * @param uvMode The UV mapping mode (CUBE_NET or FLAT)
     */
    public void setUVMode(UVMode uvMode) {
        if (uvMode == null || uvMode == currentUVMode) {
            return;
        }

        logger.info("Changing UV mode from {} to {}", currentUVMode, uvMode);
        currentUVMode = uvMode;

        // Only rebuild if we have cached dimensions
        if (cachedWidth > 0 && cachedHeight > 0 && cachedDepth > 0) {
            rebuildFromCachedDimensions();
        }
    }

    /**
     * Get current UV mapping mode.
     *
     * @return Current UV mode
     */
    public UVMode getUVMode() {
        return currentUVMode;
    }

    /**
     * Load model from a list of ModelParts.
     *
     * @param modelParts The parts to load
     */
    public void loadParts(List<ModelPart> modelParts) {
        parts.clear();
        if (modelParts != null) {
            parts.addAll(modelParts);
        }
        rebuildGeometry();
        logger.debug("Loaded {} model parts", parts.size());
    }

    /**
     * Add a single part to the model.
     *
     * @param part The part to add
     */
    public void addPart(ModelPart part) {
        if (part != null) {
            parts.add(part);
            rebuildGeometry();
        }
    }

    /**
     * Clear all parts.
     */
    public void clearParts() {
        parts.clear();
        currentVertices = null;
        currentTexCoords = null;
        currentIndices = null;
        vertexCount = 0;
        indexCount = 0;
    }

    /**
     * Update a vertex position by its global index.
     * Also updates ALL other mesh vertices at the same geometric position.
     * This is critical after subdivision where multiple mesh vertices share positions.
     *
     * @param globalIndex The global vertex index across all parts
     * @param position The new position
     */
    public void updateVertexPosition(int globalIndex, Vector3f position) {
        if (currentVertices == null || globalIndex < 0) {
            return;
        }

        int offset = globalIndex * 3;
        if (offset + 2 >= currentVertices.length) {
            logger.warn("Vertex index {} out of bounds", globalIndex);
            return;
        }

        // Get the OLD position at this index to find all vertices sharing it
        Vector3f oldPosition = new Vector3f(
            currentVertices[offset],
            currentVertices[offset + 1],
            currentVertices[offset + 2]
        );

        // Find ALL mesh vertices at the old position (handles subdivision vertices)
        java.util.List<Integer> allVerticesAtPosition = findMeshVerticesAtPosition(oldPosition, 0.001f);

        // Update ALL vertices at this position
        for (int vertexIndex : allVerticesAtPosition) {
            int vOffset = vertexIndex * 3;
            if (vOffset + 2 < currentVertices.length) {
                currentVertices[vOffset] = position.x;
                currentVertices[vOffset + 1] = position.y;
                currentVertices[vOffset + 2] = position.z;
            }
        }

        // Rebuild interleaved data and upload to GPU
        float[] interleavedData = buildInterleavedData();
        updateVBO(interleavedData);

        logger.trace("Updated {} vertices at position ({}, {}, {}) to ({}, {}, {})",
            allVerticesAtPosition.size(), oldPosition.x, oldPosition.y, oldPosition.z,
            position.x, position.y, position.z);
    }

    /**
     * Update all vertex positions at once.
     * Compatible with CubeModelRenderer API - expects positions in format [x0,y0,z0, x1,y1,z1, ...].
     * More efficient than multiple individual updates as it only uploads to GPU once.
     *
     * @param positions Array of vertex positions (must match current vertex count * 3)
     */
    public void updateVertexPositions(float[] positions) {
        if (!initialized) {
            logger.warn("Cannot update vertex positions: renderer not initialized");
            return;
        }

        if (positions == null) {
            logger.error("Cannot update vertex positions: positions array is null");
            return;
        }

        if (currentVertices == null) {
            logger.warn("Cannot update vertex positions: no vertices loaded");
            return;
        }

        int expectedLength = currentVertices.length;
        int updateLength = positions.length;

        if (positions.length != expectedLength) {
            // After subdivision, currentVertices grows but caller may have old array size.
            // Update only the vertices that exist in the input array (original vertices).
            // New subdivision vertices already have correct positions.
            logger.debug("Array length mismatch in updateVertexPositions: current {} floats ({}v), input {} floats ({}v) - updating common vertices",
                expectedLength, expectedLength / 3, positions.length, positions.length / 3);
            updateLength = Math.min(positions.length, expectedLength);
        }

        try {
            // After subdivision, mesh has more vertices than the input positions array.
            // We need to update ALL mesh vertices that share each input position.
            // Strategy: For each input position, find the old position at that index,
            // then update all mesh vertices at that old position.

            int inputVertexCount = positions.length / 3;
            int meshVertexCount = currentVertices.length / 3;

            if (inputVertexCount < meshVertexCount) {
                // Post-subdivision: update by position matching
                for (int i = 0; i < inputVertexCount; i++) {
                    int offset = i * 3;
                    Vector3f oldPos = new Vector3f(
                        currentVertices[offset],
                        currentVertices[offset + 1],
                        currentVertices[offset + 2]
                    );
                    Vector3f newPos = new Vector3f(
                        positions[offset],
                        positions[offset + 1],
                        positions[offset + 2]
                    );

                    // Skip if position unchanged
                    if (oldPos.equals(newPos, 0.0001f)) {
                        continue;
                    }

                    // Find ALL vertices at the old position and update them
                    java.util.List<Integer> verticesAtPos = findMeshVerticesAtPosition(oldPos, 0.001f);
                    for (int vertexIndex : verticesAtPos) {
                        int vOffset = vertexIndex * 3;
                        if (vOffset + 2 < currentVertices.length) {
                            currentVertices[vOffset] = newPos.x;
                            currentVertices[vOffset + 1] = newPos.y;
                            currentVertices[vOffset + 2] = newPos.z;
                        }
                    }
                }
            } else {
                // Pre-subdivision or exact match: direct copy
                System.arraycopy(positions, 0, currentVertices, 0, updateLength);
            }

            // Build interleaved data and upload to GPU
            float[] interleavedData = buildInterleavedData();
            updateVBO(interleavedData);

            logger.trace("Updated {} of {} vertex positions", updateLength / 3, currentVertices.length / 3);

        } catch (Exception e) {
            logger.error("Error updating vertex positions", e);
        }
    }

    /**
     * Get vertex position by global index.
     *
     * @param globalIndex The global vertex index
     * @return The vertex position, or null if invalid
     */
    public Vector3f getVertexPosition(int globalIndex) {
        if (currentVertices == null || globalIndex < 0) {
            return null;
        }

        int offset = globalIndex * 3;
        if (offset + 2 >= currentVertices.length) {
            return null;
        }

        return new Vector3f(
                currentVertices[offset],
                currentVertices[offset + 1],
                currentVertices[offset + 2]
        );
    }

    /**
     * Get total vertex count across all parts.
     *
     * @return Total vertex count
     */
    public int getTotalVertexCount() {
        return currentVertices != null ? currentVertices.length / 3 : 0;
    }

    /**
     * Find if a triangle contains a specific edge.
     * @return Edge position (0, 1, or 2) if found, -1 if not found
     */
    private int findEdgeInTriangle(int i0, int i1, int i2, int e1, int e2) {
        // Check edge i0-i1
        if ((i0 == e1 && i1 == e2) || (i0 == e2 && i1 == e1)) {
            return 0;
        }
        // Check edge i1-i2
        if ((i1 == e1 && i2 == e2) || (i1 == e2 && i2 == e1)) {
            return 1;
        }
        // Check edge i2-i0
        if ((i2 == e1 && i0 == e2) || (i2 == e2 && i0 == e1)) {
            return 2;
        }
        return -1;
    }

    /**
     * Find all mesh vertex indices at a given position.
     * Used to map unique vertex positions to mesh vertex indices.
     *
     * @param position The position to search for
     * @param epsilon Tolerance for position matching
     * @return List of mesh vertex indices at that position
     */
    public java.util.List<Integer> findMeshVerticesAtPosition(Vector3f position, float epsilon) {
        java.util.List<Integer> result = new java.util.ArrayList<>();
        if (currentVertices == null || position == null) {
            return result;
        }

        int count = currentVertices.length / 3;
        for (int i = 0; i < count; i++) {
            float dx = currentVertices[i * 3] - position.x;
            float dy = currentVertices[i * 3 + 1] - position.y;
            float dz = currentVertices[i * 3 + 2] - position.z;
            float distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < epsilon * epsilon) {
                result.add(i);
            }
        }
        return result;
    }

    /**
     * Apply edge subdivision using endpoint positions instead of indices.
     * Finds ALL mesh vertex pairs at the endpoint positions and splits ALL triangles
     * that use any of these edge pairs. This handles edges shared by multiple faces.
     *
     * @param midpointPosition Position of the new midpoint vertex
     * @param endpoint1 Position of first edge endpoint
     * @param endpoint2 Position of second edge endpoint
     * @return Index of the new vertex, or -1 if failed
     */
    public int applyEdgeSubdivisionByPosition(Vector3f midpointPosition, Vector3f endpoint1, Vector3f endpoint2) {
        if (!initialized || midpointPosition == null || endpoint1 == null || endpoint2 == null) {
            logger.warn("Cannot apply subdivision: invalid parameters");
            return -1;
        }

        // Use larger epsilon to handle floating-point drift between systems
        float epsilon = 0.01f;

        logger.info("=== SUBDIVISION DEBUG ===");
        logger.info("Looking for endpoints: ({},{},{}) and ({},{},{})",
            endpoint1.x, endpoint1.y, endpoint1.z, endpoint2.x, endpoint2.y, endpoint2.z);

        // Print first 8 mesh vertex positions for comparison
        if (currentVertices != null && currentVertices.length >= 24) {
            logger.info("GenericModelRenderer first 8 vertices:");
            for (int i = 0; i < 8 && i * 3 + 2 < currentVertices.length; i++) {
                logger.info("  v{}: ({}, {}, {})", i,
                    currentVertices[i * 3], currentVertices[i * 3 + 1], currentVertices[i * 3 + 2]);
            }
        }

        // Find ALL mesh vertices at endpoint positions
        java.util.List<Integer> vertices1 = findMeshVerticesAtPosition(endpoint1, epsilon);
        java.util.List<Integer> vertices2 = findMeshVerticesAtPosition(endpoint2, epsilon);

        logger.debug("Found {} vertices at endpoint1, {} vertices at endpoint2",
            vertices1.size(), vertices2.size());

        if (vertices1.isEmpty() || vertices2.isEmpty()) {
            logger.warn("Cannot apply subdivision: edge endpoints not found in mesh. " +
                "endpoint1 found: {}, endpoint2 found: {}", vertices1.size(), vertices2.size());
            // Log first few mesh vertices for debugging
            if (currentVertices != null && currentVertices.length >= 9) {
                logger.warn("First 3 mesh vertices: ({},{},{}), ({},{},{}), ({},{},{})",
                    currentVertices[0], currentVertices[1], currentVertices[2],
                    currentVertices[3], currentVertices[4], currentVertices[5],
                    currentVertices[6], currentVertices[7], currentVertices[8]);
            }
            return -1;
        }

        // Collect ALL valid mesh edge pairs (same geometric edge on different faces)
        java.util.List<int[]> validEdgePairs = new java.util.ArrayList<>();
        for (int v1 : vertices1) {
            for (int v2 : vertices2) {
                if (isEdgeInMesh(v1, v2)) {
                    validEdgePairs.add(new int[]{v1, v2});
                    logger.debug("Found valid edge pair: ({}, {})", v1, v2);
                }
            }
        }

        if (validEdgePairs.isEmpty()) {
            logger.warn("Cannot apply subdivision: no valid edge found in mesh. " +
                "vertices1: {}, vertices2: {}", vertices1, vertices2);
            return -1;
        }

        logger.debug("Found {} mesh edge pairs for geometric edge (expect 2 for cube)", validEdgePairs.size());

        // For cube net textures, each face has different UVs for the same geometric edge.
        // We need to add a SEPARATE vertex (same position, different UV) for each triangle
        // being split, so that each face maintains its own UV mapping.

        // Step 1: First pass - count how many triangles will be split
        int triangleCount = currentIndices.length / 3;
        int trianglesToSplit = 0;

        for (int t = 0; t < triangleCount; t++) {
            int i0 = currentIndices[t * 3];
            int i1 = currentIndices[t * 3 + 1];
            int i2 = currentIndices[t * 3 + 2];

            for (int[] pair : validEdgePairs) {
                if (findEdgeInTriangle(i0, i1, i2, pair[0], pair[1]) >= 0) {
                    trianglesToSplit++;
                    break;
                }
            }
        }

        if (trianglesToSplit == 0) {
            logger.warn("No triangles found to split");
            return -1;
        }

        // Step 2: Expand vertex and texCoord arrays for new midpoint vertices
        // Each split triangle gets its OWN midpoint vertex (same position, unique UV)
        int firstNewVertexIndex = vertexCount;

        int newVerticesLength = (vertexCount + trianglesToSplit) * 3;
        float[] newVertices = new float[newVerticesLength];
        if (currentVertices != null) {
            System.arraycopy(currentVertices, 0, newVertices, 0, currentVertices.length);
        }

        int newTexCoordsLength = (vertexCount + trianglesToSplit) * 2;
        float[] newTexCoords = new float[newTexCoordsLength];
        if (currentTexCoords != null) {
            System.arraycopy(currentTexCoords, 0, newTexCoords, 0, currentTexCoords.length);
        }

        // Step 3: Split triangles, adding a new vertex for each
        java.util.List<Integer> newIndices = new java.util.ArrayList<>();
        int splitCount = 0;
        int currentNewVertex = firstNewVertexIndex;

        for (int t = 0; t < triangleCount; t++) {
            int i0 = currentIndices[t * 3];
            int i1 = currentIndices[t * 3 + 1];
            int i2 = currentIndices[t * 3 + 2];

            // Check if this triangle contains ANY of the edge pairs
            int edgePos = -1;
            int matchedE1 = -1, matchedE2 = -1;

            for (int[] pair : validEdgePairs) {
                edgePos = findEdgeInTriangle(i0, i1, i2, pair[0], pair[1]);
                if (edgePos >= 0) {
                    matchedE1 = pair[0];
                    matchedE2 = pair[1];
                    break;
                }
            }

            if (edgePos >= 0) {
                // This triangle contains one of the edges - split it
                int oppositeVertex;
                int e1, e2;

                switch (edgePos) {
                    case 0: // Edge i0-i1
                        oppositeVertex = i2;
                        e1 = i0;
                        e2 = i1;
                        break;
                    case 1: // Edge i1-i2
                        oppositeVertex = i0;
                        e1 = i1;
                        e2 = i2;
                        break;
                    case 2: // Edge i2-i0
                        oppositeVertex = i1;
                        e1 = i2;
                        e2 = i0;
                        break;
                    default:
                        newIndices.add(i0);
                        newIndices.add(i1);
                        newIndices.add(i2);
                        continue;
                }

                // Add new vertex at midpoint position
                newVertices[currentNewVertex * 3] = midpointPosition.x;
                newVertices[currentNewVertex * 3 + 1] = midpointPosition.y;
                newVertices[currentNewVertex * 3 + 2] = midpointPosition.z;

                // Interpolate UV from THIS triangle's edge vertices (not first pair)
                // This ensures each face gets correct UV interpolation for cube net textures
                float u1 = 0, v1 = 0, u2 = 0, v2 = 0;
                if (currentTexCoords != null && e1 * 2 + 1 < currentTexCoords.length) {
                    u1 = currentTexCoords[e1 * 2];
                    v1 = currentTexCoords[e1 * 2 + 1];
                }
                if (currentTexCoords != null && e2 * 2 + 1 < currentTexCoords.length) {
                    u2 = currentTexCoords[e2 * 2];
                    v2 = currentTexCoords[e2 * 2 + 1];
                }
                newTexCoords[currentNewVertex * 2] = (u1 + u2) / 2.0f;
                newTexCoords[currentNewVertex * 2 + 1] = (v1 + v2) / 2.0f;

                // Create 2 new triangles using this triangle's midpoint vertex
                newIndices.add(e1);
                newIndices.add(currentNewVertex);
                newIndices.add(oppositeVertex);

                newIndices.add(currentNewVertex);
                newIndices.add(e2);
                newIndices.add(oppositeVertex);

                splitCount++;
                logger.debug("Split triangle {} ({},{},{}) on edge ({},{}) -> 2 triangles, new vertex {} UV=({},{})",
                    t, i0, i1, i2, e1, e2, currentNewVertex,
                    newTexCoords[currentNewVertex * 2], newTexCoords[currentNewVertex * 2 + 1]);

                currentNewVertex++;
            } else {
                // Keep original triangle
                newIndices.add(i0);
                newIndices.add(i1);
                newIndices.add(i2);
            }
        }

        // Update arrays
        currentVertices = newVertices;
        currentTexCoords = newTexCoords;
        vertexCount += trianglesToSplit;

        // Step 4: Update indices array
        currentIndices = newIndices.stream().mapToInt(Integer::intValue).toArray();
        indexCount = currentIndices.length;

        // Step 5: Rebuild GPU buffers
        float[] interleavedData = buildInterleavedData();
        updateVBO(interleavedData);
        updateEBO(currentIndices);

        logger.debug("Applied subdivision: added {} vertices (first: {}), split {} triangles, indices {} -> {}",
            trianglesToSplit, firstNewVertexIndex, splitCount, triangleCount * 3, indexCount);

        return firstNewVertexIndex;
    }

    /**
     * Check if two vertices form an edge in any triangle.
     */
    private boolean isEdgeInMesh(int v1, int v2) {
        if (currentIndices == null) {
            return false;
        }

        int triangleCount = currentIndices.length / 3;
        for (int t = 0; t < triangleCount; t++) {
            int i0 = currentIndices[t * 3];
            int i1 = currentIndices[t * 3 + 1];
            int i2 = currentIndices[t * 3 + 2];

            if (findEdgeInTriangle(i0, i1, i2, v1, v2) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Set the texture for rendering.
     *
     * @param textureId OpenGL texture ID
     */
    public void setTexture(int textureId) {
        this.textureId = textureId;
        this.useTexture = textureId > 0;
    }

    /**
     * Rebuild geometry from current parts.
     * Call after adding/removing parts or updating part data.
     */
    private void rebuildGeometry() {
        if (parts.isEmpty()) {
            currentVertices = null;
            currentTexCoords = null;
            currentIndices = null;
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
        currentVertices = new float[totalVertices * 3];
        currentTexCoords = new float[totalVertices * 2];
        currentIndices = totalIndices > 0 ? new int[totalIndices] : null;

        // Copy data from parts
        int vertexOffset = 0;
        int indexOffset = 0;
        int baseVertex = 0;

        for (ModelPart part : parts) {
            // Copy vertices
            if (part.vertices() != null) {
                System.arraycopy(part.vertices(), 0, currentVertices, vertexOffset * 3, part.vertices().length);
            }

            // Copy tex coords
            if (part.texCoords() != null) {
                System.arraycopy(part.texCoords(), 0, currentTexCoords, vertexOffset * 2, part.texCoords().length);
            }

            // Copy indices (offset by base vertex)
            if (part.indices() != null && currentIndices != null) {
                for (int i = 0; i < part.indices().length; i++) {
                    currentIndices[indexOffset + i] = part.indices()[i] + baseVertex;
                }
                indexOffset += part.indices().length;
            }

            baseVertex += part.getVertexCount();
            vertexOffset += part.getVertexCount();
        }

        vertexCount = totalVertices;
        indexCount = totalIndices;

        // Update GPU buffers if initialized
        if (initialized) {
            float[] interleavedData = buildInterleavedData();
            updateVBO(interleavedData);
            if (currentIndices != null) {
                updateEBO(currentIndices);
            }
        }

        logger.debug("Rebuilt geometry: {} vertices, {} indices", vertexCount, indexCount);
    }

    /**
     * Build interleaved vertex data (position + texCoord).
     */
    private float[] buildInterleavedData() {
        if (currentVertices == null) {
            return new float[0];
        }

        int count = currentVertices.length / 3;
        float[] interleaved = new float[count * FLOATS_PER_VERTEX];

        for (int i = 0; i < count; i++) {
            int srcPos = i * 3;
            int srcTex = i * 2;
            int dst = i * FLOATS_PER_VERTEX;

            // Position
            interleaved[dst] = currentVertices[srcPos];
            interleaved[dst + 1] = currentVertices[srcPos + 1];
            interleaved[dst + 2] = currentVertices[srcPos + 2];

            // TexCoord
            if (currentTexCoords != null && srcTex + 1 < currentTexCoords.length) {
                interleaved[dst + 3] = currentTexCoords[srcTex];
                interleaved[dst + 4] = currentTexCoords[srcTex + 1];
            } else {
                interleaved[dst + 3] = 0.0f;
                interleaved[dst + 4] = 0.0f;
            }
        }

        return interleaved;
    }

    @Override
    protected GeometryData createGeometry() {
        float[] interleaved = buildInterleavedData();
        if (interleaved.length == 0) {
            // Return minimal valid geometry for empty state
            return GeometryData.nonIndexed(new float[0], 0, STRIDE);
        }

        if (currentIndices != null && currentIndices.length > 0) {
            return GeometryData.indexed(interleaved, currentIndices, vertexCount, STRIDE);
        } else {
            return GeometryData.nonIndexed(interleaved, vertexCount, STRIDE);
        }
    }

    @Override
    protected void configureVertexAttributes() {
        // Position attribute (location = 0): 3 floats
        glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE, 0);
        glEnableVertexAttribArray(0);

        // TexCoord attribute (location = 1): 2 floats
        glVertexAttribPointer(1, 2, GL_FLOAT, false, STRIDE, 3 * Float.BYTES);
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
        // Set model matrix for per-part transforms
        shader.setMat4("uModelMatrix", modelMatrix);
    }
}
