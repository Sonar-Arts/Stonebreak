package com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry;

import com.openmason.engine.voxel.mms.mmsGeometry.MmsCuboidGenerator;
import com.stonebreak.blocks.Water;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;
import com.openmason.engine.voxel.mms.mmsTexturing.MmsTextureMapper;

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
    private static final float WATER_FLAG_EPSILON = 0.0001f;
    private static final float HEIGHT_ALIGNMENT_EPSILON = 0.0001f;

    private final World world;
    private final float[] waterTopTextureBounds; // [uMin, uMax, vMin, vMax]

    private long cachedCornerKey = Long.MIN_VALUE;
    private float[] cachedCornerHeights;

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
    private static final ThreadLocal<float[]> SCRATCH_WATER_TEXCOORDS =
        ThreadLocal.withInitial(() -> new float[8]);

    /**
     * Creates a water generator with world reference for neighbor lookups.
     *
     * @param world World instance for accessing water blocks
     * @param textureMapper Texture mapper for resolving top-face UV data
     */
    public MmsWaterGenerator(World world, MmsTextureMapper textureMapper) {
        if (world == null) {
            throw new IllegalArgumentException("World cannot be null for water generator");
        }
        if (textureMapper == null) {
            throw new IllegalArgumentException("Texture mapper cannot be null for water generator");
        }
        this.world = world;
        float[] topFaceTex = textureMapper.generateFaceTextureCoordinates(BlockType.WATER, BlockType.Face.TOP.getIndex());
        this.waterTopTextureBounds = extractTextureBounds(topFaceTex);
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
     * Generates texture coordinates for water face with proper height-based V coordinate adjustment.
     * This prevents texture stretching on side faces when water has variable heights.
     *
     * Side Face Vertex Layout (looking at the face from outside):
     * v3 (top-left) ---- v2 (top-right)
     * |                   |
     * |                   |
     * v0 (bottom-left) -- v1 (bottom-right)
     *
     * Texture Coordinate Mapping:
     * - U: 0 (left) to 1 (right)
     * - V: 0 (top) to 1 (bottom)
     *
     * @param face Face index (0-5)
     * @param blockX Block X coordinate
     * @param blockY Block Y coordinate
     * @param blockZ Block Z coordinate
     * @param baseTexCoords Base texture coordinates from texture mapper
     * @return Adjusted texture coordinates (8 floats = 4 vertices × 2 coords)
     */
    public float[] generateWaterTextureCoordinates(int face, int blockX, int blockY, int blockZ, float[] baseTexCoords) {
        if (baseTexCoords.length != 8) {
            throw new IllegalArgumentException("Base texture coordinates must have 8 floats (4 vertices × 2 coords)");
        }

        // Side faces remap to the water top-face texture; top/bottom pass through.
        // Returns a per-thread scratch buffer — caller must consume before the next
        // call on the same thread overwrites it.
        if (face < 2 || face > 5) {
            return baseTexCoords;
        }

        float[] texCoords = SCRATCH_WATER_TEXCOORDS.get();
        float[] bounds = waterTopTextureBounds;
        float uMin = bounds[0];
        float uMax = bounds[1];
        float vTop = bounds[2];
        float vBottom = bounds[3];

        texCoords[0] = uMin;    texCoords[1] = vBottom;
        texCoords[2] = uMax;    texCoords[3] = vBottom;
        texCoords[4] = uMax;    texCoords[5] = vTop;
        texCoords[6] = uMin;    texCoords[7] = vTop;

        return texCoords;
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
        if (key == cachedCornerKey && cachedCornerHeights != null) {
            return cachedCornerHeights;
        }

        // Corner indices (see header): 0=(x, z+1), 1=(x+1, z+1), 2=(x+1, z), 3=(x, z).
        float[] heights = new float[]{
            cornerHeightAtWorld(blockX,     blockY, blockZ + 1),
            cornerHeightAtWorld(blockX + 1, blockY, blockZ + 1),
            cornerHeightAtWorld(blockX + 1, blockY, blockZ),
            cornerHeightAtWorld(blockX,     blockY, blockZ),
        };
        normalizeCornerHeights(heights);

        cachedCornerKey = key;
        cachedCornerHeights = heights;
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
                    WaterBlock wb = Water.getWaterBlock(bx, blockY, bz);
                    if (wb == null || wb.isSource()) {
                        sourceCount++;
                    }
                } else {
                    BlockType bt = world.getBlockAt(bx, blockY, bz);
                    if (bt != null && !bt.isTransparent() && bt != BlockType.WATER) {
                        solidCount++;
                    }
                }

                WaterBlock below = Water.getWaterBlock(bx, blockY - 1, bz);
                if (below != null && below.isSource()) {
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

        // Connect seamlessly to water below
        if (belowType == BlockType.WATER) {
            WaterBlock waterBelow = Water.getWaterBlock(blockX, belowY, blockZ);
            if (waterBelow != null) {
                float waterBelowHeight = waterBelow.level() == WaterBlock.SOURCE_LEVEL ?
                    MAX_WATER_HEIGHT : (8 - waterBelow.level()) * MAX_WATER_HEIGHT / 8.0f;
                waterBelowHeight = clampWaterHeight(waterBelowHeight);
                return belowY + waterBelowHeight;
            }
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
     * Resolves water height at a specific position.
     *
     * @param x Block X coordinate
     * @param y Block Y coordinate
     * @param z Block Z coordinate
     * @return Water height or NaN if no water
     */
    private float resolveWaterHeight(int x, int y, int z) {
        WaterBlock waterBlock = Water.getWaterBlock(x, y, z);
        if (waterBlock != null) {
            float height = waterBlock.level() == WaterBlock.SOURCE_LEVEL ?
                MAX_WATER_HEIGHT : (8 - waterBlock.level()) * MAX_WATER_HEIGHT / 8.0f;
            return clampWaterHeight(height);
        }

        float level = Water.getWaterLevel(x, y, z);
        if (level > 0.0f) {
            return clampWaterHeight(level * MAX_WATER_HEIGHT);
        }

        if (world.getBlockAt(x, y, z) == BlockType.WATER) {
            return clampWaterHeight(MAX_WATER_HEIGHT);
        }

        return Float.NaN;
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

    private float[] extractTextureBounds(float[] texCoords) {
        if (texCoords == null || texCoords.length != 8) {
            throw new IllegalArgumentException("Water top texture coordinates must contain 8 floats");
        }

        float uMin = Math.min(Math.min(texCoords[0], texCoords[2]), Math.min(texCoords[4], texCoords[6]));
        float uMax = Math.max(Math.max(texCoords[0], texCoords[2]), Math.max(texCoords[4], texCoords[6]));
        float vMin = Math.min(Math.min(texCoords[1], texCoords[3]), Math.min(texCoords[5], texCoords[7]));
        float vMax = Math.max(Math.max(texCoords[1], texCoords[3]), Math.max(texCoords[5], texCoords[7]));

        return new float[]{uMin, uMax, vMin, vMax};
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
