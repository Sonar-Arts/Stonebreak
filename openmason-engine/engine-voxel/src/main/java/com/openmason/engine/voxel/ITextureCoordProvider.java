package com.openmason.engine.voxel;

/**
 * Provides texture atlas UV coordinates for block faces.
 *
 * <p>Abstracts the game's texture atlas so that engine-level mesh
 * generation can map block faces to texture regions without depending
 * on a specific atlas implementation.
 */
public interface ITextureCoordProvider {

    /**
     * Get the atlas UV coordinates for a block face.
     *
     * @param blockType the block type
     * @param face      face index (0=top, 1=bottom, 2=north, 3=south, 4=east, 5=west)
     * @return float array of UV coordinates for 4 vertices [u0,v0, u1,v1, u2,v2, u3,v3]
     */
    float[] getBlockFaceUVs(IBlockType blockType, int face);
}
