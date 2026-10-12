package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.SnowLayerManager;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.features.VegetationGenerator;

/**
 * A world's terrain source, as the world, chunk store and FastLOD see it. Implemented by
 * Standard Generation ({@link TerrainGenerationSystem}) and the experimental Diffusion
 * Generation; {@link TerrainGeneratorType} picks one per world.
 *
 * <p>Every Y this interface reports is world Y.
 */
public interface TerrainGenerator {

    /** {@code outFloor} sentinel for a cell with no cave mouth; see {@link #sampleCellOpenings}. */
    int NO_OPENING = Integer.MIN_VALUE;

    /**
     * Result of terrain-only generation: the chunk plus the column profile so deferred
     * feature population can reuse it instead of resampling the terrain.
     */
    record TerrainResult(Chunk chunk, ColumnProfile profile) {}

    long getSeed();

    /**
     * Identifies the terrain this generator produces, for caches that persist what it sampled
     * (FastLOD's per-world node store). A cache written under a different tag is discarded. Code
     * changes on the Java side are versioned by the caches themselves; this covers what they
     * cannot see, such as an external model being retrained.
     */
    default String lodCacheTag() {
        return "standard";
    }

    /** Releases anything the generator holds open. Called from {@code World.cleanup()}. */
    default void shutdown() {
    }

    BiomeType getBiomeAt(int x, int z);

    /** Base height before shaping (debug). */
    int getBaseHeightAt(int x, int z);

    /** Shaped height without surface detail (debug). */
    int getShapedHeightAt(int x, int z);

    /** Final terrain height as used by chunk generation. */
    int getFinalTerrainHeightAt(int x, int z);

    /** Water level as used by chunk generation, or {@code WorldConfiguration.NO_WATER}. */
    int getWaterLevelAt(int x, int z);

    /** One past the highest solid block of a column, after carving. */
    int carvedSurfaceHeight(int worldX, int worldZ);

    /**
     * Per-cell cave-mouth geometry for a FastLOD node: the lowest carved floor in each cell's
     * footprint ({@link #NO_OPENING} when nothing reaches below {@code cellHeights}) and the
     * carved share of the footprint, 0..255.
     */
    void sampleCellOpenings(int worldX0, int worldZ0, int cellsPerAxis, int cellSize,
                            int[] cellHeights, int[] outFloor, byte[] outCoverage);

    /**
     * Batched column probe for FastLOD over a {@code count x count} grid at
     * {@code (worldX0 + ix*stride, worldZ0 + iz*stride)}, indexed {@code [ix*count + iz]}.
     * Heights are the carved surface. Every output but {@code outHeights} is nullable.
     *
     * <p>With {@code stride > 1} each probe stands for the {@code stride x stride} footprint
     * {@code [p - stride/2, p - stride/2 + stride)} around it on both axes, and an implementation
     * may report the column in that footprint that represents it best rather than the probe
     * itself, as long as the choice depends only on the footprint (neighbouring nodes probe the
     * same cells for their margins, and must agree).
     */
    void sampleColumns(int worldX0, int worldZ0, int count, int stride,
                       int[] outHeights, int[] outWaterLevels, BlockType[] outSurface,
                       VegetationGenerator.TreeSample[] outTrees);

    /**
     * {@link #sampleColumns} without the cave carve: heights are the raw terrain height and
     * surfaces the biome's surface block, so no carved surface profile is built. For FastLOD's
     * coarse uncarved levels ({@code FastLodQuality#carves}), where that profile — a carve-mask
     * build of every chunk the grid touches — is nearly all of a node's cost. No tree samples.
     * The footprint rule of {@link #sampleColumns} applies unchanged.
     */
    void sampleRawColumns(int worldX0, int worldZ0, int count, int stride,
                          int[] outHeights, int[] outWaterLevels, BlockType[] outSurface);

    /**
     * Every tree the real generator plants inside a coarse FastLOD node, for drawing past the
     * finest band: each column of each cell probed against the cell's biome and sampled
     * surface block. A submerged cell, or one whose surface no tree grows on, plants nothing.
     *
     * @param cellHeights      sampled surface height per cell, {@code [ix*cells+iz]}
     * @param cellWaterLevels  sampled water level per cell, {@code [ix*cells+iz]}
     * @param cellSurface      sampled surface block per cell, {@code [ix*cells+iz]}
     * @return packed spots ({@code FastLodChunkData.packTreeSpot}); empty when none
     */
    int[] probeCellTrees(int chunkX, int chunkZ, int cellsPerAxis, int cellSize,
                         int[] cellHeights, int[] cellWaterLevels, BlockType[] cellSurface);

    /** Terrain blocks for a chunk; features are populated later, once neighbours exist. */
    TerrainResult generateTerrainOnly(int chunkX, int chunkZ);

    /**
     * Ores, vegetation and decorations on an already-terrained chunk. {@code profile} is the
     * one {@link #generateTerrainOnly} returned, or null to recompute it.
     */
    void populateChunkWithFeatures(World world, Chunk chunk, SnowLayerManager snowLayerManager,
                                   ColumnProfile profile);
}
