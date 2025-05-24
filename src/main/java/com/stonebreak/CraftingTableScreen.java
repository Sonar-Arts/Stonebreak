package com.stonebreak;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_BOTTOM;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_CENTER;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_MIDDLE;
import static org.lwjgl.nanovg.NanoVG.NVG_ALIGN_RIGHT;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glIsEnabled;

public class CraftingTableScreen {

    private final Inventory inventory;
    private boolean visible;
    private final UIRenderer uiRenderer; // Changed from Font
    private final Renderer renderer; // Kept for 3D item rendering
    private final InputHandler inputHandler;
    private final RecipeManager recipeManager;

    private ItemStack draggedItemStack;
    private int draggedItemOriginalSlotIndex;
    private enum DragSource { PLAYER_MAIN, PLAYER_HOTBAR, CRAFTING_INPUT, NONE }
    private DragSource dragSource = DragSource.NONE;
    private int draggedItemOriginalCraftingSlotIndex = -1;

    private static final int SLOT_SIZE = 40;
    private static final int SLOT_PADDING = 5;
    private static final int TITLE_HEIGHT = 30;
    private static final int CRAFTING_GRID_DIMENSION = 3;
    private static final int CRAFTING_INPUT_SLOTS_COUNT = CRAFTING_GRID_DIMENSION * CRAFTING_GRID_DIMENSION;
    private static final int CRAFTING_ARROW_WIDTH = 30;
    private static final int CRAFTING_AREA_PADDING = 10;

    private final ItemStack[] craftingInputSlots = new ItemStack[CRAFTING_INPUT_SLOTS_COUNT];
    private ItemStack craftingOutputSlot;
    private CraftingRecipe currentValidRecipe;

    private int craftingGridX, craftingGridY;
    private int craftingOutputX, craftingOutputY;
    private int craftingArrowX, craftingArrowY;

    private static final int BORDER_COLOR_R = 100;
    private static final int BORDER_COLOR_G = 100;
    private static final int BORDER_COLOR_B = 100;
    private static final int BACKGROUND_COLOR_R = 50;
    private static final int BACKGROUND_COLOR_G = 50;
    private static final int BACKGROUND_COLOR_B = 50;
    private static final int SLOT_BACKGROUND_R = 70;
    private static final int SLOT_BACKGROUND_G = 70;
    private static final int SLOT_BACKGROUND_B = 70;
    private static final int TEXT_COLOR_R = 255;
    private static final int TEXT_COLOR_G = 255;
    private static final int TEXT_COLOR_B = 255;

