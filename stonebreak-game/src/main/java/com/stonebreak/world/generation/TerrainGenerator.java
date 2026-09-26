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
     */
    void sampleColumns(int worldX0, int worldZ0, int count, int stride,
                       int[] outHeights, int[] outWaterLevels, BlockType[] outSurface,
                       VegetationGenerator.TreeSample[] outTrees);

    /** Terrain blocks for a chunk; features are populated later, once neighbours exist. */
    TerrainResult generateTerrainOnly(int chunkX, int chunkZ);

    /**
     * Ores, vegetation and decorations on an already-terrained chunk. {@code profile} is the
     * one {@link #generateTerrainOnly} returned, or null to recompute it.
     */
    void populateChunkWithFeatures(World world, Chunk chunk, SnowLayerManager snowLayerManager,
                                   ColumnProfile profile);
}
