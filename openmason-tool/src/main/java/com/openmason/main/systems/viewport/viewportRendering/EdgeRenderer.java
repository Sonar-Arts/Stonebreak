package com.openmason.main.systems.viewport.viewportRendering;

import com.openmason.main.systems.viewport.viewportRendering.edge.EdgeExtractor;
import com.openmason.main.systems.viewport.shaders.ShaderProgram;
import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.Collection;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders model edges as lines, complementing VertexRenderer.
 * Mirrors VertexRenderer pattern for consistency.
 */
public class EdgeRenderer {

    private static final Logger logger = LoggerFactory.getLogger(EdgeRenderer.class);

    // OpenGL resources
    private int vao = 0;
    private int vbo = 0;
    private int edgeCount = 0;
    private boolean initialized = false;

    // Rendering state
    private boolean enabled = false;
    private float lineWidth = 1.5f;
    private final Vector3f edgeColor = new Vector3f(0.0f, 0.0f, 0.0f); // Black
    private final Vector3f hoverEdgeColor = new Vector3f(1.0f, 0.6f, 0.0f); // Orange for hover (same as vertices)

    // Hover state
    private int hoveredEdgeIndex = -1; // -1 means no edge is hovered
    private float[] edgePositions = null; // Store positions for hit testing

    // Edge extraction (Single Responsibility)
    private final EdgeExtractor edgeExtractor = new EdgeExtractor();

