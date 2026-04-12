package com.openmason.engine.voxel.sbo.sboRenderer;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;

/**
 * Strategy interface for SBO block face culling decisions.
 *
 * <p>Determines which faces of an SBO block should be included in the
 * chunk mesh. Implementations can provide different culling behaviors:
 * <ul>
 *   <li>Standard cubic culling — skip faces adjacent to opaque blocks</li>
 *   <li>Always-render — emit all faces regardless of neighbors (for non-cubic shapes)</li>
 *   <li>Custom rules — partial occlusion, transparency-aware, etc.</li>
 * </ul>
 *
 * @see com.openmason.engine.voxel.mms.mmsIntegration.MmsFaceCullingService
 */
public interface SBOCullingPolicy {

    /**
     * Determines if a specific face of a block should be rendered.
     *
     * @param blockType the block being rendered
     * @param lx        local X coordinate within the chunk
     * @param ly        local Y coordinate within the chunk
     * @param lz        local Z coordinate within the chunk
     * @param face      MMS face index (0=top, 1=bottom, 2=north, 3=south, 4=east, 5=west)
     * @param chunkData chunk data for neighbor lookups
     * @return true if the face should be rendered
     */
    boolean shouldRenderFace(IBlockType blockType, int lx, int ly, int lz,
                             int face, CcoChunkData chunkData);
}
