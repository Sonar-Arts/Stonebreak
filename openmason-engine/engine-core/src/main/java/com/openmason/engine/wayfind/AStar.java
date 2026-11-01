package com.openmason.engine.wayfind;

import com.openmason.engine.util.LongIntHashMap;

import java.util.Arrays;
import java.util.Objects;

/**
 * A* over an opaque {@link SearchDomain}. The shipped {@link PathSolver}.
 *
 * <p>Structure-of-arrays throughout: every node the search touches is interned once into parallel
 * primitive arrays, the open set is a binary heap of {@code int} node ids with back-pointers for
 * O(log n) decrease-key, and key lookup goes through the engine's primitive
 * {@link LongIntHashMap}. Nothing is boxed and nothing is allocated per node. The buffers are
 * retained between searches, so a warm solver allocates only the {@code long[]} it hands back in
 * its {@link SearchResult}.
 *
 * <p><b>Determinism.</b> Ordering breaks ties by f, then h, then insertion order. Given a domain
 * that enumerates successors in a fixed order, the same query always yields byte-identical results
 * — which is what lets a server and a client agree on where a mob is walking, and what lets tests
 * assert on exact paths.
 *
 * <p><b>Reopening.</b> Closed nodes are reopened if a cheaper route to them turns up. That costs
 * nothing when the heuristic is consistent, and keeps results correct when it is not — voxel
 * navigation mixes movement costs (swimming, jumping, falling) that an octile distance cannot
 * model consistently.
 *
 * <p>Stateful and NOT thread-safe. Give each worker thread its own instance and reuse it.
 */
public final class AStar implements PathSolver {

    /** Expansions between {@link CancelToken} polls. Power of two — the loop masks against it. */
    public static final int CANCEL_POLL_INTERVAL = 64;

    private static final int INITIAL_CAPACITY = 1024;

    /**
     * A relaxation must beat the incumbent by more than this to count. Guards against a float
     * rounding difference re-opening a node forever on a graph with symmetric costs.
     */
    private static final float RELAX_EPSILON = 1e-4f;

    /** Not in the open heap: either never pushed, or popped (closed). */
    private static final int NOT_IN_HEAP = -1;

    private static final int NO_PARENT = -1;

    /** Node ids are non-negative, so -1 is a safe "key not interned yet" marker. */
    private static final int UNKNOWN_NODE = -1;

    // ── Node table (index = node id, assigned in discovery order) ─────────────
    private long[] key = new long[INITIAL_CAPACITY];
    private float[] gScore = new float[INITIAL_CAPACITY];
    private float[] hScore = new float[INITIAL_CAPACITY];
    private float[] fScore = new float[INITIAL_CAPACITY];
    private int[] parent = new int[INITIAL_CAPACITY];
    private int[] heapPos = new int[INITIAL_CAPACITY];
    private int nodeCount;

    private final LongIntHashMap index = new LongIntHashMap(INITIAL_CAPACITY);

    // ── Open set: binary min-heap of node ids ────────────────────────────────
    private int[] heap = new int[INITIAL_CAPACITY];
    private int heapSize;

    // ── Per-expansion scratch, handed to the domain ──────────────────────────
    private long[] successorNodes = new long[8];
    private float[] successorCosts = new float[8];

    /** Searches with {@link SearchLimits#DEFAULT} and no cancellation. */
    public SearchResult search(SearchDomain domain, long start) {
        return search(domain, start, SearchLimits.DEFAULT, CancelToken.NEVER);
    }

    @Override
    public SearchResult search(SearchDomain domain, long start, SearchLimits limits, CancelToken cancel) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(cancel, "cancel");

        reset(domain.maxSuccessors());

        final float weight = limits.heuristicWeight();
        final int startId = addNode(start, 0.0f, domain.heuristic(start), NO_PARENT, weight);
        push(startId);

        // The chain we fall back on when the goal is not reached: closest to the goal by heuristic,
        // cheapest to get to among equals.
        int bestId = startId;
        int expansions = 0;

        while (heapSize > 0) {
            if (expansions >= limits.maxExpansions()) {
                return partial(SearchResult.Status.PARTIAL_BUDGET, startId, bestId, expansions);
            }
            if ((expansions & (CANCEL_POLL_INTERVAL - 1)) == 0 && cancel.isCancelled()) {
                return SearchResult.empty(SearchResult.Status.CANCELLED, expansions);
            }

            int current = pop();
            long currentKey = key[current];
            if (domain.isGoal(currentKey)) {
                return reconstruct(SearchResult.Status.FOUND, current, expansions);
            }
            expansions++;

            int count = domain.successors(currentKey, successorNodes, successorCosts);
            for (int i = 0; i < count; i++) {
                float tentativeG = gScore[current] + successorCosts[i];
                if (tentativeG > limits.maxCost()) {
                    continue;
                }
                long successorKey = successorNodes[i];
                int successor = index.get(successorKey, UNKNOWN_NODE);

                if (successor == UNKNOWN_NODE) {
                    successor = addNode(successorKey, tentativeG,
                            domain.heuristic(successorKey), current, weight);
                    push(successor);
                } else if (tentativeG + RELAX_EPSILON < gScore[successor]) {
                    gScore[successor] = tentativeG;
                    fScore[successor] = tentativeG + weight * hScore[successor];
                    parent[successor] = current;
                    if (heapPos[successor] == NOT_IN_HEAP) {
                        push(successor); // reopen — a cheaper route to an already-closed node
                    } else {
                        siftUp(heapPos[successor]);
                    }
                } else {
                    continue;
                }

                if (isBetterFallback(successor, bestId)) {
                    bestId = successor;
                }
            }
        }

