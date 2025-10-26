package com.stonebreak.world.generation.noise;

/**
 * Immutable container for the six climate/terrain parameters used in multi-noise generation.
 *
 * These parameters serve dual purposes:
 * 1. Terrain Generation: Determine height, flatness, peak sharpness
 * 2. Biome Selection: Determine which biome generates at a location
 *
 * Inspired by Minecraft 1.18+ multi-noise system where terrain generates independently
 * from biomes, and biomes adapt to whatever terrain they generate on.
 *
 * Parameters:
 * - continentalness: Inland vs coastal (-1 = deep ocean, 0 = coast, 1 = far inland)
 * - erosion: Flat vs mountainous (-1 = sharp mountains, 1 = flat plains)
 * - peaksValleys: Local height variation (-1 = deep valleys, 1 = sharp peaks)
 * - weirdness: Terrain uniqueness and biome variants (-1 to 1, high values = rare features)
 * - temperature: Cold to hot (0 = frozen, 1 = hot, adjusted for altitude)
 * - humidity: Dry to wet (0 = arid, 1 = humid)
 *
 * Follows immutability pattern for thread safety and predictable behavior.
 */
public class MultiNoiseParameters {

    public final float continentalness;
    public final float erosion;
    public final float peaksValleys;
    public final float weirdness;
    public final float temperature;
    public final float humidity;

    /**
     * Creates a new multi-noise parameter set.
     *
     * @param continentalness Inland vs coastal [-1.0, 1.0]
     * @param erosion Flat vs mountainous [-1.0, 1.0]
     * @param peaksValleys Local height variation [-1.0, 1.0]
     * @param weirdness Terrain uniqueness [-1.0, 1.0]
     * @param temperature Cold to hot [0.0, 1.0]
     * @param humidity Dry to wet [0.0, 1.0]
     */
    public MultiNoiseParameters(
            float continentalness,
            float erosion,
            float peaksValleys,
            float weirdness,
            float temperature,
            float humidity
    ) {
        this.continentalness = continentalness;
        this.erosion = erosion;
        this.peaksValleys = peaksValleys;
        this.weirdness = weirdness;
        this.temperature = temperature;
        this.humidity = humidity;
    }

    /**
     * Creates a copy with adjusted temperature (for altitude chill).
     *
     * @param newTemperature Adjusted temperature value
     * @return New MultiNoiseParameters with updated temperature
     */
    public MultiNoiseParameters withTemperature(float newTemperature) {
        return new MultiNoiseParameters(
                continentalness,
                erosion,
                peaksValleys,
                weirdness,
                newTemperature,
                humidity
        );
    }

    /**
     * Calculates 6-dimensional Euclidean distance to another parameter point.
     * Used for finding nearest biome when no exact match exists.
     *
     * @param other Other parameter point
     * @return Euclidean distance in 6D parameter space
     */
    public double distanceTo(MultiNoiseParameters other) {
        double dx1 = continentalness - other.continentalness;
        double dx2 = erosion - other.erosion;
        double dx3 = peaksValleys - other.peaksValleys;
        double dx4 = weirdness - other.weirdness;
        double dx5 = temperature - other.temperature;
        double dx6 = humidity - other.humidity;

        return Math.sqrt(
                dx1 * dx1 +
                dx2 * dx2 +
                dx3 * dx3 +
                dx4 * dx4 +
                dx5 * dx5 +
                dx6 * dx6
        );
    }

    @Override
    public String toString() {
        return String.format(
                "MultiNoiseParameters{C:%.2f, E:%.2f, PV:%.2f, W:%.2f, T:%.2f, H:%.2f}",
                continentalness, erosion, peaksValleys, weirdness, temperature, humidity
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MultiNoiseParameters that = (MultiNoiseParameters) o;

        if (Float.compare(that.continentalness, continentalness) != 0) return false;
        if (Float.compare(that.erosion, erosion) != 0) return false;
        if (Float.compare(that.peaksValleys, peaksValleys) != 0) return false;
        if (Float.compare(that.weirdness, weirdness) != 0) return false;
        if (Float.compare(that.temperature, temperature) != 0) return false;
        return Float.compare(that.humidity, humidity) == 0;
    }

    @Override
    public int hashCode() {
        int result = (continentalness != +0.0f ? Float.floatToIntBits(continentalness) : 0);
        result = 31 * result + (erosion != +0.0f ? Float.floatToIntBits(erosion) : 0);
        result = 31 * result + (peaksValleys != +0.0f ? Float.floatToIntBits(peaksValleys) : 0);
        result = 31 * result + (weirdness != +0.0f ? Float.floatToIntBits(weirdness) : 0);
        result = 31 * result + (temperature != +0.0f ? Float.floatToIntBits(temperature) : 0);
        result = 31 * result + (humidity != +0.0f ? Float.floatToIntBits(humidity) : 0);
        return result;
    }
}
