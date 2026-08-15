package com.openmason.engine.wayfind.voxel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Collapsing runs must never change where a path goes — only how many points describe it. The
 * endpoints and every corner survive; anything in between that continues in the same direction does
 * not.
 */
class PathSmootherTest {

    @Test
    void collapsesAStraightRunToItsEndpoints() {
        long[] straight = {
                NavNodes.pack(0, 64, 0),
                NavNodes.pack(1, 64, 0),
                NavNodes.pack(2, 64, 0),
                NavNodes.pack(3, 64, 0),
        };

        assertArrayEquals(new long[]{NavNodes.pack(0, 64, 0), NavNodes.pack(3, 64, 0)},
                PathSmoother.collapseCollinear(straight));
    }

    @Test
    void keepsEveryCorner() {
        long[] dogleg = {
                NavNodes.pack(0, 64, 0),
                NavNodes.pack(1, 64, 0),
                NavNodes.pack(2, 64, 0),
                NavNodes.pack(2, 64, 1), // turn
                NavNodes.pack(2, 64, 2),
        };

        assertArrayEquals(new long[]{
                NavNodes.pack(0, 64, 0),
                NavNodes.pack(2, 64, 0),
                NavNodes.pack(2, 64, 2),
        }, PathSmoother.collapseCollinear(dogleg));
    }

    @Test
    void treatsAChangeOfHeightAsACorner() {
        long[] steppingUp = {
                NavNodes.pack(0, 64, 0),
                NavNodes.pack(1, 64, 0),
                NavNodes.pack(2, 65, 0), // step up — the follower has to aim here
                NavNodes.pack(3, 65, 0),
        };

        assertArrayEquals(new long[]{
                NavNodes.pack(0, 64, 0),
                NavNodes.pack(1, 64, 0),
                NavNodes.pack(2, 65, 0),
                NavNodes.pack(3, 65, 0),
        }, PathSmoother.collapseCollinear(steppingUp));
    }

    @Test
    void collapsesDiagonalRunsToo() {
        long[] diagonal = {
                NavNodes.pack(0, 64, 0),
                NavNodes.pack(1, 64, 1),
                NavNodes.pack(2, 64, 2),
                NavNodes.pack(3, 64, 3),
        };

        assertArrayEquals(new long[]{NavNodes.pack(0, 64, 0), NavNodes.pack(3, 64, 3)},
                PathSmoother.collapseCollinear(diagonal));
    }

    @Test
    void shortPathsAreReturnedUntouched() {
        long[] pair = {NavNodes.pack(0, 64, 0), NavNodes.pack(1, 64, 0)};
        long[] single = {NavNodes.pack(0, 64, 0)};
        long[] empty = new long[0];

        assertSame(pair, PathSmoother.collapseCollinear(pair));
        assertSame(single, PathSmoother.collapseCollinear(single));
        assertSame(empty, PathSmoother.collapseCollinear(empty));
    }

    @Test
    void anAllCornersPathIsReturnedUntouched() {
        long[] zigzag = {
                NavNodes.pack(0, 64, 0),
                NavNodes.pack(1, 64, 0),
                NavNodes.pack(1, 64, 1),
                NavNodes.pack(2, 64, 1),
        };

        assertSame(zigzag, PathSmoother.collapseCollinear(zigzag),
                "nothing to collapse should mean no copy");
    }
}
