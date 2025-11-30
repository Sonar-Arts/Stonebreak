package com.openmason.main.systems.viewport.viewportRendering;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for detecting face hover interactions.
 *
 * REDESIGNED APPROACH:
 * 1. Face positions are ALREADY transformed by FaceExtractor (includes global + part transforms)
 * 2. We only need to apply view-projection (NOT model matrix again - that causes double transformation!)
 * 3. Use robust triangle-based point-in-quad test (split quad into 2 triangles)
 * 4. Use minimum depth for better accuracy on rotated faces
 *
 * Mirrors EdgeHoverDetector and VertexHoverDetector pattern.
 */
public final class FaceHoverDetector {

    private static final Logger logger = LoggerFactory.getLogger(FaceHoverDetector.class);
    private static final float EPSILON = 0.0001f;

    // Private constructor to prevent instantiation (utility class)
    private FaceHoverDetector() {
        throw new AssertionError("FaceHoverDetector is a utility class and should not be instantiated");
    }

    /**
     * Detects which face (if any) is currently hovered by the mouse.
     * Uses robust screen-space triangle-based point-in-quad detection with depth sorting.
     *
     * @param mouseX Mouse X coordinate in viewport space
     * @param mouseY Mouse Y coordinate in viewport space
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @param viewMatrix Camera view matrix
     * @param projectionMatrix Camera projection matrix
     * @param modelMatrix Model transformation matrix (for gizmo transforms - applied to ALREADY transformed faces)
     * @param facePositions Face positions ALREADY TRANSFORMED by FaceExtractor (12 floats per face: 4 vertices × 3 coords)
     * @param faceCount Number of faces
     * @return Index of hovered face, or -1 if none
     */
    public static int detectHoveredFace(float mouseX, float mouseY,
                                       int viewportWidth, int viewportHeight,
                                       Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                       Matrix4f modelMatrix,
                                       float[] facePositions, int faceCount) {
        // Validate inputs
        if (viewMatrix == null || projectionMatrix == null || modelMatrix == null) {
            logger.trace("detectHoveredFace: null matrix");
            return -1;
        }

        if (facePositions == null || faceCount == 0) {
            logger.trace("detectHoveredFace: no face data (positions={}, count={})",
                facePositions != null ? "exists" : "null", faceCount);
            return -1;
        }

        if (viewportWidth <= 0 || viewportHeight <= 0) {
            logger.trace("detectHoveredFace: invalid viewport size {}x{}", viewportWidth, viewportHeight);
            return -1;
        }

        try {
            // CRITICAL FIX: Face positions are ALREADY transformed by FaceExtractor!
            // We apply modelMatrix here to handle gizmo transforms (translation/rotation/scale)
            // Then apply view-projection to get to screen space
            Matrix4f viewModelMatrix = new Matrix4f(viewMatrix).mul(modelMatrix);
            Matrix4f vpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix);
            Matrix4f mvpMatrix = new Matrix4f(vpMatrix).mul(modelMatrix);

            // Track closest face (by minimum depth, not average - more accurate)
            int closestFaceIndex = -1;
            float closestDepth = Float.POSITIVE_INFINITY;

            // Reusable vectors for transformation
            Vector4f worldPos = new Vector4f();
            Vector4f viewPos = new Vector4f();
            Vector4f clipPos = new Vector4f();
            Vector2f[] screenVerts = new Vector2f[4];
            for (int i = 0; i < 4; i++) {
                screenVerts[i] = new Vector2f();
            }
            float[] depths = new float[4];

            int validFaceCount = 0;
            int behindCameraCount = 0;
            int culledByViewCount = 0;

            // Test each face
            for (int faceIdx = 0; faceIdx < faceCount; faceIdx++) {
                int posIndex = faceIdx * 12; // Each face has 4 vertices × 3 coords = 12 floats

                boolean allVerticesValid = true;
                boolean allVerticesInFront = true;

                // Project all 4 vertices to screen space
                for (int v = 0; v < 4; v++) {
                    int vertexIndex = posIndex + (v * 3);

                    // Bounds check
                    if (vertexIndex + 2 >= facePositions.length) {
                        logger.warn("Face {} vertex {} out of bounds (index {} >= length {})",
                            faceIdx, v, vertexIndex + 2, facePositions.length);
                        allVerticesValid = false;
                        break;
                    }

                    // Get vertex position (ALREADY TRANSFORMED by FaceExtractor!)
                    worldPos.set(
                        facePositions[vertexIndex + 0],
                        facePositions[vertexIndex + 1],
                        facePositions[vertexIndex + 2],
                        1.0f
                    );

                    // Transform to view space first to check if behind camera
                    viewModelMatrix.transform(worldPos, viewPos);

                    // Check if vertex is behind camera (positive Z in view space)
                    // In OpenGL view space: camera looks down -Z axis, so objects in front have negative Z
                    if (viewPos.z > -EPSILON) {
                        allVerticesInFront = false;
                        behindCameraCount++;
                        break;
                    }

                    // Apply full MVP to get clip space
                    mvpMatrix.transform(worldPos, clipPos);

                    // For orthographic projection, w is always 1.0
                    // For perspective projection, we'd also check clipPos.w > 0
                    if (clipPos.w <= EPSILON) {
                        allVerticesInFront = false;
                        behindCameraCount++;
                        break;
                    }

                    // Convert to NDC (Normalized Device Coordinates)
                    float ndcX = clipPos.x / clipPos.w;
                    float ndcY = clipPos.y / clipPos.w;
                    float ndcZ = clipPos.z / clipPos.w;

                    // Store depth for sorting (use NDC Z for accuracy)
                    depths[v] = ndcZ;

                    // Convert NDC to screen space
                    screenVerts[v].x = (ndcX + 1.0f) * 0.5f * viewportWidth;
                    screenVerts[v].y = (1.0f - ndcY) * 0.5f * viewportHeight; // Flip Y (NDC Y is inverted)
                }

                // Skip if any vertex is invalid or behind camera
                if (!allVerticesValid || !allVerticesInFront) {
                    continue;
                }

                // Cull faces with ALL vertices outside viewport (optimization)
                if (isFaceOutsideViewport(screenVerts, viewportWidth, viewportHeight)) {
                    culledByViewCount++;
                    continue;
                }

                validFaceCount++;

                // Check if mouse is inside this quad using robust triangle-based test
                // Split quad into 2 triangles: (v0, v1, v2) and (v0, v2, v3)
                boolean insideQuad = isPointInTriangle(mouseX, mouseY,
                                                      screenVerts[0], screenVerts[1], screenVerts[2]) ||
                                    isPointInTriangle(mouseX, mouseY,
                                                      screenVerts[0], screenVerts[2], screenVerts[3]);

                if (insideQuad) {
                    // Use MINIMUM depth for better accuracy (closest point of face)
                    float minDepth = Math.min(Math.min(depths[0], depths[1]),
                                             Math.min(depths[2], depths[3]));

                    logger.trace("Face {} contains mouse! MinDepth: {:.3f}, screenPos: ({:.1f},{:.1f}) ({:.1f},{:.1f}) ({:.1f},{:.1f}) ({:.1f},{:.1f})",
                        faceIdx, minDepth,
                        screenVerts[0].x, screenVerts[0].y, screenVerts[1].x, screenVerts[1].y,
                        screenVerts[2].x, screenVerts[2].y, screenVerts[3].x, screenVerts[3].y);

                    // Select closest face when multiple faces contain mouse
                    if (minDepth < closestDepth) {
                        closestDepth = minDepth;
                        closestFaceIndex = faceIdx;
                    }
                }
            }

            logger.trace("detectHoveredFace: checked {} faces, {} valid, {} behind camera, {} culled, result: {}",
                faceCount, validFaceCount, behindCameraCount, culledByViewCount, closestFaceIndex);

            return closestFaceIndex;

        } catch (Exception e) {
            logger.error("Error detecting hovered face", e);
            return -1;
        }
    }

    /**
     * Check if point is inside a triangle using barycentric coordinates.
     * This is MORE ROBUST than cross-product edge test for quads.
     *
     * @param px, py Point coordinates (screen space)
     * @param v0, v1, v2 Triangle vertices (screen space)
     * @return true if point is inside triangle
     */
    private static boolean isPointInTriangle(float px, float py,
                                            Vector2f v0, Vector2f v1, Vector2f v2) {
        // Compute vectors
        float v0x = v2.x - v0.x;
        float v0y = v2.y - v0.y;
        float v1x = v1.x - v0.x;
        float v1y = v1.y - v0.y;
        float v2x = px - v0.x;
        float v2y = py - v0.y;

        // Compute dot products
        float dot00 = v0x * v0x + v0y * v0y;
        float dot01 = v0x * v1x + v0y * v1y;
        float dot02 = v0x * v2x + v0y * v2y;
        float dot11 = v1x * v1x + v1y * v1y;
        float dot12 = v1x * v2x + v1y * v2y;

        // Compute barycentric coordinates
        float invDenom = 1.0f / (dot00 * dot11 - dot01 * dot01);
        float u = (dot11 * dot02 - dot01 * dot12) * invDenom;
        float v = (dot00 * dot12 - dot01 * dot02) * invDenom;

        // Check if point is in triangle
        return (u >= 0) && (v >= 0) && (u + v <= 1.0f);
    }

    /**
     * Check if a face quad is completely outside the viewport (optimization).
     * Only cull if ALL 4 vertices are outside on the SAME side.
     *
     * @param verts Array of 4 screen-space vertices
     * @param viewportWidth Viewport width
     * @param viewportHeight Viewport height
     * @return true if face is completely outside viewport
     */
    private static boolean isFaceOutsideViewport(Vector2f[] verts, int viewportWidth, int viewportHeight) {
        // Check if all vertices are to the left
        boolean allLeft = true;
        // Check if all vertices are to the right
        boolean allRight = true;
        // Check if all vertices are above
        boolean allAbove = true;
        // Check if all vertices are below
        boolean allBelow = true;

        for (Vector2f v : verts) {
            if (v.x >= 0) allLeft = false;
            if (v.x <= viewportWidth) allRight = false;
            if (v.y >= 0) allAbove = false;
            if (v.y <= viewportHeight) allBelow = false;
        }

        return allLeft || allRight || allAbove || allBelow;
    }
}
