package com.stonebreak.core.dev;

import com.stonebreak.world.TimeOfDay;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sky capture puts the setting sun at each elevation it names. */
class SkyCaptureTest {

    @Test
    void duskTickPutsTheSettingSunAtTheElevation() {
        for (float elevation : SkyCapture.ELEVATIONS) {
            long tick = SkyCapture.duskTick(elevation);
            float y = TimeOfDay.directionAt(tick / (float) TimeOfDay.TICKS_PER_DAY).y;
            float actual = (float) Math.toDegrees(Math.asin(y));
            // Within a tick's worth of sun motion.
            assertEquals(elevation, actual, 0.2f, "tick " + tick);
            assertTrue(tick > TimeOfDay.NOON && tick < TimeOfDay.MIDNIGHT, "the evening side, tick " + tick);
        }
    }
}
