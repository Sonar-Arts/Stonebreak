package com.stonebreak.blocks.waterSystem.types;

import com.stonebreak.blocks.waterSystem.WaterBlock;

/**
 * Represents vertically falling water. Behaves like freshly reset flow depth
 * without being treated as a true source block, preventing waterfalls from
 * converting into permanent sources when their origin is removed.
 */
public class FallingWaterType implements WaterType {

    @Override
    public int getDepth() {
        return WaterBlock.SOURCE_DEPTH; // reset depth after a vertical drop
    }

    @Override
    public boolean canGenerateFlow() {
        return true;
    }

    @Override
    public boolean canCreateSource() {
        return false;
    }

    @Override
    public boolean isSource() {
        return false;
    }

    @Override
    public int getFlowPressure() {
        return 7; // matching source pressure for realistic waterfall speed
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof FallingWaterType;
    }

    @Override
    public int hashCode() {
        return FallingWaterType.class.hashCode();
    }

    @Override
    public String toString() {
        return "FallingWaterType{depth=0, pressure=7}";
    }
}
