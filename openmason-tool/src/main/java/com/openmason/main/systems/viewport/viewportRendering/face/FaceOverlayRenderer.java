package com.openmason.main.systems.viewport.viewportRendering.face;

import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import com.openmason.main.systems.viewport.viewportRendering.mesh.MeshManager;
import com.openmason.main.systems.rendering.core.shaders.ShaderProgram;
import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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
 * @see MeshManager
 */
public class FaceOverlayRenderer {

    private static final Logger logger = LoggerFactory.getLogger(FaceOverlayRenderer.class);

    // Layout constants
    private static final int COMPONENTS_PER_POSITION = 3; // x, y, z
    private static final int COLOR_OFFSET_FLOATS = COMPONENTS_PER_POSITION; // Color data starts after position
    private static final float POSITION_EPSILON = 0.01f; // Tolerance for position matching (same as GenericModelRenderer)

    // Triangle mode constants (3 vertices per face, post-subdivision)
    private static final int VERTICES_PER_TRIANGLE = 3;
    private static final int FLOATS_PER_VERTEX_TRIANGLE = 7; // position (3) + color (4)
    private static final int FLOATS_PER_TRIANGLE_VBO = VERTICES_PER_TRIANGLE * FLOATS_PER_VERTEX_TRIANGLE; // 21