        return partial(SearchResult.Status.PARTIAL_UNREACHABLE, startId, bestId, expansions);
    }

    /** Closer to the goal wins; among equals, the cheaper node to stand on wins. */
    private boolean isBetterFallback(int candidate, int incumbent) {
        if (hScore[candidate] < hScore[incumbent]) {
            return true;
        }
        return hScore[candidate] == hScore[incumbent] && gScore[candidate] < gScore[incumbent];
    }

    private SearchResult partial(SearchResult.Status status, int startId, int bestId, int expansions) {
        if (bestId == startId) {
            // Nothing beat standing still — boxed in, or every successor led away from the goal.
            return SearchResult.empty(SearchResult.Status.NO_PATH, expansions);
        }
        return reconstruct(status, bestId, expansions);
    }

    private SearchResult reconstruct(SearchResult.Status status, int endId, int expansions) {
        int length = 0;
        for (int id = endId; id != NO_PARENT; id = parent[id]) {
            length++;
        }
        long[] path = new long[length];
        for (int id = endId, i = length - 1; id != NO_PARENT; id = parent[id], i--) {
            path[i] = key[id];
        }
        return new SearchResult(status, path, gScore[endId], expansions);
    }

    // ── Node table ───────────────────────────────────────────────────────────

    private void reset(int maxSuccessors) {
        nodeCount = 0;
        heapSize = 0;
        index.clear();
        if (successorNodes.length < maxSuccessors) {
            successorNodes = new long[maxSuccessors];
            successorCosts = new float[maxSuccessors];
        }
    }

    private int addNode(long nodeKey, float g, float h, int parentId, float weight) {
        int id = nodeCount++;
        if (id == key.length) {
            growNodeTable();
        }
        key[id] = nodeKey;
        gScore[id] = g;
        hScore[id] = h;
        fScore[id] = g + weight * h;
        parent[id] = parentId;
        heapPos[id] = NOT_IN_HEAP;
        index.put(nodeKey, id);
        return id;
    }

    private void growNodeTable() {
        int capacity = key.length << 1;
        key = Arrays.copyOf(key, capacity);
        gScore = Arrays.copyOf(gScore, capacity);
        hScore = Arrays.copyOf(hScore, capacity);
        fScore = Arrays.copyOf(fScore, capacity);
        parent = Arrays.copyOf(parent, capacity);
        heapPos = Arrays.copyOf(heapPos, capacity);
    }

    // ── Open set ─────────────────────────────────────────────────────────────

    private void push(int id) {
        if (heapSize == heap.length) {
            heap = Arrays.copyOf(heap, heapSize << 1);
        }
        heap[heapSize] = id;
        heapPos[id] = heapSize;
        siftUp(heapSize++);
    }

    private int pop() {
        int top = heap[0];
        heapPos[top] = NOT_IN_HEAP;
        int last = heap[--heapSize];
        if (heapSize > 0) {
            heap[0] = last;
            heapPos[last] = 0;
            siftDown(0);
        }
        return top;
    }

    private void siftUp(int position) {
        int id = heap[position];
        while (position > 0) {
            int parentPos = (position - 1) >>> 1;
            int parentNode = heap[parentPos];
            if (!precedes(id, parentNode)) {
                break;
            }
            heap[position] = parentNode;
            heapPos[parentNode] = position;
            position = parentPos;
        }
        heap[position] = id;
        heapPos[id] = position;
    }

    private void siftDown(int position) {
        int id = heap[position];
        int half = heapSize >>> 1;
        while (position < half) {
            int childPos = (position << 1) + 1;
            int right = childPos + 1;
            int child = heap[childPos];
            if (right < heapSize && precedes(heap[right], child)) {
                child = heap[childPos = right];
            }
            if (!precedes(child, id)) {
                break;
            }
            heap[position] = child;
            heapPos[child] = position;
            position = childPos;
        }
        heap[position] = id;
        heapPos[id] = position;
    }

    /** Strict heap ordering: f, then h (prefer being nearer the goal), then discovery order. */
    private boolean precedes(int a, int b) {
        if (fScore[a] != fScore[b]) {
            return fScore[a] < fScore[b];
        }
        if (hScore[a] != hScore[b]) {
            return hScore[a] < hScore[b];
        }
        return a < b;
    }
}
