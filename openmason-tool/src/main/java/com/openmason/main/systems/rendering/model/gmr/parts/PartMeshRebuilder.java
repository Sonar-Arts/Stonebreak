package com.openmason.main.systems.rendering.model.gmr.parts;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Rebuilds the combined mesh buffer from individual model parts.
 *
 * <p>Single Responsibility: combines part geometry into a flat buffer layout,
 * applying per-part transforms. Does not own any state — it is a pure function
 * wrapped in a class for testability and DI.
 *
 * <p>Each part's geometry (vertices, texCoords, indices) is concatenated into
 * a single combined buffer. Indices are offset by the cumulative vertex count
 * of preceding parts. A {@link MeshRange} is computed for each part to enable
 * part-aware queries on the combined buffer.
 */
public class PartMeshRebuilder {

    private static final Logger logger = LoggerFactory.getLogger(PartMeshRebuilder.class);

    /**
     * Combine all parts into a single mesh, applying per-part transforms.
     *
     * @param parts       Ordered list of part descriptors (defines iteration order)
     * @param partData    Map of partId → raw geometry data (vertices, texCoords, indices)
     * @return RebuildResult with combined buffers and per-part mesh ranges
     */
    public RebuildResult rebuild(List<ModelPartDescriptor> parts, Map<String, PartGeometry> partData) {
        if (parts.isEmpty()) {
            logger.debug("No parts to rebuild — returning empty result");
            return RebuildResult.empty();
        }

        // First pass: compute total sizes
        int totalVertices = 0;
        int totalIndices = 0;
        int totalFaces = 0;

        for (ModelPartDescriptor part : parts) {
            PartGeometry geo = partData.get(part.id());
            if (geo == null) {
                continue;
            }
            totalVertices += geo.vertexCount();
            totalIndices += geo.indexCount();
            totalFaces += geo.faceCount();
        }

        float[] combinedVertices = new float[totalVertices * 3];
        float[] combinedTexCoords = new float[totalVertices * 2];
        int[] combinedIndices = new int[totalIndices];
        int[] triangleToFaceId = new int[totalIndices / 3];

        Map<String, MeshRange> partRanges = new LinkedHashMap<>();

        int vertexOffset = 0;
        int indexOffset = 0;
        int faceOffset = 0;

        // Second pass: copy and transform
        for (ModelPartDescriptor part : parts) {
            PartGeometry geo = partData.get(part.id());
            if (geo == null) {
                continue;
            }

            int partVertexStart = vertexOffset;
            int partIndexStart = indexOffset;
            int partFaceStart = faceOffset;

            // Copy and transform vertices
            Matrix4f transform = part.transform().toMatrix();
            boolean hasTransform = !part.transform().isIdentity();

            for (int i = 0; i < geo.vertexCount(); i++) {
                int srcPos = i * 3;
                int dstPos = (vertexOffset + i) * 3;

                if (hasTransform) {
                    Vector4f v = new Vector4f(
                            geo.vertices()[srcPos],
                            geo.vertices()[srcPos + 1],
                            geo.vertices()[srcPos + 2],
                            1.0f
                    );
                    transform.transform(v);
                    combinedVertices[dstPos] = v.x;
                    combinedVertices[dstPos + 1] = v.y;
                    combinedVertices[dstPos + 2] = v.z;
                } else {
                    combinedVertices[dstPos] = geo.vertices()[srcPos];
                    combinedVertices[dstPos + 1] = geo.vertices()[srcPos + 1];
                    combinedVertices[dstPos + 2] = geo.vertices()[srcPos + 2];
                }
            }

            // Copy texture coordinates (unmodified by transform)
            if (geo.texCoords() != null) {
                System.arraycopy(geo.texCoords(), 0, combinedTexCoords, vertexOffset * 2,
                        geo.vertexCount() * 2);
            }

            // Copy and offset indices
            if (geo.indices() != null) {
                for (int i = 0; i < geo.indices().length; i++) {
                    combinedIndices[indexOffset + i] = geo.indices()[i] + vertexOffset;
                }
            }

            // Copy and offset triangle-to-face mapping
            if (geo.triangleToFaceId() != null) {
                int triangleStart = indexOffset / 3;
                for (int i = 0; i < geo.triangleToFaceId().length; i++) {
                    triangleToFaceId[triangleStart + i] = geo.triangleToFaceId()[i] + faceOffset;
                }
            }

            // Compute mesh range for this part
            MeshRange range = new MeshRange(
                    partVertexStart, geo.vertexCount(),
                    partIndexStart, geo.indexCount(),
                    partFaceStart, geo.faceCount()
            );
            partRanges.put(part.id(), range);

            vertexOffset += geo.vertexCount();
            indexOffset += geo.indexCount();
            faceOffset += geo.faceCount();
        }

        logger.debug("Rebuilt {} parts: {} vertices, {} indices, {} faces",
                parts.size(), totalVertices, totalIndices, totalFaces);

        return new RebuildResult(
                combinedVertices, combinedTexCoords,
                combinedIndices, triangleToFaceId,
                partRanges, Map.of()
        );
    }

