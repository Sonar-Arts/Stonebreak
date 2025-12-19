package com.stonebreak.world.generation.caves;

import com.stonebreak.world.generation.config.NoiseConfig;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.noise.Noise3D;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Generates ridged noise-based caves inspired by Minecraft 1.18+ cave system.
 *
 * This class implements the modern approach to cave generation using ridged noise
 * functions to create natural-looking cave systems with multiple types:
 * - Cheese caves: Large caverns with dramatic vertical space and pillars
 * - Spaghetti caves: Long winding tunnel networks (to be added)
 * - Noodle caves: Thin claustrophobic passages (future)
 *
 * Architecture:
 * The generator uses 3D ridged noise where absolute values create 2D surfaces
 * winding through 3D space. Where these surfaces have low values, caves form.
 *
 * Key Innovation: Terrain-Independent Caves
 * Unlike the old approach where terrain features remove blocks, this generator
 * produces a density value that gets subtracted from terrain density during
 * initial generation, creating caves as an integral part of the terrain.
 *
 * Depth Control:
 * Cave density is modulated by depth to prevent surface holes:
 * - Near surface (Y > 100): Very weak cave influence
 * - Mid-depth (Y 40-100): Moderate caves with entrances
 * - Deep underground (Y < 40): Full cave systems
 *
 * Thread Safety:
 * - Immutable after construction
 * - Noise generators are thread-safe
 * - Safe for concurrent sampling from multiple chunk generation threads
 *
 * Follows SOLID Principles:
 * - Single Responsibility: Only generates cave density values
 * - Dependency Inversion: Configuration injected via constructor
 */
public class CaveNoiseGenerator {

    private final Noise3D cheeseNoise;
    private final Noise3D spaghettiNoise1;
    private final Noise3D spaghettiNoise2;
    private final int seaLevel;

    // Cheese cave parameters (large caverns)
    private static final float CHEESE_SCALE = 80.0f;  // Large scale for spacious caverns
    private static final float CHEESE_THRESHOLD = 0.35f;  // How much of space becomes caves

    // Spaghetti cave parameters (tunnel networks)
    private static final float SPAGHETTI_SCALE = 50.0f;  // Medium scale for tunnels
    private static final float SPAGHETTI_THRESHOLD = 0.15f;  // Threshold for tunnel formation

    // Altitude adjustment parameters
    private static final int SURFACE_FADE_START = 70;  // Start reducing caves above this Y
    private static final int DEEP_CAVE_START = 10;  // Full cave systems below this Y
    private static final int MIN_CAVE_DEPTH = 10;  // No caves within 10 blocks of surface

    /**
     * Creates a new cave noise generator with the given seed.
     *
     * Initializes noise generators for different cave types:
     * - Cheese caves: Large spacious caverns
     * - Spaghetti caves: Long winding tunnel networks
     *
     * Each type uses independent noise with different seed offsets to
     * ensure varied, non-repeating cave patterns.
     *
     * @param seed World seed for deterministic generation
     */
    public CaveNoiseGenerator(long seed) {
        this.seaLevel = WorldConfiguration.SEA_LEVEL;

        // Initialize cheese cave noise with large-scale configuration
        NoiseConfig cheeseConfig = NoiseConfigFactory.forCaveSystems();
        this.cheeseNoise = new Noise3D(seed + 200, cheeseConfig);

        // Initialize two independent spaghetti noise generators
        // Intersecting these creates long tunnel networks
        NoiseConfig spaghettiConfig = NoiseConfigFactory.forCaveSystems();
        this.spaghettiNoise1 = new Noise3D(seed + 300, spaghettiConfig);
        this.spaghettiNoise2 = new Noise3D(seed + 400, spaghettiConfig);
    }

    /**
     * Samples cave density at a 3D position.
     *
     * Returns a density value that represents how much the caves should
     * "carve out" terrain at this position. Higher values = more carving.
     *
     * The density is modulated by depth to prevent surface holes while
     * ensuring full cave systems deep underground.
     *
     * Integration:
     * This value should be SUBTRACTED from terrain density:
     * finalDensity = terrainDensity - caveDensity
     *
     * @param worldX World X coordinate
     * @param y World Y coordinate
     * @param worldZ World Z coordinate
     * @param surfaceHeight Surface height at this XZ position (for depth calculation)
     * @return Cave density value (0.0 = no cave, higher = more cave)
     */
    public float sampleCaveDensity(int worldX, int y, int worldZ, int surfaceHeight) {
        // Calculate depth below surface
        float depthBelowSurface = Math.max(0, surfaceHeight - y);

        // Don't generate caves too close to surface
        if (depthBelowSurface < MIN_CAVE_DEPTH) {
            return 0.0f;
        }

        // Sample both cave types
        float cheeseDensity = sampleCheeseCaves(worldX, y, worldZ);
        float spaghettiDensity = sampleSpaghettiCaves(worldX, y, worldZ);

        // Combine cave types using maximum (union of cave spaces)
        // This allows both large caverns AND tunnel networks
        float combinedDensity = Math.max(cheeseDensity, spaghettiDensity);

        // Calculate altitude factor (reduces caves near surface)
        float altitudeFactor = calculateAltitudeFactor(y, surfaceHeight);

        // Apply altitude modulation
        float finalCaveDensity = combinedDensity * altitudeFactor;

        return Math.max(0.0f, finalCaveDensity);
    }

