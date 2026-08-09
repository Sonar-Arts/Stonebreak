package com.openmason.engine.wayfind.voxel;

import com.openmason.engine.wayfind.AStar;
import com.openmason.engine.wayfind.CancelToken;
import com.openmason.engine.wayfind.SearchLimits;
import com.openmason.engine.wayfind.SearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flight routing rules, tested against terrain built block by block rather than against stubs — the
 * whole question an air domain answers is "can this shape be flown through", and a mock cannot pose
 * it.
 *
 * <p>The recurring assertion is that a route never enters a blocked cell. That is stricter than it
 * sounds: cells are four blocks a side and a cell is only flyable when every block in it is empty,
 * so "the route stays in flyable cells" is exactly the guarantee a bird's clearance depends on.
 */
class AirNavDomainTest {

    private static final int CELL = 4;
    private static final int SIZE = 64;

    /** A world of open sky over flat ground, with no ceiling worth worrying about. */
    private static BoxNavVolume sky() {
        return BoxNavVolume.ground(SIZE, SIZE, SIZE, 7);
    }

    private static AirNavProfile profile(float cruiseY) {
        return AirNavProfile.flyer(CELL, cruiseY, 0, SIZE - 1);
    }

    /**
     * The same profile with unloaded air refused. Tests about a <em>shape</em> use this: outside the
     * box every cell reads UNKNOWN, and a flyer allowed through unknown air can always sidestep a
     * wall by leaving the built world, which would answer a question nobody asked.
     */
    private static AirNavProfile boxedProfile(float cruiseY) {
        return profile(cruiseY).withUnknownAllowed(false);
    }

    private static SearchResult fly(NavVolume volume, AirNavProfile profile,
                                    int fromX, int fromY, int fromZ,
                                    int toX, int toY, int toZ) {
        AirNavDomain domain = new AirNavDomain(volume, profile,
                AirNavDomain.cellOf(profile, toX, toY, toZ), CELL);
        return new AStar().search(domain, AirNavDomain.cellOf(profile, fromX, fromY, fromZ),
                new SearchLimits(20_000, Float.MAX_VALUE, 1.0f), CancelToken.NEVER);
    }

    private static void assertRouteIsFlyable(NavVolume volume, AirNavProfile profile, long[] nodes) {
        AirNavDomain domain = new AirNavDomain(volume, profile, nodes[nodes.length - 1], 0.0f);
        for (long node : nodes) {
            assertTrue(domain.isFlyable(node),
                    "route passes through unflyable cell " + NavNodes.toString(node));
        }
    }

    @Test
    @DisplayName("open sky routes in a straight line")
    void openSkyRoutesStraight() {
        BoxNavVolume volume = sky();
        AirNavProfile profile = profile(32.0f);

        SearchResult result = fly(volume, profile, 8, 32, 8, 56, 32, 8);

        assertTrue(result.reachedGoal(), "a clear corridor should be reachable");
        assertRouteIsFlyable(volume, profile, result.nodes());
        // Every node holds the cruise band and marches along X: nothing here justifies a detour.
        int cruiseCell = Math.floorDiv(32, CELL);
        for (long node : result.nodes()) {
            assertEquals(cruiseCell, NavNodes.y(node),
                    "clear sky should not move a route off cruise altitude");
            assertEquals(Math.floorDiv(8, CELL), NavNodes.z(node),
                    "clear sky should not move a route sideways");
        }
    }

