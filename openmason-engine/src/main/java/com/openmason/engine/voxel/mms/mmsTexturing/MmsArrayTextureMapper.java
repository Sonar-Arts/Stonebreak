package com.openmason.engine.voxel.mms.mmsTexturing;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.ILayerIndexProvider;
import com.openmason.engine.voxel.ITextureCoordProvider;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;

/**
 * Mighty Mesh System - texture-array based texture coordinate mapper.
 *
 * <p>The texture-array counterpart of {@link MmsAtlasTextureMapper}. Each
 * block face occupies a full layer of a {@code GL_TEXTURE_2D_ARRAY}, so UVs
 * are always the unit square; the face's identity is carried by a per-vertex
 * layer index supplied by an {@link ILayerIndexProvider}.
 *
 * @since MMS 2.0
 */
public class MmsArrayTextureMapper implements MmsTextureMapper {

    private static final ThreadLocal<float[]> SCRATCH_QUAD_TEX =
        ThreadLocal.withInitial(() -> new float[MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_QUAD]);
    private static final ThreadLocal<float[]> SCRATCH_CROSS_TEX =
        ThreadLocal.withInitial(() -> new float[MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_CROSS]);
    private static final ThreadLocal<float[]> SCRATCH_QUAD_ALPHA =
        ThreadLocal.withInitial(() -> new float[MmsBufferLayout.VERTICES_PER_QUAD]);
    private static final ThreadLocal<float[]> SCRATCH_CROSS_ALPHA =
        ThreadLocal.withInitial(() -> new float[MmsBufferLayout.VERTICES_PER_CROSS]);
    private static final ThreadLocal<float[]> SCRATCH_QUAD_LAYER =
        ThreadLocal.withInitial(() -> new float[MmsBufferLayout.VERTICES_PER_QUAD]);
    private static final ThreadLocal<float[]> SCRATCH_CROSS_LAYER =
        ThreadLocal.withInitial(() -> new float[MmsBufferLayout.VERTICES_PER_CROSS]);

    private final ITextureCoordProvider uvProvider;
    private final ILayerIndexProvider layerProvider;

    /**
     * @param uvProvider    supplies the tile-local UV rectangle (the unit square)
     * @param layerProvider supplies the texture-array layer index per block face
     */
    public MmsArrayTextureMapper(ITextureCoordProvider uvProvider, ILayerIndexProvider layerProvider) {
        if (uvProvider == null || layerProvider == null) {
            throw new IllegalArgumentException("UV and layer providers cannot be null");
        }
        this.uvProvider = uvProvider;
        this.layerProvider = layerProvider;
    }

    @Override
    public float[] generateFaceTextureCoordinates(IBlockType blockType, int face) {
        validateFaceIndex(face);
        float[] uvs = uvProvider.getBlockFaceUVs(blockType, face);
        float[] texCoords = SCRATCH_QUAD_TEX.get();
        int idx = 0;
        // Winding matches MmsAtlasTextureMapper: BL, BR, TR, TL.
        texCoords[idx++] = uvs[0]; texCoords[idx++] = uvs[3];
        texCoords[idx++] = uvs[2]; texCoords[idx++] = uvs[3];
        texCoords[idx++] = uvs[2]; texCoords[idx++] = uvs[1];
        texCoords[idx++] = uvs[0]; texCoords[idx]   = uvs[1];
        return texCoords;
    }

    @Override
    public float[] generateCrossTextureCoordinates(IBlockType blockType) {
        float[] uvs = uvProvider.getBlockFaceUVs(blockType, 0);
        float[] texCoords = SCRATCH_CROSS_TEX.get();
        int idx = 0;
        for (int plane = 0; plane < 2; plane++) {
            texCoords[idx++] = uvs[0]; texCoords[idx++] = uvs[3];
            texCoords[idx++] = uvs[2]; texCoords[idx++] = uvs[3];
            texCoords[idx++] = uvs[2]; texCoords[idx++] = uvs[1];
            texCoords[idx++] = uvs[0]; texCoords[idx++] = uvs[1];
        }
        return texCoords;
    }

    @Override
    public float[] generateFaceLayers(IBlockType blockType, int face) {
        validateFaceIndex(face);
        float layer = layerProvider.getBlockFaceLayer(blockType, face);
        float[] layers = SCRATCH_QUAD_LAYER.get();
        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_QUAD; i++) {
            layers[i] = layer;
        }
        return layers;
    }

    @Override
    public float[] generateCrossLayers(IBlockType blockType) {
        // Cross blocks use a single sprite texture; face 0 resolves it.
        float layer = layerProvider.getBlockFaceLayer(blockType, 0);
        float[] layers = SCRATCH_CROSS_LAYER.get();
        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_CROSS; i++) {
            layers[i] = layer;
        }
        return layers;
    }

    @Override
    public float[] generateAlphaFlags(IBlockType blockType) {
        float[] flags = SCRATCH_QUAD_ALPHA.get();
        float flagValue = requiresAlphaTesting(blockType) ? 1.0f : 0.0f;
        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_QUAD; i++) {
            flags[i] = flagValue;
        }
        return flags;
    }

    /** Generates alpha flags for cross-section blocks (8 vertices). */
    public float[] generateCrossAlphaFlags(IBlockType blockType) {
        float[] flags = SCRATCH_CROSS_ALPHA.get();
        float flagValue = requiresAlphaTesting(blockType) ? 1.0f : 0.0f;
        for (int i = 0; i < MmsBufferLayout.VERTICES_PER_CROSS; i++) {
            flags[i] = flagValue;
        }
        return flags;
    }

    @Override
    public boolean requiresAlphaTesting(IBlockType blockType) {
        return blockType.isTransparent();
    }

    private void validateFaceIndex(int face) {
        if (face < 0 || face >= 6) {
            throw new IllegalArgumentException("Face index must be 0-5, got: " + face);
        }
    }
}
