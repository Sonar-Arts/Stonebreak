package com.stonebreak.rendering.gameWorld.fog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The fog map marks where the Shade is, softly, and carries the ground under it. */
class ShadeFogMapTest {

    private static final int RADIUS = 100;

    /** A Shade disc of RADIUS blocks round the world origin, on ground sloping with x. */
    private static final ShadeFogMap.Source DISC = new ShadeFogMap.Source() {
        @Override public boolean isShade(int x, int z) { return x * x + z * z < RADIUS * RADIUS; }
        @Override public int groundHeight(int x, int z) { return 70 + x / 16; }
    };

    private static float coverageAt(ShadeFogMap.Grid g, int worldX, int worldZ) {
        int ix = Math.floorDiv(worldX - g.originX(), ShadeFogMap.CELL);
        int iz = Math.floorDiv(worldZ - g.originZ(), ShadeFogMap.CELL);
        return g.coverage()[iz * ShadeFogMap.CELLS + ix];
    }

    @Test
    void coverageIsFullInsideTheShadeAndNoneFarFromIt() {
        int origin = -ShadeFogMap.SIZE_BLOCKS / 2;
        ShadeFogMap.Grid g = ShadeFogMap.build(origin, origin, DISC);
        assertTrue(g.hasShade());
        assertEquals(1f, coverageAt(g, 0, 0), 1e-6f);
        assertEquals(1f, coverageAt(g, 40, -40), 1e-6f);
        assertEquals(0f, coverageAt(g, 300, 0), 1e-6f);
        assertEquals(0f, coverageAt(g, -400, 400), 1e-6f);
        // The edge is soft: somewhere across the rim coverage is strictly between.
        boolean partial = false;
        for (int x = RADIUS - 24; x <= RADIUS + 24; x += ShadeFogMap.CELL) {
            float c = coverageAt(g, x, 0);
            partial |= c > 0f && c < 1f;
        }
        assertTrue(partial, "the Shade's edge should blur");
    }

    @Test
    void groundHeightsPassThroughAndBoundTheShade() {
        int origin = -ShadeFogMap.SIZE_BLOCKS / 2;
        ShadeFogMap.Grid g = ShadeFogMap.build(origin, origin, DISC);
        int ix = (0 - origin) / ShadeFogMap.CELL;
        int cx = origin + ix * ShadeFogMap.CELL + ShadeFogMap.CELL / 2;
        assertEquals(DISC.groundHeight(cx, 0), g.height()[ix + ix * ShadeFogMap.CELLS], 1e-6f);
        assertTrue(g.minGround() <= 70 - RADIUS / 16 + 1 && g.maxGround() >= 70 + RADIUS / 16 - 1,
                "min " + g.minGround() + " max " + g.maxGround());
    }

    @Test
    void noShadeMeansNoFog() {
        ShadeFogMap.Source plains = new ShadeFogMap.Source() {
            @Override public boolean isShade(int x, int z) { return false; }
            @Override public int groundHeight(int x, int z) { throw new AssertionError("heights unread without Shade"); }
        };
        assertFalse(ShadeFogMap.build(0, 0, plains).hasShade());
    }
}
