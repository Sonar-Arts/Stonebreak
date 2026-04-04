package com.openmason.main.systems.rendering.model.gmr.subrenders.edge;

import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders the knife tool preview line and cut point indicator.
 * Lightweight OpenGL renderer using the BASIC shader.
 *
 * <p>Draws a preview line from the first cut point to the current hover position,
 * rendered on top of all geometry using {@code GL_ALWAYS} depth test.
 *
 * <p>Color: orange (1.0, 0.6, 0.0) to match edge hover highlighting.
 */
public class KnifePreviewRenderer {

    private static final Logger logger = LoggerFactory.getLogger(KnifePreviewRenderer.class);

    private static final Vector3f PREVIEW_COLOR = new Vector3f(1.0f, 0.6f, 0.0f);
    private static final float PREVIEW_LINE_WIDTH = 2.0f;
    private static final int VERTEX_STRIDE_BYTES = 6 * Float.BYTES; // pos(3) + color(3)

    private int vao = 0;
    private int vbo = 0;
    private boolean initialized = false;
    private boolean active = false;

    // Preview line endpoints (model space)
    private Vector3f startPoint = null;
    private Vector3f endPoint = null;
    private boolean hasPreviewLine = false;

    // Cut point indicator (committed first cut)
    private Vector3f cutPoint = null;
    private boolean hasCutPoint = false;

    // Hover point indicator (where the next cut will land)
    private Vector3f hoverPoint = null;
    private boolean hasHoverPoint = false;

    /**
     * Initialize OpenGL resources. Lazily allocates a small VAO/VBO.
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        // Allocate for 4 vertices max (2 for line + 2 for cut point cross)
        glBufferData(GL_ARRAY_BUFFER, (long) 4 * VERTEX_STRIDE_BYTES, GL_DYNAMIC_DRAW);

        // Position attribute (location = 0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, VERTEX_STRIDE_BYTES, 0);
        glEnableVertexAttribArray(0);

        // Color attribute (location = 1)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, VERTEX_STRIDE_BYTES, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        initialized = true;
        logger.debug("KnifePreviewRenderer initialized");
    }

    /**
     * Set the preview line from first cut point to the current hover position.
     *
     * @param start First cut point in model space
     * @param end Current hover position in model space
     */
    public void setPreviewLine(Vector3f start, Vector3f end) {
        this.startPoint = start;
        this.endPoint = end;
        this.hasPreviewLine = (start != null && end != null);
    }

    /**
     * Set the cut point indicator (shown after first click).
     *
     * @param point Cut point position in model space, or null to clear
     */
    public void setCutPoint(Vector3f point) {
        this.cutPoint = point;
        this.hasCutPoint = (point != null);
    }

    /**
     * Set the hover point indicator (shows where the next cut will land on the hovered edge).
     *
     * @param point Hover position in model space, or null to clear
     */
    public void setHoverPoint(Vector3f point) {
        this.hoverPoint = point;
        this.hasHoverPoint = (point != null);
    }

    /**
     * Clear all preview state.
     */
    public void clearPreview() {
        this.startPoint = null;
        this.endPoint = null;
        this.cutPoint = null;
        this.hoverPoint = null;
        this.hasPreviewLine = false;
        this.hasCutPoint = false;
        this.hasHoverPoint = false;
    }

    /**
     * Set whether the knife preview is active.
     */
    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            clearPreview();
        }
    }

    /**
     * @return true if the preview renderer is active and has something to draw
     */
    public boolean isActive() {
        return active && (hasPreviewLine || hasCutPoint || hasHoverPoint);
    }

    /**
     * @return true if the renderer has been initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Render the knife preview overlay.
     *
     * @param shader BASIC shader program
     * @param context Render context with camera matrices
     * @param modelMatrix Model transformation matrix
     */
    public void render(ShaderProgram shader, RenderContext context, Matrix4f modelMatrix) {
        if (!initialized || !active || (!hasPreviewLine && !hasCutPoint && !hasHoverPoint)) {
            return;
        }

        try {
            shader.use();

            // Compute MVP
            Matrix4f viewMatrix = context.getCamera().getViewMatrix();
            Matrix4f projectionMatrix = context.getCamera().getProjectionMatrix();
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);
            shader.setMat4("uMVPMatrix", mvpMatrix);
            shader.setFloat("uIntensity", 1.0f);

            // Draw on top of everything
            int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
            glDepthFunc(GL_ALWAYS);
            glLineWidth(PREVIEW_LINE_WIDTH);

            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);

            if (hasPreviewLine) {
                // Upload line vertices: [pos(3) + color(3)] x 2
                float[] lineData = {
                    startPoint.x, startPoint.y, startPoint.z,
                    PREVIEW_COLOR.x, PREVIEW_COLOR.y, PREVIEW_COLOR.z,
                    endPoint.x, endPoint.y, endPoint.z,
                    PREVIEW_COLOR.x, PREVIEW_COLOR.y, PREVIEW_COLOR.z
                };
                glBufferSubData(GL_ARRAY_BUFFER, 0, lineData);
                glDrawArrays(GL_LINES, 0, 2);
            }

            if (hasCutPoint) {
                // Draw a small point at the committed cut location
                float[] pointData = {
                    cutPoint.x, cutPoint.y, cutPoint.z,
                    PREVIEW_COLOR.x, PREVIEW_COLOR.y, PREVIEW_COLOR.z
                };
                glBufferSubData(GL_ARRAY_BUFFER, 0, pointData);
                glPointSize(8.0f);
                glDrawArrays(GL_POINTS, 0, 1);
            }

            if (hasHoverPoint) {
                // Draw a point where the next cut will land on the hovered edge
                float[] hoverData = {
                    hoverPoint.x, hoverPoint.y, hoverPoint.z,
                    PREVIEW_COLOR.x, PREVIEW_COLOR.y, PREVIEW_COLOR.z
                };
                glBufferSubData(GL_ARRAY_BUFFER, 0, hoverData);
                glPointSize(8.0f);
                glDrawArrays(GL_POINTS, 0, 1);
            }

            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);

            // Restore state
            glDepthFunc(prevDepthFunc);

        } catch (Exception e) {
            logger.error("Error rendering knife preview", e);
        } finally {
            glUseProgram(0);
        }
    }

    /**
     * Free OpenGL resources.
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
        logger.debug("KnifePreviewRenderer cleaned up");
    }
}
