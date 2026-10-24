package com.openmason.engine.voxel;

/**
 * The chunk pass a block's geometry is drawn in, cheapest first.
 */
public enum RenderLayer {
    /** Solid blocks: no blending, depth-written. */
    OPAQUE,
    /** Binary alpha (leaves, flowers): alpha-tested, depth-written. */
    CUTOUT,
    /** Partial alpha (glass, ice): blended, back-to-front. */
    TRANSLUCENT
}
