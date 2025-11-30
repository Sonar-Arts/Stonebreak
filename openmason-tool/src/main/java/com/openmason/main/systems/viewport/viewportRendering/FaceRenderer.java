package com.openmason.main.systems.viewport.viewportRendering;

import com.openmason.main.systems.viewport.viewportRendering.face.FaceExtractor;
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
 * Renders model faces as colored semi-transparent quads, similar to Blender's face mode.
 * Face positions are extracted by FaceExtractor; this class handles rendering.
 * Supports selection highlighting (white), hover highlighting (orange), and modification tracking (yellow).
 * Mirrors EdgeRenderer and VertexRenderer pattern for consistency.
 */
public class FaceRenderer {

    private static final Logger logger = LoggerFactory.getLogger(FaceRenderer.class);

    // OpenGL resources
    private int vao = 0;
    private int vbo = 0;
    private int faceCount = 0;
    private boolean initialized = false;

    // Rendering state
    private boolean enabled = false;
    private final Vector3f defaultFaceColor = new Vector3f(0.2f, 0.4f, 0.8f); // Semi-transparent blue
    private final float defaultFaceAlpha = 0.3f;
    private final Vector3f hoveredFaceColor = new Vector3f(1.0f, 0.6f, 0.0f); // Orange
    private final float hoveredFaceAlpha = 0.5f;
    private final Vector3f selectedFaceColor = new Vector3f(1.0f, 1.0f, 1.0f); // White
    private final float selectedFaceAlpha = 0.5f;
    private final Vector3f modifiedFaceColor = new Vector3f(1.0f, 1.0f, 0.0f); // Yellow
    private final float modifiedFaceAlpha = 0.5f;

    // Hover state
    private int hoveredFaceIndex = -1; // -1 means no face is hovered
    private float[] facePositions = null; // Store positions for hit testing (12 floats per face)

    // Selection state
    private int selectedFaceIndex = -1; // -1 means no face is selected
    private Set<Integer> modifiedFaces = new HashSet<>(); // Indices of modified faces

    // Face extraction (Single Responsibility)
    private final FaceExtractor faceExtractor = new FaceExtractor();

    /**
     * Initialize the face renderer.
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
            // Stride = 7 floats (3 for position, 4 for color RGBA)
            int stride = 7 * Float.BYTES;

            // Position attribute (location = 0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
            glEnableVertexAttribArray(0);

            // Color attribute (location = 1) - note: 4 components for RGBA
            glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, 3 * Float.BYTES);
            glEnableVertexAttribArray(1);

            // Unbind
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);

            initialized = true;

        } catch (Exception e) {
            logger.error("Failed to initialize FaceRenderer", e);
            cleanup();
            throw new RuntimeException("FaceRenderer initialization failed", e);
        }
    }

    /**
     * Update face data from a collection of model parts with transformation.
     * Generic method that works with ANY model type.
     */
    public void updateFaceData(Collection<ModelDefinition.ModelPart> parts, Matrix4f transformMatrix) {
        if (!initialized) {
            logger.warn("FaceRenderer not initialized, cannot update face data");
            return;
        }

        if (parts == null || parts.isEmpty()) {
            faceCount = 0;
            facePositions = null;
            return;
        }

        try {
            // Extract faces using FaceExtractor (Single Responsibility)
            facePositions = faceExtractor.extractFaces(parts, transformMatrix);

            // Face positions are [v0(x,y,z), v1(x,y,z), v2(x,y,z), v3(x,y,z), ...] (4 vertices per face)
            faceCount = facePositions.length / 12; // 12 floats per face (4 vertices × 3 coords)

            // Create interleaved vertex data (position + color RGBA) for each vertex
            int vertexCount = faceCount * 4; // 4 vertices per face
            float[] vertexData = new float[vertexCount * 7]; // 3 for position, 4 for color RGBA

            for (int faceIndex = 0; faceIndex < faceCount; faceIndex++) {
                // Determine color based on state (selected > modified > hovered > default)
                Vector3f color;
                float alpha;

                if (faceIndex == selectedFaceIndex) {
                    color = selectedFaceColor;
                    alpha = selectedFaceAlpha;
                } else if (modifiedFaces.contains(faceIndex)) {
                    color = modifiedFaceColor;
                    alpha = modifiedFaceAlpha;
                } else if (faceIndex == hoveredFaceIndex) {
                    color = hoveredFaceColor;
                    alpha = hoveredFaceAlpha;
                } else {
                    color = defaultFaceColor;
                    alpha = defaultFaceAlpha;
                }

                // Copy position and color for all 4 vertices of this face
                int facePosIndex = faceIndex * 12; // 12 floats per face
                int faceDataIndex = faceIndex * 4 * 7; // 4 vertices × 7 floats

                for (int v = 0; v < 4; v++) {
                    int posIndex = facePosIndex + (v * 3);
                    int dataIndex = faceDataIndex + (v * 7);

                    // Copy position
                    vertexData[dataIndex + 0] = facePositions[posIndex + 0];
                    vertexData[dataIndex + 1] = facePositions[posIndex + 1];
                    vertexData[dataIndex + 2] = facePositions[posIndex + 2];

                    // Set color RGBA
                    vertexData[dataIndex + 3] = color.x;
                    vertexData[dataIndex + 4] = color.y;
                    vertexData[dataIndex + 5] = color.z;
                    vertexData[dataIndex + 6] = alpha;
                }
            }

            // Upload to GPU
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, vertexData, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

        } catch (Exception e) {
            logger.error("Error updating face data", e);
        }
    }

