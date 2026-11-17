package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.nav.NavLimits;
import com.openmason.engine.nav.NavPath;
import com.openmason.engine.nav.NavWorld;
import com.openmason.engine.physics.PhysicsWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service contract: searches queue, run on {@link PathfindingService#step}, and every one ends
 * exactly once — found, partial, empty or dropped; load sheds instead of queueing without bound; and
 * teardown leaves nothing behind. Timing is the test's: nothing runs until it steps.
 */
class PathfindingServiceTest {

    private static final int GROUND_TOP = 63;
    private static final int STAND_Y = 64;
    private static final int WORLD_HEIGHT = 256;
    private static final com.openmason.engine.nav.GroundProfile WALKER =
            new com.openmason.engine.nav.GroundProfile(0.9f, 0, 0.5f, 1.125f, 1.5f, 3.0f, false, 3.0f, 8.0f,
                    0.5f, 2.0f, 0.5f);
    private static final NavLimits LIMITS = new NavLimits(500, Float.MAX_VALUE, 1.0f);

    private PhysicsWorld physics;

    @AfterEach
    void tearDown() {
        if (physics != null) {
            physics.close();
        }
    }

    private PathfindingService serviceOver(FlatWorldCells cells) {
        physics = cells.physics();
        return PathfindingService.over(physics, WORLD_HEIGHT);
    }

    @Test
    void findsARouteAcrossOpenGround() {
        PathfindingService service = serviceOver(new FlatWorldCells(GROUND_TOP));

        long request = submit(service, 0.5f, 5.5f);

        assertTrue(request > 0);
        assertNull(service.nav().poll(request), "nothing runs until the service steps");
        service.step();
        NavPath path = service.nav().poll(request).path();
        assertFalse(path.isEmpty());
        assertTrue(path.isComplete(), "open ground should reach the goal");
        assertEquals(5.5f, path.x(path.size() - 1), 1.0f);
        assertEquals(STAND_Y, path.y(path.size() - 1), 0.01f);
        service.close();
    }

    @Test
    void routesAroundAnObstacle() {
        FlatWorldCells world = new FlatWorldCells(GROUND_TOP);
        for (int z = -4; z <= 4; z++) {
            world.wall(3, z); // a wall the mob must walk around, not over
        }
        PathfindingService service = serviceOver(world);

        NavPath path = run(service, submit(service, 0.5f, 6.5f)).path();

        assertTrue(path.isComplete());
        boolean detoured = false;
        for (int i = 0; i < path.size(); i++) {
            if (Math.abs(path.z(i)) > 4.0f) {
                detoured = true;
            }
        }
        assertTrue(detoured, "the only way past is around the ends of the wall");
        service.close();
    }

    @Test
    void anUnreachableGoalStillComesBackWithABestEffortRoute() {
        FlatWorldCells world = new FlatWorldCells(GROUND_TOP);
        for (int z = -60; z <= 60; z++) {
            world.wall(3, z); // sealed across the whole search radius
        }
        PathfindingService service = serviceOver(world);

        NavPath path = run(service, submit(service, 0.5f, 8.5f)).path();

        assertFalse(path.isEmpty(), "a mob should still walk up to the wall");
        assertFalse(path.isComplete(), "and know it never got there");
        assertTrue(service.stats().partial() > 0);
        service.close();
    }

    @Test
    void aStartBuriedInRockResolvesToNoRoute() {
        PathfindingService service = serviceOver(new FlatWorldCells(GROUND_TOP));

        long request = service.nav().searchGround(new NavWorld.GroundQuery(0.5f, 10.0f, 0.5f, 5.5f, STAND_Y, 0.5f,
                1.0f, 4, 2, WALKER, LIMITS));

        NavWorld.Outcome outcome = run(service, request);
        assertTrue(outcome.path().isEmpty());
        assertEquals(NavWorld.SEARCH_NO_PATH, outcome.status());
        service.close();
    }

    @Test
    void aFailingWorldResolvesTheRequestInsteadOfHangingIt() {
        PathfindingService service = serviceOver(new FlatWorldCells(GROUND_TOP).failOnRead());

        long request = submit(service, 0.5f, 5.5f);

        // A world that cannot be read is unloaded to physics, so to navigation: unknown, no route.
        NavWorld.Outcome outcome = run(service, request);
        assertNotNull(outcome, "an agent must never be left waiting on a crashed search");
        assertTrue(outcome.path().isEmpty());
        service.close();
    }

    @Test
    void loadShedsOnceTooManySearchesAreInFlight() {
        PathfindingService service = serviceOver(new FlatWorldCells(GROUND_TOP));

        for (int i = 0; i < PathfindingService.DEFAULT_MAX_IN_FLIGHT; i++) {
            assertTrue(submit(service, 0.5f, 5.5f) > 0, "request " + i + " should be accepted");
        }

        assertEquals(0L, submit(service, 0.5f, 5.5f), "over the cap, the service refuses rather than queueing");
        assertTrue(service.stats().rejected() > 0);
        assertEquals(PathfindingService.DEFAULT_MAX_IN_FLIGHT, service.stats().inFlight());

        service.step();
        assertEquals(0, service.stats().inFlight(), "finished searches release their slot");
        service.close();
    }

    @Test
    void cancelledSearchesResolveEmptyRatherThanRunningOn() {
        PathfindingService service = serviceOver(new FlatWorldCells(GROUND_TOP));

        long request = submit(service, 0.5f, 5.5f);
        assertTrue(request > 0);
        service.nav().release(request);
        service.step();

        assertEquals(0, service.stats().completed(), "a released search never runs");
        assertEquals(0, service.stats().inFlight());
        assertThrows(IllegalArgumentException.class, () -> service.nav().poll(request));
        service.close();
    }

    @Test
    void closingCancelsInFlightWorkAndRefusesMore() {
        PathfindingService service = serviceOver(new FlatWorldCells(GROUND_TOP));
        long inFlight = submit(service, 0.5f, 5.5f);
        assertTrue(inFlight > 0);

        service.close();

        assertTrue(service.isClosed());
        assertEquals(0, service.stats().inFlight(), "teardown drops what was queued");
        assertThrows(IllegalStateException.class, () -> submit(service, 0.5f, 5.5f));
        assertThrows(IllegalStateException.class, service::step);
    }

    @Test
    void closingTwiceIsHarmless() {
        PathfindingService service = serviceOver(new FlatWorldCells(GROUND_TOP));
        service.close();
        service.close();
        assertTrue(service.isClosed());
    }

    private static long submit(PathfindingService service, float fromX, float toX) {
        return service.nav().searchGround(new NavWorld.GroundQuery(fromX, STAND_Y, 0.5f, toX, STAND_Y, 0.5f, 1.0f,
                4, 2, WALKER, LIMITS));
    }

    private static NavWorld.Outcome run(PathfindingService service, long request) {
        service.step();
        NavWorld.Outcome outcome = service.nav().poll(request);
        service.nav().release(request);
        return outcome;
    }
}
