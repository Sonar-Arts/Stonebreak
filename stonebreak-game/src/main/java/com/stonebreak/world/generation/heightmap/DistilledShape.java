package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.StandardShapeVersion;
import com.stonebreak.world.generation.StandardTerrain;
import com.stonebreak.world.generation.noise.NoiseChannel2D;
import com.stonebreak.world.generation.noise.TerrainNoise;

/**
 * The Standard generator's terrain shape, distilled from DaedalusTGM-Exp (2026-11-01).
 *
 * <p>Structure: DaedalusTGM-Exp's own procedural control layer
 * ({@code terrain_slm/world/generator.py procedural_controls_torch}) in block coordinates —
 * continents with a home bump around spawn, mountain ranges along the zero lines of a
 * domain-warped field (spine, foothills, spurs inside the range body, height rising and falling
 * along the chain), lowland hill provinces — plus a roughness-scaled stand-in for what the model's
 * learned stages add (V valleys on the zero lines of an fbm, fine texture), a summit soft cap,
 * and the model's metres-to-blocks curve ({@link HeightCurveTable}).
 *
 * <p>Numbers: {@link #FITTED}, fitted by {@code terrain_slm.eval.distill_fit} against an unbiased
 * DaedalusTGM-Exp v4 sample (24 seeds, coarse + fine tiles) on the game's real FastNoise2,
 * minimising the distance of land share, hypsometry, summits, band spectra, chain shape,
 * coastline, slopes, relief, skew and cliffs ({@code distill_stats.distance}).
 *
 * <p>Port arithmetic: only + - * /, sqrt, abs, min/max, floor, so the native worldgen library
 * ({@code terrain_ctx.hpp DistilledShape}, built -ffp-contract=off) reproduces every height bit
 * for bit from {@link #nativeConstants()} and {@link HeightCurveTable#table()}. Domain warp is
 * evaluated on a globally anchored {@link #LATTICE}-block lattice and cubic-B-spline
 * interpolated, so a column is a pure function of its position whichever request asks.
 */
public final class DistilledShape {

    /** Warp lattice spacing, blocks. */
    public static final int LATTICE = 16;

    // Channel order (shared with the native context): seed offset, octaves, period (blocks).
    public static final int CONT = 0, WARP_X = 1, WARP_Z = 2, RANGE = 3, SPUR = 4, ALONG = 5,
        HILLS = 6, HILLY = 7, VALLEY = 8, TEXTURE = 9, DETAIL = 10, CHANNELS = 11;
    private static final int[] SEED_OFFSET = {2, 21, 22, 8, 23, 24, 25, 26, 27, 28, 3};
    private static final int[] OCTAVES = {4, 2, 2, 2, 2, 2, 4, 3, 4, 4, 3};
    /** Standard shape 2 channels (native {@code Generator::shape2}): province, rolling, mesa, style, tributaries. */
    public static final int PROV = 0, ROLL = 1, MESA = 2, STYLE = 3, TRIB = 4, CHANNELS2 = 5;
    private static final int[] SEED_OFFSET2 = {29, 30, 31, 32, 33};
    private static final int[] OCTAVES2 = {3, 2, 2, 2, 3};
    /** Inland sub-sea columns (continent field at least this) are lakes in shape 2 (native PIT_LAND). */
    public static final double PIT_LAND = 0.999;

    /** Std of FastNoise2 simplex fbm (gain 0.5, lacunarity 2) for 2 / 3 / 4 octaves (distill_fit.FBM_STD). */
    static final double FBM_STD2 = 0.405, FBM_STD3 = 0.3555, FBM_STD4 = 0.3337;

