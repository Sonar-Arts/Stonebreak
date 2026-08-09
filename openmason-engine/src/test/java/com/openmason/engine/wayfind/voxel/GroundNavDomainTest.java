package com.openmason.engine.wayfind.voxel;

import com.openmason.engine.wayfind.AStar;
import com.openmason.engine.wayfind.CancelToken;
import com.openmason.engine.wayfind.SearchLimits;
import com.openmason.engine.wayfind.SearchResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The movement rules, pinned one at a time against hand-built terrain.
 *
 * <p>These are the assertions that keep planned routes and actual physics in agreement: what counts
 * as standable, how high an agent steps versus jumps, how far it will drop, what it does at a corner
 * or a puddle, and — the rule that is easiest to break and hardest to notice — that each physical
 * surface is exactly one node.
 */
class GroundNavDomainTest {

    private static final int GROUND_TOP = 63;
    private static final int STAND_Y = 64;
    private static final float EPSILON = 1e-3f;
    private static final float DIAGONAL = 1.4142135f;

    /** Water's surface sits an eighth of a block below the top of its cell, so entering is a small drop. */
    private static final float ENTRY_DROP = 1.0f - BoxNavVolume.WATER_SURFACE_HEIGHT;

    /** Short enough to need one cell of headroom, single-column footprint. */
    private static final NavProfile SHORT_WALKER = NavProfile.walker(0.9f, 0);

    // ── Flat ground ──────────────────────────────────────────────────────────

    @Test
    void flatGroundOffersEightMovesAtOctileCosts() {
        Moves moves = movesFrom(BoxNavVolume.ground(9, 70, 9, GROUND_TOP), SHORT_WALKER, 4, STAND_Y, 4);

        assertEquals(8, moves.count());
        assertEquals(1.0f, moves.costTo(5, STAND_Y, 4), EPSILON, "orthogonal step");
        assertEquals(DIAGONAL, moves.costTo(5, STAND_Y, 5), EPSILON, "diagonal step");
    }

    @Test
    void feetRestOnTopOfTheBlockBelow() {
        GroundNavDomain domain = domain(BoxNavVolume.ground(5, 70, 5, GROUND_TOP), SHORT_WALKER, 0, 0, 0);

        assertEquals(STAND_Y, domain.surfaceOf(NavNodes.pack(2, STAND_Y, 2)), EPSILON);
    }

    // ── Vertical rules ───────────────────────────────────────────────────────

