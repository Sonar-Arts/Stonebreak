package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.config.BiomeNoiseConfig;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.config.TerrainNoiseWeights;

/**
 * Combines multiple noise types to shape terrain based on biome characteristics.
 *
 * This class orchestrates the application of various noise generators (Peaks & Valleys,
 * Ridged, Weirdness) to create distinct terrain for each biome type. It acts as the
 * central noise combination system in the terrain generation pipeline.
 *
 * Noise Combination Flow:
 * 1. Get biome-specific noise weights
 * 2. Sample each noise type (PV, Ridged, Weirdness)
 * 3. Scale by biome-specific strengths
 * 4. Combine additively
 * 5. Apply total amplitude multiplier
 * 6. Add to base height
 *
 * Architecture:
 * - Follows Strategy Pattern: Different noise combinations per biome
 * - Follows Dependency Injection: Noise generators injected via constructor
 * - Follows Single Responsibility: Only handles noise combination logic
 *
 * Integration:
 * Used by HeightMapGenerator during terrain height calculation, after base
 * continentalness height is determined but before erosion is applied.
 */
public class TerrainShaper {

    private final PeaksValleysNoiseGenerator pvNoise;
    private final RidgedNoiseGenerator ridgedNoise;
    private final WeirdnessNoiseGenerator weirdnessNoise;
    private final TerrainNoiseWeights noiseWeights;

    /**
     * Creates a new terrain shaper with the specified noise generators.
     *
     * @param seed World seed for deterministic generation
     */
    public TerrainShaper(long seed) {
        this.pvNoise = new PeaksValleysNoiseGenerator(seed + 11, NoiseConfigFactory.terrainPeaksValleys());
        this.ridgedNoise = new RidgedNoiseGenerator(seed + 10, NoiseConfigFactory.ridged());
        this.weirdnessNoise = new WeirdnessNoiseGenerator(seed + 12, NoiseConfigFactory.terrainWeirdness());
        this.noiseWeights = new TerrainNoiseWeights();
    }

    /**
     * Creates a terrain shaper with custom noise generators and weights.
     *
     * Useful for testing or custom terrain generation systems.
     *
     * @param pvNoise       Peaks & Valleys noise generator
     * @param ridgedNoise   Ridged noise generator
     * @param weirdnessNoise Weirdness noise generator
     * @param noiseWeights  Biome-specific noise weight configuration
     */
    public TerrainShaper(
        PeaksValleysNoiseGenerator pvNoise,
        RidgedNoiseGenerator ridgedNoise,
        WeirdnessNoiseGenerator weirdnessNoise,
        TerrainNoiseWeights noiseWeights
    ) {
        this.pvNoise = pvNoise;
        this.ridgedNoise = ridgedNoise;
        this.weirdnessNoise = weirdnessNoise;
        this.noiseWeights = noiseWeights;
    }

    /**
     * Shapes terrain height based on biome-specific noise combination.
     *
     * Takes a base height (from continentalness) and applies biome-specific
     * noise modifiers to create varied terrain.
     *
     * @param baseHeight Base terrain height from continentalness
     * @param biome      Biome type at this location
     * @param worldX     World X coordinate
     * @param worldZ     World Z coordinate
     * @return Shaped height with noise modifiers applied
     */
    public int shapeHeight(int baseHeight, BiomeType biome, int worldX, int worldZ) {
        // Get biome-specific configuration
        BiomeNoiseConfig config = noiseWeights.getConfig(biome);

        // SMOOTHING FIX: Increase noise scale for smoother, broader features
        // Changed from amplitude * 3 to amplitude * 10 (research-based)
        // Results in 200-600 block scales instead of 50-120
        float noiseScale = Math.max(200.0f, config.getTotalAmplitude() * 10.0f);

        // Sample each noise type at larger scales
        float pvValue = pvNoise.noise(worldX / noiseScale, worldZ / noiseScale);
        float ridgedValue = ridgedNoise.noise(worldX / noiseScale, worldZ / noiseScale);
        float weirdValue = weirdnessNoise.noise(worldX / noiseScale, worldZ / noiseScale);

        // SMOOTHING FIX: Apply smoothstep to each noise value for gentler transitions
        pvValue = NoiseSmoothing.smoothstep(pvValue);
        ridgedValue = NoiseSmoothing.smoothstep(ridgedValue);
        weirdValue = NoiseSmoothing.smoothstep(weirdValue);

        // Apply biome-specific weights to each noise type
        float weightedPV = pvValue * config.getPeaksValleysStrength();
        float weightedRidged = ridgedValue * config.getRidgedStrength();
        float weightedWeird = weirdValue * config.getWeirdnessStrength();

        // Combine all noise contributions additively
        // Each noise is in range [-1, 1], weighted average is also [-1, 1]
        float totalNoise = weightedPV + weightedRidged + weightedWeird;

        // Normalize by sum of weights to keep in [-1, 1] range
        float totalWeight = config.getPeaksValleysStrength() +
                          config.getRidgedStrength() +
                          config.getWeirdnessStrength();

        if (totalWeight > 0) {
            totalNoise /= totalWeight;
        }

        // Apply total amplitude to convert to block height delta
        int heightDelta = Math.round(totalNoise * config.getTotalAmplitude());

        // Add to base height
        return baseHeight + heightDelta;
    }

