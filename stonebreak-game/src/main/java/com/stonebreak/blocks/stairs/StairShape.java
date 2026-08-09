package com.stonebreak.blocks.stairs;

import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.openmason.engine.voxel.sbo.SBONormalComputer;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOFaceConventions;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.registry.BlockRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Model-derived step profile of a stair block, as a height field over the cell
 * footprint.
 *
 * <p>Nothing here is hardcoded to a particular asset: the profile is every
 * upward-facing surface of the block's SBO model — the same triangles the
 * renderer buckets into {@code MMS_TOP}, classified by
 * {@link SBONormalComputer}. Re-export the asset with four steps instead of
 * three and collision, ground height and targeting all follow.
 *
 * <p>Queries are in <em>cell space</em>: both horizontal coordinates in
 * {@code [0,1]} relative to the block's min corner, and the returned height is
 * the surface's distance above the cell floor, also in {@code [0,1]}. The
 * facing is applied by rotating the query back into the authored model's
 * frame, so only one profile is ever built per block type.
 *
 * <p>Models are assumed to be authored in block-local space
 * ({@code [-0.5, 0.5]} on every axis) — the same assumption the SBO stamp
 * emitter makes when it stamps geometry at a cell's centre. A block whose
 * model cannot be read falls back to a full cube, which is safe (solid, just
 * not climbable) and logged once.
 */
public final class StairShape {

    private static final Logger logger = LoggerFactory.getLogger(StairShape.class);

    /** Fallback profile: one plate covering the whole cell at full height. */
    private static final StairShape FULL_CUBE =
            new StairShape(List.of(new Plate(0f, 0f, 1f, 1f, 1f)));

    private static final Map<BlockType, StairShape> CACHE = new ConcurrentHashMap<>();

    /** One upward-facing surface: its footprint in cell space and its height. */
    private record Plate(float minX, float minZ, float maxX, float maxZ, float top) {}

    private final List<Plate> plates;

    private StairShape(List<Plate> plates) {
        this.plates = plates;
    }

    /** The cached profile for a stair block type. Never null. */
    public static StairShape of(BlockType type) {
        return CACHE.computeIfAbsent(type, StairShape::build);
    }

    /**
     * Collision height of the stair at a world cell over a world-space XZ
     * rectangle — the single entry point every physics and targeting caller
     * uses, so they all agree on the profile and its orientation.
     *
     * <p>Pass the leading edge of a horizontal sweep to get the step the body
     * is about to walk into (which the caller's step-up rule then lifts it
     * onto), or the body's whole footprint to get what it stands on.
     *
     * @return height above the cell floor in {@code [0,1]}, 0 where the cell is open
     */
    public static float stepHeight(com.stonebreak.world.World world, int x, int y, int z,
                                   BlockType type,
                                   float minWorldX, float minWorldZ,
                                   float maxWorldX, float maxWorldZ) {
        // Clip to the cell in world space first: a body's footprint usually
        // spans several cells, and only the part inside this one rests on it.
        float clippedMinX = Math.max(minWorldX, x);
        float clippedMaxX = Math.min(maxWorldX, x + 1f);
        float clippedMinZ = Math.max(minWorldZ, z);
        float clippedMaxZ = Math.min(maxWorldZ, z + 1f);
        if (clippedMinX > clippedMaxX || clippedMinZ > clippedMaxZ) {
            return 0f;
        }
        StairState state = StairState.parse(world.getBlockStateAt(x, y, z));
        return of(type).maxHeightIn(state.facing(),
                clippedMinX - x, clippedMinZ - z, clippedMaxX - x, clippedMaxZ - z);
    }

    /**
     * Height of the step directly under a point of the cell floor, or 0 where
     * the stair leaves the cell open.
     *
     * @param facing the placed stair's facing
     * @param cellX  X within the cell, {@code [0,1]}
     * @param cellZ  Z within the cell, {@code [0,1]}
     */
    public float heightAt(StairState.Facing facing, float cellX, float cellZ) {
        return maxHeightIn(facing, cellX, cellZ, cellX, cellZ);
    }