    // Color definitions with alpha for transparency
    private final Vector4f defaultFaceColor = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f); // Transparent (invisible)
    private final Vector4f hoverFaceColor = new Vector4f(1.0f, 0.6f, 0.0f, 0.3f); // Orange with 30% alpha
    private final Vector4f selectedFaceColor = new Vector4f(1.0f, 1.0f, 1.0f, 0.3f); // White with 30% alpha

    // Reference to GenericModelRenderer for mesh vertex position access after subdivision
    private GenericModelRenderer genericModelRenderer = null;

    // Cached mesh vertex positions from GenericModelRenderer (updated after subdivision)
    private float[] cachedMeshVertexPositions = null;

    // Triangle mode flag (true after subdivision, false for original quad faces)
    private boolean triangleMode = false;

    // Maps original face ID to list of triangle indices (used in triangle mode)
    private java.util.Map<Integer, java.util.List<Integer>> originalFaceToTriangles = new java.util.HashMap<>();

    // Total triangle count (used in triangle mode)
    private int triangleCount = 0;

    /**
     * Render face overlays with semi-transparent highlighting (single selection - backward compat).
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
        java.util.Set<Integer> selectedSet = new java.util.HashSet<>();
        if (selectedFaceIndex >= 0) {
            selectedSet.add(selectedFaceIndex);
        }
        render(vao, vbo, shader, context, modelMatrix, hoveredFaceIndex, selectedSet, faceCount);
    }

    /**
     * Render face overlays with semi-transparent highlighting (multi-selection).
     * KISS: Only render hovered/selected faces, skip default (transparent) faces.
     *
     * @param vao The vertex array object to bind
     * @param vbo The vertex buffer object for color updates
     * @param shader The shader program to use
     * @param context The render context
     * @param modelMatrix The model transformation matrix
     * @param hoveredFaceIndex The currently hovered face index (-1 if none)
     * @param selectedFaceIndices Set of selected face indices
     * @param faceCount Total number of faces
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
     * @param hoveredFaceIndex The hovered face index
     * @param selectedFaceIndices Set of selected face indices
     * @param faceCount Total number of faces
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
     * Render visible faces (hovered and/or selected) - multi-selection support.
     *
     * @param vbo The vertex buffer object for color updates
     * @param hoveredFaceIndex The hovered face index
     * @param selectedFaceIndices Set of selected face indices
     */
    private void renderVisibleFaces(int vbo, int hoveredFaceIndex, java.util.Set<Integer> selectedFaceIndices) {
        // Render hovered face first (on top)
        if (hoveredFaceIndex >= 0) {
            renderFaceWithColor(vbo, hoveredFaceIndex, hoverFaceColor);
        }

        // Render all selected faces (except hovered one to avoid double-render)
        if (selectedFaceIndices != null) {
            for (int selectedFaceIndex : selectedFaceIndices) {
                if (selectedFaceIndex >= 0 && selectedFaceIndex != hoveredFaceIndex) {
                    renderFaceWithColor(vbo, selectedFaceIndex, selectedFaceColor);
                }
            }
        }
    }

    /**
     * Render a single face with a specific color.
     * Updates VBO color data, renders the face, then restores default color.
     *
     * <p>In triangle mode with grouped faces:
     * <ul>
     *   <li>faceIndex refers to the original face ID (0-5 for cube)</li>
     *   <li>Renders ALL triangles belonging to that original face</li>
     * </ul>
     *
     * @param vbo The vertex buffer object for color updates
     * @param faceIndex The face index to render (original face ID in triangle mode)
     * @param color The color to use (RGBA with alpha for transparency)
     */
    private void renderFaceWithColor(int vbo, int faceIndex, Vector4f color) {
        int colorOffsetBytes = COLOR_OFFSET_FLOATS * Float.BYTES;

        if (triangleMode && originalFaceToTriangles.containsKey(faceIndex)) {
            // Grouped triangle mode: render ALL triangles belonging to this original face
            java.util.List<Integer> triangles = originalFaceToTriangles.get(faceIndex);

            // Update colors for all triangles of this face
            for (int triIndex : triangles) {
                int dataStart = triIndex * FLOATS_PER_TRIANGLE_VBO;
                updateTriangleFaceColors(vbo, dataStart, colorOffsetBytes, color);
            }

            // Render all triangles of this face
            for (int triIndex : triangles) {
                glDrawArrays(GL_TRIANGLES, triIndex * VERTICES_PER_TRIANGLE, VERTICES_PER_TRIANGLE);
            }

            // Restore default colors for all triangles
            for (int triIndex : triangles) {
                int dataStart = triIndex * FLOATS_PER_TRIANGLE_VBO;
                updateTriangleFaceColors(vbo, dataStart, colorOffsetBytes, defaultFaceColor);
            }
        } else if (!triangleMode) {
            // Quad mode: 6 vertices per face (original)
            int dataStart = faceIndex * MeshManager.FLOATS_PER_FACE_VBO;

            // Update color in VBO
            updateQuadFaceColors(vbo, dataStart, colorOffsetBytes, color);

            // Render the face (2 triangles = 6 vertices)
            glDrawArrays(GL_TRIANGLES, faceIndex * MeshManager.VERTICES_PER_FACE, MeshManager.VERTICES_PER_FACE);

            // Restore default color
            updateQuadFaceColors(vbo, dataStart, colorOffsetBytes, defaultFaceColor);
        }
    }

    /**
     * Update color data for all vertices of a quad face in the VBO.
     *
     * @param vbo The vertex buffer object
     * @param dataStart Starting offset in the VBO (in floats)
     * @param colorOffsetBytes Byte offset to color data within each vertex
     * @param color The color to set
     */
    private void updateQuadFaceColors(int vbo, int dataStart, int colorOffsetBytes, Vector4f color) {
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        float[] colorData = new float[] { color.x, color.y, color.z, color.w };
        for (int i = 0; i < MeshManager.VERTICES_PER_FACE; i++) {
            int vboOffset = (dataStart + (i * MeshManager.FLOATS_PER_VERTEX)) * Float.BYTES + colorOffsetBytes;
            glBufferSubData(GL_ARRAY_BUFFER, vboOffset, colorData);
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Update color data for all vertices of a triangle face in the VBO.
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
     * Triangle mode is used after subdivision when faces are individual triangles
     * rather than quads (2 triangles).
     *
     * @param enabled true to enable triangle mode, false for quad mode
     */
    public void setTriangleMode(boolean enabled) {
        this.triangleMode = enabled;
        logger.debug("Triangle mode set to: {}", enabled);
    }

    /**
     * Check if triangle mode is enabled.
     *
     * @return true if triangle mode is enabled
     */
    public boolean isTriangleMode() {
        return triangleMode;
    }

    /**
     * Set the mapping from original face IDs to triangle indices.
     * Used for grouped face rendering after subdivision.
     *
     * @param mapping Map from original face ID to list of triangle indices
     */
    public void setOriginalFaceToTriangles(java.util.Map<Integer, java.util.List<Integer>> mapping) {
        this.originalFaceToTriangles = mapping != null ? mapping : new java.util.HashMap<>();
        logger.debug("Original face to triangles mapping set: {} faces", originalFaceToTriangles.size());
    }

    /**
     * Set the total triangle count (used in triangle mode).
     *
     * @param count Total number of triangles
     */
    public void setTriangleCount(int count) {
        this.triangleCount = count;
        logger.debug("Triangle count set to: {}", count);
    }

    /**
     * Get the total triangle count.
     *
     * @return Total number of triangles
     */
    public int getTriangleCount() {
        return triangleCount;
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
     * Call this after subdivision operations to ensure face overlay vertex
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

        cachedMeshVertexPositions = genericModelRenderer.getAllMeshVertexPositions();

        if (cachedMeshVertexPositions != null) {
            logger.debug("Synced {} mesh vertex positions from GenericModelRenderer",
                cachedMeshVertexPositions.length / 3);
        }
    }

    /**
     * Find all mesh vertex indices at a given position.
     * Uses the same position-matching strategy as GenericModelRenderer.findMeshVerticesAtPosition().
     * This is critical for correct vertex attachment after edge subdivision.
     *
     * @param position The position to search for
     * @return List of mesh vertex indices at that position, or empty list if none found
     */
    public List<Integer> findMeshVerticesAtPosition(Vector3f position) {
        // First try using GenericModelRenderer directly (most accurate)
        if (genericModelRenderer != null) {
            return genericModelRenderer.findMeshVerticesAtPosition(position, POSITION_EPSILON);
        }

        // Fallback to cached positions
        java.util.List<Integer> result = new java.util.ArrayList<>();
        if (cachedMeshVertexPositions == null || position == null) {
            return result;
        }

        int count = cachedMeshVertexPositions.length / 3;
        for (int i = 0; i < count; i++) {
            float dx = cachedMeshVertexPositions[i * 3] - position.x;
            float dy = cachedMeshVertexPositions[i * 3 + 1] - position.y;
            float dz = cachedMeshVertexPositions[i * 3 + 2] - position.z;
            float distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < POSITION_EPSILON * POSITION_EPSILON) {
                result.add(i);
            }
        }
        return result;
    }

    /**
     * Get the total mesh vertex count from GenericModelRenderer.
     * Used to determine VBO layout after subdivision.
     *
     * @return Total mesh vertex count, or 0 if not available
     */
    public int getMeshVertexCount() {
        if (genericModelRenderer != null) {
            return genericModelRenderer.getTotalVertexCount();
        }
        if (cachedMeshVertexPositions != null) {
            return cachedMeshVertexPositions.length / 3;
        }
        return 0;
    }

    /**
     * Get mesh vertex position by index.
     * Used to determine face overlay vertex attachments.
     *
     * @param meshVertexIndex The mesh vertex index
     * @return The vertex position, or null if invalid
     */
    public Vector3f getMeshVertexPosition(int meshVertexIndex) {
        if (genericModelRenderer != null) {
            return genericModelRenderer.getVertexPosition(meshVertexIndex);
        }

        if (cachedMeshVertexPositions == null || meshVertexIndex < 0) {
            return null;
        }

        int offset = meshVertexIndex * 3;
        if (offset + 2 >= cachedMeshVertexPositions.length) {
            return null;
        }

        return new Vector3f(
            cachedMeshVertexPositions[offset],
            cachedMeshVertexPositions[offset + 1],
            cachedMeshVertexPositions[offset + 2]
        );
    }
}
