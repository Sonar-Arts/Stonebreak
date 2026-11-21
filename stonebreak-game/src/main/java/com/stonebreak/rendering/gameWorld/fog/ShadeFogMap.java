package com.stonebreak.rendering.gameWorld.fog;

import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where the Shade lies around the camera, for its night ground fog ({@link GroundFog}):
 * a {@link #CELLS}² grid of {@link #CELL}-block cells centred on the camera, holding each
 * cell's Shade coverage (blurred over {@link #BLUR_CELLS} so the fog's edge is soft) and
 * its ground height. Built on a worker thread whenever the camera strays
 * {@link #REBUILD_DISTANCE} blocks from the grid's centre, or the world changes.
 */
public final class ShadeFogMap implements AutoCloseable {

    /** The terrain as the fog sees it. */
    public interface Source {
        boolean isShade(int worldX, int worldZ);

        int groundHeight(int worldX, int worldZ);

        static Source of(TerrainGenerator terrain) {
            return new Source() {
                @Override public boolean isShade(int x, int z) { return terrain.getBiomeAt(x, z) == BiomeType.SHADE; }
                @Override public int groundHeight(int x, int z) { return terrain.getShapedHeightAt(x, z); }
            };
        }
    }

    public static final int CELLS = 128;
    public static final int CELL = 8;
    public static final int SIZE_BLOCKS = CELLS * CELL;
    /** Blocks the camera may move from the grid's centre before a new grid is built. */
    static final int REBUILD_DISTANCE = 128;
    static final int BLUR_CELLS = 2;

    /**
     * One built grid, row-major in z as the texture wants it: cell {@code (ix, iz)} is at
     * index {@code iz * CELLS + ix}, centred on {@code (originX + ix * CELL + CELL / 2, ...)}.
     *
     * @param minGround lowest ground under any Shade coverage, blocks
     * @param maxGround highest ground under any Shade coverage, blocks
     */
    public record Grid(int originX, int originZ, float[] coverage, float[] height,
                       boolean hasShade, float minGround, float maxGround) {
        float centreX() { return originX + SIZE_BLOCKS / 2f; }
        float centreZ() { return originZ + SIZE_BLOCKS / 2f; }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shade-fog-map");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean building = new AtomicBoolean();
    private volatile Grid grid;
    private volatile Object gridSource;
    private volatile Object focusedSource;

    /**
     * Keeps a grid around the camera for {@code terrain}; call once per frame. Returns at
     * once: a grid that needs building is built on the worker and shows up in
     * {@link #grid()} when done.
     */
    public void focus(float cameraX, float cameraZ, TerrainGenerator terrain) {
        if (terrain == null) return;
        if (terrain != focusedSource) {
            focusedSource = terrain;
            grid = null;
        }
        Grid g = grid;
        if (g != null && gridSource == terrain
                && Math.abs(cameraX - g.centreX()) < REBUILD_DISTANCE
                && Math.abs(cameraZ - g.centreZ()) < REBUILD_DISTANCE) {
            return;
        }
        if (!building.compareAndSet(false, true)) return;
        int originX = Math.floorDiv((int) Math.floor(cameraX), CELL) * CELL - SIZE_BLOCKS / 2;
        int originZ = Math.floorDiv((int) Math.floor(cameraZ), CELL) * CELL - SIZE_BLOCKS / 2;
        Source source = Source.of(terrain);
        worker.execute(() -> {
            try {
                Grid built = build(originX, originZ, source);
                // A world switched mid-build: drop the stale grid, the next focus rebuilds.
                if (focusedSource == terrain) {
                    gridSource = terrain;
                    grid = built;
                }
            } finally {
                building.set(false);
            }
        });
    }

    /** The latest grid, or null before the first is built. */
    public Grid grid() {
        return grid;
    }

    /** Builds the grid whose corner cell starts at {@code (originX, originZ)}. Pure. */
    static Grid build(int originX, int originZ, Source source) {
        float[] shade = new float[CELLS * CELLS];
        boolean any = false;
        for (int iz = 0; iz < CELLS; iz++) {
            for (int ix = 0; ix < CELLS; ix++) {
                if (source.isShade(originX + ix * CELL + CELL / 2, originZ + iz * CELL + CELL / 2)) {
                    shade[iz * CELLS + ix] = 1f;
                    any = true;
                }
            }
        }
        float[] height = new float[CELLS * CELLS];
        if (!any) {
            return new Grid(originX, originZ, shade, height, false, 0f, 0f);
        }
        float[] coverage = blur(shade);
        float minGround = Float.MAX_VALUE, maxGround = -Float.MAX_VALUE;
        for (int iz = 0; iz < CELLS; iz++) {
            for (int ix = 0; ix < CELLS; ix++) {
                int i = iz * CELLS + ix;
                float h = source.groundHeight(originX + ix * CELL + CELL / 2, originZ + iz * CELL + CELL / 2);
                height[i] = h;
                if (coverage[i] > 0f) {
                    minGround = Math.min(minGround, h);
                    maxGround = Math.max(maxGround, h);
                }
            }
        }
        return new Grid(originX, originZ, coverage, height, true, minGround, maxGround);
    }

    /** Box blur over BLUR_CELLS each way, clamped at the grid's edge. */
    private static float[] blur(float[] in) {
        float[] rows = new float[in.length];
        float[] out = new float[in.length];
        int span = 2 * BLUR_CELLS + 1;
        for (int iz = 0; iz < CELLS; iz++) {
            for (int ix = 0; ix < CELLS; ix++) {
                float sum = 0f;
                for (int d = -BLUR_CELLS; d <= BLUR_CELLS; d++) {
                    sum += in[iz * CELLS + clamp(ix + d)];
                }
                rows[iz * CELLS + ix] = sum / span;
            }
        }
        for (int iz = 0; iz < CELLS; iz++) {
            for (int ix = 0; ix < CELLS; ix++) {
                float sum = 0f;
                for (int d = -BLUR_CELLS; d <= BLUR_CELLS; d++) {
                    sum += rows[clamp(iz + d) * CELLS + ix];
                }
                out[iz * CELLS + ix] = sum / span;
            }
        }
        return out;
    }

    private static int clamp(int i) {
        return Math.max(0, Math.min(CELLS - 1, i));
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }
}
