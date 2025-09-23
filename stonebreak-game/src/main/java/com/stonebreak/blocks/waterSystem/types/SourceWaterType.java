package com.stonebreak.blocks.waterSystem.types;

import com.stonebreak.blocks.waterSystem.WaterBlock;

/**
 * Represents a water source block that can generate infinite flow.
 * Source blocks maintain depth 0 and always generate full flow pressure.
 */
public class SourceWaterType implements WaterType {

    @Override
    public int getDepth() {
        return WaterBlock.SOURCE_DEPTH;
    }

    @Override
    public boolean canGenerateFlow() {
        return true;
    }

    @Override
    public boolean canCreateSource() {
        // Source blocks themselves don't create new sources through collision
        // New sources are created by flow block collisions
        return false;
    }

    @Override
    public boolean isSource() {
        return true;
    }

    @Override
    public int getFlowPressure() {
        return 7; // Maximum flow pressure
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof SourceWaterType;
    }

    @Override
    public int hashCode() {
        return SourceWaterType.class.hashCode();
    }

    @Override
    public String toString() {
        return "SourceWaterType{depth=0, pressure=7}";
    }
}