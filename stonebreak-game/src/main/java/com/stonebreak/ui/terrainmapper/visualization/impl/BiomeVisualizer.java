package com.stonebreak.ui.terrainmapper.visualization.impl;

import com.stonebreak.ui.terrainmapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Visualizes biome distribution using the multi-noise biome selection system.
 *
 * This visualizer shows which biome would generate at each position based on
 * the 6 noise parameters (continentalness, erosion, PV, weirdness, temperature, humidity).
 *
 * Sampling Process:
 * 1. Sample all 6 parameters via NoiseRouter
 * 2. Select biome via BiomeParameterTable lookup
 * 3. Return biome ordinal (0-12 for 13 biomes)
 *
 * Visualization Modes:
 * - Grayscale: Each biome ID mapped to gray value (0=black, 9=white)
 * - Color-Coded: Each biome rendered in its characteristic color
 *
 * Thread Safety: Safe for concurrent use (BiomeManager is thread-safe)
 */
public class BiomeVisualizer implements NoiseVisualizer {

    private final BiomeManager biomeManager;
    private static final int NUM_BIOMES = BiomeType.values().length;

    /**
     * Creates a new biome visualizer.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public BiomeVisualizer(long seed, TerrainGenerationConfig config) {
        this.biomeManager = new BiomeManager(seed, config);
    }

    /**
     * Samples biome at the given world position.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param seed World seed (unused - seed already set in constructor)
     * @return Biome ordinal (0 to NUM_BIOMES-1)
     */
    @Override
    public double sample(int worldX, int worldZ, long seed) {
        // Sample parameters and select biome
        MultiNoiseParameters params = biomeManager.getParameters(worldX, worldZ, WorldConfiguration.SEA_LEVEL);
        BiomeType biome = biomeManager.getBiomeAtHeight(worldX, worldZ, WorldConfiguration.SEA_LEVEL);

        // Return biome ordinal as double
        return biome.ordinal();
    }

    /**
     * Gets the display name for this visualizer.
     *
     * @return "Biome Distribution"
     */
    @Override
    public String getName() {
        return "Biome Distribution";
    }

    /**
     * Gets the minimum possible biome ordinal.
     *
     * @return 0.0 (first biome)
     */
    @Override
    public double getMinValue() {
        return 0.0;
    }

    /**
     * Gets the maximum possible biome ordinal.
     *
     * @return NUM_BIOMES - 1 (last biome)
     */
    @Override
    public double getMaxValue() {
        return NUM_BIOMES - 1;
    }

    /**
     * Gets the characteristic color for a biome type.
     *
     * Colors chosen to match the biome's visual appearance:
     * - Desert: Sandy yellow
     * - Plains: Grass green
     * - Snowy: White
     * - Ocean: Blue
     * etc.
     *
     * @param biome The biome type
     * @return RGB color array [r, g, b] in range [0, 255]
     */
    public int[] getBiomeColor(BiomeType biome) {
        return switch (biome) {
            case PLAINS -> new int[]{141, 182, 0};         // Grass green
            case DESERT -> new int[]{237, 201, 175};       // Sandy yellow
            case RED_SAND_DESERT -> new int[]{200, 100, 50}; // Red-orange
            case SNOWY_PLAINS -> new int[]{255, 255, 255}; // White
            case TUNDRA -> new int[]{180, 220, 220};       // Light blue-gray
            case TAIGA -> new int[]{11, 102, 89};          // Dark green
            case STONY_PEAKS -> new int[]{128, 128, 128};  // Gray
            case GRAVEL_BEACH -> new int[]{162, 162, 132}; // Tan
            case ICE_FIELDS -> new int[]{160, 200, 255};   // Ice blue
            case BADLANDS -> new int[]{150, 100, 80};      // Brown
            case OCEAN -> new int[]{0, 105, 148};          // Ocean blue
            case DEEP_OCEAN -> new int[]{0, 60, 100};      // Deep ocean blue
            case FROZEN_OCEAN -> new int[]{112, 160, 200}; // Frozen ocean blue-gray
        };
    }

    /**
     * Gets the user-friendly name for a biome type.
     *
     * Converts enum name to title case with spaces:
     * - RED_SAND_DESERT → "Red Sand Desert"
     * - STONY_PEAKS → "Stony Peaks"
     *
     * @param biome The biome type
     * @return Formatted biome name
     */
    public String getBiomeName(BiomeType biome) {
        // Convert enum name to title case with spaces
        String name = biome.name().replace('_', ' ');
        String[] words = name.split(" ");
        StringBuilder result = new StringBuilder();

        for (String word : words) {
            if (result.length() > 0) {
                result.append(" ");
            }
            // Capitalize first letter, lowercase the rest
            result.append(word.charAt(0))
                  .append(word.substring(1).toLowerCase());
        }

        return result.toString();
    }

    /**
     * Gets the biome type from a sampled value.
     *
     * Converts the ordinal value back to a BiomeType for color mapping.
     *
     * @param value Sampled value (biome ordinal)
     * @return BiomeType corresponding to the ordinal
     */
    public BiomeType getBiomeFromValue(double value) {
        int ordinal = (int) Math.round(value);
        ordinal = Math.max(0, Math.min(NUM_BIOMES - 1, ordinal)); // Clamp to valid range
        return BiomeType.values()[ordinal];
    }
}