    /**
     * Initialize the edge renderer.
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
            logger.error("Failed to initialize EdgeRenderer", e);
            cleanup();
            throw new RuntimeException("EdgeRenderer initialization failed", e);
        }
    }

    /**
     * Update edge data from a collection of model parts with transformation.
     * Generic method that works with ANY model type.
     */
    public void updateEdgeData(Collection<ModelDefinition.ModelPart> parts, Matrix4f transformMatrix) {
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
            // Extract edges using EdgeExtractor (Single Responsibility)
            edgePositions = edgeExtractor.extractEdges(parts, transformMatrix);

            // Edge positions are [x1,y1,z1, x2,y2,z2, ...] (2 endpoints per edge)
            edgeCount = edgePositions.length / 6; // 6 floats per edge (2 endpoints × 3 coords)

            // Create interleaved vertex data (position + color) for each endpoint
            int vertexCount = edgeCount * 2; // 2 endpoints per edge
            float[] vertexData = new float[vertexCount * 6]; // 3 for position, 3 for color

            for (int i = 0; i < vertexCount; i++) {
                int posIndex = i * 3;
                int dataIndex = i * 6;

                // Copy position
                vertexData[dataIndex + 0] = edgePositions[posIndex + 0];
                vertexData[dataIndex + 1] = edgePositions[posIndex + 1];
                vertexData[dataIndex + 2] = edgePositions[posIndex + 2];

                // Set color (black - same pattern as vertices)
                vertexData[dataIndex + 3] = edgeColor.x;
                vertexData[dataIndex + 4] = edgeColor.y;
                vertexData[dataIndex + 5] = edgeColor.z;
            }

            // Upload to GPU
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, vertexData, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

        } catch (Exception e) {
            logger.error("Error updating edge data", e);
        }
    }

    /**
     * Handle mouse movement for edge hover detection.
     * Follows the same pattern as VertexRenderer.handleMouseMove().
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

        if (edgePositions == null || edgeCount == 0) {
            return;
        }

        // Detect hovered edge using screen-space point-to-line distance detection
        // Pass model matrix so edges in model space are properly transformed
        int newHoveredEdge = EdgeHoverDetector.detectHoveredEdge(
            mouseX, mouseY,
            viewportWidth, viewportHeight,
            viewMatrix, projectionMatrix, modelMatrix,
            edgePositions,
            edgeCount,
            lineWidth  // Use actual line width for accurate detection
        );

        // Update hover state if changed (same pattern as vertices)
        if (newHoveredEdge != hoveredEdgeIndex) {
            int previousHover = hoveredEdgeIndex;
            hoveredEdgeIndex = newHoveredEdge;

            logger.debug("Edge hover changed: {} -> {} (total edges: {})",
                        previousHover, hoveredEdgeIndex, edgeCount);
        }
    }

    /**
     * Render edges as lines with hover highlighting.
     * KISS: Simple rendering with intensity-based highlighting (same pattern as vertices).
     *
     * @param shader The shader program to use
     * @param context The render context
     * @param modelMatrix The model transformation matrix (for gizmo transforms)
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
            shader.setFloat("uIntensity", 1.0f);

            // Render all edges at once with appropriate color
            if (hoveredEdgeIndex >= 0) {
                // Render non-hovered edges normally (black)
                for (int i = 0; i < edgeCount; i++) {
                    if (i != hoveredEdgeIndex) {
                        glDrawArrays(GL_LINES, i * 2, 2);
                    }
                }

                // Update hovered edge color to orange temporarily
                int hoveredVertexStart = hoveredEdgeIndex * 2; // 2 vertices per edge
                int stride = 6 * Float.BYTES; // position + color
                int colorOffset = 3 * Float.BYTES; // Skip position, start at color

                // Create buffer with orange color for both vertices of the edge
                FloatBuffer orangeBuffer = BufferUtils.createFloatBuffer(6);
                orangeBuffer.put(hoverEdgeColor.x).put(hoverEdgeColor.y).put(hoverEdgeColor.z);  // Vertex 1
                orangeBuffer.put(hoverEdgeColor.x).put(hoverEdgeColor.y).put(hoverEdgeColor.z);  // Vertex 2
                orangeBuffer.flip();

                // Update VBO with orange color for hovered edge
                glBindBuffer(GL_ARRAY_BUFFER, vbo);
                int vboOffset = (hoveredVertexStart * stride) + colorOffset;

                // Update first vertex color (3 floats)
                glBufferSubData(GL_ARRAY_BUFFER, vboOffset, new float[] {
                    hoverEdgeColor.x, hoverEdgeColor.y, hoverEdgeColor.z
                });

                // Update second vertex color (3 floats)
                glBufferSubData(GL_ARRAY_BUFFER, vboOffset + stride, new float[] {
                    hoverEdgeColor.x, hoverEdgeColor.y, hoverEdgeColor.z
                });

                glBindBuffer(GL_ARRAY_BUFFER, 0);

                // Render hovered edge with orange color
                glDrawArrays(GL_LINES, hoveredEdgeIndex * 2, 2);

                // Restore black color for next frame
                glBindBuffer(GL_ARRAY_BUFFER, vbo);
                glBufferSubData(GL_ARRAY_BUFFER, vboOffset, new float[] {
                    edgeColor.x, edgeColor.y, edgeColor.z
                });
                glBufferSubData(GL_ARRAY_BUFFER, vboOffset + stride, new float[] {
                    edgeColor.x, edgeColor.y, edgeColor.z
                });
                glBindBuffer(GL_ARRAY_BUFFER, 0);
            } else {
                // No hover - render all edges normally (black)
                glDrawArrays(GL_LINES, 0, edgeCount * 2);
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
        edgeCount = 0;
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
     * Update all edge endpoints that match a dragged vertex position.
     * Searches through ALL edge endpoints and updates any that were at the old vertex position.
     * This handles the fact that EdgeExtractor creates 24 face-based edges (4 per face × 6 faces)
     * instead of 12 unique edges, so multiple edge endpoints share the same vertex position.
     *
     * @param oldPosition The original position of the vertex before dragging
     * @param newPosition The new position of the vertex after dragging
     */
    public void updateEdgesConnectedToVertex(Vector3f oldPosition, Vector3f newPosition) {
        if (!initialized) {
            logger.warn("Cannot update edge endpoints: renderer not initialized");
            return;
        }

        if (edgePositions == null || edgeCount == 0) {
            logger.warn("Cannot update edge endpoints: no edge data");
            return;
        }

        if (oldPosition == null || newPosition == null) {
            logger.warn("Cannot update edge endpoints: positions are null");
            return;
        }

        try {
            float epsilon = 0.0001f; // Same epsilon as VertexExtractor for consistency
            int updatedCount = 0;

            glBindBuffer(GL_ARRAY_BUFFER, vbo);

            // Search through ALL edge endpoints (2 endpoints per edge)
            int totalEndpoints = edgeCount * 2;
            for (int endpointIdx = 0; endpointIdx < totalEndpoints; endpointIdx++) {
                int posIndex = endpointIdx * 3; // 3 floats per position (x,y,z)

                // Check if this endpoint matches the old vertex position
                if (posIndex + 2 < edgePositions.length) {
                    Vector3f endpointPos = new Vector3f(
                            edgePositions[posIndex + 0],
                            edgePositions[posIndex + 1],
                            edgePositions[posIndex + 2]
                    );

                    if (endpointPos.distance(oldPosition) < epsilon) {
                        // Found a matching endpoint - update it!

                        // Update VBO (interleaved: position + color)
                        int dataIndex = endpointIdx * 6; // 6 floats per endpoint (x,y,z, r,g,b)
                        long offset = dataIndex * Float.BYTES;

                        float[] positionData = new float[] { newPosition.x, newPosition.y, newPosition.z };
                        glBufferSubData(GL_ARRAY_BUFFER, offset, positionData);

                        // Update in-memory array
                        edgePositions[posIndex + 0] = newPosition.x;
                        edgePositions[posIndex + 1] = newPosition.y;
                        edgePositions[posIndex + 2] = newPosition.z;

                        updatedCount++;
                    }
                }
            }

            glBindBuffer(GL_ARRAY_BUFFER, 0);

            logger.trace("Updated {} edge endpoints from ({}, {}, {}) to ({}, {}, {})",
                    updatedCount,
                    String.format("%.2f", oldPosition.x),
                    String.format("%.2f", oldPosition.y),
                    String.format("%.2f", oldPosition.z),
                    String.format("%.2f", newPosition.x),
                    String.format("%.2f", newPosition.y),
                    String.format("%.2f", newPosition.z));

        } catch (Exception e) {
            logger.error("Error updating edge endpoints", e);
        }
    }

}
