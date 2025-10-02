package com.stonebreak.world.chunk.api.mightyMesh.mmsGeometry;

import com.stonebreak.blocks.BlockType;

/**
 * Mighty Mesh System - Geometry generation service interface.
 *
 * Defines the contract for generating vertex positions and normals for different
 * block types. Implementations can be registered per block type using the
 * Strategy pattern.
 *
 * Design Philosophy:
 * - Open/Closed Principle: Extend without modifying core code
 * - Interface Segregation: Focused geometry generation contract
 * - Single Responsibility: Only handles geometric data
 *
 * @since MMS 1.0
 */
public interface MmsGeometryService {

    /**
     * Generates vertex positions for a block face.
     *
     * @param face Face index (0=top, 1=bottom, 2=north, 3=south, 4=east, 5=west)
     * @param blockType Type of block
     * @param worldX World X coordinate
     * @param worldY World Y coordinate
     * @param worldZ World Z coordinate
     * @param blockHeight Visual height of the block (for water, grass, etc.)
     * @return Array of 12 floats representing 4 vertices (x,y,z each)
     */
    float[] generateFaceVertices(int face, BlockType blockType,
                                  float worldX, float worldY, float worldZ,
                                  float blockHeight);

    /**
     * Generates vertex positions for a block face with water surface data.
     *
     * @param face Face index
     * @param blockType Type of block
     * @param worldX World X coordinate
     * @param worldY World Y coordinate
     * @param worldZ World Z coordinate
     * @param blockHeight Base block height
     * @param waterCornerHeights Corner-specific water heights (4 floats) or null
     * @param waterBottomHeight Bottom attachment height for water
     * @return Array of 12 floats representing 4 vertices (x,y,z each)
     */
    float[] generateFaceVerticesWithWater(int face, BlockType blockType,
                                          float worldX, float worldY, float worldZ,
                                          float blockHeight,
                                          float[] waterCornerHeights,
                                          float waterBottomHeight);

    /**
     * Generates normal vectors for a block face.
     *
     * @param face Face index (0=top, 1=bottom, 2=north, 3=south, 4=east, 5=west)
     * @return Array of 12 floats representing 4 normal vectors (nx,ny,nz each)
     */
    float[] generateFaceNormals(int face);

    /**
     * Calculates the visual height of a block.
     * Used for blocks with variable heights (water, grass, etc.).
     *
     * @param blockType Type of block
     * @param worldX World X coordinate
     * @param worldY World Y coordinate
     * @param worldZ World Z coordinate
     * @return Block height (0.0 to 1.0)
     */
    float calculateBlockHeight(BlockType blockType, float worldX, float worldY, float worldZ);

    /**
     * Calculates water corner heights for realistic water surfaces.
     * Returns null if block is not water.
     *
     * @param blockX Block X coordinate
     * @param blockY Block Y coordinate
     * @param blockZ Block Z coordinate
     * @param baseHeight Base water height
     * @return Array of 4 corner heights or null if not water
     */
    float[] calculateWaterCornerHeights(int blockX, int blockY, int blockZ, float baseHeight);

    /**
     * Checks if a block type requires custom geometry generation.
     *
     * @param blockType Type of block
     * @return true if block needs custom geometry
     */
    default boolean requiresCustomGeometry(BlockType blockType) {
        return blockType == BlockType.WATER ||
               blockType == BlockType.ROSE ||
               blockType == BlockType.DANDELION;
    }
}
