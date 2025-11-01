package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfig;

/**
 * Adds organic waviness to Voronoi biome boundaries to hide cellular patterns.
 *
 * <p>This class applies low-frequency noise distortion to world coordinates before
 * Voronoi lookup, creating natural, flowing biome boundaries instead of straight
 * geometric lines.</p>
 *
 * <h3>Problem</h3>
 * <p>Standard Voronoi diagrams create straight-line boundaries between cells, resulting in
 * obvious geometric/cellular patterns that look artificial.</p>
 *
 * <h3>Solution</h3>
 * <p>Apply smooth, low-frequency noise to offset coordinates before Voronoi lookup.
 * This makes boundaries wavy and organic while preserving large biome regions.</p>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Sample X distortion noise at (worldX * scale, worldZ * scale)</li>
 *   <li>Sample Z distortion noise at (worldX * scale, worldZ * scale)</li>
 *   <li>Apply offsets: distortedX = worldX + offsetX * strength</li>
 *   <li>Apply offsets: distortedZ = worldZ + offsetZ * strength</li>
 *   <li>Use distorted coordinates for Voronoi cell lookup</li>
 * </ol>
 *
 * <h3>Configuration Guidelines</h3>
 * <ul>
 *   <li><strong>Strength</strong>: 20-30 blocks typical (< 50% of cell size to preserve regions)</li>
 *   <li><strong>Scale</strong>: 0.01-0.02 typical (low frequency for smooth waviness)</li>
 *   <li><strong>Effect</strong>: Higher strength = more wavy, lower = straighter</li>
 * </ul>
 *
 * <h3>Performance</h3>
 * <p>Adds only 2 noise samples per biome query (minimal overhead ~5%).</p>
 *
 * @author Stonebreak Team
 * @see BiomeVoronoiGrid
 */
public class VoronoiDistortionField {

    private final NoiseGenerator distortionNoiseX;
    private final NoiseGenerator distortionNoiseZ;
    private final float distortionStrength;  // blocks (20-30 typical)
    private final float distortionScale;     // frequency (0.01-0.02 typical)

    /**
     * Creates a new Voronoi distortion field.
     *
     * @param seed World seed for deterministic distortion
     * @param strength Distortion strength in blocks (20-30 recommended)
     * @param scale Distortion frequency (0.01-0.02 recommended for smooth waviness)
     */
    public VoronoiDistortionField(long seed, float strength, float scale) {
        if (strength < 0) {
            throw new IllegalArgumentException("Distortion strength must be non-negative, got: " + strength);
        }
        if (scale <= 0) {
            throw new IllegalArgumentException("Distortion scale must be positive, got: " + scale);
        }

        this.distortionStrength = strength;
        this.distortionScale = scale;

        // Create separate noise generators for X and Z offsets
        // Different seeds ensure independence between X and Z distortion
        NoiseConfig distortionConfig = new NoiseConfig(3, 0.5, 2.0);  // Low octaves for smooth distortion
        this.distortionNoiseX = new NoiseGenerator(seed + 1000, distortionConfig);
        this.distortionNoiseZ = new NoiseGenerator(seed + 2000, distortionConfig);
    }

    /**
     * Apply distortion to world coordinates before Voronoi lookup.
     *
     * <p>This method offsets the input coordinates using low-frequency noise,
     * creating organic, wavy boundaries when used for Voronoi cell lookup.</p>
     *
     * <h4>Example Usage</h4>
     * <pre>{@code
     * VoronoiDistortionField distortion = new VoronoiDistortionField(seed, 25.0f, 0.015f);
     * DistortedPosition distorted = distortion.getDistortedPosition(worldX, worldZ);
     * // Use distorted.distortedX and distorted.distortedZ for Voronoi lookup
     * }</pre>
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Distorted position with offset amounts
     */
    public DistortedPosition getDistortedPosition(int worldX, int worldZ) {
        // Sample distortion noise at lower frequency for smooth waviness
        // Scale converts block coordinates to noise space
        float noiseX = worldX * distortionScale;
        float noiseZ = worldZ * distortionScale;

        // Sample X and Z offsets independently
        float offsetX = distortionNoiseX.noise(noiseX, noiseZ) * distortionStrength;
        float offsetZ = distortionNoiseZ.noise(noiseX, noiseZ) * distortionStrength;

        // Apply distortion to world coordinates
        int distortedX = worldX + Math.round(offsetX);
        int distortedZ = worldZ + Math.round(offsetZ);

        return new DistortedPosition(distortedX, distortedZ, offsetX, offsetZ);
    }

    /**
     * Gets the distortion strength in blocks.
     *
     * @return Distortion strength (20-30 typical)
     */
    public float getDistortionStrength() {
        return distortionStrength;
    }

    /**
     * Gets the distortion frequency scale.
     *
     * @return Distortion scale (0.01-0.02 typical)
     */
    public float getDistortionScale() {
        return distortionScale;
    }

    /**
     * Container for distorted position and offset amounts.
     *
     * <p>Includes both the distorted coordinates (for Voronoi lookup) and the
     * raw offset amounts (for debugging/analysis).</p>
     */
    public static class DistortedPosition {
        /**
         * Distorted X coordinate (use for Voronoi lookup).
         */
        public final int distortedX;

        /**
         * Distorted Z coordinate (use for Voronoi lookup).
         */
        public final int distortedZ;

        /**
         * Raw X offset in blocks (distortedX = originalX + offsetX).
         */
        public final float offsetX;

        /**
         * Raw Z offset in blocks (distortedZ = originalZ + offsetZ).
         */
        public final float offsetZ;

        /**
         * Creates a new distorted position.
         *
         * @param distortedX Distorted X coordinate
         * @param distortedZ Distorted Z coordinate
         * @param offsetX Raw X offset amount
         * @param offsetZ Raw Z offset amount
         */
        public DistortedPosition(int distortedX, int distortedZ, float offsetX, float offsetZ) {
            this.distortedX = distortedX;
            this.distortedZ = distortedZ;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
        }

        @Override
        public String toString() {
            return "DistortedPosition{" +
                    "distortedX=" + distortedX +
                    ", distortedZ=" + distortedZ +
                    ", offsetX=" + offsetX +
                    ", offsetZ=" + offsetZ +
                    '}';
        }
    }
}
