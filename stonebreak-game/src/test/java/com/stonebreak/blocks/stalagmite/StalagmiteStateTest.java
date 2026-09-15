package com.stonebreak.blocks.stalagmite;

import com.stonebreak.blocks.BlockRenderState;
import com.stonebreak.blocks.stairs.StairState.Facing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StalagmiteStateTest {

    @Test
    void roundTripsEveryVariantAndIsItsOwnMeshKey() {
        for (int size = 1; size <= 3; size++) {
            for (boolean hanging : new boolean[]{false, true}) {
                for (Facing facing : Facing.values()) {
                    StalagmiteState state = new StalagmiteState(size, hanging, facing);
                    String raw = state.toStateString();
                    assertEquals(state, StalagmiteState.parse(raw));
                    // The renderer registers stamps under the mesh key; it must be the whole string.
                    assertEquals(raw, BlockRenderState.meshVariantKey(raw));
                }
            }
        }
    }

    @Test
    void missingOrLegacyStatesAreUprightSouth() {
        assertEquals(new StalagmiteState(1, false, Facing.SOUTH), StalagmiteState.parse(null));
        assertEquals(new StalagmiteState(2, false, Facing.SOUTH), StalagmiteState.parse("Stalagmite2"));
        assertEquals(new StalagmiteState(1, false, Facing.SOUTH), StalagmiteState.parse("junk"));
        assertEquals("Stalagmite3", new StalagmiteState(3, true, Facing.WEST).sboStateName());
    }

    @Test
    void toleratesUnknownKeysAndClampsSize() {
        StalagmiteState s = StalagmiteState.parse("stalagmite:size=9;wet=yes;hanging=true;facing=west");
        assertEquals(3, s.size());
        assertTrue(s.hanging());
        assertEquals(Facing.WEST, s.facing());
    }

    @Test
    void placementHangsFromUndersidesAndFacesTheLook() {
        StalagmiteState hung = StalagmiteState.placed(true, 0f, -1f);
        assertTrue(hung.hanging());
        assertEquals(Facing.NORTH, hung.facing());
        assertEquals(1, hung.size());
        assertEquals(-1, hung.direction());
        assertFalse(StalagmiteState.placed(false, 1f, 0f).hanging());
    }
}
