package com.stonebreak.world.weather;

import com.stonebreak.world.generation.NoiseGenerator;

/**
 * The deterministic cloud field: every system that cares where clouds are (the
 * renderer, cloud shadows, rain, lightning) asks this. It is a pure function of
 * the world seed, a position and the weather clock, so the server only syncs the
 * clock and the {@link WeatherMode}, never cloud data, and every client agrees.
 *
 * <p>The field lives in <em>cloud space</em>: world space minus the wind drift.
 * Each {@link #CELL}-block cloud-space cell carries three bias-independent noise
 * channels ({@link #raw}); {@link #resolve} turns them into a {@link CloudColumn}
 * under the current {@link WeatherBias}. The renderer uploads the raw channels
 * and resolves on the GPU ({@code cloud_field.glsl}), so changing weather never
 * re-uploads the map — it only changes uniforms.</p>
 */
public final class CloudField {

    /** Horizontal size of a cloud cell, in blocks. */
    public static final int CELL = 12;
    /** Vertical quantum of cloud bases and tops, in blocks. */
    public static final int VOXEL = 4;

    /** Wind drift, blocks per second of weather time. */
    public static final double WIND_X = 0.6;
    public static final double WIND_Z = 0.15;

    /** Coverage noise above this (at zero bias) starts to cloud. */
    static final float COVERAGE_THRESHOLD = 0.58f;
    /** How far the bias moves the threshold: bias -1 lifts it out of reach, +1 overcasts. */
    static final float COVERAGE_BIAS_SPAN = 0.42f;
    /** Noise range over which a column fades from wisp to full density. */
    public static final float COVERAGE_EDGE = 0.14f;
    /**
     * Noise range over which a column grows to its full height. Wider than
     * {@link #COVERAGE_EDGE}, so a cloud domes up toward the middle of its noise
     * blob instead of every full-density cell reaching the same flat top. The
     * height goes with the square of the ramp, so only a cloud's core towers.
     */
    public static final float DOME_RAMP = 0.55f;
    /**
     * Wetness range past {@link CloudType#CUMULONIMBUS_MIN_WETNESS} over which a
     * storm grows from nimbostratus height to its full tower. Wetness is the
     * smooth large-scale channel, so towers come out broad, about as wide as
     * they are tall, instead of pillars over single cumulus blobs.
     */
    public static final float TOWER_RAMP = 0.12f;

    /**
     * Cumulus scale: individual clouds, a few hundred blocks across. Thresholded
     * fractal noise gives many small clouds and few large ones, like the
     * observed power law of cloud sizes (exponent about 1.7).
     */
    private static final float COVERAGE_SCALE = 1f / 14f;
    /**
     * Mesoscale organisation, about two kilometres: clouds come in fields with
     * clear stretches between them instead of an even scatter.
     */
    private static final float ORGANISATION_SCALE = 1f / 160f;
    /** Share of the organisation in the coverage channel; the rest is cumulus. */
    static final float ORGANISATION_WEIGHT = 0.5f;
    private static final float WETNESS_SCALE = 1f / 200f;
    /** Share of the cumulus noise in the wetness channel. */
    static final float WETNESS_CELL_WEIGHT = 0.3f;
    /** Smooth across cells, so tops vary gently instead of stepping per cell. */
    private static final float DETAIL_SCALE = 1f / 9f;
    /** Spreads the fractal noise (which clusters near 0) over most of [0, 1]. */
    private static final float NOISE_GAIN = 1.7f;

    /** AUTO weather periods, seconds of weather time. */
    private static final float AUTO_COVERAGE_PERIOD = 1500f;
    private static final float AUTO_WETNESS_PERIOD = 2300f;
    /*
     * AUTO tuning, calibrated by CloudFieldTest against mid-latitude land
     * climatology: about a third of the time clear, a third broken, a third
     * overcast; rain over a point about 10% of the time, thunderstorms 1-2%.
     */
    private static final float AUTO_COVERAGE_MEAN = 0.24f;
    private static final float AUTO_COVERAGE_SWING = 2.1f;
    private static final float AUTO_WETNESS_MEAN = -0.31f;
    private static final float AUTO_WETNESS_FOLLOW = 0.35f;
    private static final float AUTO_WETNESS_SWING = 0.22f;

    private final NoiseGenerator coverage;
    private final NoiseGenerator organisation;
    private final NoiseGenerator wetness;
    private final NoiseGenerator detail;
    private final NoiseGenerator climate;

    public CloudField(long worldSeed) {
        this.coverage = new NoiseGenerator(worldSeed ^ 0x434C4F5544L, 4, 0.5, 2.0);
        this.organisation = new NoiseGenerator(worldSeed ^ 0x4F5247L, 2, 0.5, 2.0);
        this.wetness = new NoiseGenerator(worldSeed ^ 0x5745545BL, 2, 0.5, 2.0);
        this.detail = new NoiseGenerator(worldSeed ^ 0x44455441L, 2, 0.5, 2.0);
        this.climate = new NoiseGenerator(worldSeed ^ 0x434C494DL, 2, 0.5, 2.0);
    }

    // --- Space -----------------------------------------------------------------

