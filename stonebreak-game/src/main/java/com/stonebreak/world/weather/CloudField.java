package com.stonebreak.world.weather;

import com.stonebreak.world.generation.NoiseGenerator;

import java.util.List;

/**
 * The deterministic cloud field: every system that cares where clouds are (the
 * renderer, cloud shadows, rain, lightning) asks this. It is a pure function of
 * the world seed, a position and the weather clock, so the server only syncs the
 * clock and the {@link WeatherMode}, never cloud data, and every client agrees.
 *
 * <p>The field lives in <em>cloud space</em>: world space minus the wind drift.
 * Each {@link #CELL}-block cloud-space cell carries four bias-independent noise
 * channels ({@link #raw}); {@link #sample} filters them smoothly between cells
 * (as the GPU does), and {@link #resolve} turns them into a {@link CloudColumn}
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
    public static final float COVERAGE_BIAS_SPAN = 0.42f;
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
    /** Share of the organisation in the coverage of an unorganised sky; the rest is cumulus. */
    public static final float ORGANISATION_WEIGHT = 0.5f;
    /**
     * Share of the organisation in the coverage of a fully organised system
     * ({@link #organised}): the cumulus noise only frays its edges, so the
     * clouds inside merge into one mass instead of a scatter of cells.
     * Applied only where it adds cover (see {@code coverage}).
     */
    public static final float ORGANISED_WEIGHT = 0.75f;
    /** Organisation channel over which a system goes from scattered cells to one mass. */
    public static final float ORGANISED_LO = 0.5f;
    public static final float ORGANISED_HI = 0.75f;
    /**
     * How far a fair-weather system organises, against a storm system's 1:
     * cumulus fields clump into broad masses, but only storms fuse into one
     * supercell.
     */
    public static final float FAIR_ORGANISATION = 0.25f;
    /** Coverage and wetness an organised storm gains: its merged core grows to full height. */
    public static final float ORGANISED_COVERAGE_LIFT = 0.12f;
    public static final float ORGANISED_WETNESS_LIFT = 0.1f;
    private static final float WETNESS_SCALE = 1f / 200f;
    /** Share of the cumulus noise in the wetness channel. */
    public static final float WETNESS_CELL_WEIGHT = 0.3f;
    /** Smooth across cells, so tops vary gently instead of stepping per cell. */
    private static final float DETAIL_SCALE = 1f / 9f;
    /** Spreads the fractal noise (which clusters near 0) over most of [0, 1]. */
    private static final float NOISE_GAIN = 1.7f;

    /** AUTO weather periods, seconds of weather time. */
    private static final float AUTO_COVERAGE_PERIOD = 1500f;
    /**
     * Scale of AUTO weather systems, blocks: one system is a few kilometres
     * across, so the cloud view (about 5.5 km) often holds the edge of one.
     * Smaller and every view would be a patchwork, and clear or overcast skies
     * (whole views) would grow rare — CloudFieldTest's climatology pins it.
     */
    static final float REGION_BLOCKS = 8000f;
    private static final float AUTO_WETNESS_PERIOD = 2300f;
    /*
     * AUTO tuning, calibrated by CloudFieldTest against mid-latitude land
     * climatology: about a third of the time clear, a third broken, a third
     * overcast; rain over a point about 10% of the time, thunderstorms 1-2%.
     */
    private static final float AUTO_COVERAGE_MEAN = 0.3f;
    private static final float AUTO_COVERAGE_SWING = 5.0f;
    private static final float AUTO_WETNESS_MEAN = -0.39f;
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
     * The channels of a cloud field position, each 0..1. They are the separate
     * noises, not yet mixed: {@link #resolve} mixes them by how organised the
     * system is, which depends on the weather.
     *
     * @param cumulus      individual clouds, a few hundred blocks across
     * @param wetness      large-scale moisture: which type the clouds are
     * @param detail       gentle variation of the tops
     * @param organisation mesoscale organisation: smooth over kilometres, so
     *                     {@link #mass} never changes sharply between neighbours
     */
    public record Channels(float cumulus, float wetness, float detail, float organisation) {}

    /**
     * The noise channels of a cloud-space cell, quantised to bytes and packed
     * as {@code 0xAABBGGRR} (R = cumulus, G = wetness, B = detail,
     * A = organisation; see {@link Channels}) — the exact bytes the GPU map
     * holds, so CPU and GPU resolve the same values.
     */
    public int raw(int cellX, int cellZ) {
        int c = toByte(coverage.noise(cellX * COVERAGE_SCALE, cellZ * COVERAGE_SCALE));
        int w = toByte(wetness.noise(cellX * WETNESS_SCALE, cellZ * WETNESS_SCALE));
        int d = toByte(detail.noise(cellX * DETAIL_SCALE, cellZ * DETAIL_SCALE));
        int m = toByte(organisation.noise(cellX * ORGANISATION_SCALE, cellZ * ORGANISATION_SCALE));
        return c | (w << 8) | (d << 16) | (m << 24);
    }

    /** A packed {@link #raw} cell as channels. */
    public static Channels unpack(int raw) {
        return new Channels((raw & 0xFF) / 255f, ((raw >>> 8) & 0xFF) / 255f,
                ((raw >>> 16) & 0xFF) / 255f, ((raw >>> 24) & 0xFF) / 255f);
    }

    /**
     * The channels at cloud-space position ({@code cloudX}, {@code cloudZ}),
     * blocks, through a cubic B-spline over the cells: smooth in value and
     * slope, so nothing follows the cell grid. Mirrored by
     * {@code cloudMapCubic} in cloud_field.glsl.
     */
    public Channels sample(double cloudX, double cloudZ) {
        double u = cloudX / CELL - 0.5, v = cloudZ / CELL - 0.5;
        long i0 = (long) Math.floor(u), j0 = (long) Math.floor(v);
        float[] wu = bspline((float) (u - i0)), wv = bspline((float) (v - j0));
        float c = 0f, w = 0f, d = 0f, m = 0f;
        for (int b = 0; b < 4; b++) {
            for (int a = 0; a < 4; a++) {
                float k = wu[a] * wv[b];
                int raw = raw((int) (i0 - 1 + a), (int) (j0 - 1 + b));
                c += k * (raw & 0xFF);
                w += k * ((raw >>> 8) & 0xFF);
                d += k * ((raw >>> 16) & 0xFF);
                m += k * ((raw >>> 24) & 0xFF);
            }
        }
        return new Channels(c / 255f, w / 255f, d / 255f, m / 255f);
    }

    /** Cubic B-spline weights of the four cells around fraction {@code f}. */
    private static float[] bspline(float f) {
        float f2 = f * f, f3 = f2 * f;
        return new float[]{
                (1f - 3f * f + 3f * f2 - f3) / 6f,
                (4f - 6f * f2 + 3f * f3) / 6f,
                (1f + 3f * f + 3f * f2 - 3f * f3) / 6f,
                f3 / 6f};
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

    /** Resolves a packed {@link #raw} cell under {@code bias}. */
    public static CloudColumn resolve(int raw, WeatherBias bias) {
        return resolve(unpack(raw), bias);
    }

    /**
     * How far the cloud system here has organised into one mass under
     * {@code bias}, 0..1. Where the organisation channel is high the clouds
     * merge: a fair-weather field clumps into broad cloud masses
     * ({@link #FAIR_ORGANISATION}), and a storm system fuses its towers into
     * one supercell. Elsewhere storms stay scattered cells. Reads only the
     * smooth channels, so a whole system organises together. Mirrored inside
     * {@code cloudResolve} in cloud_field.glsl.
     */
    public static float organised(Channels ch, WeatherBias bias) {
        return smoothstep(ORGANISED_LO, ORGANISED_HI, ch.organisation())
                * (FAIR_ORGANISATION + (1f - FAIR_ORGANISATION) * storminess(ch, bias));
    }

    /** How far the large-scale moisture here makes a storm system, 0..1: none below rain, full at storm. */
    private static float storminess(Channels ch, WeatherBias bias) {
        return smoothstep(CloudType.NIMBOSTRATUS_MIN_WETNESS, CloudType.CUMULONIMBUS_MIN_WETNESS,
                ch.wetness() + bias.wetness());
    }

    /**
     * How far the system here is an organised storm, 0..1: the share of
     * {@link #organised} that comes from storminess, which lifts its cover and
     * moisture so the merged core grows into one full tower.
     */
    private static float organisedStorm(Channels ch, WeatherBias bias) {
        return smoothstep(ORGANISED_LO, ORGANISED_HI, ch.organisation()) * storminess(ch, bias);
    }

    /**
     * Coverage noise of a system organised by {@code organised}, for cumulus
     * noise {@code cumulus}. Scattered cells lean on the cumulus; organising
     * leans on the smooth organisation instead, but only ever fills in: it
     * merges the clouds of a system into one mass and never clears a cloud the
     * cumulus already grew.
     */
    private static float coverage(float cumulus, float organisation, float organised) {
        float scattered = ORGANISATION_WEIGHT * organisation + (1f - ORGANISATION_WEIGHT) * cumulus;
        float merged = ORGANISED_WEIGHT * organisation + (1f - ORGANISED_WEIGHT) * cumulus;
        return scattered + organised * Math.max(0f, merged - scattered);
    }

    /**
     * Resolves channels under {@code bias}. Mirrored by {@code cloudResolve}
     * in {@code shaders/clouds/cloud_field.glsl} — change both together.
     */
    public static CloudColumn resolve(Channels ch, WeatherBias bias) {
        float org = organised(ch, bias);
        float storm = organisedStorm(ch, bias);
        float c = coverage(ch.cumulus(), ch.organisation(), org) + ORGANISED_COVERAGE_LIFT * storm;

        float t = coverageThreshold(bias);
        float density = smoothstep(t, t + COVERAGE_EDGE, c);
        if (density <= 0f) return CloudColumn.CLEAR;

        // Scattered storms follow the cumulus blobs a little, so their towers
        // rise as separate cells out of a rain area; an organised system is one
        // type throughout, and an organised storm one tower.
        float cellular = (1f - WETNESS_CELL_WEIGHT) * ch.wetness() + WETNESS_CELL_WEIGHT * ch.cumulus();
        float wet = cellular + (ch.wetness() - cellular) * org + ORGANISED_WETNESS_LIFT * storm + bias.wetness();
        CloudType type = CloudType.forWetness(wet);
        float height = type.maxHeight;
        if (type.storm) {
            float floor = CloudType.NIMBOSTRATUS.maxHeight;
            float min = CloudType.CUMULONIMBUS_MIN_WETNESS;
            height = floor + (height - floor) * smoothstep(min, min + TOWER_RAMP, wet);
        }
        float dome = smoothstep(t, t + DOME_RAMP, c);
        float build = dome * dome * (0.75f + 0.25f * ch.detail());
        int voxels = Math.max(1, (int) Math.floor(height / VOXEL * build + 0.5f));
        return new CloudColumn(density, type, type.baseY, type.baseY + voxels * VOXEL, build, wet);
    }

    /**
     * How much cloud the system here has clumped up under {@code bias}, 0..1:
     * the dome {@link #resolve} builds height from, for a cloud of average
     * cumulus noise in an unorganised system. It reads only the organisation,
     * so it is smooth over kilometres: a whole cloud mass decides together
     * whether it rides over a mountain, rather than its core going over while
     * its fringe is steered aside (that tore clouds apart). Organising is left
     * out on purpose: it changes faster across the sky than the steering may.
     * Mirrored by {@code cloudMass} in cloud_field.glsl.
     */
    public static float mass(Channels ch, WeatherBias bias) {
        float t = coverageThreshold(bias);
        // Cumulus noise averages 0.5.
        float c = coverage(0.5f, ch.organisation(), 0f);
        return smoothstep(t, t + DOME_RAMP, c);
    }

    /** Cloud-space X of world X at {@code weatherSeconds}. */
    public static double cloudX(double worldX, double weatherSeconds) {
        return worldX - driftX(weatherSeconds);
    }

    public static double cloudZ(double worldZ, double weatherSeconds) {
        return worldZ - driftZ(weatherSeconds);
    }

    /** The column above world position ({@code worldX}, {@code worldZ}), smoothly filtered, terrain aside. */
    public CloudColumn columnAt(double worldX, double worldZ, double weatherSeconds, WeatherBias bias) {
        return resolve(sample(cloudX(worldX, weatherSeconds), cloudZ(worldZ, weatherSeconds)), bias);
    }

    /**
     * The bias {@code mode} applies to world column ({@code worldX}, {@code worldZ})
     * at {@code weatherSeconds}. AUTO is regional: weather systems about
     * {@link #REGION_BLOCKS} across, fixed in cloud space so they ride the wind
     * with their clouds, that grow and fade over time. Deterministic, so every
     * client agrees without extra sync.
     */
    public WeatherBias bias(WeatherMode mode, double worldX, double worldZ, double weatherSeconds) {
        if (mode != WeatherMode.AUTO) return mode.fixedBias();
        float rx = (float) ((worldX - driftX(weatherSeconds)) / REGION_BLOCKS);
        float rz = (float) ((worldZ - driftZ(weatherSeconds)) / REGION_BLOCKS);
        float tc = (float) (weatherSeconds / AUTO_COVERAGE_PERIOD);
        float tw = (float) (weatherSeconds / AUTO_WETNESS_PERIOD);
        // Fractional offsets keep the axes off the simplex lattice planes, where
        // the noise is muted (a line through one swung half as far).
        float cov = Math.clamp(AUTO_COVERAGE_MEAN + AUTO_COVERAGE_SWING * climate.noise3D(rx + 0.31f, rz + 0.67f, tc), -1f, 1f);
        // Rain comes with cloud: the wetter systems are the overcast ones.
        float wet = Math.clamp(AUTO_WETNESS_MEAN + AUTO_WETNESS_FOLLOW * cov
                + AUTO_WETNESS_SWING * climate.noise3D(rz + 41.9f, tw, rx + 0.53f), -0.5f, 0.5f);
        return new WeatherBias(cov, wet);
    }

    /**
     * The weather at a world column: the world's {@code mode}, with each zone
     * that reaches it blended over on top.
     */
    public WeatherBias bias(WeatherMode mode, List<WeatherZone> zones,
                            double worldX, double worldZ, double weatherSeconds) {
        WeatherBias b = bias(mode, worldX, worldZ, weatherSeconds);
        for (WeatherZone zone : zones) {
            float w = zone.weight(worldX, worldZ);
            if (w >= 1f) b = bias(zone.mode(), worldX, worldZ, weatherSeconds);
            else if (w > 0f) b = b.lerp(bias(zone.mode(), worldX, worldZ, weatherSeconds), w);
        }
        return b;
    }

    public static float smoothstep(float e0, float e1, float x) {
        float t = Math.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }
}