    /** The knobs; field names match {@code distill_fit.Params}. */
    public record Params(
        double contPeriod, double contOffset, double contWidth, double homeRadius, double homeAmp,
        double rangePeriod, double warpPeriod, double warpAmp, double spineWidth, double spurPeriod,
        double alongPeriod, double alongBias, double spineM, double spurM, double footM,
        double hillsPeriod, double hillyPeriod, double hillBias, double hillSoftness,
        double hillPlainsM, double hillCountryM, double landBaseM, double seaFloorM,
        double roughFloor, double roughHill, double valleyPeriod, double valleyM, double valleyPower,
        double texturePeriod, double textureM, double textureFloorM, double detailBlocks,
        double peakKneeM, double peakSpanM,
        // Standard shape 2 (2026-11-19): open ocean shelf / abyss, lowland landform provinces, dendritic tributaries
        double shelfWidth, double abyssWidth, double abyssM, double provPeriod, double provBias,
        double rollPeriod, double rollM, double mesaPeriod, double mesaLevel, double mesaM,
        double stylePeriod, double styleBias, double tribPeriod, double tribM, double tribReach, double provRough) {

        /** The 2026-11-01 knobs (shape 1): the shape-2 fields unused. */
        Params(double contPeriod, double contOffset, double contWidth, double homeRadius, double homeAmp,
               double rangePeriod, double warpPeriod, double warpAmp, double spineWidth, double spurPeriod,
               double alongPeriod, double alongBias, double spineM, double spurM, double footM,
               double hillsPeriod, double hillyPeriod, double hillBias, double hillSoftness,
               double hillPlainsM, double hillCountryM, double landBaseM, double seaFloorM,
               double roughFloor, double roughHill, double valleyPeriod, double valleyM, double valleyPower,
               double texturePeriod, double textureM, double textureFloorM, double detailBlocks,
               double peakKneeM, double peakSpanM) {
            this(contPeriod, contOffset, contWidth, homeRadius, homeAmp, rangePeriod, warpPeriod, warpAmp, spineWidth,
                spurPeriod, alongPeriod, alongBias, spineM, spurM, footM, hillsPeriod, hillyPeriod, hillBias,
                hillSoftness, hillPlainsM, hillCountryM, landBaseM, seaFloorM, roughFloor, roughHill, valleyPeriod,
                valleyM, valleyPower, texturePeriod, textureM, textureFloorM, detailBlocks, peakKneeM, peakSpanM,
                0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0);
        }

        double period2(int channel) {
            return switch (channel) {
                case PROV -> provPeriod;
                case ROLL -> rollPeriod;
                case MESA -> mesaPeriod;
                case STYLE -> stylePeriod;
                default -> tribPeriod;
            };
        }

        double period(int channel) {
            return switch (channel) {
                case CONT -> contPeriod;
                case WARP_X, WARP_Z -> warpPeriod;
                case RANGE -> rangePeriod;
                case SPUR -> spurPeriod;
                case ALONG -> alongPeriod;
                case HILLS -> hillsPeriod;
                case HILLY -> hillyPeriod;
                case VALLEY -> valleyPeriod;
                case TEXTURE -> texturePeriod;
                case DETAIL -> 45.0;
                default -> throw new IllegalArgumentException("channel " + channel);
            };
        }

        /** valley exponent snapped to {0.5, 1, 1.5, 2}, as the fit evaluates it. */
        double valleyExponent() {
            double best = 0.5;
            for (double c : new double[]{1.0, 1.5, 2.0}) {
                if (Math.abs(c - valleyPower) < Math.abs(best - valleyPower)) {
                    best = c;
                }
            }
            return best;
        }
    }

    // BEGIN FITTED (generated by terrain_slm.eval.distill_export from fit_v2/best.json)
    public static final Params FITTED = new Params(
        6894.989622808905, 0.34463361352951966, 0.09859857325686658, 600.0, 0.7,
        3944.511100992684, 1821.7527333238593, 404.5148080216746, 0.5310144938793813, 577.2424211879252,
        4344.064076674278, 0.011431446131307199, 2348.41250626434, 1911.1343613301422, 172.30188545142178,
        142.39095239499332, 1827.960213884483, -0.4538265830406665, 4.600349343005898, 161.1449691547196,
        399.1323760057598, 587.0916674781482, -238.16724380150708, 0.053105921091504305, 0.9401180978049882,
        451.3265503952944, 508.73099538006727, 2.0, 18.47190652270889, 123.11199426403962,
        1.651965331080935, 0.7185657206192777, 4134.428426652372, 835.096478721458);
    // END FITTED

