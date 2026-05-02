package com.openmason.engine.voxel.sbo.sboRenderer;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;

/**
 * Per-face override that can force individual faces of a normally-translucent
 * block to be emitted as fully opaque based on position and surrounding context.
 *
 * <p>The base translucency classification is per-block-type, but some visual
 * cases want per-face behavior — e.g. ice faces that touch water should look
 * opaque (avoiding double-translucent sort/z-fight artifacts) while faces
 * exposed to air remain translucent.
 *
 * <p>Returning {@code true} causes the emitter to disable both alpha-blending
 * and alpha-testing flags for that face's vertices so the fragment shader's
 * regular opaque path is taken.
 */
@FunctionalInterface
public interface SBOInstanceTranslucencyOverride {

    /**
     * @param face MMS face index (0=top, 1=bottom, 2=north, 3=south, 4=east, 5=west)
     * @return true to render this specific face as fully opaque,
     *         overriding the type-level translucency classification
     */
    boolean shouldRenderFaceAsOpaque(IBlockType blockType, int lx, int ly, int lz, int face, CcoChunkData chunkData);
}
