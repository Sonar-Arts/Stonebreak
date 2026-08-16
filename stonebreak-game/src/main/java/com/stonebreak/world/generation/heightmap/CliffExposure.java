package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.operations.WorldConfiguration;

/**
 * How much a column sits behind a steep face — the datum that lets caves reach a cliff.
 *
 * <p>{@link Density3D} keeps caves off the surface with two depth gates: the cheese threshold
 * spline and the spaghetti fade. Both measure <em>vertical</em> depth below the column's own
 * surface, which is the right measure on flat ground and the wrong one on a cliff. The
 * overhang band is anchored per column, so on a slope it is a thin skin following the terrain;
 * a few blocks horizontally into a face and you have left it, and what is behind it is the
 * depth 10-35 shell where neither gate has opened yet. That is why a cliff opening reads as a
 * 2-4 block pocket with a wall behind it.
 *
 * <p>This class supplies the missing horizontal information as a single per-column scalar in
 * {@code [0,1]}: the steepest local grade, smoothstepped. {@code Density3D} uses it to
 * interpolate those two gates toward a shallower pair, so tunnels and chambers come right up
 * to a face while flat terrain keeps today's numbers exactly.
 *
 * <p>Deliberately <b>noise-free</b>. It is pure arithmetic over heights the chunk pipeline
 * already resolves, so it costs no volume fill — {@code prepareChunk} stays at three — and,
 * unlike {@link CaveWaterTable}, the batched and point paths agree by construction rather than
 * by relying on a fill/sample guarantee. Every emitted value is a pure function of the column,
 * which is the same purity rule the rest of the carve stack obeys.
 */
public final class CliffExposure {

    /**
     * Tap distance in blocks, and so the reach of the effect: a column this far in from a face
     * still sees it. This is the knob that sets how deep the recess behind an opening goes.
     */
    private static final int RADIUS = 14;

    /**
     * Grade at which a slope starts counting as a face, and at which it saturates.
     *
     * <p>0.6 is about 31 degrees — comfortably above rolling plains and meadow hillsides, which
     * therefore stay at exactly zero and generate bit-for-bit as they do today. A real cliff
     * grades 3 or more and pins at 1.
     */
    private static final float SLOPE_MIN = 0.6f;
    private static final float SLOPE_MAX = 2.0f;

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    /** The 8 compass directions, scaled to {@link #RADIUS}. Diagonals use the same tap distance. */
    private static final int[] TAP_DX = {RADIUS, RADIUS, 0, -RADIUS, -RADIUS, -RADIUS, 0, RADIUS};
    private static final int[] TAP_DZ = {0, RADIUS, RADIUS, RADIUS, 0, -RADIUS, -RADIUS, -RADIUS};

    private final HeightMapGenerator heightMap;

    public CliffExposure(HeightMapGenerator heightMap) {
        this.heightMap = heightMap;
    }

    /**
     * Per-column exposure for a chunk, indexed {@code [x*CHUNK_SIZE+z]} to match the
     * heights/waterLevels/table grids.
     *
     * <p>The taps leave the chunk, which is why this cannot work off the caller's height grid
     * the way {@link CaveWaterTable#tableForChunk} does. It reads a {@link #RADIUS}-haloed patch
     * instead, in one pass, so the cost is a handful of tile resolutions per chunk rather than
     * eight per column — {@code getTile} is a concurrent-map lookup plus LRU bookkeeping on the
     * production cache, and this runs on the generation threads.
     *
     * @param heights final surface height per column; used only as the patch's centre values,
     *                so the grid the caller already holds is not resolved a second time
     */
    public float[] exposureForChunk(int chunkX, int chunkZ, int[] heights) {
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        int minX = baseX - RADIUS;
        int minZ = baseZ - RADIUS;
        int span = CHUNK_SIZE + 2 * RADIUS;

        int[] patch = new int[span * span];
        heightMap.populateHeightPatch(minX, minZ, span, span, patch);

        float[] exposure = new float[CHUNK_SIZE * CHUNK_SIZE];
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                int surface = heights[x * CHUNK_SIZE + z];
                int lowest = surface;
                for (int i = 0; i < TAP_DX.length; i++) {
                    int px = x + RADIUS + TAP_DX[i];
                    int pz = z + RADIUS + TAP_DZ[i];
                    int h = patch[px * span + pz];
                    if (h < lowest) {
                        lowest = h;
                    }
                }
                exposure[x * CHUNK_SIZE + z] = grade(surface, lowest);
            }
        }
        return exposure;
    }

    /**
     * Exposure at an arbitrary world column, for the per-point path where the batched grid is
     * not available. Agrees with {@link #exposureForChunk} exactly — same taps, same arithmetic.
     */
    public float exposureAt(int worldX, int worldZ) {
        return resolve(worldX, worldZ, heightMap.generateHeight(worldX, worldZ));
    }

    /**
     * Steepest outward grade at one column, smoothstepped into {@code [0,1]}.
     *
     * <p>Only <em>descent</em> counts: a column at the foot of a cliff has a huge grade to its
     * neighbours, but the rock is above it, not behind it, so signing the difference this way
     * keeps the effect on the cliff top and face where the openings are.
     */
    private float resolve(int worldX, int worldZ, int surface) {
        int lowest = surface;
        for (int i = 0; i < TAP_DX.length; i++) {
            int h = heightMap.generateHeight(worldX + TAP_DX[i], worldZ + TAP_DZ[i]);
            if (h < lowest) {
                lowest = h;
            }
        }
        return grade(surface, lowest);
    }

    /**
     * Shared by both paths so the batched grid and the point sample cannot drift apart.
     *
     * <p>The two callers duplicate the tap loop — one walks a patch array, the other calls the
     * tile source — because the whole point of the patch is to not do the second thing. What is
     * factored out here is everything that carries a constant or a formula, which is what would
     * actually diverge silently; a min over the same {@link #TAP_DX}/{@link #TAP_DZ} pair would
     * not.
     *
     * <p>The one invariant the compiler cannot hold: both paths must pass the <em>same</em>
     * centre height. {@code exposureForChunk} takes it from the caller's grid and
     * {@code exposureAt} from {@link HeightMapGenerator#generateHeight}; those agree today
     * because both are {@code clampToWorld} of the same tile value, and they must keep agreeing.
     */
    private static float grade(int surface, int lowest) {
        return smoothstep(SLOPE_MIN, SLOPE_MAX, (surface - lowest) / (float) RADIUS);
    }

    /** Hermite smoothstep, clamped — 0 at or below {@code edge0}, 1 at or above {@code edge1}. */
    private static float smoothstep(float edge0, float edge1, float value) {
        if (value <= edge0) {
            return 0f;
        }
        if (value >= edge1) {
            return 1f;
        }
        float t = (value - edge0) / (edge1 - edge0);
        return t * t * (3f - 2f * t);
    }
}
