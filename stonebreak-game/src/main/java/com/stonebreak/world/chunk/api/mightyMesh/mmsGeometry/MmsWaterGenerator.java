package com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry;

import com.openmason.engine.voxel.mms.mmsGeometry.MmsCuboidGenerator;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.ChunkWaterLayer;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;

/**
 * Mighty Mesh System - Water block geometry generator.
 *
 * Generates vertex positions and normals for water blocks with variable heights
 * based on water level and flow direction. Implements smooth water surface rendering
 * with per-corner height variations for realistic flow appearance.
 *
 * Design Philosophy:
 * - DRY: Reuses cuboid base logic where appropriate
 * - KISS: Clear water height calculation algorithm
 * - Performance: Pre-computed values where possible
 *
 * Water Height System:
 * - Source blocks: 7/8 block height (0.875)
 * - Flowing water: Decreases by level (level 1-7)
 * - Corner heights: Blended from neighbors for smooth appearance
 * - Bottom attachment: Connects smoothly to blocks below
 *
 * Face Indices (same as MmsCuboidGenerator):
 * - 0: Top (+Y) - Variable height based on water level
 * - 1: Bottom (-Y) - May attach to water below
 * - 2: North (-Z) - Side face with top vertex adjustment
 * - 3: South (+Z) - Side face with top vertex adjustment
 * - 4: East (+X) - Side face with top vertex adjustment
 * - 5: West (-X) - Side face with top vertex adjustment
 *
 * @since MMS 1.1 (Water Support)
 */
public class MmsWaterGenerator extends MmsCuboidGenerator {

    private static final float MIN_DISPLAYED_WATER_HEIGHT = 0.0625f; // 1/16th block - allows level 7 to render properly
    private static final float MAX_WATER_HEIGHT = 0.875f; // 7/8 block height for source blocks
    private static final float WATER_ATTACHMENT_EPSILON = 0.001f;
    /** Matches MAX_WAVE_DELTA in water.vert — waves push a surface down by at most this. */
    private static final float SIDE_CLIP_WAVE_OVERLAP = 0.2f;
    private static final float WATER_FLAG_EPSILON = 0.0001f;
    private static final float HEIGHT_ALIGNMENT_EPSILON = 0.0001f;

    private final World world;

    /**
     * Per-thread memo for {@link #getSewnCornerHeights}. The mesh pipeline runs many builder
     * threads against the singleton MmsWaterGenerator (via singleton MmsCcoAdapter); a plain
     * mutable instance cache here would race — one thread writes corner heights for chunk X,
     * another reads them for its own chunk Y, producing skewed/dark translucent water sheets
     * under burst load (e.g. join-time chunk flood). Sibling SCRATCH_* buffers below are
     * correctly thread-local for the same reason.
     */
    private static final class CornerCache {
        long key = Long.MIN_VALUE;
        float[] heights;
    }
    private static final ThreadLocal<CornerCache> CORNER_CACHE = ThreadLocal.withInitial(CornerCache::new);

    /**
     * Per-thread scratch buffers reused across water-face emissions. Water
     * blocks rebuild often (flow updates, wave-driven remeshes) so the
     * per-face allocations were noticeable. Each role gets its own slot
     * because the consumer reads multiple of them at once.
     */
    private static final ThreadLocal<float[]> SCRATCH_WATER_VERTS =
        ThreadLocal.withInitial(() -> new float[MmsBufferLayout.POSITION_SIZE * MmsBufferLayout.VERTICES_PER_QUAD]);
    private static final ThreadLocal<float[]> SCRATCH_WATER_FLAGS =
        ThreadLocal.withInitial(() -> new float[MmsBufferLayout.VERTICES_PER_QUAD]);

    /**
     * Creates a water generator with world reference for neighbor lookups.
     * Texture coordinates are no longer this class's concern — the water mesh
     * carries face-local UVs and the dedicated water shader is fully procedural.
     *
     * @param world World instance for accessing water blocks
     */
    public MmsWaterGenerator(World world) {
        if (world == null) {
            throw new IllegalArgumentException("World cannot be null for water generator");
        }
        this.world = world;
    }

