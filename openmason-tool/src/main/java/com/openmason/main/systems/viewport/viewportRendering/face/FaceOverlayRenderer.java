package com.openmason.main.systems.viewport.viewportRendering.face;

import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import com.openmason.main.systems.viewport.viewportRendering.face.operations.FaceUpdateOperation;
import com.openmason.main.systems.viewport.shaders.ShaderProgram;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Handles semi-transparent face overlay rendering for interactive highlighting.
 *
 * <p>This class is responsible for rendering colored overlays on model faces to provide
 * visual feedback for hover and selection states. It manages OpenGL state, colors,
 * and rendering operations for face overlays.
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li><b>Single Responsibility</b>: Only handles overlay rendering logic</li>
 *   <li><b>Separation of Concerns</b>: Decoupled from face geometry management</li>
 *   <li><b>RAII</b>: Properly restores OpenGL state after rendering</li>
 * </ul>
 *
 * <h2>Rendering Strategy</h2>
 * <p>Uses two-pass rendering with transparency:
 * <ol>
 *   <li>Model renders first (solid, textured)</li>
 *   <li>Face overlays render second (semi-transparent, colored)</li>
 * </ol>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Hover highlighting with orange color</li>
 *   <li>Selection highlighting with white color</li>
 *   <li>Transparent default state (invisible)</li>
 *   <li>OpenGL state management with restoration</li>
 *   <li>VBO color updates for efficient rendering</li>
 * </ul>
 *
 * @see FaceRenderer
 * @see FaceUpdateOperation
 */
public class FaceOverlayRenderer {

    private static final Logger logger = LoggerFactory.getLogger(FaceOverlayRenderer.class);

    // Layout constants
    private static final int COMPONENTS_PER_POSITION = 3; // x, y, z
    private static final int COLOR_OFFSET_FLOATS = COMPONENTS_PER_POSITION; // Color data starts after position

