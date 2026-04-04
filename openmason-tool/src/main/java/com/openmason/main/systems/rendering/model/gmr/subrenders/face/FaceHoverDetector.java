package com.openmason.main.systems.rendering.model.gmr.subrenders.face;

import com.openmason.main.systems.viewport.coordinates.CoordinateSystem;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for detecting which face the mouse is hovering over.
 * Uses ray-triangle intersection (Moller-Trumbore algorithm) for accurate hit detection.
 * Follows the same pattern as VertexHoverDetector and EdgeHoverDetector.
 *
 * Static utility class (private constructor prevents instantiation).
 */
public final class FaceHoverDetector {

    private static final Logger logger = LoggerFactory.getLogger(FaceHoverDetector.class);

    // Private constructor prevents instantiation
    private FaceHoverDetector() {
        throw new AssertionError("FaceHoverDetector is a utility class and should not be instantiated");
    }

    /**
     * Detect which face (if any) the mouse is hovering over.
     * Uses ray-triangle intersection to test each face (split into 2 triangles).
     *
     * @param mouseX Mouse X coordinate in viewport space
     * @param mouseY Mouse Y coordinate in viewport space
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @param viewMatrix Camera view matrix
     * @param projectionMatrix Camera projection matrix
     * @param modelMatrix Model transformation matrix
     * @param facePositions Array of face vertex positions [v0x,v0y,v0z, v1x,v1y,v1z, v2x,v2y,v2z, v3x,v3y,v3z, ...]
     * @param faceCount Number of faces
     * @return Index of hovered face, or -1 if no face is hovered
     */
    public static int detectHoveredFace(float mouseX, float mouseY,
                                       int viewportWidth, int viewportHeight,
                                       Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                       Matrix4f modelMatrix,
                                       float[] facePositions,
                                       int faceCount) {
        if (facePositions == null || faceCount <= 0) {
            return -1;
        }

        if (viewMatrix == null || projectionMatrix == null || modelMatrix == null) {
            logger.warn("Cannot detect face hover: null matrices");
            return -1;
        }

        try {
            // Create ray from mouse position
            CoordinateSystem.Ray ray = CoordinateSystem.createWorldRayFromScreen(
                mouseX, mouseY,
                viewportWidth, viewportHeight,
                viewMatrix, projectionMatrix
            );

            if (ray == null) {
                logger.warn("Failed to create ray from mouse position");
                return -1;
            }

            // Test each face for intersection
            int closestFaceIndex = -1;
            float closestDistance = Float.POSITIVE_INFINITY;

            for (int faceIdx = 0; faceIdx < faceCount; faceIdx++) {
                int posStart = faceIdx * 12; // 12 floats per face (4 vertices × 3 coords)

                // Get 4 corners of quad face in MODEL SPACE
                Vector3f v0_model = new Vector3f(
                    facePositions[posStart + 0],
                    facePositions[posStart + 1],
                    facePositions[posStart + 2]
                );
                Vector3f v1_model = new Vector3f(
                    facePositions[posStart + 3],
                    facePositions[posStart + 4],
                    facePositions[posStart + 5]
                );
                Vector3f v2_model = new Vector3f(
                    facePositions[posStart + 6],
                    facePositions[posStart + 7],
                    facePositions[posStart + 8]
                );
                Vector3f v3_model = new Vector3f(
                    facePositions[posStart + 9],
                    facePositions[posStart + 10],
                    facePositions[posStart + 11]
                );

                // Transform vertices from MODEL SPACE to WORLD SPACE
                Vector3f v0 = new Vector3f();
                Vector3f v1 = new Vector3f();
                Vector3f v2 = new Vector3f();
                Vector3f v3 = new Vector3f();
                modelMatrix.transformPosition(v0_model, v0);
                modelMatrix.transformPosition(v1_model, v1);
                modelMatrix.transformPosition(v2_model, v2);
                modelMatrix.transformPosition(v3_model, v3);

                // Split quad into 2 triangles and test both
                // Triangle 1: v0, v1, v2
                // Triangle 2: v0, v2, v3

                float[] outT = new float[1];

                // Test triangle 1
                if (intersectRayTriangle(ray.origin(), ray.direction(), v0, v1, v2, outT)) {
                    float distance = outT[0];
                    if (distance >= 0 && distance < closestDistance) {
                        closestDistance = distance;
                        closestFaceIndex = faceIdx;
                    }
                }

                // Test triangle 2
                if (intersectRayTriangle(ray.origin(), ray.direction(), v0, v2, v3, outT)) {
                    float distance = outT[0];
                    if (distance >= 0 && distance < closestDistance) {
                        closestDistance = distance;
                        closestFaceIndex = faceIdx;
                    }
                }
            }

            if (closestFaceIndex >= 0) {
                logger.trace("Face {} hovered at distance {}", closestFaceIndex, closestDistance);
            }

            return closestFaceIndex;

        } catch (Exception e) {
            logger.error("Error detecting face hover", e);
            return -1;
        }
    }

