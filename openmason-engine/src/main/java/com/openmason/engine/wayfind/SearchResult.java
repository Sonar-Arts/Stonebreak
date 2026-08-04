package com.openmason.engine.wayfind;

/**
 * The outcome of one search: an ordered node chain from the start node to wherever the search got
 * to, plus why it stopped there.
 *
 * <p><b>Partial results are a feature, not a failure.</b> A mob asked to walk somewhere it cannot
 * reach should still walk as close as it can get, which is why an unreachable goal or an exhausted
 * budget still returns the chain to the most promising node reached. Only {@link Status#NO_PATH}
 * and {@link Status#CANCELLED} carry an empty chain.
 *
 * <p>Note that {@code equals}/{@code hashCode} compare {@link #nodes} by identity (record semantics
 * over an array); compare paths element-wise if you need value equality.
 *
 * @param nodes      start-to-end node keys; empty when there is nothing to walk
 * @param cost       accumulated cost along {@link #nodes}
 * @param expansions how many nodes the search expanded (diagnostics / budget tuning)
 */
public record SearchResult(Status status, long[] nodes, float cost, int expansions) {

    public enum Status {
        /** A node satisfying {@code isGoal} was reached; the chain is complete. */
        FOUND,
        /** The expansion budget ran out; the chain leads to the best node reached so far. */
        PARTIAL_BUDGET,
        /** Every reachable node was explored without hitting the goal; best-effort chain returned. */
        PARTIAL_UNREACHABLE,
        /** Nothing better than the start node was found — the start is boxed in. */
        NO_PATH,
        /** The caller cancelled mid-search. */
        CANCELLED
    }

    private static final long[] EMPTY = new long[0];

    public static SearchResult empty(Status status, int expansions) {
        return new SearchResult(status, EMPTY, 0.0f, expansions);
    }

    /** Whether the search actually reached the goal. */
    public boolean reachedGoal() {
        return status == Status.FOUND;
    }

    /**
     * Whether the chain is worth following. True for {@link Status#FOUND} and both partial results
     * once they contain more than the start node.
     */
    public boolean isUsable() {
        return nodes.length > 1 || (status == Status.FOUND && nodes.length > 0);
    }

    public int length() {
        return nodes.length;
    }

    public long node(int i) {
        return nodes[i];
    }
}
