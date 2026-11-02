package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.StandardTerrain;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * The Standard generator's height oracle: {@link DistilledShape} (DaedalusTGM-Exp's continents,
 * ranges and hill provinces, fitted to the model's output) through the model's height curve.
 *
 * Biomes do NOT contribute to terrain shape. They are selected to match the shape
 * downstream via {@code BiomeSelector}, from the same column fields.
 */
public class HeightMapGenerator implements SurfaceHeights {
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final int SEA_LEVEL = StandardTerrain.SEA_LEVEL;

    private final DistilledShape shape;

    public HeightMapGenerator(NoiseRouter noise) {
        this.shape = noise.shape();
    }

    public DistilledShape shape() {
        return shape;
    }

    /** Debug view of the large-scale shape: the column without its block-space detail fuzz. */
    public int baseHeight(int x, int z) {
        return shapedHeight(x, z);
    }

    /** Elevation without surface detail (debug + biome temperature). */
    public int shapedHeight(int x, int z) {
        DistilledShape.Column c = new DistilledShape.Column();
        shape.column(x, z, c);
        return c.shapedHeight;
    }

    /** Final surface height including detail noise. */
    public int generateHeight(int x, int z) {
        return shape.height(x, z);
    }

    /**
     * One column's shape fields with THIS oracle's heights. A subclass is a height oracle (the
     * test seam {@code TerrainGenerationSystem(seed, heightMap)} runs the whole stack on a known
     * surface): its {@link #generateHeight} / {@link #shapedHeight} stay authoritative, so biome
     * rules that read the height (shore strip, low-lying swamps, altitude chill) follow the
     * supplied surface, not the seed's own.
     */
    public void column(int x, int z, DistilledShape.Column out) {
        shape.column(x, z, out);
        if (isHeightOracle()) {
            out.shapedHeight = shapedHeight(x, z);
            out.height = generateHeight(x, z);
        }
    }

    /**
     * Batched {@link #column}s, {@code out[ix*countZ + iz]} at {@code (baseX + ix*stride,
     * baseZ + iz*stride)}; every value equals the per-point {@link #column} at the same position.
     */
    public void fillColumns(int baseX, int baseZ, int countX, int countZ, int stride,
                            DistilledShape.Column[] out) {
        shape.fill(baseX, baseZ, countX, countZ, stride, out);
        if (isHeightOracle()) {
            for (int ix = 0; ix < countX; ix++) {
                for (int iz = 0; iz < countZ; iz++) {
                    DistilledShape.Column c = out[ix * countZ + iz];
                    int x = baseX + ix * stride, z = baseZ + iz * stride;
                    c.shapedHeight = shapedHeight(x, z);
                    c.height = generateHeight(x, z);
                }
            }
        }
    }

    /** True for subclasses: they supply heights; the seed's shape only supplies the fields. */
    private boolean isHeightOracle() {
        return getClass() != HeightMapGenerator.class;
    }

    /**
     * Fills a 16x16 final-height grid for the given chunk, indexed [x*16+z]. Values match
     * {@link #generateHeight} exactly.
     */
    public void populateChunkHeights(int chunkX, int chunkZ, int[] out) {
        DistilledShape.Column[] cols = new DistilledShape.Column[CHUNK_SIZE * CHUNK_SIZE];
        shape.fill(chunkX * CHUNK_SIZE, chunkZ * CHUNK_SIZE, CHUNK_SIZE, CHUNK_SIZE, 1, cols);
        for (int i = 0; i < cols.length; i++) {
            out[i] = cols[i].height;
        }
    }

    /**
     * Fills a 16x16 final-height grid AND the co-located water plane the cave
     * carvers guard against. Indices match {@link #populateChunkHeights}.
     */
    public void populateChunkHeights(int chunkX, int chunkZ, int[] out, int[] outWaterLevels) {
        populateChunkHeights(chunkX, chunkZ, out);
        if (outWaterLevels == null) {
            return;
        }
        for (int i = 0; i < out.length; i++) {
            outWaterLevels[i] = waterLevelFor(out[i]);
        }
    }

    /**
     * Water level for a column, or {@link WorldConfiguration#NO_WATER} when it is dry.
     *
     * <p>Standard has no rivers or lakes — the only standing water is the sea that
     * {@code determineBlockType} fills in below {@code SEA_LEVEL}. So a column is wet
     * exactly when its surface is submerged, and its water level is sea level.
     */
    public int waterLevel(int x, int z) {
        return waterLevelFor(generateHeight(x, z));
    }

    private static int waterLevelFor(int height) {
        return height < SEA_LEVEL ? SEA_LEVEL : WorldConfiguration.NO_WATER;
    }
}
