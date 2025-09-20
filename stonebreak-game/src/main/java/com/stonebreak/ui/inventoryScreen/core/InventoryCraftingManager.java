package com.stonebreak.ui.inventoryScreen.core;

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
    private final int craftingGridSize;

    public InventoryCraftingManager(CraftingManager craftingManager) {
        this(craftingManager, InventoryLayoutCalculator.getCraftingGridSize());
    }

    public InventoryCraftingManager(CraftingManager craftingManager, int craftingGridSize) {
        this.craftingManager = craftingManager;
        this.craftingGridSize = craftingGridSize;
        this.craftingInputSlots = new ItemStack[craftingGridSize * craftingGridSize];
        initializeCraftingSlots();
        this.craftingOutputSlot = new ItemStack(BlockType.AIR.getId(), 0);
    }

    private void initializeCraftingSlots() {
        int totalSlots = craftingGridSize * craftingGridSize;
        for (int i = 0; i < totalSlots; i++) {
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

        for (int r = 0; r < craftingGridSize; r++) {
            List<ItemStack> row = new ArrayList<>();
            for (int c = 0; c < craftingGridSize; c++) {
                row.add(craftingInputSlots[r * craftingGridSize + c]);
            }
            grid.add(row);
        }
        return grid;
    }

    public void consumeCraftingIngredients() {
        int totalSlots = craftingGridSize * craftingGridSize;

        for (int i = 0; i < totalSlots; i++) {
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

    public int getCraftingGridSize() {
        return craftingGridSize;
    }
}