package com.stonebreak.blocks;

import com.stonebreak.blocks.stairs.StairState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which per-block state changes are worth a chunk rebuild, and which mesh
 * variant a state string names.
 */
class BlockRenderStateTest {

    @Test
    void aStairStateIsItsOwnVariantKey() {
        // The mesher looks the stamp up by this key and the renderer registers
        // rotated stamps under it. When the projection dropped states without a
        // "state=" pair, every stair meshed with its default orientation while
        // collision — which reads the raw string — turned. Both ends now derive
        // from this one rule.
        for (StairState.Facing facing : StairState.Facing.values()) {
            String state = StairState.stateStringFor(facing);
            assertEquals(state, BlockRenderState.meshVariantKey(state));
        }
        // Distinct facings must stay distinct keys, or they'd share a stamp.
        assertEquals(StairState.Facing.values().length,
                java.util.Arrays.stream(StairState.Facing.values())
                        .map(f -> BlockRenderState.meshVariantKey(StairState.stateStringFor(f)))
                        .distinct().count());
    }

    @Test
    void aPayloadCarryingStateProjectsToItsVariantOnly() {
        assertEquals("Lit", BlockRenderState.meshVariantKey(
                "furnace:state=Lit;ing=B:1:1;fuel=B:2:1;out=B:0:0;burn=40;burnTotal=80;cook=7"));
        assertEquals("Unlit", BlockRenderState.meshVariantKey("furnace:state=Unlit"));
    }

    @Test
    void absentStatesHaveNoVariant() {
        assertNull(BlockRenderState.meshVariantKey(null));
        assertNull(BlockRenderState.meshVariantKey(""));
        assertNull(BlockRenderState.meshVariantKey("   "));
    }

    @Test
    void identicalStatesNeverRemesh() {
        assertFalse(BlockRenderState.affectsMesh(null, null));
        assertFalse(BlockRenderState.affectsMesh("stairs:facing=EAST", "stairs:facing=EAST"));
    }

    @Test
    void aStairFacingChangeRemeshes() {
        assertTrue(BlockRenderState.affectsMesh(
                StairState.stateStringFor(StairState.Facing.SOUTH),
                StairState.stateStringFor(StairState.Facing.WEST)));
        // Placement writes the first facing onto a cell that had no state.
        assertTrue(BlockRenderState.affectsMesh(null,
                StairState.stateStringFor(StairState.Facing.NORTH)));
    }

    @Test
    void furnaceProgressDoesNotRemeshButLightingDoes() {
        String unlitIdle = "furnace:state=Unlit;ing=B:0:0;fuel=B:0:0;out=B:0:0;burn=0;burnTotal=0;cook=0";
        String unlitCooking = "furnace:state=Unlit;ing=B:1:1;fuel=B:0:0;out=B:0:0;burn=0;burnTotal=0;cook=7";
        String lit = "furnace:state=Lit;ing=B:1:1;fuel=B:2:1;out=B:0:0;burn=40;burnTotal=80;cook=7";

        assertFalse(BlockRenderState.affectsMesh(unlitIdle, unlitCooking),
                "contents and progress arrive every tick and must not rebuild the chunk");
        assertTrue(BlockRenderState.affectsMesh(unlitCooking, lit));
        assertTrue(BlockRenderState.affectsMesh(lit, null), "a broken furnace clears its state");
    }
}
