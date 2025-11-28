package com.openmason.main.systems.viewport.viewportRendering;

import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexExtractor;
import com.openmason.main.systems.viewport.shaders.ShaderProgram;
import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

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
    private float[] vertexPositions = null; // Store positions for hit testing

    // Selection state
    private int selectedVertexIndex = -1; // -1 means no vertex is selected
    private Set<Integer> modifiedVertices = new HashSet<>(); // Indices of modified vertices

    // Vertex extraction (Single Responsibility)
    private final VertexExtractor vertexExtractor = new VertexExtractor();

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
     */
    public void updateVertexData(Collection<ModelDefinition.ModelPart> parts, Matrix4f transformMatrix) {
        if (!initialized) {
            logger.warn("VertexRenderer not initialized, cannot update vertex data");
            return;
        }

        if (parts == null || parts.isEmpty()) {
            vertexCount = 0;
            vertexPositions = null;
            return;
        }

        try {
            // Extract vertices using VertexExtractor (Single Responsibility)
            float[] positions = vertexExtractor.extractVertices(parts, transformMatrix);

            // Store positions for hit testing
            vertexPositions = positions;
            vertexCount = positions.length / 3;

            // Create interleaved vertex data (position + color)
            // Color varies based on state: selected (white), modified (yellow), default (orange)
            float[] vertexData = new float[vertexCount * 6]; // 3 for position, 3 for color

            for (int i = 0; i < vertexCount; i++) {
                int posIndex = i * 3;
                int dataIndex = i * 6;

                // Copy position
                vertexData[dataIndex + 0] = positions[posIndex + 0];
                vertexData[dataIndex + 1] = positions[posIndex + 1];
                vertexData[dataIndex + 2] = positions[posIndex + 2];

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
     * Handle mouse movement for vertex hover detection.
     * Follows the same pattern as GizmoRenderer.handleMouseMove().
     *
     * @param mouseX Mouse X coordinate in viewport space
     * @param mouseY Mouse Y coordinate in viewport space
     * @param viewMatrix Camera view matrix
     * @param projectionMatrix Camera projection matrix
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     */
    public void handleMouseMove(float mouseX, float mouseY,
                               Matrix4f viewMatrix, Matrix4f projectionMatrix,
                               int viewportWidth, int viewportHeight) {
        if (!initialized || !enabled) {
            return;
        }

        if (vertexPositions == null || vertexCount == 0) {
            return;
        }

        // Detect hovered vertex using screen-space point size detection
        int newHoveredVertex = VertexHoverDetector.detectHoveredVertex(
            mouseX, mouseY,
            viewportWidth, viewportHeight,
            viewMatrix, projectionMatrix,
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
     */
    public void render(ShaderProgram shader, RenderContext context) {
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

            // Calculate MVP matrix (model is identity since vertices are already transformed)
            Matrix4f viewMatrix = context.getCamera().getViewMatrix();
            Matrix4f projectionMatrix = context.getCamera().getProjectionMatrix();
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix);

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
     * Update a single vertex position (for live preview during drag).
     * This modifies the GPU buffer directly without re-extracting all vertices.
     *
     * @param index Vertex index to update
     * @param position New world-space position
     */
    public void updateVertexPosition(int index, Vector3f position) {
        if (!initialized) {
            logger.warn("Cannot update vertex position: renderer not initialized");
            return;
        }

        if (index < 0 || index >= vertexCount) {
            logger.warn("Invalid vertex index for position update: {} (max: {})", index, vertexCount - 1);
            return;
        }

        if (position == null) {
            logger.warn("Cannot update vertex position: position is null");
            return;
        }

        try {
            // Update in-memory position array
            int posIndex = index * 3;
            vertexPositions[posIndex + 0] = position.x;
            vertexPositions[posIndex + 1] = position.y;
            vertexPositions[posIndex + 2] = position.z;

            // Update VBO (only the position part of this vertex)
            // Vertex data is interleaved: [x,y,z, r,g,b, x,y,z, r,g,b, ...]
            int dataIndex = index * 6; // 6 floats per vertex
            long offset = dataIndex * Float.BYTES;

            glBindBuffer(GL_ARRAY_BUFFER, vbo);

            // Create temporary array with just position data
            float[] positionData = new float[] { position.x, position.y, position.z };

            // Update only position floats in VBO (leave color unchanged)
            glBufferSubData(GL_ARRAY_BUFFER, offset, positionData);

            glBindBuffer(GL_ARRAY_BUFFER, 0);

            logger.trace("Updated vertex {} position to ({}, {}, {})",
                    index,
                    String.format("%.2f", position.x),
                    String.format("%.2f", position.y),
                    String.format("%.2f", position.z));

        } catch (Exception e) {
            logger.error("Error updating vertex position for index {}", index, e);
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
