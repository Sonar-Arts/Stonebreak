package com.stonebreak.world.generation.features.terrain;

/**
 * Utility class for depth-weighted density calculations used in 3D terrain features.
 *
 * This class provides the core mathematical functions for combining heightmap-based
 * "base density" with 3D noise to create terrain features that:
 * - Minimize surface erosion (mostly stay solid at surface)
 * - Enable underground caves (allow carving deep underground)
 * - Create rare surface features (overhangs, arches) with high thresholds
 *
 * Key Concept: Depth-Weighted Mixing
 * Rather than replacing the heightmap with 3D noise, we ADD noise influence
 * that grows stronger underground:
 *
 * finalDensity = baseDensity + (noise * influence)
 *
 * where influence varies from 10% at surface to 90% deep underground.
 *
 * Mathematical Foundation (from Minecraft 1.18+ research):
 * - Surface (y > surfaceHeight): influence = 10-20% (noise barely affects)
 * - Shallow (y = surfaceHeight - 20): influence = 40-60% (cave entrances)
 * - Deep (y < surfaceHeight - 60): influence = 80-90% (full cave systems)
 */
public class TerrainFeatureDensityUtil {

    /**
     * Calculates base density from distance below surface.
     *
     * Base density represents the "default" solidity based on heightmap:
     * - Positive = tends toward solid
     * - Negative = tends toward air
     * - Zero = at surface level
     *
     * Formula: baseDensity = depthBelowSurface / 8.0
     *
     * Why divide by 8?
     * - At depth=8: baseDensity=1.0 (moderately solid)
     * - At depth=16: baseDensity=2.0 (very solid)
     * - At depth=0 (surface): baseDensity=0.0 (neutral)
     * - Above surface: baseDensity is negative (tends toward air)
     *
     * @param y             Block Y coordinate
     * @param surfaceHeight Surface height at this (x, z)
     * @return Base density (positive = solid bias, negative = air bias)
     */
    public static float calculateBaseDensity(int y, int surfaceHeight) {
        float depthBelowSurface = surfaceHeight - y;
        return depthBelowSurface / 8.0f;
    }

    /**
     * Calculates noise influence factor based on depth below surface.
     *
     * Influence determines how much 3D noise affects the final density:
     * - 0.1 at surface (10% influence - noise barely affects)
     * - 0.5 at mid-depth (50% influence - balanced)
     * - 0.9 deep underground (90% influence - noise dominates)
     *
     * Formula: influence = 0.1 + (depthFactor * 0.8)
     * where depthFactor = clamp(depthBelowSurface / 60.0, 0.0, 1.0)
     *
     * Why 60 blocks transition?
     * - Matches typical mountain height variations
     * - Full cave systems start around y=0-10 (60 blocks below surface at y=64)
     * - Gradual transition prevents visible "layer" boundaries
     *
     * @param y             Block Y coordinate
     * @param surfaceHeight Surface height at this (x, z)
     * @return Noise influence factor (0.1 to 0.9)
     */
    public static float calculateNoiseInfluence(int y, int surfaceHeight) {
        float depthBelowSurface = Math.max(0, surfaceHeight - y);
        float depthFactor = Math.min(1.0f, depthBelowSurface / 60.0f);
        return 0.1f + (depthFactor * 0.8f);
    }

    /**
     * Combines base density and noise using depth-weighted mixing.
     *
     * This is the core formula for terrain feature generation:
     * finalDensity = baseDensity + (noise * influence)
     *
     * Example at surface (y=70, surfaceHeight=70):
     * - baseDensity = 0 / 8.0 = 0.0
     * - influence = 0.1 (10%)
     * - noise = 0.8 (high value)
     * - finalDensity = 0.0 + (0.8 * 0.1) = 0.08 (slightly solid, stays solid)
     *
     * Example deep underground (y=10, surfaceHeight=70):
     * - baseDensity = 60 / 8.0 = 7.5 (very solid)
     * - influence = 0.9 (90%)
     * - noise = 0.5
     * - finalDensity = 7.5 + (0.5 * 0.9) = 7.95 (stays solid)
     * - If noise = -0.5: finalDensity = 7.5 + (-0.5 * 0.9) = 7.05 (still solid)
     * - If noise = -8.0: finalDensity = 7.5 + (-8.0 * 0.9) = 0.3 (barely solid)
     * - If noise = -9.0: finalDensity = 7.5 + (-9.0 * 0.9) = -0.6 (becomes air = cave!)
     *
     * @param baseDensity Base density from heightmap
     * @param noise       3D noise value (typically -1 to 1)
     * @param influence   Noise influence factor (0.1 to 0.9)
     * @return Final combined density (>0 = solid, <0 = air)
     */
    public static float combineDensities(float baseDensity, float noise, float influence) {
        return baseDensity + (noise * influence);
    }

    /**
     * Complete density calculation with automatic depth-weighting.
     *
     * Convenience method that combines all steps:
     * 1. Calculate base density from depth
     * 2. Calculate noise influence from depth
     * 3. Combine using depth-weighted mixing
     *
     * @param y             Block Y coordinate
     * @param surfaceHeight Surface height at this (x, z)
     * @param noise         3D noise value (typically -1 to 1)
     * @return Final density (>0 = solid, <0 = air)
     */
    public static float calculateFinalDensity(int y, int surfaceHeight, float noise) {
        float baseDensity = calculateBaseDensity(y, surfaceHeight);
        float influence = calculateNoiseInfluence(y, surfaceHeight);
        return combineDensities(baseDensity, noise, influence);
    }

    /**
     * Checks if a position is within range for surface features (overhangs, arches).
     *
     * Surface features should only occur very close to the surface:
     * - Within 5 blocks below surface (cave entrances, cliff bases)
     * - Within 3 blocks above surface (overhangs, arch tops)
     *
     * @param y             Block Y coordinate
     * @param surfaceHeight Surface height at this (x, z)
     * @param rangeBelow    How many blocks below surface to allow (e.g., 5)
     * @param rangeAbove    How many blocks above surface to allow (e.g., 3)
     * @return true if in surface feature range
     */
    public static boolean isInSurfaceRange(int y, int surfaceHeight, int rangeBelow, int rangeAbove) {
        return y >= surfaceHeight - rangeBelow && y <= surfaceHeight + rangeAbove;
    }

    /**
     * Checks if a position is deep enough for underground features (caves).
     *
     * Underground features should only occur well below surface to avoid
     * creating surface holes.
     *
     * @param y                Block Y coordinate
     * @param surfaceHeight    Surface height at this (x, z)
     * @param minimumDepth     Minimum blocks below surface (e.g., 10)
     * @return true if deep enough for underground features
     */
    public static boolean isUnderground(int y, int surfaceHeight, int minimumDepth) {
        return y < surfaceHeight - minimumDepth;
    }

    /**
     * Clamps a value between min and max.
     *
     * @param value Value to clamp
     * @param min   Minimum value
     * @param max   Maximum value
     * @return Clamped value
     */
    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
