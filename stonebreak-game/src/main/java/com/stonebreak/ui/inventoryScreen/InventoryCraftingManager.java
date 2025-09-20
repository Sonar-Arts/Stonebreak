package com.stonebreak.ui.inventoryScreen;

import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.items.ItemStack;
import com.stonebreak.blocks.BlockType;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages crafting-specific logic for the inventory screen.
 * Follows Single Responsibility Principle by handling only crafting operations.
 */
public class InventoryCraftingManager {

    private final CraftingManager craftingManager;
    private final ItemStack[] craftingInputSlots;
    private ItemStack craftingOutputSlot;

    public InventoryCraftingManager(CraftingManager craftingManager) {
        this.craftingManager = craftingManager;
        this.craftingInputSlots = new ItemStack[InventoryLayoutCalculator.getCraftingGridSize() *
                                                InventoryLayoutCalculator.getCraftingGridSize()];
        initializeCraftingSlots();
        this.craftingOutputSlot = new ItemStack(BlockType.AIR.getId(), 0);
    }

    private void initializeCraftingSlots() {
        int gridSize = InventoryLayoutCalculator.getCraftingGridSize() *
                      InventoryLayoutCalculator.getCraftingGridSize();
        for (int i = 0; i < gridSize; i++) {
            this.craftingInputSlots[i] = new ItemStack(BlockType.AIR.getId(), 0);
        }
    }

    public void updateCraftingOutput() {
        List<List<ItemStack>> grid = createCraftingGrid();
        ItemStack result = craftingManager.craftItem(grid);

        if (result != null && !result.isEmpty()) {
            craftingOutputSlot = result;
        } else {
            craftingOutputSlot = new ItemStack(BlockType.AIR.getId(), 0);
        }
    }

    private List<List<ItemStack>> createCraftingGrid() {
        List<List<ItemStack>> grid = new ArrayList<>();
        int gridSize = InventoryLayoutCalculator.getCraftingGridSize();

        for (int r = 0; r < gridSize; r++) {
            List<ItemStack> row = new ArrayList<>();
            for (int c = 0; c < gridSize; c++) {
                row.add(craftingInputSlots[r * gridSize + c]);
            }
            grid.add(row);
        }
        return grid;
    }

    public void consumeCraftingIngredients() {
        int gridSize = InventoryLayoutCalculator.getCraftingGridSize() *
                      InventoryLayoutCalculator.getCraftingGridSize();

        for (int i = 0; i < gridSize; i++) {
            if (craftingInputSlots[i] != null && !craftingInputSlots[i].isEmpty()) {
                craftingInputSlots[i].decrementCount(1);
                if (craftingInputSlots[i].isEmpty()) {
                    craftingInputSlots[i] = new ItemStack(BlockType.AIR.getId(), 0);
                }
            }
        }
    }

    public ItemStack[] getCraftingInputSlots() {
        return craftingInputSlots;
    }

    public ItemStack getCraftingOutputSlot() {
        return craftingOutputSlot;
    }

    public void setCraftingOutputSlot(ItemStack itemStack) {
        this.craftingOutputSlot = itemStack;
    }

    public ItemStack getCraftingInputSlot(int index) {
        if (index >= 0 && index < craftingInputSlots.length) {
            return craftingInputSlots[index];
        }
        return new ItemStack(BlockType.AIR.getId(), 0);
    }

    public void setCraftingInputSlot(int index, ItemStack itemStack) {
        if (index >= 0 && index < craftingInputSlots.length) {
            craftingInputSlots[index] = itemStack;
        }
    }
}