package com.openmason.main.systems.viewport.viewportRendering.face;

import com.openmason.main.systems.rendering.model.MeshChangeListener;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import com.openmason.main.systems.viewport.viewportRendering.mesh.MeshManager;
import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

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
 * @see MeshFaceExtractor
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
    private float[] facePositions = null; // Store positions for hit testing

    // Selection state
    private int selectedFaceIndex = -1; // -1 means no face is selected

    // Face-to-vertex mapping (FIX: prevents vertex unification bug)
    // Maps face index → array of 4 unique vertex indices [v0, v1, v2, v3]
    // For cube: 6 faces, each connecting 4 of the 8 unique vertices
    private Map<Integer, int[]> faceToVertexMapping = new HashMap<>();

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
            int stride = MeshManager.FLOATS_PER_VERTEX * Float.BYTES;

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
     * Update face data from model parts with transformation applied.
     *
     * <p>Extracts face geometry from model parts, applies transformation,
     * and uploads vertex data to GPU. Delegates to MeshManager
     * for DRY compliance and single responsibility.
     *
     * <p>This method works with any model type through polymorphic
     * geometry extraction via {@link IGeometryExtractor}.
     *
     * @param parts collection of model parts to extract faces from
     * @param transformMatrix transformation to apply to face positions
     */
    public void updateFaceData(Collection<ModelDefinition.ModelPart> parts, Matrix4f transformMatrix) {
        if (!initialized) {
            logger.warn("FaceRenderer not initialized, cannot update face data");
            return;
        }

        if (parts == null || parts.isEmpty()) {
            faceCount = 0;
            facePositions = null;
            faceToVertexMapping.clear();
            return;
        }

        try {
            // Extract faces using MeshManager (centralized mesh operations)
            facePositions = meshManager.extractFaceGeometry(parts, transformMatrix);

            // Face positions are [v0x,v0y,v0z, v1x,v1y,v1z, v2x,v2y,v2z, v3x,v3y,v3z, ...] (4 vertices per face)
            faceCount = facePositions.length / MeshManager.FLOATS_PER_FACE_POSITION;

            // Delegate bulk VBO creation to MeshManager (DRY + Single Responsibility)
            MeshManager.getInstance().updateAllFaces(vbo, facePositions, faceCount, overlayRenderer.getDefaultFaceColor());

            logger.debug("Updated face data: {} faces ({} floats)", faceCount, facePositions.length);

        } catch (Exception e) {
            logger.error("Error updating face data", e);
        }
    }

    /**
     * Build face-to-vertex mapping from unique vertex positions.
     * FIX: Creates mapping needed for index-based updates to prevent vertex unification.
     * Matches face corners to unique vertices using epsilon comparison.
     * Delegates to MeshManager for clean separation of concerns.
     *
     * <p><b>Note:</b> In triangle mode, this method preserves the existing mapping
     * from quad mode so that original corner vertices can still be accessed for
     * wireframe updates during face translation.
     *
     * @param uniqueVertexPositions Array of unique vertex positions [x0,y0,z0, x1,y1,z1, ...]
     */
    public void buildFaceToVertexMapping(float[] uniqueVertexPositions) {
        // In triangle mode, preserve existing mapping - it maps original face IDs
        // to original VertexRenderer indices, which is needed for wireframe updates
        if (usingTriangleMode) {
            logger.debug("Preserving existing face-to-vertex mapping in triangle mode");
            return; // Don't rebuild or clear - keep the mapping from quad mode
        }

        if (facePositions == null || faceCount == 0) {
            logger.warn("Cannot build face mapping: no face data");
            faceToVertexMapping.clear();
            return;
        }

        if (uniqueVertexPositions == null || uniqueVertexPositions.length < COMPONENTS_PER_POSITION) {
            logger.warn("Cannot build face mapping: invalid unique vertex data");
            faceToVertexMapping.clear();
            return;
        }

        // Delegate to MeshManager (Single Responsibility Principle)
        faceToVertexMapping = MeshManager.getInstance().buildFaceToVertexMapping(
            facePositions,
            faceCount,
            uniqueVertexPositions,
            VERTEX_MATCH_EPSILON
        );

        logger.debug("Built face-to-vertex mapping for {} faces", faceCount);
    }


    /**
     * Get the vertex indices for a specific face.
     * Delegates to MeshManager for clean separation of concerns.
     *
     * <p><b>Note:</b> This method only works in quad mode. In triangle mode,
     * use {@link #getTriangleVertexIndicesForFace(int)} instead.
     *
     * @param faceIndex the face index
     * @return array of 4 vertex indices [v0, v1, v2, v3], or null if invalid or in triangle mode
     */
    public int[] getFaceVertexIndices(int faceIndex) {
        // Not applicable in triangle mode
        if (usingTriangleMode) {
            logger.debug("getFaceVertexIndices not applicable in triangle mode - use getTriangleVertexIndicesForFace()");
            return null;
        }

        // Delegate to MeshManager (Single Responsibility Principle)
        return MeshManager.getInstance().getFaceVertexIndices(faceIndex, faceCount, faceToVertexMapping);
    }

    /**
     * Get all unique vertex indices for all triangles belonging to an original face.
     * Used in triangle mode (post-subdivision) for face translation.
     *
     * <p>Returns the mesh vertex indices from GenericModelRenderer that make up
     * all triangles of the specified original face. The returned indices can be
     * used to update vertex positions during face translation.
     *
     * @param originalFaceId the original face ID (0-5 for cube)
     * @return array of unique vertex indices, or null if invalid or not in triangle mode
     */
    public int[] getTriangleVertexIndicesForFace(int originalFaceId) {
        if (!usingTriangleMode) {
            logger.debug("getTriangleVertexIndicesForFace only applicable in triangle mode");
            return null;
        }

        if (genericModelRenderer == null) {
            logger.warn("Cannot get triangle vertex indices: GenericModelRenderer not set");
            return null;
        }

        java.util.List<Integer> triangleList = originalFaceToTriangles.get(originalFaceId);
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
        java.util.Set<Integer> uniqueVertexIndices = new java.util.LinkedHashSet<>();
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
     * Get the vertices of a face.
     * In quad mode, returns 4 vertices [v0, v1, v2, v3].
     * In triangle mode, returns 3 vertices [v0, v1, v2].
     *
     * @param faceIndex the face index
     * @return array of vertices, or null if invalid
     */
    public Vector3f[] getFaceVertices(int faceIndex) {
        if (facePositions == null || faceIndex < 0 || faceIndex >= faceCount) {
            return null;
        }

        if (usingTriangleMode) {
            // Triangle mode: 3 vertices per face (9 floats)
            int offset = faceIndex * 9;
            if (offset + 8 >= facePositions.length) {
                return null;
            }
            return new Vector3f[] {
                new Vector3f(facePositions[offset], facePositions[offset + 1], facePositions[offset + 2]),
                new Vector3f(facePositions[offset + 3], facePositions[offset + 4], facePositions[offset + 5]),
                new Vector3f(facePositions[offset + 6], facePositions[offset + 7], facePositions[offset + 8])
            };
        } else {
            // Quad mode: Delegate to MeshManager (Single Responsibility Principle)
            return MeshManager.getInstance().getFaceVertices(facePositions, faceIndex, faceCount);
        }
    }

    /**
     * Update face position by vertex indices (index-based update to prevent unification).
     * Delegates to MeshManager for clean separation of concerns.
     *
     * <p><b>Note:</b> This method only works in quad mode. In triangle mode, face data
     * should be rebuilt from GenericModelRenderer instead.
     *
     * @param faceIndex the face index
     * @param vertexIndices array of 4 unique vertex indices
     * @param newPositions array of 4 new positions
     */
    public void updateFaceByVertexIndices(int faceIndex, int[] vertexIndices, Vector3f[] newPositions) {
        if (!initialized) {
            logger.warn("Cannot update face: renderer not initialized");
            return;
        }

        // Skip in triangle mode - use rebuildFromGenericModelRenderer() instead
        if (usingTriangleMode) {
            logger.debug("Skipping face update in triangle mode - use rebuildFromGenericModelRenderer()");
            return;
        }

        // Delegate to MeshManager (Single Responsibility Principle)
        MeshManager.getInstance().updateFacePosition(vbo, facePositions, faceCount, faceIndex, vertexIndices, newPositions);
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

        if (usingTriangleMode && trianglePositions != null && triangleCount > 0) {
            // Post-subdivision: hit test all triangles, return original face ID
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
        } else if (facePositions != null) {
            // Quad mode: 4 vertices per face (12 floats)
            newHoveredFace = FaceHoverDetector.detectHoveredFace(
                mouseX, mouseY,
                viewportWidth, viewportHeight,
                viewMatrix, projectionMatrix, modelMatrix,
                facePositions,
                faceCount
            );
        } else {
            newHoveredFace = -1;
        }

        // Update hover state if changed
        if (newHoveredFace != hoveredFaceIndex) {
            int previousHover = hoveredFaceIndex;
            hoveredFaceIndex = newHoveredFace;

            logger.debug("Face hover changed: {} -> {} (total faces: {}, triangleMode: {})",
                        previousHover, hoveredFaceIndex, faceCount, usingTriangleMode);
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
        overlayRenderer.render(vao, vbo, shader, context, modelMatrix,
                             hoveredFaceIndex, selectedFaceIndex, faceCount);
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
        facePositions = null;
        faceToVertexMapping.clear();
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

        // In triangle mode, we need to update trianglePositions and rebuild VBO
        if (usingTriangleMode && genericModelRenderer != null) {
            // Update triangle positions that use any of the affected mesh vertices
            updateTrianglePositionsForMeshVertices(affectedMeshIndices, newPosition);
        }

        // Sync overlay renderer with updated positions
        syncMeshVertexPositions();

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

    public int getSelectedFaceIndex() {
        return selectedFaceIndex;
    }

    public void setSelectedFace(int faceIndex) {
        if (faceIndex < -1 || faceIndex >= faceCount) {
            logger.warn("Invalid face index: {}, valid range is -1 to {}", faceIndex, faceCount - 1);
            return;
        }

        this.selectedFaceIndex = faceIndex;

        if (faceIndex >= 0) {
            logger.debug("Selected face {}", faceIndex);
        } else {
            logger.debug("Cleared face selection");
        }
    }

    public void clearSelection() {
        this.selectedFaceIndex = -1;
    }

    public int getFaceCount() {
        return faceCount;
    }

    public float[] getFacePositions() {
        return facePositions;
    }

    // ========================================
    // GenericModelRenderer Integration (for subdivision support)
    // ========================================

    // Reference to GenericModelRenderer for triangle data access
    private GenericModelRenderer genericModelRenderer = null;

    // Flag indicating face data is from triangles (post-subdivision) vs quads (original)
    private boolean usingTriangleMode = false;

    // Maps original face ID (0-5 for cube) to list of triangle indices in GenericModelRenderer
    // Used for grouped face rendering after subdivision
    private Map<Integer, java.util.List<Integer>> originalFaceToTriangles = new HashMap<>();

    // Triangle positions for hover detection (9 floats per triangle: 3 vertices × 3 coords)
    private float[] trianglePositions = null;
    private int triangleCount = 0;

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
     * @see FaceOverlayRenderer#setGenericModelRenderer(GenericModelRenderer)
     */
    public void setGenericModelRenderer(GenericModelRenderer renderer) {
        // Unregister from previous renderer
        if (this.genericModelRenderer != null) {
            this.genericModelRenderer.removeMeshChangeListener(this);
        }

        this.genericModelRenderer = renderer;
        overlayRenderer.setGenericModelRenderer(renderer);

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
     * Synchronize face overlay mesh vertex positions from GenericModelRenderer.
     * Call this after subdivision operations to ensure face overlay vertex
     * attachments use the correct (updated) mesh vertex positions.
     *
     * <p>This should be called after:
     * <ul>
     *   <li>Edge subdivision operations</li>
     *   <li>Any mesh topology changes in GenericModelRenderer</li>
     * </ul>
     *
     * @see FaceOverlayRenderer#syncMeshVertexPositions()
     */
    public void syncMeshVertexPositions() {
        overlayRenderer.syncMeshVertexPositions();
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
            facePositions = null;
            trianglePositions = null;
            originalFaceToTriangles.clear();
            usingTriangleMode = false;
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
                    .computeIfAbsent(originalFaceId, k -> new java.util.ArrayList<>())
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
            usingTriangleMode = true;

            // Rebuild VBO with all triangles
            rebuildGroupedTriangleVBO(meshVertices, triangleIndices);

            // Pass data to overlay renderer
            overlayRenderer.setTriangleMode(true);
            overlayRenderer.setOriginalFaceToTriangles(originalFaceToTriangles);
            overlayRenderer.setTriangleCount(triangleCount);
            overlayRenderer.syncMeshVertexPositions();

            logger.info("Rebuilt face data from GenericModelRenderer: {} original faces, {} triangles",
                faceCount, triangleCount);

            // Log face-to-triangle mapping for debugging
            for (Map.Entry<Integer, java.util.List<Integer>> entry : originalFaceToTriangles.entrySet()) {
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
        int floatsPerVertex = 7;
        int verticesPerTriangle = 3;
        int floatsPerTriangle = floatsPerVertex * verticesPerTriangle;

        float[] vboData = new float[triangleCount * floatsPerTriangle];
        Vector4f defaultColor = overlayRenderer.getDefaultFaceColor();

        for (int t = 0; t < triangleCount; t++) {
            int i0 = triangleIndices[t * 3];
            int i1 = triangleIndices[t * 3 + 1];
            int i2 = triangleIndices[t * 3 + 2];

            int vboOffset = t * floatsPerTriangle;

            // Vertex 0
            vboData[vboOffset] = meshVertices[i0 * 3];
            vboData[vboOffset + 1] = meshVertices[i0 * 3 + 1];
            vboData[vboOffset + 2] = meshVertices[i0 * 3 + 2];
            vboData[vboOffset + 3] = defaultColor.x;
            vboData[vboOffset + 4] = defaultColor.y;
            vboData[vboOffset + 5] = defaultColor.z;
            vboData[vboOffset + 6] = defaultColor.w;

            // Vertex 1
            vboData[vboOffset + 7] = meshVertices[i1 * 3];
            vboData[vboOffset + 8] = meshVertices[i1 * 3 + 1];
            vboData[vboOffset + 9] = meshVertices[i1 * 3 + 2];
            vboData[vboOffset + 10] = defaultColor.x;
            vboData[vboOffset + 11] = defaultColor.y;
            vboData[vboOffset + 12] = defaultColor.z;
            vboData[vboOffset + 13] = defaultColor.w;

            // Vertex 2
            vboData[vboOffset + 14] = meshVertices[i2 * 3];
            vboData[vboOffset + 15] = meshVertices[i2 * 3 + 1];
            vboData[vboOffset + 16] = meshVertices[i2 * 3 + 2];
            vboData[vboOffset + 17] = defaultColor.x;
            vboData[vboOffset + 18] = defaultColor.y;
            vboData[vboOffset + 19] = defaultColor.z;
            vboData[vboOffset + 20] = defaultColor.w;
        }

        // Upload to VBO
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vboData, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        logger.debug("Rebuilt grouped triangle VBO: {} triangles, {} floats", triangleCount, vboData.length);
    }

    /**
     * Check if face renderer is using triangle mode (post-subdivision).
     *
     * @return true if using triangle mode, false if using quad mode
     */
    public boolean isUsingTriangleMode() {
        return usingTriangleMode;
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
