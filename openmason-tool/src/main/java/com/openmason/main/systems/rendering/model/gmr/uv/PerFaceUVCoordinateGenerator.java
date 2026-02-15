package com.openmason.main.systems.rendering.model.gmr.uv;

import com.openmason.main.systems.rendering.model.UVMode;
import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Per-face UV coordinate generator that computes UVs from {@link IFaceTextureManager} mappings.
 *
 * <p>For each face in the mesh, looks up its {@link FaceTextureMapping} and interpolates
 * vertex positions within the face's UV region based on the vertex's position relative
 * to the face bounds. Supports UV rotation and falls back to full {@code (0,0)→(1,1)}
 * mapping for faces without explicit mappings.
 *
 * <p>Replaces the deprecated {@link UVCoordinateGenerator} which assumed cube topology.
 * This implementation supports arbitrary geometry with any face count and layout.
 *
 * <p><b>Algorithm:</b>
 * <ol>
 *   <li>Group vertex indices by face ID using the triangle-face mapper</li>
 *   <li>For each face, look up its {@link FaceTextureMapping} from {@link IFaceTextureManager}</li>
 *   <li>Compute face bounds by projecting vertices onto the face's dominant plane</li>
 *   <li>For each vertex, compute parametric position (0..1) within the face bounds</li>
 *   <li>Map parametric position into the face's {@code uvRegion}</li>
 *   <li>Apply {@code uvRotation} if non-zero</li>
 * </ol>
 *
 * @see FaceTextureMapping
 * @see IFaceTextureManager
 */
public class PerFaceUVCoordinateGenerator implements IUVCoordinateGenerator {

    private static final Logger logger = LoggerFactory.getLogger(PerFaceUVCoordinateGenerator.class);

    /** Position-matching tolerance for degenerate face detection. */
    private static final float EPSILON = 0.0001f;

    private final IFaceTextureManager textureManager;

    /**
     * @param textureManager Source of per-face UV mappings (must not be null)
     */
    public PerFaceUVCoordinateGenerator(IFaceTextureManager textureManager) {
        this.textureManager = Objects.requireNonNull(textureManager, "textureManager must not be null");
    }

    // ── Primary API ─────────────────────────────────────────────────────────

    /**
     * Generate per-face UV coordinates using {@link IFaceTextureManager} mappings.
     *
     * <p>This is the primary entry point, replacing the deprecated mode-based methods.
     * Requires vertex positions, triangle indices, and a face mapper to resolve
     * which vertices belong to which face.
     *
     * @param vertices   Vertex positions (x,y,z interleaved)
     * @param indices    Triangle indices
     * @param faceMapper Triangle-to-face mapping
     * @return UV coordinates array (u,v interleaved), length = (vertices.length / 3) * 2
     */
    public float[] generatePerFaceUVs(float[] vertices, int[] indices, ITriangleFaceMapper faceMapper) {
        if (vertices == null || vertices.length < 9) {
            logger.debug("Insufficient vertex data for per-face UV generation ({} floats)",
                vertices != null ? vertices.length : 0);
            return new float[0];
        }

        int vertexCount = vertices.length / 3;
        float[] texCoords = new float[vertexCount * 2];

        if (indices == null || indices.length < 3 || faceMapper == null || !faceMapper.hasMapping()) {
            logger.debug("No topology data — assigning default full-region UVs to all vertices");
            assignFullRegionFallback(texCoords, vertexCount);
            return texCoords;
        }

        Map<Integer, List<Integer>> faceVertexIndices = groupVerticesByFace(indices, faceMapper);
        boolean[] assigned = new boolean[vertexCount];

        for (Map.Entry<Integer, List<Integer>> entry : faceVertexIndices.entrySet()) {
            int faceId = entry.getKey();
            List<Integer> vertexIndicesList = entry.getValue();

            FaceTextureMapping mapping = textureManager.getFaceMapping(faceId);
            FaceTextureMapping.UVRegion uvRegion = (mapping != null)
                ? mapping.uvRegion()
                : FaceTextureMapping.FULL_REGION;
            FaceTextureMapping.UVRotation rotation = (mapping != null)
                ? mapping.uvRotation()
                : FaceTextureMapping.UVRotation.NONE;

            assignFaceUVs(vertices, texCoords, vertexIndicesList, uvRegion, rotation, assigned);
        }

        // Orphan vertices (not belonging to any face) get origin UVs
        for (int i = 0; i < vertexCount; i++) {
            if (!assigned[i]) {
                texCoords[i * 2] = 0.0f;
                texCoords[i * 2 + 1] = 0.0f;
            }
        }

        logger.debug("Generated per-face UVs: {} vertices, {} faces",
            vertexCount, faceVertexIndices.size());
        return texCoords;
    }

