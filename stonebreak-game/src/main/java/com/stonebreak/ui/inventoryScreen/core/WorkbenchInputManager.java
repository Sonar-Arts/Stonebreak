package com.stonebreak.ui.inventoryScreen.core;

import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.ui.inventoryScreen.handlers.WorkbenchDragDropHandler;
import org.joml.Vector2f;

/**
 * Input handling for the workbench (crafting-table) screen. The mouse dispatch is inherited
 * from {@link InventoryInputManager} so every crafting/inventory interaction — right-drag
 * distribution, double-click gather, Craft All, Sort, shift-click, middle-click balance — works
 * on the 3x3 grid exactly as on the inventory's 2x2 (issue #317). This class only supplies the
 * workbench layout, its drop handler, and the absence of the character tab strip.
 */
public class WorkbenchInputManager extends InventoryInputManager {

    public WorkbenchInputManager(InputHandler inputHandler,
                                Inventory inventory,
                                InventorySlotManager slotManager,
                                InventoryCraftingManager craftingManager) {
        super(inputHandler, inventory, slotManager, craftingManager);
    }

    @Override
    protected InventoryLayoutCalculator.InventoryLayout layoutFor(int screenWidth, int screenHeight) {
        return InventoryLayoutCalculator.calculateWorkbenchLayout(screenWidth, screenHeight);
    }

    /** The workbench screen has no Inventory/Character/... tab strip. */
    @Override
    protected boolean hasCharacterTabs() {
        return false;
    }

    @Override
    protected void placeDraggedItem(InventoryLayoutCalculator.InventoryLayout layout) {
        Vector2f mousePos = inputHandler.getMousePosition();
        WorkbenchDragDropHandler.placeDraggedItem(dragState, inventory,
                                                craftingManager.getCraftingInputSlots(),
                                                mousePos, layout, craftingManager::updateCraftingOutput);
    }
}
