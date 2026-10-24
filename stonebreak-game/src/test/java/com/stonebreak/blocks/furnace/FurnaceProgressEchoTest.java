package com.stonebreak.blocks.furnace;

import com.openmason.engine.util.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cooking furnace's burn/cook counters change every tick; echoing each one sent 20
 * BlockStateS2C per second per furnace to every connection. Progress-only changes are now
 * rate-limited, while contents and lit flips — and the value progress settles on — still
 * reach clients at once.
 */
class FurnaceProgressEchoTest {

    private FurnaceState furnace;

    private static String state(String lit, String ing, int burn, int cook) {
        return FurnaceState.STATE_PREFIX + "state=" + lit + ";ing=" + ing + ";fuel=coal:1;out=air:0"
                + ";burn=" + burn + ";burnTotal=100;cook=" + cook;
    }

    @BeforeEach
    void echoedOnce() {
        furnace = new FurnaceState(new BlockPos(0, 64, 0));
        String loaded = state("Lit", "ore:3", 100, 0);
        assertTrue(furnace.echoDue(loaded), "a state clients never received echoes");
        furnace.markEchoed(loaded);
    }

    @Test
    void progressOnlyChangesEchoAtTheProgressRate() {
        for (int tick = 1; tick < FurnaceState.PROGRESS_ECHO_TICKS; tick++) {
            assertFalse(furnace.echoDue(state("Lit", "ore:3", 100 - tick, tick)), "tick " + tick);
        }
        int tick = FurnaceState.PROGRESS_ECHO_TICKS;
        assertTrue(furnace.echoDue(state("Lit", "ore:3", 100 - tick, tick)));
    }

    @Test
    void contentsAndLitChangesEchoAtOnce() {
        assertTrue(furnace.echoDue(state("Lit", "ore:2", 99, 0)), "ingredient consumed");
        furnace.markEchoed(state("Lit", "ore:2", 99, 0));
        assertTrue(furnace.echoDue(state("Unlit", "ore:2", 0, 0)), "lit flip");
    }

    @Test
    void progressThatStopsMovingStillReachesClients() {
        String stalled = state("Lit", "ore:3", 99, 1);
        assertFalse(furnace.echoDue(stalled));
        assertTrue(furnace.echoDue(stalled), "the settled value is echoed on the next tick");
        furnace.markEchoed(stalled);
        assertFalse(furnace.echoDue(stalled), "and only once");
    }
}