    public CraftingTableScreen(Inventory inventory, UIRenderer uiRenderer, Renderer renderer, InputHandler inputHandler, RecipeManager recipeManager) { // Changed Font to UIRenderer
        this.inventory = inventory;
        this.visible = false; // Default to false, Game class will set it true when opening
        this.uiRenderer = uiRenderer;
        this.renderer = renderer;
        this.inputHandler = inputHandler;
        this.recipeManager = recipeManager;
        this.draggedItemStack = null;
        this.draggedItemOriginalSlotIndex = -1;
        this.dragSource = DragSource.NONE;

        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            craftingInputSlots[i] = ItemStack.empty();
        }
        craftingOutputSlot = ItemStack.empty();
        this.currentValidRecipe = null;
    }

    public void toggleVisibility() {
        // The Game class is responsible for actually changing the visibility state,
        // presumably by calling setVisible() on this instance.
        Game.getInstance().toggleCraftingTableScreen(); // This will manage paused state, cursor, and call setVisible.
    }

    /**
     * Sets the visibility of this screen. Called by the Game class.
     * Handles cleanup when the screen is hidden.
     * @param visible True to show, false to hide.
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
        if (!this.visible) { // If screen is being hidden
            if (draggedItemStack != null && !draggedItemStack.isEmpty()) {
                tryReturnToOriginalSlot();
                if (draggedItemStack != null && !draggedItemStack.isEmpty()) {
                    inventory.addItem(draggedItemStack); // Attempt to return to player inventory
                    draggedItemStack = null; // Nullify after attempting to add
                }
                resetDragState(); // Ensure drag state is fully reset
            }
        }
    }

    public boolean isVisible() {
        return visible;
    }

    public void render(int screenWidth, int screenHeight) {
        if (!visible) {
            return;
        }

        // GL state management remains largely the same initially
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        // ShaderProgram might not be directly needed if UIRenderer handles it internally,
        // but keeping it for now if `renderer.draw3DItemInSlot` needs it or if UIRenderer uses a pre-bound shader
        // ShaderProgram shaderProgram = renderer.getShaderProgram(); // Removed as it was unused
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean cullFaceEnabled = glIsEnabled(GL_CULL_FACE);

        glDisable(GL_DEPTH_TEST); // Common for UI overlays
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        if (cullFaceEnabled) {
            glDisable(GL_CULL_FACE);
        }
        
        // UIRenderer.beginFrame should set up NanoVG's coordinate system and any frame-specific state.
        // This call assumes screenWidth, screenHeight, and a pixelRatio (typically 1.0f unless HiDPI is handled specially).
        uiRenderer.beginFrame(screenWidth, screenHeight, 1.0f);


        int craftingGridActualWidth = CRAFTING_GRID_DIMENSION * SLOT_SIZE + (CRAFTING_GRID_DIMENSION - 1) * SLOT_PADDING;
        int craftingAreaWidthForCentering = craftingGridActualWidth + CRAFTING_ARROW_WIDTH + SLOT_SIZE + 2 * CRAFTING_AREA_PADDING;
        int craftingAreaHeight = CRAFTING_GRID_DIMENSION * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING; // Height of N slots

        int numDisplayCols = Inventory.MAIN_INVENTORY_COLS;
        int mainInvWidth = numDisplayCols * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int panelWidth = Math.max(mainInvWidth, craftingAreaWidthForCentering);
        
        int totalContentHeight = TITLE_HEIGHT + CRAFTING_AREA_PADDING + craftingAreaHeight + CRAFTING_AREA_PADDING +
                                 (Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING) + CRAFTING_AREA_PADDING +
                                 (SLOT_SIZE) + CRAFTING_AREA_PADDING;


        int panelStartX = (screenWidth - panelWidth) / 2;
        int panelStartY = (screenHeight - totalContentHeight) / 2;

        // Use UIRenderer.renderQuad
        uiRenderer.renderQuad(panelStartX - 5, panelStartY - 5, panelWidth + 10, totalContentHeight + 10,
                BORDER_COLOR_R / 255.0f, BORDER_COLOR_G / 255.0f, BORDER_COLOR_B / 255.0f, 1.0f);
        uiRenderer.renderQuad(panelStartX, panelStartY, panelWidth, totalContentHeight,
                BACKGROUND_COLOR_R / 255.0f, BACKGROUND_COLOR_G / 255.0f, BACKGROUND_COLOR_B / 255.0f, 200.0f / 255.0f);

        String title = "Crafting Table";
        // For title width, UIRenderer would need a method if NanoVG's textBounds isn't exposed.
        // Assuming a generic text width or centering logic for now.
        // For now, let's approximate centering with direct NanoVG text alignment
        uiRenderer.drawString(title,
                panelStartX + panelWidth / 2.0f,
                panelStartY + SLOT_PADDING + TITLE_HEIGHT / 2.0f, // Adjust Y for vertical centering
                UIRenderer.FONT_MINECRAFT, 24, // Example font and size
                TEXT_COLOR_R, TEXT_COLOR_G, TEXT_COLOR_B, 255,
                NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);


        int currentY = panelStartY + TITLE_HEIGHT + CRAFTING_AREA_PADDING;

        craftingGridX = panelStartX + (panelWidth - (craftingGridActualWidth + CRAFTING_ARROW_WIDTH + SLOT_SIZE)) / 2;
        craftingGridY = currentY;

        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            int r = i / CRAFTING_GRID_DIMENSION;
            int c = i % CRAFTING_GRID_DIMENSION;
            int slotX = craftingGridX + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY = craftingGridY + r * (SLOT_SIZE + SLOT_PADDING);
            drawSlot(craftingInputSlots[i], slotX, slotY, false, -1, false, true);
        }

        craftingArrowX = craftingGridX + craftingGridActualWidth + SLOT_PADDING;
        craftingArrowY = craftingGridY + (craftingAreaHeight - SLOT_SIZE) / 2;
        // Arrow rendering using UIRenderer.renderQuad
        uiRenderer.renderQuad(craftingArrowX, craftingArrowY + SLOT_SIZE / 2.0f - 2, CRAFTING_ARROW_WIDTH - 10, 4, 200/255f, 200/255f, 200/255f, 1.0f);
        uiRenderer.renderQuad(craftingArrowX + CRAFTING_ARROW_WIDTH - 10 - 5, craftingArrowY + SLOT_SIZE / 2.0f - 5, 5, 10, 200/255f, 200/255f, 200/255f, 1.0f);


        craftingOutputX = craftingArrowX + CRAFTING_ARROW_WIDTH;
        craftingOutputY = craftingArrowY;
        drawSlot(craftingOutputSlot, craftingOutputX, craftingOutputY, false, -1, true, false);

        currentY += craftingAreaHeight + CRAFTING_AREA_PADDING;
        
        ItemStack[] mainSlots = inventory.getMainInventorySlots();
        int mainInvStartX = panelStartX + (panelWidth - mainInvWidth) / 2 + SLOT_PADDING;
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = mainInvStartX + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = currentY + row * (SLOT_SIZE + SLOT_PADDING);
            drawSlot(mainSlots[i], slotX, slotY, false, -1, false, false);
        }
        
        currentY += Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING + CRAFTING_AREA_PADDING;

        ItemStack[] hotbarSlots = inventory.getHotbarSlots();
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = mainInvStartX + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = currentY;
            drawSlot(hotbarSlots[i], slotX, slotY, true, i, false, false);
        }

        if (draggedItemStack != null && !draggedItemStack.isEmpty()) {
            Vector2f mousePos = inputHandler.getMousePosition();
            int itemRenderX = (int) (mousePos.x - (SLOT_SIZE - 4) / 2.0f);
            int itemRenderY = (int) (mousePos.y - (SLOT_SIZE - 4) / 2.0f);
            renderItemInHand(draggedItemStack, itemRenderX, itemRenderY);
        }
        
        uiRenderer.endFrame(); // Finalize NanoVG rendering for this UI pass

        // Restore GL state
        if (!blendWasEnabled) glDisable(GL_BLEND);
        if (depthTestEnabled) glEnable(GL_DEPTH_TEST);
        if (cullFaceEnabled) glEnable(GL_CULL_FACE);
         // ShaderProgram might need to be unbound if UIRenderer uses its own exclusively
        // shaderProgram.unbind(); // Or as appropriate based on UIRenderer design
    }

    // Removed ShaderProgram, uiProjection, identityView as UIRenderer handles this
    private void renderItemInHand(ItemStack itemStack, int x, int y) {
        if (itemStack == null || itemStack.isEmpty()) return;

        // For 3D item rendering in UI, the Renderer class is still used.
        // It needs to be set up for orthographic projection if not already done.
        // UIRenderer.beginFrame/endFrame might affect GL state for text,
        // so direct GL calls for 3D items need to be compatible.

        if (itemStack.isBlock()) {
            BlockType type = itemStack.getBlockType();
            if (type != null && type.getAtlasX() != -1) {
                 // Ensure Renderer is set up for this specific 3D draw if UIRenderer altered projection
                 // This might involve renderer.getShaderProgram().bind() and setting matrices for 3D view.
                 renderer.draw3DItemInSlot(type, x, y, SLOT_SIZE - 4, SLOT_SIZE - 4);
                 // Reset GL state if draw3DItemInSlot changed it (e.g. depth test)
                 glDisable(GL_DEPTH_TEST); // Typically disable for subsequent 2D UI
                 glEnable(GL_BLEND);
            }
        } else if (itemStack.getItemType() != null) {
            ItemType type = itemStack.getItemType();
            float r, g, b;
            switch (type) {
                case STICK -> { r = 139; g = 69; b = 19; }
                case WOODEN_PICKAXE -> { r = 100; g = 100; b = 150; }
                default -> { r = 150; g = 150; b = 250; }
            }
             uiRenderer.renderQuad(type == ItemType.STICK ? x + (SLOT_SIZE - 4) / 4.0f : x, y,
                                  type == ItemType.STICK ? (SLOT_SIZE - 4) / 2.0f : SLOT_SIZE - 4,
                                  SLOT_SIZE - 4, r/255f, g/255f, b/255f, 1.0f);
        }
        
        if (itemStack.getCount() > 1) {
            String countText = String.valueOf(itemStack.getCount());
            // Calculate text width for right alignment if UIRenderer doesn't have getTextWidth.
            // For simplicity, using NVG_ALIGN_RIGHT. This requires NanoVG context to be active.
            // Assuming default font size of 12 for item counts for now
            float FONT_SIZE_ITEM_COUNT = 12;
            uiRenderer.drawString(countText,
                                  x + SLOT_SIZE - 4 - 3, // x position for right-aligned text
                                  y + SLOT_SIZE - 4 - FONT_SIZE_ITEM_COUNT - 1, // y position (approx bottom right)
                                  UIRenderer.FONT_SANS_BOLD, FONT_SIZE_ITEM_COUNT,
                                  255, 220, 0, 255,
                                  NVG_ALIGN_RIGHT | NVG_ALIGN_BOTTOM);
        }
    }

    // Removed ShaderProgram, uiProjection, identityView
    private void drawSlot(ItemStack itemStack, int slotX, int slotY,
                          boolean isHotbarSlot, int hotbarIndex, boolean isCraftingOutputSlot, boolean isCraftingInputSlot) {

        if (isHotbarSlot && inventory.getSelectedHotbarSlotIndex() == hotbarIndex) {
            uiRenderer.renderQuad(slotX - 2, slotY - 2, SLOT_SIZE + 4, SLOT_SIZE + 4, 1.0f, 1.0f, 1.0f, 1.0f);
        } else if (isCraftingOutputSlot) {
            uiRenderer.renderQuad(slotX - 2, slotY - 2, SLOT_SIZE + 4, SLOT_SIZE + 4, 150/255f, 150/255f, 50/255f, 1.0f);
        } else {
             uiRenderer.renderQuad(slotX, slotY, SLOT_SIZE, SLOT_SIZE, BORDER_COLOR_R/255f, BORDER_COLOR_G/255f, BORDER_COLOR_B/255f, 1.0f);
        }
        
        float bgR_f = SLOT_BACKGROUND_R/255f, bgG_f = SLOT_BACKGROUND_G/255f, bgB_f = SLOT_BACKGROUND_B/255f;
        if (isCraftingInputSlot) {
            bgR_f = 60/255f; bgG_f = 80/255f; bgB_f = 60/255f;
        } else if (isCraftingOutputSlot && (itemStack == null || itemStack.isEmpty())) {
             bgR_f = 80/255f; bgG_f = 60/255f; bgB_f = 60/255f;
        }
        uiRenderer.renderQuad(slotX + 1, slotY + 1, SLOT_SIZE - 2, SLOT_SIZE - 2, bgR_f, bgG_f, bgB_f, 1.0f);

        if (itemStack != null && !itemStack.isEmpty()) {
            renderItemInHand(itemStack, slotX + 2, slotY + 2);
        }
    }
    
    public void handleMouseInput(int screenWidth, int screenHeight) {
        if (!visible) return;

        Vector2f mousePos = inputHandler.getMousePosition();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;

        // Calculate panel and slot positions (used by multiple input handlers)
        final int craftingGridActualWidth = CRAFTING_GRID_DIMENSION * SLOT_SIZE + (CRAFTING_GRID_DIMENSION - 1) * SLOT_PADDING;
        final int craftingAreaWidthForCentering = craftingGridActualWidth + CRAFTING_ARROW_WIDTH + SLOT_SIZE + 2 * CRAFTING_AREA_PADDING;
        final int craftingAreaHeight = CRAFTING_GRID_DIMENSION * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING;
        final int numDisplayCols = Inventory.MAIN_INVENTORY_COLS;
        final int mainInvWidth = numDisplayCols * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        final int panelWidth = Math.max(mainInvWidth, craftingAreaWidthForCentering);
        final int totalContentHeight = TITLE_HEIGHT + CRAFTING_AREA_PADDING + craftingAreaHeight + CRAFTING_AREA_PADDING +
                                 (Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING) + CRAFTING_AREA_PADDING +
                                 (SLOT_SIZE) + CRAFTING_AREA_PADDING;
        final int panelStartX = (screenWidth - panelWidth) / 2;
        final int panelStartY = (screenHeight - totalContentHeight) / 2;
        
        final int calc_craftingGridX = panelStartX + (panelWidth - (craftingGridActualWidth + CRAFTING_ARROW_WIDTH + SLOT_SIZE)) / 2;
        final int calc_craftingGridY = panelStartY + TITLE_HEIGHT + CRAFTING_AREA_PADDING;
        
        final int calc_craftingArrowX = calc_craftingGridX + craftingGridActualWidth + SLOT_PADDING;
        final int calc_craftingArrowY = calc_craftingGridY + (craftingAreaHeight - SLOT_SIZE) / 2;
        final int calc_craftingOutputX = calc_craftingArrowX + CRAFTING_ARROW_WIDTH;
        final int calc_craftingOutputY = calc_craftingArrowY;
        
        final int calc_mainInvStartY = calc_craftingGridY + craftingAreaHeight + CRAFTING_AREA_PADDING;
        final int calc_mainInvStartX = panelStartX + (panelWidth - mainInvWidth) / 2 + SLOT_PADDING;
        final int calc_hotbarStartY = calc_mainInvStartY + Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING + CRAFTING_AREA_PADDING;

        // 1. Handle Left Mouse Button Release (Dropping a dragged item)
        if (!inputHandler.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) { // Left button is UP
            if (draggedItemStack != null) { // If we were dragging something
                // placeDraggedItem calculates its own slot positions. Ideally, it would take them as params.
                placeDraggedItem(screenWidth, screenHeight);
                // resetDragState is usually called within placeDraggedItem or if item becomes empty
                if (draggedItemStack == null || draggedItemStack.isEmpty()) { // Check if it was fully placed
                     resetDragState(); // Ensure drag state is reset if item is gone
                }
            }
            return; // Done for this frame if left button was released.
        }

        // 2. Handle Right Mouse Button Press (Placing a single item from dragged stack into crafting grid)
        if (inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            if (draggedItemStack != null && !draggedItemStack.isEmpty()) {
                inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                boolean itemPlacedOne = false;

                // Check Crafting Input Slots
                for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
                    int r = i / CRAFTING_GRID_DIMENSION;
                    int c = i % CRAFTING_GRID_DIMENSION;
                    int slotX = calc_craftingGridX + c * (SLOT_SIZE + SLOT_PADDING);
                    int slotY = calc_craftingGridY + r * (SLOT_SIZE + SLOT_PADDING);

                    if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE && mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                        ItemStack targetSlot = craftingInputSlots[i];
                        if (targetSlot.isEmpty()) {
                            ItemStack singleItemToAdd = draggedItemStack.copy(); // Copy dragged item properties
                            singleItemToAdd.setCount(1);                       // Set count to 1
                            craftingInputSlots[i] = singleItemToAdd;           // Place it
                            draggedItemStack.decrementCount(1);                // Decrease dragged stack
                            itemPlacedOne = true;
                        } else if (targetSlot.equals(draggedItemStack) && targetSlot.getCount() < targetSlot.getMaxStackSize()) {
                            targetSlot.incrementCount(1);                      // Increment target slot
                            draggedItemStack.decrementCount(1);                // Decrease dragged stack
                            itemPlacedOne = true;
                        }
                        
                        if (itemPlacedOne) {
                            if (draggedItemStack.isEmpty()) {
                                resetDragState(); // Clear drag if dragged stack is now empty
                            }
                            updateCraftingOutput(true);
                            return; // Action completed for this right-click
                        }
                        // If mouse is over a slot but cannot place (e.g. different item type), 
                        // the action (or inaction) is determined for this slot. Return to finish handling this right-click.
                        return; 
                    }
                }
                // If right-click was pressed while dragging, but not over a crafting input slot.
                // Return to ensure this right-click doesn't fall through to left-click logic.
                return; 
            }
             // If right-click pressed but not dragging an item, it does nothing in this screen specific logic.
             // Return to prevent falling through to left-click logic.
            return; 
        }

        // AT THIS POINT: Left button is down, AND it wasn't a right-click that was handled and returned.

        // 3. Handle Left Mouse Button Held (Not a new press - i.e., dragging)
        // This condition means: Left Mouse is Down AND It's NOT a new Left Mouse Press this frame.
        if (!inputHandler.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            if (draggedItemStack != null) { // Still dragging
                 // If dragging and button is held (not a new press), simply allow visual drag to continue.
                 // No need to consume press here, as it's not a "new" event being handled.
            }
            return; // If button is held (dragging or not), return to avoid pickup logic.
        }
        
        // 4. Handle New Left Mouse Press (Picking up an item)
        // This is reached if: Left button is down, AND it's a new press, AND right-click didn't handle and return.
        inputHandler.consumeMouseButtonPress(GLFW.GLFW_MOUSE_BUTTON_LEFT);

        // Pickup logic uses the 'calc_' prefixed slot positions
        // Try to pick up from Crafting Input Slots
        for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
            int r = i / CRAFTING_GRID_DIMENSION;
            int c = i % CRAFTING_GRID_DIMENSION;
            int slotX = calc_craftingGridX + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY = calc_craftingGridY + r * (SLOT_SIZE + SLOT_PADDING);

            if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE && mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                if (craftingInputSlots[i] != null && !craftingInputSlots[i].isEmpty()) {
                    draggedItemStack = craftingInputSlots[i].copy();
                    craftingInputSlots[i].clear();
                    dragSource = DragSource.CRAFTING_INPUT;
                    draggedItemOriginalCraftingSlotIndex = i;
                    updateCraftingOutput(true);
                    return;
                }
            }
        }
        
        // Try to pick up from Crafting Output Slot
        if (mouseX >= calc_craftingOutputX && mouseX <= calc_craftingOutputX + SLOT_SIZE && mouseY >= calc_craftingOutputY && mouseY <= calc_craftingOutputY + SLOT_SIZE) {
            if (craftingOutputSlot != null && !craftingOutputSlot.isEmpty() && currentValidRecipe != null) {
                ItemStack resultToTake = craftingOutputSlot.copy();
                
                if (inventory.addItem(resultToTake)) { // Try to add to player inventory
                    consumeCraftingIngredients();
                    updateCraftingOutput(true); // Re-evaluate after consumption
                } else {
                     System.out.println("Player inventory full, cannot take crafted item.");
                }
                return;
            }
        }

        // Try to pick up from main inventory
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = calc_mainInvStartX + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = calc_mainInvStartY + row * (SLOT_SIZE + SLOT_PADDING);
            if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE && mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                ItemStack currentStack = inventory.getMainInventorySlot(i);
                if (currentStack != null && !currentStack.isEmpty()) {
                    draggedItemStack = currentStack.copy();
                    inventory.setMainInventorySlot(i, ItemStack.empty());
                    dragSource = DragSource.PLAYER_MAIN;
                    draggedItemOriginalSlotIndex = i;
                    return;
                }
            }
        }

        // Try to pick up from hotbar
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int col = i % Inventory.MAIN_INVENTORY_COLS; // Hotbar uses main inventory columns for alignment
            int slotX = calc_mainInvStartX + col * (SLOT_SIZE + SLOT_PADDING); // Use main inv start X for alignment
            int slotY = calc_hotbarStartY; // Hotbar is at this Y
             if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE && mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                ItemStack currentStack = inventory.getHotbarSlot(i);
                if (currentStack != null && !currentStack.isEmpty()) {
                    draggedItemStack = currentStack.copy();
                    inventory.setHotbarSlot(i, ItemStack.empty());
                    dragSource = DragSource.PLAYER_HOTBAR;
                    draggedItemOriginalSlotIndex = i;
                    return;
                }
            }
        }
    }
    
    private void placeDraggedItem(int screenWidth, int screenHeight) {
        if (draggedItemStack == null || draggedItemStack.isEmpty()) return;

        Vector2f mousePos = inputHandler.getMousePosition();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;
        
        boolean placedEffectively = false;

        // Recalculate positions (same as in handleMouseInput)
        int craftingGridActualWidth = CRAFTING_GRID_DIMENSION * SLOT_SIZE + (CRAFTING_GRID_DIMENSION - 1) * SLOT_PADDING;
        int craftingAreaWidthForCentering = craftingGridActualWidth + CRAFTING_ARROW_WIDTH + SLOT_SIZE + 2 * CRAFTING_AREA_PADDING;
        int craftingAreaHeight = CRAFTING_GRID_DIMENSION * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING;
        int numDisplayCols = Inventory.MAIN_INVENTORY_COLS;
        int mainInvWidth = numDisplayCols * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int panelWidth = Math.max(mainInvWidth, craftingAreaWidthForCentering);
        int totalContentHeight = TITLE_HEIGHT + CRAFTING_AREA_PADDING + craftingAreaHeight + CRAFTING_AREA_PADDING +
                                 (Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING) + CRAFTING_AREA_PADDING +
                                 (SLOT_SIZE) + CRAFTING_AREA_PADDING;
        int panelStartX = (screenWidth - panelWidth) / 2;
        int panelStartY = (screenHeight - totalContentHeight) / 2;
        
        int drop_craftingGridY = panelStartY + TITLE_HEIGHT + CRAFTING_AREA_PADDING;
        int drop_craftingGridX = panelStartX + (panelWidth - (craftingGridActualWidth + CRAFTING_ARROW_WIDTH + SLOT_SIZE)) / 2;

        int mainInvDropY = drop_craftingGridY + craftingAreaHeight + CRAFTING_AREA_PADDING;
        int mainInvDropX = panelStartX + (panelWidth - mainInvWidth) / 2 + SLOT_PADDING;
        int hotbarDropY = mainInvDropY + Inventory.MAIN_INVENTORY_ROWS * (SLOT_SIZE + SLOT_PADDING) - SLOT_PADDING + CRAFTING_AREA_PADDING;


        // Try to place in Crafting Input Slots
        for (int i_loop = 0; i_loop < CRAFTING_INPUT_SLOTS_COUNT; i_loop++) {
            final int slotIdx = i_loop;
            int r = slotIdx / CRAFTING_GRID_DIMENSION;
            int c = slotIdx % CRAFTING_GRID_DIMENSION;
            int slotX = drop_craftingGridX + c * (SLOT_SIZE + SLOT_PADDING);
            int slotY = drop_craftingGridY + r * (SLOT_SIZE + SLOT_PADDING);

            if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE && mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                if (handleGenericSlotPlacement(() -> craftingInputSlots[slotIdx], (item) -> craftingInputSlots[slotIdx] = item)) {
                    placedEffectively = true;
                }
                updateCraftingOutput(true); // Update output regardless of full placement for swaps
                break; 
            }
        }

        // Try to place in main inventory
        if (!placedEffectively) {
            for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
                int row = i / Inventory.MAIN_INVENTORY_COLS;
                int col = i % Inventory.MAIN_INVENTORY_COLS;
                int slotX = mainInvDropX + col * (SLOT_SIZE + SLOT_PADDING);
                int slotY = mainInvDropY + row * (SLOT_SIZE + SLOT_PADDING);
                 if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE && mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                    final int inventorySlotIndex = i;
                    if (handleGenericSlotPlacement(() -> inventory.getMainInventorySlot(inventorySlotIndex), (item) -> inventory.setMainInventorySlot(inventorySlotIndex, item))) {
                         placedEffectively = true;
                    }
                    break; 
                }
            }
        }

        // Try to place in hotbar
        if (!placedEffectively) {
            for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
                int col = i % Inventory.MAIN_INVENTORY_COLS;
                int slotX = mainInvDropX + col * (SLOT_SIZE + SLOT_PADDING);
                int slotY = hotbarDropY;
                 if (mouseX >= slotX && mouseX <= slotX + SLOT_SIZE && mouseY >= slotY && mouseY <= slotY + SLOT_SIZE) {
                    final int hotbarSlotIndex = i;
                     if (handleGenericSlotPlacement(() -> inventory.getHotbarSlot(hotbarSlotIndex), (item) -> inventory.setHotbarSlot(hotbarSlotIndex, item))) {
                        placedEffectively = true;
                    }
                    break;
                }
            }
        }
        
        if (draggedItemStack == null || draggedItemStack.isEmpty()) { // Item was fully placed or stacked
            resetDragState();
        } else if (!placedEffectively) { // Could not place anywhere, try to return
            tryReturnToOriginalSlot();
             if (draggedItemStack != null && !draggedItemStack.isEmpty()) { // Still holding after tryReturn
                 // Item couldn't be returned to original spot. Game might drop it on ground. Here, just stays in hand.
             } else {
                 resetDragState(); // Returned or cleared.
             }
        }
        // If placedEffectively is true, but draggedItemStack is NOT empty, it means a swap happened.
        // The original dragged item is placed. The new draggedItemStack is the item from the target slot.
        // The dragSource and originalSlotIndex for the *newly* picked up item are effectively set by handleGenericSlotPlacement's swap logic.
    }

    // Generalized placement: checks target, tries to stack or swap.
    // Returns true if the item *being dragged* was successfully placed (either fully, partially, or by initiating a swap).
    private boolean handleGenericSlotPlacement(java.util.function.Supplier<ItemStack> getTargetSlot, java.util.function.Consumer<ItemStack> setTargetSlot) {
        ItemStack targetStack = getTargetSlot.get();
        boolean itemWeWereHoldingIsPlaced;

        if (targetStack.isEmpty()) {
            setTargetSlot.accept(draggedItemStack.copy()); // Place a copy
            draggedItemStack.clear(); // Clear the one in hand
            itemWeWereHoldingIsPlaced = true;
        } else if (targetStack.equals(draggedItemStack) && targetStack.getCount() < targetStack.getMaxStackSize()) {
            int canAdd = targetStack.getMaxStackSize() - targetStack.getCount();
            int toAdd = Math.min(canAdd, draggedItemStack.getCount());
            
            ItemStack modifiedTarget = targetStack.copy(); // Modify a copy then set
            modifiedTarget.incrementCount(toAdd);
            setTargetSlot.accept(modifiedTarget);
            
            draggedItemStack.decrementCount(toAdd);
            if (draggedItemStack.isEmpty()) {
                draggedItemStack.clear();
            }
            itemWeWereHoldingIsPlaced = true; // At least part of it was placed
        } else { // Different items or target is full stack - SWAP
            ItemStack itemFromTargetSlot = targetStack.copy();
            setTargetSlot.accept(draggedItemStack.copy()); // Place our item
            draggedItemStack = itemFromTargetSlot;          // Pick up target's item
            itemWeWereHoldingIsPlaced = true; 
            // Update dragSource/originalSlotIndex for the newly picked up item (this becomes complex if trying to track original spot of *new* item)
            // For now, treat the original dragged item as "placed". The new item in hand has no "original slot" from the current drag op.
            dragSource = DragSource.NONE; // Effectively, we just picked up a new item.
            draggedItemOriginalSlotIndex = -1;
            draggedItemOriginalCraftingSlotIndex = -1;
        }
        
        if (draggedItemStack != null && draggedItemStack.isEmpty()) {
            draggedItemStack = null; // Nullify if it became empty
        }
        return itemWeWereHoldingIsPlaced;
    }

    private void tryReturnToOriginalSlot() {
        if (draggedItemStack == null || draggedItemStack.isEmpty()) {
            resetDragState();
            return;
        }

        boolean returned = false;
        switch (dragSource) {
            case PLAYER_HOTBAR -> {
                if (draggedItemOriginalSlotIndex >= 0 && draggedItemOriginalSlotIndex < Inventory.HOTBAR_SIZE) {
                    ItemStack target = inventory.getHotbarSlot(draggedItemOriginalSlotIndex);
                    if (target.isEmpty()) {
                        inventory.setHotbarSlot(draggedItemOriginalSlotIndex, draggedItemStack);
                        returned = true;
                    } else if (target.equals(draggedItemStack) && target.getCount() < target.getMaxStackSize()) {
                        int canAdd = target.getMaxStackSize() - target.getCount();
                        int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                        inventory.getHotbarSlot(draggedItemOriginalSlotIndex).incrementCount(toAdd);
                        draggedItemStack.decrementCount(toAdd);
                        if(draggedItemStack.isEmpty()) returned = true;
                    }
                }
            }
            case PLAYER_MAIN -> {
                 if (draggedItemOriginalSlotIndex >= 0 && draggedItemOriginalSlotIndex < Inventory.MAIN_INVENTORY_SIZE) {
                    ItemStack target = inventory.getMainInventorySlot(draggedItemOriginalSlotIndex);
                    if (target.isEmpty()) {
                        inventory.setMainInventorySlot(draggedItemOriginalSlotIndex, draggedItemStack);
                        returned = true;
                    } else if (target.equals(draggedItemStack) && target.getCount() < target.getMaxStackSize()) {
                        int canAdd = target.getMaxStackSize() - target.getCount();
                        int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                        inventory.getMainInventorySlot(draggedItemOriginalSlotIndex).incrementCount(toAdd);
                        draggedItemStack.decrementCount(toAdd);
                         if(draggedItemStack.isEmpty()) returned = true;
                    }
                 }
            }
            case CRAFTING_INPUT -> {
                if (draggedItemOriginalCraftingSlotIndex >= 0 && draggedItemOriginalCraftingSlotIndex < CRAFTING_INPUT_SLOTS_COUNT) {
                    ItemStack target = craftingInputSlots[draggedItemOriginalCraftingSlotIndex];
                     if (target.isEmpty()) {
                        craftingInputSlots[draggedItemOriginalCraftingSlotIndex] = draggedItemStack;
                        returned = true;
                    } else if (target.equals(draggedItemStack) && target.getCount() < target.getMaxStackSize()) {
                         int canAdd = target.getMaxStackSize() - target.getCount();
                        int toAdd = Math.min(canAdd, draggedItemStack.getCount());
                        craftingInputSlots[draggedItemOriginalCraftingSlotIndex].incrementCount(toAdd);
                        draggedItemStack.decrementCount(toAdd);
                        if(draggedItemStack.isEmpty()) returned = true;
                    }
                    updateCraftingOutput(true);
                }
            }
            case NONE -> {
            }
        }

        if (returned || (draggedItemStack != null && draggedItemStack.isEmpty())) {
            draggedItemStack = null; // Clear if returned or became empty
            resetDragState();
        }
        // If not returned and still has items, it remains dragged.
    }

    private void resetDragState() {
        draggedItemStack = null;
        draggedItemOriginalSlotIndex = -1;
        draggedItemOriginalCraftingSlotIndex = -1;
        dragSource = DragSource.NONE;
    }
    
    private void updateCraftingOutput(boolean is3x3Grid) {
        // Ensure the craftingInputSlots array is appropriate for the RecipeManager
        // RecipeManager's findMatchingRecipe(ItemStack[], boolean) will expect
        // an array of 9 for 3x3, or 4 for 2x2.
        // Our this.craftingInputSlots is already sized for 3x3 (9 slots).
        CraftingRecipe matchedRecipe = recipeManager.findMatchingRecipe(this.craftingInputSlots, is3x3Grid);
        this.currentValidRecipe = matchedRecipe;

        if (matchedRecipe != null) {
            craftingOutputSlot = matchedRecipe.getOutput().copy();
        } else {
            craftingOutputSlot.clear(); // No recipe found, clear output slot
        }
    }
    
    /**
     * Consumes ingredients from the crafting input slots based on the matched recipe.
     * Prioritizes custom consumption rules, then falls back to default logic for shaped or shapeless recipes.
     */
    private void consumeCraftingIngredients() {
        if (currentValidRecipe == null) return;

        Map<ItemStack.CraftableIdentifier, Integer> consumptionMap = currentValidRecipe.getItemsConsumedPerCraft();

        if (consumptionMap != null && !consumptionMap.isEmpty()) {
            // Use custom consumption rules from the recipe
            for (Map.Entry<ItemStack.CraftableIdentifier, Integer> entry : consumptionMap.entrySet()) {
                ItemStack.CraftableIdentifier consumedId = entry.getKey();
                int quantityToConsume = entry.getValue();

                if (quantityToConsume <= 0) continue;

                // Find and consume items from crafting grid slots
                for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
                    ItemStack slotStack = craftingInputSlots[i];
                    if (!slotStack.isEmpty() && slotStack.getIdentifier().equals(consumedId)) {
                        int canConsume = Math.min(quantityToConsume, slotStack.getCount());
                        slotStack.decrementCount(canConsume);
                        quantityToConsume -= canConsume;
                        if (slotStack.isEmpty()) {
                            slotStack.clear();
                        }
                        if (quantityToConsume <= 0) {
                            break; // All needed for this type are consumed
                        }
                    }
                }
            }
        } else {
            // Default consumption logic:
            if (currentValidRecipe.isShapeless()) {
                // For shapeless, consume the exact amount specified in recipe ingredients from any slots.
                List<ItemStack> requiredIngredients = currentValidRecipe.getShapelessIngredients();
                if (requiredIngredients == null) return; // Should not happen with valid recipes

                // Create a mutable copy of required ingredients map for consumption tracking
                Map<ItemStack.CraftableIdentifier, Integer> requiredToConsume = new HashMap<>();
                for (ItemStack ingredient : requiredIngredients) {
                    requiredToConsume.put(ingredient.getIdentifier(), requiredToConsume.getOrDefault(ingredient.getIdentifier(), 0) + ingredient.getCount());
                }
                
                // Iterate through grid slots and consume
                for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
                    ItemStack slotStack = craftingInputSlots[i];
                    if (!slotStack.isEmpty()) {
                        ItemStack.CraftableIdentifier slotId = slotStack.getIdentifier();
                        int neededForType = requiredToConsume.getOrDefault(slotId, 0);

                        if (neededForType > 0) { // If this type is needed
                            int canConsume = Math.min(neededForType, slotStack.getCount());
                            slotStack.decrementCount(canConsume);
                            requiredToConsume.put(slotId, neededForType - canConsume);
                            if (slotStack.isEmpty()) {
                                slotStack.clear();
                            }
                        }
                    }
                }

            } else {
                // For shaped recipes without custom consumption, consume 1 from each actively used pattern slot.
                for (int i = 0; i < CRAFTING_INPUT_SLOTS_COUNT; i++) {
                    // Check if recipe expects an item in this shaped slot, AND it's not empty in the grid.
                    // This implies the item of correct type for this slot has already matched based on current crafting input.
                    if (!currentValidRecipe.getShapedInputPattern()[i].isEmpty() && craftingInputSlots[i] != null && !craftingInputSlots[i].isEmpty()) {
                         craftingInputSlots[i].decrementCount(1);
                         if (craftingInputSlots[i].isEmpty()) {
                             craftingInputSlots[i].clear();
                         }
                    }
                }
            }
        }
    }
}