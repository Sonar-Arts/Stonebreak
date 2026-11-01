package com.openmason.engine.voxel.sbo.sboRenderer;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;

/**
 * Per-face additional cull policy for SBO block emission. Lets the host
 * application suppress individual faces beyond the engine's standard
 * neighbor-based culling — useful for game-specific rules that don't
 * generalize (e.g. cull an ice block's top face when snow rests on top
 * to avoid z-fighting between coplanar ice/snow surfaces).
 *
 * <p>Returning {@code true} causes the emitter to skip that face entirely.
 */
@FunctionalInterface
public interface SBOInstanceFaceCullPolicy {

    /**
     * @param face MMS face index (0=top, 1=bottom, 2=north, 3=south, 4=east, 5=west)
     * @return true to additionally cull (skip) this face
     */
    boolean shouldCullFace(IBlockType blockType, int lx, int ly, int lz, int face, CcoChunkData chunkData);
}