    // BEGIN FITTED_V2 (generated by terrain_slm.eval.distill_export from sd/final_v2.json)
    public static final Params FITTED_V2 = new Params(
        2976.5022133332095, 0.2537497923775508, 0.17057381705500238, 600.0, 0.7,
        4549.300084941332, 689.3841629345459, 470.7615793504769, 0.7634902366691082, 735.381978845092,
        2631.4857294771236, 0.05849088248366707, 2312.615172741152, 2029.5336250207358, 118.34590008262866,
        714.9684588762541, 1830.829695608029, -0.44807938310772777, 1.03974198690706, 97.29792255593588,
        585.225387721916, 669.9239625236764, -147.12777803862218, 0.011635812433122578, 0.922346100394017,
        423.8569177521512, 717.4374379394668, 2.0, 19.030731561417234, 133.53646456203128,
        9.317393269358739, 0.0385418264584745, 4122.165575172685, 831.8761973935764, 0.09216620550162948,
        0.2830373215129432, -802.9793054435004, 1646.5575868136636, -0.273252332597429, 122.72936228428608,
        149.20274452098775, 351.834085950318, 0.4357415369943749, 114.71945645690859, 4447.476884937562,
        0.018541867753971708, 146.5543278419213, 6.683036966735735, 0.6487380944733836, 0.2624665372583039);
    // END FITTED_V2

    /** Per-column result of {@link #column}; reused by batched callers. */
    public static final class Column {
        /** Final surface height (ground occupies y &lt; height), clamped to [1, WORLD_HEIGHT - 1]. */
        public int height;
        /** Height without the block-space detail fuzz (biome temperature chill). */
        public int shapedHeight;
        /** Elevation, metres, before the curve. */
        public double elevationM;
        /** Land weight, 0 = open ocean in the controls, 1 = continent. */
        public double land;
        /** Mountain-range body, along-chain weighted, in [0, 1]. */
        public double mountain;
        /** Hill-country province weight in [0, 1]. */
        public double hilly;
        /** Roughness in [0, 1] (land-masked). */
        public double rough;
        /** Shape 2: an inland sub-sea column (a lake: the land's biome, a lake bed). */
        public boolean lake;
    }

    private final Params p;
    private final boolean v2;
    private final NoiseChannel2D[] channel = new NoiseChannel2D[CHANNELS];
    private final NoiseChannel2D[] channel2 = new NoiseChannel2D[CHANNELS2];
    private final int[] channelSeed = new int[CHANNELS];
    private final double valleyExponent;
    private final Lattice lattice = new Lattice();

    public DistilledShape(long seed) {
        this(seed, FITTED);
    }

    public DistilledShape(long seed, Params p) {
        this(seed, p, false);
    }

    /** The shape of a world created with {@code version} (null = {@link StandardShapeVersion#CURRENT}). */
    public DistilledShape(long seed, StandardShapeVersion version) {
        this(seed, StandardShapeVersion.orCurrent(version) == StandardShapeVersion.V1 ? FITTED : FITTED_V2,
            StandardShapeVersion.orCurrent(version) != StandardShapeVersion.V1);
    }

    private DistilledShape(long seed, Params p, boolean v2) {
        this.p = p;
        this.v2 = v2;
        this.valleyExponent = p.valleyExponent();
        for (int c = 0; c < CHANNELS; c++) {
            long s = seed + SEED_OFFSET[c];
            channelSeed[c] = TerrainNoise.nativeSeed(s);
            channel[c] = TerrainNoise.channel2D(s, OCTAVES[c], 0.5, 2.0, frequency(p, c), 0f, 0f, 0, 0);
        }
        if (v2) {
            for (int c = 0; c < CHANNELS2; c++) {
                channel2[c] = TerrainNoise.channel2D(seed + SEED_OFFSET2[c], OCTAVES2[c], 0.5, 2.0,
                    (float) (1.0 / p.period2(c)), 0f, 0f, 0, 0);
            }
        }
    }

    /** True for a Standard shape 2 world's shape. */
    public boolean isV2() {
        return v2;
    }

    static float frequency(Params p, int channel) {
        return (float) (1.0 / p.period(channel));
    }

    public Params params() {
        return p;
    }

    // ------------------------------------------------------------------------------- sampling