    /**
     * Detect which triangle (if any) the mouse is hovering over.
     * Uses ray-triangle intersection for hit detection.
     * This variant is for triangle mode (post-subdivision) where faces are individual triangles.
     *
     * @param mouseX Mouse X coordinate in viewport space
     * @param mouseY Mouse Y coordinate in viewport space
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @param viewMatrix Camera view matrix
     * @param projectionMatrix Camera projection matrix
     * @param modelMatrix Model transformation matrix
     * @param trianglePositions Array of triangle vertex positions [v0x,v0y,v0z, v1x,v1y,v1z, v2x,v2y,v2z, ...]
     * @param triangleCount Number of triangles
     * @return Index of hovered triangle, or -1 if no triangle is hovered
     */
    public static int detectHoveredTriangle(float mouseX, float mouseY,
                                            int viewportWidth, int viewportHeight,
                                            Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                            Matrix4f modelMatrix,
                                            float[] trianglePositions,
                                            int triangleCount) {
        if (trianglePositions == null || triangleCount <= 0) {
            return -1;
        }

        if (viewMatrix == null || projectionMatrix == null || modelMatrix == null) {
            logger.warn("Cannot detect triangle hover: null matrices");
            return -1;
        }

        try {
            // Create ray from mouse position
            CoordinateSystem.Ray ray = CoordinateSystem.createWorldRayFromScreen(
                mouseX, mouseY,
                viewportWidth, viewportHeight,
                viewMatrix, projectionMatrix
            );

            if (ray == null) {
                logger.warn("Failed to create ray from mouse position");
                return -1;
            }

            // Test each triangle for intersection
            int closestTriangleIndex = -1;
            float closestDistance = Float.POSITIVE_INFINITY;

            for (int triIdx = 0; triIdx < triangleCount; triIdx++) {
                int posStart = triIdx * 9; // 9 floats per triangle (3 vertices × 3 coords)

                // Bounds check
                if (posStart + 8 >= trianglePositions.length) {
                    break;
                }

                // Get 3 vertices of triangle in MODEL SPACE
                Vector3f v0_model = new Vector3f(
                    trianglePositions[posStart + 0],
                    trianglePositions[posStart + 1],
                    trianglePositions[posStart + 2]
                );
                Vector3f v1_model = new Vector3f(
                    trianglePositions[posStart + 3],
                    trianglePositions[posStart + 4],
                    trianglePositions[posStart + 5]
                );
                Vector3f v2_model = new Vector3f(
                    trianglePositions[posStart + 6],
                    trianglePositions[posStart + 7],
                    trianglePositions[posStart + 8]
                );

                // Transform vertices from MODEL SPACE to WORLD SPACE
                Vector3f v0 = new Vector3f();
                Vector3f v1 = new Vector3f();
                Vector3f v2 = new Vector3f();
                modelMatrix.transformPosition(v0_model, v0);
                modelMatrix.transformPosition(v1_model, v1);
                modelMatrix.transformPosition(v2_model, v2);

                float[] outT = new float[1];

                // Test triangle
                if (intersectRayTriangle(ray.origin(), ray.direction(), v0, v1, v2, outT)) {
                    float distance = outT[0];
                    if (distance >= 0 && distance < closestDistance) {
                        closestDistance = distance;
                        closestTriangleIndex = triIdx;
                    }
                }
            }

            if (closestTriangleIndex >= 0) {
                logger.trace("Triangle {} hovered at distance {}", closestTriangleIndex, closestDistance);
            }

            return closestTriangleIndex;

        } catch (Exception e) {
            logger.error("Error detecting triangle hover", e);
            return -1;
        }
    }

    /**
     * Ray-triangle intersection using Moller-Trumbore algorithm.
     * Fast, robust algorithm that doesn't require pre-computing the plane equation.
     *
     * @param rayOrigin Origin of the ray
     * @param rayDirection Direction of the ray (must be normalized)
     * @param v0 First vertex of triangle
     * @param v1 Second vertex of triangle
     * @param v2 Third vertex of triangle
     * @param outT Output parameter for distance along ray (t value)
     * @return true if ray intersects triangle, false otherwise
     */
    private static boolean intersectRayTriangle(Vector3f rayOrigin, Vector3f rayDirection,
                                               Vector3f v0, Vector3f v1, Vector3f v2,
                                               float[] outT) {
        final float EPSILON = 0.0000001f;

        // Compute edge vectors
        Vector3f edge1 = new Vector3f(v1).sub(v0);
        Vector3f edge2 = new Vector3f(v2).sub(v0);

        // Begin calculating determinant - also used to calculate u parameter
        Vector3f h = new Vector3f(rayDirection).cross(edge2);
        float a = edge1.dot(h);

        // If determinant is near zero, ray is parallel to triangle
        if (Math.abs(a) < EPSILON) {
            return false;
        }

        float f = 1.0f / a;
        Vector3f s = new Vector3f(rayOrigin).sub(v0);
        float u = f * s.dot(h);

        // Check if intersection is outside triangle (barycentric coordinate u)
        if (u < 0.0f || u > 1.0f) {
            return false;
        }

        Vector3f q = new Vector3f(s).cross(edge1);
        float v = f * rayDirection.dot(q);

        // Check if intersection is outside triangle (barycentric coordinate v)
        if (v < 0.0f || u + v > 1.0f) {
            return false;
        }

        // Calculate distance along ray (t value)
        float t = f * edge2.dot(q);

        // Ray intersection occurs if t > EPSILON
        if (t > EPSILON) {
            outT[0] = t;
            return true;
        }

        // Line intersection but not ray intersection
        return false;
    }
}
