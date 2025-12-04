package com.openmason.main.systems.viewport.viewportRendering;

import com.openmason.main.systems.viewport.viewportRendering.common.IGeometryExtractor;
import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexExtractor;
import com.openmason.main.systems.viewport.shaders.ShaderProgram;
import com.stonebreak.model.ModelDefinition;
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
  * Vertex positions are extracted by VertexExtractor; this class handles rendering.
  * Supports selection highlighting (white) and modification tracking (yellow).
  */
public class VertexRenderer {

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

    // Selection state
    private int selectedVertexIndex = -1; // -1 means no vertex is selected
    private Set<Integer> modifiedVertices = new HashSet<>(); // Indices of modified vertices

    // Mesh instance mapping (fixes vertex duplication bug)
    private Map<Integer, List<Integer>> uniqueToMeshMapping = new HashMap<>(); // Maps unique vertex index to mesh vertex indices
    private float[] allMeshVertices = null; // Store ALL mesh vertices for mapping

    // Vertex extraction (Single Responsibility) - uses interface for polymorphism
    private final IGeometryExtractor geometryExtractor = new VertexExtractor();
    private final VertexExtractor vertexExtractor = new VertexExtractor(); // Keep for unique vertices method

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
     * Update vertex data from a collection of model parts with transformation.
     * Generic method that works with ANY model type - cow, cube, sheep, future models.
     * Extracts UNIQUE vertices only to prevent duplication bug.
     */
    public void updateVertexData(Collection<ModelDefinition.ModelPart> parts, Matrix4f transformMatrix) {
        if (!initialized) {
            logger.warn("VertexRenderer not initialized, cannot update vertex data");
            return;
        }

        if (parts == null || parts.isEmpty()) {
            vertexCount = 0;
            vertexPositions = null;
            allMeshVertices = null;
            uniqueToMeshMapping.clear();
            return;
        }

        try {
            // Extract ALL mesh vertices (24 for cube) for mapping - uses interface method
            allMeshVertices = geometryExtractor.extractGeometry(parts, transformMatrix);

            // Extract UNIQUE vertices only (8 for cube) for rendering - uses specific method
            float[] uniquePositions = vertexExtractor.extractUniqueVertices(parts, transformMatrix);

            // Store unique positions for hit testing
            vertexPositions = uniquePositions;
            vertexCount = uniquePositions.length / 3;

            // Build mapping from unique vertex index to mesh vertex indices
            buildUniqueToMeshMapping(uniquePositions, allMeshVertices);

            // Create interleaved vertex data (position + color) using UNIQUE vertices
            // Color varies based on state: selected (white), modified (yellow), default (orange)
            float[] vertexData = new float[vertexCount * 6]; // 3 for position, 3 for color

            for (int i = 0; i < vertexCount; i++) {
                int posIndex = i * 3;
                int dataIndex = i * 6;

                // Copy position from unique vertices
                vertexData[dataIndex + 0] = uniquePositions[posIndex + 0];
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

        } catch (Exception e) {
            logger.error("Error updating vertex data", e);
        }
    }

    /**
     * Build mapping from unique vertex indices to mesh vertex indices.
     * For a cube: Each of the 8 unique corner vertices maps to 3 mesh vertex instances.
     *
     * @param uniquePositions Array of unique vertex positions
     * @param meshPositions Array of ALL mesh vertex positions
     */
    private void buildUniqueToMeshMapping(float[] uniquePositions, float[] meshPositions) {
        uniqueToMeshMapping.clear();

        float epsilon = 0.0001f; // Same epsilon as VertexExtractor

        // For each mesh vertex, find its matching unique vertex
        int meshVertexCount = meshPositions.length / 3;
        int uniqueVertexCount = uniquePositions.length / 3;

        for (int meshIndex = 0; meshIndex < meshVertexCount; meshIndex++) {
            int meshPosIndex = meshIndex * 3;
            Vector3f meshPos = new Vector3f(
                    meshPositions[meshPosIndex + 0],
                    meshPositions[meshPosIndex + 1],
                    meshPositions[meshPosIndex + 2]
            );

            // Find which unique vertex this mesh vertex matches
            for (int uniqueIndex = 0; uniqueIndex < uniqueVertexCount; uniqueIndex++) {
                int uniquePosIndex = uniqueIndex * 3;
                Vector3f uniquePos = new Vector3f(
                        uniquePositions[uniquePosIndex + 0],
                        uniquePositions[uniquePosIndex + 1],
                        uniquePositions[uniquePosIndex + 2]
                );

                if (meshPos.distance(uniquePos) < epsilon) {
                    // Found matching unique vertex - add to mapping
                    uniqueToMeshMapping.computeIfAbsent(uniqueIndex, k -> new ArrayList<>()).add(meshIndex);
                    break;
                }
            }
        }

        logger.debug("Built vertex mapping: {} unique vertices → {} mesh vertices",
                uniqueVertexCount, meshVertexCount);

        // Log mapping for debugging (only for small models like cube)
        if (uniqueVertexCount <= 10) {
            for (Map.Entry<Integer, List<Integer>> entry : uniqueToMeshMapping.entrySet()) {
                logger.trace("Unique vertex {} → mesh vertices {}",
                        entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Handle mouse movement for vertex hover detection.
     * Follows the same pattern as GizmoRenderer.handleMouseMove().
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
                if (i == hoveredVertexIndex || i == selectedVertexIndex) {
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
            if (hoveredVertexIndex >= 0 && hoveredVertexIndex != selectedVertexIndex) {
                shader.setFloat("uIntensity", hoverIntensity);
                glDrawArrays(GL_POINTS, hoveredVertexIndex, 1);
            }

            // Render selected vertex last (always on top) with larger point size
            if (selectedVertexIndex >= 0) {
                glPointSize(selectedPointSize);
                shader.setFloat("uIntensity", selectedIntensity);
                glDrawArrays(GL_POINTS, selectedVertexIndex, 1);
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

    /**
     * Get the number of vertices.
     * @return Number of vertices
     */
    public int getVertexCount() {
        return vertexCount;
    }

    /**
     * Set the selected vertex index.
     * @param index Vertex index to select, or -1 to clear selection
     */
    public void setSelectedVertex(int index) {
        if (index < -1 || (index >= 0 && index >= vertexCount)) {
            logger.warn("Invalid vertex index for selection: {} (max: {})", index, vertexCount - 1);
            return;
        }

        if (selectedVertexIndex != index) {
            selectedVertexIndex = index;
            logger.debug("Selected vertex set to: {}", index);
        }
    }

    /**
     * Get the currently selected vertex index.
     * @return Selected vertex index, or -1 if no selection
     */
    public int getSelectedVertexIndex() {
        return selectedVertexIndex;
    }

    /**
     * Clear the current selection.
     */
    public void clearSelection() {
        selectedVertexIndex = -1;
        logger.debug("Vertex selection cleared");
    }

    /**
     * Set which vertices are modified (unsaved changes).
     * @param indices Set of modified vertex indices
     */
    public void setModifiedVertices(Set<Integer> indices) {
        if (indices == null) {
            modifiedVertices.clear();
        } else {
            modifiedVertices = new HashSet<>(indices);
        }
        logger.debug("Modified vertices updated: {} vertices marked as modified", modifiedVertices.size());
    }

    /**
     * Get the set of modified vertex indices.
     * @return Copy of modified vertices set
     */
    public Set<Integer> getModifiedVertices() {
        return new HashSet<>(modifiedVertices);
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

        if (uniqueIndex < 0 || uniqueIndex >= vertexCount) {
            logger.warn("Invalid vertex index for position update: {} (max: {})", uniqueIndex, vertexCount - 1);
            return;
        }

        if (position == null) {
            logger.warn("Cannot update vertex position: position is null");
            return;
        }

        try {
            // Update in-memory position array (unique vertices)
            int posIndex = uniqueIndex * 3;
            vertexPositions[posIndex + 0] = position.x;
            vertexPositions[posIndex + 1] = position.y;
            vertexPositions[posIndex + 2] = position.z;

            // Update VBO for the unique vertex display
            int dataIndex = uniqueIndex * 6; // 6 floats per vertex (x,y,z, r,g,b)
            long offset = dataIndex * Float.BYTES;

            glBindBuffer(GL_ARRAY_BUFFER, vbo);

            // Create temporary array with just position data
            float[] positionData = new float[] { position.x, position.y, position.z };

            // Update only position floats in VBO (leave color unchanged)
            glBufferSubData(GL_ARRAY_BUFFER, offset, positionData);

            // CRITICAL FIX: Update ALL mesh instances of this unique vertex
            // This prevents the "cloning" bug where old vertex stays visible
            List<Integer> meshIndices = uniqueToMeshMapping.get(uniqueIndex);
            if (meshIndices != null && allMeshVertices != null) {
                for (Integer meshIndex : meshIndices) {
                    int meshPosIndex = meshIndex * 3;
                    if (meshPosIndex + 2 < allMeshVertices.length) {
                        allMeshVertices[meshPosIndex + 0] = position.x;
                        allMeshVertices[meshPosIndex + 1] = position.y;
                        allMeshVertices[meshPosIndex + 2] = position.z;
                    }
                }
                logger.trace("Updated {} mesh instances for unique vertex {}",
                        meshIndices.size(), uniqueIndex);
            }

            glBindBuffer(GL_ARRAY_BUFFER, 0);

            logger.trace("Updated unique vertex {} position to ({}, {}, {})",
                    uniqueIndex,
                    String.format("%.2f", position.x),
                    String.format("%.2f", position.y),
                    String.format("%.2f", position.z));

        } catch (Exception e) {
            logger.error("Error updating vertex position for index {}", uniqueIndex, e);
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
            vertexPositions[posIndex + 0],
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
     * Update all vertices that match an old position to a new position.
     * Used for edge translation to update connected vertices.
     *
     * @param oldPosition The original vertex position
     * @param newPosition The new vertex position
     */
    public void updateVerticesByPosition(Vector3f oldPosition, Vector3f newPosition) {
        if (!initialized || vertexPositions == null || vertexCount == 0) {
            logger.warn("Cannot update vertices: renderer not initialized or no vertices");
            return;
        }

        if (oldPosition == null || newPosition == null) {
            logger.warn("Cannot update vertices: positions are null");
            return;
        }

        try {
            float epsilon = 0.0001f;
            int updatedCount = 0;

            for (int i = 0; i < vertexCount; i++) {
                int posIndex = i * 3;

                Vector3f vertexPos = new Vector3f(
                    vertexPositions[posIndex + 0],
                    vertexPositions[posIndex + 1],
                    vertexPositions[posIndex + 2]
                );

                if (vertexPos.distance(oldPosition) < epsilon) {
                    // Found matching vertex - update it
                    updateVertexPosition(i, newPosition);
                    updatedCount++;
                }
            }

            logger.trace("Updated {} vertices from ({}, {}, {}) to ({}, {}, {})",
                    updatedCount,
                    String.format("%.2f", oldPosition.x),
                    String.format("%.2f", oldPosition.y),
                    String.format("%.2f", oldPosition.z),
                    String.format("%.2f", newPosition.x),
                    String.format("%.2f", newPosition.y),
                    String.format("%.2f", newPosition.z));

        } catch (Exception e) {
            logger.error("Error updating vertices by position", e);
        }
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