    @Test
    void stepUpOntoAPartialBlockIsAStepNotAJump() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP).partial(5, STAND_Y, 4, 0.5f);
        Moves moves = movesFrom(world, SHORT_WALKER, 4, STAND_Y, 4);

        // The tread is inside cell STAND_Y, so that is the node — and it costs a step, not a jump.
        assertEquals(1.0f + SHORT_WALKER.stepCost(), moves.costTo(5, STAND_Y, 4), EPSILON);
    }

    @Test
    void surfaceOnAPartialBlockIsReportedAtItsRealHeight() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP).partial(5, STAND_Y, 4, 0.5f);
        GroundNavDomain domain = domain(world, SHORT_WALKER, 0, 0, 0);

        assertEquals(STAND_Y + 0.5f, domain.surfaceOf(NavNodes.pack(5, STAND_Y, 4)), EPSILON);
    }

    @Test
    void theCellAboveAPartialBlockIsNotASecondNode() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP).partial(5, STAND_Y, 4, 0.5f);
        GroundNavDomain domain = domain(world, SHORT_WALKER, 0, 0, 0);
        Moves moves = movesFrom(world, SHORT_WALKER, 4, STAND_Y, 4);

        assertTrue(Float.isNaN(domain.surfaceOf(NavNodes.pack(5, STAND_Y + 1, 4))),
                "one surface must map to exactly one node, or the search expands it twice");
        assertFalse(moves.reaches(5, STAND_Y + 1, 4));
    }

    @Test
    void jumpingOntoAFullBlockCostsAJump() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP).solid(5, STAND_Y, 4);
        Moves moves = movesFrom(world, SHORT_WALKER, 4, STAND_Y, 4);

        assertEquals(1.0f + SHORT_WALKER.jumpCost(), moves.costTo(5, STAND_Y + 1, 4), EPSILON);
    }

    @Test
    void aTwoBlockLedgeIsOutOfReach() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP).column(5, 4, STAND_Y, STAND_Y + 1);
        Moves moves = movesFrom(world, SHORT_WALKER, 4, STAND_Y, 4);

        assertFalse(moves.reachesColumn(5, 4), "nothing in that column should be reachable");
        // Both +x diagonals go with it: they would have to swing through the blocked column.
        assertEquals(5, moves.count());
    }

    @Test
    void aNonJumperCannotEvenMountOneBlock() {
        NavProfile stepper = new NavProfile(0.9f, 0, 0.5f, 0.5f, 0.5f, 3.0f, false,
                3.0f, 8.0f, 0.5f, 2.0f, 0.5f);
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP).solid(5, STAND_Y, 4);

        assertFalse(stepper.canJump());
        assertFalse(movesFrom(world, stepper, 4, STAND_Y, 4).reachesColumn(5, 4));
    }

    @Test
    void dropsWithinTheFallLimitAreTakenAndCosted() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP);
        digShaft(world, 5, 4, GROUND_TOP - 2, GROUND_TOP); // floor left at GROUND_TOP - 3

        Moves moves = movesFrom(world, SHORT_WALKER, 4, STAND_Y, 4);

        float drop = 3.0f;
        assertEquals(1.0f + SHORT_WALKER.fallCostPerBlock() * drop,
                moves.costTo(5, STAND_Y - 3, 4), EPSILON);
    }

    @Test
    void dropsBeyondTheFallLimitAreRefused() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP);
        digShaft(world, 5, 4, GROUND_TOP - 5, GROUND_TOP); // a six-block drop

        assertFalse(movesFrom(world, SHORT_WALKER, 4, STAND_Y, 4).reachesColumn(5, 4));
    }

    // ── Shape rules ──────────────────────────────────────────────────────────

    @Test
    void diagonalsWillNotSqueezeThroughACorner() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP)
                .column(5, 4, STAND_Y, STAND_Y + 1)
                .column(4, 5, STAND_Y, STAND_Y + 1);

        Moves moves = movesFrom(world, SHORT_WALKER, 4, STAND_Y, 4);

        assertFalse(moves.reaches(5, STAND_Y, 5), "cutting between two blocks would clip both");
    }

    @Test
    void headroomIsRequiredForTheAgentsFullHeight() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP).solid(5, STAND_Y + 1, 4);

        assertTrue(movesFrom(world, SHORT_WALKER, 4, STAND_Y, 4).reaches(5, STAND_Y, 4),
                "a 0.9-tall agent fits under a one-cell gap");
        assertFalse(movesFrom(world, NavProfile.walker(1.6f, 0), 4, STAND_Y, 4).reaches(5, STAND_Y, 4),
                "a 1.6-tall agent does not");
    }

    @Test
    void aWideAgentDoesNotFitAOneBlockCorridor() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP);
        for (int x = 0; x < 9; x++) {
            world.column(x, 3, STAND_Y, STAND_Y + 1);
            world.column(x, 5, STAND_Y, STAND_Y + 1);
        }

        assertTrue(movesFrom(world, SHORT_WALKER, 4, STAND_Y, 4).reaches(5, STAND_Y, 4),
                "a single-column agent walks the corridor");
        assertEquals(0, movesFrom(world, NavProfile.walker(0.9f, 1), 4, STAND_Y, 4).count(),
                "a three-column agent cannot even stand in it");
    }

    // ── Cell kinds ───────────────────────────────────────────────────────────

    @Test
    void unloadedTerrainIsImpassable() {
        // From the box's edge column, three of the eight neighbours lie outside the world.
        Moves moves = movesFrom(BoxNavVolume.ground(9, 70, 9, GROUND_TOP), SHORT_WALKER, 0, STAND_Y, 4);

        assertEquals(5, moves.count());
        assertFalse(moves.reachesColumn(-1, 4));
    }

    @Test
    void shallowWaterIsWadedAtAPenalty() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP).pond(5, 4, GROUND_TOP, 1);
        Moves moves = movesFrom(world, SHORT_WALKER, 4, STAND_Y, 4);

        assertEquals(SHORT_WALKER.wadeCostMultiplier()
                        + SHORT_WALKER.fallCostPerBlock() * ENTRY_DROP,
                moves.costTo(5, GROUND_TOP, 4), EPSILON,
                "the wading penalty, plus the eighth-block step down to the waterline");
    }

    @Test
    void deepWaterStopsANonSwimmerAndCarriesASwimmer() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP).pond(5, 4, GROUND_TOP, 2);

        assertFalse(movesFrom(world, SHORT_WALKER, 4, STAND_Y, 4).reachesColumn(5, 4),
                "out of its depth");

        NavProfile swimmer = NavProfile.swimmer(0.9f, 0);
        assertEquals(swimmer.swimCostMultiplier() + swimmer.fallCostPerBlock() * ENTRY_DROP,
                movesFrom(world, swimmer, 4, STAND_Y, 4).costTo(5, GROUND_TOP, 4), EPSILON,
                "the swimming penalty, plus the small step down to the waterline");
    }

    /**
     * Deep water must not be a one-way trap.
     *
     * <p>A swimmer submerged in two-deep water has to be able to reach the one-deep shelf beside it
     * — that shelf is the only route back to land. Refusing every rise from a submerged cell (on
     * the grounds that a swimming agent has nothing to push off) leaves it circling the deep end
     * with no path out, because this domain has no vertical-only moves either: depth can only be
     * changed by a move that also goes sideways.
     */
    @Test
    void aSwimmerCanClimbFromDeepWaterOntoTheShallowShelf() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP)
                .pond(4, 4, GROUND_TOP, 2)   // two deep...
                .pond(5, 4, GROUND_TOP, 1);  // ...beside a one-deep shelf

        NavProfile swimmer = NavProfile.swimmer(0.9f, 0);
        Moves moves = movesFrom(world, swimmer, 4, GROUND_TOP, 4); // afloat over the deep end

        assertTrue(moves.reaches(5, GROUND_TOP, 4),
                "a swimmer must be able to cross onto the shelf, or it can never leave");
    }

    /** And the same swimmer can then leave the shelf for dry land. */
    @Test
    void aSwimmerOnTheShelfCanStepOutOntoLand() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP)
                .pond(5, 4, GROUND_TOP, 1)      // shelf water, waterline just below the shore
                .solid(6, STAND_Y, 4);          // and a bank one block above that shore

        Moves moves = movesFrom(world, NavProfile.swimmer(0.9f, 0), 5, GROUND_TOP, 4);

        assertTrue(moves.reaches(6, STAND_Y + 1, 4), "and then out onto the bank");
    }

    /**
     * A shore standing a block above the water is the shape that traps mobs, because it is two
     * cells tall measured from a swimmer's dangling feet but only one block above the waterline it
     * is actually floating at. A stroke reaches it, so a route has to be willing to plan it.
     */
    @Test
    void aSwimmerCanPlanTheClimbOntoAShoreAboveTheWaterline() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP)
                .pond(4, 4, GROUND_TOP, 2)
                .solid(5, STAND_Y, 4); // shore one block proud of the water's own surface

        Moves moves = movesFrom(world, NavProfile.swimmer(0.9f, 0), 4, GROUND_TOP, 4);

        assertTrue(moves.reaches(5, STAND_Y + 1, 4),
                "a stroke clears a one-block shore; refusing to plan it is what strands mobs");
    }

    /** But a wall it genuinely cannot stroke over is still a wall. */
    @Test
    void aSwimmerWillNotPlanAClimbBeyondItsStroke() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP)
                .pond(4, 4, GROUND_TOP, 2)
                .column(5, 4, STAND_Y, STAND_Y + 2); // three blocks of cliff

        Moves moves = movesFrom(world, NavProfile.swimmer(0.9f, 0), 4, GROUND_TOP, 4);

        assertFalse(moves.reachesColumn(5, 4), "a cliff is not an exit");
    }

    @Test
    void hazardsAreNeverEntered() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP).hazard(5, STAND_Y, 4);

        assertFalse(movesFrom(world, SHORT_WALKER, 4, STAND_Y, 4).reachesColumn(5, 4));
    }

    // ── Snapping ─────────────────────────────────────────────────────────────

    @Test
    void snapToSurfaceFindsTheGroundUnderAPositionInTheAir() {
        GroundNavDomain domain = domain(BoxNavVolume.ground(9, 70, 9, GROUND_TOP), SHORT_WALKER, 0, 0, 0);

        assertEquals(NavNodes.pack(4, STAND_Y, 4), domain.snapToSurface(4, STAND_Y + 6, 4, 8, 2));
    }

    @Test
    void snapToSurfaceGivesUpInsideSolidRock() {
        BoxNavVolume world = BoxNavVolume.ground(9, 70, 9, GROUND_TOP);
        GroundNavDomain domain = domain(world, SHORT_WALKER, 0, 0, 0);

        assertEquals(GroundNavDomain.NO_NODE, domain.snapToSurface(4, 10, 4, 4, 4));
    }

    // ── End to end ───────────────────────────────────────────────────────────

    @Test
    void routesAroundAWallToReachTheOtherSide() {
        BoxNavVolume world = BoxNavVolume.ground(12, 70, 12, GROUND_TOP);
        for (int z = 0; z <= 10; z++) {
            world.column(5, z, STAND_Y, STAND_Y + 1); // wall with a gap at z == 11
        }
        GroundNavDomain domain = domain(world, SHORT_WALKER, 10, STAND_Y, 0);

        SearchResult result = new AStar().search(domain, NavNodes.pack(0, STAND_Y, 0),
                SearchLimits.DEFAULT.withMaxExpansions(20_000), CancelToken.NEVER);

        assertEquals(SearchResult.Status.FOUND, result.status());
        boolean throughTheGap = false;
        for (long node : result.nodes()) {
            if (NavNodes.x(node) == 5) {
                assertEquals(11, NavNodes.z(node), "the wall is only open at z == 11");
                throughTheGap = true;
            }
        }
        assertTrue(throughTheGap, "the route has to cross the wall line somewhere");
    }

    @Test
    void aSealedRoomYieldsABestEffortPathNotACrash() {
        BoxNavVolume world = BoxNavVolume.ground(12, 70, 12, GROUND_TOP);
        for (int z = 0; z < 12; z++) {
            world.column(5, z, STAND_Y, STAND_Y + 1); // no gap this time
        }
        GroundNavDomain domain = domain(world, SHORT_WALKER, 10, STAND_Y, 0);

        SearchResult result = new AStar().search(domain, NavNodes.pack(0, STAND_Y, 0),
                SearchLimits.DEFAULT.withMaxExpansions(20_000), CancelToken.NEVER);

        assertEquals(SearchResult.Status.PARTIAL_UNREACHABLE, result.status());
        assertTrue(result.isUsable(), "the mob should still walk up to the wall");
        assertEquals(4, NavNodes.x(result.node(result.length() - 1)), "as close as it can get");
    }

    // ── Fixture helpers ──────────────────────────────────────────────────────

    private static GroundNavDomain domain(BoxNavVolume world, NavProfile profile,
                                          int goalX, int goalY, int goalZ) {
        return new GroundNavDomain(new NavCellCache(world), profile, goalX, goalY, goalZ, 0.0f);
    }

    private static Moves movesFrom(BoxNavVolume world, NavProfile profile, int x, int y, int z) {
        GroundNavDomain domain = domain(world, profile, 0, 0, 0);
        long[] nodes = new long[domain.maxSuccessors()];
        float[] costs = new float[domain.maxSuccessors()];
        int count = domain.successors(NavNodes.pack(x, y, z), nodes, costs);
        return new Moves(nodes, costs, count);
    }

    /** Clears a column between two heights, leaving whatever is below as the new floor. */
    private static void digShaft(BoxNavVolume world, int x, int z, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) {
            world.air(x, y, z);
        }
    }

    private record Moves(long[] nodes, float[] costs, int count) {

        float costTo(int x, int y, int z) {
            long wanted = NavNodes.pack(x, y, z);
            for (int i = 0; i < count; i++) {
                if (nodes[i] == wanted) {
                    return costs[i];
                }
            }
            throw new AssertionError("no move to " + NavNodes.toString(wanted));
        }

        boolean reaches(int x, int y, int z) {
            long wanted = NavNodes.pack(x, y, z);
            for (int i = 0; i < count; i++) {
                if (nodes[i] == wanted) {
                    return true;
                }
            }
            return false;
        }

        /** Whether any move lands anywhere in the given column, at any height. */
        boolean reachesColumn(int x, int z) {
            for (int i = 0; i < count; i++) {
                if (NavNodes.x(nodes[i]) == x && NavNodes.z(nodes[i]) == z) {
                    return true;
                }
            }
            return false;
        }
    }
}
