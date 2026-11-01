package com.openmason.engine.wayfind;

/**
 * Immutable bounds on one search. Shareable across threads and searches — the mutable per-request
 * piece (cancellation) is a separate {@link CancelToken}.
 *
 * @param maxExpansions   hard cap on expanded nodes; the search returns its best partial path once
 *                        reached. This is the knob that keeps a pathological search (a mob boxed
 *                        into a cave system) from costing more than a bounded slice of CPU.
 * @param maxCost         paths costing more than this are not explored; {@link Float#MAX_VALUE}
 *                        disables the bound
 * @param heuristicWeight multiplier on the heuristic. {@code 1.0} is plain A* (optimal with an
 *                        admissible heuristic). Values above 1 trade optimality for far fewer
 *                        expansions — a mob walking a slightly longer route is invisible in play,
 *                        so agents default to a mild over-weight.
 */
public record SearchLimits(int maxExpansions, float maxCost, float heuristicWeight) {

    /** Enough for a mob route of a few dozen blocks through cluttered terrain. */
    public static final SearchLimits DEFAULT = new SearchLimits(500, Float.MAX_VALUE, 1.0f);

    public SearchLimits {
        if (maxExpansions <= 0) {
            throw new IllegalArgumentException("maxExpansions must be positive: " + maxExpansions);
        }
        if (!(maxCost > 0.0f)) {
            throw new IllegalArgumentException("maxCost must be positive: " + maxCost);
        }
        if (!(heuristicWeight >= 1.0f)) {
            throw new IllegalArgumentException("heuristicWeight must be >= 1: " + heuristicWeight);
        }
    }

    public SearchLimits withMaxExpansions(int expansions) {
        return new SearchLimits(expansions, maxCost, heuristicWeight);
    }

    public SearchLimits withMaxCost(float cost) {
        return new SearchLimits(maxExpansions, cost, heuristicWeight);
    }

    public SearchLimits withHeuristicWeight(float weight) {
        return new SearchLimits(maxExpansions, maxCost, weight);
    }
}