    @Test
    @DisplayName("a ridge the flyer can top is climbed over, not detoured around")
    void climbsOverALowRidge() {
        BoxNavVolume volume = sky();
        // A ridge across the whole Z extent, topping out at y=39 — above cruise, but the sky above
        // it is open and there is no way round it.
        for (int z = 0; z < SIZE; z++) {
            for (int y = 8; y <= 39; y++) {
                volume.solid(32, y, z);
                volume.solid(33, y, z);
            }
        }
        AirNavProfile profile = boxedProfile(32.0f);

        SearchResult result = fly(volume, profile, 8, 32, 8, 56, 32, 8);

        assertTrue(result.reachedGoal());
        assertRouteIsFlyable(volume, profile, result.nodes());
        int highest = 0;
        for (long node : result.nodes()) {
            highest = Math.max(highest, NavNodes.y(node));
        }
        assertTrue(highest * CELL >= 40, "the route must clear the ridge, not fly through it");
    }

    @Test
    @DisplayName("a wall too tall to top is flown around through its gap")
    void routesAroundAWallThroughItsGap() {
        BoxNavVolume volume = sky();
        // A wall from the ground to the ceiling, with a gap left at high Z. The only way through is
        // sideways, which is the case a climb-over look-ahead can never solve.
        for (int z = 0; z < 44; z++) {
            for (int y = 8; y < SIZE; y++) {
                volume.solid(32, y, z);
                volume.solid(33, y, z);
            }
        }
        AirNavProfile profile = boxedProfile(32.0f);

        SearchResult result = fly(volume, profile, 8, 32, 8, 56, 32, 8);

        assertTrue(result.reachedGoal(), "the gap makes the goal reachable");
        assertRouteIsFlyable(volume, profile, result.nodes());

        // It must have gone through the gap: some node beyond the wall's Z extent.
        boolean usedGap = false;
        for (long node : result.nodes()) {
            if (NavNodes.z(node) * CELL >= 44) {
                usedGap = true;
                break;
            }
        }
        assertTrue(usedGap, "the route should detour through the gap at high Z");
    }

    @Test
    @DisplayName("a sealed goal returns the best partial rather than nothing")
    void sealedGoalReturnsAPartialRoute() {
        BoxNavVolume volume = sky();
        for (int z = 0; z < SIZE; z++) {
            for (int y = 0; y < SIZE; y++) {
                volume.solid(32, y, z);
                volume.solid(33, y, z);
            }
        }
        AirNavProfile profile = boxedProfile(32.0f);

        SearchResult result = fly(volume, profile, 8, 32, 8, 56, 32, 8);

        assertFalse(result.reachedGoal());
        assertTrue(result.isUsable(), "an unreachable goal must still point the flyer somewhere");
        assertRouteIsFlyable(volume, profile, result.nodes());
    }

    @Test
    @DisplayName("water blocks a flight corridor — a bird lands on it, it does not fly through it")
    void waterIsNotFlyable() {
        BoxNavVolume volume = BoxNavVolume.ground(SIZE, SIZE, SIZE, 7);
        for (int x = 16; x < 24; x++) {
            for (int z = 0; z < SIZE; z++) {
                volume.water(x, 12, z);
            }
        }
        AirNavProfile profile = profile(12.0f);
        AirNavDomain domain = new AirNavDomain(volume, profile,
                AirNavDomain.cellOf(profile, 20, 12, 8), 0.0f);

        assertFalse(domain.isFlyable(AirNavDomain.cellOf(profile, 20, 12, 8)),
                "a cell holding water is not a flight corridor");
    }

    @Test
    @DisplayName("a goal inside terrain snaps out onto real airspace")
    void blockedGoalSnapsToFlyableAir() {
        BoxNavVolume volume = sky();
        for (int x = 30; x < 36; x++) {
            for (int z = 30; z < 36; z++) {
                for (int y = 8; y <= 39; y++) {
                    volume.solid(x, y, z);
                }
            }
        }
        AirNavProfile profile = profile(32.0f);
        long buried = AirNavDomain.cellOf(profile, 32, 32, 32);
        AirNavDomain domain = new AirNavDomain(volume, profile, buried, 0.0f);

        assertFalse(domain.isFlyable(buried));
        long snapped = domain.snapToFlyable(32, 32, 32, 2);

        assertNotEquals(AirNavDomain.NO_NODE, snapped);
        assertNotEquals(buried, snapped);
        assertTrue(domain.isFlyable(snapped));
    }