    // ── Deprecated interface methods (fallback behavior) ────────────────────

    /**
     * @deprecated Use {@link #generatePerFaceUVs(float[], int[], ITriangleFaceMapper)} instead.
     *             This method cannot access face topology and returns flat UVs as a fallback.
     */
    @Override
    @Deprecated
    public float[] generateUVs(UVMode mode, int vertexCount) {
        logger.debug("Legacy generateUVs called — returning flat fallback for {} vertices", vertexCount);
        return generateFlatUVs(vertexCount);
    }

    /**
     * @deprecated Cube-specific method. Per-face system does not use cube net layout.
     *             Returns flat UVs as a fallback.
     */
    @Override
    @Deprecated
    public float[] generateCubeNetUVs(int vertexCount) {
        logger.debug("Legacy generateCubeNetUVs called — returning flat fallback for {} vertices", vertexCount);
        return generateFlatUVs(vertexCount);
    }

    /**
     * @deprecated Use {@link #generatePerFaceUVs(float[], int[], ITriangleFaceMapper)} instead.
     *             This method provides a basic fallback mapping each group of 4 vertices
     *             to the full (0,0)→(1,1) range.
     */
    @Override
    @Deprecated
    public float[] generateFlatUVs(int vertexCount) {
        float[] texCoords = new float[vertexCount * 2];
        assignFullRegionFallback(texCoords, vertexCount);
        return texCoords;
    }

    /**
     * @deprecated Cube-specific method. Per-face system has no concept of cube net bounds.
     *             Always returns {@code null}.
     */
    @Override
    @Deprecated
    public float[] getCubeNetFaceBounds(int faceIndex) {
        return null;
    }

    // ── Face UV computation ─────────────────────────────────────────────────

    /**
     * Compute and assign UV coordinates for all vertices of a single face.
     *
     * <p>Projects vertex positions onto the face's dominant 2D plane, normalizes
     * to parametric (0..1) space, applies rotation, and maps into the UV region.
     */
    private void assignFaceUVs(float[] vertices, float[] texCoords, List<Integer> vertexIndices,
                                FaceTextureMapping.UVRegion uvRegion,
                                FaceTextureMapping.UVRotation rotation,
                                boolean[] assigned) {
        if (vertexIndices.size() < 3) {
            return;
        }

        // Compute face normal from the first three vertices
        int i0 = vertexIndices.get(0);
        int i1 = vertexIndices.get(1);
        int i2 = vertexIndices.get(2);

        Vector3f normal = computeFaceNormal(vertices, i0, i1, i2);

        // Determine projection axes from dominant normal component
        int uAxis = selectUAxis(normal);
        int vAxis = selectVAxis(normal);

        // Compute face bounds along projection axes
        float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE;
        float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;

        for (int idx : vertexIndices) {
            float u = vertices[idx * 3 + uAxis];
            float v = vertices[idx * 3 + vAxis];
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }

        float rangeU = maxU - minU;
        float rangeV = maxV - minV;

        // Map each vertex to UV space
        for (int idx : vertexIndices) {
            float projU = vertices[idx * 3 + uAxis];
            float projV = vertices[idx * 3 + vAxis];

            // Normalize to parametric 0..1 (degenerate edges collapse to 0.5)
            float s = (rangeU > EPSILON) ? (projU - minU) / rangeU : 0.5f;
            float t = (rangeV > EPSILON) ? (projV - minV) / rangeV : 0.5f;

            // Apply rotation in parametric space (inlined to avoid per-vertex allocation)
            if (rotation != FaceTextureMapping.UVRotation.NONE) {
                float rs, rt;
                switch (rotation) {
                    case CW_90  -> { rs = t;          rt = 1.0f - s; }
                    case CW_180 -> { rs = 1.0f - s;   rt = 1.0f - t; }
                    case CW_270 -> { rs = 1.0f - t;   rt = s;        }
                    default     -> { rs = s;           rt = t;        }
                }
                s = rs;
                t = rt;
            }

            // Map parametric position into UV region
            texCoords[idx * 2] = uvRegion.u0() + s * uvRegion.width();
            texCoords[idx * 2 + 1] = uvRegion.v0() + t * uvRegion.height();
            assigned[idx] = true;
        }
    }

