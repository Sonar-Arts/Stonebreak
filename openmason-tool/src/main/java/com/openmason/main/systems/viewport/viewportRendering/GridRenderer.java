package com.openmason.main.systems.viewport.viewportRendering;

import com.openmason.main.systems.viewport.shaders.ShaderProgram;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders an infinite grid in the viewport using a shader-based approach.
 * Similar to Blender's infinite grid that extends infinitely in all directions
 * and fades with distance from the camera.
 *
 * The grid is rendered as a single quad that covers the entire viewport,
 * with grid lines generated procedurally in the fragment shader.
 */
public class GridRenderer {

    private static final Logger logger = LoggerFactory.getLogger(GridRenderer.class);

    // OpenGL resources
    private int vao = 0;
    private int vbo = 0;
    private boolean initialized = false;

    // Grid appearance settings
    private float gridScale = 1.0f;          // Size of each grid cell (matches Blender's 1 unit grid)
    private float gridLineWidth = 0.02f;     // Thickness of grid lines
    private float fadeDistance = 400.0f;     // Distance at which grid starts to fade (increased for infinite appearance)
    private float maxDistance = 500.0f;      // Maximum distance before grid disappears (increased for infinite appearance)

    // Grid colors
    private final Vector3f primaryColor = new Vector3f(0.5f, 0.5f, 0.5f);    // Main grid lines
    private final Vector3f secondaryColor = new Vector3f(0.3f, 0.3f, 0.3f);  // Subdivision lines
    private final Vector3f axisXColor = new Vector3f(0.8f, 0.2f, 0.2f);      // X-axis (red)
    private final Vector3f axisZColor = new Vector3f(0.2f, 0.2f, 0.8f);      // Z-axis (blue)
    private final Vector3f fogColor = new Vector3f(0.2f, 0.2f, 0.3f);        // Background fog color (matches viewport background)

    /**
     * Initialize the infinite grid renderer.
     */
    public void initialize() {
        if (initialized) {
            logger.debug("InfiniteGridRenderer already initialized");
            return;
        }

        try {
            logger.info("Initializing InfiniteGridRenderer...");

            // Create a full-screen quad (two triangles)
            float[] quadVertices = {
                // Position (x, y, z)      UV (u, v)
                -1.0f, -1.0f, 0.0f,       0.0f, 0.0f,
                 1.0f, -1.0f, 0.0f,       1.0f, 0.0f,
                 1.0f,  1.0f, 0.0f,       1.0f, 1.0f,
                -1.0f,  1.0f, 0.0f,       0.0f, 1.0f
            };

            // Generate VAO and VBO
            vao = glGenVertexArrays();
            vbo = glGenBuffers();

            // Bind VAO
            glBindVertexArray(vao);

            // Upload vertex data
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, quadVertices, GL_STATIC_DRAW);

            // Configure vertex attributes
            // Position attribute (location = 0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
            glEnableVertexAttribArray(0);

            // UV attribute (location = 1)
            glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
            glEnableVertexAttribArray(1);

            // Unbind
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);

            initialized = true;
            logger.info("InfiniteGridRenderer initialized successfully");

        } catch (Exception e) {
            logger.error("Failed to initialize InfiniteGridRenderer", e);
            cleanup();
            throw new RuntimeException("InfiniteGridRenderer initialization failed", e);
        }
    }

    /**
     * Render the infinite grid.
     */
    public void render(ShaderProgram shader, RenderContext context) {
        if (!initialized) {
            logger.warn("InfiniteGridRenderer not initialized");
            return;
        }

        try {
            // Use shader
            shader.use();

            // Get camera matrices
            Matrix4f viewMatrix = context.getCamera().getViewMatrix();
            Matrix4f projectionMatrix = context.getCamera().getProjectionMatrix();
            Vector3f cameraPosition = context.getCamera().getPosition();

            // Upload uniforms
            shader.setMat4("uViewMatrix", viewMatrix);
            shader.setMat4("uProjectionMatrix", projectionMatrix);
            shader.setVec3("uCameraPosition", cameraPosition);
            shader.setFloat("uGridScale", gridScale);
            shader.setFloat("uGridLineWidth", gridLineWidth);
            shader.setFloat("uFadeDistance", fadeDistance);
            shader.setFloat("uMaxDistance", maxDistance);
            shader.setVec3("uPrimaryColor", primaryColor);
            shader.setVec3("uSecondaryColor", secondaryColor);
            shader.setVec3("uAxisXColor", axisXColor);
            shader.setVec3("uAxisZColor", axisZColor);
            shader.setVec3("uFogColor", fogColor);

            // Render the quad
            glBindVertexArray(vao);
            glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
            glBindVertexArray(0);

            logger.trace("Infinite grid rendered successfully");

        } catch (Exception e) {
            logger.error("Error rendering infinite grid", e);
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
        initialized = false;
        logger.debug("InfiniteGridRenderer cleanup complete");
    }

    public boolean isInitialized() {
        return initialized;
    }
}
