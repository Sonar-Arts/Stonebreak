package com.stonebreak.world.chunk.api.mightyMesh.mmsTexturing;

import com.stonebreak.blocks.BlockType;

/**
 * Mighty Mesh System - Texture coordinate generation interface.
 *
 * Generates texture coordinates for block faces and assigns material flags
 * (water, alpha testing) for proper rendering.
 *
 * Design Philosophy:
 * - Single Responsibility: Only handles texture mapping
 * - Open/Closed: Extensible for custom texture layouts
 * - Interface Segregation: Focused texture operations
 *
 * @since MMS 1.0
 */
public interface MmsTextureMapper {

    /**
     * Generates texture coordinates for a standard block face.
     *
     * @param blockType Type of block
     * @param face Face index (0=top, 1=bottom, 2=north, 3=south, 4=east, 5=west)
     * @return Array of 8 floats representing texture coords for 4 vertices (u,v each)
     */
    float[] generateFaceTextureCoordinates(BlockType blockType, int face);

    /**
     * Generates texture coordinates for a cross-section block.
     *
     * @param blockType Type of block (must be cross-section type)
     * @return Array of 16 floats for 8 vertices (2 planes * 2 sides * 4 vertices)
     */
    float[] generateCrossTextureCoordinates(BlockType blockType);

    /**
     * Generates water height flags for vertices.
     * Used to encode per-vertex water surface heights.
     *
     * @param face Face index
     * @param waterCornerHeights Corner heights (4 floats) or null
     * @return Array of 4 water flags (one per vertex)
     */
    float[] generateWaterFlags(int face, float[] waterCornerHeights);

    /**
     * Generates alpha test flags for vertices.
     *
     * @param blockType Type of block
     * @return Array of 4 alpha flags (one per vertex), 0.0 or 1.0
     */
    float[] generateAlphaFlags(BlockType blockType);

    /**
     * Checks if a block type requires alpha testing.
     *
     * @param blockType Type of block
     * @return true if alpha testing is needed
     */
    default boolean requiresAlphaTesting(BlockType blockType) {
        return blockType == BlockType.ROSE ||
               blockType == BlockType.DANDELION ||
               blockType == BlockType.LEAVES ||
               blockType == BlockType.PINE_LEAVES ||
               blockType == BlockType.ELM_LEAVES;
    }

    /**
     * Checks if a block type is water.
     *
     * @param blockType Type of block
     * @return true if water block
     */
    default boolean isWaterBlock(BlockType blockType) {
        return blockType == BlockType.WATER;
    }
}
