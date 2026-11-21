package com.stonebreak.world;

import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.world.voxel.VoxelLayers;

/**
 * Snow layer counts of a world, stored in the native world's SNOW layer ({@link VoxelLayers#SNOW}) since the
 * cubic voxel runtime (#447): 1..8 per SNOW cell, 0 = untracked (reads as 1). The layer is part of the cube
 * (saved with it, sent with it over the network, meshed natively as per-count snow models), so there is no
 * per-chunk map, no unload purge and no separate save/network channel any more.
 *
 * <p>Gameplay mutations ({@link #setSnowLayers}, {@link #removeSnowLayers}, {@link #addSnowLayer}) fire the
 * mutation listener (physics invalidation + server replication, wired by {@link World});
 * {@link #putRaw} hydration does not. Writes to unloaded cubes are dropped.
 */
public class SnowLayerManager {

    private final World world;

    public SnowLayerManager(World world) {
        this.world = world;
    }

    /**
     * Observer for gameplay snow mutations ({@code layers == 0} means removed). Fired by
     * {@link #setSnowLayers}/{@link #removeSnowLayers} but NOT by {@link #putRaw} hydration.
     */
    @FunctionalInterface
    public interface SnowMutationListener {
        void onSnowChanged(long x, long y, long z, int layers);
    }

    private volatile SnowMutationListener mutationListener;

    public void setMutationListener(SnowMutationListener listener) {
        this.mutationListener = listener;
    }

    private void fireChanged(long x, long y, long z, int layers) {
        SnowMutationListener l = mutationListener;
        if (l != null) {
            l.onSnowChanged(x, y, z, layers);
        }
    }

    /** In bounds and the world still open (a closed world's layers read untracked, writes are no-ops). */
    private boolean inBounds(long y) {
        return !world.isClosed() && com.stonebreak.world.operations.WorldConfiguration.inBounds(y);
    }

    private int raw(long x, long y, long z) {
        if (!inBounds(y)) {
            return 0;
        }
        int v = world.voxels().getLayer(VoxelLayers.SNOW, x, y, z);
        return v < 0 ? 0 : v;
    }

    /** Layer count 1..8 at a position (1 when untracked or unloaded). */
    public int getSnowLayers(long x, long y, long z) {
        int v = raw(x, y, z);
        return v <= 0 ? 1 : Math.min(8, v);
    }

    /** Sets the layer count (1..8); fires the listener when it changed. */
    public void setSnowLayers(long x, long y, long z, int layers) {
        if (layers < 1 || layers > 8) {
            throw new IllegalArgumentException("Snow layers must be between 1 and 8");
        }
        if (!inBounds(y)) {
            return;
        }
        int previous = world.voxels().setLayer(VoxelLayers.SNOW, x, y, z, layers);
        if (previous == CubeWorld.MISSING) {
            return;
        }
        if (previous != layers) {
            fireChanged(x, y, z, layers);
        }
    }

    /** Clears the layer count (back to untracked); fires the listener when there was one. */
    public void removeSnowLayers(long x, long y, long z) {
        if (!inBounds(y)) {
            return;
        }
        int previous = world.voxels().setLayer(VoxelLayers.SNOW, x, y, z, 0);
        if (previous > 0) {
            fireChanged(x, y, z, 0);
        }
    }

    /** Adds one layer; false when already at 8. */
    public boolean addSnowLayer(long x, long y, long z) {
        int current = getSnowLayers(x, y, z);
        if (current < 8) {
            setSnowLayers(x, y, z, current + 1);
            return true;
        }
        return false;
    }

    /** Visual height of snow at a position (0.125 to 1.0). */
    public float getSnowHeight(long x, long y, long z) {
        return getSnowLayers(x, y, z) * 0.125f;
    }

    /** Writes a count without firing the listener (hydration from a save or the network), clamped 1..8. */
    public void putRaw(long x, long y, long z, int layers) {
        if (!inBounds(y)) {
            return;
        }
        world.voxels().setLayer(VoxelLayers.SNOW, x, y, z, Math.max(1, Math.min(8, layers)));
    }
}
