package com.stonebreak.blocks.waterSystem.types;

/**
 * Represents naturally generated ocean/world water.
 * Ocean water is always a source block but has special designation
 * for world generation and validation purposes.
 */
public class OceanWaterType extends SourceWaterType {

    @Override
    public boolean canGenerateFlow() {
        return true;
    }

    /**
     * Checks if this is ocean water (world-generated).
     *
     * @return true since this is ocean water
     */
    public boolean isOceanWater() {
        return true;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof OceanWaterType;
    }

    @Override
    public int hashCode() {
        return OceanWaterType.class.hashCode();
    }

    @Override
    public String toString() {
        return "OceanWaterType{depth=0, pressure=7, ocean=true}";
    }
}