package com.stonebreak.world.generation.debug;

/**
 * Represents a single spline interpolation point used in terrain height calculation.
 * Contains the parameter coordinates, interpolation weight, and height contribution.
 */
public class SplineInterpolationPoint {
    private final double continentalness;
    private final double erosion;
    private final double peaksValleys;
    private final double weirdness;
    private final double weight;
    private final double heightContribution;

    public SplineInterpolationPoint(
            double continentalness,
            double erosion,
            double peaksValleys,
            double weirdness,
            double weight,
            double heightContribution) {
        this.continentalness = continentalness;
        this.erosion = erosion;
        this.peaksValleys = peaksValleys;
        this.weirdness = weirdness;
        this.weight = weight;
        this.heightContribution = heightContribution;
    }

    public double getContinentalness() {
        return continentalness;
    }

    public double getErosion() {
        return erosion;
    }

    public double getPeaksValleys() {
        return peaksValleys;
    }

    public double getWeirdness() {
        return weirdness;
    }

    public double getWeight() {
        return weight;
    }

    public double getHeightContribution() {
        return heightContribution;
    }

    @Override
    public String toString() {
        return String.format("Point(C:%.2f, E:%.2f, PV:%.2f, W:%.2f) → Weight:%.2f, Height:%.1f",
                continentalness, erosion, peaksValleys, weirdness, weight, heightContribution);
    }
}
