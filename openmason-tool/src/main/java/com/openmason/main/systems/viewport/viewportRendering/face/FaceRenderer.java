package com.openmason.main.systems.viewport.viewportRendering.face;

import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import com.openmason.main.systems.viewport.viewportRendering.common.IGeometryExtractor;
import com.openmason.main.systems.viewport.viewportRendering.face.operations.FaceUpdateOperation;
import com.openmason.main.systems.viewport.shaders.ShaderProgram;
import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders semi-transparent face overlays for interactive highlighting.
 *
 * <p>This renderer provides visual feedback for face selection and hover states
 * by drawing colored overlays on model faces. The renderer follows SOLID principles
 * with clear separation of concerns and delegates data operations to
 * {@link com.openmason.main.systems.viewport.viewportRendering.face.operations.FaceUpdateOperation}.
 *
 * <h2>Rendering Strategy</h2>
 * <p>Uses two-pass rendering for clean visual separation:
 * <ol>
 *   <li>Model renders first (solid, textured)</li>
 *   <li>Face overlays render second (semi-transparent, colored)</li>
 * </ol>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Hover detection with ray-triangle intersection</li>
 *   <li>Selection highlighting with distinct colors</li>
 *   <li>Transparent default state (KISS principle)</li>
 *   <li>Face-to-vertex mapping for precise updates</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * <p>Follows the same pattern as EdgeRenderer and VertexRenderer for consistency.
 * Uses OpenGL VBO with interleaved vertex data (position + color RGBA).
 *
 * @see com.openmason.main.systems.viewport.viewportRendering.face.operations.FaceUpdateOperation
 * @see FaceHoverDetector
 * @see FaceExtractor
 */
public class FaceRenderer {

    private static final Logger logger = LoggerFactory.getLogger(FaceRenderer.class);

    // Layout constants
    private static final int COMPONENTS_PER_POSITION = 3; // x, y, z
    private static final int COMPONENTS_PER_COLOR = 4; // r, g, b, a
    private static final int CORNERS_PER_FACE = 4; // quad face
    private static final float VERTEX_MATCH_EPSILON = 0.0001f;
    private static final int COLOR_OFFSET_FLOATS = COMPONENTS_PER_POSITION; // Color data starts after position

    // OpenGL resources
    private int vao = 0;
    private int vbo = 0;
    private int faceCount = 0;
    private boolean initialized = false;

    // Rendering state
    private boolean enabled = false;

