package com.stonebreak.world.chunk.api.voxel;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.ITextureCoordProvider;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.TextureAtlas;

/**
 * Adapts the game's {@link TextureAtlas} to the engine's {@link ITextureCoordProvider} interface.
 */
public record TextureAtlasAdapter(TextureAtlas textureAtlas) implements ITextureCoordProvider {

    private static final BlockType.Face[] FACES = BlockType.Face.values();

    @Override
    public float[] getBlockFaceUVs(IBlockType blockType, int face) {
        BlockType bt;
        if (blockType instanceof BlockType directType) {
            bt = directType;
        } else if (blockType instanceof BlockTypeAdapter adapter) {
            bt = adapter.unwrap();
        } else {
            return new float[]{0, 0, 1, 1};
        }
        BlockType.Face blockFace = face >= 0 && face < FACES.length ? FACES[face] : null;
        return textureAtlas.getBlockFaceUVs(bt, blockFace);
    }
}
