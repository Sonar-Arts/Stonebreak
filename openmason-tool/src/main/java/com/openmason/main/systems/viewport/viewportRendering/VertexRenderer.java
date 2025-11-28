package com.openmason.main.systems.viewport.viewportRendering;

import com.openmason.main.systems.viewport.viewportRendering.vertex.VertexExtractor;
import com.openmason.main.systems.viewport.shaders.ShaderProgram;
import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
  * Renders model vertices as colored points, similar to Blender's vertex display mode.
  * Vertex positions are extracted by VertexExtractor; this class handles rendering.
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
    private final Vector3f defaultVertexColor = new Vector3f(1.0f, 0.6f, 0.0f); // Blender's orange

    // Hover state
    private int hoveredVertexIndex = -1; // -1 means no vertex is hovered
    private float[] vertexPositions = null; // Store positions for hit testing

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
            // KISS: All vertices always orange - intensity uniform handles hover highlighting
            float[] vertexData = new float[vertexCount * 6]; // 3 for position, 3 for color

            for (int i = 0; i < vertexCount; i++) {
                int posIndex = i * 3;
                int dataIndex = i * 6;

                // Copy position
                vertexData[dataIndex + 0] = positions[posIndex + 0];
                vertexData[dataIndex + 1] = positions[posIndex + 1];
                vertexData[dataIndex + 2] = positions[posIndex + 2];

                // All vertices use default orange color (same pattern as gizmo)
                vertexData[dataIndex + 3] = defaultVertexColor.x;
                vertexData[dataIndex + 4] = defaultVertexColor.y;
                vertexData[dataIndex + 5] = defaultVertexColor.z;
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

            // KISS: Render each vertex with appropriate intensity (same pattern as gizmo)
            // Hovered vertex gets high intensity (brightens to yellow), others get normal intensity
            float normalIntensity = 1.0f;
            float hoverIntensity = 2.5f; // Orange * 2.5 ≈ Yellow


            // Render all vertices at once with appropriate intensity
            if (hoveredVertexIndex >= 0) {
                // Render non-hovered vertices with normal intensity
                shader.setFloat("uIntensity", normalIntensity);

                // Draw all except hovered
                for (int i = 0; i < vertexCount; i++) {
                    if (i != hoveredVertexIndex) {
                        glDrawArrays(GL_POINTS, i, 1);
                    }
                }

                // Render hovered vertex with high intensity (yellow)
                shader.setFloat("uIntensity", hoverIntensity);
                glDrawArrays(GL_POINTS, hoveredVertexIndex, 1);
            } else {
                // No hover - render all vertices with normal intensity
                shader.setFloat("uIntensity", normalIntensity);
                glDrawArrays(GL_POINTS, 0, vertexCount);
            }

            glBindVertexArray(0);

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
