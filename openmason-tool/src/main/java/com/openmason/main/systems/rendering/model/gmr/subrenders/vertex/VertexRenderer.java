package com.openmason.main.systems.rendering.model.gmr.subrenders.vertex;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.rendering.model.MeshChangeListener;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import com.openmason.main.systems.rendering.model.gmr.mesh.MeshManager;
import com.openmason.main.systems.rendering.model.gmr.mesh.vertexOperations.MeshVertexMerger;
import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders model vertices as colored points, similar to Blender's vertex display mode.
 * Vertex positions are extracted by MeshVertexExtractor; this class handles rendering.
 * Supports selection highlighting (white) and modification tracking (yellow).
 *
 * <p>Implements MeshChangeListener to receive notifications from GenericModelRenderer
 * when vertex positions change or geometry is rebuilt. This replaces position-based
 * matching with index-based updates for robust synchronization.
 */
public class VertexRenderer implements MeshChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(VertexRenderer.class);

    // OpenGL resources
    private int vao = 0;
    private int vbo = 0;
    private int vertexCount = 0;
    private boolean initialized = false;

    // Rendering state
    private boolean enabled = false;
    private float pointSize = 5.0f;
    private float selectedPointSize = 8.0f;
    private final Vector3f defaultVertexColor = new Vector3f(1.0f, 0.6f, 0.0f); // Blender's orange
    private final Vector3f selectedVertexColor = new Vector3f(1.0f, 1.0f, 1.0f); // White
    private final Vector3f modifiedVertexColor = new Vector3f(1.0f, 1.0f, 0.0f); // Yellow

    // Hover state
    private int hoveredVertexIndex = -1; // -1 means no vertex is hovered
    private float[] vertexPositions = null; // Store positions for hit testing (unique vertices only)

    // Selection state - supports multi-selection
    private int selectedVertexIndex = -1; // -1 means no vertex is selected (for backward compat)
    private Set<Integer> selectedVertexIndices = new HashSet<>(); // Multi-selection support
    private Set<Integer> modifiedVertices = new HashSet<>(); // Indices of modified vertices

    // Persistent vertex merge tracking (for multi-stage merges)
    private Map<Integer, Integer> originalToCurrentMapping = new HashMap<>(); // Maps original cube vertex (0-7) to current unique vertex

    // Mesh management - delegate to MeshManager
    private final MeshManager meshManager = MeshManager.getInstance();

    // Reference to GenericModelRenderer for observer pattern
    private GenericModelRenderer modelRenderer;

    /**
     * Set the GenericModelRenderer that this VertexRenderer observes.
     * Registers as a MeshChangeListener to receive index-based updates.
     *
     * @param renderer The GenericModelRenderer to observe, or null to disconnect
     */
    public void setModelRenderer(GenericModelRenderer renderer) {
        // Unregister from previous renderer
        if (this.modelRenderer != null) {
            this.modelRenderer.removeMeshChangeListener(this);
        }

        this.modelRenderer = renderer;

        // Register with new renderer
        if (renderer != null) {
            renderer.addMeshChangeListener(this);
            // Initial sync from model
            rebuildFromModel();
        }

        logger.debug("VertexRenderer {} model renderer",
            renderer != null ? "connected to" : "disconnected from");
    }

    /**
     * Get the associated GenericModelRenderer.
     *
     * @return The model renderer, or null if not set
     */
    public GenericModelRenderer getModelRenderer() {
        return modelRenderer;
    }

    /**
     * Initialize the vertex renderer.
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

            // Configure vertex attributes (interleaved: position + color)
            // Stride = 6 floats (3 for position, 3 for color)
            int stride = 6 * Float.BYTES;

            // Position attribute (location = 0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
            glEnableVertexAttribArray(0);

            // Color attribute (location = 1)
            glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3 * Float.BYTES);
            glEnableVertexAttribArray(1);

            // Unbind
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);

            initialized = true;

        } catch (Exception e) {
            logger.error("Failed to initialize VertexRenderer", e);
            cleanup();
            throw new RuntimeException("VertexRenderer initialization failed", e);
        }
    }

    /**
     * Update vertex data from GenericModelRenderer (GMR is single source of truth).
     * Gets vertex data directly from GMR instead of extracting from ModelDefinition.
     * GMR provides the authoritative mesh topology after any subdivisions or modifications.
     */
    public void updateVertexDataFromGMR() {
        if (!initialized) {
            logger.warn("VertexRenderer not initialized, cannot update vertex data");
            return;
        }

        if (modelRenderer == null) {
            logger.warn("GenericModelRenderer not set");
            vertexCount = 0;
            vertexPositions = null;
            meshManager.clearMeshData();
            return;
        }

        try {
            // Extract ALL mesh vertices from GMR (e.g., 24 for cube) for mapping
            float[] allMeshVertices = modelRenderer.getAllMeshVertexPositions();

            // Extract UNIQUE vertices from GMR (e.g., 8 for cube) for rendering
            float[] uniquePositions = modelRenderer.getAllUniqueVertexPositions();

            // Store unique positions for hit testing
            vertexPositions = uniquePositions;
            vertexCount = uniquePositions.length / 3;

            // Sync mesh vertices with MeshManager (mapping is owned by GenericModelRenderer)
            meshManager.setMeshVertices(allMeshVertices);

            // Initialize original-to-current mapping (identity for fresh load)
            // For a cube, this maps original vertices 0-7 to themselves initially
            originalToCurrentMapping.clear();
            for (int i = 0; i < vertexCount; i++) {
                originalToCurrentMapping.put(i, i);
            }

            // Create interleaved vertex data (position + color) using UNIQUE vertices
            // Color varies based on state: selected (white), modified (yellow), default (orange)
            float[] vertexData = new float[vertexCount * 6]; // 3 for position, 3 for color

            for (int i = 0; i < vertexCount; i++) {
                int posIndex = i * 3;
                int dataIndex = i * 6;

                // Copy position from unique vertices
                vertexData[dataIndex] = uniquePositions[posIndex];
                vertexData[dataIndex + 1] = uniquePositions[posIndex + 1];
                vertexData[dataIndex + 2] = uniquePositions[posIndex + 2];

                // Determine color based on state (selected > modified > default)
                Vector3f color;
                if (i == selectedVertexIndex) {
                    color = selectedVertexColor; // White for selected
                } else if (modifiedVertices.contains(i)) {
                    color = modifiedVertexColor; // Yellow for modified
                } else {
                    color = defaultVertexColor; // Orange for default
                }

                // Apply color
                vertexData[dataIndex + 3] = color.x;
                vertexData[dataIndex + 4] = color.y;
                vertexData[dataIndex + 5] = color.z;
            }

            // Upload to GPU
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, vertexData, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            logger.debug("Updated vertex data from GMR: {} vertices", vertexCount);

        } catch (Exception e) {
            logger.error("Error updating vertex data from GMR", e);
        }
    }


    /**
     * Handle mouse movement for vertex hover detection.
     * Follows the same pattern as GizmoRenderer.handleMouseMove().
     * Only processes hover when EditMode is VERTEX.
     */
    public void handleMouseMove(float mouseX, float mouseY,
                               Matrix4f viewMatrix, Matrix4f projectionMatrix,
                               Matrix4f modelMatrix,
                               int viewportWidth, int viewportHeight) {
        if (!initialized || !enabled) {
            return;
        }

        // Skip hover detection if not in VERTEX edit mode
        if (!EditModeManager.getInstance().isVertexEditingAllowed()) {
            hoveredVertexIndex = -1;
            return;
        }

        if (vertexPositions == null || vertexCount == 0) {
            return;
        }

        // Detect hovered vertex using screen-space point size detection
        // Pass model matrix so vertices in model space are properly transformed
        int newHoveredVertex = VertexHoverDetector.detectHoveredVertex(
            mouseX, mouseY,
            viewportWidth, viewportHeight,
            viewMatrix, projectionMatrix, modelMatrix,
            vertexPositions,
            vertexCount,
            pointSize  // Use actual vertex point size for accurate detection
        );

        // Update hover state if changed (same pattern as gizmo)
        if (newHoveredVertex != hoveredVertexIndex) {
            int previousHover = hoveredVertexIndex;
            hoveredVertexIndex = newHoveredVertex;

            logger.debug("Vertex hover changed: {} -> {} (total vertices: {})",
                        previousHover, hoveredVertexIndex, vertexCount);
        }
    }

    /**
     * Render vertices as points.
     * KISS: Simple, focused rendering logic.
     *
     * @param shader The shader program to use
     * @param context The render context
     * @param modelMatrix The model transformation matrix (for gizmo transforms)
     */
    public void render(ShaderProgram shader, RenderContext context, Matrix4f modelMatrix) {
        if (!initialized) {
            logger.warn("VertexRenderer not initialized");
            return;
        }

        if (!enabled) {
            return;
        }

        if (vertexCount == 0) {
            return;
        }

        try {
            // Use shader
            shader.use();

            // Calculate MVP matrix with model transform (vertices are in model space)
            Matrix4f viewMatrix = context.getCamera().getViewMatrix();
            Matrix4f projectionMatrix = context.getCamera().getProjectionMatrix();
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);

            // Upload MVP matrix
            shader.setMat4("uMVPMatrix", mvpMatrix);

            // Set point size (fixed function)
            glPointSize(pointSize);

            // Save and modify depth function to ensure points are visible on surfaces
            int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
            glDepthFunc(GL_LEQUAL);

            // Bind VAO
            glBindVertexArray(vao);

            // Render vertices in order: Normal → Modified → Hovered → Selected (always on top)
            // Each state has different intensity for brightness control
            float normalIntensity = 1.0f;
            float modifiedIntensity = 2.0f;   // Yellow glow for unsaved changes
            float hoverIntensity = 2.5f;      // Bright yellow for hover
            float selectedIntensity = 3.0f;   // Brightest white for selection

            // Set normal point size for most vertices
            glPointSize(pointSize);

            // Render normal and modified vertices
            shader.setFloat("uIntensity", normalIntensity);
            for (int i = 0; i < vertexCount; i++) {
                // Skip hovered and selected vertices (will render separately)
                if (i == hoveredVertexIndex || selectedVertexIndices.contains(i)) {
                    continue;
                }

                // Modified vertices get higher intensity for visual distinction
                if (modifiedVertices.contains(i)) {
                    shader.setFloat("uIntensity", modifiedIntensity);
                    glDrawArrays(GL_POINTS, i, 1);
                    shader.setFloat("uIntensity", normalIntensity);
                } else {
                    glDrawArrays(GL_POINTS, i, 1);
                }
            }

            // Render hovered vertex (if any and not selected)
            if (hoveredVertexIndex >= 0 && !selectedVertexIndices.contains(hoveredVertexIndex)) {
                shader.setFloat("uIntensity", hoverIntensity);
                glDrawArrays(GL_POINTS, hoveredVertexIndex, 1);
            }

            // Render all selected vertices last (always on top) with larger point size
            if (!selectedVertexIndices.isEmpty()) {
                glPointSize(selectedPointSize);
                shader.setFloat("uIntensity", selectedIntensity);
                for (int selectedIndex : selectedVertexIndices) {
                    if (selectedIndex >= 0 && selectedIndex < vertexCount) {
                        glDrawArrays(GL_POINTS, selectedIndex, 1);
                    }
                }
            }

            glBindVertexArray(0);

            // Restore normal point size
            glPointSize(pointSize);

            // Restore previous depth function
            glDepthFunc(prevDepthFunc);

        } catch (Exception e) {
            logger.error("Error rendering vertices", e);
        } finally {
            glUseProgram(0);
        }
    }

    /**
     * Clean up OpenGL resources.
     */
    public void cleanup() {
        // Unregister from model renderer
        if (modelRenderer != null) {
            modelRenderer.removeMeshChangeListener(this);
            modelRenderer = null;
        }

        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vbo != 0) {
            glDeleteBuffers(vbo);
            vbo = 0;
        }
        vertexCount = 0;
        initialized = false;
    }

    // =========================================================================
    // MESH CHANGE LISTENER IMPLEMENTATION - Index-based updates from GenericModelRenderer
    // =========================================================================

    /**
     * Called when a vertex position has been updated in GenericModelRenderer.
     * Updates the vertex directly by unique index - no position search needed.
     *
     * @param uniqueIndex The unique vertex index (maps directly to our vertex array)
     * @param newPosition The new position of the vertex
     * @param affectedMeshIndices All mesh vertex indices that were updated (for reference)
     */
    @Override
    public void onVertexPositionChanged(int uniqueIndex, Vector3f newPosition, int[] affectedMeshIndices) {
        if (!initialized || vertexPositions == null) {
            return;
        }

        // Validate index bounds
        if (uniqueIndex < 0 || uniqueIndex >= vertexCount) {
            logger.warn("onVertexPositionChanged: uniqueIndex {} out of bounds (vertexCount={})",
                uniqueIndex, vertexCount);
            return;
        }

        // Update the position directly by index (no position search!)
        int posIndex = uniqueIndex * 3;
        vertexPositions[posIndex] = newPosition.x;
        vertexPositions[posIndex + 1] = newPosition.y;
        vertexPositions[posIndex + 2] = newPosition.z;

        // Update the VBO for this vertex
        updateVertexInBuffer(uniqueIndex, newPosition);

        logger.trace("VertexRenderer received vertex update: unique {} -> ({}, {}, {})",
            uniqueIndex, newPosition.x, newPosition.y, newPosition.z);
    }

    /**
     * Called when the entire geometry has been rebuilt in GenericModelRenderer.
     * Triggers a full rebuild of our vertex data from the model.
     */
    @Override
    public void onGeometryRebuilt() {
        logger.debug("VertexRenderer received geometry rebuild notification");
        rebuildFromModel();
    }

    /**
     * Rebuild vertex data from the associated GenericModelRenderer.
     * Called when initially connected or when geometry is rebuilt.
     */
    private void rebuildFromModel() {
        if (modelRenderer == null) {
            return;
        }

        if (!initialized) {
            logger.warn("Cannot rebuild from model: VertexRenderer not initialized");
            return;
        }

        // Get unique vertex positions from GenericModelRenderer
        float[] uniquePositions = modelRenderer.getAllUniqueVertexPositions();
        if (uniquePositions == null || uniquePositions.length == 0) {
            vertexCount = 0;
            vertexPositions = null;
            return;
        }

        // Update our vertex data
        int newVertexCount = uniquePositions.length / 3;
        vertexPositions = uniquePositions;
        vertexCount = newVertexCount;

        // Update original-to-current mapping (identity for fresh rebuild)
        originalToCurrentMapping.clear();
        for (int i = 0; i < vertexCount; i++) {
            originalToCurrentMapping.put(i, i);
        }

        // Sync MeshManager with the new data (mapping is owned by GenericModelRenderer)
        float[] allMeshVertices = modelRenderer.getAllMeshVertexPositions();
        if (allMeshVertices != null) {
            meshManager.setMeshVertices(allMeshVertices);
        }

        // Rebuild VBO
        rebuildVBO();

        logger.debug("VertexRenderer rebuilt from model: {} unique vertices", vertexCount);
    }

    /**
     * Update a single vertex in the GPU buffer.
     * Used for incremental updates when positions change.
     *
     * @param uniqueIndex The unique vertex index
     * @param position The new position
     */
    private void updateVertexInBuffer(int uniqueIndex, Vector3f position) {
        if (!initialized || uniqueIndex < 0 || uniqueIndex >= vertexCount) {
            return;
        }

        // Calculate offset in interleaved buffer (6 floats per vertex: pos + color)
        int bufferOffset = uniqueIndex * 6 * Float.BYTES;

        // Create position data
        float[] posData = new float[] { position.x, position.y, position.z };

        // Update just the position portion of this vertex in the VBO
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, bufferOffset, posData);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Set the selected vertex index (single selection - replaces any existing selection).
     * @param index Vertex index to select, or -1 to clear selection
     */
    public void setSelectedVertex(int index) {
        if (index < -1 || (index >= 0 && index >= vertexCount)) {
            logger.warn("Invalid vertex index for selection: {} (max: {})", index, vertexCount - 1);
            return;
        }

        // Clear existing and set new single selection
        selectedVertexIndices.clear();
        if (index >= 0) {
            selectedVertexIndices.add(index);
        }
        selectedVertexIndex = index;
        logger.debug("Selected vertex set to: {}", index);
    }

    /**
     * Update the selection with a set of vertex indices (multi-selection).
     * @param indices Set of vertex indices to select
     */
    public void updateSelectionSet(Set<Integer> indices) {
        selectedVertexIndices.clear();
        if (indices != null) {
            for (Integer index : indices) {
                if (index >= 0 && index < vertexCount) {
                    selectedVertexIndices.add(index);
                }
            }
        }
        // Update backward compat field
        selectedVertexIndex = selectedVertexIndices.isEmpty() ? -1 : selectedVertexIndices.iterator().next();
        logger.debug("Updated vertex selection set: {} vertices", selectedVertexIndices.size());
    }

    /**
     * Get the set of selected vertex indices.
     * @return Copy of the selected vertex indices set
     */
    public Set<Integer> getSelectedVertexIndices() {
        return new HashSet<>(selectedVertexIndices);
    }

    /**
     * Check if a specific vertex is selected.
     * @param index Vertex index to check
     * @return true if the vertex is selected
     */
    public boolean isVertexSelected(int index) {
        return selectedVertexIndices.contains(index);
    }

    /**
     * Get the currently selected vertex index (backward compatibility).
     * @return First selected vertex index, or -1 if no selection
     */
    public int getSelectedVertexIndex() {
        return selectedVertexIndex;
    }

    /**
     * Clear the current selection.
     */
    public void clearSelection() {
        selectedVertexIndices.clear();
        selectedVertexIndex = -1;
        logger.debug("Vertex selection cleared");
    }

    /**
     * Update a single UNIQUE vertex position (for live preview during drag).
     * This updates ALL mesh instances of this vertex to eliminate duplication.
     * For example, when dragging corner vertex 0 on a cube, all 3 mesh instances
     * at that corner are updated simultaneously.
     *
     * @param uniqueIndex Unique vertex index to update (0-7 for cube)
     * @param position New position in model space
     */
    public void updateVertexPosition(int uniqueIndex, Vector3f position) {
        if (!initialized) {
            logger.warn("Cannot update vertex position: renderer not initialized");
            return;
        }

        // Update VertexRenderer's positions and VBO
        meshManager.updateVertexPosition(uniqueIndex, position, vertexPositions, vbo, vertexCount);

        // Sync mesh vertices in MeshManager using GenericModelRenderer's mapping
        // This ensures MeshManager.getAllMeshVertices() returns updated positions
        // for the solid model to follow the wireframe
        if (modelRenderer != null) {
            int[] meshIndices = modelRenderer.getMeshIndicesForUniqueVertex(uniqueIndex);
            if (meshIndices != null) {
                float[] allMeshVertices = meshManager.getAllMeshVertices();
                if (allMeshVertices != null) {
                    for (int meshIndex : meshIndices) {
                        int offset = meshIndex * 3;
                        if (offset + 2 < allMeshVertices.length) {
                            allMeshVertices[offset] = position.x;
                            allMeshVertices[offset + 1] = position.y;
                            allMeshVertices[offset + 2] = position.z;
                        }
                    }
                }
            }
        }
    }

    /**
     * Get the hovered vertex index.
     * @return Hovered vertex index, or -1 if no vertex is hovered
     */
    public int getHoveredVertexIndex() {
        return hoveredVertexIndex;
    }

    /**
     * Get the position of a specific vertex by index.
     * @param index Vertex index
     * @return Vertex position as Vector3f, or null if invalid index
     */
    public Vector3f getVertexPosition(int index) {
        if (vertexPositions == null || index < 0 || index >= vertexCount) {
            return null;
        }

        int posIndex = index * 3;
        return new Vector3f(
            vertexPositions[posIndex],
            vertexPositions[posIndex + 1],
            vertexPositions[posIndex + 2]
        );
    }

    /**
     * Get all unique vertex positions as a flat array.
     * Used for updating the BlockModelRenderer mesh when vertices are dragged.
     *
     * @return Array of vertex positions [x0,y0,z0, x1,y1,z1, ...] or null if no vertices
     */
    public float[] getAllVertexPositions() {
        return vertexPositions;
    }

    /**
     * Get the current vertex count.
     *
     * @return Number of unique vertices
     */
    public int getVertexCount() {
        return vertexCount;
    }

    /**
     * Apply vertex addition from a subdivision operation.
     * Updates vertex data structures with new vertex positions and count.
     * Used when subdivision creates a new midpoint vertex.
     *
     * @param newVertexPositions Updated vertex positions array
     * @param newVertexCount Updated vertex count
     */
    public void applyVertexAddition(float[] newVertexPositions, int newVertexCount) {
        if (!initialized) {
            logger.warn("Cannot apply vertex addition: renderer not initialized");
            return;
        }

        if (newVertexPositions == null || newVertexCount <= 0) {
            logger.warn("Cannot apply vertex addition: invalid parameters");
            return;
        }

        // Update vertex data
        this.vertexPositions = newVertexPositions;
        this.vertexCount = newVertexCount;

        // Update original-to-current mapping for new vertex (identity mapping)
        int newVertexIndex = newVertexCount - 1;
        originalToCurrentMapping.put(newVertexIndex, newVertexIndex);

        // Rebuild VBO with new vertex data
        rebuildVBO();

        // Note: GenericModelRenderer owns the unique-to-mesh mapping and will
        // rebuild it automatically when notifyGeometryRebuilt() is called

        logger.debug("Applied vertex addition: now {} vertices (new vertex at index {})",
                    vertexCount, newVertexIndex);
    }

    /**
     * Update multiple unique vertices by their indices and new positions.
     * FIX: Index-based update prevents vertex unification bug.
     * Uses uniqueToMeshMapping to update all mesh instances correctly.
     *
     * @param vertexIndex1 First unique vertex index
     * @param newPosition1 New position for first vertex
     * @param vertexIndex2 Second unique vertex index
     * @param newPosition2 New position for second vertex
     */
    public void updateVerticesByIndices(int vertexIndex1, Vector3f newPosition1,
                                        int vertexIndex2, Vector3f newPosition2) {
        if (!initialized || vertexPositions == null || vertexCount == 0) {
            logger.warn("Cannot update vertices: renderer not initialized or no vertices");
            return;
        }

        if (newPosition1 == null || newPosition2 == null) {
            logger.warn("Cannot update vertices: positions are null");
            return;
        }

        if (vertexIndex1 < 0 || vertexIndex1 >= vertexCount ||
            vertexIndex2 < 0 || vertexIndex2 >= vertexCount) {
            logger.warn("Cannot update vertices: invalid indices {}, {} (count: {})",
                    vertexIndex1, vertexIndex2, vertexCount);
            return;
        }

        try {
            // Update first vertex using index-based method
            updateVertexPosition(vertexIndex1, newPosition1);

            // Update second vertex using index-based method
            updateVertexPosition(vertexIndex2, newPosition2);

            logger.trace("Updated vertices {} and {} to new positions (index-based)",
                    vertexIndex1, vertexIndex2);

        } catch (Exception e) {
            logger.error("Error updating vertices by indices", e);
        }
    }

    /**
     * Merge overlapping vertices by removing duplicates and updating all references.
     * This is a TRUE merge operation that:
     * 1. Identifies groups of vertices at the same position
     * 2. Keeps the first vertex in each group, removes the rest
     * 3. Returns a mapping of old indices to new indices for updating edges
     *
     * @param epsilon Distance threshold for considering vertices overlapping
     * @return Mapping of old vertex indices to new vertex indices (for updating edges)
     */
    public Map<Integer, Integer> mergeOverlappingVertices(float epsilon) {
        if (!initialized || vertexPositions == null || vertexCount == 0) {
            return new HashMap<>();
        }

        // Step 1: Perform the merge operation via MeshManager
        MeshVertexMerger.MergeResult result = meshManager.mergeOverlappingVertices(
                vertexPositions,
                vertexCount,
                epsilon,
                originalToCurrentMapping
        );

        // If no merge occurred, return existing mapping
        if (result == null) {
            return new HashMap<>(originalToCurrentMapping);
        }

        // Step 2: Update renderer state with merge results (Single Responsibility)
        // Note: GenericModelRenderer now owns the unique-to-mesh mapping, so we pass
        // empty/no-op values for the deprecated mapping parameters
        RendererStateUpdater.UpdateContext context = new RendererStateUpdater.UpdateContext(
                meshManager.getAllMeshVertices(),
                java.util.Collections.emptyMap() // Mapping now owned by GenericModelRenderer
        );

        RendererStateUpdater stateUpdater = new RendererStateUpdater(
                context,
                () -> {}, // Empty VBO rebuilder - we'll rebuild after updating fields
                (positions, mesh) -> {} // No-op - mapping owned by GenericModelRenderer
        );

        RendererStateUpdater.UpdateContext updatedContext = stateUpdater.applyMergeResult(result);

        // Step 3: Apply updated state back to renderer
        this.vertexPositions = updatedContext.vertexPositions;
        this.vertexCount = updatedContext.vertexCount;
        this.originalToCurrentMapping = updatedContext.originalToCurrentMapping;
        this.selectedVertexIndex = updatedContext.selectedVertexIndex;

        // Step 4: Rebuild VBO now that fields are updated
        // FIX: Must rebuild AFTER fields are updated, not during applyMergeResult
        rebuildVBO();

        // Return the persistent original-to-current mapping (all 8 original vertices)
        return new HashMap<>(originalToCurrentMapping);
    }

    /**
     * Rebuild the VBO with current vertex positions and colors.
     * Used after merging vertices to update GPU data.
     */
    private void rebuildVBO() {
        if (!initialized || vertexPositions == null || vertexCount == 0) {
            return;
        }

        // Create interleaved vertex data (position + color)
        float[] vertexData = new float[vertexCount * 6];

        for (int i = 0; i < vertexCount; i++) {
            int posIndex = i * 3;
            int dataIndex = i * 6;

            // Copy position
            vertexData[dataIndex] = vertexPositions[posIndex];
            vertexData[dataIndex + 1] = vertexPositions[posIndex + 1];
            vertexData[dataIndex + 2] = vertexPositions[posIndex + 2];

            // Determine color based on state
            Vector3f color;
            if (i == selectedVertexIndex) {
                color = selectedVertexColor;
            } else if (modifiedVertices.contains(i)) {
                color = modifiedVertexColor;
            } else {
                color = defaultVertexColor;
            }

            vertexData[dataIndex + 3] = color.x;
            vertexData[dataIndex + 4] = color.y;
            vertexData[dataIndex + 5] = color.z;
        }

        // Upload to GPU
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertexData, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        logger.debug("Rebuilt VBO with {} vertices", vertexCount);
    }

    // Getters and setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setPointSize(float pointSize) {
        this.pointSize = Math.max(1.0f, Math.min(15.0f, pointSize));
        logger.trace("Point size set to: {}", this.pointSize);
    }

    public boolean isInitialized() {
        return initialized;
    }

}
