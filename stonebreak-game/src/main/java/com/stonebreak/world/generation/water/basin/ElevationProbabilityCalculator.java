package com.stonebreak.world.generation.water.basin;

import java.util.Random;

/**
 * Calculates elevation-based probability for basin water generation.
 *
 * <p>Uses exponential decay to make high-elevation basins progressively rarer:</p>
 * <ul>
 *     <li>y=66: 100% probability (base elevation)</li>
 *     <li>y=90: ~49% probability (k=0.03)</li>
 *     <li>y=130: ~15% probability (k=0.03)</li>
 * </ul>
 *
 * <p><strong>Formula:</strong> {@code P(y) = e^(-k * (y - baseY))}<br>
 * where k = decay rate (0.03 default)</p>
 *
 * <p><strong>Deterministic Random:</strong> Uses position-based hashing for repeatability.
 * The same world position always generates the same result for the same seed.</p>
 *
 * <p><strong>Design:</strong> Follows Single Responsibility Principle - only calculates
 * elevation probability. Part of the two-tiered water generation system.</p>
 */
public class ElevationProbabilityCalculator {

    private final float decayRate;
    private final int baseElevation;

    /**
     * Creates elevation probability calculator.
     *
     * @param decayRate Exponential decay rate (0.03 typical - gives ~50% at y=90)
     * @param baseElevation Base elevation where probability = 100% (66 typical)
     */
    public ElevationProbabilityCalculator(float decayRate, int baseElevation) {
        this.decayRate = decayRate;
        this.baseElevation = baseElevation;
    }

    /**
     * Calculates probability of basin water at given elevation.
     *
     * <p>Returns value in [0.0, 1.0] representing probability.
     * Elevations at or below base elevation always return 1.0 (100%).</p>
     *
     * <p><strong>Formula:</strong> {@code e^(-decayRate * (elevation - baseElevation))}</p>
     *
     * @param elevation Water level elevation
     * @return Probability in range [0.0, 1.0]
     */
    public float calculateProbability(int elevation) {
        if (elevation <= baseElevation) {
            return 1.0f; // Always allow at or below base elevation
        }

        int delta = elevation - baseElevation;
        return (float) Math.exp(-decayRate * delta);
    }

    /**
     * Checks if basin should generate water based on elevation probability.
     *
     * <p>Uses deterministic random based on world position for repeatability.
     * The same position with the same seed always produces the same result.</p>
     *
     * <p><strong>Determinism:</strong> Position hash ensures spatial variation while
     * maintaining reproducibility across world generation sessions.</p>
     *
     * @param elevation Water level elevation
     * @param worldX World X coordinate (for deterministic random)
     * @param worldZ World Z coordinate (for deterministic random)
     * @param seed World seed
     * @return true if basin should generate water
     */
    public boolean shouldGenerateWater(int elevation, int worldX, int worldZ, long seed) {
        float probability = calculateProbability(elevation);

        // Deterministic random based on position and seed
        long hash = seed;
        hash = hash * 31 + worldX;
        hash = hash * 31 + worldZ;
        hash = hash * 31 + elevation;

        Random random = new Random(hash);
        float roll = random.nextFloat();

        return roll < probability;
    }

    /**
     * Gets the configured decay rate.
     *
     * @return Decay rate
     */
    public float getDecayRate() {
        return decayRate;
    }

    /**
     * Gets the configured base elevation.
     *
     * @return Base elevation
     */
    public int getBaseElevation() {
        return baseElevation;
    }
}
