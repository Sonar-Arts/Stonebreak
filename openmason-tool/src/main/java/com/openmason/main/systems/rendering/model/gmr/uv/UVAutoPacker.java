package com.openmason.main.systems.rendering.model.gmr.uv;

import com.openmason.main.systems.rendering.model.gmr.topology.MeshFace;
import com.openmason.main.systems.rendering.model.gmr.topology.MeshTopology;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Automatic UV layout for models with many faces using shelf packing.
 *
 * <p>Arranges all faces of each material into non-overlapping UV regions
 * within the normalized (0,0)→(1,1) texture space. Face aspect ratios are
 * preserved based on their world-space projected dimensions.
 *
 * <p><b>Algorithm (per material):</b>
 * <ol>
 *   <li>Collect all faces and compute world-space dimensions (width × height)
 *       by projecting onto each face's dominant plane</li>
 *   <li>Sort by height descending (shelf packing heuristic)</li>
 *   <li>Place faces left-to-right in shelves, opening a new shelf when the
 *       current one overflows the estimated canvas width</li>
 *   <li>Normalize packed positions to (0,0)→(1,1) UV space, preserving
 *       face aspect ratios</li>
 *   <li>Update {@link IFaceTextureManager} with computed UV regions</li>
 * </ol>
 *
 * <p>Voxel faces are axis-aligned rectangles — no distortion, no seam
 * optimization needed. Shelf packing handles this layout efficiently.
 *
 * @see FaceTextureMapping
 * @see IFaceTextureManager
 */
public final class UVAutoPacker {

    private static final Logger logger = LoggerFactory.getLogger(UVAutoPacker.class);

    /** Minimum face dimension to prevent degenerate zero-area regions. */
    private static final float EPSILON = 0.0001f;

    /** Canvas width oversize factor to reduce shelf overflow into a tall strip. */
    private static final float CANVAS_WIDTH_MARGIN = 1.1f;

    // ── Result record ───────────────────────────────────────────────────────

    /**
     * Immutable result of a UV auto-pack operation.
     *
     * @param packedFaceCount Total number of faces successfully packed
     * @param shelfCount      Total number of shelves used across all materials
     * @param utilization     Average packing efficiency (0..1), ratio of face area to bounding box area
     */
    public record PackResult(int packedFaceCount, int shelfCount, float utilization) {}

    // ── Internal data ───────────────────────────────────────────────────────

    /** One face's projected 2D dimensions in world space. */
    private record FaceMeasurement(int faceId, float width, float height) {}

    /** One face's position and size after shelf packing (normalized to 0..1). */
    private record PackedPosition(int faceId, float x, float y, float width, float height) {}

    /** Result of shelf packing a single material group. */
    private record ShelfResult(List<PackedPosition> positions, int shelfCount, float utilization) {}

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Pack all face UV regions using shelf packing, grouped by material.
     *
     * <p>For each material registered in the texture manager, collects the
     * assigned faces, computes their world-space projected dimensions, and
     * packs them into the (0,0)→(1,1) UV space using a shelf algorithm.
     * The resulting UV regions are written back to the texture manager,
     * replacing any existing mappings.
     *
     * @param topology       Mesh topology providing face geometry and vertex mapping
     * @param vertices       Vertex positions (x,y,z interleaved, indexed by mesh vertex index)
     * @param textureManager Face-material manager to read groupings from and write UV regions to
     * @return Pack result with statistics
     * @throws NullPointerException if any argument is null
     */
    public PackResult pack(MeshTopology topology, float[] vertices, IFaceTextureManager textureManager) {
        Objects.requireNonNull(topology, "topology must not be null");
        Objects.requireNonNull(vertices, "vertices must not be null");
        Objects.requireNonNull(textureManager, "textureManager must not be null");

        Map<Integer, List<Integer>> facesByMaterial = textureManager.getFaceIdsByMaterial();
        if (facesByMaterial.isEmpty()) {
            logger.debug("No face mappings — nothing to pack");
            return new PackResult(0, 0, 0.0f);
        }

        int totalPacked = 0;
        int totalShelves = 0;
        float utilizationSum = 0.0f;
        int packedGroupCount = 0;

        for (Map.Entry<Integer, List<Integer>> entry : facesByMaterial.entrySet()) {
            int materialId = entry.getKey();
            List<Integer> faceIds = entry.getValue();

            List<FaceMeasurement> measurements = measureFaces(faceIds, topology, vertices);
            if (measurements.isEmpty()) {
                continue;
            }

            measurements.sort(Comparator.comparingDouble(FaceMeasurement::height).reversed());

            ShelfResult result = shelfPack(measurements);
            applyPackedRegions(result.positions(), materialId, textureManager);

            totalPacked += result.positions().size();
            totalShelves += result.shelfCount();
            utilizationSum += result.utilization();
            packedGroupCount++;

            logger.debug("Packed material {}: {} faces, {} shelves, utilization {}",
                materialId, result.positions().size(), result.shelfCount(),
                String.format("%.1f%%", result.utilization() * 100.0f));
        }

        float avgUtilization = packedGroupCount > 0 ? utilizationSum / packedGroupCount : 0.0f;

        logger.info("UV auto-pack complete: {} faces across {} materials, avg utilization {}",
            totalPacked, packedGroupCount,
            String.format("%.1f%%", avgUtilization * 100.0f));

        return new PackResult(totalPacked, totalShelves, avgUtilization);
    }

