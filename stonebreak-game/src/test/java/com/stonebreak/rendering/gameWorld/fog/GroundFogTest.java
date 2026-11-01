package com.stonebreak.rendering.gameWorld.fog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import com.stonebreak.world.TimeOfDay;

/**
 * The ground fog forms after sunset, is full through the night, and burns off at dawn; its
 * march box holds all of it, and only views that reach that box draw it.
 */
class GroundFogTest {

    private static float strengthAt(int ticks) {
        return GroundFog.nightStrength(TimeOfDay.directionAt(ticks / 24000f).y);
    }

    @Test
    void noneByDayFullAtMidnight() {
        assertEquals(0f, strengthAt(TimeOfDay.NOON), 1e-6f);
        assertEquals(0f, strengthAt(TimeOfDay.SUNSET), 1e-6f);
        assertEquals(1f, strengthAt(TimeOfDay.MIDNIGHT), 1e-6f);
        assertEquals(0f, strengthAt(TimeOfDay.SUNRISE), 1e-6f);
    }

    @Test
    void formsSteadilyAsTheSunGoesDown() {
        float previous = 0f;
        boolean partial = false;
        for (int t = TimeOfDay.SUNSET; t <= TimeOfDay.DUSK + 1000; t += 25) {
            float s = strengthAt(t);
            assertTrue(s >= previous - 1e-6f, "fog thinned at tick " + t);
            partial |= s > 0f && s < 1f;
            previous = s;
        }
        assertEquals(1f, previous, 1e-6f);
        assertTrue(partial, "the fog should fade in, not switch on");
    }

    // --- The map's baked edge fade ---------------------------------------------------

    @Test
    void rimIsFullInsideAndFadesToTheMapEdge() {
        int n = ShadeFogMap.CELLS;
        assertEquals(1f, GroundFog.RIM[(n / 2) * n + n / 2], 0f);
        assertEquals(1f, GroundFog.RIM[(n / 2) * n + n / 4], 0f);
        assertTrue(GroundFog.RIM[(n / 2) * n] < 0.01f, "the outermost cell is all but faded");
        float previous = -1f;
        for (int ix = 0; ix <= n / 8; ix++) {
            float r = GroundFog.RIM[(n / 2) * n + ix];
            assertTrue(r > previous, "the fade rises inward, cell " + ix);
            previous = r;
        }
    }

    // --- The march box ---------------------------------------------------------------

    private static final int ORIGIN = -ShadeFogMap.SIZE_BLOCKS / 2;

    /** A Shade disc of {@code radius} round (cx, cz). */
    private static ShadeFogMap.Source disc(int cx, int cz, int radius) {
        return new ShadeFogMap.Source() {
            @Override public boolean isShade(int x, int z) {
                return (x - cx) * (x - cx) + (z - cz) * (z - cz) < radius * radius;
            }
            @Override public int groundHeight(int x, int z) { return 70 + x / 16; }
        };
    }

    /** Coverage at world (x, z) as the GPU filters it: bilinear, clamped to the edge. */
    private static float filteredCoverage(ShadeFogMap.Grid g, float x, float z) {
        int n = ShadeFogMap.CELLS;
        float fx = (x - g.originX()) / ShadeFogMap.CELL - 0.5f;
        float fz = (z - g.originZ()) / ShadeFogMap.CELL - 0.5f;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        float tx = fx - x0, tz = fz - z0;
        float sum = 0f;
        for (int dz = 0; dz <= 1; dz++) {
            for (int dx = 0; dx <= 1; dx++) {
                int ix = Math.max(0, Math.min(n - 1, x0 + dx));
                int iz = Math.max(0, Math.min(n - 1, z0 + dz));
                float w = (dx == 0 ? 1f - tx : tx) * (dz == 0 ? 1f - tz : tz);
                sum += w * g.coverage()[iz * n + ix];
            }
        }
        return sum;
    }

    /** No fog outside the box: the march skipping it loses nothing. */
    private static void assertBoxHoldsAllCoverage(ShadeFogMap.Grid g) {
        Vector3f min = new Vector3f(), max = new Vector3f();
        GroundFog.box(g, min, max);
        assertTrue(min.x >= g.originX() && min.z >= g.originZ(), "box starts on the map");
        assertTrue(max.x <= g.originX() + ShadeFogMap.SIZE_BLOCKS
                && max.z <= g.originZ() + ShadeFogMap.SIZE_BLOCKS, "box ends on the map");
        boolean anyInside = false;
        for (float z = g.originZ(); z <= g.originZ() + ShadeFogMap.SIZE_BLOCKS; z += 1.5f) {
            for (float x = g.originX(); x <= g.originX() + ShadeFogMap.SIZE_BLOCKS; x += 1.5f) {
                boolean inside = x >= min.x && x <= max.x && z >= min.z && z <= max.z;
                float c = filteredCoverage(g, x, z);
                if (!inside) {
                    assertEquals(0f, c, 0f, "fog outside the box at " + x + ", " + z);
                }
                anyInside |= inside && c > 0f;
            }
        }
        assertTrue(anyInside);
        assertEquals(g.minGround() - GroundFog.BELOW, min.y, 0f);
        assertEquals(g.maxGround() + GroundFog.DEPTH, max.y, 0f);
    }

    @Test
    void boxHoldsAShadeInTheMiddleOfTheMap() {
        ShadeFogMap.Grid g = ShadeFogMap.build(ORIGIN, ORIGIN, disc(30, -60, 100));
        assertBoxHoldsAllCoverage(g);
        Vector3f min = new Vector3f(), max = new Vector3f();
        GroundFog.box(g, min, max);
        // Tight: no more than the blur and a cell of filter past the disc.
        float slack = (ShadeFogMap.BLUR_CELLS + 2) * ShadeFogMap.CELL;
        assertTrue(min.x >= 30 - 100 - slack && max.x <= 30 + 100 + slack, min + " " + max);
    }

    @Test
    void boxHoldsAShadeRunningOffTheMap() {
        assertBoxHoldsAllCoverage(ShadeFogMap.build(ORIGIN, ORIGIN, disc(ORIGIN, 200, 300)));
        assertBoxHoldsAllCoverage(ShadeFogMap.build(ORIGIN, ORIGIN, disc(0, 0, 5000)));
    }

    // --- Whether the view reaches the box --------------------------------------------

    /** Eye at the origin looking down -z (identity view), 70 degree frustum, 1000 far. */
    private static boolean seen(float x0, float y0, float z0, float x1, float y1, float z1) {
        Matrix4f viewProj = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.1f, 1000f);
        return GroundFog.visible(viewProj, new Vector3f(x0, y0, z0), new Vector3f(x1, y1, z1), new Vector4f());
    }

    @Test
    void aBoxInFrontIsSeenOneBehindIsNot() {
        assertTrue(seen(-10, -5, -60, 10, 5, -40));
        assertFalse(seen(-10, -5, 40, 10, 5, 60), "behind the eye");
        assertFalse(seen(-10, 200, -60, 10, 210, -40), "high above the view");
        assertFalse(seen(300, -5, -60, 320, 5, -40), "off to the side");
    }

    @Test
    void theEyeInsideTheBoxAlwaysSeesIt() {
        assertTrue(seen(-1, -1, -1, 1, 1, 1));
        assertTrue(seen(-200, -3, -400, 200, 3, 0.5f), "the eye just inside the box's back face");
    }

    @Test
    void aBoxPastTheFarPlaneIsSeenForTheSkyRaysThatReachIt() {
        assertTrue(seen(-50, -5, -3000, 50, 5, -2000));
    }
}