    /**
     * Generates water face vertices with variable heights based on water level.
     *
     * @param face Face index (0-5)
     * @param worldX World X coordinate
     * @param worldY World Y coordinate
     * @param worldZ World Z coordinate
     * @return Vertex array with 12 floats (4 vertices × 3 components)
     */
    @Override
    public float[] generateFaceVertices(int face, float worldX, float worldY, float worldZ) {
        validateFaceIndex(face);

        int blockX = (int) Math.floor(worldX);
        int blockY = (int) Math.floor(worldY);
        int blockZ = (int) Math.floor(worldZ);

        // Get water height and corner heights for this block
        float[] cornerHeights = getSewnCornerHeights(blockX, blockY, blockZ);

        float waterBottomY = computeWaterBottomAttachmentHeight(blockX, blockY, blockZ, worldY);
        if (face >= 2) {
            waterBottomY = sealSideBottomToNeighborSurface(face, blockX, blockY, blockZ, waterBottomY);
        }

        float[] vertices = SCRATCH_WATER_VERTS.get();
        int idx = 0;

        switch (face) {
            case 0 -> { // Top face (+Y) - Use corner heights for smooth surface
                vertices[idx++] = worldX;       vertices[idx++] = worldY + cornerHeights[0]; vertices[idx++] = worldZ + 1;
                vertices[idx++] = worldX + 1;   vertices[idx++] = worldY + cornerHeights[1]; vertices[idx++] = worldZ + 1;
                vertices[idx++] = worldX + 1;   vertices[idx++] = worldY + cornerHeights[2]; vertices[idx++] = worldZ;
                vertices[idx++] = worldX;       vertices[idx++] = worldY + cornerHeights[3]; vertices[idx++] = worldZ;
            }
            case 1 -> { // Bottom face (-Y) - Use water bottom attachment
                vertices[idx++] = worldX;       vertices[idx++] = waterBottomY; vertices[idx++] = worldZ;
                vertices[idx++] = worldX + 1;   vertices[idx++] = waterBottomY; vertices[idx++] = worldZ;
                vertices[idx++] = worldX + 1;   vertices[idx++] = waterBottomY; vertices[idx++] = worldZ + 1;
                vertices[idx++] = worldX;       vertices[idx++] = waterBottomY; vertices[idx++] = worldZ + 1;
            }
            case 2 -> { // North face (-Z) - Corner heights already sewn with neighbor data
                vertices[idx++] = worldX + 1;   vertices[idx++] = waterBottomY;                      vertices[idx++] = worldZ;
                vertices[idx++] = worldX;       vertices[idx++] = waterBottomY;                      vertices[idx++] = worldZ;
                vertices[idx++] = worldX;       vertices[idx++] = worldY + cornerHeights[3];         vertices[idx++] = worldZ;
                vertices[idx++] = worldX + 1;   vertices[idx++] = worldY + cornerHeights[2];         vertices[idx++] = worldZ;
            }
            case 3 -> { // South face (+Z) - Corner heights already sewn with neighbor data
                vertices[idx++] = worldX;       vertices[idx++] = waterBottomY;                      vertices[idx++] = worldZ + 1;
                vertices[idx++] = worldX + 1;   vertices[idx++] = waterBottomY;                      vertices[idx++] = worldZ + 1;
                vertices[idx++] = worldX + 1;   vertices[idx++] = worldY + cornerHeights[1];         vertices[idx++] = worldZ + 1;
                vertices[idx++] = worldX;       vertices[idx++] = worldY + cornerHeights[0];         vertices[idx++] = worldZ + 1;
            }
            case 4 -> { // East face (+X) - Corner heights already sewn with neighbor data
                vertices[idx++] = worldX + 1;   vertices[idx++] = waterBottomY;                      vertices[idx++] = worldZ + 1;
                vertices[idx++] = worldX + 1;   vertices[idx++] = waterBottomY;                      vertices[idx++] = worldZ;
                vertices[idx++] = worldX + 1;   vertices[idx++] = worldY + cornerHeights[2];         vertices[idx++] = worldZ;
                vertices[idx++] = worldX + 1;   vertices[idx++] = worldY + cornerHeights[1];         vertices[idx++] = worldZ + 1;
            }
            case 5 -> { // West face (-X) - Corner heights already sewn with neighbor data
                vertices[idx++] = worldX;       vertices[idx++] = waterBottomY;                      vertices[idx++] = worldZ;
                vertices[idx++] = worldX;       vertices[idx++] = waterBottomY;                      vertices[idx++] = worldZ + 1;
                vertices[idx++] = worldX;       vertices[idx++] = worldY + cornerHeights[0];         vertices[idx++] = worldZ + 1;
                vertices[idx++] = worldX;       vertices[idx++] = worldY + cornerHeights[3];         vertices[idx++] = worldZ;
            }
        }

        return vertices;
    }