    // ── Geometry helpers ────────────────────────────────────────────────────

    /**
     * Compute the face normal from three vertex positions using the cross product.
     * Logs a debug message if the resulting normal is degenerate (collinear or coincident vertices).
     */
    private Vector3f computeFaceNormal(float[] vertices, int i0, int i1, int i2) {
        float e1x = vertices[i1 * 3]     - vertices[i0 * 3];
        float e1y = vertices[i1 * 3 + 1] - vertices[i0 * 3 + 1];
        float e1z = vertices[i1 * 3 + 2] - vertices[i0 * 3 + 2];

        float e2x = vertices[i2 * 3]     - vertices[i0 * 3];
        float e2y = vertices[i2 * 3 + 1] - vertices[i0 * 3 + 1];
        float e2z = vertices[i2 * 3 + 2] - vertices[i0 * 3 + 2];

        Vector3f normal = new Vector3f(
            e1y * e2z - e1z * e2y,
            e1z * e2x - e1x * e2z,
            e1x * e2y - e1y * e2x
        );

        if (normal.lengthSquared() < EPSILON * EPSILON) {
            logger.debug("Degenerate face normal (collinear or coincident vertices: {}, {}, {})", i0, i1, i2);
        }

        return normal;
    }

    /**
     * Select the U projection axis index (0=X, 1=Y, 2=Z) based on the face normal.
     * Picks one of the two axes most perpendicular to the dominant normal component.
     */
    private int selectUAxis(Vector3f normal) {
        float absX = Math.abs(normal.x);
        float absY = Math.abs(normal.y);
        float absZ = Math.abs(normal.z);

        if (absX >= absY && absX >= absZ) {
            return 1; // Normal along X → U = Y
        } else {
            return 0; // Normal along Y or Z → U = X
        }
    }

    /**
     * Select the V projection axis index (0=X, 1=Y, 2=Z) based on the face normal.
     * Picks one of the two axes most perpendicular to the dominant normal component.
     */
    private int selectVAxis(Vector3f normal) {
        float absX = Math.abs(normal.x);
        float absY = Math.abs(normal.y);
        float absZ = Math.abs(normal.z);

        if (absZ >= absX && absZ >= absY) {
            return 1; // Normal along Z → V = Y
        } else {
            return 2; // Normal along X or Y → V = Z
        }
    }

    // ── Vertex grouping ─────────────────────────────────────────────────────

    /**
     * Group vertex indices by their face ID using the triangle-face mapper.
     *
     * <p>Each triangle's three vertex indices are associated with the face ID
     * returned by the mapper. Duplicate indices within a face are deduplicated
     * while preserving insertion order.
     */
    private Map<Integer, List<Integer>> groupVerticesByFace(int[] indices, ITriangleFaceMapper faceMapper) {
        Map<Integer, Set<Integer>> faceVertexSets = new LinkedHashMap<>();
        int triangleCount = indices.length / 3;

        for (int t = 0; t < triangleCount; t++) {
            int faceId = faceMapper.getOriginalFaceIdForTriangle(t);
            if (faceId < 0) {
                continue;
            }

            Set<Integer> vertexSet = faceVertexSets.computeIfAbsent(faceId, k -> new LinkedHashSet<>());
            vertexSet.add(indices[t * 3]);
            vertexSet.add(indices[t * 3 + 1]);
            vertexSet.add(indices[t * 3 + 2]);
        }

        Map<Integer, List<Integer>> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<Integer>> entry : faceVertexSets.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return result;
    }

    // ── Fallback ────────────────────────────────────────────────────────────

    /** UV coordinates for a standard quad: BL, BR, TR, TL (u,v interleaved). */
    private static final float[] QUAD_UV_PATTERN = {
        0.0f, 1.0f,  // BL
        1.0f, 1.0f,  // BR
        1.0f, 0.0f,  // TR
        0.0f, 0.0f   // TL
    };

    /**
     * Assign full-region UV mapping assuming groups of 4 vertices per face
     * (BL, BR, TR, TL ordering). Uses the standard quad UV pattern cyclically.
     */
    private void assignFullRegionFallback(float[] texCoords, int vertexCount) {
        for (int i = 0; i < vertexCount; i++) {
            int vertInFace = (i % 4) * 2;
            texCoords[i * 2]     = QUAD_UV_PATTERN[vertInFace];
            texCoords[i * 2 + 1] = QUAD_UV_PATTERN[vertInFace + 1];
        }
    }
}