    /**
     * Highest step overlapping an axis-aligned footprint of the cell floor —
     * what a body standing on (or walking into) the stair actually rests on.
     * The rectangle is clamped to the cell, so callers can pass a player AABB
     * that spills into neighbouring cells.
     *
     * @param facing the placed stair's facing
     * @return height above the cell floor in {@code [0,1]}, 0 when nothing overlaps
     */
    public float maxHeightIn(StairState.Facing facing,
                             float minCellX, float minCellZ, float maxCellX, float maxCellZ) {
        float[] a = new StairState(facing).toModelCell(clampToCell(minCellX), clampToCell(minCellZ));
        float[] b = new StairState(facing).toModelCell(clampToCell(maxCellX), clampToCell(maxCellZ));
        // Quarter turns keep the rectangle axis-aligned; the corners may swap.
        float qMinX = Math.min(a[0], b[0]);
        float qMaxX = Math.max(a[0], b[0]);
        float qMinZ = Math.min(a[1], b[1]);
        float qMaxZ = Math.max(a[1], b[1]);

        float height = 0f;
        for (Plate plate : plates) {
            if (qMinX <= plate.maxX() && qMaxX >= plate.minX()
                    && qMinZ <= plate.maxZ() && qMaxZ >= plate.minZ()
                    && plate.top() > height) {
                height = plate.top();
            }
        }
        return height;
    }

    private static float clampToCell(float v) {
        return Math.clamp(v, 0f, 1f);
    }

    // ------------------------------------------------------------------
    // Model extraction
    // ------------------------------------------------------------------

    private static StairShape build(BlockType type) {
        try {
            BlockRegistry.BlockEntry entry = BlockRegistry.getInstance().getById(type.getId()).orElse(null);
            SBOParseResult sbo = entry != null ? entry.sboData() : null;
            ParsedMeshData mesh = sbo != null ? sbo.meshData() : null;
            if (mesh == null || !mesh.hasGeometry() || mesh.indices() == null) {
                logger.warn("No SBO geometry for {} — stairs collide as a full cube", type.name());
                return FULL_CUBE;
            }

            // Same classification the chunk mesher uses, so the collidable
            // steps are exactly the surfaces drawn facing up.
            SBONormalComputer.ProcessedMesh processed = SBONormalComputer.compute(
                    mesh.vertices(), mesh.texCoords(), mesh.indices());
            float[] verts = processed.vertices();
            int[] faces = processed.triangleFaces();

            List<Plate> plates = new ArrayList<>();
            for (int tri = 0; tri < faces.length; tri++) {
                if (faces[tri] != SBOFaceConventions.MMS_TOP) {
                    continue;
                }
                int base = tri * 9;
                float top = verts[base + 1] + 0.5f;
                if (top <= 0f) {
                    continue; // a surface at or below the cell floor holds nothing up
                }
                float minX = Math.min(verts[base], Math.min(verts[base + 3], verts[base + 6])) + 0.5f;
                float maxX = Math.max(verts[base], Math.max(verts[base + 3], verts[base + 6])) + 0.5f;
                float minZ = Math.min(verts[base + 2], Math.min(verts[base + 5], verts[base + 8])) + 0.5f;
                float maxZ = Math.max(verts[base + 2], Math.max(verts[base + 5], verts[base + 8])) + 0.5f;
                plates.add(new Plate(minX, minZ, maxX, maxZ, Math.min(top, 1f)));
            }

            if (plates.isEmpty()) {
                logger.warn("{} has no upward-facing geometry — stairs collide as a full cube", type.name());
                return FULL_CUBE;
            }
            logger.info("Stair profile for {}: {} step surface(s)", type.name(), plates.size());
            return new StairShape(List.copyOf(plates));
        } catch (Exception e) {
            logger.error("Failed to derive the stair profile for {} — falling back to a full cube",
                    type.name(), e);
            return FULL_CUBE;
        }
    }
}
