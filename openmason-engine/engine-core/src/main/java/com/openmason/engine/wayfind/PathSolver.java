package com.openmason.engine.wayfind;

/**
 * Solves a {@link SearchDomain}. The seam between "what graph am I searching" and "how is it
 * searched".
 *
 * <p>{@link AStar} is the shipped implementation. The interface exists so a different strategy can
 * be swapped in per call site without touching callers — a Dijkstra/flow-field variant for crowd
 * movement, or a native backend should the search ever outgrow the JVM. (A native backend only pays
 * off once the navigation data is resident off-heap: with lazily-read world data, marshalling the
 * voxel window costs more than the whole Java search it would replace.)
 *
 * <p>Implementations are stateful and NOT thread-safe — one solver per thread, reused across
 * searches so its scratch buffers stay warm.
 */
public interface PathSolver {

    /**
     * Searches {@code domain} outward from {@code start}.
     *
     * @param cancel polled periodically; pass {@link CancelToken#NEVER} for an uncancellable search
     * @return never null; see {@link SearchResult.Status}
     */
    SearchResult search(SearchDomain domain, long start, SearchLimits limits, CancelToken cancel);
}
