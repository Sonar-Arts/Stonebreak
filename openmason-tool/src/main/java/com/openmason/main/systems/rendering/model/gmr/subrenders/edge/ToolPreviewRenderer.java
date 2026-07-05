package com.openmason.main.systems.rendering.model.gmr.subrenders.edge;

import com.openmason.engine.rendering.shaders.ShaderProgram;
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
 * Renders an arbitrary list of overlay line segments for modal tools
 * (inset/extrude preview). Lightweight OpenGL renderer using the BASIC shader,
 * modeled on {@link KnifePreviewRenderer} but with a caller-supplied segment
 * list instead of fixed line/point slots.
 *
 * <p>Segments are model-space endpoint pairs, drawn on top of all geometry
 * with {@code GL_ALWAYS} depth test.
 *
 * <p>Color: orange (1.0, 0.6, 0.0) to match the other modal tool overlays.
 */
public class ToolPreviewRenderer {

    private static final Logger logger = LoggerFactory.getLogger(ToolPreviewRenderer.class);

    private static final Vector3f PREVIEW_COLOR = new Vector3f(1.0f, 0.6f, 0.0f);
    private static final float PREVIEW_LINE_WIDTH = 2.0f;
    private static final int FLOATS_PER_VERTEX = 6; // pos(3) + color(3)
    private static final int VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;

    private int vao = 0;
    private int vbo = 0;
    private int vboCapacityFloats = 0;
    private boolean initialized = false;
    private boolean active = false;

    // Model-space segments: [x1,y1,z1, x2,y2,z2, ...] (length divisible by 6)
    private float[] segments = new float[0];
    private boolean segmentsDirty = false;

    /**
     * Initialize OpenGL resources. Lazily allocates a VAO/VBO; the VBO grows
     * on demand as larger segment lists arrive.
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        // Position attribute (location = 0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, VERTEX_STRIDE_BYTES, 0);
        glEnableVertexAttribArray(0);

        // Color attribute (location = 1)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, VERTEX_STRIDE_BYTES, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        initialized = true;
        logger.debug("ToolPreviewRenderer initialized");
    }

    /**
     * Set the preview line segments.
     *
     * @param lineSegments Flat model-space endpoint array [x1,y1,z1, x2,y2,z2, ...];
     *                     length must be divisible by 6. Null or empty clears the preview.
     */
    public void setLines(float[] lineSegments) {
        if (lineSegments == null || lineSegments.length < 6) {
            clear();
            return;
        }
        int usable = lineSegments.length - (lineSegments.length % 6);
        this.segments = java.util.Arrays.copyOf(lineSegments, usable);
        this.segmentsDirty = true;
    }

    /**
     * Clear all preview segments.
     */
    public void clear() {
        this.segments = new float[0];
        this.segmentsDirty = false;
    }

    /**
     * Set whether the tool preview is active.
     */
    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            clear();
        }
    }

    /**
     * @return true if the preview renderer is active and has something to draw
     */
    public boolean isActive() {
        return active && segments.length >= 6;
    }

    /**
     * @return true if the renderer has been initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Render the tool preview overlay.
     *
     * @param shader BASIC shader program
     * @param context Render context with camera matrices
     * @param modelMatrix Model transformation matrix
     */
    public void render(ShaderProgram shader, RenderContext context, Matrix4f modelMatrix) {
        if (!initialized || !isActive()) {
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

            int vertexCount = segments.length / 3;
            if (segmentsDirty) {
                uploadSegments(vertexCount);
                segmentsDirty = false;
            }

            glDrawArrays(GL_LINES, 0, vertexCount);

            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);

            // Restore state
            glDepthFunc(prevDepthFunc);

        } catch (Exception e) {
            logger.error("Error rendering tool preview", e);
        } finally {
            glUseProgram(0);
        }
    }

    /**
     * Interleave positions with the preview color and upload, growing the VBO
     * when the current capacity is too small.
     */
    private void uploadSegments(int vertexCount) {
        float[] vertexData = new float[vertexCount * FLOATS_PER_VERTEX];
        for (int v = 0; v < vertexCount; v++) {
            int src = v * 3;
            int dst = v * FLOATS_PER_VERTEX;
            vertexData[dst]     = segments[src];
            vertexData[dst + 1] = segments[src + 1];
            vertexData[dst + 2] = segments[src + 2];
            vertexData[dst + 3] = PREVIEW_COLOR.x;
            vertexData[dst + 4] = PREVIEW_COLOR.y;
            vertexData[dst + 5] = PREVIEW_COLOR.z;
        }

        if (vertexData.length > vboCapacityFloats) {
            glBufferData(GL_ARRAY_BUFFER, vertexData, GL_DYNAMIC_DRAW);
            vboCapacityFloats = vertexData.length;
        } else {
            glBufferSubData(GL_ARRAY_BUFFER, 0, vertexData);
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
        vboCapacityFloats = 0;
        initialized = false;
        logger.debug("ToolPreviewRenderer cleaned up");
    }
}