    /**
     * Shapes height with altitude-based noise scaling.
     *
     * Mountains get more dramatic noise effects, lowlands get gentler effects.
     * This creates more realistic terrain where high areas are rougher.
     *
     * @param baseHeight Base terrain height
     * @param biome      Biome type
     * @param worldX     World X coordinate
     * @param worldZ     World Z coordinate
     * @param seaLevel   Sea level height for altitude calculation
     * @return Shaped height with altitude-scaled noise
     */
    public int shapeHeightWithAltitudeScaling(int baseHeight, BiomeType biome, int worldX, int worldZ, int seaLevel) {
        BiomeNoiseConfig config = noiseWeights.getConfig(biome);

        // Calculate altitude factor (0.0 at sea level, 1.0 at high altitude)
        float altitudeFactor = Math.max(0.0f, (baseHeight - seaLevel) / 100.0f);
        altitudeFactor = Math.min(1.0f, altitudeFactor);

        // SMOOTHING FIX: Larger noise scale for smoother features
        float noiseScale = Math.max(200.0f, config.getTotalAmplitude() * 10.0f);

        // Sample noise at larger scales
        float pvValue = pvNoise.noise(worldX / noiseScale, worldZ / noiseScale);
        float ridgedValue = ridgedNoise.noise(worldX / noiseScale, worldZ / noiseScale);
        float weirdValue = weirdnessNoise.noise(worldX / noiseScale, worldZ / noiseScale);

        // SMOOTHING FIX: Apply smoothstep for gentler transitions
        pvValue = NoiseSmoothing.smoothstep(pvValue);
        ridgedValue = NoiseSmoothing.smoothstep(ridgedValue);
        weirdValue = NoiseSmoothing.smoothstep(weirdValue);

        // SMOOTHING FIX: Altitude-based fade for smoother lowlands
        // Reduce noise strength at low altitudes
        pvValue = NoiseSmoothing.altitudeFade(pvValue, altitudeFactor);
        ridgedValue = NoiseSmoothing.altitudeFade(ridgedValue, altitudeFactor);

        // Apply altitude scaling: ridged noise only in high areas, PV everywhere
        float weightedPV = pvValue * config.getPeaksValleysStrength();
        float weightedRidged = ridgedValue * config.getRidgedStrength() * altitudeFactor;  // Scale by altitude
        float weightedWeird = weirdValue * config.getWeirdnessStrength();

        float totalNoise = weightedPV + weightedRidged + weightedWeird;

        // Normalize
        float totalWeight = config.getPeaksValleysStrength() +
                          config.getRidgedStrength() * altitudeFactor +
                          config.getWeirdnessStrength();

        if (totalWeight > 0) {
            totalNoise /= totalWeight;
        }

        int heightDelta = Math.round(totalNoise * config.getTotalAmplitude());

        return baseHeight + heightDelta;
    }

    /**
     * Gets the noise contribution without adding to base height.
     *
     * Useful for debugging or analyzing noise effects separately.
     *
     * @param biome  Biome type
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Height delta from noise (can be positive or negative)
     */
    public int getNoiseContribution(BiomeType biome, int worldX, int worldZ) {
        BiomeNoiseConfig config = noiseWeights.getConfig(biome);

        float noiseScale = Math.max(50.0f, config.getTotalAmplitude() * 3.0f);

        float pvValue = pvNoise.noise(worldX / noiseScale, worldZ / noiseScale);
        float ridgedValue = ridgedNoise.noise(worldX / noiseScale, worldZ / noiseScale);
        float weirdValue = weirdnessNoise.noise(worldX / noiseScale, worldZ / noiseScale);

        float weightedPV = pvValue * config.getPeaksValleysStrength();
        float weightedRidged = ridgedValue * config.getRidgedStrength();
        float weightedWeird = weirdValue * config.getWeirdnessStrength();

        float totalNoise = weightedPV + weightedRidged + weightedWeird;

        float totalWeight = config.getPeaksValleysStrength() +
                          config.getRidgedStrength() +
                          config.getWeirdnessStrength();

        if (totalWeight > 0) {
            totalNoise /= totalWeight;
        }

        return Math.round(totalNoise * config.getTotalAmplitude());
    }

    /**
     * Gets the terrain noise weights configuration.
     *
     * @return Terrain noise weights
     */
    public TerrainNoiseWeights getNoiseWeights() {
        return noiseWeights;
    }
}
