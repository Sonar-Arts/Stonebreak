package com.stonebreak.rendering.gameWorld.fog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.stonebreak.world.TimeOfDay;

/** The ground fog forms after sunset, is full through the night, and burns off at dawn. */
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
}