    /**
     * Generates water flags for each vertex.
     * Encodes water height information for shader animation.
     *
     * @param face Face index (0-5)
     * @param blockX Block X coordinate
     * @param blockY Block Y coordinate
     * @param blockZ Block Z coordinate
     * @param blockHeight Block height (0.0 - 0.875)
     * @return Water flag array with 4 floats (one per vertex)
     */
    public float[] generateWaterFlags(int face, int blockX, int blockY, int blockZ, float blockHeight) {
        float[] cornerHeights = getSewnCornerHeights(blockX, blockY, blockZ);
        float[] waterFlags = SCRATCH_WATER_FLAGS.get();

        switch (face) {
            case 0 -> { // Top face - Use corner heights
                waterFlags[0] = encodeWaterHeight(cornerHeights[0]);
                waterFlags[1] = encodeWaterHeight(cornerHeights[1]);
                waterFlags[2] = encodeWaterHeight(cornerHeights[2]);
                waterFlags[3] = encodeWaterHeight(cornerHeights[3]);
            }
            case 2 -> { // North face (-Z)
                waterFlags[0] = WATER_FLAG_EPSILON; // Bottom vertices
                waterFlags[1] = WATER_FLAG_EPSILON;
                waterFlags[2] = encodeWaterHeight(cornerHeights[3]); // Top vertices
                waterFlags[3] = encodeWaterHeight(cornerHeights[2]);
            }
            case 3 -> { // South face (+Z)
                waterFlags[0] = WATER_FLAG_EPSILON; // Bottom vertices
                waterFlags[1] = WATER_FLAG_EPSILON;
                waterFlags[2] = encodeWaterHeight(cornerHeights[1]); // Top vertices
                waterFlags[3] = encodeWaterHeight(cornerHeights[0]);
            }
            case 4 -> { // East face (+X)
                waterFlags[0] = WATER_FLAG_EPSILON; // Bottom vertices
                waterFlags[1] = WATER_FLAG_EPSILON;
                waterFlags[2] = encodeWaterHeight(cornerHeights[2]); // Top vertices
                waterFlags[3] = encodeWaterHeight(cornerHeights[1]);
            }
            case 5 -> { // West face (-X)
                waterFlags[0] = WATER_FLAG_EPSILON; // Bottom vertices
                waterFlags[1] = WATER_FLAG_EPSILON;
                waterFlags[2] = encodeWaterHeight(cornerHeights[0]); // Top vertices
                waterFlags[3] = encodeWaterHeight(cornerHeights[3]);
            }
            default -> { // Bottom face - All flags minimal
                waterFlags[0] = WATER_FLAG_EPSILON;
                waterFlags[1] = WATER_FLAG_EPSILON;
                waterFlags[2] = WATER_FLAG_EPSILON;
                waterFlags[3] = WATER_FLAG_EPSILON;
            }
        }

        return waterFlags;
    }

    /**
     * Returns corner heights for the specified block.
     *
     * Corner indices used throughout this generator:
     * 0: (x, z+1) SW, 1: (x+1, z+1) SE, 2: (x+1, z) NE, 3: (x, z) NW.
     *
     * Each corner's height is computed purely from the corner's world-space
     * (x, z) position — independent of which block is asking. This guarantees
     * the four blocks sharing a corner all produce bit-identical heights, so
     * the shader's `blockBase + waterHeight + wave` displacement never leaves
     * visible seams along shared edges.
     */
    private float[] getSewnCornerHeights(int blockX, int blockY, int blockZ) {
        long key = packBlockKey(blockX, blockY, blockZ);
        CornerCache cache = CORNER_CACHE.get();
        if (key == cache.key && cache.heights != null) {
            return cache.heights;
        }

        // Corner indices (see header): 0=(x, z+1), 1=(x+1, z+1), 2=(x+1, z), 3=(x, z).
        float[] heights = new float[]{
            cornerHeightAtWorld(blockX,     blockY, blockZ + 1),
            cornerHeightAtWorld(blockX + 1, blockY, blockZ + 1),
            cornerHeightAtWorld(blockX + 1, blockY, blockZ),
            cornerHeightAtWorld(blockX,     blockY, blockZ),
        };
        normalizeCornerHeights(heights);

        cache.key = key;
        cache.heights = heights;
        return heights;
    }