    /** One column, everything sampled per point (lattice nodes cached). */
    public void column(int x, int z, Column out) {
        float[] w2 = v2 ? new float[CHANNELS2] : null;
        if (v2) {
            for (int c = 0; c < CHANNELS2; c++) {
                w2[c] = channel2[c].sample(x, z);
            }
        }
        evaluate(x, z,
            channel[CONT].sample(x, z), channel[ALONG].sample(x, z), channel[HILLS].sample(x, z),
            channel[HILLY].sample(x, z), channel[VALLEY].sample(x, z), channel[TEXTURE].sample(x, z),
            channel[DETAIL].sample(x, z), w2, out);
    }

    public int height(int x, int z) {
        Column c = new Column();
        column(x, z, c);
        return c.height;
    }

    /**
     * Batched columns at {@code (baseX + ix*stride, baseZ + iz*stride)}, {@code out[ix*countZ + iz]}.
     * Direct channels are grid-filled (one SIMD call each), so values equal {@link #column} exactly.
     */
    public void fill(int baseX, int baseZ, int countX, int countZ, int stride, Column[] out) {
        int n = countX * countZ;
        float[][] g = new float[CHANNELS][];
        for (int c : new int[]{CONT, ALONG, HILLS, HILLY, VALLEY, TEXTURE, DETAIL}) {
            g[c] = new float[n];
            channel[c].fill(g[c], baseX, baseZ, countX, countZ, stride);
        }
        float[][] g2 = new float[CHANNELS2][];
        if (v2) {
            for (int c = 0; c < CHANNELS2; c++) {
                g2[c] = new float[n];
                channel2[c].fill(g2[c], baseX, baseZ, countX, countZ, stride);
            }
        }
        float[] w2 = v2 ? new float[CHANNELS2] : null;
        for (int ix = 0; ix < countX; ix++) {
            for (int iz = 0; iz < countZ; iz++) {
                int i = ix * countZ + iz;
                if (out[i] == null) {
                    out[i] = new Column();
                }
                if (v2) {
                    for (int c = 0; c < CHANNELS2; c++) {
                        w2[c] = g2[c][i];
                    }
                }
                evaluate(baseX + ix * stride, baseZ + iz * stride, g[CONT][i], g[ALONG][i], g[HILLS][i],
                    g[HILLY][i], g[VALLEY][i], g[TEXTURE][i], g[DETAIL][i], w2, out[i]);
            }
        }
    }

