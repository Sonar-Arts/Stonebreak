package com.openmason.main.systems.viewport.viewportRendering.face;

import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import com.openmason.main.systems.viewport.viewportRendering.mesh.MeshManager;
import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Handles semi-transparent primitive overlay rendering for interactive highlighting.
 * Shape-agnostic design supporting arbitrary GMR geometries (cubes, arbitrary meshes, etc).
 *
 * <p>This class is responsible for rendering colored overlays on model primitives to provide
 * visual feedback for hover and selection states. It manages OpenGL state, colors,
 * and rendering operations for primitive overlays without assuming specific geometry types.
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li><b>Single Responsibility</b>: Only handles overlay rendering logic</li>
 *   <li><b>Separation of Concerns</b>: Decoupled from primitive geometry management</li>
 *   <li><b>Shape-Agnostic</b>: Works with arbitrary GMR geometries (cubes, arbitrary meshes)</li>
 *   <li><b>RAII</b>: Properly restores OpenGL state after rendering</li>
 * </ul>
 *
 * <h2>Rendering Strategy</h2>
 * <p>Uses two-pass rendering with transparency:
 * <ol>
 *   <li>Model renders first (solid, textured)</li>
 *   <li>Primitive overlays render second (semi-transparent, colored)</li>
 * </ol>
 *
 * <h2>Rendering Modes</h2>
 * <ul>
 *   <li><b>Polygon Mode</b>: Pre-tessellated geometry with fixed vertex count per primitive</li>
 *   <li><b>Triangle Mode</b>: Subdivided/triangulated geometry with dynamic primitive grouping</li>
 * </ul>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Hover highlighting with orange color</li>
 *   <li>Selection highlighting with white color</li>
 *   <li>Transparent default state (invisible)</li>
 *   <li>OpenGL state management with restoration</li>
 *   <li>VBO color updates for efficient rendering</li>
 *   <li>Multi-selection support</li>
 * </ul>
 *
 * @see FaceRenderer
 * @see MeshManager
 */
public class FaceOverlayRenderer {

    private static final Logger logger = LoggerFactory.getLogger(FaceOverlayRenderer.class);

    // Layout constants
    private static final int COMPONENTS_PER_POSITION = 3; // x, y, z
    private static final int COLOR_OFFSET_FLOATS = COMPONENTS_PER_POSITION; // Color data starts after position

    // Triangle mode constants (3 vertices per triangle primitive, post-subdivision)
    private static final int VERTICES_PER_TRIANGLE = 3;
    private static final int FLOATS_PER_VERTEX_TRIANGLE = 7; // position (3) + color (4)
    private static final int FLOATS_PER_TRIANGLE_VBO = VERTICES_PER_TRIANGLE * FLOATS_PER_VERTEX_TRIANGLE; // 21

