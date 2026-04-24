package com.openmason.engine.voxel.lighting;

/**
 * World-space queries required by {@link VertexLightSampler}. Implemented by
 * the host application to bridge between the engine's sampler math and the
 * host's chunk map / block type system.
 *
 * <p>Both methods must gracefully handle unloaded regions — never trigger chunk
 * generation from inside these callbacks. Mesh building runs on background
 * threads; an unguarded {@code getOrCreate} from here cascades into runaway
 * neighbor loads.
 *
 * <p>Future lighting signals (emissive blocks, colored block-light, time-of-day
 * sun tint) plug in here as additional query methods without breaking existing
 * sampler math.
 *
 * @since 1.0
 */
public interface LightingContext {

    /**
     * Returns the "sky begins at this Y" height for the world column, i.e. one
     * above the topmost opaque block in that (x, z). Return {@code -1} when the
     * column's chunk isn't loaded or the heightmap isn't populated yet — the
     * sampler will drop that sample from its average.
     */
    int getColumnHeight(int worldX, int worldZ);

    /**
     * True iff the chunk containing (x, y, z) is loaded AND the block there is
     * opaque. Unloaded or out-of-range coords must return {@code false}.
     */
    boolean isSolidAt(int worldX, int worldY, int worldZ);
}