    /** The single per-column formula (mirrored line for line by terrain_ctx.hpp). */
    private void evaluate(int x, int z, float contRaw, float alongRaw, float hillsRaw, float hillyRaw,
                          float valleyRaw, float textureRaw, float detailRaw, float[] w2, Column out) {
        double xd = x, zd = z;
        double home = bell((xd * xd + zd * zd) / (2.0 * p.homeRadius() * p.homeRadius()));
        double cont = contRaw + p.homeAmp() * home;
        double land = smoothstep((cont + p.contOffset()) / p.contWidth());

        double[] fields = lattice.interpolate(x, z);
        double nRange = fields[0] / FBM_STD2;
        double nSpur = fields[1] / FBM_STD2;
        double w = p.spineWidth();
        double spine = bell(sq(nRange / w));
        double foot = bell(sq(nRange / (2.4 * w)));
        double body = bell(sq(nRange / (1.6 * w)));
        double spur = bell(sq(nSpur / 0.6)) * body;
        double along = smoothstep((alongRaw / FBM_STD2 + p.alongBias()) / 1.2);
        double hills = hillsRaw;
        double hilly = smoothstep((hillyRaw / FBM_STD3 + p.hillBias()) / p.hillSoftness());
        hilly = Math.max(hilly, 0.55 * home);

        double mountains = along * (p.spineM() * spine + p.spurM() * spur + p.footM() * foot);
        double hillM = p.hillPlainsM() + (p.hillCountryM() - p.hillPlainsM()) * hilly + 200.0 * along * foot;
        double trend = p.seaFloorM() + (p.landBaseM() - p.seaFloorM()) * land * (0.6 + 0.5 * cont)
            + land * (mountains + hillM * hills);
        double provLow = 0.0;
        if (v2) {
            double deep = smoothstep((-(cont + p.contOffset()) - p.shelfWidth()) / p.abyssWidth());
            trend = trend + (1.0 - land) * p.abyssM() * deep;
            double prov = smoothstep(((double) w2[PROV] / FBM_STD3 + p.provBias()) / 1.2);
            double lowland = land * (1.0 - along * body) * prov;
            provLow = (1.0 - along * body) * prov;
            double roll = (double) w2[ROLL] / FBM_STD2;
            double mesa = smoothstep(((double) w2[MESA] / FBM_STD2 - p.mesaLevel()) / 0.35);
            double style = smoothstep(((double) w2[STYLE] / FBM_STD2 + p.styleBias()) / 0.8);
            trend = trend + lowland * (p.rollM() * roll + style * p.mesaM() * (mesa - 0.5));
        }
        double roughIn = p.roughFloor() + p.roughHill() * hilly
            + along * (0.9 * spine + 0.45 * spur + 0.25 * foot)
            + 0.25 * (0.3 + along * foot) * Math.abs(hills);
        if (v2) {
            roughIn = roughIn + p.provRough() * provLow;
        }
        double rough = clamp01(roughIn) * land;
        double a = Math.abs(valleyRaw) / (2.0 * FBM_STD4);
        double valleys = p.valleyM() * rough * (valleyShape(a, valleyExponent) - 0.5);
        if (v2) {
            double b = Math.abs((double) w2[TRIB]) / (2.0 * FBM_STD3);
            double near = bell(a * a / p.tribReach());
            valleys = valleys + p.tribM() * rough * near * (valleyShape(b, valleyExponent) - 0.5);
        }
        double tex = (p.textureFloorM() + (p.textureM() - p.textureFloorM()) * rough) * textureRaw;
        double e = trend + valleys + tex;
        double over = Math.max(e - p.peakKneeM(), 0.0);
        e = e - over + over / (1.0 + over / p.peakSpanM());

        double blocks = v2 ? HeightCurveTable.blocksV2(e) : HeightCurveTable.blocks(e);
        out.elevationM = e;
        out.shapedHeight = clampHeight(Math.floor(blocks));
        out.height = clampHeight(Math.floor(blocks + p.detailBlocks() * detailRaw));
        out.lake = false;
        if (v2) {
            // inland sub-sea pits are lakes; one-block puddles fill in (native evaluateColumn)
            int sea = StandardTerrain.SEA_LEVEL;
            out.lake = out.height < sea && land >= PIT_LAND;
            if (out.lake && out.height == sea - 1) {
                out.height = sea;
                out.lake = false;
            }
        }
        out.land = land;
        out.mountain = along * body;
        out.hilly = hilly;
        out.rough = rough;
    }

    // ------------------------------------------------------------------------------- warp lattice

    /**
     * Warped range/spur fields at lattice nodes {@code (li*LATTICE, lj*LATTICE)}: the node's own
     * warp offset moves the sample point, the fields are read there with single fractional
     * samples. Direct-mapped, lock-free cache of immutable nodes (a racing write only recomputes).
     */
    private final class Lattice {
        private static final int BITS = 15;
        private final Node[] cache = new Node[1 << BITS];

        private record Node(long key, float range, float spur) {
        }

