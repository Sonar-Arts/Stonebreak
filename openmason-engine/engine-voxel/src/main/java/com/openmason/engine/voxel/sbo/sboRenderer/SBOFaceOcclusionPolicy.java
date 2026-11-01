package com.openmason.engine.voxel.sbo.sboRenderer;

import com.openmason.engine.voxel.IBlockType;

/**
 * Shape-aware occlusion test: does a block cover the whole of one of its six
 * cell boundary planes?
 *
 * <p>A cube covers all six, so its neighbours can safely drop the faces that
 * touch it. A shaped block (stairs) covers only some — the notched sides leave
 * part of the plane open, and a neighbour that culled against them would leave
 * a hole you can see straight through.
 *
 * <p>The natural implementation reads the pre-baked
 * {@link com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp} occlusion
 * mask via {@link SBOStampCache#occludesFace}, so the answer is derived from
 * the model the artist exported rather than hardcoded per block.
 */
@FunctionalInterface
public interface SBOFaceOcclusionPolicy {

    /**
     * @param block   the block being asked about (never null in practice; return
     *                {@code true} for anything unrecognised so culling is unchanged)
     * @param mmsFace MMS face index (0=top, 1=bottom, 2=north, 3=south, 4=east, 5=west)
     * @return true when the block's geometry fills that boundary plane
     */
    boolean occludesFace(IBlockType block, int mmsFace);
}
