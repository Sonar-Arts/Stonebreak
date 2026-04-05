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
        if (blockType instanceof BlockTypeAdapter adapter) {
            BlockType.Face blockFace = face >= 0 && face < FACES.length ? FACES[face] : null;
            return textureAtlas.getBlockFaceUVs(adapter.unwrap(), blockFace);
        }
        return new float[]{0, 0, 1, 1};
    }
}
