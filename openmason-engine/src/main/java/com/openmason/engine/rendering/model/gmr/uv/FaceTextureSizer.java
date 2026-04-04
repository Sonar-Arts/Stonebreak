package com.openmason.engine.rendering.model.gmr.uv;

import com.openmason.engine.rendering.model.gmr.extraction.GMRFaceExtractor;
import org.joml.Vector3f;

/**
 * Stateless utility that computes pixel dimensions for a face texture
 * based on the face's 3D geometry.
 *
 * <p>Projects face vertices into tangent space (reusing {@link FaceProjectionUtil})
 * to determine the face's world-space width and height, then converts to pixel
 * dimensions at a configurable pixels-per-unit ratio. Non-square faces produce
 * non-square textures, preserving geometric fidelity.
 *
 * @see FaceProjectionUtil
 */
public final class FaceTextureSizer {

    /** Default resolution: 16 pixels per world unit (matches Stonebreak block textures). */
    public static final int DEFAULT_PIXELS_PER_UNIT = 16;

    /** Minimum texture dimension to prevent degenerate textures. */
    public static final int MIN_TEXTURE_SIZE = 4;

    /** Maximum texture dimension to prevent excessive memory usage. */
    public static final int MAX_TEXTURE_SIZE = 256;

    private FaceTextureSizer() {
        // Stateless utility — no instantiation
    }

    /**
     * Computed texture dimensions for a face.
     *
     * @param width      Pixel width (clamped and rounded to even)
     * @param height     Pixel height (clamped and rounded to even)
     * @param worldWidth  Face extent in world units along the tangent axis
     * @param worldHeight Face extent in world units along the bitangent axis
     */
    public record FaceTextureDimensions(int width, int height, float worldWidth, float worldHeight) {}

    /**
     * Compute pixel dimensions for a face from its vertex positions.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Compute face normal from first 3 vertices</li>
     *   <li>Compute tangent frame via {@link FaceProjectionUtil#computeTangentFrame}</li>
     *   <li>Project all vertices onto tangent/bitangent plane</li>
     *   <li>Measure world-space extent in both axes</li>
     *   <li>Convert to pixels, clamp, and round up to even numbers</li>
     * </ol>
     *
     * @param positions    Vertex positions (x,y,z interleaved) for this face only
     * @param vertexCount  Number of vertices in the face
     * @param pixelsPerUnit Resolution in pixels per world unit
     * @return Computed dimensions, or {@code null} if the face is degenerate
     */
    public static FaceTextureDimensions computeForFace(float[] positions, int vertexCount, int pixelsPerUnit) {
        if (positions == null || vertexCount < 3 || positions.length < vertexCount * 3) {
            return null;
        }

        // Compute face normal robustly — after subdivision, the first three
        // vertices may be collinear (e.g., [A, midpoint(A,B), B, ...])
        Vector3f normal = FaceProjectionUtil.computeRobustFaceNormal(positions, vertexCount);

        Vector3f[] frame = FaceProjectionUtil.computeTangentFrame(normal);
        if (frame == null) {
            return null; // Degenerate face
        }

        Vector3f tangent = frame[0];
        Vector3f bitangent = frame[1];

        // Use first vertex as reference point
        float refX = positions[0];
        float refY = positions[1];
        float refZ = positions[2];

        // Project all vertices onto tangent frame and compute bounds
        float minS = Float.MAX_VALUE, maxS = -Float.MAX_VALUE;
        float minT = Float.MAX_VALUE, maxT = -Float.MAX_VALUE;

        for (int i = 0; i < vertexCount; i++) {
            float dx = positions[i * 3]     - refX;
            float dy = positions[i * 3 + 1] - refY;
            float dz = positions[i * 3 + 2] - refZ;

            float s = dx * tangent.x + dy * tangent.y + dz * tangent.z;
            float t = dx * bitangent.x + dy * bitangent.y + dz * bitangent.z;

            minS = Math.min(minS, s);
            maxS = Math.max(maxS, s);
            minT = Math.min(minT, t);
            maxT = Math.max(maxT, t);
        }

        float worldWidth = maxS - minS;
        float worldHeight = maxT - minT;

        // Convert to pixels
        int pixelW = toEvenClamped(Math.round(worldWidth * pixelsPerUnit));
        int pixelH = toEvenClamped(Math.round(worldHeight * pixelsPerUnit));

        return new FaceTextureDimensions(pixelW, pixelH, worldWidth, worldHeight);
    }

    /**
     * Convenience overload that extracts vertex positions for a specific face
     * from a {@link GMRFaceExtractor.FaceExtractionResult}.
     *
     * @param result       Face extraction result containing all face data
     * @param faceId       Index of the face to compute dimensions for
     * @param pixelsPerUnit Resolution in pixels per world unit
     * @return Computed dimensions, or {@code null} if the face ID is invalid or degenerate
     */
    public static FaceTextureDimensions computeForFace(GMRFaceExtractor.FaceExtractionResult result,
                                                        int faceId, int pixelsPerUnit) {
        if (result == null || faceId < 0 || faceId >= result.faceCount()) {
            return null;
        }

        int startFloat = result.faceOffsets()[faceId];
        int endFloat = result.faceOffsets()[faceId + 1];
        int vertexCount = result.verticesPerFace()[faceId];

        if (vertexCount < 3 || endFloat - startFloat < vertexCount * 3) {
            return null;
        }

        // Copy face vertex positions into a local array
        int floatCount = vertexCount * 3;
        float[] facePositions = new float[floatCount];
        System.arraycopy(result.positions(), startFloat, facePositions, 0, floatCount);

        return computeForFace(facePositions, vertexCount, pixelsPerUnit);
    }

    /**
     * Clamp value to [{@link #MIN_TEXTURE_SIZE}, {@link #MAX_TEXTURE_SIZE}]
     * and round up to the nearest even number.
     */
    private static int toEvenClamped(int value) {
        int clamped = Math.clamp(value, MIN_TEXTURE_SIZE, MAX_TEXTURE_SIZE);
        // Round up to even
        return (clamped + 1) & ~1;
    }
}
