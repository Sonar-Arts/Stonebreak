package com.openmason.engine.wayfind;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the generic search core, exercised through an 8-connected grid domain — the
 * simplest graph that still has the properties voxel navigation depends on: diagonal costs, walls,
 * unreachable regions and dead ends.
 *
 * <p>The properties pinned here are the ones every caller relies on and none can verify itself:
 * optimality (checked against a brute-force Dijkstra oracle), best-effort partial results when the
 * goal cannot be reached, budget and cancellation honouring, and bit-for-bit determinism across
 * repeated and interleaved searches on a reused solver.
 */
class AStarTest {

    private static final float EPSILON = 1e-3f;
    private static final float DIAGONAL = (float) Math.sqrt(2.0);

    // ── Optimality ───────────────────────────────────────────────────────────

    @Test
    void findsShortestPathAcrossOpenGrid() {
        GridDomain grid = GridDomain.open(10, 10, 9, 9);

        SearchResult result = new AStar().search(grid, GridDomain.key(0, 0));

        assertEquals(SearchResult.Status.FOUND, result.status());
        assertEquals(9 * DIAGONAL, result.cost(), EPSILON, "pure diagonal run is the optimum");
        assertEquals(GridDomain.key(0, 0), result.node(0));
        assertEquals(GridDomain.key(9, 9), result.node(result.length() - 1));
        grid.assertContiguous(result);
    }

    @Test
    void matchesDijkstraOnRandomMazes() {
        AStar solver = new AStar();
        SearchLimits generous = SearchLimits.DEFAULT.withMaxExpansions(100_000);
        int reachable = 0;

        for (int seed = 0; seed < 200; seed++) {
            GridDomain grid = GridDomain.maze(20, 20, 19, 19, seed);
            SearchResult result = solver.search(grid, GridDomain.key(0, 0), generous, CancelToken.NEVER);
            float optimum = grid.dijkstraCostToGoal(0, 0);

            if (Float.isInfinite(optimum)) {
                assertNotEquals(SearchResult.Status.FOUND, result.status(),
                        "seed " + seed + ": claimed to reach a walled-off goal");
            } else {
                assertEquals(SearchResult.Status.FOUND, result.status(), "seed " + seed);
                assertEquals(optimum, result.cost(), EPSILON, "seed " + seed + ": not the optimal path");
                grid.assertContiguous(result);
                reachable++;
            }
        }
        assertTrue(reachable > 20, "maze generator produced too few solvable mazes to be meaningful");
    }

    @Test
    void goalAtStartReturnsTheStartAlone() {
        GridDomain grid = GridDomain.open(5, 5, 2, 2);

        SearchResult result = new AStar().search(grid, GridDomain.key(2, 2));

        assertEquals(SearchResult.Status.FOUND, result.status());
        assertArrayEquals(new long[]{GridDomain.key(2, 2)}, result.nodes());
        assertEquals(0, result.expansions());
    }

    // ── Partial results ──────────────────────────────────────────────────────

    @Test
    void unreachableGoalReturnsBestEffortTowardIt() {
        // A full-height wall at x == 5 seals the goal off; the closest reachable cell is (4, 9).
        GridDomain grid = GridDomain.open(10, 10, 9, 9).wallColumn(5);

        SearchResult result = new AStar().search(grid, GridDomain.key(0, 0));

        assertEquals(SearchResult.Status.PARTIAL_UNREACHABLE, result.status());
        assertTrue(result.isUsable(), "a partial path is still worth walking");
        assertEquals(GridDomain.key(4, 9), result.node(result.length() - 1),
                "should stop at the reachable cell nearest the goal");
        grid.assertContiguous(result);
    }

    @Test
    void boxedInStartReturnsNoPath() {
        GridDomain grid = GridDomain.open(10, 10, 9, 9).wallColumn(1).wallRow(1);

        SearchResult result = new AStar().search(grid, GridDomain.key(0, 0));

        assertEquals(SearchResult.Status.NO_PATH, result.status());
        assertEquals(0, result.length());
        assertFalse(result.isUsable(), "nothing to follow");
    }

    @Test
    void budgetExhaustionReturnsPartialTowardGoal() {
        GridDomain grid = GridDomain.open(200, 200, 199, 199);
        SearchLimits tight = SearchLimits.DEFAULT.withMaxExpansions(20);

        SearchResult result = new AStar().search(grid, GridDomain.key(0, 0), tight, CancelToken.NEVER);

        assertEquals(SearchResult.Status.PARTIAL_BUDGET, result.status());
        assertTrue(result.expansions() <= 20, "expanded past its budget: " + result.expansions());
        assertTrue(result.isUsable());
        assertEquals(GridDomain.key(0, 0), result.node(0));
        grid.assertContiguous(result);
    }

    @Test
    void maxCostBoundsHowFarTheSearchCommits() {
        GridDomain grid = GridDomain.open(50, 50, 49, 49);
        SearchLimits capped = SearchLimits.DEFAULT
                .withMaxExpansions(100_000)
                .withMaxCost(5 * DIAGONAL);

        SearchResult result = new AStar().search(grid, GridDomain.key(0, 0), capped, CancelToken.NEVER);

        assertEquals(SearchResult.Status.PARTIAL_UNREACHABLE, result.status());
        assertTrue(result.cost() <= 5 * DIAGONAL + EPSILON, "walked past the cost bound: " + result.cost());
    }