    // ── Face measurement ────────────────────────────────────────────────────

    /**
     * Compute world-space projected dimensions for each face.
     *
     * <p>Projects each face's vertices onto the 2D plane perpendicular to its
     * dominant normal axis, then computes the bounding rectangle dimensions.
     */
    private List<FaceMeasurement> measureFaces(List<Integer> faceIds,
                                                MeshTopology topology, float[] vertices) {
        List<FaceMeasurement> measurements = new ArrayList<>(faceIds.size());

        for (int faceId : faceIds) {
            MeshFace face = topology.getFace(faceId);
            if (face == null || face.vertexCount() < 3) {
                logger.debug("Skipping face {} — null or degenerate", faceId);
                continue;
            }

            Vector3f normal = topology.getFaceNormal(faceId);
            if (normal == null) {
                logger.debug("Skipping face {} — no normal available", faceId);
                continue;
            }

            float[] bounds = computeProjectedBounds(face, normal, topology, vertices);
            if (bounds == null) {
                continue;
            }

            float width = Math.max(bounds[0], EPSILON);
            float height = Math.max(bounds[1], EPSILON);
            measurements.add(new FaceMeasurement(faceId, width, height));
        }

        return measurements;
    }

    /**
     * Project a face's vertices onto its dominant 2D plane and compute bounding dimensions.
     *
     * @return float[2] containing {width, height}, or {@code null} if fewer than 3 valid vertices
     */
    private float[] computeProjectedBounds(MeshFace face, Vector3f normal,
                                            MeshTopology topology, float[] vertices) {
        int uAxis = selectUAxis(normal);
        int vAxis = selectVAxis(normal);

        float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE;
        float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
        int validCount = 0;

        for (int uniqueIdx : face.vertexIndices()) {
            int[] meshIndices = topology.indexMappingQuery().getMeshIndicesForUniqueVertex(uniqueIdx);
            if (meshIndices.length == 0) {
                continue;
            }

            int meshIdx = meshIndices[0];
            float u = vertices[meshIdx * 3 + uAxis];
            float v = vertices[meshIdx * 3 + vAxis];

            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
            validCount++;
        }

        if (validCount < 3) {
            return null;
        }

        return new float[]{maxU - minU, maxV - minV};
    }

    // ── Axis selection ──────────────────────────────────────────────────────

    /**
     * Select the U projection axis based on the face normal's dominant component.
     *
     * @return World axis index (0=X, 1=Y, 2=Z) for the U direction
     */
    private static int selectUAxis(Vector3f normal) {
        float absX = Math.abs(normal.x);
        float absY = Math.abs(normal.y);
        float absZ = Math.abs(normal.z);
        return (absX >= absY && absX >= absZ) ? 1 : 0;
    }

