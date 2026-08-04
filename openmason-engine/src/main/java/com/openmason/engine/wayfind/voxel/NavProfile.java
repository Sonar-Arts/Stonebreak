package com.openmason.engine.wayfind.voxel;

/**
 * How one kind of agent moves: the shape it needs, the heights it can climb and drop, and what
 * water costs it. This is the whole of a mob's "personality" as far as route planning is concerned
 * — a chicken and a cow differ only in these numbers.
 *
 * <p>The climb and fall limits must mirror the game's own physics. A profile that claims a bigger
 * step than collision actually performs produces routes the mob then fails to walk, which reads as
 * a stuck mob rather than as a bad number.
 *
 * @param height             standing height in blocks; sets the headroom every cell must give
 * @param columnRadius       extra columns of clearance required either side (0 = a single column).
 *                           Deriving this from a collision width is game policy, not engine policy:
 *                           a 1.1-wide mob squeezing down a 1-wide corridor is a design call.
 * @param maxStepUp          rise the agent walks up without jumping (game physics auto-step)
 * @param maxClimb           greatest rise reachable at all on land, jumping included; below
 *                           {@code maxStepUp} means the agent cannot jump
 * @param waterEscapeClimb   greatest rise reachable when leaving water. Separate from
 *                           {@code maxClimb} because pushing off water is not a standing jump —
 *                           if the two are conflated, a mob whose land jump is a hair under the
 *                           height of a bank is trapped in the pond beside it forever
 * @param maxFall            greatest drop the agent will take voluntarily
 * @param canSwim            whether the agent may enter water deep enough to submerge it
 * @param wadeCostMultiplier cost scale for water shallow enough to keep its head out
 * @param swimCostMultiplier cost scale for submerged water
 * @param stepCost           surcharge for stepping up
 * @param jumpCost           surcharge for jumping up — set it well above {@code stepCost} so routes
 *                           prefer a ramp to a hop
 * @param fallCostPerBlock   surcharge per block dropped
 */
public record NavProfile(
        float height,
        int columnRadius,
        float maxStepUp,
        float maxClimb,
        float waterEscapeClimb,
        float maxFall,
        boolean canSwim,
        float wadeCostMultiplier,
        float swimCostMultiplier,
        float stepCost,
        float jumpCost,
        float fallCostPerBlock) {

    public NavProfile {
        if (!(height > 0.0f)) {
            throw new IllegalArgumentException("height must be positive: " + height);
        }
        if (columnRadius < 0) {
            throw new IllegalArgumentException("columnRadius must not be negative: " + columnRadius);
        }
        if (maxStepUp < 0.0f || maxClimb < 0.0f || waterEscapeClimb < 0.0f || maxFall < 0.0f) {
            throw new IllegalArgumentException("climb and fall limits must not be negative");
        }
        // Cost multipliers below 1 would make the search's octile heuristic inadmissible, silently
        // costing optimality — cheap water is expressed by lowering other costs, not by going < 1.
        if (wadeCostMultiplier < 1.0f || swimCostMultiplier < 1.0f) {
            throw new IllegalArgumentException("liquid cost multipliers must be >= 1");
        }
        if (stepCost < 0.0f || jumpCost < 0.0f || fallCostPerBlock < 0.0f) {
            throw new IllegalArgumentException("surcharges must not be negative");
        }
    }

    /**
     * A land walker with the usual voxel-game numbers: half-block auto-step, one-block jump, a
     * three-block safe drop, and a strong preference for staying dry without refusing a puddle.
     */
    public static NavProfile walker(float height, int columnRadius) {
        return new NavProfile(height, columnRadius,
                0.5f, 1.125f, 1.5f, 3.0f,
                false, 3.0f, 8.0f,
                0.5f, 2.0f, 0.5f);
    }

    /** A walker equally at home in water — waterfowl, or anything amphibious. */
    public static NavProfile swimmer(float height, int columnRadius) {
        return new NavProfile(height, columnRadius,
                0.5f, 1.125f, 1.5f, 5.0f,
                true, 1.0f, 1.2f,
                0.5f, 2.0f, 0.2f);
    }

    /** Whether this agent can leave the ground at all. */
    public boolean canJump() {
        return maxClimb > maxStepUp;
    }

    public NavProfile withSwimming(boolean swim) {
        return new NavProfile(height, columnRadius, maxStepUp, maxClimb, waterEscapeClimb, maxFall,
                swim, wadeCostMultiplier, swimCostMultiplier, stepCost, jumpCost, fallCostPerBlock);
    }

    public NavProfile withMaxFall(float fall) {
        return new NavProfile(height, columnRadius, maxStepUp, maxClimb, waterEscapeClimb, fall,
                canSwim, wadeCostMultiplier, swimCostMultiplier, stepCost, jumpCost, fallCostPerBlock);
    }
}
