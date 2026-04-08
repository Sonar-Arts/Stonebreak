package com.stonebreak.world.generation.caves;

import com.stonebreak.world.generation.config.NoiseConfig;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.noise.Noise3D;
/**
 * Generates ridged noise-based worm (spaghetti) caves.
 *
 * Uses two intersecting ridged noise functions to create long, winding tunnel
 * systems. The intersection of two 2D surfaces in 3D space creates 1D paths (tunnels).
 *
 * Key Innovation: Terrain-Independent Caves
 * Unlike the old approach where terrain features remove blocks, this generator
 * produces a density value that gets subtracted from terrain density during
 * initial generation, creating caves as an integral part of the terrain.
 *
 * Depth Control:
 * Cave density is modulated by depth to prevent surface holes:
 * - Near surface (Y > 70): Fading cave influence
 * - Mid-depth (Y 10-70): Moderate caves
 * - Deep underground (Y < 10): Full cave systems
 *
 * Thread Safety:
 * - Immutable after construction
 * - Noise generators are thread-safe
 * - Safe for concurrent sampling from multiple chunk generation threads
 */
public class CaveNoiseGenerator {

    private final Noise3D spaghettiNoise1;
    private final Noise3D spaghettiNoise2;

    // Spaghetti cave parameters (tunnel networks)
    private static final float SPAGHETTI_SCALE = 50.0f;  // Medium scale for tunnels
    private static final float SPAGHETTI_THRESHOLD = 0.20f;  // Threshold for tunnel formation

    // Altitude adjustment parameters
    private static final int SURFACE_FADE_DEPTH = 20;  // Fade caves over this many blocks below the surface
    private static final int DEEP_CAVE_START = 10;  // Full cave systems below this Y
    // Weirdness thresholds for surface cave openings
    private static final float WEIRDNESS_OPENING_THRESHOLD = 0.4f;
    private static final float WEIRDNESS_OPENING_MAX = 0.9f;

    /**
     * Creates a new cave noise generator with the given seed.
     *
     * @param seed World seed for deterministic generation
     */
    public CaveNoiseGenerator(long seed) {
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
     * This value should be SUBTRACTED from terrain density:
     * finalDensity = terrainDensity - caveDensity
     *
     * @param worldX World X coordinate
     * @param y World Y coordinate
     * @param worldZ World Z coordinate
     * @param surfaceHeight Surface height at this XZ position (for depth calculation)
     * @return Cave density value (0.0 = no cave, higher = more cave)
     */
    public float sampleCaveDensity(int worldX, int y, int worldZ, int surfaceHeight, float weirdness) {
        float combinedDensity = sampleSpaghettiCaves(worldX, y, worldZ);
        float altitudeFactor = calculateAltitudeFactor(y, surfaceHeight, weirdness);

        return Math.max(0.0f, combinedDensity * altitudeFactor);
    }

    /**
     * Samples spaghetti cave noise for long tunnel networks.
     *
     * Algorithm:
     * 1. Sample two independent 3D noise functions
     * 2. Take absolute value of each (creates ridged surfaces)
     * 3. Add the absolute values together
     * 4. Caves form where BOTH values are small (intersection creates 1D paths)
     * 5. Apply threshold to control tunnel width
     */
    private float sampleSpaghettiCaves(int worldX, int y, int worldZ) {
        float noise1 = spaghettiNoise1.getDensity(worldX, y, worldZ, SPAGHETTI_SCALE);
        float noise2 = spaghettiNoise2.getDensity(worldX, y, worldZ, SPAGHETTI_SCALE);

        float combined = Math.abs(noise1) + Math.abs(noise2);

        return Math.max(0.0f, SPAGHETTI_THRESHOLD - combined);
    }

    /**
     * Calculates altitude-based modulation factor for cave density.
     *
     * <p>The fade zone is surface-relative, not fixed to an absolute Y level.
     * High weirdness shrinks the fade zone, allowing caves to reach the surface.</p>
     *
     * @param y World Y coordinate
     * @param surfaceHeight Surface height at this XZ position (from heightmap)
     * @param weirdness Weirdness noise value [-1, 1]; controls surface opening
     * @return Altitude factor (0.0 = no caves, 1.0 = full caves)
     */
    private float calculateAltitudeFactor(int y, int surfaceHeight, float weirdness) {
        float depthBelowSurface = surfaceHeight - y;

        // Above the heightmap surface: never carve
        if (depthBelowSurface < 0) return 0.0f;

        // Weirdness shrinks the fade zone: at max weirdness the buffer reaches 0
        float openingFactor = calcOpeningFactor(weirdness);
        float effectiveFadeDepth = SURFACE_FADE_DEPTH * (1.0f - openingFactor);

        // Within the surface fade zone: smooth approach to surface
        if (depthBelowSurface < effectiveFadeDepth) {
            float t = depthBelowSurface / Math.max(1.0f, effectiveFadeDepth);
            return smoothstep(t);
        }

        // Deep caves: full strength
        if (y < DEEP_CAVE_START) {
            return 1.0f;
        }

        // Transition zone between deep caves and surface fade
        int fadeStart = surfaceHeight - SURFACE_FADE_DEPTH;
        float transitionRange = fadeStart - DEEP_CAVE_START;
        if (transitionRange <= 0) return 1.0f;
        float transitionAmount = (y - DEEP_CAVE_START) / transitionRange;
        return 1.0f - (smoothstep(transitionAmount) * 0.4f);
    }

    /**
     * Returns how much the surface fade buffer is reduced based on weirdness.
     * 0.0 = no reduction (full buffer), 1.0 = buffer removed (cave can reach surface).
     */
    private static float calcOpeningFactor(float weirdness) {
        if (weirdness < WEIRDNESS_OPENING_THRESHOLD) return 0.0f;
        float t = (weirdness - WEIRDNESS_OPENING_THRESHOLD) / (WEIRDNESS_OPENING_MAX - WEIRDNESS_OPENING_THRESHOLD);
        t = Math.min(1.0f, t);
        return t * t * (3 - 2 * t); // smoothstep
    }

    private float smoothstep(float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * Checks if caves should generate at this Y level.
     *
     * @param y World Y coordinate
     * @return true if caves can potentially generate at this Y level
     */
    public boolean canGenerateCaves(int y) {
        return y > 0;
    }
}