    // Color definitions with alpha for transparency
    private final Vector4f defaultFaceColor = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f); // Transparent (invisible)
    private final Vector4f hoverFaceColor = new Vector4f(1.0f, 0.6f, 0.0f, 0.3f); // Orange with 30% alpha
    private final Vector4f selectedFaceColor = new Vector4f(1.0f, 1.0f, 1.0f, 0.3f); // White with 30% alpha

    /**
     * Render face overlays with semi-transparent highlighting.
     * KISS: Only render hovered/selected faces, skip default (transparent) faces.
     *
     * @param vao The vertex array object to bind
     * @param vbo The vertex buffer object for color updates
     * @param shader The shader program to use
     * @param context The render context
     * @param modelMatrix The model transformation matrix
     * @param hoveredFaceIndex The currently hovered face index (-1 if none)
     * @param selectedFaceIndex The currently selected face index (-1 if none)
     * @param faceCount Total number of faces
     */
    public void render(int vao, int vbo, ShaderProgram shader, RenderContext context,
                      Matrix4f modelMatrix, int hoveredFaceIndex, int selectedFaceIndex, int faceCount) {
        if (!shouldRender(hoveredFaceIndex, selectedFaceIndex, faceCount)) {
            return;
        }

        try {
            setupShaderAndMatrices(shader, context, modelMatrix);
            RenderState previousState = setupRenderState();

            glBindVertexArray(vao);
            renderVisibleFaces(vbo, hoveredFaceIndex, selectedFaceIndex);
            glBindVertexArray(0);

            restoreRenderState(previousState);

        } catch (Exception e) {
            logger.error("Error rendering face overlays", e);
        } finally {
            glUseProgram(0);
        }
    }

    /**
     * Check if overlay rendering should proceed.
     *
     * @param hoveredFaceIndex The hovered face index
     * @param selectedFaceIndex The selected face index
     * @param faceCount Total number of faces
     * @return true if should render, false otherwise
     */
    private boolean shouldRender(int hoveredFaceIndex, int selectedFaceIndex, int faceCount) {
        if (faceCount == 0) {
            return false;
        }

        // Only render if there's something to show
        return hoveredFaceIndex >= 0 || selectedFaceIndex >= 0;
    }

    /**
     * Setup shader and upload matrices.
     *
     * @param shader The shader program
     * @param context The render context
     * @param modelMatrix The model transformation matrix
     */
    private void setupShaderAndMatrices(ShaderProgram shader, RenderContext context, Matrix4f modelMatrix) {
        shader.use();

        // Calculate and upload MVP matrix
        Matrix4f viewMatrix = context.getCamera().getViewMatrix();
        Matrix4f projectionMatrix = context.getCamera().getProjectionMatrix();
        Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);

        shader.setMat4("uMVPMatrix", mvpMatrix);
        shader.setFloat("uIntensity", 1.0f);
    }

    /**
     * Setup OpenGL render state for face overlay rendering.
     *
     * @return previous render state for restoration
     */
    private RenderState setupRenderState() {
        // Save previous state
        int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
        boolean prevDepthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean prevCullFace = glGetBoolean(GL_CULL_FACE);

        // Enable blending for transparency
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Depth test but don't write (render on top of model)
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);

        // Disable backface culling to render both sides
        if (prevCullFace) {
            glDisable(GL_CULL_FACE);
        }

        // Enable polygon offset to prevent z-fighting
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-1.0f, -1.0f);

        return new RenderState(prevDepthFunc, prevDepthMask, prevCullFace);
    }

    /**
     * Render visible faces (hovered and/or selected).
     *
     * @param vbo The vertex buffer object for color updates
     * @param hoveredFaceIndex The hovered face index
     * @param selectedFaceIndex The selected face index
     */
    private void renderVisibleFaces(int vbo, int hoveredFaceIndex, int selectedFaceIndex) {
        if (hoveredFaceIndex >= 0) {
            renderFaceWithColor(vbo, hoveredFaceIndex, hoverFaceColor);
        }

        if (selectedFaceIndex >= 0 && selectedFaceIndex != hoveredFaceIndex) {
            renderFaceWithColor(vbo, selectedFaceIndex, selectedFaceColor);
        }
    }

    /**
     * Render a single face with a specific color.
     * Updates VBO color data, renders the face, then restores default color.
     * Uses shared constants from FaceUpdateOperation for DRY compliance.
     *
     * @param vbo The vertex buffer object for color updates
     * @param faceIndex The face index to render
     * @param color The color to use (RGBA with alpha for transparency)
     */
    private void renderFaceWithColor(int vbo, int faceIndex, Vector4f color) {
        int dataStart = faceIndex * FaceUpdateOperation.FLOATS_PER_FACE_VBO;
        int colorOffsetBytes = COLOR_OFFSET_FLOATS * Float.BYTES;

        // Update color in VBO
        updateFaceColors(vbo, dataStart, colorOffsetBytes, color);

        // Render the face (2 triangles = 6 vertices)
        glDrawArrays(GL_TRIANGLES, faceIndex * FaceUpdateOperation.VERTICES_PER_FACE, FaceUpdateOperation.VERTICES_PER_FACE);

        // Restore default color
        updateFaceColors(vbo, dataStart, colorOffsetBytes, defaultFaceColor);
    }

    /**
     * Update color data for all vertices of a face in the VBO.
     *
     * @param vbo The vertex buffer object
     * @param dataStart Starting offset in the VBO
     * @param colorOffsetBytes Byte offset to color data within each vertex
     * @param color The color to set
     */
    private void updateFaceColors(int vbo, int dataStart, int colorOffsetBytes, Vector4f color) {
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        float[] colorData = new float[] { color.x, color.y, color.z, color.w };
        for (int i = 0; i < FaceUpdateOperation.VERTICES_PER_FACE; i++) {
            int vboOffset = (dataStart + (i * FaceUpdateOperation.FLOATS_PER_VERTEX)) * Float.BYTES + colorOffsetBytes;
            glBufferSubData(GL_ARRAY_BUFFER, vboOffset, colorData);
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Restore OpenGL render state.
     *
     * @param state Previous render state to restore
     */
    private void restoreRenderState(RenderState state) {
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDepthMask(state.depthMask);
        glDepthFunc(state.depthFunc);

        if (state.cullFace) {
            glEnable(GL_CULL_FACE);
        }

        glDisable(GL_BLEND);
    }

    /**
     * Get the default (invisible) face color.
     *
     * @return The default face color
     */
    public Vector4f getDefaultFaceColor() {
        return new Vector4f(defaultFaceColor);
    }

    /**
     * Get the hover highlight color.
     *
     * @return The hover face color
     */
    public Vector4f getHoverFaceColor() {
        return new Vector4f(hoverFaceColor);
    }

    /**
     * Get the selection highlight color.
     *
     * @return The selected face color
     */
    public Vector4f getSelectedFaceColor() {
        return new Vector4f(selectedFaceColor);
    }

    /**
     * Holds previous OpenGL render state for restoration.
     */
    private static class RenderState {
        final int depthFunc;
        final boolean depthMask;
        final boolean cullFace;

        RenderState(int depthFunc, boolean depthMask, boolean cullFace) {
            this.depthFunc = depthFunc;
            this.depthMask = depthMask;
            this.cullFace = cullFace;
        }
    }
}
