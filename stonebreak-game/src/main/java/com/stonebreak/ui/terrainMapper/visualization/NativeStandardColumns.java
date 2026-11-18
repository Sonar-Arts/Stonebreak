package com.stonebreak.ui.terrainMapper.visualization;

import com.stonebreak.world.generation.NativeCubeGenerator;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.NativeTerrain;
import com.stonebreak.world.generation.TerrainGeneratorType;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The Standard world's own column heights for the terrain mapper: the native generator
 * ({@code libstonebreak_worldgen}) built for one seed at one {@link TerrainScalePreset}, asked one point at a
 * time through {@code sbw_sample_columns}' strided raw path (a single column evaluation, no 32x32 tile and no
 * carving), so the preview shows exactly the heights - and water - a world created with these settings gets.
 *
 * <p>Sampled from the preview worker's parallel rows (the generator is safe for concurrent queries) while the
 * render thread may close it on a reseed or a scale change: every query holds a read lock, {@link #close}
 * the write lock, and a query after close answers {@link Float#NaN} instead of touching freed native memory.
 */
public final class NativeStandardColumns implements AutoCloseable {

    private final NativeTerrain terrain;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean closed;

    private NativeStandardColumns(NativeTerrain terrain) {
        this.terrain = terrain;
    }

    /** The native Standard generator for {@code seed} at {@code scale}, or null when the library is missing. */
    public static NativeStandardColumns open(long seed, TerrainScalePreset scale) {
        if (!NativeTerrain.isAvailable()) {
            return null;
        }
        try {
            return new NativeStandardColumns(NativeTerrain.create(seed, TerrainGeneratorType.STANDARD, scale, null));
        } catch (RuntimeException | LinkageError e) {
            System.err.println("[TerrainMapper] native Standard preview unavailable: " + e);
            return null;
        }
    }

    /** Raw surface height (first y above ground) at the column, or NaN once closed. */
    public float height(int worldX, int worldZ) {
        int[] h = new int[1];
        return sample(worldX, worldZ, h, null) ? h[0] : Float.NaN;
    }

    /** Standing water level at the column, {@link NativeCubeGenerator#NO_WATER} when dry, NaN once closed. */
    public float water(int worldX, int worldZ) {
        int[] h = new int[1];
        int[] w = new int[1];
        return sample(worldX, worldZ, h, w) ? w[0] : Float.NaN;
    }

    private boolean sample(int worldX, int worldZ, int[] heights, int[] water) {
        ReentrantReadWriteLock.ReadLock read = lock.readLock();
        read.lock();
        try {
            if (closed) {
                return false;
            }
            // count 1, stride 2: the strided raw path evaluates the single column without building its tile.
            terrain.nativeGenerator().sampleColumns(worldX, worldZ, 1, 2, false, heights, water, null, null);
            return true;
        } finally {
            read.unlock();
        }
    }

    @Override
    public void close() {
        ReentrantReadWriteLock.WriteLock write = lock.writeLock();
        write.lock();
        try {
            if (!closed) {
                closed = true;
                terrain.close();
            }
        } finally {
            write.unlock();
        }
    }

    /**
     * The factor the native generator multiplies block coordinates by to reach the 1:4 shape space its shape
     * and climate fields are evaluated in ({@code column.cpp}: {@code shapeScale = horizontal metres per block / 60},
     * so 1:2 terrain is twice as wide in blocks as 1:4). The Java noise previews (continentalness, erosion, climate, biome) sample at the same position
     * so they line up with the native heights at every preset.
     */
    public static double shapeScale(TerrainScalePreset scale) {
        TerrainScalePreset s = scale == null ? TerrainScalePreset.ONE_TO_FOUR : scale;
        return s.horizontalMetresPerBlock() / 60.0;
    }
}
