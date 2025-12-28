package com.openmason.main.systems.viewport.viewportRendering.edge;

import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import com.openmason.main.systems.viewport.viewportRendering.edge.operations.EdgeSelectionManager;
import com.openmason.main.systems.viewport.viewportRendering.mesh.MeshManager;
import com.openmason.main.systems.viewport.viewportRendering.mesh.edgeOperations.MeshEdgeBufferUpdater;
import com.openmason.main.systems.viewport.viewportRendering.mesh.edgeOperations.MeshEdgePositionUpdater;
import com.openmason.main.systems.viewport.viewportRendering.mesh.edgeOperations.MeshEdgeSubdivider;
import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexRenderer;
import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.Collection;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders model edges as lines in the 3D viewport.
 * Provides edge visualization, selection, and hover detection for model editing.
 *
 * <p>This renderer complements the VertexRenderer by displaying edges as lines,
 * allowing users to select and manipulate edges during model editing operations.
 * Supports hover highlighting, selection feedback, and real-time position updates
 * in response to vertex transformations.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>Renders edges as GL_LINES with configurable width and colors</li>
 *   <li>Hover detection with orange highlight feedback</li>
 *   <li>Selection support with white highlight feedback</li>
 *   <li>Edge-to-vertex mapping for precise position updates</li>
 *   <li>Supports both position-based and index-based edge updates</li>
 *   <li>Integrates with viewport input controllers for interaction</li>
 * </ul>
 *
 * <p><b>Rendering Pipeline:</b>
 * <ol>
 *   <li>Initialize OpenGL resources (VAO, VBO) with {@link #initialize()}</li>
 *   <li>Update edge data from model parts with {@link #updateEdgeData}</li>
 *   <li>Build edge-to-vertex mapping with {@link #buildEdgeToVertexMapping}</li>
 *   <li>Render edges each frame with {@link #render}</li>
 *   <li>Clean up resources on shutdown with {@link #cleanup()}</li>
 * </ol>
 *
 * <p><b>Thread Safety:</b> This class is not thread-safe. All methods must be called
 * from the OpenGL context thread.
 *
 * <p><b>Data Format:</b> Edges are stored in an interleaved VBO format with 6 floats
 * per vertex (x, y, z, r, g, b). Edge positions are cached separately for hover detection.
 *
 * @see com.openmason.main.systems.viewport.viewportRendering.vertex.VertexRenderer
 * @see com.openmason.main.systems.viewport.viewportRendering.edge.operations
 */
public class EdgeRenderer {

    private static final Logger logger = LoggerFactory.getLogger(EdgeRenderer.class);

    // Constants for edge rendering
    /** Default line width for edge rendering in pixels. */
    private static final float DEFAULT_LINE_WIDTH = 1.5f;

    /** Normal intensity for edge rendering. */
    private static final float NORMAL_INTENSITY = 1.0f;

    /** Number of vertices per edge (start and end points). */
    private static final int VERTICES_PER_EDGE = 2;

    /** Number of floats per edge position (2 endpoints × 3 coordinates). */
    private static final int FLOATS_PER_EDGE = 6;

    /** Vertex attribute stride in bytes (position + color: 6 floats). */
    private static final int VERTEX_STRIDE_BYTES = 6 * Float.BYTES;

    /** Color attribute offset in bytes (after 3 position floats). */
    private static final int COLOR_OFFSET_BYTES = 3 * Float.BYTES;

    /** Number of components in position attribute (x, y, z). */
    private static final int POSITION_COMPONENTS = 3;

    /** Number of components in color attribute (r, g, b). */
    private static final int COLOR_COMPONENTS = 3;

    /** Epsilon tolerance for vertex position matching. */
    private static final float POSITION_EPSILON = 0.0001f;

    /** Minimum valid length for vertex position array (one vertex). */
    private static final int MIN_VERTEX_ARRAY_LENGTH = 3;

    /** Index value indicating no edge is selected or hovered. */
    private static final int NO_EDGE_SELECTED = -1;

    // OpenGL resources
    /** Vertex Array Object handle. */
    private int vao = 0;

    /** Vertex Buffer Object handle. */
    private int vbo = 0;

    /** Number of edges currently loaded. */
    private int edgeCount = 0;

    /** Whether the renderer has been initialized. */
    private boolean initialized = false;

    // Rendering state
    /** Whether edge rendering is enabled. */
    private boolean enabled = false;

    /** Line width for edge rendering in pixels. */
    private float lineWidth = DEFAULT_LINE_WIDTH;

    /** Default edge color (black). */
    private final Vector3f edgeColor = new Vector3f(0.0f, 0.0f, 0.0f);

    /** Hover edge color (orange, matching vertex renderer). */
    private final Vector3f hoverEdgeColor = new Vector3f(1.0f, 0.6f, 0.0f);

    /** Selected edge color (white for high visibility). */
    private final Vector3f selectedEdgeColor = new Vector3f(1.0f, 1.0f, 1.0f);

    // Hover state
    /** Index of currently hovered edge, or -1 if no edge is hovered. */
    private int hoveredEdgeIndex = NO_EDGE_SELECTED;

    /** Cached edge positions for hover detection [x1,y1,z1, x2,y2,z2, ...]. */
    private float[] edgePositions = null;

    // Selection state
    /** Index of currently selected edge, or -1 if no edge is selected. */
    private int selectedEdgeIndex = NO_EDGE_SELECTED;

    // Edge-to-vertex mapping for precise updates
    /**
     * Maps edge index to unique vertex indices.
     * Format: edgeToVertexMapping[edgeIndex][0] = vertexIndex1,
     *         edgeToVertexMapping[edgeIndex][1] = vertexIndex2.
     * Prevents vertex unification bugs during edge position updates.
     * For a cube: 12 edges connecting 8 unique vertices.
     */
    private int[][] edgeToVertexMapping = null;

    // Operation delegates (Single Responsibility Pattern)
    /**
     * Manages edge selection state and validation.
     * Validates selection indices and tracks state changes.
     */
    private final EdgeSelectionManager selectionManager = new EdgeSelectionManager();

    /**
     * Unified mesh manager for all mesh operations.
     * Handles buffer updates, position updates, remapping, and geometry queries.
     */
    private final MeshManager meshManager = MeshManager.getInstance();

    /**
     * Initializes the edge renderer and allocates OpenGL resources.
     * Creates a VAO and VBO with interleaved vertex attributes (position + color).
     *
     * <p>This method must be called before any rendering operations.
     * It is safe to call multiple times; subsequent calls are ignored.
     *
     * <p><b>OpenGL State:</b> Creates and configures a VAO with two vertex attributes:
     * <ul>
     *   <li>Attribute 0: Position (vec3)</li>
     *   <li>Attribute 1: Color (vec3)</li>
     * </ul>
     *
     * @throws RuntimeException if OpenGL resource allocation fails
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
            // Position attribute (location = 0)
            glVertexAttribPointer(0, POSITION_COMPONENTS, GL_FLOAT, false, VERTEX_STRIDE_BYTES, 0);
            glEnableVertexAttribArray(0);

            // Color attribute (location = 1)
            glVertexAttribPointer(1, COLOR_COMPONENTS, GL_FLOAT, false, VERTEX_STRIDE_BYTES, COLOR_OFFSET_BYTES);
            glEnableVertexAttribArray(1);

            // Unbind
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);

            initialized = true;

        } catch (Exception e) {
            logger.error("Failed to initialize EdgeRenderer", e);
            cleanup();
            throw new RuntimeException("EdgeRenderer initialization failed", e);
        }
    }

    /**
     * Updates edge data from a collection of model parts with transformation.
     * Extracts edges from model geometry and uploads to GPU buffer.
     *
     * <p>This method works with any model type by using the IGeometryExtractor interface.
     * The transformation matrix is applied to all edge positions during extraction.
     *
     * <p><b>Side Effects:</b>
     * <ul>
     *   <li>Updates the VBO with new edge data</li>
     *   <li>Updates edgeCount and edgePositions fields</li>
     *   <li>Invalidates edge-to-vertex mapping (must be rebuilt)</li>
     * </ul>
     *
     * @param parts collection of model parts to extract edges from
     * @param transformMatrix transformation matrix to apply to edge positions
     */
    public void updateEdgeData(Collection<ModelDefinition.ModelPart> parts, Matrix4f transformMatrix) {
        // Delegate to overloaded method without unique vertices (uses duplicate edges)
        updateEdgeData(parts, transformMatrix, null);
    }

    /**
     * Update edge data from model parts with optional deduplication.
     *
     * <p>This method extracts edge geometry from the given model parts and updates
     * the internal VBO. If uniqueVertexPositions is provided, duplicate edges are
     * eliminated (shared edges between faces are stored only once).
     *
     * <p><b>Side Effects:</b>
     * <ul>
     *   <li>Updates internal edge positions array</li>
     *   <li>Updates edge count</li>
     *   <li>Rebuilds edge VBO</li>
     *   <li>Invalidates edge-to-vertex mapping (must be rebuilt)</li>
     * </ul>
     *
     * @param parts collection of model parts to extract edges from
     * @param transformMatrix transformation matrix to apply to edge positions
     * @param uniqueVertexPositions unique vertex positions for deduplication (or null for duplicates)
     */
    public void updateEdgeData(Collection<ModelDefinition.ModelPart> parts,
                                Matrix4f transformMatrix,
                                float[] uniqueVertexPositions) {
        if (!initialized) {
            logger.warn("EdgeRenderer not initialized, cannot update edge data");
            return;
        }

        if (parts == null || parts.isEmpty()) {
            edgeCount = 0;
            edgePositions = null;
            return;
        }

        try {
            // Extract edges using MeshManager (centralized mesh operations)
            float[] extractedPositions;
            if (uniqueVertexPositions != null) {
                // Extract unique edges (no duplicates)
                extractedPositions = meshManager.extractUniqueEdgeGeometry(parts, transformMatrix, uniqueVertexPositions);
            } else {
                // Extract all edges (with duplicates for backward compatibility)
                extractedPositions = meshManager.extractEdgeGeometry(parts, transformMatrix);
            }

            // Update buffer with extracted edge data (delegated to MeshManager)
            MeshEdgeBufferUpdater.UpdateResult result = meshManager.updateEdgeBuffer(vbo, extractedPositions, edgeColor);

            if (result != null) {
                edgeCount = result.getEdgeCount();
                edgePositions = result.getEdgePositions();
            } else {
                logger.warn("Buffer update failed, clearing edge data");
                edgeCount = 0;
                edgePositions = null;
            }

        } catch (Exception e) {
            logger.error("Error updating edge data", e);
        }
    }

    /**
     * Builds edge-to-vertex mapping from unique vertex positions.
     * Creates a mapping that identifies which unique vertices each edge connects.
     *
     * <p>This mapping is essential for index-based edge position updates, which prevent
     * vertex unification bugs when multiple edges share the same spatial position but
     * represent different logical connections.
     *
     * <p>The algorithm matches edge endpoints to unique vertices using epsilon-based
     * floating-point comparison for robustness against numerical precision issues.
     *
     * <p><b>Prerequisites:</b> {@link #updateEdgeData} must be called first to populate edge data.
     *
     * <p><b>Time Complexity:</b> O(E × V) where E is edge count and V is vertex count.
     * For typical models, this completes in milliseconds.
     *
     * @param uniqueVertexPositions array of unique vertex positions in format [x0,y0,z0, x1,y1,z1, ...]
     */
    public void buildEdgeToVertexMapping(float[] uniqueVertexPositions) {
        // Delegate to MeshManager for edge-to-vertex mapping
        edgeToVertexMapping = meshManager.buildEdgeToVertexMapping(
            edgePositions, edgeCount, uniqueVertexPositions, POSITION_EPSILON
        );
    }

    /**
     * Remaps edge vertex indices after vertices have been merged.
     * Updates the edge-to-vertex mapping to use new vertex indices post-consolidation.
     *
     * <p>This method is typically called after vertex merging operations that consolidate
     * duplicate or nearby vertices. It ensures edges correctly reference the updated
     * vertex indices.
     *
     * <p><b>Prerequisites:</b> {@link #buildEdgeToVertexMapping} must have been called
     * to create the initial mapping.
     *
     * @param oldToNewIndexMap mapping from old vertex indices to new vertex indices
     */
    public void remapEdgeVertexIndices(Map<Integer, Integer> oldToNewIndexMap) {
        var result = meshManager.remapEdgeVertexIndices(edgeToVertexMapping, edgeCount, oldToNewIndexMap);

        if (result != null && result.isSuccessful()) {
            logger.debug("Remapped {} of {} edge vertex indices",
                result.getRemappedEdges(), result.getTotalEdges());
        }
    }

    /**
     * Renders edges as lines with hover highlighting.
     * Renders all edges with appropriate colors and highlights the hovered edge if present.
     *
     * <p>This method implements a simple rendering strategy:
     * <ul>
     *   <li>Renders non-hovered edges in default color (black)</li>
     *   <li>Temporarily updates hovered edge color to orange</li>
     *   <li>Renders hovered edge with highlight</li>
     *   <li>Restores original color for next frame</li>
     * </ul>
     *
     * <p><b>Rendering Strategy:</b> Uses GL_LINES mode with per-frame color updates
     * for hover highlighting. This approach keeps edge rendering simple (KISS principle)
     * while providing visual feedback consistent with vertex renderer behavior.
     *
     * <p><b>OpenGL State:</b>
     * <ul>
     *   <li>Temporarily modifies depth function to GL_LEQUAL</li>
     *   <li>Sets line width before rendering</li>
     *   <li>Restores previous depth function after rendering</li>
     * </ul>
     *
     * @param shader the shader program to use for rendering
     * @param context the render context containing camera matrices
     * @param modelMatrix the model transformation matrix (for gizmo transforms)
     */
    public void render(ShaderProgram shader, RenderContext context, Matrix4f modelMatrix) {
        if (!initialized) {
            logger.warn("EdgeRenderer not initialized");
            return;
        }

        if (!enabled) {
            return;
        }

        if (edgeCount == 0) {
            return;
        }

        try {
            // Use shader
            shader.use();

            // Calculate MVP matrix with model transform (edges are in model space)
            Matrix4f viewMatrix = context.getCamera().getViewMatrix();
            Matrix4f projectionMatrix = context.getCamera().getProjectionMatrix();
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);

            // Upload MVP matrix
            shader.setMat4("uMVPMatrix", mvpMatrix);

            // Set line width
            glLineWidth(lineWidth);

            // Save and modify depth function to ensure lines are visible on surfaces
            int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
            glDepthFunc(GL_LEQUAL);

            // Bind VAO
            glBindVertexArray(vao);

            // Set normal intensity for all edges
            shader.setFloat("uIntensity", NORMAL_INTENSITY);

            // Render all edges at once with appropriate color
            if (hoveredEdgeIndex >= 0) {
                // Render non-hovered edges normally (black)
                for (int i = 0; i < edgeCount; i++) {
                    if (i != hoveredEdgeIndex) {
                        glDrawArrays(GL_LINES, i * VERTICES_PER_EDGE, VERTICES_PER_EDGE);
                    }
                }

                // Update hovered edge color to orange temporarily
                int hoveredVertexStart = hoveredEdgeIndex * VERTICES_PER_EDGE;
                int vboOffset = (hoveredVertexStart * VERTEX_STRIDE_BYTES) + COLOR_OFFSET_BYTES;

                // Create buffer with orange color for both vertices of the edge
                FloatBuffer orangeBuffer = BufferUtils.createFloatBuffer(6);
                orangeBuffer.put(hoverEdgeColor.x).put(hoverEdgeColor.y).put(hoverEdgeColor.z);  // Vertex 1
                orangeBuffer.put(hoverEdgeColor.x).put(hoverEdgeColor.y).put(hoverEdgeColor.z);  // Vertex 2
                orangeBuffer.flip();

                // Update VBO with orange color for hovered edge
                glBindBuffer(GL_ARRAY_BUFFER, vbo);

                // Update first vertex color
                glBufferSubData(GL_ARRAY_BUFFER, vboOffset, new float[] {
                    hoverEdgeColor.x, hoverEdgeColor.y, hoverEdgeColor.z
                });

                // Update second vertex color
                glBufferSubData(GL_ARRAY_BUFFER, vboOffset + VERTEX_STRIDE_BYTES, new float[] {
                    hoverEdgeColor.x, hoverEdgeColor.y, hoverEdgeColor.z
                });

                glBindBuffer(GL_ARRAY_BUFFER, 0);

                // Render hovered edge with orange color
                glDrawArrays(GL_LINES, hoveredEdgeIndex * VERTICES_PER_EDGE, VERTICES_PER_EDGE);

                // Restore black color for next frame
                glBindBuffer(GL_ARRAY_BUFFER, vbo);
                glBufferSubData(GL_ARRAY_BUFFER, vboOffset, new float[] {
                    edgeColor.x, edgeColor.y, edgeColor.z
                });
                glBufferSubData(GL_ARRAY_BUFFER, vboOffset + VERTEX_STRIDE_BYTES, new float[] {
                    edgeColor.x, edgeColor.y, edgeColor.z
                });
                glBindBuffer(GL_ARRAY_BUFFER, 0);
            } else {
                // No hover - render all edges normally (black)
                glDrawArrays(GL_LINES, 0, edgeCount * VERTICES_PER_EDGE);
            }

            glBindVertexArray(0);

            // Restore previous depth function
            glDepthFunc(prevDepthFunc);

        } catch (Exception e) {
            logger.error("Error rendering edges", e);
        } finally {
            glUseProgram(0);
        }
    }

    /**
     * Cleans up OpenGL resources.
     * Deletes the VAO and VBO, freeing GPU memory.
     *
     * <p>This method should be called when the renderer is no longer needed.
     * After calling cleanup, {@link #initialize()} must be called again before
     * rendering can resume.
     *
     * <p>It is safe to call this method multiple times or when not initialized.
     */
    public void cleanup() {
        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vbo != 0) {
            glDeleteBuffers(vbo);
            vbo = 0;
        }
        edgeCount = 0;
        initialized = false;
    }

    // Getters and setters

    /**
     * Returns whether edge rendering is enabled.
     *
     * @return true if edge rendering is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether edge rendering is enabled.
     *
     * @param enabled true to enable edge rendering, false to disable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns whether the renderer has been initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns the index of the currently hovered edge.
     *
     * @return hovered edge index, or -1 if no edge is hovered
     */
    public int getHoveredEdgeIndex() {
        return hoveredEdgeIndex;
    }

    /**
     * Sets the hovered edge index.
     * Called by input controller after hover detection via raycasting.
     *
     * <p>When the hover changes, logs a debug message for tracing user interaction.
     *
     * @param edgeIndex the index of the edge to set as hovered, or -1 to clear hover
     */
    public void setHoveredEdgeIndex(int edgeIndex) {
        if (edgeIndex != hoveredEdgeIndex) {
            int previousHover = hoveredEdgeIndex;
            hoveredEdgeIndex = edgeIndex;

            logger.debug("Edge hover changed: {} -> {} (total edges: {})",
                    previousHover, hoveredEdgeIndex, edgeCount);
        }
    }

    /**
     * Returns the edge positions array for hover detection.
     * Used by input controller to perform screen-space raycasting.
     *
     * @return edge positions array in format [x1,y1,z1, x2,y2,z2, ...], or null if no edges
     */
    public float[] getEdgePositions() {
        return edgePositions;
    }

    /**
     * Returns the number of edges currently loaded.
     *
     * @return number of edges
     */
    public int getEdgeCount() {
        return edgeCount;
    }

    /**
     * Returns the line width used for edge rendering.
     * Used by input controller for accurate hover detection based on visual line thickness.
     *
     * @return line width in pixels
     */
    public float getLineWidth() {
        return lineWidth;
    }

    /**
     * Returns the index of the currently selected edge.
     *
     * @return selected edge index, or -1 if no edge is selected
     */
    public int getSelectedEdgeIndex() {
        return selectedEdgeIndex;
    }

    /**
     * Sets the selected edge by index.
     * Delegates to EdgeSelectionManager for validation and state management.
     *
     * @param edgeIndex the index of the edge to select, or -1 to clear selection
     */
    public void setSelectedEdge(int edgeIndex) {
        EdgeSelectionManager.SelectionResult result =
            selectionManager.setSelectedEdge(edgeIndex, selectedEdgeIndex, edgeCount);

        if (result != null) {
            this.selectedEdgeIndex = result.getSelectedIndex();
        }
    }

    /**
     * Clears the edge selection.
     * Delegates to EdgeSelectionManager for state management.
     */
    public void clearSelection() {
        EdgeSelectionManager.SelectionResult result =
            selectionManager.clearSelection(selectedEdgeIndex);

        if (result != null) {
            this.selectedEdgeIndex = result.getSelectedIndex();
        }
    }

    /**
     * Returns the endpoint positions of an edge.
     * Delegates to MeshManager for data retrieval.
     *
     * @param edgeIndex the index of the edge to query
     * @return array containing [endpoint1, endpoint2], or null if index is invalid
     */
    public Vector3f[] getEdgeEndpoints(int edgeIndex) {
        return meshManager.getEdgeEndpoints(edgeIndex, edgePositions, edgeCount);
    }

    /**
     * Updates all edge endpoints that match a dragged vertex position.
     * Uses position-based matching strategy via MeshManager.
     *
     * <p>This method delegates to {@link MeshManager#updateEdgesByPosition} which
     * searches through all edge endpoints and updates any that were at the old vertex position.
     * This handles models where MeshEdgeExtractor creates face-based edges (e.g., 24 edges for a cube:
     * 4 per face × 6 faces) instead of 12 unique edges, so multiple edge endpoints share positions.
     *
     * @param oldPosition the original position of the vertex before dragging
     * @param newPosition the new position of the vertex after dragging
     */
    public void updateEdgesConnectedToVertex(Vector3f oldPosition, Vector3f newPosition) {
        if (!initialized) {
            logger.warn("Cannot update edge endpoints: renderer not initialized");
            return;
        }

        var result = meshManager.updateEdgesByPosition(vbo, edgePositions, edgeCount, oldPosition, newPosition);

        if (result != null && result.isSuccessful()) {
            logger.trace("Updated {} edge endpoints using {} strategy",
                result.getUpdatedCount(), result.getStrategy());
        }
    }

    /**
     * Updates edges connected to a single vertex using index-based matching.
     * Uses the edge-to-vertex mapping for precise updates, preventing coordinate drift
     * that can occur with position-based matching after subdivision operations.
     *
     * <p>This method is preferred over {@link #updateEdgesConnectedToVertex} for vertex
     * dragging operations, especially after edge subdivision where position-based
     * matching may fail due to floating-point coordinate differences.
     *
     * <p><b>Prerequisites:</b> {@link #buildEdgeToVertexMapping} must have been called.
     *
     * @param vertexIndex the unique vertex index that was moved
     * @param newPosition the new position for the vertex
     */
    public void updateEdgesConnectedToVertexByIndex(int vertexIndex, Vector3f newPosition) {
        if (!initialized) {
            logger.warn("Cannot update edges: renderer not initialized");
            return;
        }

        if (edgeToVertexMapping == null) {
            logger.warn("Cannot update edges by index: mapping not built. Falling back to position-based update.");
            return;
        }

        var result = meshManager.updateEdgesBySingleVertexIndex(vbo, edgePositions, edgeCount, edgeToVertexMapping,
                                                                 vertexIndex, newPosition);

        if (result != null && result.isSuccessful()) {
            logger.trace("Updated {} edges connected to vertex {} using {} strategy",
                result.getUpdatedCount(), vertexIndex, result.getStrategy());
        }
    }

    /**
     * Returns the unique vertex indices for a given edge.
     * Delegates to MeshManager to retrieve which unique vertices this edge connects.
     *
     * <p><b>Prerequisites:</b> {@link #buildEdgeToVertexMapping} must have been called.
     *
     * @param edgeIndex the edge index to query
     * @return array of [vertexIndex1, vertexIndex2], or null if mapping is not available
     */
    public int[] getEdgeVertexIndices(int edgeIndex) {
        return meshManager.getEdgeVertexIndices(edgeIndex, edgeToVertexMapping);
    }

    /**
     * Updates edges connected to specific vertex indices.
     * Uses index-based matching strategy via MeshManager to prevent vertex unification bugs.
     *
     * <p>This method delegates to {@link MeshManager#updateEdgesByIndices} which only
     * updates edges that connect to the specified unique vertex indices. This is more precise
     * than position-based updates and prevents unintended modifications when vertices share positions.
     *
     * <p><b>Prerequisites:</b> {@link #buildEdgeToVertexMapping} must have been called.
     *
     * @param vertexIndex1 the first unique vertex index that was moved
     * @param newPosition1 the new position for the first vertex
     * @param vertexIndex2 the second unique vertex index that was moved
     * @param newPosition2 the new position for the second vertex
     */
    public void updateEdgesByVertexIndices(int vertexIndex1, Vector3f newPosition1,
                                           int vertexIndex2, Vector3f newPosition2) {
        if (!initialized) {
            logger.warn("Cannot update edges: renderer not initialized");
            return;
        }

        var result = meshManager.updateEdgesByIndices(vbo, edgePositions, edgeCount, edgeToVertexMapping,
                                                     vertexIndex1, newPosition1, vertexIndex2, newPosition2);

        if (result != null && result.isSuccessful()) {
            logger.trace("Updated {} edges using {} strategy",
                result.getUpdatedCount(), result.getStrategy());
        }
    }

    // ========================================
    // Edge Subdivision Operations
    // ========================================

    /**
     * Subdivide the currently hovered edge at its midpoint.
     * Creates a new vertex at the midpoint and replaces the hovered edge with two new edges.
     *
     * <p>This operation:
     * <ul>
     *   <li>Validates a valid edge is hovered</li>
     *   <li>Performs subdivision through MeshManager</li>
     *   <li>Updates edge data structures (positions, count, mapping)</li>
     *   <li>Updates vertex renderer with new vertex</li>
     *   <li>Rebuilds edge VBO</li>
     *   <li>Clears hover state (edge indices have shifted)</li>
     * </ul>
     *
     * <p><b>Prerequisites:</b> {@link #buildEdgeToVertexMapping} must have been called.
     *
     * @param vertexRenderer VertexRenderer to update with new vertex
     * @return Index of newly created vertex, or -1 if subdivision failed
     */
    public int subdivideHoveredEdge(VertexRenderer vertexRenderer) {
        if (!initialized) {
            logger.warn("Cannot subdivide: edge renderer not initialized");
            return -1;
        }

        if (hoveredEdgeIndex < 0 || hoveredEdgeIndex >= edgeCount) {
            logger.warn("Cannot subdivide: no valid hovered edge (index: {}, count: {})",
                       hoveredEdgeIndex, edgeCount);
            return -1;
        }

        if (edgeToVertexMapping == null) {
            logger.warn("Cannot subdivide: edge-to-vertex mapping not built");
            return -1;
        }

        if (vertexRenderer == null) {
            logger.warn("Cannot subdivide: vertex renderer is null");
            return -1;
        }

        // Get current vertex data from renderer
        float[] vertexPositions = vertexRenderer.getAllVertexPositions();
        int vertexCount = vertexRenderer.getVertexCount();

        if (vertexPositions == null || vertexCount <= 0) {
            logger.warn("Cannot subdivide: no vertex data available");
            return -1;
        }

        // Perform subdivision through MeshManager
        MeshEdgeSubdivider.SubdivisionResult result = meshManager.subdivideEdge(
            hoveredEdgeIndex, edgePositions, edgeCount,
            vertexPositions, vertexCount, edgeToVertexMapping
        );

        if (result == null || !result.isSuccessful()) {
            logger.warn("Edge subdivision failed for edge {}", hoveredEdgeIndex);
            return -1;
        }

        // Apply subdivision result to edge renderer
        applySubdivisionResult(result);

        // Update vertex renderer with new vertex
        vertexRenderer.applyVertexAddition(result.getNewVertexPositions(), result.getNewVertexCount());

        int newVertexIndex = result.getNewVertexIndex();
        logger.info("Subdivided edge {} at midpoint, created vertex {} (edges: {} -> {})",
                   hoveredEdgeIndex, newVertexIndex, edgeCount - 1, edgeCount);

        return newVertexIndex;
    }

    /**
     * Apply subdivision result to edge renderer state.
     * Updates edge positions, count, mapping, and rebuilds VBO.
     *
     * @param result SubdivisionResult containing new edge data
     */
    private void applySubdivisionResult(MeshEdgeSubdivider.SubdivisionResult result) {
        // Update edge data structures
        this.edgePositions = result.getNewEdgePositions();
        this.edgeCount = result.getNewEdgeCount();
        this.edgeToVertexMapping = result.getNewEdgeToVertexMapping();

        // Rebuild edge VBO with new data
        MeshEdgeBufferUpdater.UpdateResult bufferResult =
            meshManager.updateEdgeBuffer(vbo, edgePositions, edgeColor);

        if (bufferResult == null) {
            logger.warn("Failed to rebuild edge VBO after subdivision");
        }

        // Clear hover state - edge indices have shifted
        hoveredEdgeIndex = NO_EDGE_SELECTED;

        // Clear selection state - selected edge index may be invalid now
        selectedEdgeIndex = NO_EDGE_SELECTED;

        logger.debug("Applied subdivision result: {} edges, hover/selection cleared", edgeCount);
    }

}