    // ── Cancellation ─────────────────────────────────────────────────────────

    @Test
    void alreadyCancelledSearchStopsImmediately() {
        GridDomain grid = GridDomain.open(200, 200, 199, 199);
        CancelToken token = new CancelToken();
        token.cancel();

        SearchResult result = new AStar().search(grid, GridDomain.key(0, 0), SearchLimits.DEFAULT, token);

        assertEquals(SearchResult.Status.CANCELLED, result.status());
        assertEquals(0, result.expansions());
        assertEquals(0, result.length());
    }

    @Test
    void cancellationMidSearchIsHonouredWithinOnePollInterval() {
        GridDomain grid = GridDomain.open(400, 400, 399, 399);
        CancelToken token = new CancelToken();
        // Cancel once the domain has been asked to expand a few nodes.
        grid.cancelAfterExpansions(token, 10);

        SearchResult result = new AStar().search(grid, GridDomain.key(0, 0),
                SearchLimits.DEFAULT.withMaxExpansions(100_000), token);

        assertEquals(SearchResult.Status.CANCELLED, result.status());
        assertTrue(result.expansions() < 10 + AStar.CANCEL_POLL_INTERVAL,
                "cancellation should land within one poll interval, expanded " + result.expansions());
    }

    // ── Determinism and reuse ────────────────────────────────────────────────

    @Test
    void repeatedSearchesOnAReusedSolverAreIdentical() {
        AStar solver = new AStar();
        GridDomain first = GridDomain.maze(30, 30, 29, 29, 7);
        GridDomain other = GridDomain.maze(25, 25, 24, 24, 99);

        SearchResult initial = solver.search(first, GridDomain.key(0, 0));
        // Interleave an unrelated search to prove no state leaks between runs.
        solver.search(other, GridDomain.key(0, 0));
        SearchResult repeat = solver.search(GridDomain.maze(30, 30, 29, 29, 7), GridDomain.key(0, 0));

        assertEquals(initial.status(), repeat.status());
        assertArrayEquals(initial.nodes(), repeat.nodes(), "same query must give byte-identical paths");
        assertEquals(initial.cost(), repeat.cost(), 0.0f);
        assertEquals(initial.expansions(), repeat.expansions());
    }

    @Test
    void freshAndReusedSolversAgree() {
        GridDomain grid = GridDomain.maze(40, 40, 39, 39, 3);

        AStar warm = new AStar();
        warm.search(GridDomain.maze(40, 40, 39, 39, 11), GridDomain.key(0, 0));
        SearchResult fromWarm = warm.search(grid, GridDomain.key(0, 0));
        SearchResult fromCold = new AStar().search(GridDomain.maze(40, 40, 39, 39, 3), GridDomain.key(0, 0));

        assertArrayEquals(fromCold.nodes(), fromWarm.nodes());
    }

    // ── Weighted search ──────────────────────────────────────────────────────

    @Test
    void weightedSearchTradesOptimalityForFewerExpansions() {
        AStar solver = new AStar();
        SearchLimits optimal = SearchLimits.DEFAULT.withMaxExpansions(100_000);
        SearchLimits greedy = optimal.withHeuristicWeight(2.0f);

        SearchResult exact = solver.search(wallWithADoorway(), GridDomain.key(0, 0), optimal, CancelToken.NEVER);
        SearchResult fast = solver.search(wallWithADoorway(), GridDomain.key(0, 0), greedy, CancelToken.NEVER);

        assertEquals(SearchResult.Status.FOUND, exact.status());
        assertEquals(SearchResult.Status.FOUND, fast.status());
        assertTrue(fast.expansions() <= exact.expansions(),
                "weighting should not expand more: " + fast.expansions() + " vs " + exact.expansions());
        assertTrue(fast.cost() >= exact.cost() - EPSILON, "the unweighted search must be the cheaper one");
    }

    /** 60×60 open field bisected by a wall with a single doorway — solvable, but not trivially. */
    private static GridDomain wallWithADoorway() {
        return GridDomain.open(60, 60, 59, 59).wallColumn(30).opening(30, 30);
    }

    // ── Limits validation ────────────────────────────────────────────────────

    @Test
    void limitsRejectNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new SearchLimits(0, 1.0f, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new SearchLimits(10, 0.0f, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new SearchLimits(10, 1.0f, 0.5f),
                "a weight below 1 would break admissibility");
    }

    @Test
    void neverTokenIsNeverCancelled() {
        assertFalse(CancelToken.NEVER.isCancelled());
    }

    // ── Test domain ──────────────────────────────────────────────────────────

    /**
     * 8-connected grid with unit orthogonal steps, √2 diagonals and no corner cutting (a diagonal
     * needs both flanking cells open) — the 2D analogue of the ground movement rules.
     */
    private static final class GridDomain implements SearchDomain {