    @Test
    @DisplayName("unloaded sky is routed through, but known-clear sky is preferred")
    void unknownAirIsPassableAtAPremium() {
        BoxNavVolume volume = sky();
        AirNavProfile allowing = profile(32.0f);
        AirNavProfile refusing = allowing.withUnknownAllowed(false);

        // Outside the box every cell reports UNKNOWN, exactly as an unloaded chunk does.
        long outside = AirNavDomain.cellOf(allowing, SIZE + 8, 32, 8);
        assertTrue(new AirNavDomain(volume, allowing, outside, 0.0f).isFlyable(outside));
        assertFalse(new AirNavDomain(volume, refusing, outside, 0.0f).isFlyable(outside));
    }

    @Test
    @DisplayName("string-pulling shortens a route without ever leaving flyable air")
    void stringPullKeepsTheRouteClear() {
        BoxNavVolume volume = sky();
        for (int z = 0; z < 44; z++) {
            for (int y = 8; y < SIZE; y++) {
                volume.solid(32, y, z);
                volume.solid(33, y, z);
            }
        }
        AirNavProfile profile = boxedProfile(32.0f);
        AirNavDomain domain = new AirNavDomain(volume, profile,
                AirNavDomain.cellOf(profile, 56, 32, 8), CELL);
        SearchResult result = new AStar().search(domain,
                AirNavDomain.cellOf(profile, 8, 32, 8),
                new SearchLimits(20_000, Float.MAX_VALUE, 1.0f), CancelToken.NEVER);

        long[] pulled = domain.stringPull(result.nodes());

        assertTrue(pulled.length <= result.nodes().length, "smoothing must not lengthen a route");
        assertEquals(result.nodes()[0], pulled[0]);
        assertEquals(result.nodes()[result.nodes().length - 1], pulled[pulled.length - 1]);
        assertRouteIsFlyable(volume, profile, pulled);
        for (int i = 0; i < pulled.length - 1; i++) {
            assertTrue(domain.lineFlyable(pulled[i], pulled[i + 1]),
                    "every surviving leg must be flyable in a straight line");
        }
    }

    @Test
    @DisplayName("the same terrain routes identically twice — hosts and clients must agree")
    void searchIsDeterministic() {
        BoxNavVolume volume = sky();
        for (int z = 0; z < 40; z++) {
            for (int y = 8; y < SIZE; y++) {
                volume.solid(32, y, z);
            }
        }
        AirNavProfile profile = boxedProfile(32.0f);

        SearchResult first = fly(volume, profile, 8, 32, 8, 56, 32, 8);
        SearchResult second = fly(volume, profile, 8, 32, 8, 56, 32, 8);

        assertArrayEqualsLong(first.nodes(), second.nodes());
        assertEquals(first.expansions(), second.expansions());
    }

    @Test
    @DisplayName("a flyer boxed in below its floor cannot route downward out of the world")
    void flightFloorIsRespected() {
        BoxNavVolume volume = sky();
        AirNavProfile profile = new AirNavProfile(CELL, 32.0f,
                0.35f, 0.5f, 0.15f, true, 0.75f, 24, SIZE - 1);
        AirNavDomain domain = new AirNavDomain(volume, profile,
                AirNavDomain.cellOf(profile, 8, 32, 8), 0.0f);

        assertFalse(domain.isFlyable(AirNavDomain.cellOf(profile, 8, 16, 8)),
                "cells below the flight floor are not flyable however empty they are");
        assertTrue(domain.isFlyable(AirNavDomain.cellOf(profile, 8, 32, 8)));
    }

    private static void assertArrayEqualsLong(long[] expected, long[] actual) {
        assertEquals(expected.length, actual.length, "route lengths differ");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "route differs at index " + i);
        }
    }
}
