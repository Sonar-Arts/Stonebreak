package com.openmason.engine.wayfind;

/**
 * The graph a {@link PathSolver} searches. This is the only thing the search core knows about the
 * problem: nodes are opaque {@code long} keys, and the domain answers three questions about them.
 *
 * <p>Nothing here is voxel-specific — the same core serves a ground mob walking blocks, a flyer
 * moving through coarse air cells, or any future graph (road networks, item routing). Voxel
 * navigation lives in {@link com.openmason.engine.wayfind.voxel}.
 *
 * <p><b>The goal is baked into the domain instance</b> rather than passed to the solver. That keeps
 * the solver signature small and lets a domain express goals the solver could not: "any node within
 * 2 blocks of the target", "any water surface", "whichever of these three doors is nearest".
 *
 * <p><b>Threading.</b> One domain instance belongs to one in-flight search. Domains are expected to
 * be cheap to construct and are free to cache per-search state (see
 * {@code voxel.NavColumnCache}); they must never be shared between concurrent searches.
 */
public interface SearchDomain {

    /**
     * Writes the successors of {@code node} into the caller's arrays and returns how many were
     * written. Both arrays are at least {@link #maxSuccessors()} long, are owned by the solver, and
     * are overwritten on every expansion — the domain must not retain them.
     *
     * <p>Costs must be finite and non-negative. A cost of zero is legal but makes the search order
     * among equal-cost nodes depend only on the heuristic.
     *
     * @param node     the node being expanded
     * @param outNodes receives successor keys
     * @param outCosts receives the step cost from {@code node} to the matching successor
     * @return the number of entries written, {@code 0} for a dead end
     */
    int successors(long node, long[] outNodes, float[] outCosts);

    /**
     * Estimated remaining cost from {@code node} to the goal. Must be non-negative, and should not
     * over-estimate the true remaining cost if optimal paths matter — an over-estimating heuristic
     * still terminates and still returns a path, just not necessarily the cheapest one.
     */
    float heuristic(long node);

    /** Whether {@code node} satisfies the search goal. */
    boolean isGoal(long node);

    /**
     * Upper bound on the number of successors a single node can have; sizes the solver's scratch
     * arrays. The default suits an 8-connected grid.
     */
    default int maxSuccessors() {
        return 8;
    }
}
