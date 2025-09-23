package com.stonebreak.blocks.waterSystem.types;

import com.stonebreak.blocks.waterSystem.WaterBlock;

/**
 * Represents flowing water blocks created by source blocks.
 * Flow blocks have depth 1-7 and can participate in source creation
 * under specific collision conditions.
 */
public class FlowWaterType implements WaterType {

    private final int depth;
    private boolean sourceCreationEligible;

    /**
     * Creates a flow water type with the specified depth.
     *
     * @param depth Water depth (1-7 for flowing water)
     */
    public FlowWaterType(int depth) {
        this.depth = Math.max(1, Math.min(WaterBlock.MAX_FLOW_DEPTH, depth));
        this.sourceCreationEligible = true; // Most flows can create sources
    }

    /**
     * Creates a flow water type with depth and source creation eligibility.
     *
     * @param depth Water depth (1-7 for flowing water)
     * @param sourceCreationEligible Whether this flow can participate in source creation
     */
    public FlowWaterType(int depth, boolean sourceCreationEligible) {
        this.depth = Math.max(1, Math.min(WaterBlock.MAX_FLOW_DEPTH, depth));
        this.sourceCreationEligible = sourceCreationEligible;
    }

    @Override
    public int getDepth() {
        return depth;
    }

    @Override
    public boolean canGenerateFlow() {
        // Flow blocks can continue to spread if they haven't reached max depth
        return depth < WaterBlock.MAX_FLOW_DEPTH;
    }

    @Override
    public boolean canCreateSource() {
        // Only depth-1 flows that are source creation eligible can create sources
        // This implements the rule about partially vertical sources emitting
        // non-source-creating flows
        return depth == 1 && sourceCreationEligible;
    }

    @Override
    public boolean isSource() {
        return false;
    }

    @Override
    public int getFlowPressure() {
        // Flow pressure decreases with depth
        return Math.max(0, WaterBlock.MAX_WATER_LEVEL - depth);
    }

    /**
     * Gets whether this flow is eligible for source creation.
     *
     * @return true if this flow can participate in source creation
     */
    public boolean isSourceCreationEligible() {
        return sourceCreationEligible;
    }

    /**
     * Sets whether this flow is eligible for source creation.
     *
     * @param eligible true if this flow can participate in source creation
     */
    public void setSourceCreationEligible(boolean eligible) {
        this.sourceCreationEligible = eligible;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof FlowWaterType)) return false;
        FlowWaterType that = (FlowWaterType) obj;
        return depth == that.depth && sourceCreationEligible == that.sourceCreationEligible;
    }

    @Override
    public int hashCode() {
        return depth * 31 + (sourceCreationEligible ? 1 : 0);
    }

    @Override
    public String toString() {
        return String.format("FlowWaterType{depth=%d, pressure=%d, canCreateSource=%s}",
                           depth, getFlowPressure(), canCreateSource());
    }
}