    /**
     * Computes the canonical water surface height at a corner identified by
     * its world-space (x, z) position. Looks at the 4 voxels sharing the
     * corner (at {@code blockY}) and derives a single deterministic value
     * so every block referencing this corner sees exactly the same height.
     */
    private float cornerHeightAtWorld(int cornerWx, int blockY, int cornerWz) {
        float maxWaterHeight = 0.0f;
        int waterCount = 0;
        int solidCount = 0;
        int sourceCount = 0;
        int sourcesBelow = 0;

        // The 4 voxels adjacent to this corner in block-space.
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                int bx = cornerWx + dx;
                int bz = cornerWz + dz;

                float h = resolveWaterHeight(bx, blockY, bz);
                if (!Float.isNaN(h)) {
                    waterCount++;
                    maxWaterHeight = Math.max(maxWaterHeight, h);
                    if (world.getWaterLevelAt(bx, blockY, bz) == ChunkWaterLayer.SOURCE) {
                        sourceCount++;
                    }
                } else {
                    BlockType bt = world.getBlockAt(bx, blockY, bz);
                    if (bt != null && !bt.isTransparent() && bt != BlockType.WATER) {
                        solidCount++;
                    }
                }

                if (world.getWaterLevelAt(bx, blockY - 1, bz) == ChunkWaterLayer.SOURCE) {
                    sourcesBelow++;
                }
            }
        }

        if (waterCount == 0) {
            return 0.0f;
        }

        // Any source voxel at this corner pins the surface to the full
        // source height — this is the invariant that keeps open ocean flat.
        if (sourceCount > 0) {
            return clampWaterHeight(MAX_WATER_HEIGHT);
        }

        float height = maxWaterHeight;

        // Walls present: use the tallest contributing flow directly (the
        // neighbor blending from the old algorithm was the main source of
        // per-block divergence and provided only a cosmetic curve).
        if (solidCount > 0) {
            return clampWaterHeight(height);
        }

        // Open flow with sources below → smooth waterfall edge pull.
        if (sourcesBelow > 0) {
            float downwardPull = Math.min(1.0f, sourcesBelow / 4.0f);
            height = height * (1.0f - downwardPull * 0.5f);
        }

        return clampWaterHeight(height);
    }


    /**
     * Computes water bottom attachment height for seamless connection to blocks below.
     *
     * @param blockX Block X coordinate
     * @param blockY Block Y coordinate
     * @param blockZ Block Z coordinate
     * @param worldY World Y coordinate
     * @return Bottom attachment Y coordinate
     */
    private float computeWaterBottomAttachmentHeight(int blockX, int blockY, int blockZ, float worldY) {
        if (blockY <= 0) {
            return worldY;
        }

        int belowY = blockY - 1;
        BlockType belowType = world.getBlockAt(blockX, belowY, blockZ);
        if (belowType == null) {
            return worldY;
        }

        // Connect seamlessly to water below.
        if (belowType == BlockType.WATER) {
            float waterBelowHeight = resolveWaterHeight(blockX, belowY, blockZ);
            if (Float.isNaN(waterBelowHeight)) {
                waterBelowHeight = MAX_WATER_HEIGHT;
            }
            return belowY + waterBelowHeight;
        }

        // For non-water blocks, attach to top surface
        float neighborHeight = getNeighborBlockHeight(belowType, blockX, belowY, blockZ);
        neighborHeight = Math.max(0.0f, Math.min(1.0f, neighborHeight));

        float attachmentHeight = Math.min(worldY, belowY + neighborHeight);
        if (attachmentHeight >= worldY - WATER_ATTACHMENT_EPSILON) {
            return worldY;
        }

        float adjustedAttachment = Math.max(belowY, attachmentHeight - WATER_ATTACHMENT_EPSILON);
        return Math.max(worldY - 1.0f, adjustedAttachment);
    }

    /**
     * Seals a side face's bottom edge against the water surface of the column
     * the face looks into (waterfall junctions, pool rims, source walls).
     *
     * <p>When the cell below the face's neighbor holds water, that neighbor's
     * top surface is what visually meets this face — but GPU waves displace
     * that surface downward by up to MAX_WAVE_DELTA, so a face bottom sitting
     * exactly on the attachment height opens a flickering slit at the seam.
     * If this cell itself stands on water (the face bottom is interior to our
     * own column), the bottom is extended {@link #SIDE_CLIP_WAVE_OVERLAP}
     * below the neighbor's rest surface so no wave can expose a gap. When the
     * cell stands on a solid block instead, the solid's own side face covers
     * the band below, so the bottom is left untouched (extending it would
     * z-fight with that solid face).
     */
    private float sealSideBottomToNeighborSurface(int face, int blockX, int blockY, int blockZ,
                                                  float defaultBottom) {
        if (blockY <= 0) {
            return defaultBottom;
        }
        int nx = blockX + switch (face) { case 4 -> 1; case 5 -> -1; default -> 0; };
        int nz = blockZ + switch (face) { case 2 -> -1; case 3 -> 1; default -> 0; };
        float neighborBelowHeight = resolveWaterHeight(nx, blockY - 1, nz);
        if (Float.isNaN(neighborBelowHeight)) {
            return defaultBottom;
        }
        boolean standsOnWater = !Float.isNaN(resolveWaterHeight(blockX, blockY - 1, blockZ));
        if (!standsOnWater) {
            return defaultBottom;
        }
        float sealed = (blockY - 1) + neighborBelowHeight - SIDE_CLIP_WAVE_OVERLAP;
        return Math.min(defaultBottom, sealed);
    }

    /**
     * Resolves water height at a specific position from the chunk-owned water
     * layer: sources and falling columns render at full height, flowing water
     * steps down with its level. NaN when the position is not water.
     */
    private float resolveWaterHeight(int x, int y, int z) {
        int value = world.getWaterLevelAt(x, y, z);
        if (value < 0) {
            return Float.NaN;
        }
        float height = (value == ChunkWaterLayer.SOURCE || value == ChunkWaterLayer.FALLING)
            ? MAX_WATER_HEIGHT
            : (8 - value) * MAX_WATER_HEIGHT / 8.0f;
        return clampWaterHeight(height);
    }

    /**
     * Gets neighbor block height (for snow layers, etc.).
     *
     * @param blockType Block type
     * @param blockX Block X coordinate
     * @param blockY Block Y coordinate
     * @param blockZ Block Z coordinate
     * @return Block height (0.0 - 1.0)
     */
    private float getNeighborBlockHeight(BlockType blockType, int blockX, int blockY, int blockZ) {
        if (blockType == BlockType.SNOW) {
            return world.getSnowLayerManager().getSnowHeight(blockX, blockY, blockZ);
        }
        return 1.0f; // Default full height
    }

    /**
     * Clamps water height to valid range.
     *
     * @param height Input height
     * @return Clamped height
     */
    private float clampWaterHeight(float height) {
        return Math.max(MIN_DISPLAYED_WATER_HEIGHT, Math.min(MAX_WATER_HEIGHT, height));
    }

    /**
     * Encodes water height for shader with epsilon for zero values.
     *
     * @param height Water height
     * @return Encoded water flag
     */
    private float encodeWaterHeight(float height) {
        return height > WATER_FLAG_EPSILON ? height : WATER_FLAG_EPSILON;
    }

    private void normalizeCornerHeights(float[] heights) {
        for (int i = 0; i < heights.length; i++) {
            heights[i] = alignHeight(heights[i]);
        }
    }

    private float alignHeight(float height) {
        float aligned = (float) (Math.round(height / HEIGHT_ALIGNMENT_EPSILON) * HEIGHT_ALIGNMENT_EPSILON);
        return clampWaterHeight(aligned);
    }

    private long packBlockKey(int x, int y, int z) {
        long lx = ((long) x & 0x3FFFFFFL) << 38;
        long ly = ((long) y & 0xFFFL) << 26;
        long lz = ((long) z & 0x3FFFFFFL);
        return lx | ly | lz;
    }
}