    /**
     * Samples cheese cave noise for large caverns.
     *
     * Cheese caves use ridged noise (absolute value) to create large
     * open caverns with dramatic vertical space and natural pillars.
     * The name comes from the swiss-cheese appearance of the terrain.
     *
     * Algorithm:
     * 1. Sample 3D noise at scaled coordinates
     * 2. Take absolute value to create ridged surface
     * 3. Invert so caves form where ridges are weak
     * 4. Apply threshold to control cave frequency
     *
     * @param worldX World X coordinate
     * @param y World Y coordinate
     * @param worldZ World Z coordinate
     * @return Cheese cave density (0.0 = solid, positive = cave)
     */
    private float sampleCheeseCaves(int worldX, int y, int worldZ) {
        // Sample 3D noise with large scale for spacious features
        float noise = cheeseNoise.getDensity(worldX, y, worldZ, CHEESE_SCALE);

        // Create ridged noise by taking absolute value
        // This creates 2D surfaces winding through 3D space
        float ridged = Math.abs(noise);

        // Invert: caves form where ridges are weak (low absolute values)
        // Subtract threshold to control how much space becomes caves
        float caveDensity = CHEESE_THRESHOLD - ridged;

        // Only positive values represent caves
        return Math.max(0.0f, caveDensity);
    }

    /**
     * Samples spaghetti cave noise for long tunnel networks.
     *
     * Spaghetti caves use two intersecting ridged noise functions to create
     * long, winding tunnel systems. The intersection of two 2D surfaces in
     * 3D space creates 1D paths (tunnels).
     *
     * Algorithm:
     * 1. Sample two independent 3D noise functions
     * 2. Take absolute value of each (creates ridged surfaces)
     * 3. Add the absolute values together
     * 4. Caves form where BOTH values are small (intersection)
     * 5. Apply threshold to control tunnel width
     *
     * @param worldX World X coordinate
     * @param y World Y coordinate
     * @param worldZ World Z coordinate
     * @return Spaghetti cave density (0.0 = solid, positive = tunnel)
     */
    private float sampleSpaghettiCaves(int worldX, int y, int worldZ) {
        // Sample two independent 3D noise functions
        float noise1 = spaghettiNoise1.getDensity(worldX, y, worldZ, SPAGHETTI_SCALE);
        float noise2 = spaghettiNoise2.getDensity(worldX, y, worldZ, SPAGHETTI_SCALE);

        // Create ridged noise by taking absolute values
        float ridged1 = Math.abs(noise1);
        float ridged2 = Math.abs(noise2);

        // Tunnels form where BOTH ridges are weak (intersection creates 1D paths)
        // Add the absolute values - tunnels only where sum is very small
        float combined = ridged1 + ridged2;

        // Invert: caves form where combined ridges are weak
        float caveDensity = SPAGHETTI_THRESHOLD - combined;

        // Only positive values represent tunnels
        return Math.max(0.0f, caveDensity);
    }

    /**
     * Calculates altitude-based modulation factor for cave density.
     *
     * This prevents caves from creating random surface holes while
     * ensuring full cave systems deep underground.
     *
     * Behavior:
     * - Y > SURFACE_FADE_START (100): Rapidly fade out caves
     * - Y = DEEP_CAVE_START (40): Full cave strength
     * - Y < DEEP_CAVE_START: Maximum cave strength
     *
     * Uses smooth interpolation to avoid harsh transitions.
     *
     * @param y World Y coordinate
     * @param surfaceHeight Surface height at this XZ position
     * @return Altitude factor (0.0 = no caves, 1.0 = full caves)
     */
    private float calculateAltitudeFactor(int y, int surfaceHeight) {
        // If above surface fade start, reduce cave influence
        if (y > SURFACE_FADE_START) {
            float fadeRange = surfaceHeight - SURFACE_FADE_START;
            if (fadeRange <= 0) return 0.0f;

            float fadeAmount = (surfaceHeight - y) / fadeRange;
            return Math.max(0.0f, Math.min(1.0f, fadeAmount));
        }

        // If below deep cave start, full strength
        if (y < DEEP_CAVE_START) {
            return 1.0f;
        }

        // Transition zone: interpolate from full strength (deep) to moderate (surface)
        float transitionRange = SURFACE_FADE_START - DEEP_CAVE_START;
        float transitionAmount = (y - DEEP_CAVE_START) / transitionRange;

        // Smooth interpolation using smoothstep
        float smoothed = smoothstep(transitionAmount);

        // Interpolate from full strength (1.0) at deep to reduced (0.6) at surface
        return 1.0f - (smoothed * 0.4f);
    }

    /**
     * Smooth interpolation function (smoothstep).
     *
     * Provides smooth transition between 0 and 1 with zero derivative
     * at both endpoints, preventing harsh visual transitions.
     *
     * Formula: 3x² - 2x³
     *
     * @param t Input value (should be in range [0, 1])
     * @return Smoothed value in range [0, 1]
     */
    private float smoothstep(float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * Checks if caves should generate at this Y level.
     *
     * Simple helper to quickly reject positions that shouldn't have caves
     * without expensive noise sampling.
     *
     * @param y World Y coordinate
     * @return true if caves can potentially generate at this Y level
     */
    public boolean canGenerateCaves(int y) {
        return y < SURFACE_FADE_START && y > 0;
    }

    /**
     * Gets the minimum depth below surface where caves can generate.
     *
     * @return Minimum depth in blocks
     */
    public int getMinCaveDepth() {
        return MIN_CAVE_DEPTH;
    }
}
