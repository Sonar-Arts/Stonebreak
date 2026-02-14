package com.openmason.main.systems.rendering.model.gmr.subrenders.face;

import com.openmason.main.systems.rendering.model.MeshChangeListener;
import com.openmason.main.systems.rendering.model.gmr.topology.MeshTopology;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import com.openmason.main.systems.rendering.model.gmr.mesh.MeshManager;
import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders semi-transparent face overlays for interactive highlighting.
 *
 * <p>This renderer provides visual feedback for face selection and hover states
 * by drawing colored overlays on model faces. The renderer follows SOLID principles
 * with clear separation of concerns and delegates data operations to
 * {@link MeshManager}.
 *
 * <h2>Rendering Strategy</h2>
 * <p>Uses two-pass rendering for clean visual separation:
 * <ol>
 *   <li>Model renders first (solid, textured)</li>
 *   <li>Face overlays render second (semi-transparent, colored)</li>
 * </ol>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Hover detection with ray-triangle intersection</li>
 *   <li>Selection highlighting with distinct colors</li>
 *   <li>Transparent default state (KISS principle)</li>
 *   <li>Face-to-vertex mapping for precise updates</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * <p>Follows the same pattern as EdgeRenderer and VertexRenderer for consistency.
 * Uses OpenGL VBO with interleaved vertex data (position + color RGBA).
 *
 * @see MeshManager
 * @see FaceHoverDetector
 */
