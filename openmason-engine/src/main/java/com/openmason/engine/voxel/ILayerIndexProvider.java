package com.openmason.engine.voxel;

/**
 * Provides texture-array layer indices for block faces.
 *
 * <p>The companion of {@link ITextureCoordProvider} for the texture-array
 * rendering path: instead of an atlas sub-rectangle, each block face resolves
 * to a single layer of a {@code GL_TEXTURE_2D_ARRAY}. Tile-local UVs (the unit
 * square) come from {@link ITextureCoordProvider}; this interface supplies the
 * third texture coordinate — the array layer.
 */
public interface ILayerIndexProvider {

    /**
     * Get the texture-array layer index for a block face.
     *
     * @param blockType the block type
     * @param face      face index (0=top, 1=bottom, 2=north, 3=south, 4=east, 5=west)
     * @return the layer index into the block texture array
     */
    int getBlockFaceLayer(IBlockType blockType, int face);

    /**
     * State-aware overload — selects a per-state texture set for blocks with
     * SBO 1.3+ state variants (e.g. {@code "Lit"} for an active furnace).
     * Defaults to the base block face layer so providers that don't care about
     * states keep working unchanged.
     */
    default int getBlockFaceLayer(IBlockType blockType, String stateName, int face) {
        return getBlockFaceLayer(blockType, face);
    }
}
