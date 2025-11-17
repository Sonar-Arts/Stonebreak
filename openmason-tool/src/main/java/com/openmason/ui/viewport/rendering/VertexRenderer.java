package com.openmason.ui.viewport.rendering;

import com.openmason.ui.viewport.rendering.vertex.VertexExtractor;
import com.openmason.ui.viewport.shaders.ShaderProgram;
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
 * Follows KISS, SOLID, DRY, and YAGNI principles.
 *
 * Single Responsibility: Render model vertices as points (extraction delegated to VertexExtractor).
 * Open/Closed: Works with any Collection of ModelParts without modification.
 * DRY: Single code path for all model types.
 * YAGNI: No unnecessary features, no model-type-specific code.
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
    private final Vector3f vertexColor = new Vector3f(1.0f, 0.6f, 0.0f); // Blender's orange

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

            // Configure vertex attributes
            // Position attribute (location = 0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
            glEnableVertexAttribArray(0);

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
     *
     * KISS: Single, simple method for all use cases.
     * DRY: No duplicate code paths.
     * Open/Closed: Add new model types without changing this class.
     *
     * @param parts Collection of model parts to render vertices from
     * @param transformMatrix Transformation matrix to apply
     */
    public void updateVertexData(Collection<ModelDefinition.ModelPart> parts, Matrix4f transformMatrix) {
        if (!initialized) {
            logger.warn("VertexRenderer not initialized, cannot update vertex data");
            return;
        }

        if (parts == null || parts.isEmpty()) {
            vertexCount = 0;
            return;
        }

        try {
            // Extract vertices using VertexExtractor (Single Responsibility)
            float[] vertices = vertexExtractor.extractVertices(parts, transformMatrix);

            // Upload to GPU
            vertexCount = vertices.length / 3;
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, vertices, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

        } catch (Exception e) {
            logger.error("Error updating vertex data", e);
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
            // This matches Blender's approach: vertices stored locally, rendered in world space
            Matrix4f viewMatrix = context.getCamera().getViewMatrix();
            Matrix4f projectionMatrix = context.getCamera().getProjectionMatrix();
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix);

            // Upload uniforms
            shader.setMat4("uMVPMatrix", mvpMatrix);
            shader.setVec3("uColor", vertexColor);

            // Set point size (fixed function)
            glPointSize(pointSize);

            // Save and modify depth function to ensure points are visible on surfaces
            int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
            glDepthFunc(GL_LEQUAL); // Use LEQUAL to allow points on surfaces

            // Render vertices
            glBindVertexArray(vao);
            glDrawArrays(GL_POINTS, 0, vertexCount);
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

    // Getters and setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public float getPointSize() {
        return pointSize;
    }

    public void setPointSize(float pointSize) {
        this.pointSize = Math.max(1.0f, Math.min(15.0f, pointSize));
        logger.trace("Point size set to: {}", this.pointSize);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public Vector3f getVertexColor() {
        return new Vector3f(vertexColor);
    }

    public void setVertexColor(float r, float g, float b) {
        this.vertexColor.set(r, g, b);
    }
}