public class FaceRenderer implements MeshChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(FaceRenderer.class);

    // Layout constants
    private static final int COMPONENTS_PER_POSITION = 3; // x, y, z
    private static final float VERTEX_MATCH_EPSILON = 0.0001f;

    // OpenGL resources
    private int vao = 0;
    private int vbo = 0;
    private int faceCount = 0;
    private boolean initialized = false;

    // Rendering state
    private boolean enabled = false;

    // Overlay renderer (Single Responsibility)
    private final FaceOverlayRenderer overlayRenderer = new FaceOverlayRenderer();

    // Hover state
    private int hoveredFaceIndex = -1; // -1 means no face is hovered

    // Selection state - multi-selection only
    private Set<Integer> selectedFaceIndices = new HashSet<>();

    // Topology info from structured face extraction
    private int[] storedVerticesPerFace = null; // vertex count per face, or null for legacy quad format
    private int[] storedFaceOffsets = null; // float offset into topologyFacePositions per face, or null for legacy
    private float[] topologyFacePositions = null; // topology-aware positions from extractFaceData()

    // GenericModelRenderer integration (for subdivision support)
    private GenericModelRenderer genericModelRenderer = null;

    // Maps original face ID (0-5 for cube) to list of triangle indices in GenericModelRenderer
    private Map<Integer, List<Integer>> originalFaceToTriangles = new HashMap<>();

    // Triangle positions for hover detection (9 floats per triangle: 3 vertices x 3 coords)
    private float[] trianglePositions = null;
    private int triangleCount = 0;

    // Mesh management - delegate to MeshManager
    private final MeshManager meshManager = MeshManager.getInstance();


    /**
     * Initialize the face renderer with OpenGL resources.
     * Creates VAO and VBO, configures vertex attributes for interleaved data.
     *
     * <p>Vertex layout: [position(x,y,z), color(r,g,b,a)] = 7 floats per vertex
     *
     * @throws RuntimeException if initialization fails
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        try {
            // Generate VAO and VBO
            vao = glGenVertexArrays();
            vbo = glGenBuffers();

            // Bind VAO
            glBindVertexArray(vao);

            // Create empty VBO (will be populated when model is set)
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, 0, GL_DYNAMIC_DRAW);

            // Configure vertex attributes (interleaved: position + color with alpha)
            // Stride = FLOATS_PER_VERTEX (3 for position, 4 for color RGBA)
            int stride = FaceOverlayRenderer.FLOATS_PER_VERTEX * Float.BYTES;

            // Position attribute (location = 0)
            glVertexAttribPointer(0, COMPONENTS_PER_POSITION, GL_FLOAT, false, stride, 0);
            glEnableVertexAttribArray(0);

            // Color attribute (location = 1) - 4 components for RGBA
            glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, COMPONENTS_PER_POSITION * Float.BYTES);
            glEnableVertexAttribArray(1);

            // Unbind
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);

            initialized = true;
            logger.debug("FaceRenderer initialized");

        } catch (Exception e) {
            logger.error("Failed to initialize FaceRenderer", e);
            cleanup();
            throw new RuntimeException("FaceRenderer initialization failed", e);
        }
    }

    /**
     * Update face data from GenericModelRenderer (GMR is single source of truth).
     * This method gets face data directly from GMR instead of extracting from ModelDefinition.
     * GMR provides the authoritative mesh topology after any subdivisions or modifications.
     *
     * Topology-aware: uses structured face extraction to support any face vertex count.
     */
    public void updateFaceDataFromGMR() {
        if (!initialized) {
            logger.warn("FaceRenderer not initialized, cannot update face data");
            return;
        }

        if (genericModelRenderer == null) {
            logger.warn("GenericModelRenderer not set");
            faceCount = 0;
            topologyFacePositions = null;
            return;
        }

        try {
            // Extract topology-aware face data for rendering
            var faceData = genericModelRenderer.extractFaceData();
            if (faceData == null) {
                logger.warn("GenericModelRenderer returned null face data");
                return;
            }

            storedVerticesPerFace = faceData.verticesPerFace();
            storedFaceOffsets = faceData.faceOffsets();
            topologyFacePositions = faceData.positions();

            // Face count from topology (not derived from array division)
            faceCount = faceData.faceCount();

            // Delegate bulk VBO creation to MeshManager, using topology for uniform/mixed decision
            MeshTopology topology = genericModelRenderer.getTopology();
            if (topology != null && topology.isUniformTopology()) {
                meshManager.updateAllFaces(vbo, topologyFacePositions, faceCount,
                    topology.getUniformVerticesPerFace(), overlayRenderer.getDefaultFaceColor());
            } else if (topology != null) {
                int[] offsets = topology.computeFacePositionOffsets();
                meshManager.updateAllFacesMixed(vbo, topologyFacePositions, faceCount,
                    storedVerticesPerFace, offsets, overlayRenderer.getDefaultFaceColor());
            } else {
                // Fallback: assume uniform quad topology
                meshManager.updateAllFaces(vbo, topologyFacePositions, faceCount,
                    4, overlayRenderer.getDefaultFaceColor());
            }

            // Compute shape-blind VBO layout from topology for overlay rendering
            computeAndSetFaceVBOLayout(storedVerticesPerFace);

            logger.debug("Updated face data from GMR: {} faces", faceCount);

        } catch (Exception e) {
            logger.error("Error updating face data from GMR", e);
        }
    }

    /**
     * Compute per-face VBO layout from per-face vertex count array (topology-aware path).
     * Uses TriangulationPattern to determine VBO vertex count for each face.
     *
     * @param verticesPerFace array of corner counts per face from GMR topology
     */
    private void computeAndSetFaceVBOLayout(int[] verticesPerFace) {
        int[] offsets = new int[verticesPerFace.length];
        int[] counts = new int[verticesPerFace.length];
        int cumulativeOffset = 0;

        for (int i = 0; i < verticesPerFace.length; i++) {
            var pattern = com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations.TriangulationPattern.forNGon(verticesPerFace[i]);
            int vboVertexCount = pattern.getVBOVertexCount();
            offsets[i] = cumulativeOffset;
            counts[i] = vboVertexCount;
            cumulativeOffset += vboVertexCount;
        }

        overlayRenderer.setFaceVBOLayout(offsets, counts);
    }

    /**
     * Get all unique vertex indices for all triangles belonging to an original face.
     * Returns the mesh vertex indices from GenericModelRenderer that make up
     * all triangles of the specified original face. The returned indices can be
     * used to update vertex positions during face translation.
     *
     * @param originalFaceId the original face ID (0-5 for cube)
     * @return array of unique vertex indices, or null if invalid
     */
    public int[] getTriangleVertexIndicesForFace(int originalFaceId) {
        if (genericModelRenderer == null) {
            logger.warn("Cannot get triangle vertex indices: GenericModelRenderer not set");
            return null;
        }

        List<Integer> triangleList = originalFaceToTriangles.get(originalFaceId);
        if (triangleList == null || triangleList.isEmpty()) {
            logger.warn("No triangles found for original face {}", originalFaceId);
            return null;
        }

        int[] triangleIndices = genericModelRenderer.getTriangleIndices();
        if (triangleIndices == null) {
            logger.warn("Cannot get triangle vertex indices: no triangle indices available");
            return null;
        }

        // Collect all unique vertex indices from all triangles of this face
        Set<Integer> uniqueVertexIndices = new LinkedHashSet<>();
        for (int triIndex : triangleList) {
            int baseIndex = triIndex * 3;
            if (baseIndex + 2 < triangleIndices.length) {
                uniqueVertexIndices.add(triangleIndices[baseIndex]);
                uniqueVertexIndices.add(triangleIndices[baseIndex + 1]);
                uniqueVertexIndices.add(triangleIndices[baseIndex + 2]);
            }
        }

        int[] result = uniqueVertexIndices.stream().mapToInt(Integer::intValue).toArray();
        logger.debug("Face {} has {} triangles with {} unique vertices",
            originalFaceId, triangleList.size(), result.length);
        return result;
    }

    /**
     * Get the vertex positions for all triangles belonging to an original face.
     * Used in triangle mode for face selection and translation.
     *
     * @param originalFaceId the original face ID (0-5 for cube)
     * @return array of vertex positions, or null if invalid
     */
    public Vector3f[] getTriangleVertexPositionsForFace(int originalFaceId) {
        int[] indices = getTriangleVertexIndicesForFace(originalFaceId);
        if (indices == null || genericModelRenderer == null) {
            return null;
        }

        Vector3f[] positions = new Vector3f[indices.length];
        for (int i = 0; i < indices.length; i++) {
            positions[i] = genericModelRenderer.getVertexPosition(indices[i]);
            if (positions[i] == null) {
                logger.warn("Could not get position for vertex {}", indices[i]);
                return null;
            }
        }
        return positions;
    }

    /**
     * Get the vertices of a face using topology-aware data.
     * Returns N vertices using stored per-face offsets.
     *
     * @param faceIndex the face index
     * @return array of vertices, or null if invalid
     */
    public Vector3f[] getFaceVertices(int faceIndex) {
        if (faceIndex < 0 || faceIndex >= faceCount) {
            return null;
        }

        if (storedVerticesPerFace != null && storedFaceOffsets != null
                   && topologyFacePositions != null && faceIndex < storedVerticesPerFace.length) {
            int vertCount = storedVerticesPerFace[faceIndex];
            int floatOffset = storedFaceOffsets[faceIndex];
            Vector3f[] vertices = new Vector3f[vertCount];
            for (int i = 0; i < vertCount; i++) {
                int base = floatOffset + i * COMPONENTS_PER_POSITION;
                vertices[i] = new Vector3f(
                    topologyFacePositions[base],
                    topologyFacePositions[base + 1],
                    topologyFacePositions[base + 2]
                );
            }
            return vertices;
        }
        return null;
    }

    /**
     * Handle mouse movement for face hover detection.
     *
     * @param mouseX Mouse X coordinate in viewport space
     * @param mouseY Mouse Y coordinate in viewport space
     * @param viewMatrix Camera view matrix
     * @param projectionMatrix Camera projection matrix
     * @param modelMatrix Model transformation matrix
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     */
    public void handleMouseMove(float mouseX, float mouseY,
                               Matrix4f viewMatrix, Matrix4f projectionMatrix,
                               Matrix4f modelMatrix,
                               int viewportWidth, int viewportHeight) {
        if (!initialized || !enabled) {
            return;
        }

        if (faceCount == 0) {
            return;
        }

        int newHoveredFace;

        if (trianglePositions != null && triangleCount > 0) {
            // Hit test all triangles, return original face ID
            int hitTriangle = FaceHoverDetector.detectHoveredTriangle(
                mouseX, mouseY,
                viewportWidth, viewportHeight,
                viewMatrix, projectionMatrix, modelMatrix,
                trianglePositions,
                triangleCount
            );

            if (hitTriangle >= 0 && genericModelRenderer != null) {
                // Map triangle to original face
                newHoveredFace = genericModelRenderer.getOriginalFaceIdForTriangle(hitTriangle);
            } else {
                newHoveredFace = -1;
            }
        } else {
            newHoveredFace = -1;
        }

        // Update hover state if changed
        if (newHoveredFace != hoveredFaceIndex) {
            int previousHover = hoveredFaceIndex;
            hoveredFaceIndex = newHoveredFace;

            logger.debug("Face hover changed: {} -> {} (total faces: {})",
                        previousHover, hoveredFaceIndex, faceCount);
        }
    }

    /**
     * Clear face hover state.
     */
    public void clearHover() {
        if (hoveredFaceIndex != -1) {
            logger.debug("Clearing face hover (was face {})", hoveredFaceIndex);
            hoveredFaceIndex = -1;
        }
    }

    /**
     * Render face overlays with semi-transparent highlighting.
     * KISS: Only render hovered/selected faces, skip default (transparent) faces.
     * Delegates to FaceOverlayRenderer for clean separation of concerns.
     *
     * @param shader The shader program to use
     * @param context The render context
     * @param modelMatrix The model transformation matrix
     */
    public void render(ShaderProgram shader, RenderContext context, Matrix4f modelMatrix) {
        if (!initialized) {
            logger.warn("FaceRenderer not initialized");
            return;
        }

        if (!enabled) {
            return;
        }

        // Delegate to overlay renderer (Single Responsibility Principle)
        // Pass the full selection set for multi-selection support
        overlayRenderer.render(vao, vbo, shader, context, modelMatrix,
                             hoveredFaceIndex, selectedFaceIndices, faceCount);
    }

    /**
     * Clean up OpenGL resources (RAII pattern).
     * Deletes VAO and VBO, clears all state data.
     *
     * <p>Should be called when the renderer is no longer needed
     * to prevent resource leaks.
     */
    public void cleanup() {
        // Unregister from model renderer
        if (genericModelRenderer != null) {
            genericModelRenderer.removeMeshChangeListener(this);
            genericModelRenderer = null;
        }

        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vbo != 0) {
            glDeleteBuffers(vbo);
            vbo = 0;
        }
        faceCount = 0;
        topologyFacePositions = null;
        storedVerticesPerFace = null;
        storedFaceOffsets = null;
        initialized = false;
        logger.debug("FaceRenderer cleaned up");
    }

    // =========================================================================
    // MESH CHANGE LISTENER IMPLEMENTATION - Index-based updates from GenericModelRenderer
    // =========================================================================

    /**
     * Called when a vertex position has been updated in GenericModelRenderer.
     * Updates face overlays that include the changed vertex.
     *
     * @param uniqueIndex The unique vertex index that changed
     * @param newPosition The new position of the vertex
     * @param affectedMeshIndices All mesh vertex indices that were updated
     */
    @Override
    public void onVertexPositionChanged(int uniqueIndex, Vector3f newPosition, int[] affectedMeshIndices) {
        if (!initialized) {
            return;
        }

        // Update trianglePositions for affected mesh vertices
        if (genericModelRenderer != null) {
            updateTrianglePositionsForMeshVertices(affectedMeshIndices, newPosition);
        }

        logger.trace("FaceRenderer received vertex update: unique {} -> ({}, {}, {})",
            uniqueIndex, newPosition.x, newPosition.y, newPosition.z);
    }

    /**
     * Called when the entire geometry has been rebuilt in GenericModelRenderer.
     * Triggers a full rebuild of face overlay data from the model.
     */
    @Override
    public void onGeometryRebuilt() {
        logger.debug("FaceRenderer received geometry rebuild notification");
        rebuildFromGenericModelRenderer();
    }

    /**
     * Update triangle positions for affected mesh vertices.
     * Used during incremental vertex position updates.
     */
    private void updateTrianglePositionsForMeshVertices(int[] affectedMeshIndices, Vector3f newPosition) {
        if (trianglePositions == null || genericModelRenderer == null) {
            return;
        }

        int[] triangleIndices = genericModelRenderer.getTriangleIndices();
        if (triangleIndices == null) {
            return;
        }

        // For each affected mesh vertex, update any triangle vertices that reference it
        for (int meshIdx : affectedMeshIndices) {
            for (int t = 0; t < triangleCount; t++) {
                int i0 = triangleIndices[t * 3];
                int i1 = triangleIndices[t * 3 + 1];
                int i2 = triangleIndices[t * 3 + 2];

                int offset = t * 9;

                if (i0 == meshIdx) {
                    trianglePositions[offset] = newPosition.x;
                    trianglePositions[offset + 1] = newPosition.y;
                    trianglePositions[offset + 2] = newPosition.z;
                }
                if (i1 == meshIdx) {
                    trianglePositions[offset + 3] = newPosition.x;
                    trianglePositions[offset + 4] = newPosition.y;
                    trianglePositions[offset + 5] = newPosition.z;
                }
                if (i2 == meshIdx) {
                    trianglePositions[offset + 6] = newPosition.x;
                    trianglePositions[offset + 7] = newPosition.y;
                    trianglePositions[offset + 8] = newPosition.z;
                }
            }
        }
    }

    // Getters and setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public int getHoveredFaceIndex() {
        return hoveredFaceIndex;
    }

    /**
     * Get the first selected face index, or -1 if none selected.
     * For multi-selection, use {@link #getSelectedFaceIndices()}.
     *
     * @return first selected face index, or -1
     */
    public int getSelectedFaceIndex() {
        return selectedFaceIndices.isEmpty() ? -1 : selectedFaceIndices.iterator().next();
    }

    public void setSelectedFace(int faceIndex) {
        if (faceIndex < -1 || faceIndex >= faceCount) {
            logger.warn("Invalid face index: {}, valid range is -1 to {}", faceIndex, faceCount - 1);
            return;
        }

        selectedFaceIndices.clear();
        if (faceIndex >= 0) {
            selectedFaceIndices.add(faceIndex);
        }

        if (faceIndex >= 0) {
            logger.debug("Selected face {}", faceIndex);
        } else {
            logger.debug("Cleared face selection");
        }
    }

    /**
     * Update the selection with a set of face indices (multi-selection).
     * @param indices Set of face indices to select
     */
    public void updateSelectionSet(Set<Integer> indices) {
        selectedFaceIndices.clear();
        if (indices != null) {
            for (Integer index : indices) {
                if (index >= 0 && index < faceCount) {
                    selectedFaceIndices.add(index);
                }
            }
        }
        logger.debug("Updated face selection set: {} faces", selectedFaceIndices.size());
    }

    /**
     * Get the set of selected face indices.
     * @return Copy of the selected face indices set
     */
    public Set<Integer> getSelectedFaceIndices() {
        return new HashSet<>(selectedFaceIndices);
    }

    /**
     * Check if a specific face is selected.
     * @param index Face index to check
     * @return true if the face is selected
     */
    public boolean isFaceSelected(int index) {
        return selectedFaceIndices.contains(index);
    }

    public void clearSelection() {
        selectedFaceIndices.clear();
    }

    public int getFaceCount() {
        return faceCount;
    }

    // ========================================
    // GenericModelRenderer Integration (for subdivision support)
    // ========================================

    /**
     * Set the GenericModelRenderer reference for face overlay vertex position access.
     * This enables correct face overlay rendering after edge subdivision operations
     * by allowing the FaceOverlayRenderer to use the same position-matching methods
     * as GenericModelRenderer.
     *
     * <p>This mirrors the approach used in EdgeRenderer for index-based updates
     * after subdivision. Also registers as a MeshChangeListener to receive
     * notifications when vertex positions change or geometry is rebuilt.
     *
     * @param renderer The GenericModelRenderer instance, or null to disconnect
     */
    public void setGenericModelRenderer(GenericModelRenderer renderer) {
        // Unregister from previous renderer
        if (this.genericModelRenderer != null) {
            this.genericModelRenderer.removeMeshChangeListener(this);
        }

        this.genericModelRenderer = renderer;

        // Register with new renderer and rebuild face data
        if (renderer != null) {
            renderer.addMeshChangeListener(this);
            // Rebuild face data from model (like VertexRenderer and EdgeRenderer do)
            rebuildFromGenericModelRenderer();
        }

        logger.debug("FaceRenderer {} GenericModelRenderer",
            renderer != null ? "connected to" : "disconnected from");
    }

    /**
     * Rebuild face overlay data from GenericModelRenderer's current triangles.
     * Call this after subdivision to sync face overlay geometry with the actual mesh.
     *
     * <p>This method preserves original face grouping (6 faces for cube) while
     * storing all triangles for each face. Face indices still refer to original
     * faces (0-5), not individual triangles.
     *
     * <p>Key behavior:
     * <ul>
     *   <li>Face count stays at original face count (6 for cube)</li>
     *   <li>Hovering any triangle of a face highlights the whole face</li>
     *   <li>All triangles of a face are rendered together when highlighted</li>
     * </ul>
     */
    public void rebuildFromGenericModelRenderer() {
        if (!initialized) {
            logger.warn("FaceRenderer not initialized, cannot rebuild face data");
            return;
        }

        if (genericModelRenderer == null) {
            logger.warn("GenericModelRenderer not set, cannot rebuild face data");
            return;
        }

        if (!genericModelRenderer.hasTriangleToFaceMapping()) {
            logger.warn("GenericModelRenderer has no triangle-to-face mapping");
            return;
        }

        triangleCount = genericModelRenderer.getTriangleCount();
        if (triangleCount == 0) {
            logger.warn("GenericModelRenderer has no triangles");
            faceCount = 0;
            trianglePositions = null;
            originalFaceToTriangles.clear();
            return;
        }

        try {
            // Get mesh data from GenericModelRenderer
            float[] meshVertices = genericModelRenderer.getAllMeshVertexPositions();
            int[] triangleIndices = genericModelRenderer.getTriangleIndices();
            int originalFaceCount = genericModelRenderer.getOriginalFaceCount();

            if (meshVertices == null || triangleIndices == null) {
                logger.warn("Cannot rebuild face data: mesh data is null");
                return;
            }

            // Build face-to-triangles mapping
            originalFaceToTriangles.clear();
            for (int t = 0; t < triangleCount; t++) {
                int originalFaceId = genericModelRenderer.getOriginalFaceIdForTriangle(t);
                originalFaceToTriangles
                    .computeIfAbsent(originalFaceId, k -> new ArrayList<>())
                    .add(t);
            }

            // Build triangle positions array for hover detection (9 floats per triangle)
            trianglePositions = new float[triangleCount * 9];
            for (int t = 0; t < triangleCount; t++) {
                int i0 = triangleIndices[t * 3];
                int i1 = triangleIndices[t * 3 + 1];
                int i2 = triangleIndices[t * 3 + 2];

                int offset = t * 9;
                trianglePositions[offset] = meshVertices[i0 * 3];
                trianglePositions[offset + 1] = meshVertices[i0 * 3 + 1];
                trianglePositions[offset + 2] = meshVertices[i0 * 3 + 2];
                trianglePositions[offset + 3] = meshVertices[i1 * 3];
                trianglePositions[offset + 4] = meshVertices[i1 * 3 + 1];
                trianglePositions[offset + 5] = meshVertices[i1 * 3 + 2];
                trianglePositions[offset + 6] = meshVertices[i2 * 3];
                trianglePositions[offset + 7] = meshVertices[i2 * 3 + 1];
                trianglePositions[offset + 8] = meshVertices[i2 * 3 + 2];
            }

            // Keep original face count (6 for cube), not triangle count
            faceCount = originalFaceCount;

            // Rebuild VBO with all triangles
            rebuildGroupedTriangleVBO(meshVertices, triangleIndices);

            // Pass data to overlay renderer
            overlayRenderer.setTriangleMode(true);
            overlayRenderer.setOriginalFaceToTriangles(originalFaceToTriangles);

            logger.debug("Rebuilt face data from GenericModelRenderer: {} original faces, {} triangles",
                faceCount, triangleCount);

            // Log face-to-triangle mapping for debugging
            for (Map.Entry<Integer, List<Integer>> entry : originalFaceToTriangles.entrySet()) {
                logger.debug("  Face {} has {} triangles: {}", entry.getKey(),
                    entry.getValue().size(), entry.getValue());
            }

        } catch (Exception e) {
            logger.error("Error rebuilding face data from GenericModelRenderer", e);
        }
    }

    /**
     * Rebuild VBO with all triangles for grouped face rendering.
     * VBO stores all triangles contiguously, overlay renderer uses face-to-triangle
     * mapping to render the correct triangles for each face.
     */
    private void rebuildGroupedTriangleVBO(float[] meshVertices, int[] triangleIndices) {
        if (triangleCount == 0) {
            return;
        }

        // VBO format: position (3 floats) + color (4 floats) = 7 floats per vertex
        // 3 vertices per triangle = 21 floats per triangle
        int floatsPerVertex = FaceOverlayRenderer.FLOATS_PER_VERTEX;
        int verticesPerTriangle = 3;
        int floatsPerTriangle = floatsPerVertex * verticesPerTriangle;

        float[] vboData = new float[triangleCount * floatsPerTriangle];
        Vector4f defaultColor = overlayRenderer.getDefaultFaceColor();

        for (int t = 0; t < triangleCount; t++) {
            int[] vertexMeshIndices = {
                triangleIndices[t * 3],
                triangleIndices[t * 3 + 1],
                triangleIndices[t * 3 + 2]
            };

            int vboOffset = t * floatsPerTriangle;

            for (int v = 0; v < verticesPerTriangle; v++) {
                int meshIdx = vertexMeshIndices[v];
                int vertOffset = vboOffset + v * floatsPerVertex;

                // Position
                vboData[vertOffset] = meshVertices[meshIdx * 3];
                vboData[vertOffset + 1] = meshVertices[meshIdx * 3 + 1];
                vboData[vertOffset + 2] = meshVertices[meshIdx * 3 + 2];

                // Color RGBA
                vboData[vertOffset + 3] = defaultColor.x;
                vboData[vertOffset + 4] = defaultColor.y;
                vboData[vertOffset + 5] = defaultColor.z;
                vboData[vertOffset + 6] = defaultColor.w;
            }
        }

        // Upload to VBO
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vboData, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        logger.debug("Rebuilt grouped triangle VBO: {} triangles, {} floats", triangleCount, vboData.length);
    }

    /**
     * Get the FaceOverlayRenderer instance.
     * Used for direct access to overlay rendering configuration.
     *
     * @return The FaceOverlayRenderer instance
     */
    public FaceOverlayRenderer getOverlayRenderer() {
        return overlayRenderer;
    }
}
