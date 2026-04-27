package com.openmason.engine.voxel.lighting;

/**
 * Block-opacity lookup for a single chunk, keyed by local coords.
 *
 * <p>Supplied by the host application (the game) whenever the heightmap needs
 * to rescan a column. Keeps the engine blind to concrete block types — the
 * probe just answers "is there something at (lx, ly, lz) that blocks the sky
 * beam?". A {@code true} return means the block fully occludes sky light; glass,
 * leaves, water, and other transparent-for-sky materials return {@code false}.
 *
 * @since 1.0
 */
@FunctionalInterface
public interface ColumnOpacityProbe {
    /**
     * @param lx local x within the owning chunk (0..width-1)
     * @param ly y (0..height-1)
     * @param lz local z (0..depth-1)
     */
    boolean isOpaqueAt(int lx, int ly, int lz);
}
