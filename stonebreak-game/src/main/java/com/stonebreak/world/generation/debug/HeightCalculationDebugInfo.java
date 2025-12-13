package com.stonebreak.world.generation.debug;

import com.stonebreak.world.generation.TerrainGeneratorType;

import java.util.Collections;
import java.util.List;

/**
 * Base class for height calculation debug information.
 * Contains generator-specific details for F3 debug visualization.
 */
public abstract class HeightCalculationDebugInfo {
    protected final TerrainGeneratorType generatorType;
    protected final double finalHeight;

    protected HeightCalculationDebugInfo(TerrainGeneratorType generatorType, double finalHeight) {
        this.generatorType = generatorType;
        this.finalHeight = finalHeight;
    }

    public TerrainGeneratorType getGeneratorType() {
        return generatorType;
    }

    public double getFinalHeight() {
        return finalHeight;
    }

    /**
     * Debug information for SPLINE terrain generator.
     * Shows spline interpolation with contributing points and weights.
     */
    public static class SplineDebugInfo extends HeightCalculationDebugInfo {
        private final List<SplineInterpolationPoint> interpolationPoints;

        public SplineDebugInfo(List<SplineInterpolationPoint> interpolationPoints, double finalHeight) {
            super(TerrainGeneratorType.SPLINE, finalHeight);
            this.interpolationPoints = Collections.unmodifiableList(interpolationPoints);
        }

        public List<SplineInterpolationPoint> getInterpolationPoints() {
            return interpolationPoints;
        }
    }
}
