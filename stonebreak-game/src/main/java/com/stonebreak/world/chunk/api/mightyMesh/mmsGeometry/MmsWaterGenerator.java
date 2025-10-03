package com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry;

import com.stonebreak.blocks.Water;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsBufferLayout;

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

    private final World world;

    /**
     * Creates a water generator with world reference for neighbor lookups.
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
        float blockHeight = getWaterHeight(blockX, blockY, blockZ);
        float[] baseCornerHeights = computeWaterCornerHeights(blockX, blockY, blockZ, blockHeight);
        float[] cornerHeights = sewCornerHeights(blockX, blockY, blockZ, baseCornerHeights);
        float waterBottomY = computeWaterBottomAttachmentHeight(blockX, blockY, blockZ, worldY);

        float[] vertices = new float[MmsBufferLayout.POSITION_SIZE * MmsBufferLayout.VERTICES_PER_QUAD];
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

        // Clone base coordinates
        float[] texCoords = baseTexCoords.clone();

        // Only adjust side faces (not top or bottom)
        if (face >= 2 && face <= 5) {
            float blockHeight = getWaterHeight(blockX, blockY, blockZ);
            float[] cornerHeights = computeWaterCornerHeights(blockX, blockY, blockZ, blockHeight);
            cornerHeights = sewCornerHeights(blockX, blockY, blockZ, cornerHeights);

            // For side faces, we need to adjust the V coordinate of top vertices based on actual height
            // The V coordinate represents vertical position in the texture:
            // V=0 is top of texture, V=1 is bottom of texture
            // For water at height 0.875, we want V to be close to 0 (top of texture)

            // Vertex order for side faces: v0 (bottom-left), v1 (bottom-right), v2 (top-right), v3 (top-left)
            // In the vertices array for MmsWaterGenerator side faces:
            // North (2): [1,0,0], [0,0,0], [0,h3,0], [1,h2,0]  -> v0, v1, v2(h2), v3(h3)
            // South (3): [0,0,1], [1,0,1], [1,h1,1], [0,h0,1]  -> v0, v1, v2(h1), v3(h0)
            // East  (4): [1,0,1], [1,0,0], [1,h2,0], [1,h1,1]  -> v0, v1, v2(h2), v3(h1)
            // West  (5): [0,0,0], [0,0,1], [0,h0,1], [0,h3,0]  -> v0, v1, v2(h0), v3(h3)

            // Get the corner heights for top vertices (v2 and v3)
            float heightV2 = 0.0f; // Top-right vertex height
            float heightV3 = 0.0f; // Top-left vertex height

            switch (face) {
                case 2 -> { // North face (-Z): v2=corner2, v3=corner3
                    heightV2 = cornerHeights[2];
                    heightV3 = cornerHeights[3];
                }
                case 3 -> { // South face (+Z): v2=corner1, v3=corner0
                    heightV2 = cornerHeights[1];
                    heightV3 = cornerHeights[0];
                }
                case 4 -> { // East face (+X): v2=corner2, v3=corner1
                    heightV2 = cornerHeights[2];
                    heightV3 = cornerHeights[1];
                }
                case 5 -> { // West face (-X): v2=corner0, v3=corner3
                    heightV2 = cornerHeights[0];
                    heightV3 = cornerHeights[3];
                }
            }

            // Calculate V coordinate based on height
            // V = 1.0 - height means: at height 0.875, V = 0.125 (near top of texture)
            // At height 0.0, V = 1.0 (bottom of texture)
            float vTopRight = 1.0f - heightV2; // v2 (top-right)
            float vTopLeft = 1.0f - heightV3;  // v3 (top-left)

            // Texture coordinates are ordered: v0(u,v), v1(u,v), v2(u,v), v3(u,v)
            // Update only the V coordinate for top vertices (v2 and v3)
            texCoords[5] = vTopRight; // v2.v (index 4 is v2.u, index 5 is v2.v)
            texCoords[7] = vTopLeft;  // v3.v (index 6 is v3.u, index 7 is v3.v)
        }

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
        float[] cornerHeights = computeWaterCornerHeights(blockX, blockY, blockZ, blockHeight);
        cornerHeights = sewCornerHeights(blockX, blockY, blockZ, cornerHeights);
        float[] waterFlags = new float[MmsBufferLayout.VERTICES_PER_QUAD];

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
     * Gets the water height for a block position.
     *
     * @param blockX Block X coordinate
     * @param blockY Block Y coordinate
     * @param blockZ Block Z coordinate
     * @return Water height (0.0 - 0.875)
     */
    private float getWaterHeight(int blockX, int blockY, int blockZ) {
        WaterBlock waterBlock = Water.getWaterBlock(blockX, blockY, blockZ);
        if (waterBlock != null) {
            float height = waterBlock.level() == WaterBlock.SOURCE_LEVEL ?
                MAX_WATER_HEIGHT : (8 - waterBlock.level()) * MAX_WATER_HEIGHT / 8.0f;
            return clampWaterHeight(height);
        }

        float level = Water.getWaterLevel(blockX, blockY, blockZ);
        if (level > 0.0f) {
            return clampWaterHeight(level * MAX_WATER_HEIGHT);
        }

        if (world.getBlockAt(blockX, blockY, blockZ) == BlockType.WATER) {
            return clampWaterHeight(MAX_WATER_HEIGHT);
        }

        return 0.0f;
    }

    /**
     * Computes corner heights for water surface with neighbor blending.
     *
     * Corner indices:
     * 0: (x, z+1) - South-West
     * 1: (x+1, z+1) - South-East
     * 2: (x+1, z) - North-East
     * 3: (x, z) - North-West
     *
     * @param blockX Block X coordinate
     * @param blockY Block Y coordinate
     * @param blockZ Block Z coordinate
     * @param blockHeight Base block height
     * @return Array of 4 corner heights
     */
    private float[] computeWaterCornerHeights(int blockX, int blockY, int blockZ, float blockHeight) {
        float initialHeight = clampWaterHeight(blockHeight);
        float[] heights = new float[]{initialHeight, initialHeight, initialHeight, initialHeight};

        // Check for water above or below for vertical blending
        WaterBlock waterAbove = Water.getWaterBlock(blockX, blockY + 1, blockZ);
        WaterBlock waterBelow = Water.getWaterBlock(blockX, blockY - 1, blockZ);
        BlockType blockBelow = (blockY > 0) ? world.getBlockAt(blockX, blockY - 1, blockZ) : BlockType.AIR;
        WaterBlock currentWater = Water.getWaterBlock(blockX, blockY, blockZ);

        boolean hasWaterAbove = waterAbove != null;
        boolean shouldConnectBelow = waterBelow != null || blockBelow == BlockType.WATER;
        boolean isFlowAboveSource = (currentWater != null && !currentWater.isSource()) &&
            (waterBelow != null && waterBelow.isSource());

        // Corner offsets: (dx, dz) for each corner's contributing neighbors
        int[][][] cornerOffsets = new int[][][]{
            {{0, 0}, {-1, 0}, {0, 1}, {-1, 1}},  // Corner 0: (x, z+1)
            {{0, 0}, {1, 0}, {0, 1}, {1, 1}},    // Corner 1: (x+1, z+1)
            {{0, 0}, {1, 0}, {0, -1}, {1, -1}},  // Corner 2: (x+1, z)
            {{0, 0}, {-1, 0}, {0, -1}, {-1, -1}} // Corner 3: (x, z)
        };

        for (int corner = 0; corner < 4; corner++) {
            float minHeight = clampWaterHeight(blockHeight);
            int waterNeighborCount = 0;
            int solidNeighborCount = 0;
            boolean allSourceBlocks = true;
            float heightSum = 0.0f;

            // Sample horizontal neighbors
            for (int[] offset : cornerOffsets[corner]) {
                int sampleX = blockX + offset[0];
                int sampleZ = blockZ + offset[1];

                // Check for solid blocks (walls)
                BlockType neighborType = world.getBlockAt(sampleX, blockY, sampleZ);
                if (neighborType != null && !neighborType.isTransparent() && neighborType != BlockType.WATER) {
                    solidNeighborCount++;
                    continue;
                }

                float height = resolveWaterHeight(sampleX, blockY, sampleZ);
                if (!Float.isNaN(height)) {
                    waterNeighborCount++;
                    heightSum += height;

                    WaterBlock neighborWater = Water.getWaterBlock(sampleX, blockY, sampleZ);
                    if (neighborWater == null || !neighborWater.isSource()) {
                        allSourceBlocks = false;
                    }

                    minHeight = Math.min(minHeight, height);
                }

                // Blend with water above diagonally
                if (hasWaterAbove && (offset[0] != 0 || offset[1] != 0)) {
                    float heightAbove = resolveWaterHeight(sampleX, blockY + 1, sampleZ);
                    if (!Float.isNaN(heightAbove)) {
                        minHeight = Math.max(minHeight, heightAbove * 0.5f);
                    }
                }

                // Blend with water below diagonally
                if (shouldConnectBelow && (offset[0] != 0 || offset[1] != 0)) {
                    float heightBelow = resolveWaterHeight(sampleX, blockY - 1, sampleZ);
                    if (!Float.isNaN(heightBelow)) {
                        minHeight = Math.min(minHeight, Math.max(minHeight, heightBelow * 0.75f));
                    }
                }
            }

            // Calculate final corner height with blending
            if (solidNeighborCount > 0) {
                // Corner against walls
                if (allSourceBlocks && currentWater != null && currentWater.isSource()) {
                    heights[corner] = clampWaterHeight(blockHeight);
                } else if (waterNeighborCount > 0) {
                    float avgHeight = heightSum / waterNeighborCount;
                    float targetHeight = minHeight;

                    if (avgHeight - minHeight > 0.05f) {
                        targetHeight = minHeight + (avgHeight - minHeight) * 0.25f;
                    }

                    heights[corner] = clampWaterHeight(targetHeight);
                } else {
                    heights[corner] = clampWaterHeight(blockHeight);
                }
            } else {
                // Open corner - use minimum height
                float baseHeight = clampWaterHeight(minHeight);

                // Check for sources below neighbors
                int sourcesBelow = 0;
                for (int[] offset : cornerOffsets[corner]) {
                    int sampleX = blockX + offset[0];
                    int sampleZ = blockZ + offset[1];
                    WaterBlock neighborBelow = Water.getWaterBlock(sampleX, blockY - 1, sampleZ);
                    if (neighborBelow != null && neighborBelow.isSource()) {
                        sourcesBelow++;
                    }
                }

                // Create smooth downward curve for waterfall edges
                if (sourcesBelow > 0) {
                    float downwardPull = Math.min(1.0f, sourcesBelow / 4.0f);
                    baseHeight = baseHeight * (1.0f - downwardPull * 0.5f);
                }

                heights[corner] = baseHeight;
            }
        }

        return heights;
    }

    /**
     * Ensures this block's corner heights align with neighboring water blocks to avoid visible seams.
     *
     * @param blockX Block X coordinate
     * @param blockY Block Y coordinate
     * @param blockZ Block Z coordinate
     * @param cornerHeights Base corner heights computed for this block
     * @return Corner heights blended with neighbors along shared edges
     */
    private float[] sewCornerHeights(int blockX, int blockY, int blockZ, float[] cornerHeights) {
        float[] sewnHeights = cornerHeights.clone();

        // North neighbor (z - 1) shares corners 2 (NE) and 3 (NW)
        mergeEdgeHeightsWithNeighbor(sewnHeights, blockX, blockY, blockZ - 1,
            new int[]{2, 3}, new int[]{1, 0});

        // South neighbor (z + 1) shares corners 0 (SW) and 1 (SE)
        mergeEdgeHeightsWithNeighbor(sewnHeights, blockX, blockY, blockZ + 1,
            new int[]{0, 1}, new int[]{3, 2});

        // East neighbor (x + 1) shares corners 1 (SE) and 2 (NE)
        mergeEdgeHeightsWithNeighbor(sewnHeights, blockX + 1, blockY, blockZ,
            new int[]{1, 2}, new int[]{0, 3});

        // West neighbor (x - 1) shares corners 0 (SW) and 3 (NW)
        mergeEdgeHeightsWithNeighbor(sewnHeights, blockX - 1, blockY, blockZ,
            new int[]{0, 3}, new int[]{1, 2});

        return sewnHeights;
    }

    /**
     * Merges corner heights along an edge with the neighbor's corner heights, using the higher value to
     * avoid gaps between adjacent water quads.
     *
     * @param targetHeights Heights to update
     * @param neighborX Neighbor block X coordinate
     * @param neighborY Neighbor block Y coordinate
     * @param neighborZ Neighbor block Z coordinate
     * @param ourCorners Corner indices for this block's edge (length 2)
     * @param neighborCorners Corresponding corner indices on the neighbor block (length 2)
     */
    private void mergeEdgeHeightsWithNeighbor(float[] targetHeights, int neighborX, int neighborY, int neighborZ,
                                              int[] ourCorners, int[] neighborCorners) {
        if (ourCorners.length != neighborCorners.length) {
            throw new IllegalArgumentException("Corner arrays must be the same length");
        }

        float neighborBlockHeight = getWaterHeight(neighborX, neighborY, neighborZ);
        if (neighborBlockHeight <= 0.0f) {
            return;
        }

        float[] neighborCornerHeights = computeWaterCornerHeights(neighborX, neighborY, neighborZ, neighborBlockHeight);
        for (int i = 0; i < ourCorners.length; i++) {
            int ourCorner = ourCorners[i];
            int neighborCorner = neighborCorners[i];
            if (ourCorner < 0 || ourCorner >= targetHeights.length) {
                continue;
            }
            if (neighborCorner < 0 || neighborCorner >= neighborCornerHeights.length) {
                continue;
            }
            targetHeights[ourCorner] = Math.max(targetHeights[ourCorner], neighborCornerHeights[neighborCorner]);
        }
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
}
