package com.stonebreak.ui.furnace.core;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #353: an echo the server built before our latest slot action arrived must not roll the
 * open furnace back — the shift-clicked output would come back and could be taken twice.
 * Actions are the numbered {@link ContainerActionC2S}s of issue #334.
 */
@Tag("regression")
class FurnaceEchoOrderingTest {

    private static final int STONE = BlockType.STONE.getId();
    private static final BlockPos POS = new BlockPos(0, 64, 0);

    private final List<ContainerActionC2S> sent = new ArrayList<>();
    private Inventory inventory;
    private FurnaceInputManager input;
    private FurnaceController controller;
    private FurnaceState furnace;

    @BeforeEach
    void setUp() {
        inventory = new Inventory();
        controller = new FurnaceController(null, null, null);
        input = new FurnaceInputManager(null, inventory, controller);
        controller.setInputManager(input);
        controller.actions().setCapture(sent::add);
        controller.setFurnaceAt(p -> true);
        furnace = new FurnaceState(POS);
        furnace.setOutput(new ItemStack(STONE, 10));
        controller.bind(furnace);
    }

    @Test
    void aStaleEchoCannotBringBackShiftClickedOutput() {
        String serverBefore = furnace.toStateString(); // output 10, as the server still has it

        input.shiftClickSlot(FurnaceController.SLOT_OUTPUT);
        controller.update(0f);
        assertEquals(1, sent.size());
        int seq = sent.get(0).seq();
        assertTrue(furnace.getOutput().isEmpty());
        assertEquals(10, stoneInInventory());

        // A cook-tick echo that left before our snapshot arrived: still has the output.
        furnace.applyStateString(serverBefore, seq - 1);
        controller.update(0f);
        assertTrue(furnace.getOutput().isEmpty(), "stale slots are dropped");
        input.shiftClickSlot(FurnaceController.SLOT_OUTPUT); // nothing there to take twice
        controller.update(0f);
        assertEquals(10, stoneInInventory());
        assertEquals(1, sent.size(), "nothing new to send");

        // The echo acknowledging our action settles it.
        FurnaceState server = FurnaceState.fromStateString(POS, serverBefore);
        server.setOutput(new ItemStack(0, 0)); // the server's QUICK_MOVE
        furnace.applyStateString(server.toStateString(), seq);
        controller.update(0f);
        assertTrue(furnace.getOutput().isEmpty());
        assertEquals(1, sent.size(), "an echo is never re-sent as intent");
    }

    @Test
    void aStaleEchoStillMovesTheServerOwnedTimers() {
        input.shiftClickSlot(FurnaceController.SLOT_OUTPUT);
        controller.update(0f);
        int seq = sent.get(0).seq();

        furnace.applyStateString("furnace:state=Lit;out=B:" + STONE + ":10;burn=80;burnTotal=100;cook=30", seq - 1);

        assertTrue(furnace.getOutput().isEmpty());
        assertEquals(80, furnace.getBurnTimeRemaining());
        assertEquals(30, furnace.getCookProgress());
    }

    private int stoneInInventory() {
        int total = 0;
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            ItemStack s = inventory.getHotbarSlot(i);
            if (s.getBlockTypeId() == STONE) total += s.getCount();
        }
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            ItemStack s = inventory.getMainInventorySlot(i);
            if (s.getBlockTypeId() == STONE) total += s.getCount();
        }
        return total;
    }
}
