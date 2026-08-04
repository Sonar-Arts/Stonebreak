package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.wayfind.SearchLimits;
import com.openmason.engine.wayfind.voxel.NavProfile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The asynchronous contract, tested without any asynchrony: the executor is injected, so these run
 * the searches on the test thread (or hold them, or drop them) and the timing disappears.
 *
 * <p>The properties here are the ones that decide whether async AI is maintainable or a source of
 * intermittent bugs — every request resolves exactly once however it ends, load sheds instead of
 * queueing without bound, and teardown leaves nothing running.
 */
class PathfindingServiceTest {

    private static final int GROUND_TOP = 63;
    private static final int STAND_Y = 64;
    private static final NavProfile WALKER = NavProfile.walker(0.9f, 0);
    private static final Executor SAME_THREAD = Runnable::run;

    @Test
    void findsARouteAcrossOpenGround() {
        PathfindingService service = new PathfindingService(new FlatNavVolume(GROUND_TOP), SAME_THREAD);

        PathRequest request = submit(service, 0.5f, 5.5f);

        assertNotNull(request);
        assertTrue(request.isDone(), "a same-thread executor finishes before submit returns");
        Path path = request.result();
        assertFalse(path.isEmpty());
        assertTrue(path.isComplete(), "open ground should reach the goal");
        assertEquals(5.5f, path.x(path.size() - 1), 1.0f);
        assertEquals(STAND_Y, path.y(path.size() - 1), 0.01f);
        service.close();
    }

    @Test
    void routesAroundAnObstacle() {
        FlatNavVolume world = new FlatNavVolume(GROUND_TOP);
        for (int z = -4; z <= 4; z++) {
            world.wall(3, z); // a wall the mob must walk around, not over
        }
        PathfindingService service = new PathfindingService(world, SAME_THREAD);

        Path path = submit(service, 0.5f, 6.5f).result();

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
        FlatNavVolume world = new FlatNavVolume(GROUND_TOP);
        for (int z = -60; z <= 60; z++) {
            world.wall(3, z); // sealed across the whole search radius
        }
        PathfindingService service = new PathfindingService(world, SAME_THREAD);

        Path path = submit(service, 0.5f, 8.5f).result();

        assertFalse(path.isEmpty(), "a mob should still walk up to the wall");
        assertFalse(path.isComplete(), "and know it never got there");
        assertTrue(service.stats().partial() > 0);
        service.close();
    }

    @Test
    void aStartBuriedInRockResolvesToNoRoute() {
        PathfindingService service = new PathfindingService(new FlatNavVolume(GROUND_TOP), SAME_THREAD);

        PathRequest request = service.submit(new Vector3f(0.5f, 10.0f, 0.5f),
                new Vector3f(5.5f, STAND_Y, 0.5f), 1.0f, WALKER, SearchLimits.DEFAULT);

        assertTrue(request.isDone());
        assertTrue(request.result().isEmpty());
        service.close();
    }

    @Test
    void aFailingWorldResolvesTheRequestInsteadOfHangingIt() {
        PathfindingService service =
                new PathfindingService(new FlatNavVolume(GROUND_TOP).failOnRead(), SAME_THREAD);

        PathRequest request = submit(service, 0.5f, 5.5f);

        assertTrue(request.isDone(), "an agent must never be left waiting on a crashed search");
        assertTrue(request.result().isEmpty());
        assertEquals(1, service.stats().failed());
        service.close();
    }

    @Test
    void loadShedsOnceTooManySearchesAreInFlight() {
        ManualExecutor executor = new ManualExecutor();
        PathfindingService service = new PathfindingService(new FlatNavVolume(GROUND_TOP), executor);

        for (int i = 0; i < PathfindingService.DEFAULT_MAX_IN_FLIGHT; i++) {
            assertNotNull(submit(service, 0.5f, 5.5f), "request " + i + " should be accepted");
        }

        assertNull(submit(service, 0.5f, 5.5f), "over the cap, submit refuses rather than queueing");
        assertTrue(service.stats().rejected() > 0);
        assertEquals(PathfindingService.DEFAULT_MAX_IN_FLIGHT, service.stats().inFlight());

        executor.runAll();
        assertEquals(0, service.stats().inFlight(), "finished searches release their slot");
        service.close();
    }

    @Test
    void cancelledSearchesResolveEmptyRatherThanRunningOn() {
        ManualExecutor executor = new ManualExecutor();
        PathfindingService service = new PathfindingService(new FlatNavVolume(GROUND_TOP), executor);

        PathRequest request = submit(service, 0.5f, 5.5f);
        assertNotNull(request);
        request.cancel();
        executor.runAll();

        assertTrue(request.isDone());
        assertTrue(request.result().isEmpty());
        service.close();
    }

    @Test
    void closingCancelsInFlightWorkAndRefusesMore() {
        ManualExecutor executor = new ManualExecutor();
        PathfindingService service = new PathfindingService(new FlatNavVolume(GROUND_TOP), executor);
        PathRequest inFlight = submit(service, 0.5f, 5.5f);

        service.close();

        assertTrue(service.isClosed());
        assertTrue(inFlight.isCancelled(), "teardown must stop searches reading a dying world");
        assertNull(submit(service, 0.5f, 5.5f));

        executor.runAll();
        assertTrue(inFlight.isDone(), "even a cancelled request resolves");
        assertTrue(inFlight.result().isEmpty());
    }

    @Test
    void closingTwiceIsHarmless() {
        PathfindingService service = new PathfindingService(new FlatNavVolume(GROUND_TOP), SAME_THREAD);
        service.close();
        service.close();
        assertTrue(service.isClosed());
    }

    private static PathRequest submit(PathfindingService service, float fromX, float toX) {
        return service.submit(new Vector3f(fromX, STAND_Y, 0.5f),
                new Vector3f(toX, STAND_Y, 0.5f), 1.0f, WALKER, SearchLimits.DEFAULT);
    }

    /** Holds submitted work until the test says otherwise, standing in for a busy pool. */
    private static final class ManualExecutor implements Executor {
        private final List<Runnable> queued = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        void runAll() {
            List<Runnable> pending = new ArrayList<>(queued);
            queued.clear();
            pending.forEach(Runnable::run);
        }
    }
}
