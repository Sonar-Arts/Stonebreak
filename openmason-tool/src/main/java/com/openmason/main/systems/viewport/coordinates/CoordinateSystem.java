package com.openmason.main.systems.viewport.coordinates;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Coordinate system conversions for viewport rendering.
 * Handles Screen, NDC, Clip, View, and World space transformations.
 */
public final class CoordinateSystem {

    private CoordinateSystem() {
        throw new AssertionError("CoordinateSystem is a utility class and should not be instantiated");
    }

    public static Vector2f screenToNDC(float screenX, float screenY, int viewportWidth, int viewportHeight) {
        validateViewportDimensions(viewportWidth, viewportHeight);
        float ndcX = (2.0f * screenX) / viewportWidth - 1.0f;
        float ndcY = 1.0f - (2.0f * screenY) / viewportHeight;
        return new Vector2f(ndcX, ndcY);
    }

    public static Vector2f worldDirectionToScreenDirection(Vector3f worldDir, Matrix4f viewMatrix,
                                                           Matrix4f projectionMatrix) {
        validateMatrices(viewMatrix, projectionMatrix);
        Vector3f viewDir = new Vector3f(worldDir).normalize();
        viewMatrix.transformDirection(viewDir);
        Vector4f clipDir = new Vector4f(viewDir, 0.0f);
        projectionMatrix.transform(clipDir);
        Vector2f screenDir = new Vector2f(clipDir.x, clipDir.y);
        if (screenDir.lengthSquared() > 0.0001f) {
            screenDir.normalize();
        }
        return screenDir;
    }

    public static Ray createWorldRayFromScreen(float screenX, float screenY,
                                               int viewportWidth, int viewportHeight,
                                               Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        validateMatrices(viewMatrix, projectionMatrix);
        validateViewportDimensions(viewportWidth, viewportHeight);
        Vector2f ndc = screenToNDC(screenX, screenY, viewportWidth, viewportHeight);
        Vector4f clipNear = new Vector4f(ndc.x, ndc.y, -1.0f, 1.0f);
        Vector4f clipFar = new Vector4f(ndc.x, ndc.y, 1.0f, 1.0f);
        Vector3f worldNear = clipToWorld(clipNear, viewMatrix, projectionMatrix);
        Vector3f worldFar = clipToWorld(clipFar, viewMatrix, projectionMatrix);
        Vector3f direction = new Vector3f(worldFar).sub(worldNear).normalize();
        return new Ray(worldNear, direction);
    }

    public static Vector3f clipToWorld(Vector4f clipPos, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        validateMatrices(viewMatrix, projectionMatrix);
        Matrix4f invProjection = new Matrix4f(projectionMatrix).invert();
        Vector4f viewPos = new Vector4f(clipPos);
        invProjection.transform(viewPos);
        if (Math.abs(viewPos.w) > 0.0001f) {
            viewPos.div(viewPos.w);
        }
        Matrix4f invView = new Matrix4f(viewMatrix).invert();
        invView.transform(viewPos);
        return new Vector3f(viewPos.x, viewPos.y, viewPos.z);
    }

    public static float projectScreenDeltaOntoWorldAxis(float screenDeltaX, float screenDeltaY,
                                                       Vector3f worldAxis,
                                                       Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                                       int viewportWidth, int viewportHeight,
                                                       float sensitivity) {
        validateMatrices(viewMatrix, projectionMatrix);
        validateViewportDimensions(viewportWidth, viewportHeight);
        float screenDeltaLength = (float) Math.sqrt(screenDeltaX * screenDeltaX + screenDeltaY * screenDeltaY);
        if (screenDeltaLength < 0.001f) {
            return 0.0f;
        }
        Vector2f screenDeltaNorm = new Vector2f(screenDeltaX / screenDeltaLength, screenDeltaY / screenDeltaLength);
        Vector2f axisScreenDir = worldDirectionToScreenDirection(worldAxis, viewMatrix, projectionMatrix);
        if (axisScreenDir.lengthSquared() < 0.001f) {
            return 0.0f;
        }
        axisScreenDir.normalize();
        float projection = screenDeltaNorm.x * axisScreenDir.x + (-screenDeltaNorm.y) * axisScreenDir.y;
        return projection * screenDeltaLength * sensitivity;
    }

    public static float projectScreenDeltaOntoWorldAxis(float screenDeltaX, float screenDeltaY,
                                                       Vector3f worldAxis,
                                                       Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                                       int viewportWidth, int viewportHeight) {
        return projectScreenDeltaOntoWorldAxis(screenDeltaX, screenDeltaY, worldAxis,
                                              viewMatrix, projectionMatrix,
                                              viewportWidth, viewportHeight, 0.01f);
    }

    public record Ray(Vector3f origin, Vector3f direction) {
        public Ray {
            if (origin == null || direction == null) {
                throw new IllegalArgumentException("Ray parameters cannot be null");
            }
            origin = new Vector3f(origin);
            direction = new Vector3f(direction).normalize();
        }

        public Vector3f getPoint(float distance) {
            return new Vector3f(origin).add(
                direction.x * distance,
                direction.y * distance,
                direction.z * distance
            );
        }
    }

    private static void validateViewportDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                String.format("Viewport dimensions must be positive: width=%d, height=%d", width, height)
            );
        }
    }

    private static void validateMatrices(Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        if (viewMatrix == null || projectionMatrix == null) {
            throw new IllegalArgumentException("View and projection matrices cannot be null");
        }
    }
}
