package com.stonebreak.world.generation.noise;

/**
 * Snapshot of the five climate/shape parameters at a world position.
 * Ranges: c, e, pv in [-1, 1]; temperature, moisture in [0, 1].
 */
public record MultiNoiseSample(
    float continentalness,
    float erosion,
    float peaksValleys,
    float temperature,
    float moisture
) {
}
