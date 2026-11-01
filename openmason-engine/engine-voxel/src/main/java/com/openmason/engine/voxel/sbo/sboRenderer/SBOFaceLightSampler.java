package com.openmason.engine.voxel.sbo.sboRenderer;

import com.openmason.engine.voxel.cco.core.CcoChunkData;

/**
 * Pluggable per-vertex world-light provider for SBO block emission. Lets the
 * game inject its shadow/light sampler without the engine needing to know
 * about chunk coordinate systems or the concrete sampler implementation.
 *
 * <p>Implementations return a normalized [0,1] light value for the vertex being
 * emitted. Per-vertex sampling is required for smooth gradients; a per-face
 * sampler would produce hard shadow boundaries. The default sampler returns
 * 1.0 (fully lit) so the engine remains usable without a sampler installed.
 *
 * @since 1.0
 */
@FunctionalInterface
public interface SBOFaceLightSampler {

    /** Default sampler: everything fully lit. */
    SBOFaceLightSampler FULLY_LIT = (face, vx, vy, vz, bx, by, bz, chunkData) -> 1.0f;

    /**
     * @param face MMS face index (0=top, 1=bottom, 2=north, 3=south, 4=east, 5=west)
     * @param vx   vertex world X position (may be fractional for interior geometry)
     * @param vy   vertex world Y position
     * @param vz   vertex world Z position
     * @param bx   world X of the cell the block being emitted occupies
     * @param by   world Y of that cell
     * @param bz   world Z of that cell — lets the sampler treat the block's own
     *             cell as open so a stair's interior treads/risers are not shaded
     *             by the stair itself
     */
    float sampleVertexLight(int face, float vx, float vy, float vz, int bx, int by, int bz, CcoChunkData chunkData);
}