        private Node node(int li, int lj) {
            long key = ((long) li << 32) ^ (lj & 0xFFFFFFFFL);
            int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - BITS));
            Node n = cache[slot];
            if (n != null && n.key == key) {
                return n;
            }
            int gx = li * LATTICE, gz = lj * LATTICE;
            float wx = channel[WARP_X].sample(gx, gz);
            float wz = channel[WARP_Z].sample(gx, gz);
            float px = (float) (gx + p.warpAmp() * wx);
            float pz = (float) (gz + p.warpAmp() * wz);
            n = new Node(key, channel[RANGE].sampleAt(px, pz), channel[SPUR].sampleAt(px, pz));
            cache[slot] = n;
            return n;
        }

        /** {range, spur} at the column: one pass over the 4 x 4 surrounding nodes. */
        double[] interpolate(int x, int z) {
            int lx = Math.floorDiv(x, LATTICE), lz = Math.floorDiv(z, LATTICE);
            double tx = (x - lx * LATTICE) / (double) LATTICE;
            double tz = (z - lz * LATTICE) / (double) LATTICE;
            double[] wz = {bspline(tz, 0), bspline(tz, 1), bspline(tz, 2), bspline(tz, 3)};
            double r = 0.0, s = 0.0;
            for (int a = 0; a < 4; a++) {
                double wa = bspline(tx, a);
                for (int b = 0; b < 4; b++) {
                    Node n = node(lx - 1 + a, lz - 1 + b);
                    double w = wa * wz[b];
                    r += w * n.range;
                    s += w * n.spur;
                }
            }
            return new double[]{r, s};
        }
    }

    // ------------------------------------------------------------------------------- native hand-off

    /** Channel configs for the native context, in channel order. */
    public int[] nativeSeeds() {
        return channelSeed.clone();
    }

    public static int[] nativeOctaves() {
        return OCTAVES.clone();
    }

    public float[] nativeFrequencies() {
        float[] f = new float[CHANNELS];
        for (int c = 0; c < CHANNELS; c++) {
            f[c] = frequency(p, c);
        }
        return f;
    }

    /**
     * Formula constants in the order {@code terrain_ctx.hpp DistilledShape::K_*} reads them.
     * Change both together and bump SBW_ABI_VERSION.
     */
    public double[] nativeConstants() {
        return new double[]{
            p.homeRadius(), p.homeAmp(), p.contOffset(), p.contWidth(), p.warpAmp(),
            p.spineWidth(), p.alongBias(), p.spineM(), p.spurM(), p.footM(),
            p.hillBias(), p.hillSoftness(), p.hillPlainsM(), p.hillCountryM(), p.landBaseM(),
            p.seaFloorM(), p.roughFloor(), p.roughHill(), p.valleyM(), valleyExponent,
            p.textureM(), p.textureFloorM(), p.detailBlocks(), p.peakKneeM(), p.peakSpanM(),
            FBM_STD2, FBM_STD3, FBM_STD4, HeightCurveTable.MIN_M, HeightCurveTable.STEP_M,
            LATTICE,
        };
    }

    /**
     * Everything the native context needs, packed for {@code sbw_terrain_create} /
     * {@code sbw_chunkgen_create} (SBW ABI 2: the old spline slots carry constants + curve table).
     */
    public record NativeArgs(int[] seeds, int[] octaves, float[] gain, float[] lacunarity, float[] freq,
                             int[] xOff, int[] zOff, double[] constants, double[] curveTable,
                             int[] sizes, float detailBlocks) {
    }

    public NativeArgs nativeArgs() {
        float[] gain = new float[CHANNELS];
        float[] lac = new float[CHANNELS];
        java.util.Arrays.fill(gain, 0.5f);
        java.util.Arrays.fill(lac, 2.0f);
        double[] k = nativeConstants();
        double[] table = HeightCurveTable.table();
        return new NativeArgs(nativeSeeds(), nativeOctaves(), gain, lac, nativeFrequencies(),
            new int[CHANNELS], new int[CHANNELS], k, table, new int[]{CHANNELS, k.length, table.length},
            (float) p.detailBlocks());
    }

    // ------------------------------------------------------------------------------- arithmetic

    /** exp(-u) look-alike for u &gt;= 0: (1 - u/3)^3, zero past u = 3 (distill_fit.bell). */
    static double bell(double u) {
        double t = Math.max(1.0 - u / 3.0, 0.0);
        return t * t * t;
    }

    static double smoothstep(double x) {
        double t = Math.max(0.0, Math.min(1.0, x));
        return t * t * (3.0 - 2.0 * t);
    }

    static double valleyShape(double a, double k) {
        if (k == 0.5) {
            return Math.sqrt(a);
        }
        if (k == 1.0) {
            return a;
        }
        if (k == 1.5) {
            return a * Math.sqrt(a);
        }
        return a * a;
    }

    /** Uniform cubic B-spline weight of node {@code k} (0..3 = -1..+2) at fraction t. */
    static double bspline(double t, int k) {
        double u = 1.0 - t;
        double t2 = t * t;
        double t3 = t * t * t;
        return switch (k) {
            case 0 -> u * u * u / 6.0;
            case 1 -> (3.0 * t3 - 6.0 * t2 + 4.0) / 6.0;
            case 2 -> (-3.0 * t3 + 3.0 * t2 + 3.0 * t + 1.0) / 6.0;
            default -> t3 / 6.0;
        };
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static int clampHeight(double floored) {
        int h = (int) floored;
        return Math.max(1, Math.min(h, StandardTerrain.WORLD_HEIGHT - 1));
    }
}
