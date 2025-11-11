package com.stonebreak.world.generation.debug;

import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.generation.heightmap.TerrainHint;

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
     * Debug information for LEGACY terrain generator.
     * Shows sequential calculation steps: base → hint → erosion → PV → weirdness → final
     */
    public static class LegacyDebugInfo extends HeightCalculationDebugInfo {
        private final double baseHeight;
        private final TerrainHint terrainHint;
        private final double heightAfterHint;
        private final double erosionFactor;
        private final double heightAfterErosion;
        private final double pvAmplification;
        private final double heightAfterPV;
        private final double weirdnessTerracing;
        private final double heightAfterWeirdness;

        public LegacyDebugInfo(
                double baseHeight,
                TerrainHint terrainHint,
                double heightAfterHint,
                double erosionFactor,
                double heightAfterErosion,
                double pvAmplification,
                double heightAfterPV,
                double weirdnessTerracing,
                double heightAfterWeirdness,
                double finalHeight) {
            super(TerrainGeneratorType.LEGACY, finalHeight);
            this.baseHeight = baseHeight;
            this.terrainHint = terrainHint;
            this.heightAfterHint = heightAfterHint;
            this.erosionFactor = erosionFactor;
            this.heightAfterErosion = heightAfterErosion;
            this.pvAmplification = pvAmplification;
            this.heightAfterPV = heightAfterPV;
            this.weirdnessTerracing = weirdnessTerracing;
            this.heightAfterWeirdness = heightAfterWeirdness;
        }

        public double getBaseHeight() {
            return baseHeight;
        }

        public TerrainHint getTerrainHint() {
            return terrainHint;
        }

        public double getHeightAfterHint() {
            return heightAfterHint;
        }

        public double getErosionFactor() {
            return erosionFactor;
        }

        public double getHeightAfterErosion() {
            return heightAfterErosion;
        }

        public double getPvAmplification() {
            return pvAmplification;
        }

        public double getHeightAfterPV() {
            return heightAfterPV;
        }

        public double getWeirdnessTerracing() {
            return weirdnessTerracing;
        }

        public double getHeightAfterWeirdness() {
            return heightAfterWeirdness;
        }
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
