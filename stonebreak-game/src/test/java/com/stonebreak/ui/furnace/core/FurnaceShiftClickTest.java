package com.stonebreak.ui.furnace.core;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #319: shift-clicking a furnace slot moves only what fits into the inventory —
 * the remainder is never deleted, and the output path never duplicates.
 */
@Tag("regression")
class FurnaceShiftClickTest {

    private static final int STONE = BlockType.STONE.getId();
    private static final int DIRT = BlockType.DIRT.getId();

    private Inventory inventory;
    private FurnaceState furnace;
    private FurnaceInputManager input;
    private int maxStack;

    @BeforeEach
    void setUp() {
        inventory = new Inventory();
        maxStack = new ItemStack(STONE, 1).getMaxStackSize();
        FurnaceController controller = new FurnaceController(null, inventory, null, null, null);
        input = new FurnaceInputManager(null, inventory, controller);
        controller.setInputManager(input);
        controller.setSlotSink((pos, slots) -> { });
        furnace = new FurnaceState(new BlockPos(0, 64, 0));
        controller.bind(furnace);
    }

    @Test
    void wholeStackMovesWhenItFits() {
        furnace.setFuel(new ItemStack(STONE, 10));
        input.shiftClickSlot(FurnaceController.SLOT_FUEL);
        assertTrue(furnace.getFuel().isEmpty());
        assertEquals(10, count(STONE));
    }

    @Test
    void fullInventoryLeavesTheIngredientInItsSlot() {
        fillWith(DIRT, Inventory.TOTAL_SLOTS);
        furnace.setIngredient(new ItemStack(STONE, 10));
        input.shiftClickSlot(FurnaceController.SLOT_INGREDIENT);
        assertEquals(STONE, furnace.getIngredient().getBlockTypeId());
        assertEquals(10, furnace.getIngredient().getCount(), "nothing fit, so nothing may leave the slot");
        assertEquals(0, count(STONE));
    }

    @Test
    void partialFitKeepsTheRemainderInTheFuelSlot() {
        fillWith(DIRT, Inventory.TOTAL_SLOTS - 1);
        inventory.addItem(new ItemStack(STONE, maxStack - 3)); // room for exactly 3 more
        furnace.setFuel(new ItemStack(STONE, 10));
        input.shiftClickSlot(FurnaceController.SLOT_FUEL);
        assertEquals(7, furnace.getFuel().getCount());
        assertEquals(maxStack, count(STONE));
    }

    @Test
    void partialOutputIsConservedNotDuplicated() {
        // No player in unit tests, so overflow stays in the slot instead of dropping;
        // either way the total must be conserved (the old code re-added the whole stack).
        fillWith(DIRT, Inventory.TOTAL_SLOTS - 1);
        inventory.addItem(new ItemStack(STONE, maxStack - 3));
        furnace.setOutput(new ItemStack(STONE, 10));
        input.shiftClickSlot(FurnaceController.SLOT_OUTPUT);
        assertEquals(maxStack - 3 + 10, count(STONE) + furnace.getOutput().getCount());
        assertEquals(7, furnace.getOutput().getCount());
    }

    @Test
    void transferNeverMutatesTheSourceStack() {
        fillWith(DIRT, Inventory.TOTAL_SLOTS - 1);
        ItemStack source = new ItemStack(STONE, 5);
        ItemStack rest = FurnaceShiftTransfer.intoInventory(source, inventory);
        assertEquals(5, source.getCount());
        assertTrue(rest.isEmpty());
    }

    private void fillWith(int blockId, int stacks) {
        for (int i = 0; i < stacks; i++) {
            assertTrue(inventory.addItem(new ItemStack(blockId, maxStack)));
        }
    }

    private int count(int blockId) {
        int total = 0;
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            ItemStack s = inventory.getHotbarSlot(i);
            if (s.getBlockTypeId() == blockId) total += s.getCount();
        }
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            ItemStack s = inventory.getMainInventorySlot(i);
            if (s.getBlockTypeId() == blockId) total += s.getCount();
        }
        return total;
    }
}
