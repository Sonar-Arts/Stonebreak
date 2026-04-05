package com.openmason.engine.voxel.mms.mmsTexturing;

import com.openmason.engine.voxel.IBlockType;

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
    float[] generateFaceTextureCoordinates(IBlockType blockType, int face);

    /**
     * Generates texture coordinates for a cross-section block.
     *
     * @param blockType Type of block (must be cross-section type)
     * @return Array of 16 floats for 8 vertices (2 planes * 2 sides * 4 vertices)
     */
    float[] generateCrossTextureCoordinates(IBlockType blockType);

    /**
     * Generates alpha test flags for vertices.
     *
     * @param blockType Type of block
     * @return Array of 4 alpha flags (one per vertex), 0.0 or 1.0
     */
    float[] generateAlphaFlags(IBlockType blockType);

    /**
     * Checks if a block type requires alpha testing.
     *
     * @param blockType Type of block
     * @return true if alpha testing is needed
     */
    boolean requiresAlphaTesting(IBlockType blockType);
}
