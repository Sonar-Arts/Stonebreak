package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.generation.noise.NoiseRouter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Voronoi-based biome region system for creating discrete biome "blobs".
 *
 * Instead of sampling biomes continuously at every block position, this system:
 * 1. Creates a grid of "biome seed points" at regular intervals (e.g., every 64 blocks)
 * 2. Samples biomes at those seed points using the multi-noise parameter system
 * 3. For any query position, finds the nearest seed point and returns its biome
 *
 * This creates natural blob-like biome regions similar to Minecraft 1.18+ while:
 * - Eliminating biome flickering at boundaries
 * - Reducing biome query overhead (uses cached grid lookups)
 * - Maintaining the multi-noise parameter system for selecting which biome goes where
 * - Creating recognizable, discrete biome regions
 *
 * Architecture:
 * - Grid coordinates calculated as: gridX = floor(worldX / cellSize)
 * - Biomes cached in ConcurrentHashMap for thread-safe access
 * - Lazy evaluation: biomes generated on-demand as regions are accessed
 *
 * Follows SOLID principles:
 * - SRP: Only handles voronoi-based biome region management
 * - OCP: Extensible via configuration (cell size, jitter, etc.)
 * - DIP: Depends on NoiseRouter and BiomeParameterTable abstractions
 */
public class BiomeVoronoiGrid {

    private final NoiseRouter noiseRouter;
    private final BiomeParameterTable parameterTable;
    private final int cellSize;
    private final int seaLevel;

    // Thread-safe cache of biome assignments at grid points
    // Key format: (gridX << 32) | (gridZ & 0xFFFFFFFFL)
    private final ConcurrentHashMap<Long, BiomeType> biomeCache;

    /**
     * Creates a new voronoi-based biome grid.
     *
     * @param noiseRouter The noise router for sampling parameters
     * @param parameterTable The biome parameter table for biome selection
     * @param cellSize Size of each voronoi cell in blocks (typically 64-128)
     * @param seaLevel Sea level for parameter sampling
     */
    public BiomeVoronoiGrid(NoiseRouter noiseRouter, BiomeParameterTable parameterTable, int cellSize, int seaLevel) {
        if (cellSize <= 0) {
            throw new IllegalArgumentException("Cell size must be positive, got: " + cellSize);
        }

        this.noiseRouter = noiseRouter;
        this.parameterTable = parameterTable;
        this.cellSize = cellSize;
        this.seaLevel = seaLevel;
        this.biomeCache = new ConcurrentHashMap<>();
    }

    /**
     * Gets the biome at a world position using voronoi nearest-neighbor lookup.
     *
     * Algorithm:
     * 1. Convert world coordinates to grid coordinates
     * 2. Find nearest grid point (center of voronoi cell)
     * 3. Look up or generate biome at that grid point
     * 4. Return cached biome
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return The biome at the nearest grid point
     */
    public BiomeType getBiome(int worldX, int worldZ) {
        // Convert world coordinates to grid coordinates
        // Using Math.floorDiv for correct negative coordinate handling
        int gridX = Math.floorDiv(worldX, cellSize);
        int gridZ = Math.floorDiv(worldZ, cellSize);

        // Check cache first
        long key = packGridCoords(gridX, gridZ);
        BiomeType cached = biomeCache.get(key);
        if (cached != null) {
            return cached;
        }

        // Generate biome at grid point (center of cell)
        int centerX = gridX * cellSize + cellSize / 2;
        int centerZ = gridZ * cellSize + cellSize / 2;

        BiomeType biome = sampleBiomeAtGridPoint(centerX, centerZ);

        // Cache for future lookups
        biomeCache.put(key, biome);

        return biome;
    }

    /**
     * Samples the biome at a specific grid point using the multi-noise parameter system.
     *
     * This maintains compatibility with the existing multi-noise terrain generation:
     * - Samples all 6 parameters (continentalness, erosion, PV, weirdness, temperature, humidity)
     * - Uses BiomeParameterTable to select matching biome
     * - Same biome selection logic as continuous system, just sampled at grid points
     *
     * @param x World X coordinate (typically center of voronoi cell)
     * @param z World Z coordinate (typically center of voronoi cell)
     * @return The biome type at this position
     */
    private BiomeType sampleBiomeAtGridPoint(int x, int z) {
        // Sample all 6 multi-noise parameters at this grid point
        MultiNoiseParameters params = noiseRouter.sampleParameters(x, z, seaLevel);

        // Select biome using parameter table (same as continuous system)
        return parameterTable.selectBiome(params);
    }

    /**
     * Packs grid coordinates into a single long for use as cache key.
     *
     * Format: (gridX << 32) | (gridZ & 0xFFFFFFFFL)
     * This allows efficient HashMap lookups with 2D coordinates.
     *
     * @param gridX Grid X coordinate
     * @param gridZ Grid Z coordinate
     * @return Packed long key
     */
    private long packGridCoords(int gridX, int gridZ) {
        return ((long) gridX << 32) | (gridZ & 0xFFFFFFFFL);
    }

    /**
     * Gets the cell size of this voronoi grid.
     *
     * @return Cell size in blocks
     */
    public int getCellSize() {
        return cellSize;
    }

    /**
     * Gets the number of cached biome assignments.
     * Useful for monitoring memory usage and performance.
     *
     * @return Number of cached grid points
     */
    public int getCacheSize() {
        return biomeCache.size();
    }

    /**
     * Clears the biome cache.
     * Useful for testing or if biome generation parameters change.
     */
    public void clearCache() {
        biomeCache.clear();
    }
}
