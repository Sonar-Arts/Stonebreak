package com.openmason.main.systems.rendering.model.gmr.subrenders.face;

import com.openmason.engine.rendering.model.MeshChangeListener;
import com.openmason.engine.rendering.model.gmr.topology.MeshTopology;
import com.openmason.main.systems.rendering.model.gmr.subrenders.MeshOverlayTheme;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.viewportRendering.RenderContext;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.model.GenericModelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders Blender-style face highlights in the 3D viewport:
 * <ul>
 *   <li><b>Translucent fills</b> — selected faces are tinted orange, the active
 *       (last-selected) face white, and the hovered face gets a faint pre-highlight
 *       (FACE shader, depth writes off)</li>
 *   <li><b>Boundary outlines</b> — GL_LINES over the fill for crisp face borders
 *       (same VBO-recolor pattern as {@link com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeRenderer})</li>
 *   <li><b>Face dots</b> — centroid points for every face while in FACE edit mode,
 *       colored by selection state like Blender's facedots (VERTEX point shader)</li>
 * </ul>
 * Colors come from {@link MeshOverlayTheme}.
 *
 * @see FaceHoverDetector
 */
public class FaceRenderer implements MeshChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(FaceRenderer.class);

    private static final int COMPONENTS_PER_POSITION = 3;

    // ── Edge highlight constants (matches EdgeRenderer pattern) ──
    /** Floats per line vertex: position(3) + color(3). */
    private static final int LINE_FLOATS_PER_VERTEX = 6;
    private static final int LINE_STRIDE_BYTES = LINE_FLOATS_PER_VERTEX * Float.BYTES;
    private static final int LINE_COLOR_OFFSET_BYTES = 3 * Float.BYTES;
    private static final int VERTICES_PER_LINE_SEGMENT = 2;

    /** Line widths. */
    private static final float HOVER_LINE_WIDTH = 2.0f;
    private static final float SELECTED_LINE_WIDTH = 2.0f;

    /** Colors (Blender default theme, see MeshOverlayTheme). */
    private static final Vector3f DEFAULT_EDGE_COLOR = new Vector3f(0.0f, 0.0f, 0.0f); // invisible (won't be drawn)
    private static final Vector3f HOVER_EDGE_COLOR = MeshOverlayTheme.FACE_HOVER_OUTLINE;
    private static final Vector3f SELECTED_EDGE_COLOR = MeshOverlayTheme.FACE_SELECT_OUTLINE;
    private static final Vector3f ACTIVE_EDGE_COLOR = MeshOverlayTheme.FACE_ACTIVE_OUTLINE;

    /** Depth range far value biasing overlays toward the camera on coplanar geometry. */
    private static final double OVERLAY_DEPTH_RANGE_FAR = 0.99999;

    // ── Edge highlight OpenGL resources ──
    private int lineVao = 0;
    private int lineVbo = 0;
    private boolean lineResourcesInitialized = false;

    /** Per-face: [startLineVertex, lineVertexCount] in the line VBO. */
    private int[] faceLineOffsets = null;
    private int[] faceLineCounts = null;
    private int totalLineVertices = 0;

    // ── Translucent fill OpenGL resources (position-only, FACE shader) ──
    private int fillVao = 0;
    private int fillVbo = 0;
    private boolean fillResourcesInitialized = false;
    private boolean fillDataDirty = true;

    /** Per-face: [startFillVertex, fillVertexCount] in the fill VBO. */
    private int[] faceFillOffsets = null;
    private int[] faceFillCounts = null;
    private int totalFillVertices = 0;

    // ── Face dot OpenGL resources (pos+color, VERTEX point shader) ──
    private int dotVao = 0;
    private int dotVbo = 0;
    private boolean dotResourcesInitialized = false;
    private boolean dotDataDirty = true;
    private int dotCount = 0;

    // ── State ──
    private boolean initialized = false;
    private boolean enabled = false;
    private int faceCount = 0;

    // Hover / selection (insertion order preserved so the last-selected face
    // can be shown as the "active" element, like Blender)
    private int hoveredFaceIndex = -1;
    private Set<Integer> selectedFaceIndices = new LinkedHashSet<>();
    private int activeFaceIndex = -1;
    private int editingFaceIndex = -1;

    // Topology info for hover detection
    private int[] storedVerticesPerFace = null;
    private int[] storedFaceOffsets = null;
    private float[] topologyFacePositions = null;

    // GenericModelRenderer integration
    private GenericModelRenderer genericModelRenderer = null;
    private Map<Integer, List<Integer>> originalFaceToTriangles = new HashMap<>();
    private float[] trianglePositions = null;
    private int triangleCount = 0;

    // =========================================================================
    // Initialization
    // =========================================================================

    public void initialize() {
        if (initialized) {
            return;
        }
        // Nothing heavyweight here — line resources are created lazily in rebuildLineVBO
        initialized = true;
        logger.debug("FaceRenderer initialized");
    }

    // =========================================================================
    // Face data loading
    // =========================================================================

    /**
     * Rebuild face data from GenericModelRenderer (primary path).
     */
    public void rebuildFromGenericModelRenderer() {
        if (!initialized || genericModelRenderer == null) {
            return;
        }

        // An empty mesh is a benign transient: every model load clears the part
        // set (empty rebuild) before the real rebuild repopulates it. Only warn
        // when geometry exists without a mapping — that is the anomalous case.
        triangleCount = genericModelRenderer.getTriangleCount();
        if (triangleCount == 0) {
            faceCount = 0;
            trianglePositions = null;
            originalFaceToTriangles.clear();
            totalFillVertices = 0;
            dotCount = 0;
            return;
        }

        if (!genericModelRenderer.hasTriangleToFaceMapping()) {
            logger.warn("GenericModelRenderer has no triangle-to-face mapping");
            return;
        }

        try {
            float[] meshVertices = genericModelRenderer.getAllMeshVertexPositions();
            int[] triangleIndices = genericModelRenderer.getTriangleIndices();
            int originalFaceCount = genericModelRenderer.getOriginalFaceCount();

            if (meshVertices == null || triangleIndices == null) {
                logger.warn("Cannot rebuild face data: mesh data is null");
                return;
            }

            // Build face-to-triangles mapping
            originalFaceToTriangles.clear();
            for (int t = 0; t < triangleCount; t++) {
                int originalFaceId = genericModelRenderer.getOriginalFaceIdForTriangle(t);
                originalFaceToTriangles
                    .computeIfAbsent(originalFaceId, k -> new ArrayList<>())
                    .add(t);
            }

            // Build triangle positions for hover detection (9 floats per triangle)
            trianglePositions = new float[triangleCount * 9];
            for (int t = 0; t < triangleCount; t++) {
                int i0 = triangleIndices[t * 3];
                int i1 = triangleIndices[t * 3 + 1];
                int i2 = triangleIndices[t * 3 + 2];

                int offset = t * 9;
                trianglePositions[offset]     = meshVertices[i0 * 3];
                trianglePositions[offset + 1] = meshVertices[i0 * 3 + 1];
                trianglePositions[offset + 2] = meshVertices[i0 * 3 + 2];
                trianglePositions[offset + 3] = meshVertices[i1 * 3];
                trianglePositions[offset + 4] = meshVertices[i1 * 3 + 1];
                trianglePositions[offset + 5] = meshVertices[i1 * 3 + 2];
                trianglePositions[offset + 6] = meshVertices[i2 * 3];
                trianglePositions[offset + 7] = meshVertices[i2 * 3 + 1];
                trianglePositions[offset + 8] = meshVertices[i2 * 3 + 2];
            }

            faceCount = originalFaceCount;

            // Build line VBO for edge highlighting
            rebuildLineVBO();

            // Fill + dot VBOs are rebuilt lazily on next render
            fillDataDirty = true;
            dotDataDirty = true;

            logger.debug("Rebuilt face data: {} faces, {} triangles", faceCount, triangleCount);

        } catch (Exception e) {
            logger.error("Error rebuilding face data from GenericModelRenderer", e);
        }
    }

    // =========================================================================
    // Line VBO — boundary edges for every face
    // =========================================================================

    /**
     * Build a GL_LINES VBO containing the boundary edges of every face.
     * Format: interleaved pos(3) + color(3) per vertex, same as EdgeRenderer.
     * Default color is black (edges won't actually be drawn unless hovered/selected).
     */
    private void rebuildLineVBO() {
        if (genericModelRenderer == null) {
            return;
        }

        MeshTopology topology = genericModelRenderer.getTopology();
        if (topology == null || faceCount == 0) {
            totalLineVertices = 0;
            return;
        }

        // First pass: count total line vertices
        faceLineOffsets = new int[faceCount];
        faceLineCounts = new int[faceCount];
        totalLineVertices = 0;

        for (int f = 0; f < faceCount; f++) {
            var face = topology.getFace(f);
            int edgeCount = (face != null) ? face.vertexIndices().length : 0;
            int lineVertCount = edgeCount * VERTICES_PER_LINE_SEGMENT;
            faceLineOffsets[f] = totalLineVertices;
            faceLineCounts[f] = lineVertCount;
            totalLineVertices += lineVertCount;
        }

        if (totalLineVertices == 0) {
            return;
        }

        // Second pass: fill vertex data
        float[] data = new float[totalLineVertices * LINE_FLOATS_PER_VERTEX];

        for (int f = 0; f < faceCount; f++) {
            var face = topology.getFace(f);
            if (face == null) continue;

            int[] vertIndices = face.vertexIndices();
            int baseVertex = faceLineOffsets[f];

            for (int i = 0; i < vertIndices.length; i++) {
                int next = (i + 1) % vertIndices.length;

                Vector3f posA = genericModelRenderer.getUniqueVertexPosition(vertIndices[i]);
                Vector3f posB = genericModelRenderer.getUniqueVertexPosition(vertIndices[next]);
                if (posA == null || posB == null) continue;

                int vIdx = baseVertex + i * VERTICES_PER_LINE_SEGMENT;
                int off0 = vIdx * LINE_FLOATS_PER_VERTEX;
                int off1 = (vIdx + 1) * LINE_FLOATS_PER_VERTEX;

                // Vertex A
                data[off0]     = posA.x;
                data[off0 + 1] = posA.y;
                data[off0 + 2] = posA.z;
                data[off0 + 3] = DEFAULT_EDGE_COLOR.x;
                data[off0 + 4] = DEFAULT_EDGE_COLOR.y;
                data[off0 + 5] = DEFAULT_EDGE_COLOR.z;

                // Vertex B
                data[off1]     = posB.x;
                data[off1 + 1] = posB.y;
                data[off1 + 2] = posB.z;
                data[off1 + 3] = DEFAULT_EDGE_COLOR.x;
                data[off1 + 4] = DEFAULT_EDGE_COLOR.y;
                data[off1 + 5] = DEFAULT_EDGE_COLOR.z;
            }
        }

        // Create / update GL resources
        ensureLineResources();

        glBindBuffer(GL_ARRAY_BUFFER, lineVbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        logger.debug("Built face line VBO: {} faces, {} line vertices", faceCount, totalLineVertices);
    }

    private void ensureLineResources() {
        if (lineResourcesInitialized) {
            return;
        }

        lineVao = glGenVertexArrays();
        lineVbo = glGenBuffers();

        glBindVertexArray(lineVao);
        glBindBuffer(GL_ARRAY_BUFFER, lineVbo);
        glBufferData(GL_ARRAY_BUFFER, 0, GL_DYNAMIC_DRAW);

        // Position (location 0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, LINE_STRIDE_BYTES, 0);
        glEnableVertexAttribArray(0);

        // Color (location 1)
        glVertexAttribPointer(1, 3, GL_FLOAT, false, LINE_STRIDE_BYTES, LINE_COLOR_OFFSET_BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        lineResourcesInitialized = true;
    }

    // =========================================================================
    // Fill VBO — translucent per-face triangle overlays (Blender face_select)
    // =========================================================================

    private void ensureFillResources() {
        if (fillResourcesInitialized) {
            return;
        }

        fillVao = glGenVertexArrays();
        fillVbo = glGenBuffers();

        glBindVertexArray(fillVao);
        glBindBuffer(GL_ARRAY_BUFFER, fillVbo);
        glBufferData(GL_ARRAY_BUFFER, 0, GL_DYNAMIC_DRAW);

        // Position only (location 0) — color comes from the FACE shader's uColor/uAlpha
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        fillResourcesInitialized = true;
    }

    /**
     * Build a GL_TRIANGLES VBO with every face's triangles stored contiguously,
     * so a single face can be filled with one glDrawArrays range.
     * Sourced from the trianglePositions cache (kept current by vertex-move updates).
     */
    private void rebuildFillVBO() {
        if (trianglePositions == null || faceCount == 0) {
            totalFillVertices = 0;
            return;
        }

        faceFillOffsets = new int[faceCount];
        faceFillCounts = new int[faceCount];
        totalFillVertices = 0;

        for (int f = 0; f < faceCount; f++) {
            List<Integer> tris = originalFaceToTriangles.get(f);
            int vertCount = (tris != null) ? tris.size() * 3 : 0;
            faceFillOffsets[f] = totalFillVertices;
            faceFillCounts[f] = vertCount;
            totalFillVertices += vertCount;
        }

        if (totalFillVertices == 0) {
            return;
        }

        float[] data = new float[totalFillVertices * 3];
        int cursor = 0;
        for (int f = 0; f < faceCount; f++) {
            List<Integer> tris = originalFaceToTriangles.get(f);
            if (tris == null) continue;
            for (int t : tris) {
                if (t >= 0 && t < triangleCount) {
                    System.arraycopy(trianglePositions, t * 9, data, cursor, 9);
                }
                cursor += 9;
            }
        }

        ensureFillResources();

        glBindBuffer(GL_ARRAY_BUFFER, fillVbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        logger.debug("Built face fill VBO: {} faces, {} fill vertices", faceCount, totalFillVertices);
    }

    private void drawFaceFill(int faceIndex) {
        if (faceFillOffsets == null || faceIndex < 0 || faceIndex >= faceFillOffsets.length) {
            return;
        }
        int count = faceFillCounts[faceIndex];
        if (count > 0) {
            glDrawArrays(GL_TRIANGLES, faceFillOffsets[faceIndex], count);
        }
    }

    // =========================================================================
    // Dot VBO — face centroid points (Blender facedots)
    // =========================================================================

    private void ensureDotResources() {
        if (dotResourcesInitialized) {
            return;
        }

        dotVao = glGenVertexArrays();
        dotVbo = glGenBuffers();

        glBindVertexArray(dotVao);
        glBindBuffer(GL_ARRAY_BUFFER, dotVbo);
        glBufferData(GL_ARRAY_BUFFER, 0, GL_DYNAMIC_DRAW);

        // Interleaved position(3) + color(3) for the VERTEX point shader
        int stride = 6 * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        dotResourcesInitialized = true;
    }

    /**
     * Build one centroid point per face, colored by selection state
     * (black default, orange selected, white active). Dot index == face index.
     */
    private void rebuildDotVBO() {
        if (faceCount == 0) {
            dotCount = 0;
            return;
        }

        float[] data = new float[faceCount * 6];
        Vector3f centroid = new Vector3f();

        for (int f = 0; f < faceCount; f++) {
            if (!computeFaceCentroid(f, centroid)) {
                centroid.set(0, 0, 0);
            }

            Vector3f color = resolveFaceDotColor(f);
            int off = f * 6;
            data[off]     = centroid.x;
            data[off + 1] = centroid.y;
            data[off + 2] = centroid.z;
            data[off + 3] = color.x;
            data[off + 4] = color.y;
            data[off + 5] = color.z;
        }

        dotCount = faceCount;

        ensureDotResources();

        glBindBuffer(GL_ARRAY_BUFFER, dotVbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Compute a face centroid. Prefers the topology's ordered boundary vertices;
     * falls back to averaging the face's cached triangle vertices.
     */
    private boolean computeFaceCentroid(int faceIndex, Vector3f out) {
        if (genericModelRenderer != null) {
            MeshTopology topology = genericModelRenderer.getTopology();
            if (topology != null && faceIndex < topology.getFaceCount()) {
                var face = topology.getFace(faceIndex);
                if (face != null) {
                    int[] vertIndices = face.vertexIndices();
                    if (vertIndices.length > 0) {
                        out.set(0, 0, 0);
                        int used = 0;
                        for (int vi : vertIndices) {
                            Vector3f pos = genericModelRenderer.getUniqueVertexPosition(vi);
                            if (pos != null) {
                                out.add(pos);
                                used++;
                            }
                        }
                        if (used > 0) {
                            out.div(used);
                            return true;
                        }
                    }
                }
            }
        }

        // Fallback: average the face's triangle vertices (duplicates weight slightly)
        List<Integer> tris = originalFaceToTriangles.get(faceIndex);
        if (tris == null || tris.isEmpty() || trianglePositions == null) {
            return false;
        }
        out.set(0, 0, 0);
        int used = 0;
        for (int t : tris) {
            if (t < 0 || t >= triangleCount) continue;
            int off = t * 9;
            for (int v = 0; v < 3; v++) {
                out.add(trianglePositions[off + v * 3],
                        trianglePositions[off + v * 3 + 1],
                        trianglePositions[off + v * 3 + 2]);
                used++;
            }
        }
        if (used == 0) {
            return false;
        }
        out.div(used);
        return true;
    }

    private Vector3f resolveFaceDotColor(int faceIndex) {
        if (faceIndex == activeFaceIndex && selectedFaceIndices.contains(faceIndex)) {
            return MeshOverlayTheme.FACE_DOT_ACTIVE;
        }
        if (selectedFaceIndices.contains(faceIndex)) {
            return MeshOverlayTheme.FACE_DOT_SELECT;
        }
        return MeshOverlayTheme.FACE_DOT;
    }

    // =========================================================================
    // Rendering — follows EdgeRenderer pattern exactly
    // =========================================================================

    /**
     * Render Blender-style face highlights: translucent fills for selected/active/hovered
     * faces, crisp boundary outlines on top, and face-centroid dots in FACE edit mode.
     *
     * @param lineShader  BASIC shader for boundary outlines
     * @param fillShader  FACE shader (uColor + uAlpha) for translucent fills
     * @param pointShader VERTEX point shader for face dots
     */
    public void render(ShaderProgram lineShader, ShaderProgram fillShader, ShaderProgram pointShader,
                       RenderContext context, Matrix4f modelMatrix) {
        if (!initialized || !enabled || faceCount == 0) {
            return;
        }

        boolean faceMode = EditModeManager.getInstance().isFaceEditingAllowed();
        boolean hasHover = hoveredFaceIndex >= 0 && hoveredFaceIndex < faceCount;
        boolean hasSelection = !selectedFaceIndices.isEmpty();
        if (!faceMode && !hasHover && !hasSelection) {
            return;
        }

        try {
            Matrix4f viewMatrix = context.getCamera().getViewMatrix();
            Matrix4f projectionMatrix = context.getCamera().getProjectionMatrix();
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);

            if (fillDataDirty) {
                rebuildFillVBO();
                fillDataDirty = false;
            }
            if (dotDataDirty && faceMode) {
                rebuildDotVBO();
                dotDataDirty = false;
            }

            int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
            glDepthFunc(GL_LEQUAL);
            glDepthRange(0.0, OVERLAY_DEPTH_RANGE_FAR); // subtle bias to resolve coplanar z-fighting
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

            // ── Layer 1: translucent fills (depth writes off) ──
            boolean hasActive = activeFaceIndex >= 0 && activeFaceIndex < faceCount
                    && selectedFaceIndices.contains(activeFaceIndex);
            if ((hasSelection || hasHover) && totalFillVertices > 0 && fillResourcesInitialized) {
                fillShader.use();
                fillShader.setMat4("uMVPMatrix", mvpMatrix);
                glDepthMask(false);
                glBindVertexArray(fillVao);

                if (hasSelection) {
                    fillShader.setVec3("uColor", MeshOverlayTheme.FACE_SELECT_FILL);
                    fillShader.setFloat("uAlpha", MeshOverlayTheme.FACE_SELECT_ALPHA);
                    for (int faceIdx : selectedFaceIndices) {
                        if (faceIdx >= 0 && faceIdx < faceCount && !(hasActive && faceIdx == activeFaceIndex)) {
                            drawFaceFill(faceIdx);
                        }
                    }

                    // Active face gets Blender's white tint instead of the orange fill
                    if (hasActive) {
                        fillShader.setVec3("uColor", MeshOverlayTheme.FACE_ACTIVE_FILL);
                        fillShader.setFloat("uAlpha", MeshOverlayTheme.FACE_ACTIVE_ALPHA);
                        drawFaceFill(activeFaceIndex);
                    }
                }

                // Hover pre-highlight on unselected faces only
                if (hasHover && !selectedFaceIndices.contains(hoveredFaceIndex)) {
                    fillShader.setVec3("uColor", MeshOverlayTheme.FACE_HOVER_FILL);
                    fillShader.setFloat("uAlpha", MeshOverlayTheme.FACE_HOVER_ALPHA);
                    drawFaceFill(hoveredFaceIndex);
                }

                glBindVertexArray(0);
                glDepthMask(true);
            }

            // ── Layer 2: boundary outlines ──
            if ((hasSelection || hasHover) && totalLineVertices > 0 && lineResourcesInitialized) {
                lineShader.use();
                lineShader.setMat4("uMVPMatrix", mvpMatrix);
                lineShader.setFloat("uIntensity", 1.0f);
                glBindVertexArray(lineVao);

                if (hasSelection) {
                    glLineWidth(SELECTED_LINE_WIDTH);
                    for (int faceIdx : selectedFaceIndices) {
                        if (faceIdx >= 0 && faceIdx < faceCount && faceIdx != hoveredFaceIndex) {
                            Vector3f color = (hasActive && faceIdx == activeFaceIndex)
                                    ? ACTIVE_EDGE_COLOR : SELECTED_EDGE_COLOR;
                            updateFaceEdgeColors(faceIdx, color);
                            drawFaceEdges(faceIdx);
                            updateFaceEdgeColors(faceIdx, DEFAULT_EDGE_COLOR);
                        }
                    }
                }

                // Hovered face on top
                if (hasHover) {
                    glLineWidth(HOVER_LINE_WIDTH);
                    updateFaceEdgeColors(hoveredFaceIndex, HOVER_EDGE_COLOR);
                    drawFaceEdges(hoveredFaceIndex);
                    updateFaceEdgeColors(hoveredFaceIndex, DEFAULT_EDGE_COLOR);
                }

                glBindVertexArray(0);
            }

            // ── Layer 3: face dots (FACE edit mode only, like Blender facedots) ──
            if (faceMode && dotCount > 0 && dotResourcesInitialized && pointShader != null) {
                pointShader.use();
                pointShader.setMat4("uMVPMatrix", mvpMatrix);
                pointShader.setFloat("uIntensity", 1.0f);
                pointShader.setFloat("uPointSize", MeshOverlayTheme.FACE_DOT_SIZE);
                glEnable(GL_VERTEX_PROGRAM_POINT_SIZE);
                glBindVertexArray(dotVao);

                glDrawArrays(GL_POINTS, 0, dotCount);

                // Hovered face's dot drawn slightly larger for affordance
                if (hasHover) {
                    pointShader.setFloat("uPointSize", MeshOverlayTheme.FACE_DOT_HOVER_SIZE);
                    glDrawArrays(GL_POINTS, hoveredFaceIndex, 1);
                }

                glBindVertexArray(0);
                glDisable(GL_VERTEX_PROGRAM_POINT_SIZE);
            }

            glDepthRange(0.0, 1.0);
            glDepthFunc(prevDepthFunc);

        } catch (Exception e) {
            logger.error("Error rendering face highlights", e);
        } finally {
            glUseProgram(0);
        }
    }

    /**
     * Update VBO colors for all line vertices of a face.
     * Same approach as EdgeRenderer.updateEdgeColors.
     */
    private void updateFaceEdgeColors(int faceIndex, Vector3f color) {
        if (faceLineOffsets == null || faceIndex >= faceLineOffsets.length) {
            return;
        }

        int startVertex = faceLineOffsets[faceIndex];
        int vertexCount = faceLineCounts[faceIndex];

        glBindBuffer(GL_ARRAY_BUFFER, lineVbo);
        float[] colorData = { color.x, color.y, color.z };

        for (int i = 0; i < vertexCount; i++) {
            long offset = (long)(startVertex + i) * LINE_STRIDE_BYTES + LINE_COLOR_OFFSET_BYTES;
            glBufferSubData(GL_ARRAY_BUFFER, offset, colorData);
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    private void drawFaceEdges(int faceIndex) {
        if (faceLineOffsets == null || faceIndex >= faceLineOffsets.length) {
            return;
        }
        int startVertex = faceLineOffsets[faceIndex];
        int vertexCount = faceLineCounts[faceIndex];
        if (vertexCount > 0) {
            glDrawArrays(GL_LINES, startVertex, vertexCount);
        }
    }

    // =========================================================================
    // Hover detection
    // =========================================================================

    public void handleMouseMove(float mouseX, float mouseY,
                               Matrix4f viewMatrix, Matrix4f projectionMatrix,
                               Matrix4f modelMatrix,
                               int viewportWidth, int viewportHeight) {
        if (!initialized || !enabled || faceCount == 0) {
            return;
        }

        int newHoveredFace = -1;

        if (trianglePositions != null && triangleCount > 0) {
            int hitTriangle = FaceHoverDetector.detectHoveredTriangle(
                mouseX, mouseY,
                viewportWidth, viewportHeight,
                viewMatrix, projectionMatrix, modelMatrix,
                trianglePositions, triangleCount
            );

            if (hitTriangle >= 0 && genericModelRenderer != null) {
                newHoveredFace = genericModelRenderer.getOriginalFaceIdForTriangle(hitTriangle);
            }
        }

        if (newHoveredFace != hoveredFaceIndex) {
            hoveredFaceIndex = newHoveredFace;
            logger.debug("Face hover changed to {} (total faces: {})", hoveredFaceIndex, faceCount);
        }
    }

    public void clearHover() {
        if (hoveredFaceIndex != -1) {
            logger.debug("Clearing face hover (was face {})", hoveredFaceIndex);
            hoveredFaceIndex = -1;
        }
    }

    // =========================================================================
    // Face vertex access (used by face translation, texture editor, etc.)
    // =========================================================================

    /**
     * Get ordered boundary vertices for a face from topology.
     */
    public Vector3f[] getFaceVertices(int faceIndex) {
        if (faceIndex < 0 || faceIndex >= faceCount) {
            return null;
        }

        // Path 1: stored topology positions
        if (storedVerticesPerFace != null && storedFaceOffsets != null
                && topologyFacePositions != null && faceIndex < storedVerticesPerFace.length) {
            int vertCount = storedVerticesPerFace[faceIndex];
            int floatOffset = storedFaceOffsets[faceIndex];
            Vector3f[] vertices = new Vector3f[vertCount];
            for (int i = 0; i < vertCount; i++) {
                int base = floatOffset + i * COMPONENTS_PER_POSITION;
                vertices[i] = new Vector3f(
                    topologyFacePositions[base],
                    topologyFacePositions[base + 1],
                    topologyFacePositions[base + 2]
                );
            }
            return vertices;
        }

        // Path 2: query topology directly
        if (genericModelRenderer != null) {
            var topology = genericModelRenderer.getTopology();
            if (topology != null && faceIndex < topology.getFaceCount()) {
                var face = topology.getFace(faceIndex);
                if (face != null) {
                    int[] vertexIndices = face.vertexIndices();
                    Vector3f[] vertices = new Vector3f[vertexIndices.length];
                    for (int i = 0; i < vertexIndices.length; i++) {
                        Vector3f pos = genericModelRenderer.getUniqueVertexPosition(vertexIndices[i]);
                        if (pos == null) return null;
                        vertices[i] = new Vector3f(pos);
                    }
                    return vertices;
                }
            }
        }
        return null;
    }

    public int[] getTriangleVertexIndicesForFace(int originalFaceId) {
        if (genericModelRenderer == null) {
            return null;
        }

        List<Integer> triangleList = originalFaceToTriangles.get(originalFaceId);
        if (triangleList == null || triangleList.isEmpty()) {
            return null;
        }

        int[] triangleIndices = genericModelRenderer.getTriangleIndices();
        if (triangleIndices == null) {
            return null;
        }

        Set<Integer> uniqueVertexIndices = new LinkedHashSet<>();
        for (int triIndex : triangleList) {
            int baseIndex = triIndex * 3;
            if (baseIndex + 2 < triangleIndices.length) {
                uniqueVertexIndices.add(triangleIndices[baseIndex]);
                uniqueVertexIndices.add(triangleIndices[baseIndex + 1]);
                uniqueVertexIndices.add(triangleIndices[baseIndex + 2]);
            }
        }

        return uniqueVertexIndices.stream().mapToInt(Integer::intValue).toArray();
    }

    public Vector3f[] getTriangleVertexPositionsForFace(int originalFaceId) {
        int[] indices = getTriangleVertexIndicesForFace(originalFaceId);
        if (indices == null || genericModelRenderer == null) {
            return null;
        }

        Vector3f[] positions = new Vector3f[indices.length];
        for (int i = 0; i < indices.length; i++) {
            positions[i] = genericModelRenderer.getVertexPosition(indices[i]);
            if (positions[i] == null) return null;
        }
        return positions;
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    public void cleanup() {
        if (genericModelRenderer != null) {
            genericModelRenderer.removeMeshChangeListener(this);
            genericModelRenderer = null;
        }

        if (lineVao != 0) {
            glDeleteVertexArrays(lineVao);
            lineVao = 0;
        }
        if (lineVbo != 0) {
            glDeleteBuffers(lineVbo);
            lineVbo = 0;
        }
        if (fillVao != 0) {
            glDeleteVertexArrays(fillVao);
            fillVao = 0;
        }
        if (fillVbo != 0) {
            glDeleteBuffers(fillVbo);
            fillVbo = 0;
        }
        if (dotVao != 0) {
            glDeleteVertexArrays(dotVao);
            dotVao = 0;
        }
        if (dotVbo != 0) {
            glDeleteBuffers(dotVbo);
            dotVbo = 0;
        }

        lineResourcesInitialized = false;
        fillResourcesInitialized = false;
        dotResourcesInitialized = false;
        fillDataDirty = true;
        dotDataDirty = true;
        totalFillVertices = 0;
        dotCount = 0;
        activeFaceIndex = -1;
        faceCount = 0;
        totalLineVertices = 0;
        topologyFacePositions = null;
        storedVerticesPerFace = null;
        storedFaceOffsets = null;
        initialized = false;
        logger.debug("FaceRenderer cleaned up");
    }

    // =========================================================================
    // MeshChangeListener
    // =========================================================================

    @Override
    public void onVertexPositionChanged(int uniqueIndex, Vector3f newPosition, int[] affectedMeshIndices) {
        if (!initialized) return;

        // Update triangle positions for hover detection
        if (genericModelRenderer != null) {
            updateTrianglePositionsForMeshVertices(affectedMeshIndices, newPosition);
        }

        // Update line VBO positions for affected face boundary edges
        updateLinePositionsForVertex(uniqueIndex, newPosition);

        // Fill geometry and dot centroids derive from the moved vertex — rebuild lazily
        fillDataDirty = true;
        dotDataDirty = true;
    }

    @Override
    public void onGeometryRebuilt() {
        logger.debug("FaceRenderer received geometry rebuild notification");
        rebuildFromGenericModelRenderer();
    }

    /**
     * Update line VBO positions when a vertex moves.
     * Finds all faces containing this vertex and updates their boundary edge positions.
     */
    private void updateLinePositionsForVertex(int uniqueIndex, Vector3f newPosition) {
        if (!lineResourcesInitialized || genericModelRenderer == null || faceLineOffsets == null) {
            return;
        }

        MeshTopology topology = genericModelRenderer.getTopology();
        if (topology == null) return;

        // Find faces containing this vertex and update their line positions
        List<Integer> affectedFaces = topology.elementAdjacencyQuery().getFacesForVertex(uniqueIndex);
        if (affectedFaces == null || affectedFaces.isEmpty()) return;

        glBindBuffer(GL_ARRAY_BUFFER, lineVbo);
        float[] posData = { newPosition.x, newPosition.y, newPosition.z };

        for (int faceId : affectedFaces) {
            if (faceId < 0 || faceId >= faceCount) continue;

            var face = topology.getFace(faceId);
            if (face == null) continue;

            int[] vertIndices = face.vertexIndices();
            int baseVertex = faceLineOffsets[faceId];

            // Check each edge of this face for the affected vertex
            for (int i = 0; i < vertIndices.length; i++) {
                int next = (i + 1) % vertIndices.length;
                int vIdx = baseVertex + i * VERTICES_PER_LINE_SEGMENT;

                if (vertIndices[i] == uniqueIndex) {
                    // This is vertex A of the line segment
                    long offset = (long) vIdx * LINE_STRIDE_BYTES;
                    glBufferSubData(GL_ARRAY_BUFFER, offset, posData);
                }
                if (vertIndices[next] == uniqueIndex) {
                    // This is vertex B of the line segment
                    long offset = (long)(vIdx + 1) * LINE_STRIDE_BYTES;
                    glBufferSubData(GL_ARRAY_BUFFER, offset, posData);
                }
            }
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    private void updateTrianglePositionsForMeshVertices(int[] affectedMeshIndices, Vector3f newPosition) {
        if (trianglePositions == null || genericModelRenderer == null) {
            return;
        }

        int[] triangleIndices = genericModelRenderer.getTriangleIndices();
        if (triangleIndices == null) return;

        for (int meshIdx : affectedMeshIndices) {
            for (int t = 0; t < triangleCount; t++) {
                int i0 = triangleIndices[t * 3];
                int i1 = triangleIndices[t * 3 + 1];
                int i2 = triangleIndices[t * 3 + 2];

                int offset = t * 9;

                if (i0 == meshIdx) {
                    trianglePositions[offset]     = newPosition.x;
                    trianglePositions[offset + 1] = newPosition.y;
                    trianglePositions[offset + 2] = newPosition.z;
                }
                if (i1 == meshIdx) {
                    trianglePositions[offset + 3] = newPosition.x;
                    trianglePositions[offset + 4] = newPosition.y;
                    trianglePositions[offset + 5] = newPosition.z;
                }
                if (i2 == meshIdx) {
                    trianglePositions[offset + 6] = newPosition.x;
                    trianglePositions[offset + 7] = newPosition.y;
                    trianglePositions[offset + 8] = newPosition.z;
                }
            }
        }
    }

    // =========================================================================
    // Getters / setters
    // =========================================================================

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isInitialized() { return initialized; }
    public int getHoveredFaceIndex() { return hoveredFaceIndex; }
    public int getFaceCount() { return faceCount; }

    public int getSelectedFaceIndex() {
        return selectedFaceIndices.isEmpty() ? -1 : selectedFaceIndices.iterator().next();
    }

    public void setSelectedFace(int faceIndex) {
        selectedFaceIndices.clear();
        activeFaceIndex = -1;
        if (faceIndex >= 0 && faceIndex < faceCount) {
            selectedFaceIndices.add(faceIndex);
            activeFaceIndex = faceIndex;
        }
        dotDataDirty = true;
    }

    public void updateSelectionSet(Set<Integer> indices) {
        selectedFaceIndices.clear();
        activeFaceIndex = -1;
        if (indices != null) {
            // Insertion order from the selection state is preserved (LinkedHashSet),
            // so the last valid index is the active element, like Blender
            for (Integer index : indices) {
                if (index >= 0 && index < faceCount) {
                    selectedFaceIndices.add(index);
                    activeFaceIndex = index;
                }
            }
        }
        dotDataDirty = true;
    }

    public Set<Integer> getSelectedFaceIndices() {
        return new HashSet<>(selectedFaceIndices);
    }

    public boolean isFaceSelected(int index) {
        return selectedFaceIndices.contains(index);
    }

    public void clearSelection() {
        selectedFaceIndices.clear();
        activeFaceIndex = -1;
        dotDataDirty = true;
    }

    public void setEditingFaceIndex(int faceIndex) {
        this.editingFaceIndex = faceIndex;
    }

    public void setGenericModelRenderer(GenericModelRenderer renderer) {
        if (this.genericModelRenderer != null) {
            this.genericModelRenderer.removeMeshChangeListener(this);
        }

        this.genericModelRenderer = renderer;

        if (renderer != null) {
            renderer.addMeshChangeListener(this);
            rebuildFromGenericModelRenderer();
        }

        logger.debug("FaceRenderer {} GenericModelRenderer",
            renderer != null ? "connected to" : "disconnected from");
    }
}
