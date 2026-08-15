package com.openmason.engine.wayfind.voxel;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Node packing is the one place where a silent bug corrupts everything downstream: two cells
 * aliasing onto one key would make the search treat distinct places as the same node, and a
 * mis-signed unpack would send a mob to the mirror image of its destination. Both would look like
 * "the AI is weird" rather than like a packing bug, so the round trip is pinned exactly.
 */
class NavNodesTest {

    @Test
    void roundTripsPositiveCoordinates() {
        long node = NavNodes.pack(1234, 64, 5678);

        assertEquals(1234, NavNodes.x(node));
        assertEquals(64, NavNodes.y(node));
        assertEquals(5678, NavNodes.z(node));
    }

    @Test
    void roundTripsNegativeHorizontals() {
        long node = NavNodes.pack(-1234, 0, -5678);

        assertEquals(-1234, NavNodes.x(node));
        assertEquals(0, NavNodes.y(node));
        assertEquals(-5678, NavNodes.z(node));
    }

    @Test
    void roundTripsTheExtremesOfEachAxis() {
        long low = NavNodes.pack(NavNodes.MIN_HORIZONTAL, 0, NavNodes.MIN_HORIZONTAL);
        assertEquals(NavNodes.MIN_HORIZONTAL, NavNodes.x(low));
        assertEquals(NavNodes.MIN_HORIZONTAL, NavNodes.z(low));
        assertEquals(0, NavNodes.y(low));

        long high = NavNodes.pack(NavNodes.MAX_HORIZONTAL, NavNodes.MAX_Y, NavNodes.MAX_HORIZONTAL);
        assertEquals(NavNodes.MAX_HORIZONTAL, NavNodes.x(high));
        assertEquals(NavNodes.MAX_HORIZONTAL, NavNodes.z(high));
        assertEquals(NavNodes.MAX_Y, NavNodes.y(high));
    }

    @Test
    void distinctCellsNeverShareAKey() {
        Set<Long> seen = new HashSet<>();
        for (int x = -3; x <= 3; x++) {
            for (int y = 0; y <= 6; y++) {
                for (int z = -3; z <= 3; z++) {
                    assertTrue(seen.add(NavNodes.pack(x, y, z)),
                            "collision at " + x + ", " + y + ", " + z);
                }
            }
        }
        assertEquals(7 * 7 * 7, seen.size());
    }

    @Test
    void neighbouringCellsStayDistinctAcrossTheSignBoundary() {
        assertEquals(-1, NavNodes.z(NavNodes.pack(0, 0, -1)));
        assertEquals(0, NavNodes.z(NavNodes.pack(0, 0, 0)));
        assertEquals(-1, NavNodes.x(NavNodes.pack(-1, 0, 0)));
    }

    @Test
    void outOfRangeCoordinatesThrowRatherThanWrap() {
        assertThrows(IllegalArgumentException.class, () -> NavNodes.pack(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> NavNodes.pack(0, NavNodes.MAX_Y + 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> NavNodes.pack(NavNodes.MAX_HORIZONTAL + 1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> NavNodes.pack(0, 0, NavNodes.MIN_HORIZONTAL - 1));
    }

    @Test
    void inRangeAgreesWithWhatPackAccepts() {
        assertTrue(NavNodes.inRange(0, 0, 0));
        assertTrue(NavNodes.inRange(NavNodes.MAX_HORIZONTAL, NavNodes.MAX_Y, NavNodes.MIN_HORIZONTAL));
        assertFalse(NavNodes.inRange(0, -1, 0));
        assertFalse(NavNodes.inRange(NavNodes.MAX_HORIZONTAL + 1, 0, 0));
    }

    @Test
    void rendersReadablyForDebugOutput() {
        assertEquals("(12, 64, -30)", NavNodes.toString(NavNodes.pack(12, 64, -30)));
    }
}
