package com.stonebreak.world.weather;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The terrain as the weather sees it: coarse relief around the camera, in
 * world-space {@link #CELL}-block cells (terrain doesn't drift with the wind,
 * so unlike the cloud map this is anchored to the world). Heights are fetched
 * on a worker thread in {@link #TILE_CELLS}-cell tiles, nearest first; each
 * tile is then {@link #derive derived} into the channels {@link TerrainFlow}
 * reads. Rain and lightning sample it here, and the renderer uploads the same
 * tiles for the clouds, so both see one relief.
 *
 * <p>Where no tile is ready the relief is flat at height 0, which leaves the
 * clouds as they are.</p>
 */
public final class TerrainRelief implements AutoCloseable {

    /** Fills {@code out[ix*count + iz]} with surface heights; see {@code TerrainGenerator.sampleReliefHeights}. */
    @FunctionalInterface
    public interface HeightSource {
        void sample(int worldX0, int worldZ0, int count, int stride, int[] out);
    }

    public static final int CELL = CloudField.CELL;
    public static final int TILE_CELLS = 64;
    public static final int TILE_BLOCKS = TILE_CELLS * CELL;
    /** Derived tiles kept on each side of the focus tile. Raw heights reach one tile further. */
    public static final int RADIUS_TILES = 7;
    /** Tiles per wrap of the GPU copy; must hold the whole derived window. */
    public static final int PERIOD_TILES = 16;

    // Channels of a derived tile.
    public static final int HEIGHT = 0;     // surface height, blocks
    public static final int SMOOTH = 1;     // height blurred over BLUR_CELLS
    public static final int GRAD_X = 2;     // slope of SMOOTH, blocks per block
    public static final int GRAD_Z = 3;
    public static final int PILEUP = 4;     // 0..1: windward face of high ground, where blocked cloud clumps
    public static final int SHIFT_X = 5;    // where light cloud here is drawn from, blocks (TerrainFlow)
    public static final int SHIFT_Z = 6;
    public static final int STEER = 7;      // 0..1: how hard ground steers light cloud here
    public static final int CHANNELS = 8;

    /** Blur radius of {@link #SMOOTH}, cells: the scale a mountain blocks air at. */
    static final int BLUR_CELLS = 4;
    /** Pile-up is the rise over this many cells upwind... */
    static final int PILEUP_CELLS = 10;
    /** ...full at this slope... */
    static final float PILEUP_SLOPE_FULL = 0.35f;
    /** ...on ground between these heights: only ground reaching up toward the clouds stops them. */
    static final float PILEUP_LOW = 96f;
    static final float PILEUP_HIGH = 160f;
    /** Blur radius of the steering field, cells: it bends cloud over a few hundred blocks, never in a step. */
    static final int FLOW_BLUR_CELLS = 6;
    /**
     * Steepest the steering may change, blocks of shift per block. Under 1, so
     * neighbouring air never swaps places: the cloud map is bent, not folded
     * over itself (a fold drew the same cloud twice, smeared between).
     */
    static final float MAX_SHEAR = 0.5f;
    /** Cells of raw height around a tile its derivation reads; under one tile. */
    static final int MARGIN = BLUR_CELLS + PILEUP_CELLS + 2 + 2 * FLOW_BLUR_CELLS + 2;

    /** Downwind unit vector. */
    static final float WIND_UX, WIND_UZ;

    static {
        double len = Math.hypot(CloudField.WIND_X, CloudField.WIND_Z);
        WIND_UX = (float) (CloudField.WIND_X / len);
        WIND_UZ = (float) (CloudField.WIND_Z / len);
    }

    /** One relief sample; see the channel constants. */
    public record Sample(float height, float smooth, float gradX, float gradZ, float pileup,
                         float shiftX, float shiftZ, float steer) {
        public static final Sample FLAT = new Sample(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
    }

    /**
     * A derived tile: {@code data[channel * TILE_CELLS² + j * TILE_CELLS + i]}
     * for cell ({@code i}, {@code j}) — z-major, the layout a texture upload takes.
     */
    public record Tile(int tx, int tz, float[] data) {
        public float at(int channel, int i, int j) {
            return data[channel * TILE_CELLS * TILE_CELLS + j * TILE_CELLS + i];
        }
    }

    private final HeightSource heights;
    /** Raw heights per tile, z-major. */
    private final Map<Long, float[]> raw = new ConcurrentHashMap<>();
    private final Map<Long, Tile> tiles = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private final Thread worker;
    private long focus;          // guarded by lock
    private boolean hasFocus;    // guarded by lock
    private volatile boolean closed;
    private boolean warned;

    public TerrainRelief(HeightSource heights) {
        this.heights = heights;
        this.worker = new Thread(this::run, "TerrainRelief");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    /** Centres the relief on world column ({@code worldX}, {@code worldZ}). Cheap; call every frame. */
    public void focus(double worldX, double worldZ) {
        long key = key(Math.floorDiv((long) Math.floor(worldX), TILE_BLOCKS),
                Math.floorDiv((long) Math.floor(worldZ), TILE_BLOCKS));
        synchronized (lock) {
            if (hasFocus && key == focus) return;
            focus = key;
            hasFocus = true;
            lock.notifyAll();
        }
    }

    /** The derived tile ({@code tx}, {@code tz}), or null while it isn't ready. */
    public Tile tile(int tx, int tz) {
        return tiles.get(key(tx, tz));
    }

    /**
     * The relief at a world position, bilinear between cell centres exactly as
     * the GPU's filtered lookup of the uploaded tiles is; cells of tiles not yet
     * ready count as flat.
     */
    public Sample sample(double worldX, double worldZ) {
        double u = worldX / CELL - 0.5, v = worldZ / CELL - 0.5;
        long i0 = (long) Math.floor(u), j0 = (long) Math.floor(v);
        float fu = (float) (u - i0), fv = (float) (v - j0);
        float[] out = new float[CHANNELS];
        accumulate(out, i0, j0, (1 - fu) * (1 - fv));
        accumulate(out, i0 + 1, j0, fu * (1 - fv));
        accumulate(out, i0, j0 + 1, (1 - fu) * fv);
        accumulate(out, i0 + 1, j0 + 1, fu * fv);
        return new Sample(out[HEIGHT], out[SMOOTH], out[GRAD_X], out[GRAD_Z], out[PILEUP],
                out[SHIFT_X], out[SHIFT_Z], out[STEER]);
    }

    private void accumulate(float[] out, long ci, long cj, float w) {
        if (w <= 0f) return;
        Tile t = tiles.get(key((int) Math.floorDiv(ci, TILE_CELLS), (int) Math.floorDiv(cj, TILE_CELLS)));
        if (t == null) return;
        int i = (int) Math.floorMod(ci, TILE_CELLS), j = (int) Math.floorMod(cj, TILE_CELLS);
        for (int c = 0; c < CHANNELS; c++) out[c] += w * t.at(c, i, j);
    }

    @Override
    public void close() {
        closed = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        worker.interrupt();
    }

    // --- Worker -----------------------------------------------------------------

    private void run() {
        while (!closed) {
            long f;
            synchronized (lock) {
                while (!hasFocus && !closed) waitQuietly();
                f = focus;
            }
            if (closed) return;
            if (!workOnce(keyX(f), keyZ(f))) {
                synchronized (lock) {
                    if (focus == f && !closed) waitQuietly();
                }
            }
        }
    }

    private void waitQuietly() {
        try {
            lock.wait();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closed = true;
        }
    }

    /** One unit of work toward the window around tile ({@code fx}, {@code fz}); false when it is complete. */
    private boolean workOnce(int fx, int fz) {
        raw.keySet().removeIf(k -> ring(keyX(k) - fx, keyZ(k) - fz) > RADIUS_TILES + 1);
        tiles.keySet().removeIf(k -> ring(keyX(k) - fx, keyZ(k) - fz) > RADIUS_TILES);
        // Nearest first, so the mountains in view are ready before the far ones.
        for (int d = 0; d <= RADIUS_TILES; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    if (ring(dx, dz) != d) continue;
                    int tx = fx + dx, tz = fz + dz;
                    if (tiles.containsKey(key(tx, tz))) continue;
                    for (int nx = -1; nx <= 1; nx++) {
                        for (int nz = -1; nz <= 1; nz++) {
                            if (!raw.containsKey(key(tx + nx, tz + nz))) {
                                raw.put(key(tx + nx, tz + nz), fetch(tx + nx, tz + nz));
                                return true;
                            }
                        }
                    }
                    tiles.put(key(tx, tz), derive(tx, tz, (i, j) -> rawAt(i, j)));
                    return true;
                }
            }
        }
        return false;
    }

    private float rawAt(long ci, long cj) {
        float[] t = raw.get(key((int) Math.floorDiv(ci, TILE_CELLS), (int) Math.floorDiv(cj, TILE_CELLS)));
        if (t == null) return 0f;
        return t[(int) Math.floorMod(cj, TILE_CELLS) * TILE_CELLS + (int) Math.floorMod(ci, TILE_CELLS)];
    }

    private float[] fetch(int tx, int tz) {
        int[] h = new int[TILE_CELLS * TILE_CELLS];
        try {
            // Probes at cell centres.
            heights.sample(tx * TILE_BLOCKS + CELL / 2, tz * TILE_BLOCKS + CELL / 2, TILE_CELLS, CELL, h);
        } catch (RuntimeException e) {
            // No terrain service (or it failed): this tile stays flat rather than retried forever.
            if (!warned) {
                warned = true;
                System.err.println("[TerrainRelief] relief unavailable, clouds ignore terrain there: " + e);
            }
            java.util.Arrays.fill(h, 0);
        }
        float[] out = new float[TILE_CELLS * TILE_CELLS];
        for (int ix = 0; ix < TILE_CELLS; ix++) {
            for (int iz = 0; iz < TILE_CELLS; iz++) {
                out[iz * TILE_CELLS + ix] = h[ix * TILE_CELLS + iz];
            }
        }
        return out;
    }

    // --- Derivation (pure) ---------------------------------------------------------

    /** Surface height at a global cell. */
    @FunctionalInterface
    interface CellHeights {
        float at(long ci, long cj);
    }

    /**
     * Derives tile ({@code tx}, {@code tz}) from raw heights, which must be known
     * {@link #MARGIN} cells around it.
     */
    static Tile derive(int tx, int tz, CellHeights heights) {
        int n = TILE_CELLS, m = MARGIN, e = n + 2 * m;
        long ci0 = (long) tx * n - m, cj0 = (long) tz * n - m;
        float[] h = new float[e * e];
        for (int j = 0; j < e; j++)
            for (int i = 0; i < e; i++)
                h[j * e + i] = heights.at(ci0 + i, cj0 + j);
        float[] smooth = blur(h, e, BLUR_CELLS);
        float[][] flow = steering(smooth, e);

        float[] data = new float[CHANNELS * n * n];
        int plane = n * n;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int ii = i + m, jj = j + m;
                float s = smooth[jj * e + ii];
                int idx = j * n + i;
                data[HEIGHT * plane + idx] = h[jj * e + ii];
                data[SMOOTH * plane + idx] = s;
                data[GRAD_X * plane + idx] = (smooth[jj * e + ii + 1] - smooth[jj * e + ii - 1]) / (2f * CELL);
                data[GRAD_Z * plane + idx] = (smooth[(jj + 1) * e + ii] - smooth[(jj - 1) * e + ii]) / (2f * CELL);

                // Pile-up: how steeply the ground rises into the wind here.
                float up = bilinear(smooth, e, ii - WIND_UX * PILEUP_CELLS, jj - WIND_UZ * PILEUP_CELLS);
                float slope = Math.max(0f, s - up) / (PILEUP_CELLS * CELL);
                data[PILEUP * plane + idx] = Math.min(1f, slope / PILEUP_SLOPE_FULL)
                        * CloudField.smoothstep(PILEUP_LOW, PILEUP_HIGH, s);
                data[SHIFT_X * plane + idx] = flow[0][jj * e + ii];
                data[SHIFT_Z * plane + idx] = flow[1][jj * e + ii];
                data[STEER * plane + idx] = flow[2][jj * e + ii];
            }
        }
        return new Tile(tx, tz, data);
    }

    /**
     * The steering field over a grid of smoothed heights: {@link TerrainFlow#steer}
     * at every cell, blurred, then eased off wherever it would change faster
     * than {@link #MAX_SHEAR}. Returns shift x, shift z and steer; valid
     * {@code 2 * FLOW_BLUR_CELLS + 1} cells in from the edges.
     */
    static float[][] steering(float[] smooth, int size) {
        float[] sx = new float[smooth.length], sz = new float[smooth.length], st = new float[smooth.length];
        for (int j = 1; j < size - 1; j++) {
            for (int i = 1; i < size - 1; i++) {
                int k = j * size + i;
                float gx = (smooth[k + 1] - smooth[k - 1]) / (2f * CELL);
                float gz = (smooth[k + size] - smooth[k - size]) / (2f * CELL);
                TerrainFlow.Flow f = TerrainFlow.steer(smooth[k], gx, gz);
                sx[k] = f.shiftX();
                sz[k] = f.shiftZ();
                st[k] = f.coverage() / TerrainFlow.CONVERGE;
            }
        }
        sx = blur(sx, size, FLOW_BLUR_CELLS);
        sz = blur(sz, size, FLOW_BLUR_CELLS);
        st = blur(st, size, FLOW_BLUR_CELLS);
        // Ease: scale each cell by how far its steepest change overshoots,
        // spread (min, then blur) so the scaling itself is gentle too.
        float[] ease = new float[smooth.length];
        java.util.Arrays.fill(ease, 1f);
        for (int j = 1; j < size - 1; j++) {
            for (int i = 1; i < size - 1; i++) {
                int k = j * size + i;
                float dxx = (sx[k + 1] - sx[k - 1]) / (2f * CELL), dxz = (sx[k + size] - sx[k - size]) / (2f * CELL);
                float dzx = (sz[k + 1] - sz[k - 1]) / (2f * CELL), dzz = (sz[k + size] - sz[k - size]) / (2f * CELL);
                float shear = (float) Math.sqrt(dxx * dxx + dxz * dxz + dzx * dzx + dzz * dzz);
                if (shear > MAX_SHEAR) ease[k] = MAX_SHEAR / shear;
            }
        }
        ease = blur(minFilter(ease, size, FLOW_BLUR_CELLS), size, FLOW_BLUR_CELLS);
        for (int k = 0; k < smooth.length; k++) {
            sx[k] *= ease[k];
            sz[k] *= ease[k];
            st[k] *= ease[k];
        }
        return new float[][]{sx, sz, st};
    }

    /** Minimum over the square of radius {@code r}, clamped at the edges. */
    private static float[] minFilter(float[] src, int size, int r) {
        float[] tmp = new float[src.length], out = new float[src.length];
        for (int j = 0; j < size; j++)
            for (int i = 0; i < size; i++) {
                float m = Float.MAX_VALUE;
                for (int k = -r; k <= r; k++) m = Math.min(m, src[j * size + Math.clamp(i + k, 0, size - 1)]);
                tmp[j * size + i] = m;
            }
        for (int j = 0; j < size; j++)
            for (int i = 0; i < size; i++) {
                float m = Float.MAX_VALUE;
                for (int k = -r; k <= r; k++) m = Math.min(m, tmp[Math.clamp(j + k, 0, size - 1) * size + i]);
                out[j * size + i] = m;
            }
        return out;
    }

    /** Separable triangle blur of radius {@code r}, clamped at the edges. */
    static float[] blur(float[] src, int size, int r) {
        float[] tmp = new float[src.length], out = new float[src.length];
        float norm = (r + 1f) * (r + 1f);
        for (int j = 0; j < size; j++)
            for (int i = 0; i < size; i++) {
                float sum = 0f;
                for (int k = -r; k <= r; k++) sum += (r + 1 - Math.abs(k)) * src[j * size + Math.clamp(i + k, 0, size - 1)];
                tmp[j * size + i] = sum / norm;
            }
        for (int j = 0; j < size; j++)
            for (int i = 0; i < size; i++) {
                float sum = 0f;
                for (int k = -r; k <= r; k++) sum += (r + 1 - Math.abs(k)) * tmp[Math.clamp(j + k, 0, size - 1) * size + i];
                out[j * size + i] = sum / norm;
            }
        return out;
    }

    private static float bilinear(float[] g, int size, float x, float y) {
        int x0 = Math.clamp((int) Math.floor(x), 0, size - 2), y0 = Math.clamp((int) Math.floor(y), 0, size - 2);
        float fx = Math.clamp(x - x0, 0f, 1f), fy = Math.clamp(y - y0, 0f, 1f);
        float a = g[y0 * size + x0] + (g[y0 * size + x0 + 1] - g[y0 * size + x0]) * fx;
        float b = g[(y0 + 1) * size + x0] + (g[(y0 + 1) * size + x0 + 1] - g[(y0 + 1) * size + x0]) * fx;
        return a + (b - a) * fy;
    }

    // --- Keys ---------------------------------------------------------------------

    static long key(long tx, long tz) {
        return (tx << 32) ^ (tz & 0xFFFFFFFFL);
    }

    static int keyX(long key) {
        return (int) (key >> 32);
    }

    static int keyZ(long key) {
        return (int) key;
    }

    private static int ring(int dx, int dz) {
        return Math.max(Math.abs(dx), Math.abs(dz));
    }
}
