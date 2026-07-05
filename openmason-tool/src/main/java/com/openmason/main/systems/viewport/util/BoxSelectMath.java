package com.openmason.main.systems.viewport.util;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Pure static math for box (rectangle) selection.
 *
 * <p>Selection rules per element type:
 * <ul>
 *   <li>Vertices: screen projection inside the rect</li>
 *   <li>Edges: BOTH endpoints inside the rect (v1 simplification vs Blender's segment crossing)</li>
 *   <li>Faces: centroid inside the rect</li>
 * </ul>
 *
 * <p>Elements behind the camera (clip.w <= 0) are never selected.
 * Rects are viewport-relative pixel rectangles as {@code {minX, minY, maxX, maxY}}.
 */
public final class BoxSelectMath {

    private BoxSelectMath() {
        throw new AssertionError("BoxSelectMath is a utility class and should not be instantiated");
    }

    /**
     * Normalize two drag corners into a {minX, minY, maxX, maxY} rect.
     * Handles all four drag directions (down-right, down-left, up-right, up-left).
     *
     * @param x0 Drag start X
     * @param y0 Drag start Y
     * @param x1 Drag end X
     * @param y1 Drag end Y
     * @return Rect as {minX, minY, maxX, maxY}
     */
    public static float[] normalizeRect(float x0, float y0, float x1, float y1) {
        return new float[] {
            Math.min(x0, x1),
            Math.min(y0, y1),
            Math.max(x0, x1),
            Math.max(y0, y1)
        };
    }

    /**
     * @param rect Rect as {minX, minY, maxX, maxY}
     * @return true if (x, y) lies inside the rect (inclusive bounds)
     */
    public static boolean contains(float[] rect, float x, float y) {
        return x >= rect[0] && x <= rect[2] && y >= rect[1] && y <= rect[3];
    }

    /**
     * Find all vertices whose screen projection falls inside the rect.
     *
     * @param positions      Vertex positions in model space (3 floats per vertex)
     * @param vertexCount    Number of vertices
     * @param mvp            Combined projection * view * model matrix
     * @param viewportWidth  Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @param rect           Rect as {minX, minY, maxX, maxY}
     * @return Indices of vertices inside the rect (empty array if none)
     */
    public static int[] verticesInRect(float[] positions, int vertexCount, Matrix4f mvp,
                                       int viewportWidth, int viewportHeight, float[] rect) {
        if (positions == null || vertexCount <= 0 || mvp == null || rect == null) {
            return new int[0];
        }

        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < vertexCount; i++) {
            int base = i * 3;
            if (base + 2 >= positions.length) {
                break;
            }
            Vector3f screen = ScreenProjectionUtil.projectToScreenWithDepth(
                positions[base], positions[base + 1], positions[base + 2],
                mvp, viewportWidth, viewportHeight);
            if (screen != null && contains(rect, screen.x, screen.y)) {
                hits.add(i);
            }
        }
        return toIntArray(hits);
    }

    /**
     * Find all edges with BOTH endpoints inside the rect.
     *
     * @param edgePositions  Edge positions in model space (6 floats per edge: x1,y1,z1, x2,y2,z2)
     * @param edgeCount      Number of edges
     * @param mvp            Combined projection * view * model matrix
     * @param viewportWidth  Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @param rect           Rect as {minX, minY, maxX, maxY}
     * @return Indices of edges fully inside the rect (empty array if none)
     */
    public static int[] edgesInRect(float[] edgePositions, int edgeCount, Matrix4f mvp,
                                    int viewportWidth, int viewportHeight, float[] rect) {
        if (edgePositions == null || edgeCount <= 0 || mvp == null || rect == null) {
            return new int[0];
        }

        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < edgeCount; i++) {
            int base = i * 6;
            if (base + 5 >= edgePositions.length) {
                break;
            }
            Vector3f screen1 = ScreenProjectionUtil.projectToScreenWithDepth(
                edgePositions[base], edgePositions[base + 1], edgePositions[base + 2],
                mvp, viewportWidth, viewportHeight);
            Vector3f screen2 = ScreenProjectionUtil.projectToScreenWithDepth(
                edgePositions[base + 3], edgePositions[base + 4], edgePositions[base + 5],
                mvp, viewportWidth, viewportHeight);
            if (screen1 != null && screen2 != null
                    && contains(rect, screen1.x, screen1.y)
                    && contains(rect, screen2.x, screen2.y)) {
                hits.add(i);
            }
        }
        return toIntArray(hits);
    }

    /**
     * Find all faces whose model-space centroid projects inside the rect.
     *
     * @param faceIds             Candidate face IDs (may be sparse)
     * @param faceVertexPositions Accessor returning a face's vertex positions in model space
     *                            (null or empty result skips the face)
     * @param mvp                 Combined projection * view * model matrix
     * @param viewportWidth       Viewport width in pixels
     * @param viewportHeight      Viewport height in pixels
     * @param rect                Rect as {minX, minY, maxX, maxY}
     * @return Face IDs whose centroid is inside the rect (empty array if none)
     */
    public static int[] facesInRect(int[] faceIds, IntFunction<Vector3f[]> faceVertexPositions,
                                    Matrix4f mvp, int viewportWidth, int viewportHeight, float[] rect) {
        if (faceIds == null || faceVertexPositions == null || mvp == null || rect == null) {
            return new int[0];
        }

        List<Integer> hits = new ArrayList<>();
        for (int faceId : faceIds) {
            Vector3f[] vertices = faceVertexPositions.apply(faceId);
            if (vertices == null || vertices.length == 0) {
                continue;
            }

            // Model-space centroid of the face's vertices
            Vector3f centroid = new Vector3f();
            for (Vector3f v : vertices) {
                centroid.add(v);
            }
            centroid.div(vertices.length);

            Vector3f screen = ScreenProjectionUtil.projectToScreenWithDepth(
                centroid.x, centroid.y, centroid.z, mvp, viewportWidth, viewportHeight);
            if (screen != null && contains(rect, screen.x, screen.y)) {
                hits.add(faceId);
            }
        }
        return toIntArray(hits);
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        }
        return result;
    }
}
