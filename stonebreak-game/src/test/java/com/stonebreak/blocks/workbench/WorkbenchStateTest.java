package com.stonebreak.blocks.workbench;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #307: a crafting table's grid is block state, persisted through the chunk's state
 * string. These tests pin the round trip and the in-place update the open UI relies on.
 */
class WorkbenchStateTest {

    private static final BlockPos POS = new BlockPos(4, 70, -9);

    @Test
    void aFreshTableHasAnEmptyNineSlotGrid() {
        WorkbenchState s = new WorkbenchState(POS);
        assertEquals(WorkbenchState.SLOT_COUNT, s.getSlots().length);
        assertTrue(s.isEmpty());
        for (ItemStack slot : s.getSlots()) {
            assertTrue(slot.isEmpty());
        }
    }

    @Test
    void theGridRoundTripsThroughTheStateString() {
        WorkbenchState s = new WorkbenchState(POS);
        s.getSlots()[0] = new ItemStack(BlockType.DIRT, 5);
        s.getSlots()[4] = new ItemStack(ItemType.STICK, 2);
        s.getSlots()[8] = new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_WATER);

        String raw = s.toStateString();
        assertTrue(WorkbenchState.isWorkbenchState(raw));

        WorkbenchState back = WorkbenchState.fromStateString(POS, raw);
        assertSame(BlockType.DIRT, back.getSlots()[0].getItem());
        assertEquals(5, back.getSlots()[0].getCount());
        assertSame(ItemType.STICK, back.getSlots()[4].getItem());
        assertEquals(2, back.getSlots()[4].getCount());
        assertEquals(ItemType.BUCKET_STATE_WATER, back.getSlots()[8].getState());
        assertTrue(back.getSlots()[1].isEmpty());
        assertEquals(raw, back.toStateString());
    }

    @Test
    void foreignOrMissingStatesYieldAnEmptyGrid() {
        assertTrue(WorkbenchState.fromStateString(POS, null).isEmpty());
        assertTrue(WorkbenchState.fromStateString(POS, "furnace:state=Lit").isEmpty());
        assertTrue(WorkbenchState.fromStateString(POS, "workbench:").isEmpty());
    }

    @Test
    void echoesUpdateTheGridInPlace() {
        // The open UI holds getSlots(); an echo must land in THAT array, not a new one.
        WorkbenchState s = new WorkbenchState(POS);
        ItemStack[] bound = s.getSlots();

        WorkbenchState server = new WorkbenchState(POS);
        server.getSlots()[2] = new ItemStack(BlockType.DIRT, 7);
        s.applyStateString(server.toStateString());

        assertSame(bound, s.getSlots());
        assertEquals(7, bound[2].getCount());
    }

    @Test
    void theFirstEchoSinceThePollRemembersThePriorGrid() {
        WorkbenchState s = new WorkbenchState(POS);
        s.getSlots()[0] = new ItemStack(BlockType.DIRT, 1);
        String beforeEcho = s.encodeSlots();

        s.applyStateString(new WorkbenchState(POS).toStateString());
        s.applyStateString(new WorkbenchState(POS).toStateString());

        assertEquals(beforeEcho, s.consumePreEchoSlots(), "snapshot from before the FIRST echo");
        assertNull(s.consumePreEchoSlots(), "consumed");
    }

    @Test
    void hydrationIsNotAnEcho() {
        WorkbenchState s = new WorkbenchState(POS);
        s.loadStateString(new WorkbenchState(POS).toStateString());
        assertNull(s.consumePreEchoSlots());
    }

    @Test
    void untrustedSnapshotsAreClampedAndPadded() {
        WorkbenchState s = new WorkbenchState(POS);
        s.applySlots("B:" + BlockType.DIRT.getId() + ":9999|garbage");
        assertEquals(s.getSlots()[0].getMaxStackSize(), s.getSlots()[0].getCount());
        for (int i = 1; i < WorkbenchState.SLOT_COUNT; i++) {
            assertTrue(s.getSlots()[i].isEmpty(), "slot " + i);
        }
        assertFalse(s.isEmpty());
    }

    /* ── Issue #353: echoes older than the last send never roll the grid back ── */

    private static String grid(ItemStack... cells) {
        WorkbenchState g = new WorkbenchState(POS);
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != null) g.getSlots()[i] = cells[i];
        }
        return g.toStateString();
    }

    @Test
    void anEchoOlderThanTheLastSendKeepsTheLocalGrid() {
        // Right-drag into A then B: snapshots {A} (seq 1) and {A,B} (seq 2) go out.
        WorkbenchState s = new WorkbenchState(POS);
        s.getSlots()[0] = new ItemStack(BlockType.DIRT, 1);
        s.markSent(1);
        s.getSlots()[1] = new ItemStack(BlockType.DIRT, 1);
        s.markSent(2);
        String local = s.encodeSlots();

        // The {A} echo arrives late: B's item must not vanish.
        s.applyStateString(grid(new ItemStack(BlockType.DIRT, 1)), 1);
        assertEquals(local, s.encodeSlots());
        assertNull(s.consumePreEchoSlots(), "a dropped echo is not an echo for the UI");
        assertTrue(s.awaitingAck());

        // Drag into C (seq 3); the server applies {A,B} then {A,B,C} and acks 3.
        s.getSlots()[2] = new ItemStack(BlockType.DIRT, 1);
        s.markSent(3);
        s.applyStateString(grid(new ItemStack(BlockType.DIRT, 1)), 2); // {A,B} echo, still stale
        assertEquals(1, s.getSlots()[1].getCount(), "B survives");
        String server = s.toStateString();
        s.applyStateString(server, 3);
        assertFalse(s.awaitingAck());
        assertEquals(server, s.toStateString());
    }

    @Test
    void anEchoAcknowledgingTheLastSendCorrectsTheGrid() {
        WorkbenchState s = new WorkbenchState(POS);
        s.getSlots()[0] = new ItemStack(BlockType.DIRT, 1);
        s.markSent(5);
        // The server changed it (another player's edit landed after ours): the echo wins.
        String server = grid(null, new ItemStack(ItemType.STICK, 4));
        s.applyStateString(server, 5);
        assertEquals(server, s.toStateString());
        assertTrue(s.consumePreEchoSlots() != null, "the UI re-baselines on it");
    }

    @Test
    void echoesWithoutAnAckWaitForTheInFlightSnapshot() {
        // A chunk re-stream carries no ack: applied when nothing is in flight, ignored otherwise.
        WorkbenchState s = new WorkbenchState(POS);
        String restream = grid(new ItemStack(BlockType.DIRT, 3));
        s.applyStateString(restream);
        assertEquals(restream, s.toStateString());

        s.getSlots()[0] = com.stonebreak.items.ItemStackStateCodec.empty();
        s.markSent(7);
        s.applyStateString(restream);
        assertTrue(s.getSlots()[0].isEmpty());

        s.applyStateString(grid(), 7);
        s.applyStateString(restream);
        assertEquals(restream, s.toStateString(), "acked: re-streams apply again");
    }
}