    // Color definitions with alpha for transparency
    private final Vector4f defaultPrimitiveColor = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f); // Transparent (invisible)
    private final Vector4f hoverPrimitiveColor = new Vector4f(1.0f, 0.6f, 0.0f, 0.3f); // Orange with 30% alpha
    private final Vector4f selectedPrimitiveColor = new Vector4f(1.0f, 1.0f, 1.0f, 0.3f); // White with 30% alpha

    // Reference to GenericModelRenderer for mesh vertex position access after subdivision
    private GenericModelRenderer genericModelRenderer = null;

    // Triangle mode flag (true after subdivision, false for pre-tessellated polygon mode)
    private boolean triangleMode = false;

    // Maps original primitive ID to list of triangle indices (used in triangle mode)
    private java.util.Map<Integer, java.util.List<Integer>> originalFaceToTriangles = new java.util.HashMap<>();

    /**
     * Render primitive overlays with semi-transparent highlighting (single selection - backward compat).
     * KISS: Only render hovered/selected primitives, skip default (transparent) primitives.
     *
     * @param vao The vertex array object to bind
     * @param vbo The vertex buffer object for color updates
     * @param shader The shader program to use
     * @param context The render context
     * @param modelMatrix The model transformation matrix
     * @param hoveredFaceIndex The currently hovered primitive index (-1 if none)
     * @param selectedFaceIndex The currently selected primitive index (-1 if none)
     * @param faceCount Total number of primitives
     */
    public void render(int vao, int vbo, ShaderProgram shader, RenderContext context,
                      Matrix4f modelMatrix, int hoveredFaceIndex, int selectedFaceIndex, int faceCount) {
        java.util.Set<Integer> selectedSet = new java.util.HashSet<>();
        if (selectedFaceIndex >= 0) {
            selectedSet.add(selectedFaceIndex);
        }
        render(vao, vbo, shader, context, modelMatrix, hoveredFaceIndex, selectedSet, faceCount);
    }

    /**
     * Render primitive overlays with semi-transparent highlighting (multi-selection).
     * KISS: Only render hovered/selected primitives, skip default (transparent) primitives.
     *
     * @param vao The vertex array object to bind
     * @param vbo The vertex buffer object for color updates
     * @param shader The shader program to use
     * @param context The render context
     * @param modelMatrix The model transformation matrix
     * @param hoveredFaceIndex The currently hovered primitive index (-1 if none)
     * @param selectedFaceIndices Set of selected primitive indices
     * @param faceCount Total number of primitives
     */
    public void render(int vao, int vbo, ShaderProgram shader, RenderContext context,
                      Matrix4f modelMatrix, int hoveredFaceIndex, java.util.Set<Integer> selectedFaceIndices, int faceCount) {
        if (!shouldRender(hoveredFaceIndex, selectedFaceIndices, faceCount)) {
            return;
        }

        try {
            setupShaderAndMatrices(shader, context, modelMatrix);
            RenderState previousState = setupRenderState();

            glBindVertexArray(vao);
            renderVisibleFaces(vbo, hoveredFaceIndex, selectedFaceIndices);
            glBindVertexArray(0);

            restoreRenderState(previousState);

        } catch (Exception e) {
            logger.error("Error rendering face overlays", e);
        } finally {
            glUseProgram(0);
        }
    }

    /**
     * Check if overlay rendering should proceed (multi-selection).
     *
     * @param hoveredFaceIndex The hovered primitive index
     * @param selectedFaceIndices Set of selected primitive indices
     * @param faceCount Total number of primitives
     * @return true if should render, false otherwise
     */
    private boolean shouldRender(int hoveredFaceIndex, java.util.Set<Integer> selectedFaceIndices, int faceCount) {
        if (faceCount == 0) {
            return false;
        }

        // Only render if there's something to show
        return hoveredFaceIndex >= 0 || (selectedFaceIndices != null && !selectedFaceIndices.isEmpty());
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
     * Setup OpenGL render state for primitive overlay rendering.
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
     * Render visible primitives (hovered and/or selected) - multi-selection support.
     *
     * @param vbo The vertex buffer object for color updates
     * @param hoveredFaceIndex The hovered primitive index
     * @param selectedFaceIndices Set of selected primitive indices
     */
    private void renderVisibleFaces(int vbo, int hoveredFaceIndex, java.util.Set<Integer> selectedFaceIndices) {
        // Render hovered primitive first (on top)
        if (hoveredFaceIndex >= 0) {
            renderFaceWithColor(vbo, hoveredFaceIndex, hoverPrimitiveColor);
        }

        // Render all selected primitives (except hovered one to avoid double-render)
        if (selectedFaceIndices != null) {
            for (int selectedFaceIndex : selectedFaceIndices) {
                if (selectedFaceIndex >= 0 && selectedFaceIndex != hoveredFaceIndex) {
                    renderFaceWithColor(vbo, selectedFaceIndex, selectedPrimitiveColor);
                }
            }
        }
    }

    /**
     * Render a single polygon primitive with a specific color.
     * Updates VBO color data, renders the primitive, then restores default color.
     *
     * <p>In triangle mode with grouped primitives:
     * <ul>
     *   <li>faceIndex refers to the original primitive ID</li>
     *   <li>Renders ALL triangles belonging to that original primitive</li>
     * </ul>
     *
     * @param vbo The vertex buffer object for color updates
     * @param faceIndex The primitive index to render (original primitive ID in triangle mode)
     * @param color The color to use (RGBA with alpha for transparency)
     */
    private void renderFaceWithColor(int vbo, int faceIndex, Vector4f color) {
        int colorOffsetBytes = COLOR_OFFSET_FLOATS * Float.BYTES;

        if (triangleMode && originalFaceToTriangles.containsKey(faceIndex)) {
            // Grouped triangle mode: render ALL triangles belonging to this original primitive
            java.util.List<Integer> triangles = originalFaceToTriangles.get(faceIndex);

            // Update colors for all triangles of this primitive
            for (int triIndex : triangles) {
                int dataStart = triIndex * FLOATS_PER_TRIANGLE_VBO;
                updateTriangleFaceColors(vbo, dataStart, colorOffsetBytes, color);
            }

            // Render all triangles of this primitive
            for (int triIndex : triangles) {
                glDrawArrays(GL_TRIANGLES, triIndex * VERTICES_PER_TRIANGLE, VERTICES_PER_TRIANGLE);
            }

            // Restore default colors for all triangles
            for (int triIndex : triangles) {
                int dataStart = triIndex * FLOATS_PER_TRIANGLE_VBO;
                updateTriangleFaceColors(vbo, dataStart, colorOffsetBytes, defaultPrimitiveColor);
            }
        } else if (!triangleMode) {
            // Polygon mode: uses pre-tessellated geometry with fixed vertex count per primitive
            int dataStart = faceIndex * MeshManager.FLOATS_PER_FACE_VBO;

            // Update color in VBO
            updatePolygonColors(vbo, dataStart, colorOffsetBytes, color);

            // Render the primitive (tessellated into triangles)
            glDrawArrays(GL_TRIANGLES, faceIndex * MeshManager.VERTICES_PER_FACE, MeshManager.VERTICES_PER_FACE);

            // Restore default color
            updatePolygonColors(vbo, dataStart, colorOffsetBytes, defaultPrimitiveColor);
        }
    }

    /**
     * Update color data for all vertices of a polygon primitive in the VBO.
     * Works with any tessellated polygon (pre-converted to triangles).
     *
     * @param vbo The vertex buffer object
     * @param dataStart Starting offset in the VBO (in floats)
     * @param colorOffsetBytes Byte offset to color data within each vertex
     * @param color The color to set
     */
    private void updatePolygonColors(int vbo, int dataStart, int colorOffsetBytes, Vector4f color) {
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        float[] colorData = new float[] { color.x, color.y, color.z, color.w };
        for (int i = 0; i < MeshManager.VERTICES_PER_FACE; i++) {
            int vboOffset = (dataStart + (i * MeshManager.FLOATS_PER_VERTEX)) * Float.BYTES + colorOffsetBytes;
            glBufferSubData(GL_ARRAY_BUFFER, vboOffset, colorData);
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Update color data for all vertices of a triangle primitive in the VBO.
     * Used in subdivided/triangulated mode where primitives are individual triangles.
     *
     * @param vbo The vertex buffer object
     * @param dataStart Starting offset in the VBO (in floats)
     * @param colorOffsetBytes Byte offset to color data within each vertex
     * @param color The color to set
     */
    private void updateTriangleFaceColors(int vbo, int dataStart, int colorOffsetBytes, Vector4f color) {
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        float[] colorData = new float[] { color.x, color.y, color.z, color.w };
        for (int i = 0; i < VERTICES_PER_TRIANGLE; i++) {
            int vboOffset = (dataStart + (i * FLOATS_PER_VERTEX_TRIANGLE)) * Float.BYTES + colorOffsetBytes;
            glBufferSubData(GL_ARRAY_BUFFER, vboOffset, colorData);
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Set triangle mode for rendering.
     * Triangle mode is used after subdivision when primitives are individual triangles
     * rather than pre-tessellated polygons.
     *
     * @param enabled true to enable triangle mode, false for polygon mode
     */
    public void setTriangleMode(boolean enabled) {
        this.triangleMode = enabled;
        logger.debug("Triangle mode set to: {}", enabled);
    }

    /**
     * Set the mapping from original primitive IDs to triangle indices.
     * Used for grouped primitive rendering after subdivision.
     *
     * @param mapping Map from original primitive ID to list of triangle indices
     */
    public void setOriginalFaceToTriangles(java.util.Map<Integer, java.util.List<Integer>> mapping) {
        this.originalFaceToTriangles = mapping != null ? mapping : new java.util.HashMap<>();
        logger.debug("Original primitive to triangles mapping set: {} primitives", originalFaceToTriangles.size());
    }

    /**
     * Set the total triangle count (used in triangle mode).
     *
     * @param count Total number of triangles
     */
    public void setTriangleCount(int count) {
        // Total triangle count (used in triangle mode)
        logger.debug("Triangle count set to: {}", count);
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
     * Get the default (invisible) primitive color.
     *
     * @return The default primitive color
     */
    public Vector4f getDefaultFaceColor() {
        return new Vector4f(defaultPrimitiveColor);
    }

    /**
         * Holds previous OpenGL render state for restoration.
         */
        private record RenderState(int depthFunc, boolean depthMask, boolean cullFace) {
    }

    // ========================================
    // Methods for synchronizing with GenericModelRenderer (used after subdivision)
    // ========================================

    /**
     * Set the GenericModelRenderer reference for mesh vertex position access.
     * This is used after subdivision to get updated vertex positions.
     *
     * @param renderer The GenericModelRenderer instance
     */
    public void setGenericModelRenderer(GenericModelRenderer renderer) {
        this.genericModelRenderer = renderer;
        logger.debug("GenericModelRenderer reference set for FaceOverlayRenderer");
    }

    /**
     * Synchronize cached mesh vertex positions from GenericModelRenderer.
     * Call this after subdivision operations to ensure primitive overlay vertex
     * attachments use the correct (updated) mesh vertex positions.
     *
     * <p>This mirrors the approach used in EdgeRenderer after subdivision,
     * where mesh positions are synchronized to prevent coordinate drift.
     */
    public void syncMeshVertexPositions() {
        if (genericModelRenderer == null) {
            logger.warn("Cannot sync mesh positions: GenericModelRenderer reference not set");
            return;
        }

        // Cached mesh vertex positions from GenericModelRenderer (updated after subdivision)
        float[] cachedMeshVertexPositions = genericModelRenderer.getAllMeshVertexPositions();

        if (cachedMeshVertexPositions != null) {
            logger.debug("Synced {} mesh vertex positions from GenericModelRenderer",
                cachedMeshVertexPositions.length / 3);
        }
    }
}
