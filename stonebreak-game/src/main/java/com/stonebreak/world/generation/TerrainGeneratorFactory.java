package com.stonebreak.world.generation;

import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.legacy.LegacyTerrainGenerator;
import com.stonebreak.world.generation.spline.SplineTerrainGenerator;

/**
 * Factory for creating terrain generator instances.
 * <p>
 * Provides centralized creation logic for all terrain generation systems,
 * ensuring proper initialization and graceful fallback to LEGACY generator
 * for unknown or invalid types.
 */
public class TerrainGeneratorFactory {

    /**
     * Create a terrain generator of the specified type.
     *
     * @param type The generator type to create
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     * @param use3DDensity Whether to use 3D density functions (only for SPLINE generator)
     * @return A terrain generator instance
     * @throws NullPointerException if type or config is null
     */
    public static TerrainGenerator create(TerrainGeneratorType type, long seed, TerrainGenerationConfig config, boolean use3DDensity) {
        if (type == null) {
            throw new NullPointerException("Generator type cannot be null");
        }
        if (config == null) {
            throw new NullPointerException("Config cannot be null");
        }

        return switch(type) {
            case LEGACY -> new LegacyTerrainGenerator(seed, config);
            case SPLINE -> new SplineTerrainGenerator(seed, config, use3DDensity);
        };
    }

    /**
     * Create a terrain generator from a string type identifier.
     * <p>
     * This method is useful for loading generator type from world metadata,
     * where the type is stored as a string. If the string is null, empty,
     * or doesn't match a valid generator type, it defaults to LEGACY.
     *
     * @param typeString String representation of generator type (e.g., "LEGACY", "SPLINE")
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     * @param use3DDensity Whether to use 3D density functions (only for SPLINE generator)
     * @return A terrain generator instance (defaults to LEGACY if type is invalid)
     * @throws NullPointerException if config is null
     */
    public static TerrainGenerator createFromString(String typeString, long seed, TerrainGenerationConfig config, boolean use3DDensity) {
        if (config == null) {
            throw new NullPointerException("Config cannot be null");
        }

        // Default to LEGACY for null or empty strings (backwards compatibility)
        if (typeString == null || typeString.isEmpty()) {
            return create(TerrainGeneratorType.LEGACY, seed, config, use3DDensity);
        }

        // Try to parse the string as an enum value
        try {
            TerrainGeneratorType type = TerrainGeneratorType.valueOf(typeString.toUpperCase());
            return create(type, seed, config, use3DDensity);
        } catch (IllegalArgumentException e) {
            // Unknown generator type, default to LEGACY for safety
            System.err.println("Warning: Unknown generator type '" + typeString + "', defaulting to LEGACY");
            return create(TerrainGeneratorType.LEGACY, seed, config, use3DDensity);
        }
    }
}
