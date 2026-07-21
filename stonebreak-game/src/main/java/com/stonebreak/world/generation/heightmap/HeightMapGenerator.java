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
    private static final float DETAIL_AMPLITUDE = 3f;

    private final NoiseRouter noise;
    private final SplineInterpolator baseSpline;
    private final SplineInterpolator peakSpline;
    private final SplineInterpolator erosionToPeakStrength;

    public HeightMapGenerator(NoiseRouter noise) {
        this.noise = noise;

        // Continentalness → base terrain elevation (blocks). Matches previous
        // deep-ocean / coast / inland profile but compressed so peaks have headroom.
        this.baseSpline = new SplineInterpolator();
        baseSpline.addPoint(-1.0, 70);
        baseSpline.addPoint(-0.8, 20);
        baseSpline.addPoint(-0.4, 58);
        baseSpline.addPoint(-0.2, 66);
        baseSpline.addPoint( 0.1, 72);
        baseSpline.addPoint( 0.3, 88);
        baseSpline.addPoint( 0.7, 100);
        baseSpline.addPoint( 1.0, 115);

        // Peaks/valleys → signed offset. Valleys dip below base; peaks rise above.
        // Steep midrange so typical PV samples (|pv| ~ 0.2–0.4) still produce
        // visible hills/mountains rather than flat ground.
        this.peakSpline = new SplineInterpolator();
        peakSpline.addPoint(-1.0, -20);
        peakSpline.addPoint(-0.3,  -5);
        peakSpline.addPoint( 0.0,   0);
        peakSpline.addPoint( 0.15, 10);
        peakSpline.addPoint( 0.3,  25);
        peakSpline.addPoint( 0.5,  55);
        peakSpline.addPoint( 0.7,  85);
        peakSpline.addPoint( 1.0, 110);

        // Erosion → peak strength multiplier. Biased high so mountains appear
        // by default; only strong positive erosion flattens them into plateaus.
        this.erosionToPeakStrength = new SplineInterpolator();
        erosionToPeakStrength.addPoint(-1.0, 1.00);
        erosionToPeakStrength.addPoint(-0.3, 0.95);
        erosionToPeakStrength.addPoint( 0.2, 0.80);
        erosionToPeakStrength.addPoint( 0.6, 0.35);
        erosionToPeakStrength.addPoint( 1.0, 0.10);
    }

    /** Base elevation from continentalness alone (ignores PV/erosion/detail). */
    public int baseHeight(int x, int z) {
        return clampToWorld((int) baseSpline.interpolate(noise.continentalness(x, z)));
    }

    /** Elevation including peaks/valleys but without surface detail (debug). */
    public int shapedHeight(int x, int z) {
        float base = (float) baseSpline.interpolate(noise.continentalness(x, z));
        float pv = (float) peakSpline.interpolate(noise.peaksValleys(x, z));
        float strength = (float) erosionToPeakStrength.interpolate(noise.erosion(x, z));
        return clampToWorld(Math.round(base + pv * strength));
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