        private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
        private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};

        private final boolean[][] open;
        private final int width;
        private final int height;
        private final int goalX;
        private final int goalY;

        private CancelToken cancelAfter;
        private int cancelThreshold;
        private int expansions;

        private GridDomain(boolean[][] open, int goalX, int goalY) {
            this.open = open;
            this.width = open.length;
            this.height = open[0].length;
            this.goalX = goalX;
            this.goalY = goalY;
        }

        static GridDomain open(int width, int height, int goalX, int goalY) {
            boolean[][] cells = new boolean[width][height];
            for (boolean[] column : cells) {
                Arrays.fill(column, true);
            }
            return new GridDomain(cells, goalX, goalY);
        }

        /** Deterministic pseudo-random maze; start and goal cells are always kept open. */
        static GridDomain maze(int width, int height, int goalX, int goalY, int seed) {
            Random random = new Random(seed);
            boolean[][] cells = new boolean[width][height];
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    cells[x][y] = random.nextFloat() > 0.28f;
                }
            }
            cells[0][0] = true;
            cells[goalX][goalY] = true;
            return new GridDomain(cells, goalX, goalY);
        }

        GridDomain wallColumn(int x) {
            Arrays.fill(open[x], false);
            return this;
        }

        GridDomain opening(int x, int y) {
            open[x][y] = true;
            return this;
        }

        GridDomain wallRow(int y) {
            for (int x = 0; x < width; x++) {
                open[x][y] = false;
            }
            return this;
        }

        void cancelAfterExpansions(CancelToken token, int threshold) {
            this.cancelAfter = token;
            this.cancelThreshold = threshold;
        }

        static long key(int x, int y) {
            return ((long) x << 32) | (y & 0xFFFFFFFFL);
        }

        static int x(long key) {
            return (int) (key >> 32);
        }

        static int y(long key) {
            return (int) key;
        }

        @Override
        public int successors(long node, long[] outNodes, float[] outCosts) {
            if (cancelAfter != null && ++expansions > cancelThreshold) {
                cancelAfter.cancel();
            }
            int x = x(node);
            int y = y(node);
            int count = 0;
            for (int dir = 0; dir < 8; dir++) {
                int nx = x + DX[dir];
                int ny = y + DZ[dir];
                if (!walkable(nx, ny)) {
                    continue;
                }
                boolean diagonal = DX[dir] != 0 && DZ[dir] != 0;
                if (diagonal && (!walkable(nx, y) || !walkable(x, ny))) {
                    continue; // no squeezing through a corner
                }
                outNodes[count] = key(nx, ny);
                outCosts[count] = diagonal ? DIAGONAL : 1.0f;
                count++;
            }
            return count;
        }

        @Override
        public float heuristic(long node) {
            int dx = Math.abs(x(node) - goalX);
            int dy = Math.abs(y(node) - goalY);
            int min = Math.min(dx, dy);
            return (dx + dy - 2 * min) + DIAGONAL * min; // octile distance
        }

        @Override
        public boolean isGoal(long node) {
            return x(node) == goalX && y(node) == goalY;
        }

        private boolean walkable(int x, int y) {
            return x >= 0 && y >= 0 && x < width && y < height && open[x][y];
        }

        /** Every consecutive pair in the path must be a legal move on this grid. */
        void assertContiguous(SearchResult result) {
            long[] scratchNodes = new long[8];
            float[] scratchCosts = new float[8];
            for (int i = 0; i < result.length() - 1; i++) {
                int count = successors(result.node(i), scratchNodes, scratchCosts);
                boolean linked = false;
                for (int s = 0; s < count; s++) {
                    if (scratchNodes[s] == result.node(i + 1)) {
                        linked = true;
                        break;
                    }
                }
                assertTrue(linked, "step " + i + " of the path is not a legal move");
            }
        }

        private record Reached(long node, float cost) {
        }

        /** Brute-force optimum from (sx, sy) to the goal; +inf when unreachable. */
        float dijkstraCostToGoal(int sx, int sy) {
            float[][] dist = new float[width][height];
            for (float[] column : dist) {
                Arrays.fill(column, Float.POSITIVE_INFINITY);
            }
            dist[sx][sy] = 0.0f;

            PriorityQueue<Reached> queue = new PriorityQueue<>(Comparator.comparingDouble(Reached::cost));
            queue.add(new Reached(key(sx, sy), 0.0f));

            long[] scratchNodes = new long[8];
            float[] scratchCosts = new float[8];
            while (!queue.isEmpty()) {
                Reached entry = queue.poll();
                if (entry.cost() > dist[x(entry.node())][y(entry.node())]) {
                    continue;
                }
                int count = successors(entry.node(), scratchNodes, scratchCosts);
                for (int i = 0; i < count; i++) {
                    long next = scratchNodes[i];
                    float candidate = entry.cost() + scratchCosts[i];
                    if (candidate < dist[x(next)][y(next)]) {
                        dist[x(next)][y(next)] = candidate;
                        queue.add(new Reached(next, candidate));
                    }
                }
            }
            return dist[goalX][goalY];
        }
    }
}
