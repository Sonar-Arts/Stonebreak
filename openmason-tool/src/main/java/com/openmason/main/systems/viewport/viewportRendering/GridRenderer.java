package com.openmason.main.systems.viewport.viewportRendering;

import com.openmason.engine.rendering.shaders.ShaderProgram;
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
    private float gridScale = 1.0f;          // Base cell size in world units (matches Blender's 1 unit grid)
    private float lineWidthPx = 1.0f;        // Grid line width in pixels (screen space, zoom independent)
    private float fadeDistance = 400.0f;     // Camera distance where the grid starts to fade
    private float maxDistance = 500.0f;      // Camera distance where the grid is fully faded

    // Grid colors
    private final Vector3f minorColor = new Vector3f(0.34f, 0.34f, 0.38f);   // Fine grid lines
    private final Vector3f majorColor = new Vector3f(0.48f, 0.48f, 0.53f);   // Every-10th grid lines
    private final Vector3f axisXColor = new Vector3f(0.86f, 0.34f, 0.36f);   // X-axis (red)
    private final Vector3f axisZColor = new Vector3f(0.31f, 0.45f, 0.88f);   // Z-axis (blue)
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

            // Upload uniforms
            shader.setMat4("uViewMatrix", viewMatrix);
            shader.setMat4("uProjectionMatrix", projectionMatrix);
            shader.setFloat("uGridScale", gridScale);
            shader.setFloat("uLineWidthPx", lineWidthPx);
            shader.setFloat("uFadeDistance", fadeDistance);
            shader.setFloat("uMaxDistance", maxDistance);
            shader.setVec3("uMinorColor", minorColor);
            shader.setVec3("uMajorColor", majorColor);
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

    // --- Appearance configuration (applied on the next rendered frame) ---

    /** Base cell size in world units. Coarser/finer levels derive from it in powers of ten. */
    public void setGridScale(float gridScale) {
        this.gridScale = Math.max(0.001f, gridScale);
    }

    /** Grid line width in screen pixels, independent of zoom. */
    public void setLineWidthPx(float lineWidthPx) {
        this.lineWidthPx = Math.max(0.5f, lineWidthPx);
    }

    /** Camera-relative distances where the grid starts fading and fully disappears. */
    public void setFadeRange(float fadeDistance, float maxDistance) {
        this.fadeDistance = Math.max(0.0f, fadeDistance);
        this.maxDistance = Math.max(this.fadeDistance + 1.0f, maxDistance);
    }

    public void setMinorColor(float r, float g, float b) {
        minorColor.set(r, g, b);
    }

    public void setMajorColor(float r, float g, float b) {
        majorColor.set(r, g, b);
    }

    public void setAxisColors(Vector3f xAxis, Vector3f zAxis) {
        axisXColor.set(xAxis);
        axisZColor.set(zAxis);
    }

    /** Should match the viewport clear color so the horizon fade blends seamlessly. */
    public void setFogColor(float r, float g, float b) {
        fogColor.set(r, g, b);
    }

    public float getGridScale() {
        return gridScale;
    }
}
