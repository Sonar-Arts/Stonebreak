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

    // Triangle-to-face mapping (tracks which original face each triangle belongs to)
    // For a cube: 12 triangles total, 2 per face, so face IDs are 0-5
    // This mapping is preserved and updated during subdivision
    private int[] triangleToOriginalFaceId;

    // === UNIQUE VERTEX MAPPING (Phase 1 of renderer refactoring) ===
    // Maps between mesh vertices (24 for cube) and unique geometric positions (8 for cube)
    // This replaces position-based matching with index-based lookup

    // For each unique vertex, stores ONE representative mesh index
    // uniqueVertexIndices[uniqueIdx] -> meshIdx (the first mesh vertex at that position)
    private int[] uniqueVertexIndices;

    // For each mesh vertex, stores which unique vertex it belongs to
    // meshToUniqueMapping[meshIdx] -> uniqueIdx
    private int[] meshToUniqueMapping;

    // For each unique vertex, stores ALL mesh indices at that position
    // uniqueToMeshIndices.get(uniqueIdx) -> int[] of all mesh indices
    private List<int[]> uniqueToMeshIndices;

    // Number of unique geometric positions
    private int uniqueVertexCount;

    // === CHANGE NOTIFICATION SYSTEM (Phase 2 of renderer refactoring) ===
    // Observers receive notifications when vertex positions change or geometry is rebuilt
    private final List<MeshChangeListener> listeners = new ArrayList<>();

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
        triangleToOriginalFaceId = null;
        uniqueVertexIndices = null;
        meshToUniqueMapping = null;
        uniqueToMeshIndices = null;
        uniqueVertexCount = 0;
        vertexCount = 0;
        indexCount = 0;
    }

    /**
     * Update a vertex position by its global index.
     * Also updates ALL other mesh vertices at the same geometric position.
     * This is critical after subdivision where multiple mesh vertices share positions.
     * Notifies all registered listeners of the change.
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

        // Get unique index for this mesh vertex (use index-based lookup if available)
        int uniqueIndex = getUniqueIndexForMeshVertex(globalIndex);
        int[] affectedMeshIndices;

        if (uniqueIndex >= 0) {
            // Use index-based mapping (preferred)
            affectedMeshIndices = getMeshIndicesForUniqueVertex(uniqueIndex);
        } else {
            // Fallback to position-based (during initialization or edge cases)
            Vector3f oldPosition = new Vector3f(
                currentVertices[offset],
                currentVertices[offset + 1],
                currentVertices[offset + 2]
            );
            java.util.List<Integer> verticesList = findMeshVerticesAtPosition(oldPosition, 0.001f);
            affectedMeshIndices = verticesList.stream().mapToInt(Integer::intValue).toArray();
        }

        // Update ALL vertices at this position
        for (int vertexIndex : affectedMeshIndices) {
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

        // Notify listeners of the change
        if (uniqueIndex >= 0 && !listeners.isEmpty()) {
            notifyVertexPositionChanged(uniqueIndex, position, affectedMeshIndices);
        }

        logger.trace("Updated {} mesh vertices for unique vertex {} to ({}, {}, {})",
            affectedMeshIndices.length, uniqueIndex, position.x, position.y, position.z);
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
     * Get all current mesh vertex positions.
     * Returns a COPY to prevent external modification.
     * Used for synchronizing MeshManager with GenericModelRenderer after subdivision.
     *
     * @return Copy of current vertex positions array, or null if none
     */
    public float[] getAllMeshVertexPositions() {
        if (currentVertices == null) {
            return null;
        }
        return currentVertices.clone();
    }

    /**
     * Get the current triangle count.
     * Each triangle has 3 indices in the currentIndices array.
     *
     * @return Number of triangles, or 0 if no indices
     */
    public int getTriangleCount() {
        return currentIndices != null ? currentIndices.length / 3 : 0;
    }

    /**
     * Get the current triangle indices.
     * Returns a COPY to prevent external modification.
     * Used for synchronizing face overlay data after subdivision.
     *
     * @return Copy of current indices array, or null if none
     */
    public int[] getTriangleIndices() {
        if (currentIndices == null) {
            return null;
        }
        return currentIndices.clone();
    }

    /**
     * Get the vertex positions for a specific triangle.
     * Used for extracting triangle geometry for face overlays.
     *
     * @param triangleIndex The triangle index (0-based)
     * @return Array of 3 Vector3f positions [v0, v1, v2], or null if invalid
     */
    public Vector3f[] getTriangleVertices(int triangleIndex) {
        if (currentIndices == null || currentVertices == null) {
            return null;
        }

        int indexOffset = triangleIndex * 3;
        if (indexOffset + 2 >= currentIndices.length) {
            return null;
        }

        int i0 = currentIndices[indexOffset];
        int i1 = currentIndices[indexOffset + 1];
        int i2 = currentIndices[indexOffset + 2];

        Vector3f[] result = new Vector3f[3];
        result[0] = getVertexPosition(i0);
        result[1] = getVertexPosition(i1);
        result[2] = getVertexPosition(i2);

        if (result[0] == null || result[1] == null || result[2] == null) {
            return null;
        }

        return result;
    }

    /**
     * Get the original face ID for a given triangle index.
     * Used for face overlay grouping after subdivision.
     *
     * @param triangleIndex The triangle index (0-based)
     * @return The original face ID (0-5 for cube), or -1 if invalid
     */
    public int getOriginalFaceIdForTriangle(int triangleIndex) {
        if (triangleToOriginalFaceId == null || triangleIndex < 0 ||
            triangleIndex >= triangleToOriginalFaceId.length) {
            return -1;
        }
        return triangleToOriginalFaceId[triangleIndex];
    }

    /**
     * Get all triangle indices that belong to a specific original face.
     * Used for rendering grouped face overlays after subdivision.
     *
     * @param originalFaceId The original face ID (0-5 for cube)
     * @return Array of triangle indices belonging to this face, or empty array if none
     */
    public int[] getAllTrianglesForOriginalFace(int originalFaceId) {
        if (triangleToOriginalFaceId == null || originalFaceId < 0) {
            return new int[0];
        }

        // Count triangles for this face
        int count = 0;
        for (int faceId : triangleToOriginalFaceId) {
            if (faceId == originalFaceId) {
                count++;
            }
        }

        // Collect triangle indices
        int[] result = new int[count];
        int idx = 0;
        for (int t = 0; t < triangleToOriginalFaceId.length; t++) {
            if (triangleToOriginalFaceId[t] == originalFaceId) {
                result[idx++] = t;
            }
        }

        return result;
    }

    /**
     * Get the number of original faces (before subdivision).
     * For a cube, this is always 6.
     *
     * @return The number of original faces
     */
    public int getOriginalFaceCount() {
        if (triangleToOriginalFaceId == null || triangleToOriginalFaceId.length == 0) {
            return 0;
        }

        // Find the maximum face ID + 1
        int maxFaceId = 0;
        for (int faceId : triangleToOriginalFaceId) {
            if (faceId > maxFaceId) {
                maxFaceId = faceId;
            }
        }
        return maxFaceId + 1;
    }

    /**
     * Check if triangle-to-face mapping is available.
     *
     * @return true if mapping is available
     */
    public boolean hasTriangleToFaceMapping() {
        return triangleToOriginalFaceId != null && triangleToOriginalFaceId.length > 0;
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

    // =========================================================================
    // UNIQUE VERTEX MAPPING - Index-based lookup (replaces position matching)
    // =========================================================================

    /**
     * Build the unique vertex mapping from current mesh vertices.
     * Groups mesh vertices by position to establish unique-to-mesh relationships.
     * Called automatically when geometry is loaded or rebuilt.
     *
     * For a cube: 24 mesh vertices → 8 unique positions (3 mesh vertices per corner)
     */
    private void buildUniqueVertexMapping() {
        if (currentVertices == null || currentVertices.length == 0) {
            uniqueVertexIndices = null;
            meshToUniqueMapping = null;
            uniqueToMeshIndices = null;
            uniqueVertexCount = 0;
            return;
        }

        int meshVertexCount = currentVertices.length / 3;
        meshToUniqueMapping = new int[meshVertexCount];
        java.util.Arrays.fill(meshToUniqueMapping, -1);

        // Temporary list to collect unique positions and their mesh indices
        List<List<Integer>> uniqueGroups = new ArrayList<>();
        List<Integer> representativeIndices = new ArrayList<>();

        float epsilon = 0.0001f;  // Tight tolerance for same-system matching

        for (int meshIdx = 0; meshIdx < meshVertexCount; meshIdx++) {
            float x = currentVertices[meshIdx * 3];
            float y = currentVertices[meshIdx * 3 + 1];
            float z = currentVertices[meshIdx * 3 + 2];

            // Check if this position matches any existing unique vertex
            int matchedUnique = -1;
            for (int u = 0; u < uniqueGroups.size(); u++) {
                int repIdx = representativeIndices.get(u);
                float rx = currentVertices[repIdx * 3];
                float ry = currentVertices[repIdx * 3 + 1];
                float rz = currentVertices[repIdx * 3 + 2];

                float dx = x - rx;
                float dy = y - ry;
                float dz = z - rz;
                if (dx * dx + dy * dy + dz * dz < epsilon * epsilon) {
                    matchedUnique = u;
                    break;
                }
            }

            if (matchedUnique >= 0) {
                // Add to existing unique group
                uniqueGroups.get(matchedUnique).add(meshIdx);
                meshToUniqueMapping[meshIdx] = matchedUnique;
            } else {
                // Create new unique vertex
                int newUniqueIdx = uniqueGroups.size();
                List<Integer> newGroup = new ArrayList<>();
                newGroup.add(meshIdx);
                uniqueGroups.add(newGroup);
                representativeIndices.add(meshIdx);
                meshToUniqueMapping[meshIdx] = newUniqueIdx;
            }
        }

        // Convert to final arrays
        uniqueVertexCount = uniqueGroups.size();
        uniqueVertexIndices = new int[uniqueVertexCount];
        uniqueToMeshIndices = new ArrayList<>(uniqueVertexCount);

        for (int u = 0; u < uniqueVertexCount; u++) {
            uniqueVertexIndices[u] = representativeIndices.get(u);
            List<Integer> group = uniqueGroups.get(u);
            int[] meshIndices = group.stream().mapToInt(Integer::intValue).toArray();
            uniqueToMeshIndices.add(meshIndices);
        }

        logger.debug("Built unique vertex mapping: {} mesh vertices → {} unique positions",
            meshVertexCount, uniqueVertexCount);
    }

    /**
     * Get the number of unique geometric vertex positions.
     * For a standard cube, this is 8 (corners).
     *
     * @return Number of unique vertices
     */
    public int getUniqueVertexCount() {
        return uniqueVertexCount;
    }

    /**
     * Get the position of a unique vertex by index.
     * This is a derived value from the mesh vertices.
     *
     * @param uniqueIndex The unique vertex index (0 to uniqueVertexCount-1)
     * @return The vertex position, or null if invalid index
     */
    public Vector3f getUniqueVertexPosition(int uniqueIndex) {
        if (uniqueVertexIndices == null || uniqueIndex < 0 || uniqueIndex >= uniqueVertexCount) {
            return null;
        }
        int meshIdx = uniqueVertexIndices[uniqueIndex];
        return getVertexPosition(meshIdx);
    }

    /**
     * Get all mesh vertex indices that share a unique geometric position.
     * For a cube corner, this returns 3 mesh indices (one per adjacent face).
     *
     * @param uniqueIndex The unique vertex index
     * @return Array of mesh indices, or empty array if invalid
     */
    public int[] getMeshIndicesForUniqueVertex(int uniqueIndex) {
        if (uniqueToMeshIndices == null || uniqueIndex < 0 || uniqueIndex >= uniqueVertexCount) {
            return new int[0];
        }
        return uniqueToMeshIndices.get(uniqueIndex).clone();
    }

    /**
     * Get the unique vertex index for a given mesh vertex.
     * This is the inverse of getMeshIndicesForUniqueVertex.
     *
     * @param meshIndex The mesh vertex index
     * @return The unique vertex index, or -1 if invalid
     */
    public int getUniqueIndexForMeshVertex(int meshIndex) {
        if (meshToUniqueMapping == null || meshIndex < 0 || meshIndex >= meshToUniqueMapping.length) {
            return -1;
        }
        return meshToUniqueMapping[meshIndex];
    }

    /**
     * Get all unique vertex positions as an array.
     * Format: [x0, y0, z0, x1, y1, z1, ...]
     *
     * @return Array of unique vertex positions, or null if none
     */
    public float[] getAllUniqueVertexPositions() {
        if (uniqueVertexIndices == null || uniqueVertexCount == 0) {
            return null;
        }

        float[] positions = new float[uniqueVertexCount * 3];
        for (int u = 0; u < uniqueVertexCount; u++) {
            int meshIdx = uniqueVertexIndices[u];
            int srcOffset = meshIdx * 3;
            int dstOffset = u * 3;
            positions[dstOffset] = currentVertices[srcOffset];
            positions[dstOffset + 1] = currentVertices[srcOffset + 1];
            positions[dstOffset + 2] = currentVertices[srcOffset + 2];
        }
        return positions;
    }

    // =========================================================================
    // CHANGE NOTIFICATION SYSTEM - Observer pattern for mesh updates
    // =========================================================================

    /**
     * Add a listener to receive mesh change notifications.
     *
     * @param listener The listener to add
     */
    public void addMeshChangeListener(MeshChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            logger.debug("Added mesh change listener: {}", listener.getClass().getSimpleName());
        }
    }

    /**
     * Remove a listener from mesh change notifications.
     *
     * @param listener The listener to remove
     */
    public void removeMeshChangeListener(MeshChangeListener listener) {
        if (listener != null) {
            listeners.remove(listener);
            logger.debug("Removed mesh change listener: {}", listener.getClass().getSimpleName());
        }
    }

    /**
     * Notify all listeners that a vertex position has changed.
     *
     * @param uniqueIndex The unique vertex index
     * @param newPosition The new position
     * @param affectedMeshIndices All mesh indices that were updated
     */
    private void notifyVertexPositionChanged(int uniqueIndex, Vector3f newPosition, int[] affectedMeshIndices) {
        for (MeshChangeListener listener : listeners) {
            try {
                listener.onVertexPositionChanged(uniqueIndex, newPosition, affectedMeshIndices);
            } catch (Exception e) {
                logger.error("Error notifying listener {} of vertex change",
                    listener.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * Notify all listeners that geometry has been rebuilt.
     * Called after subdivision, UV mode change, or model loading.
     */
    private void notifyGeometryRebuilt() {
        for (MeshChangeListener listener : listeners) {
            try {
                listener.onGeometryRebuilt();
            } catch (Exception e) {
                logger.error("Error notifying listener {} of geometry rebuild",
                    listener.getClass().getSimpleName(), e);
            }
        }
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
        java.util.List<Integer> newTriangleToFaceId = new java.util.ArrayList<>();  // Track face IDs for new triangles
        int splitCount = 0;
        int currentNewVertex = firstNewVertexIndex;

        for (int t = 0; t < triangleCount; t++) {
            // Get the original face ID for this triangle (to preserve during split)
            int originalFaceId = (triangleToOriginalFaceId != null && t < triangleToOriginalFaceId.length)
                ? triangleToOriginalFaceId[t] : (t / 2);
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
                        newTriangleToFaceId.add(originalFaceId);
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
                // Both new triangles inherit the original face ID
                newIndices.add(e1);
                newIndices.add(currentNewVertex);
                newIndices.add(oppositeVertex);
                newTriangleToFaceId.add(originalFaceId);  // First new triangle inherits face ID

                newIndices.add(currentNewVertex);
                newIndices.add(e2);
                newIndices.add(oppositeVertex);
                newTriangleToFaceId.add(originalFaceId);  // Second new triangle inherits face ID

                splitCount++;
                logger.debug("Split triangle {} ({},{},{}) on edge ({},{}) -> 2 triangles, new vertex {} UV=({},{})",
                    t, i0, i1, i2, e1, e2, currentNewVertex,
                    newTexCoords[currentNewVertex * 2], newTexCoords[currentNewVertex * 2 + 1]);

                currentNewVertex++;
            } else {
                // Keep original triangle - preserve its face ID
                newIndices.add(i0);
                newIndices.add(i1);
                newIndices.add(i2);
                newTriangleToFaceId.add(originalFaceId);
            }
        }

        // Update arrays
        currentVertices = newVertices;
        currentTexCoords = newTexCoords;
        vertexCount += trianglesToSplit;

        // Step 4: Update indices array and face mapping
        currentIndices = newIndices.stream().mapToInt(Integer::intValue).toArray();
        indexCount = currentIndices.length;
        triangleToOriginalFaceId = newTriangleToFaceId.stream().mapToInt(Integer::intValue).toArray();

        logger.debug("Updated triangle-to-face mapping: {} triangles, preserving face IDs",
            triangleToOriginalFaceId.length);

        // Step 5: Rebuild GPU buffers
        float[] interleavedData = buildInterleavedData();
        updateVBO(interleavedData);
        updateEBO(currentIndices);

        // Step 6: Rebuild unique vertex mapping (new vertices added)
        buildUniqueVertexMapping();

        // Step 7: Notify listeners of geometry rebuild
        notifyGeometryRebuilt();

        logger.debug("Applied subdivision: added {} vertices (first: {}), split {} triangles, indices {} -> {}, unique: {}",
            trianglesToSplit, firstNewVertexIndex, splitCount, triangleCount * 3, indexCount, uniqueVertexCount);

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

        // Initialize triangle-to-face mapping
        // For a cube: 12 triangles total (6 faces × 2 triangles each)
        // Triangles 0-1 = face 0, triangles 2-3 = face 1, etc.
        if (currentIndices != null) {
            int triangleCount = currentIndices.length / 3;
            triangleToOriginalFaceId = new int[triangleCount];
            for (int t = 0; t < triangleCount; t++) {
                triangleToOriginalFaceId[t] = t / 2;  // 2 triangles per original face
            }
            logger.debug("Initialized triangle-to-face mapping: {} triangles, {} faces",
                triangleCount, (triangleCount + 1) / 2);
        } else {
            triangleToOriginalFaceId = null;
        }

        // Update GPU buffers if initialized
        if (initialized) {
            float[] interleavedData = buildInterleavedData();
            updateVBO(interleavedData);
            if (currentIndices != null) {
                updateEBO(currentIndices);
            }
        }

        // Build unique vertex mapping for index-based lookup
        buildUniqueVertexMapping();

        // Notify listeners of geometry rebuild
        notifyGeometryRebuilt();

        logger.debug("Rebuilt geometry: {} vertices, {} indices, {} unique positions",
            vertexCount, indexCount, uniqueVertexCount);
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
