package com.stonebreak.ui.inventoryScreen.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the batch-crafting contract that powers the two inventory QoL features:
 * repeated clicking the output slot to accumulate a held stack, and shift-clicking
 * it to craft everything the grid allows.
 */
class InventoryCraftingManagerTest {

    private static InventoryCraftingManager managerWithDirtToSticks() {
        CraftingManager cm = new CraftingManager();
        cm.registerRecipe(new Recipe("dirtToSticks",
            List.of(List.of(new ItemStack(BlockType.DIRT, 1))),
            new ItemStack(ItemType.STICK, 4)));
        return new InventoryCraftingManager(cm);
    }

    @Test
    void takeCraftBatchCraftsOneBatchAndConsumesOneIngredient() {
        InventoryCraftingManager crafting = managerWithDirtToSticks();
        crafting.setCraftingInputSlot(0, new ItemStack(BlockType.DIRT, 2));
        crafting.updateCraftingOutput();

        ItemStack first = crafting.takeCraftBatch();
        assertEquals(4, first.getCount(), "one batch of the recipe output");
        assertEquals(1, crafting.getCraftingInputSlot(0).getCount(),
            "exactly one ingredient is consumed per batch");
        assertEquals(4, crafting.getCraftingOutputSlot().getCount(),
            "a second batch is still craftable and shown in the output slot");

        ItemStack second = crafting.takeCraftBatch();
        assertEquals(4, second.getCount());
        assertTrue(crafting.getCraftingInputSlot(0).isEmpty(), "last ingredient consumed");
        assertTrue(crafting.getCraftingOutputSlot().isEmpty(), "no more craftable output");

        assertNull(crafting.takeCraftBatch(), "an exhausted grid must not craft");
    }

    @Test
    void takeCraftBatchReturnsNullWhenOutputIsEmpty() {
        InventoryCraftingManager crafting = managerWithDirtToSticks();
        assertNull(crafting.takeCraftBatch(), "an empty grid has nothing to craft");
    }

    @Test
    void craftAllConsumesEverythingAndReturnsEveryBatch() {
        InventoryCraftingManager crafting = managerWithDirtToSticks();
        crafting.setCraftingInputSlot(0, new ItemStack(BlockType.DIRT, 3));
        crafting.updateCraftingOutput();

        List<ItemStack> crafted = crafting.craftAll();

        assertEquals(3, crafted.size(), "three batches from three ingredients");
        int total = crafted.stream().mapToInt(ItemStack::getCount).sum();
        assertEquals(12, total, "total output = batches x output-per-batch");
        for (ItemStack batch : crafted) {
            assertEquals(ItemType.STICK, batch.getItem());
        }
        for (ItemStack slot : crafting.getCraftingInputSlots()) {
            assertTrue(slot.isEmpty(), "every ingredient must be consumed");
        }
        assertTrue(crafting.getCraftingOutputSlot().isEmpty());
    }

    @Test
    void craftAllOnAnEmptyGridIsANoOp() {
        InventoryCraftingManager crafting = managerWithDirtToSticks();
        assertTrue(crafting.craftAll().isEmpty());
    }
}