    /**
     * Rebuild with explicit per-part face offsets for stable face IDs.
     * Face IDs for each part are assigned from the provided offset map rather than
     * auto-incrementing, ensuring face IDs remain stable across hide/show toggles.
     *
     * @param parts            Ordered list of visible part descriptors
     * @param partData         Map of partId → raw geometry data
     * @param stableFaceOffsets Map of partId → stable face offset for that part
     * @return RebuildResult with combined buffers using stable face IDs
     */
    public RebuildResult rebuildWithFaceOffsets(List<ModelPartDescriptor> parts,
                                                Map<String, PartGeometry> partData,
                                                Map<String, Integer> stableFaceOffsets) {
        if (parts.isEmpty()) {
            return RebuildResult.empty();
        }

        int totalVertices = 0;
        int totalIndices = 0;

        for (ModelPartDescriptor part : parts) {
            PartGeometry geo = partData.get(part.id());
            if (geo == null) continue;
            totalVertices += geo.vertexCount();
            totalIndices += geo.indexCount();
        }

        float[] combinedVertices = new float[totalVertices * 3];
        float[] combinedTexCoords = new float[totalVertices * 2];
        int[] combinedIndices = new int[totalIndices];
        int[] triangleToFaceId = new int[totalIndices / 3];

        Map<String, MeshRange> partRanges = new LinkedHashMap<>();

        int vertexOffset = 0;
        int indexOffset = 0;

        for (ModelPartDescriptor part : parts) {
            PartGeometry geo = partData.get(part.id());
            if (geo == null) continue;

            int partVertexStart = vertexOffset;
            int partIndexStart = indexOffset;
            // Use stable face offset instead of auto-incrementing
            int partFaceStart = stableFaceOffsets.getOrDefault(part.id(), 0);

            Matrix4f transform = part.transform().toMatrix();
            boolean hasTransform = !part.transform().isIdentity();

            for (int i = 0; i < geo.vertexCount(); i++) {
                int srcPos = i * 3;
                int dstPos = (vertexOffset + i) * 3;

                if (hasTransform) {
                    Vector4f v = new Vector4f(
                            geo.vertices()[srcPos], geo.vertices()[srcPos + 1],
                            geo.vertices()[srcPos + 2], 1.0f);
                    transform.transform(v);
                    combinedVertices[dstPos] = v.x;
                    combinedVertices[dstPos + 1] = v.y;
                    combinedVertices[dstPos + 2] = v.z;
                } else {
                    System.arraycopy(geo.vertices(), srcPos, combinedVertices, dstPos, 3);
                }
            }

            if (geo.texCoords() != null) {
                System.arraycopy(geo.texCoords(), 0, combinedTexCoords, vertexOffset * 2,
                        geo.vertexCount() * 2);
            }

            if (geo.indices() != null) {
                for (int i = 0; i < geo.indices().length; i++) {
                    combinedIndices[indexOffset + i] = geo.indices()[i] + vertexOffset;
                }
            }

            if (geo.triangleToFaceId() != null) {
                int triangleStart = indexOffset / 3;
                for (int i = 0; i < geo.triangleToFaceId().length; i++) {
                    triangleToFaceId[triangleStart + i] = geo.triangleToFaceId()[i] + partFaceStart;
                }
            }

            MeshRange range = new MeshRange(
                    partVertexStart, geo.vertexCount(),
                    partIndexStart, geo.indexCount(),
                    partFaceStart, geo.faceCount()
            );
            partRanges.put(part.id(), range);

            vertexOffset += geo.vertexCount();
            indexOffset += geo.indexCount();
        }

        logger.debug("Rebuilt {} visible parts with stable face offsets: {} vertices, {} indices",
                parts.size(), totalVertices, totalIndices);

        return new RebuildResult(
                combinedVertices, combinedTexCoords,
                combinedIndices, triangleToFaceId,
                partRanges, Map.of()
        );
    }