    public static double driftX(double weatherSeconds) {
        return WIND_X * weatherSeconds;
    }

    public static double driftZ(double weatherSeconds) {
        return WIND_Z * weatherSeconds;
    }

    /** The cloud-space cell above world column ({@code worldX}, {@code worldZ}). */
    public static int cellX(double worldX, double weatherSeconds) {
        return (int) Math.floor((worldX - driftX(weatherSeconds)) / CELL);
    }

    public static int cellZ(double worldZ, double weatherSeconds) {
        return (int) Math.floor((worldZ - driftZ(weatherSeconds)) / CELL);
    }

    // --- Raw channels ----------------------------------------------------------

    /**
     * The three noise channels of a cloud-space cell, quantised to bytes and
     * packed as {@code 0xAABBGGRR} (R = coverage, G = wetness, B = detail,
     * A = 255) — the exact bytes the GPU map holds, so CPU and GPU resolve the
     * same values.
     */
    public int raw(int cellX, int cellZ) {
        float cumulus = coverage.noise(cellX * COVERAGE_SCALE, cellZ * COVERAGE_SCALE);
        float fields = organisation.noise(cellX * ORGANISATION_SCALE, cellZ * ORGANISATION_SCALE);
        int c = toByte(ORGANISATION_WEIGHT * fields + (1f - ORGANISATION_WEIGHT) * cumulus);
        // Wetness follows the cumulus blobs a little, so storm towers rise as
        // separate cells out of a rain area instead of one solid deck.
        float wet = wetness.noise(cellX * WETNESS_SCALE, cellZ * WETNESS_SCALE);
        int w = toByte((1f - WETNESS_CELL_WEIGHT) * wet + WETNESS_CELL_WEIGHT * cumulus);
        int d = toByte(detail.noise(cellX * DETAIL_SCALE, cellZ * DETAIL_SCALE));
        return c | (w << 8) | (d << 16) | (0xFF << 24);
    }

    private static int toByte(float noise) {
        float v = 0.5f + 0.5f * noise * NOISE_GAIN;
        return Math.round(Math.clamp(v, 0f, 1f) * 255f);
    }

    // --- Resolution ------------------------------------------------------------

    /** Coverage noise at which a column starts to cloud under {@code bias}. */
    public static float coverageThreshold(WeatherBias bias) {
        return COVERAGE_THRESHOLD - COVERAGE_BIAS_SPAN * bias.coverage();
    }

    /**
     * Resolves a packed {@link #raw} cell under {@code bias}. Mirrored by
     * {@code cloudResolve} in {@code shaders/clouds/cloud_field.glsl} — change both together.
     */
    public static CloudColumn resolve(int raw, WeatherBias bias) {
        float c = (raw & 0xFF) / 255f;
        float w = ((raw >>> 8) & 0xFF) / 255f;
        float d = ((raw >>> 16) & 0xFF) / 255f;

        float t = coverageThreshold(bias);
        float density = smoothstep(t, t + COVERAGE_EDGE, c);
        if (density <= 0f) return CloudColumn.CLEAR;

        float wet = w + bias.wetness();
        CloudType type = CloudType.forWetness(wet);
        float height = type.maxHeight;
        if (type.storm) {
            float floor = CloudType.NIMBOSTRATUS.maxHeight;
            float min = CloudType.CUMULONIMBUS_MIN_WETNESS;
            height = floor + (height - floor) * smoothstep(min, min + TOWER_RAMP, wet);
        }
        float dome = smoothstep(t, t + DOME_RAMP, c);
        int voxels = Math.max(1, (int) Math.floor(
                height / VOXEL * dome * dome * (0.75f + 0.25f * d) + 0.5f));
        return new CloudColumn(density, type, type.baseY, type.baseY + voxels * VOXEL);
    }

    /** The column above world column ({@code worldX}, {@code worldZ}). */
    public CloudColumn columnAt(double worldX, double worldZ, double weatherSeconds, WeatherBias bias) {
        return resolve(raw(cellX(worldX, weatherSeconds), cellZ(worldZ, weatherSeconds)), bias);
    }

    /**
     * The bias {@code mode} applies at {@code weatherSeconds}. AUTO wanders
     * slowly and deterministically, so every client agrees without extra sync.
     */
    public WeatherBias bias(WeatherMode mode, double weatherSeconds) {
        if (mode != WeatherMode.AUTO) return mode.fixedBias();
        float tc = (float) (weatherSeconds / AUTO_COVERAGE_PERIOD);
        float tw = (float) (weatherSeconds / AUTO_WETNESS_PERIOD);
        float cov = Math.clamp(AUTO_COVERAGE_MEAN + AUTO_COVERAGE_SWING * climate.noise(tc, 17.3f), -1f, 1f);
        // Rain comes with cloud: the wetter spells are the overcast ones.
        float wet = Math.clamp(AUTO_WETNESS_MEAN + AUTO_WETNESS_FOLLOW * cov
                + AUTO_WETNESS_SWING * climate.noise(41.9f, tw), -0.5f, 0.5f);
        return new WeatherBias(cov, wet);
    }

    static float smoothstep(float e0, float e1, float x) {
        float t = Math.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
