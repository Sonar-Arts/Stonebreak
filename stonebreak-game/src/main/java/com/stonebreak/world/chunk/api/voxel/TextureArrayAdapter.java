package com.stonebreak.world.chunk.api.voxel;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.ILayerIndexProvider;
import com.openmason.engine.voxel.ITextureCoordProvider;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.BlockTextureArray;

/**
 * Adapts the game's {@link BlockTextureArray} to the engine's texture
 * interfaces for the texture-array rendering path.
 *
 * <p>A cube face's tile-local UVs are always the unit square, so
 * {@link #getBlockFaceUVs} returns a fixed {@code [0,0,1,1]} rectangle; the
 * face's identity is carried entirely by {@link #getBlockFaceLayer}.
 */
public record TextureArrayAdapter(BlockTextureArray textureArray)
        implements ITextureCoordProvider, ILayerIndexProvider {

    /** Tile-local UV rectangle [u1,v1,u2,v2] — every block face spans its full layer. */
    private static final float[] UNIT_SQUARE = {0f, 0f, 1f, 1f};

    @Override
    public float[] getBlockFaceUVs(IBlockType blockType, int face) {
        return UNIT_SQUARE;
    }

    @Override
    public int getBlockFaceLayer(IBlockType blockType, int face) {
        BlockType bt = unwrap(blockType);
        if (bt == null) {
            return textureArray.getErrorLayer();
        }
        return textureArray.getBlockFaceLayer(bt, face);
    }

    @Override
    public int getBlockFaceLayer(IBlockType blockType, String stateName, int face) {
        BlockType bt = unwrap(blockType);
        if (bt == null) {
            return textureArray.getErrorLayer();
        }
        return textureArray.getBlockFaceLayer(bt, stateName, face);
    }

    private static BlockType unwrap(IBlockType blockType) {
        if (blockType instanceof BlockType direct) {
            return direct;
        }
        if (blockType instanceof BlockTypeAdapter adapter) {
            return adapter.unwrap();
        }
        return null;
    }
}
