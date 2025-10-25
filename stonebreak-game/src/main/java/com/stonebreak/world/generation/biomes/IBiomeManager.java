package com.stonebreak.world.generation.biomes;

/**
 * Interface for biome determination and climate queries.
 *
 * Defines the contract for determining biome types based on temperature
 * and moisture values, with support for altitude-based climate adjustment.
 *
 * Implementations can use different biome classification systems:
 * - Whittaker diagram (current implementation)
 * - Custom biome rules
 * - Real-world climate data
 * - Procedural biome systems
 *
 * Benefits of this interface:
 * - Dependency Inversion: Generators depend on abstraction, not concrete implementation
 * - Testability: Easy to create mock biome managers for unit testing
 * - Flexibility: Can implement different biome classification systems
 * - Modularity: Biome logic isolated from terrain generation
 */
public interface IBiomeManager {

    /**
     * Determines the biome type at sea level based on temperature and moisture.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return The biome type at the given position (at sea level)
     */
    BiomeType getBiome(int x, int z);

    /**
     * Determines the biome type at a specific height with altitude-based temperature adjustment.
     *
     * Higher elevations become colder, potentially changing the biome classification.
     * For example, a hot lowland might become a cold mountain peak at high altitude.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param height Terrain height at this position (affects temperature)
     * @return The biome type at the given position and height
     */
    BiomeType getBiomeAtHeight(int x, int z, int height);

    /**
     * Gets the moisture value at the specified position.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Moisture value in range [0.0, 1.0] (0 = driest, 1 = wettest)
     */
    float getMoisture(int x, int z);

    /**
     * Gets the base temperature value at sea level.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Temperature value in range [0.0, 1.0] (0 = coldest, 1 = hottest) at sea level
     */
    float getTemperature(int x, int z);

    /**
     * Gets the temperature value at a specific height with altitude-based adjustment.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param height Terrain height (affects temperature via altitude chill)
     * @return Temperature value in range [0.0, 1.0] adjusted for altitude
     */
    float getTemperatureAtHeight(int x, int z, int height);
}
