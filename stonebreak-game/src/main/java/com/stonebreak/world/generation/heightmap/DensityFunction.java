package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.config.TerrainNoiseWeights;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Determines block solidity using 3D density-based terrain generation.
 *
 * This class enables 3D terrain features like overhangs, caves, and natural arches
 * by sampling 3D noise to determine if a block should be solid or air. Unlike
 * traditional 2D heightmap generation, 3D density allows terrain to fold back on
 * itself, creating complex geological features.
 *
 * Performance Optimization:
 * 3D noise is expensive (~10x slower than 2D). This class optimizes by only
 * sampling in "transition zones" near the surface (±10-20 blocks). Deep underground
 * is always solid, high in air is always air.
 *
 * Density Calculation:
 * - Positive density (> threshold) → Solid block
 * - Negative density (< threshold) → Air block
 * - Height bias: More solid below sea level, more air above
 *
 * Biome Integration:
 * Different biomes have different 3D feature frequencies. Some biomes
 * (like beaches and deserts) disable 3D terrain entirely for performance
 * and visual consistency.
 *
 * Follows Single Responsibility Principle - only handles density-based solid/air determination.
 * Follows Dependency Inversion Principle - configuration injected via constructor.
 */
public class DensityFunction {

    private final Noise3D noise3D;
    private final TerrainNoiseWeights noiseWeights;
    private final int seaLevel;
    private final float densityThreshold;
    private final float densityScale;
    private final int transitionZoneMin;
    private final int transitionZoneMax;

    /**
     * Creates a new density function with default parameters.
     *
     * @param seed World seed for deterministic generation
     */
    public DensityFunction(long seed) {
        this(seed, 0.0f, 60.0f, -10, 20);
    }

    /**
     * Creates a new density function with custom parameters.
     *
     * @param seed                World seed for deterministic generation
     * @param densityThreshold    Threshold for solid/air (typically 0.0)
     * @param densityScale        Scale for 3D noise (larger = smoother features)
     * @param transitionZoneMin   Start 3D sampling (relative to surface, e.g., -10)
     * @param transitionZoneMax   Stop 3D sampling (relative to surface, e.g., +20)
     */
    public DensityFunction(long seed, float densityThreshold, float densityScale, int transitionZoneMin, int transitionZoneMax) {
        this.noise3D = new Noise3D(seed + 13, NoiseConfigFactory.density3D());
        this.noiseWeights = new TerrainNoiseWeights();
        this.seaLevel = WorldConfiguration.SEA_LEVEL;
        this.densityThreshold = densityThreshold;
        this.densityScale = densityScale;
        this.transitionZoneMin = transitionZoneMin;
        this.transitionZoneMax = transitionZoneMax;
    }

    /**
     * Determines if a block should be solid based on 3D density.
     *
     * Optimized to only sample 3D noise in the transition zone near the surface.
     * Outside the transition zone, uses fast heightmap checks.
     *
     * @param worldX        World X coordinate
     * @param y             World Y coordinate
     * @param worldZ        World Z coordinate
     * @param surfaceHeight Surface height from 2D heightmap
     * @param biome         Biome type at this location
     * @return true if block should be solid, false if air
     */
    public boolean isSolid(int worldX, int y, int worldZ, int surfaceHeight, BiomeType biome) {
        // Check if biome supports 3D terrain
        if (!noiseWeights.is3DEnabled(biome)) {
            // Fall back to simple heightmap (fast path)
            return y < surfaceHeight;
        }

        // Deep underground → always solid (fast path)
        if (y < surfaceHeight + transitionZoneMin) {
            return true;
        }

        // High in air → always air (fast path)
        if (y > surfaceHeight + transitionZoneMax) {
            return false;
        }

        // Transition zone → use 3D density (expensive)
        float density = noise3D.getDensity(worldX, y, worldZ, densityScale);

        return density > densityThreshold;
    }

    /**
     * Determines if a block should be solid with custom overhang frequency.
     *
     * Allows runtime adjustment of 3D feature frequency without changing biome config.
     *
     * @param worldX           World X coordinate
     * @param y                World Y coordinate
     * @param worldZ           World Z coordinate
     * @param surfaceHeight    Surface height from 2D heightmap
     * @param biome            Biome type at this location
     * @param overhangFrequency Frequency multiplier (0.0-1.0, overrides biome default)
     * @return true if block should be solid, false if air
     */
    public boolean isSolidWithFrequency(int worldX, int y, int worldZ, int surfaceHeight, BiomeType biome, float overhangFrequency) {
        if (!noiseWeights.is3DEnabled(biome) || overhangFrequency <= 0.0f) {
            return y < surfaceHeight;
        }

        if (y < surfaceHeight + transitionZoneMin) {
            return true;
        }

        if (y > surfaceHeight + transitionZoneMax) {
            return false;
        }

        // Adjust density threshold based on frequency
        // Higher frequency → lower threshold → more air blocks (more overhangs)
        float adjustedThreshold = densityThreshold + (1.0f - overhangFrequency) * 0.3f;

        float density = noise3D.getDensity(worldX, y, worldZ, densityScale);

        return density > adjustedThreshold;
    }

    /**
     * Gets the raw density value at a 3D position.
     *
     * Useful for debugging or visualization of density fields.
     *
     * @param worldX World X coordinate
     * @param y      World Y coordinate
     * @param worldZ World Z coordinate
     * @return Density value (positive = solid bias, negative = air bias)
     */
    public float getDensity(int worldX, int y, int worldZ) {
        return noise3D.getDensity(worldX, y, worldZ, densityScale);
    }

    /**
     * Checks if a position is within the 3D sampling transition zone.
     *
     * @param y             World Y coordinate
     * @param surfaceHeight Surface height from 2D heightmap
     * @return true if position is in transition zone
     */
    public boolean isInTransitionZone(int y, int surfaceHeight) {
        return y >= surfaceHeight + transitionZoneMin &&
               y <= surfaceHeight + transitionZoneMax;
    }

    /**
     * Estimates the number of 3D samples needed for a chunk column.
     *
     * Used for performance profiling.
     *
     * @param surfaceHeight Surface height of the column
     * @return Estimated number of 3D noise samples
     */
    public int estimateSamplesForColumn(int surfaceHeight) {
        int zoneStart = surfaceHeight + transitionZoneMin;
        int zoneEnd = surfaceHeight + transitionZoneMax;

        zoneStart = Math.max(0, zoneStart);
        zoneEnd = Math.min(WorldConfiguration.WORLD_HEIGHT - 1, zoneEnd);

        return Math.max(0, zoneEnd - zoneStart + 1);
    }

    /**
     * Gets the sea level used for density bias calculations.
     *
     * @return Sea level height
     */
    public int getSeaLevel() {
        return seaLevel;
    }

    /**
     * Gets the density threshold for solid/air determination.
     *
     * @return Density threshold
     */
    public float getDensityThreshold() {
        return densityThreshold;
    }

    /**
     * Gets the 3D noise scale.
     *
     * @return Density scale
     */
    public float getDensityScale() {
        return densityScale;
    }
}