    /**
     * Select the V projection axis based on the face normal's dominant component.
     *
     * @return World axis index (0=X, 1=Y, 2=Z) for the V direction
     */
    private static int selectVAxis(Vector3f normal) {
        float absX = Math.abs(normal.x);
        float absY = Math.abs(normal.y);
        float absZ = Math.abs(normal.z);
        return (absZ >= absX && absZ >= absY) ? 1 : 2;
    }

    // ── Shelf packing ───────────────────────────────────────────────────────

    /**
     * Pack measured faces into shelves and normalize to (0,0)→(1,1) UV space.
     *
     * <p>Uses a next-fit decreasing-height (NFDH) shelf algorithm:
     * <ol>
     *   <li>Estimate canvas width as √(totalArea) with a margin factor</li>
     *   <li>Place faces left-to-right, opening a new shelf when the row overflows</li>
     *   <li>Normalize all positions by the maximum packed dimension to preserve aspect ratios</li>
     * </ol>
     */
    private ShelfResult shelfPack(List<FaceMeasurement> measurements) {
        if (measurements.isEmpty()) {
            return new ShelfResult(List.of(), 0, 0.0f);
        }

        float totalFaceArea = 0.0f;
        float maxFaceWidth = 0.0f;
        for (FaceMeasurement m : measurements) {
            totalFaceArea += m.width() * m.height();
            maxFaceWidth = Math.max(maxFaceWidth, m.width());
        }

        // Canvas width: √(totalArea) with margin, but at least the widest face
        float canvasWidth = Math.max(
            (float) Math.sqrt(totalFaceArea) * CANVAS_WIDTH_MARGIN,
            maxFaceWidth
        );

        // Place faces in shelves
        List<PackedPosition> packed = new ArrayList<>(measurements.size());
        float shelfX = 0.0f;
        float shelfY = 0.0f;
        float shelfHeight = 0.0f;
        int shelfCount = 1;

        for (FaceMeasurement m : measurements) {
            if (shelfX + m.width() > canvasWidth && shelfX > 0.0f) {
                shelfY += shelfHeight;
                shelfX = 0.0f;
                shelfHeight = 0.0f;
                shelfCount++;
            }

            packed.add(new PackedPosition(m.faceId(), shelfX, shelfY, m.width(), m.height()));
            shelfX += m.width();
            shelfHeight = Math.max(shelfHeight, m.height());
        }

        // Compute actual bounding box of the packed layout
        float maxX = 0.0f;
        float maxY = 0.0f;
        for (PackedPosition p : packed) {
            maxX = Math.max(maxX, p.x() + p.width());
            maxY = Math.max(maxY, p.y() + p.height());
        }

        // Normalize to 0..1 preserving aspect ratios (scale uniformly by max dimension)
        float scale = Math.max(maxX, maxY);
        if (scale < EPSILON) {
            scale = 1.0f;
        }

        List<PackedPosition> normalized = new ArrayList<>(packed.size());
        for (PackedPosition p : packed) {
            normalized.add(new PackedPosition(
                p.faceId(),
                p.x() / scale,
                p.y() / scale,
                p.width() / scale,
                p.height() / scale
            ));
        }

        float utilization = (maxX * maxY > 0.0f) ? totalFaceArea / (maxX * maxY) : 0.0f;

        return new ShelfResult(Collections.unmodifiableList(normalized), shelfCount, utilization);
    }

    // ── Apply results ───────────────────────────────────────────────────────

    /**
     * Write packed UV regions back to the texture manager.
     */
    private static void applyPackedRegions(List<PackedPosition> positions, int materialId,
                                            IFaceTextureManager textureManager) {
        for (PackedPosition p : positions) {
            FaceTextureMapping.UVRegion region = new FaceTextureMapping.UVRegion(
                p.x(), p.y(),
                p.x() + p.width(), p.y() + p.height()
            );
            FaceTextureMapping mapping = new FaceTextureMapping(
                p.faceId(), materialId, region, FaceTextureMapping.UVRotation.NONE
            );
            textureManager.setFaceMapping(mapping);
        }
    }
}
