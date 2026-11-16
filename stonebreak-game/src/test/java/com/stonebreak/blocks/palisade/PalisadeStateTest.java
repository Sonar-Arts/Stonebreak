package com.stonebreak.blocks.palisade;

import com.stonebreak.blocks.BlockRenderState;
import com.stonebreak.blocks.stairs.StairState.Facing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PalisadeStateTest {

    @Test
    void roundTripsEveryLean() {
        for (Facing f : Facing.values()) {
            PalisadeState s = new PalisadeState(f);
            assertEquals(s, PalisadeState.parse(s.toStateString()));
        }
    }

    @Test
    void leansTheWayThePlacerLooks() {
        assertEquals(Facing.NORTH, PalisadeState.leaningToward(0.1f, -1f).facing());
        assertEquals(Facing.EAST, PalisadeState.leaningToward(1f, 0.3f).facing());
        assertEquals(Facing.SOUTH, PalisadeState.leaningToward(0f, 1f).facing());
        assertEquals(Facing.WEST, PalisadeState.leaningToward(-1f, -0.2f).facing());
    }

    @Test
    void unreadableStatesLeanTheAuthoredWay() {
        assertEquals(Facing.SOUTH, PalisadeState.parse(null).facing());
        assertEquals(Facing.SOUTH, PalisadeState.parse("stairs:facing=EAST").facing());
        assertEquals(Facing.SOUTH, PalisadeState.parse("palisade:facing=UP").facing());
        assertEquals(Facing.WEST, PalisadeState.parse("palisade:junk;facing=west").facing());
        assertFalse(PalisadeState.isPalisadeState("stairs:facing=EAST"));
        assertTrue(PalisadeState.isPalisadeState("palisade:facing=EAST"));
    }

    @Test
    void theStateStringIsItsOwnStampKey() {
        // The renderer registers each rotation under this key; the mesher must read the same one back.
        for (Facing f : Facing.values()) {
            String raw = PalisadeState.stateStringFor(f);
            assertEquals(raw, BlockRenderState.meshVariantKey(raw));
        }
    }
}
