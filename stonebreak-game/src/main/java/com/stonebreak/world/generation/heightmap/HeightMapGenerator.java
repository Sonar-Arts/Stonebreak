package com.stonebreak.world.generation.heightmap;

import com.openmason.engine.util.SplineInterpolator;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Generates terrain height from three independent noise channels routed through {@link NoiseRouter}:
 * <ul>
 *   <li><b>Continentalness</b> → base elevation spline (land/ocean).</li>
 *   <li><b>Peaks/Valleys</b> → signed mountain offset spline.</li>
 *   <li><b>Erosion</b> → peak strength (0 = flat, 1 = full peaks).</li>
 * </ul>
 * A low-amplitude detail noise adds ±3 block fuzz for surface texture.
 *
 * Biomes do NOT contribute to terrain shape. They are selected to match the shape
 * downstream via {@code BiomeSelector}.
 *
 * Final height = base(C) + pv(PV) * peakStrength(E) + detail
 */
public class HeightMapGenerator {
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    /** Max absolute detail offset in blocks. Keeps biomes from looking samey. */
    public static final float DETAIL_AMPLITUDE = 3f;

    // Spline control points, exported so the native carver's terrain context
    // evaluates the EXACT same height formula. Order: base(C), peak(PV),
    // erosion->peakStrength(E).
    //
    // base: continentalness → elevation (blocks) — previous deep-ocean/coast/
    // inland profile, compressed so peaks have headroom.
    // peak: PV → signed offset; steep midrange so typical |pv| ~ 0.2–0.4 still
    // produces visible hills instead of flat ground.
    // strength: erosion → peak multiplier, biased high so mountains appear by
    // default; only strong positive erosion flattens them into plateaus.
    private static final double[][] BASE_POINTS = {
        {-1.0, 70}, {-0.8, 20}, {-0.4, 58}, {-0.2, 66},
        {0.1, 72}, {0.3, 88}, {0.7, 100}, {1.0, 115}};
    private static final double[][] PEAK_POINTS = {
        {-1.0, -20}, {-0.3, -5}, {0.0, 0}, {0.15, 10},
        {0.3, 25}, {0.5, 55}, {0.7, 85}, {1.0, 110}};
    private static final double[][] STRENGTH_POINTS = {
        {-1.0, 1.00}, {-0.3, 0.95}, {0.2, 0.80}, {0.6, 0.35}, {1.0, 0.10}};

    private final NoiseRouter noise;
    private final SplineInterpolator baseSpline;
    private final SplineInterpolator peakSpline;
    private final SplineInterpolator erosionToPeakStrength;

    public HeightMapGenerator(NoiseRouter noise) {
        this.noise = noise;
        this.baseSpline = splineOf(BASE_POINTS);
        this.peakSpline = splineOf(PEAK_POINTS);
        this.erosionToPeakStrength = splineOf(STRENGTH_POINTS);
    }

    private static SplineInterpolator splineOf(double[][] points) {
        SplineInterpolator spline = new SplineInterpolator();
        for (double[] point : points) {
            spline.addPoint(point[0], point[1]);
        }
        return spline;
    }

    /** Concatenated spline X coordinates (base, peak, strength) for the native carver. */
    public static double[] splineXs() {
        return concatColumn(0);
    }

    /** Concatenated spline Y coordinates (base, peak, strength) for the native carver. */
    public static double[] splineYs() {
        return concatColumn(1);
    }

    /** Per-spline point counts (base, peak, strength) for the native carver. */
    public static int[] splineSizes() {
        return new int[]{BASE_POINTS.length, PEAK_POINTS.length, STRENGTH_POINTS.length};
    }

    private static double[] concatColumn(int column) {
        double[] out = new double[BASE_POINTS.length + PEAK_POINTS.length + STRENGTH_POINTS.length];
        int i = 0;
        for (double[][] points : new double[][][]{BASE_POINTS, PEAK_POINTS, STRENGTH_POINTS}) {
            for (double[] point : points) {
                out[i++] = point[column];
            }
        }
        return out;
    }

    /** Base elevation from continentalness alone (ignores PV/erosion/detail). */
    public int baseHeight(int x, int z) {
        return clampToWorld((int) baseSpline.interpolate(noise.continentalness(x, z)));
    }

    /** Elevation including peaks/valleys but without surface detail (debug + biome temperature). */
    public int shapedHeight(int x, int z) {
        return shapedFromChannels(noise.continentalness(x, z), noise.peaksValleys(x, z), noise.erosion(x, z));
    }

    /** {@link #shapedHeight} from already-sampled channel values (batched paths). */
    public int shapedFromChannels(float c, float pv, float e) {
        float base = (float) baseSpline.interpolate(c);
        float peak = (float) peakSpline.interpolate(pv);
        float strength = (float) erosionToPeakStrength.interpolate(e);
        return clampToWorld(Math.round(base + peak * strength));
    }

    /** Final surface height including detail noise. */
    public int generateHeight(int x, int z) {
        return heightFromChannels(noise.continentalness(x, z), noise.peaksValleys(x, z),
            noise.erosion(x, z), noise.detail(x, z));
    }

    /**
     * The single height formula, shared by the per-point path and the batched
     * chunk path so both produce identical results from identical channel values.
     */
    public int heightFromChannels(float c, float pv, float e, float d) {
        float base = (float) baseSpline.interpolate(c);
        float peak = (float) peakSpline.interpolate(pv);
        float strength = (float) erosionToPeakStrength.interpolate(e);
        float detail = d * DETAIL_AMPLITUDE;
        return clampToWorld(Math.round(base + peak * strength + detail));
    }

    /**
     * Fills a 16x16 final-height grid for the given chunk, indexed [x*16+z].
     * Channels are batch-filled (one SIMD call each on the native backend)
     * and combined per cell — values match {@link #generateHeight} exactly.
     */
    public void populateChunkHeights(int chunkX, int chunkZ, int[] out) {
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        int cells = CHUNK_SIZE * CHUNK_SIZE;
        float[] c = new float[cells];
        float[] pv = new float[cells];
        float[] e = new float[cells];
        float[] d = new float[cells];
        noise.fillShapeChannels(baseX, baseZ, CHUNK_SIZE, CHUNK_SIZE, 1, c, pv, e, d);
        for (int i = 0; i < cells; i++) {
            out[i] = heightFromChannels(c[i], pv[i], e[i], d[i]);
        }
    }

    private static int clampToWorld(int height) {
        return Math.max(1, Math.min(height, WORLD_HEIGHT - 1));
    }
}