    // Colors with alpha for transparency
    private final Vector4f defaultFaceColor = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f); // Transparent (invisible)
    private final Vector4f hoverFaceColor = new Vector4f(1.0f, 0.6f, 0.0f, 0.3f); // Orange with 30% alpha
    private final Vector4f selectedFaceColor = new Vector4f(1.0f, 1.0f, 1.0f, 0.3f); // White with 30% alpha

    // Hover state
    private int hoveredFaceIndex = -1; // -1 means no face is hovered
    private float[] facePositions = null; // Store positions for hit testing

    // Selection state
    private int selectedFaceIndex = -1; // -1 means no face is selected

    // Face-to-vertex mapping (FIX: prevents vertex unification bug)
    // Maps face index → array of 4 unique vertex indices [v0, v1, v2, v3]
    // For cube: 6 faces, each connecting 4 of the 8 unique vertices
    private Map<Integer, int[]> faceToVertexMapping = new HashMap<>();

    // Face extraction (Single Responsibility) - uses interface for polymorphism
    private final IGeometryExtractor geometryExtractor = new FaceExtractor();

    // Face update operation (Single Responsibility)
    private final FaceUpdateOperation faceUpdateOperation = new FaceUpdateOperation();

    /**
     * Initialize the face renderer with OpenGL resources.
     * Creates VAO and VBO, configures vertex attributes for interleaved data.
     *
     * <p>Vertex layout: [position(x,y,z), color(r,g,b,a)] = 7 floats per vertex
     *
     * @throws RuntimeException if initialization fails
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

            // Configure vertex attributes (interleaved: position + color with alpha)
            // Stride = FLOATS_PER_VERTEX (3 for position, 4 for color RGBA)
            int stride = FaceUpdateOperation.FLOATS_PER_VERTEX * Float.BYTES;

            // Position attribute (location = 0)
            glVertexAttribPointer(0, COMPONENTS_PER_POSITION, GL_FLOAT, false, stride, 0);
            glEnableVertexAttribArray(0);

            // Color attribute (location = 1) - 4 components for RGBA
            glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, COMPONENTS_PER_POSITION * Float.BYTES);
            glEnableVertexAttribArray(1);

            // Unbind
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);

            initialized = true;
            logger.debug("FaceRenderer initialized");

        } catch (Exception e) {
            logger.error("Failed to initialize FaceRenderer", e);
            cleanup();
            throw new RuntimeException("FaceRenderer initialization failed", e);
        }
    }

    /**
     * Update face data from model parts with transformation applied.
     *
     * <p>Extracts face geometry from model parts, applies transformation,
     * and uploads vertex data to GPU. Delegates to FaceUpdateOperation
     * for DRY compliance and single responsibility.
     *
     * <p>This method works with any model type through polymorphic
     * geometry extraction via {@link IGeometryExtractor}.
     *
     * @param parts collection of model parts to extract faces from
     * @param transformMatrix transformation to apply to face positions
     */
    public void updateFaceData(Collection<ModelDefinition.ModelPart> parts, Matrix4f transformMatrix) {
        if (!initialized) {
            logger.warn("FaceRenderer not initialized, cannot update face data");
            return;
        }

        if (parts == null || parts.isEmpty()) {
            faceCount = 0;
            facePositions = null;
            faceToVertexMapping.clear();
            return;
        }

        try {
            // Extract faces using interface method (polymorphism + validation)
            facePositions = geometryExtractor.extractGeometry(parts, transformMatrix);

            // Face positions are [v0x,v0y,v0z, v1x,v1y,v1z, v2x,v2y,v2z, v3x,v3y,v3z, ...] (4 vertices per face)
            faceCount = facePositions.length / FaceUpdateOperation.FLOATS_PER_FACE_POSITION;

            // Delegate bulk VBO creation to operation class (DRY + Single Responsibility)
            faceUpdateOperation.updateAllFaces(vbo, facePositions, faceCount, defaultFaceColor);

            logger.debug("Updated face data: {} faces ({} floats)", faceCount, facePositions.length);

        } catch (Exception e) {
            logger.error("Error updating face data", e);
        }
    }

    /**
     * Build face-to-vertex mapping from unique vertex positions.
     * FIX: Creates mapping needed for index-based updates to prevent vertex unification.
     * Matches face corners to unique vertices using epsilon comparison.
     *
     * @param uniqueVertexPositions Array of unique vertex positions [x0,y0,z0, x1,y1,z1, ...]
     */
    public void buildFaceToVertexMapping(float[] uniqueVertexPositions) {
        if (facePositions == null || faceCount == 0) {
            logger.warn("Cannot build face mapping: no face data");
            faceToVertexMapping.clear();
            return;
        }

        if (uniqueVertexPositions == null || uniqueVertexPositions.length < COMPONENTS_PER_POSITION) {
            logger.warn("Cannot build face mapping: invalid unique vertex data");
            faceToVertexMapping.clear();
            return;
        }

        int uniqueVertexCount = uniqueVertexPositions.length / COMPONENTS_PER_POSITION;
        faceToVertexMapping.clear();

        // For each face, find which unique vertices it connects
        for (int faceIdx = 0; faceIdx < faceCount; faceIdx++) {
            int[] vertexIndices = findVertexIndicesForFace(faceIdx, uniqueVertexPositions, uniqueVertexCount);
            faceToVertexMapping.put(faceIdx, vertexIndices);
        }

        logger.debug("Built face-to-vertex mapping for {} faces", faceCount);
    }

    /**
     * Find vertex indices for a specific face by matching positions.
     *
     * @param faceIdx the face index
     * @param uniqueVertexPositions array of unique vertex positions
     * @param uniqueVertexCount number of unique vertices
     * @return array of 4 vertex indices for the face corners
     */
    private int[] findVertexIndicesForFace(int faceIdx, float[] uniqueVertexPositions, int uniqueVertexCount) {
        int facePosIdx = faceIdx * FaceUpdateOperation.FLOATS_PER_FACE_POSITION;
        int[] vertexIndices = new int[CORNERS_PER_FACE];

        for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
            Vector3f faceVertex = extractFaceCornerPosition(facePosIdx, corner);
            int matchedIndex = findMatchingVertexIndex(faceVertex, uniqueVertexPositions, uniqueVertexCount);

            vertexIndices[corner] = matchedIndex;

            if (matchedIndex == -1) {
                logger.warn("Face {} corner {} has unmatched vertex", faceIdx, corner);
            }
        }

        return vertexIndices;
    }

    /**
     * Extract position of a face corner.
     *
     * @param facePosIdx starting index of face in positions array
     * @param corner corner index (0-3)
     * @return position vector
     */
    private Vector3f extractFaceCornerPosition(int facePosIdx, int corner) {
        int cornerPosIdx = facePosIdx + (corner * COMPONENTS_PER_POSITION);
        return new Vector3f(
            facePositions[cornerPosIdx],
            facePositions[cornerPosIdx + 1],
            facePositions[cornerPosIdx + 2]
        );
    }

    /**
     * Find the index of a unique vertex that matches the given position.
     *
     * @param position the position to match
     * @param uniqueVertexPositions array of unique vertex positions
     * @param uniqueVertexCount number of unique vertices
     * @return index of matching vertex, or -1 if no match found
     */
    private int findMatchingVertexIndex(Vector3f position, float[] uniqueVertexPositions, int uniqueVertexCount) {
        for (int vIdx = 0; vIdx < uniqueVertexCount; vIdx++) {
            Vector3f uniqueVertex = extractVertexPosition(uniqueVertexPositions, vIdx);

            if (position.distance(uniqueVertex) < VERTEX_MATCH_EPSILON) {
                return vIdx;
            }
        }
        return -1;
    }

    /**
     * Extract a vertex position from the positions array.
     *
     * @param positions the positions array
     * @param vertexIdx the vertex index
     * @return position vector
     */
    private Vector3f extractVertexPosition(float[] positions, int vertexIdx) {
        int vPosIdx = vertexIdx * COMPONENTS_PER_POSITION;
        return new Vector3f(
            positions[vPosIdx],
            positions[vPosIdx + 1],
            positions[vPosIdx + 2]
        );
    }

    /**
     * Get the vertex indices for a specific face.
     *
     * @param faceIndex the face index
     * @return array of 4 vertex indices [v0, v1, v2, v3], or null if invalid
     */
    public int[] getFaceVertexIndices(int faceIndex) {
        if (faceIndex < 0 || faceIndex >= faceCount) {
            return null;
        }
        return faceToVertexMapping.get(faceIndex);
    }

    /**
     * Get the 4 corner vertices of a face.
     *
     * @param faceIndex the face index
     * @return array of 4 vertices [v0, v1, v2, v3], or null if invalid
     */
    public Vector3f[] getFaceVertices(int faceIndex) {
        if (faceIndex < 0 || faceIndex >= faceCount || facePositions == null) {
            return null;
        }

        int posIndex = faceIndex * FaceUpdateOperation.FLOATS_PER_FACE_POSITION;

        if (posIndex + (FaceUpdateOperation.FLOATS_PER_FACE_POSITION - 1) >= facePositions.length) {
            return null;
        }

        Vector3f[] vertices = new Vector3f[CORNERS_PER_FACE];
        for (int i = 0; i < CORNERS_PER_FACE; i++) {
            vertices[i] = extractFaceCornerPosition(posIndex, i);
        }

        return vertices;
    }

    /**
     * Update face position by vertex indices (index-based update to prevent unification).
     * Delegates to FaceUpdateOperation for clean separation of concerns.
     *
     * @param faceIndex the face index
     * @param vertexIndices array of 4 unique vertex indices
     * @param newPositions array of 4 new positions
     */
    public void updateFaceByVertexIndices(int faceIndex, int[] vertexIndices, Vector3f[] newPositions) {
        if (!initialized) {
            logger.warn("Cannot update face: renderer not initialized");
            return;
        }

        // Delegate to operation class (Single Responsibility Principle)
        faceUpdateOperation.updateFace(vbo, facePositions, faceCount, faceIndex, vertexIndices, newPositions);
    }

    /**
     * Handle mouse movement for face hover detection.
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

        if (facePositions == null || faceCount == 0) {
            return;
        }

        // Detect hovered face using ray-triangle intersection
        int newHoveredFace = FaceHoverDetector.detectHoveredFace(
            mouseX, mouseY,
            viewportWidth, viewportHeight,
            viewMatrix, projectionMatrix, modelMatrix,
            facePositions,
            faceCount
        );

        // Update hover state if changed
        if (newHoveredFace != hoveredFaceIndex) {
            int previousHover = hoveredFaceIndex;
            hoveredFaceIndex = newHoveredFace;

            logger.debug("Face hover changed: {} -> {} (total faces: {})",
                        previousHover, hoveredFaceIndex, faceCount);
        }
    }

    /**
     * Clear face hover state.
     */
    public void clearHover() {
        if (hoveredFaceIndex != -1) {
            logger.debug("Clearing face hover (was face {})", hoveredFaceIndex);
            hoveredFaceIndex = -1;
        }
    }

    /**
     * Render face overlays with semi-transparent highlighting.
     * KISS: Only render hovered/selected faces, skip default (transparent) faces.
     *
     * @param shader The shader program to use
     * @param context The render context
     * @param modelMatrix The model transformation matrix
     */
    public void render(ShaderProgram shader, RenderContext context, Matrix4f modelMatrix) {
        if (!shouldRender()) {
            return;
        }

        try {
            setupShaderAndMatrices(shader, context, modelMatrix);
            RenderState previousState = setupRenderState();

            glBindVertexArray(vao);
            renderVisibleFaces();
            glBindVertexArray(0);

            restoreRenderState(previousState);

        } catch (Exception e) {
            logger.error("Error rendering faces", e);
        } finally {
            glUseProgram(0);
        }
    }

    /**
     * Check if rendering should proceed.
     *
     * @return true if should render, false otherwise
     */
    private boolean shouldRender() {
        if (!initialized) {
            logger.warn("FaceRenderer not initialized");
            return false;
        }

        if (!enabled || faceCount == 0) {
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
     */
    private void renderVisibleFaces() {
        if (hoveredFaceIndex >= 0) {
            renderFaceWithColor(hoveredFaceIndex, hoverFaceColor);
        }

        if (selectedFaceIndex >= 0 && selectedFaceIndex != hoveredFaceIndex) {
            renderFaceWithColor(selectedFaceIndex, selectedFaceColor);
        }
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

    /**
     * Render a single face with a specific color.
     * Updates VBO color data, renders the face, then restores default color.
     * Uses shared constants from FaceUpdateOperation for DRY compliance.
     *
     * @param faceIndex the face index to render
     * @param color the color to use (RGBA with alpha for transparency)
     */
    private void renderFaceWithColor(int faceIndex, Vector4f color) {
        int dataStart = faceIndex * FaceUpdateOperation.FLOATS_PER_FACE_VBO;
        int colorOffsetBytes = COLOR_OFFSET_FLOATS * Float.BYTES;

        // Update color in VBO
        updateFaceColors(dataStart, colorOffsetBytes, color);

        // Render the face (2 triangles = 6 vertices)
        glDrawArrays(GL_TRIANGLES, faceIndex * FaceUpdateOperation.VERTICES_PER_FACE, FaceUpdateOperation.VERTICES_PER_FACE);

        // Restore default color
        updateFaceColors(dataStart, colorOffsetBytes, defaultFaceColor);
    }

    /**
     * Update color data for all vertices of a face in the VBO.
     *
     * @param dataStart starting offset in the VBO
     * @param colorOffsetBytes byte offset to color data within each vertex
     * @param color the color to set
     */
    private void updateFaceColors(int dataStart, int colorOffsetBytes, Vector4f color) {
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        float[] colorData = new float[] { color.x, color.y, color.z, color.w };
        for (int i = 0; i < FaceUpdateOperation.VERTICES_PER_FACE; i++) {
            int vboOffset = (dataStart + (i * FaceUpdateOperation.FLOATS_PER_VERTEX)) * Float.BYTES + colorOffsetBytes;
            glBufferSubData(GL_ARRAY_BUFFER, vboOffset, colorData);
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Clean up OpenGL resources (RAII pattern).
     * Deletes VAO and VBO, clears all state data.
     *
     * <p>Should be called when the renderer is no longer needed
     * to prevent resource leaks.
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
        faceCount = 0;
        facePositions = null;
        faceToVertexMapping.clear();
        initialized = false;
        logger.debug("FaceRenderer cleaned up");
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

    public int getHoveredFaceIndex() {
        return hoveredFaceIndex;
    }

    public int getSelectedFaceIndex() {
        return selectedFaceIndex;
    }

    public void setSelectedFace(int faceIndex) {
        if (faceIndex < -1 || faceIndex >= faceCount) {
            logger.warn("Invalid face index: {}, valid range is -1 to {}", faceIndex, faceCount - 1);
            return;
        }

        this.selectedFaceIndex = faceIndex;

        if (faceIndex >= 0) {
            logger.debug("Selected face {}", faceIndex);
        } else {
            logger.debug("Cleared face selection");
        }
    }

    public void clearSelection() {
        this.selectedFaceIndex = -1;
    }

    public int getFaceCount() {
        return faceCount;
    }

    public float[] getFacePositions() {
        return facePositions;
    }
}
