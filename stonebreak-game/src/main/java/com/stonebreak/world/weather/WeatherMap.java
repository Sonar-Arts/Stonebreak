package com.stonebreak.world.weather;

import java.util.Arrays;

/**
 * The weather bias over the land around the camera: a {@link #SIZE}² grid of
 * {@link #TEXEL_BLOCKS}-block world-space texels, addressed toroidally (texel
 * = cell mod SIZE), each holding the regional weather plus zones there. Every
 * {@link #REFRESH_SECONDS} the grid is recomputed and each texel eased toward
 * its new value, so a {@code /weather} change rolls in instead of popping.
 *
 * <p>The single source of local weather for both sides: rain and lightning
 * {@link #sample} it here, and the renderer uploads {@link #coverage} and
 * {@link #wetness} for the clouds, which filter it the same bilinear way.</p>
 */
public final class WeatherMap {

    public static final int SIZE = 128;
    /** One wrap of the grid spans the cloud map's 12288 blocks. */
    public static final int TEXEL_BLOCKS = 96;
    public static final int PERIOD_BLOCKS = SIZE * TEXEL_BLOCKS;
    static final float REFRESH_SECONDS = 0.5f;
    /** Time constant of the ease toward a new weather. */
    static final float TRANSITION_SECONDS = 12f;

    private final float[] coverage = new float[SIZE * SIZE];
    private final float[] wetness = new float[SIZE * SIZE];
    /** Cell each texel holds, or {@link Long#MIN_VALUE} when empty. */
    private final long[] cell = new long[SIZE * SIZE];
    private float sinceRefresh = Float.MAX_VALUE;
    private int version;

    public WeatherMap() {
        Arrays.fill(cell, Long.MIN_VALUE);
    }

    /**
     * Advances by {@code deltaSeconds}; on a refresh, recomputes the window
     * centred on world ({@code focusX}, {@code focusZ}). Texels newly in the
     * window take their value at once, the rest ease toward it.
     */
    public void update(CloudField field, WeatherState state, double focusX, double focusZ, float deltaSeconds) {
        sinceRefresh += Math.max(0f, deltaSeconds);
        if (sinceRefresh < REFRESH_SECONDS) return;
        float elapsed = Math.min(sinceRefresh, 60f);
        sinceRefresh = 0f;
        refresh(field, state, focusX, focusZ, 1f - (float) Math.exp(-elapsed / TRANSITION_SECONDS));
    }

    /** Recomputes at once with no easing (a new world, a teleport). */
    public void snap(CloudField field, WeatherState state, double focusX, double focusZ) {
        Arrays.fill(cell, Long.MIN_VALUE);
        refresh(field, state, focusX, focusZ, 1f);
        sinceRefresh = 0f;
    }

    private void refresh(CloudField field, WeatherState state, double focusX, double focusZ, float ease) {
        long ci0 = (long) Math.floor(focusX / TEXEL_BLOCKS) - SIZE / 2;
        long cj0 = (long) Math.floor(focusZ / TEXEL_BLOCKS) - SIZE / 2;
        double seconds = state.seconds();
        WeatherMode mode = state.mode();
        var zones = state.zones();
        for (long cj = cj0; cj < cj0 + SIZE; cj++) {
            for (long ci = ci0; ci < ci0 + SIZE; ci++) {
                int idx = (int) (Math.floorMod(cj, SIZE) * SIZE + Math.floorMod(ci, SIZE));
                long key = TerrainRelief.key(ci, cj);
                WeatherBias b = field.bias(mode, zones, (ci + 0.5) * TEXEL_BLOCKS, (cj + 0.5) * TEXEL_BLOCKS, seconds);
                float t = cell[idx] == key ? ease : 1f;
                cell[idx] = key;
                coverage[idx] += (b.coverage() - coverage[idx]) * t;
                wetness[idx] += (b.wetness() - wetness[idx]) * t;
            }
        }
        version++;
    }

    /** The bias at a world position, bilinear between texel centres as a filtered GPU lookup is. */
    public WeatherBias sample(double worldX, double worldZ) {
        double u = worldX / TEXEL_BLOCKS - 0.5, v = worldZ / TEXEL_BLOCKS - 0.5;
        long i0 = (long) Math.floor(u), j0 = (long) Math.floor(v);
        float fu = (float) (u - i0), fv = (float) (v - j0);
        int a = index(i0, j0), b = index(i0 + 1, j0), c = index(i0, j0 + 1), d = index(i0 + 1, j0 + 1);
        float w00 = (1 - fu) * (1 - fv), w10 = fu * (1 - fv), w01 = (1 - fu) * fv, w11 = fu * fv;
        return new WeatherBias(
                w00 * coverage[a] + w10 * coverage[b] + w01 * coverage[c] + w11 * coverage[d],
                w00 * wetness[a] + w10 * wetness[b] + w01 * wetness[c] + w11 * wetness[d]);
    }

    private static int index(long ci, long cj) {
        return (int) (Math.floorMod(cj, SIZE) * SIZE + Math.floorMod(ci, SIZE));
    }

    /** Coverage bias per texel, row-major by z (the texture's layout). Read on the render thread only. */
    public float[] coverage() {
        return coverage;
    }

    public float[] wetness() {
        return wetness;
    }

    /** Changes every refresh, so the GPU copy knows when to re-upload. */
    public int version() {
        return version;
    }
}