    // ========== Data Records ==========

    /**
     * Raw geometry data for a single part.
     *
     * @param vertices         Vertex positions (x, y, z interleaved)
     * @param texCoords        Texture coordinates (u, v interleaved)
     * @param indices          Index array
     * @param triangleToFaceId Triangle-to-face mapping
     * @param vertexCount      Number of vertices
     * @param indexCount       Number of indices
     * @param faceCount        Number of logical faces
     */
    public record PartGeometry(
            float[] vertices,
            float[] texCoords,
            int[] indices,
            int[] triangleToFaceId,
            int vertexCount,
            int indexCount,
            int faceCount
    ) {
        /**
         * Create PartGeometry from raw arrays with auto-computed counts.
         */
        public static PartGeometry of(float[] vertices, float[] texCoords, int[] indices,
                                       int[] triangleToFaceId) {
            int vertexCount = vertices != null ? vertices.length / 3 : 0;
            int indexCount = indices != null ? indices.length : 0;

            // Compute face count from triangle-to-face mapping
            int faceCount = 0;
            if (triangleToFaceId != null) {
                for (int faceId : triangleToFaceId) {
                    faceCount = Math.max(faceCount, faceId + 1);
                }
            }

            return new PartGeometry(vertices, texCoords, indices, triangleToFaceId,
                    vertexCount, indexCount, faceCount);
        }
    }

    /**
     * Result of a combined mesh rebuild.
     *
     * @param combinedVertices   All vertices concatenated
     * @param combinedTexCoords  All texture coords concatenated
     * @param combinedIndices    All indices concatenated (offset per part)
     * @param triangleToFaceId   Triangle-to-face mapping (offset per part)
     * @param partRanges         Map of partId → MeshRange in the combined buffer
     * @param faceIdRemap        Map of old face ID → new face ID (empty if no shift occurred)
     */
    public record RebuildResult(
            float[] combinedVertices,
            float[] combinedTexCoords,
            int[] combinedIndices,
            int[] triangleToFaceId,
            Map<String, MeshRange> partRanges,
            Map<Integer, Integer> faceIdRemap
    ) {
        /**
         * Create an empty result (no parts).
         */
        public static RebuildResult empty() {
            return new RebuildResult(
                    new float[0], new float[0],
                    new int[0], new int[0],
                    Map.of(), Map.of()
            );
        }

        /**
         * Get total vertex count across all parts.
         */
        public int totalVertexCount() {
            return combinedVertices != null ? combinedVertices.length / 3 : 0;
        }

        /**
         * Get total index count across all parts.
         */
        public int totalIndexCount() {
            return combinedIndices != null ? combinedIndices.length : 0;
        }
    }
}