    /**
     * Handle mouse movement for face hover detection.
     * Follows the same pattern as EdgeRenderer.handleMouseMove().
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

        if (facePositions == null || faceCount == 0) {
            return;
        }

        // Detect hovered face using screen-space point-in-quad detection
        int newHoveredFace = FaceHoverDetector.detectHoveredFace(
            mouseX, mouseY,
            viewportWidth, viewportHeight,
            viewMatrix, projectionMatrix, modelMatrix,
            facePositions,
            faceCount
        );

        // Update hover state if changed (same pattern as edges/vertices)
        if (newHoveredFace != hoveredFaceIndex) {
            int previousHover = hoveredFaceIndex;
            hoveredFaceIndex = newHoveredFace;

            logger.debug("Face hover changed: {} -> {} (total faces: {})",
                        previousHover, hoveredFaceIndex, faceCount);
        }
    }

    /**
     * Render faces as semi-transparent quads with hover highlighting.
     * KISS: Simple rendering with state-based coloring (same pattern as edges/vertices).
     *
     * @param shader The shader program to use
     * @param context The render context
     * @param modelMatrix The model transformation matrix (for gizmo transforms)
     */
    public void render(ShaderProgram shader, RenderContext context, Matrix4f modelMatrix) {
        if (!initialized) {
            logger.warn("FaceRenderer not initialized");
            return;
        }

        if (!enabled) {
            return;
        }

        if (faceCount == 0) {
            return;
        }

        try {
            // Use shader
            shader.use();

            // Calculate MVP matrix with model transform (faces are in model space)
            Matrix4f viewMatrix = context.getCamera().getViewMatrix();
            Matrix4f projectionMatrix = context.getCamera().getProjectionMatrix();
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);

            // Upload MVP matrix
            shader.setMat4("uMVPMatrix", mvpMatrix);

            // Enable blending for transparency
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            // Save and modify depth function to ensure faces are visible on surfaces
            int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
            glDepthFunc(GL_LEQUAL);

            // Enable polygon offset to prevent z-fighting
            // This pushes the face overlay slightly toward the camera
            glEnable(GL_POLYGON_OFFSET_FILL);
            glPolygonOffset(-1.0f, -1.0f); // Negative values pull toward camera

            // Bind VAO
            glBindVertexArray(vao);

            // ONLY render hovered and selected faces (not all faces)
            // This prevents covering the entire model with blue overlays
            float hoverIntensity = 2.0f;      // Bright orange for hover
            float selectedIntensity = 2.5f;   // Brightest white for selection

            // Render hovered face (if any and not selected)
            if (hoveredFaceIndex >= 0 && hoveredFaceIndex != selectedFaceIndex) {
                shader.setFloat("uIntensity", hoverIntensity);
                glDrawArrays(GL_TRIANGLE_FAN, hoveredFaceIndex * 4, 4);
                logger.trace("Rendering hovered face {}", hoveredFaceIndex);
            }

            // Render selected face last (always on top)
            if (selectedFaceIndex >= 0) {
                shader.setFloat("uIntensity", selectedIntensity);
                glDrawArrays(GL_TRIANGLE_FAN, selectedFaceIndex * 4, 4);
                logger.trace("Rendering selected face {}", selectedFaceIndex);
            }

            glBindVertexArray(0);

            // Disable polygon offset
            glDisable(GL_POLYGON_OFFSET_FILL);

            // Disable blending
            glDisable(GL_BLEND);

            // Restore previous depth function
            glDepthFunc(prevDepthFunc);

        } catch (Exception e) {
            logger.error("Error rendering faces", e);
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
        faceCount = 0;
        initialized = false;
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

    /**
     * Get the index of the currently hovered face.
     *
     * @return hovered face index, or -1 if no face is hovered
     */
    public int getHoveredFaceIndex() {
        return hoveredFaceIndex;
    }

    /**
     * Get the index of the currently selected face.
     *
     * @return selected face index, or -1 if no face is selected
     */
    public int getSelectedFaceIndex() {
        return selectedFaceIndex;
    }

    /**
     * Set the selected face by index.
     *
     * @param faceIndex the index of the face to select, or -1 to clear selection
     */
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

    /**
     * Clear the face selection.
     */
    public void clearSelection() {
        this.selectedFaceIndex = -1;
    }

    /**
     * Set which faces are modified (unsaved changes).
     *
     * @param indices Set of modified face indices
     */
    public void setModifiedFaces(Set<Integer> indices) {
        if (indices == null) {
            modifiedFaces.clear();
        } else {
            modifiedFaces = new HashSet<>(indices);
        }
        logger.debug("Modified faces updated: {} faces marked as modified", modifiedFaces.size());
    }

    /**
     * Get the set of modified face indices.
     *
     * @return Copy of modified faces set
     */
    public Set<Integer> getModifiedFaces() {
        return new HashSet<>(modifiedFaces);
    }

    /**
     * Get the 4 vertex positions of a face.
     *
     * @param faceIndex the index of the face
     * @return array containing 4 vertices [v0, v1, v2, v3], or null if invalid index
     */
    public Vector3f[] getFaceVertices(int faceIndex) {
        if (faceIndex < 0 || faceIndex >= faceCount) {
            return null;
        }

        if (facePositions == null) {
            return null;
        }

        int posIndex = faceIndex * 12; // 12 floats per face (4 vertices × 3 coords)

        if (posIndex + 11 >= facePositions.length) {
            return null;
        }

        Vector3f[] vertices = new Vector3f[4];
        for (int v = 0; v < 4; v++) {
            int vertexIndex = posIndex + (v * 3);
            vertices[v] = new Vector3f(
                facePositions[vertexIndex + 0],
                facePositions[vertexIndex + 1],
                facePositions[vertexIndex + 2]
            );
        }

        return vertices;
    }

    /**
     * Update the position of a specific face.
     *
     * @param faceIndex the index of the face to update
     * @param newVertices array of 4 new vertex positions
     */
    public void updateFacePosition(int faceIndex, Vector3f[] newVertices) {
        if (!initialized) {
            logger.warn("Cannot update face position: renderer not initialized");
            return;
        }

        if (faceIndex < 0 || faceIndex >= faceCount) {
            logger.warn("Invalid face index: {}", faceIndex);
            return;
        }

        if (facePositions == null || newVertices == null || newVertices.length != 4) {
            logger.warn("Cannot update face position: null data or invalid vertex count");
            return;
        }

        try {
            int posIndex = faceIndex * 12; // 12 floats per face

            // Update in-memory array
            for (int v = 0; v < 4; v++) {
                int vertexIndex = posIndex + (v * 3);
                facePositions[vertexIndex + 0] = newVertices[v].x;
                facePositions[vertexIndex + 1] = newVertices[v].y;
                facePositions[vertexIndex + 2] = newVertices[v].z;
            }

            // Update VBO (interleaved: position + color)
            // Each vertex: 7 floats (3 position + 4 color RGBA)
            glBindBuffer(GL_ARRAY_BUFFER, vbo);

            for (int v = 0; v < 4; v++) {
                int dataIndex = (faceIndex * 4 + v) * 7; // Vertex index in VBO
                float[] posData = new float[] {
                    newVertices[v].x,
                    newVertices[v].y,
                    newVertices[v].z
                };
                glBufferSubData(GL_ARRAY_BUFFER, dataIndex * Float.BYTES, posData);
            }

            glBindBuffer(GL_ARRAY_BUFFER, 0);

            logger.trace("Updated face {} position", faceIndex);

        } catch (Exception e) {
            logger.error("Error updating face position", e);
        }
    }

    /**
     * Update all faces that have a vertex matching the old position.
     * Searches through ALL face vertices and updates any that were at the old vertex position.
     * This is used when vertices/edges are dragged to keep faces synchronized.
     *
     * @param oldPosition The original position of the vertex before dragging
     * @param newPosition The new position of the vertex after dragging
     */
    public void updateFacesByVertexPosition(Vector3f oldPosition, Vector3f newPosition) {
        if (!initialized) {
            logger.warn("Cannot update face vertices: renderer not initialized");
            return;
        }

        if (facePositions == null || faceCount == 0) {
            logger.warn("Cannot update face vertices: no face data");
            return;
        }

        if (oldPosition == null || newPosition == null) {
            logger.warn("Cannot update face vertices: positions are null");
            return;
        }

        try {
            float epsilon = 0.0001f; // Same epsilon as vertex/edge renderers for consistency
            int updatedCount = 0;

            glBindBuffer(GL_ARRAY_BUFFER, vbo);

            // Search through ALL face vertices (4 vertices per face)
            int totalVertices = faceCount * 4;
            for (int vertexIdx = 0; vertexIdx < totalVertices; vertexIdx++) {
                int posIndex = vertexIdx * 3; // 3 floats per position (x,y,z)

                // Check if this vertex matches the old position
                if (posIndex + 2 < facePositions.length) {
                    Vector3f vertexPos = new Vector3f(
                            facePositions[posIndex + 0],
                            facePositions[posIndex + 1],
                            facePositions[posIndex + 2]
                    );

                    if (vertexPos.distance(oldPosition) < epsilon) {
                        // Found a matching vertex - update it!

                        // Update VBO (interleaved: position + color)
                        int dataIndex = vertexIdx * 7; // 7 floats per vertex (x,y,z, r,g,b,a)
                        long offset = dataIndex * Float.BYTES;

                        float[] positionData = new float[] {
                            newPosition.x,
                            newPosition.y,
                            newPosition.z
                        };
                        glBufferSubData(GL_ARRAY_BUFFER, offset, positionData);

                        // Update in-memory array
                        facePositions[posIndex + 0] = newPosition.x;
                        facePositions[posIndex + 1] = newPosition.y;
                        facePositions[posIndex + 2] = newPosition.z;

                        updatedCount++;
                    }
                }
            }

            glBindBuffer(GL_ARRAY_BUFFER, 0);

            logger.trace("Updated {} face vertices from ({}, {}, {}) to ({}, {}, {})",
                    updatedCount,
                    String.format("%.2f", oldPosition.x),
                    String.format("%.2f", oldPosition.y),
                    String.format("%.2f", oldPosition.z),
                    String.format("%.2f", newPosition.x),
                    String.format("%.2f", newPosition.y),
                    String.format("%.2f", newPosition.z));

        } catch (Exception e) {
            logger.error("Error updating face vertices", e);
        }
    }
}
