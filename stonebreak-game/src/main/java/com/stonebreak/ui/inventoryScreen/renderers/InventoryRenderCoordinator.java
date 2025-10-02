package com.stonebreak.ui.inventoryScreen.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.Item;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.ui.inventoryScreen.core.InventoryController;
import com.stonebreak.ui.inventoryScreen.core.InventoryCraftingManager;
import com.stonebreak.ui.inventoryScreen.core.InventoryInputManager;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import com.stonebreak.ui.recipeScreen.renderers.RecipeUIStyleRenderer;
import org.joml.Vector2f;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Coordinates rendering phases for the inventory screen.
 * Follows Single Responsibility Principle by handling only rendering coordination.
 */
public class InventoryRenderCoordinator {

    private final UIRenderer uiRenderer;
    private final Renderer renderer;
    private final InputHandler inputHandler;
    private final Inventory inventory;
    private final InventoryController controller;
    private final InventoryInputManager inputManager;
    private final InventoryCraftingManager craftingManager;

    private static final String RECIPE_BUTTON_TEXT = "Recipes";

    public InventoryRenderCoordinator(UIRenderer uiRenderer,
                                     Renderer renderer,
                                     InputHandler inputHandler,
                                     Inventory inventory,
                                     InventoryController controller,
                                     InventoryInputManager inputManager,
                                     InventoryCraftingManager craftingManager) {
        this.uiRenderer = uiRenderer;
        this.renderer = renderer;
        this.inputHandler = inputHandler;
        this.inventory = inventory;
        this.controller = controller;
        this.inputManager = inputManager;
        this.craftingManager = craftingManager;
    }

    public void render(int screenWidth, int screenHeight) {
        controller.setHoveredItemStack(null);

        InventoryLayoutCalculator.InventoryLayout layout = InventoryLayoutCalculator.calculateLayout(screenWidth, screenHeight);

        renderPanel(layout);
        renderCraftingSection(layout);
        renderInventorySection(layout);
    }

    public void renderWithoutTooltips(int screenWidth, int screenHeight) {
        render(screenWidth, screenHeight);
    }

    public void renderTooltipsOnly(int screenWidth, int screenHeight) {
        ItemStack hoveredItemStack = controller.getHoveredItemStack();
        if (hoveredItemStack != null && !hoveredItemStack.isEmpty() &&
            !inputManager.getDragState().isDragging()) {
            Item item = hoveredItemStack.getItem();
            if (item != null && item != BlockType.AIR) {
                Vector2f mousePos = inputHandler.getMousePosition();
                InventoryTooltipRenderer.drawItemTooltip(uiRenderer, item.getName(),
                                                       mousePos.x + 15, mousePos.y + 15,
                                                       screenWidth, screenHeight);
            }
        }
    }

    public void renderDraggedItemOnly(int screenWidth, int screenHeight) {
        InventoryDragDropHandler.DragState dragState = inputManager.getDragState();
        if (!dragState.isDragging()) {
            return;
        }

        Vector2f mousePos = inputHandler.getMousePosition();
        int itemRenderX = (int) (mousePos.x - (InventoryLayoutCalculator.getSlotSize() - 4) / 2.0f);
        int itemRenderY = (int) (mousePos.y - (InventoryLayoutCalculator.getSlotSize() - 4) / 2.0f);

        Item item = dragState.draggedItemStack.getItem();
        if (item != null && item.getAtlasX() != -1 && item.getAtlasY() != -1) {
            drawDraggedItem(item, itemRenderX, itemRenderY, dragState.draggedItemStack.getCount());
        }
    }

    public void renderHotbar(int screenWidth, int screenHeight) {
        uiRenderer.renderHotbar(controller.getHotbarScreen(), screenWidth, screenHeight,
                              renderer.getTextureAtlas(), renderer.getShaderProgram());
        uiRenderer.renderHotbarTooltip(controller.getHotbarScreen(), screenWidth, screenHeight);
    }

    public void renderHotbarWithoutTooltips(int screenWidth, int screenHeight) {
        uiRenderer.renderHotbar(controller.getHotbarScreen(), screenWidth, screenHeight,
                              renderer.getTextureAtlas(), renderer.getShaderProgram());
    }

    public void renderHotbarTooltipsOnly(int screenWidth, int screenHeight) {
        uiRenderer.renderHotbarTooltip(controller.getHotbarScreen(), screenWidth, screenHeight);
    }

    private void renderPanel(InventoryLayoutCalculator.InventoryLayout layout) {
        InventoryPanelRenderer.drawInventoryPanel(uiRenderer, layout.panelStartX, layout.panelStartY,
                                                layout.inventoryPanelWidth, layout.inventoryPanelHeight);
    }

    private void renderCraftingSection(InventoryLayoutCalculator.InventoryLayout layout) {
        // Draw "Crafting" title
        float craftingTitleY = layout.panelStartY + 20;
        InventoryPanelRenderer.drawInventoryTitle(uiRenderer, layout.panelStartX + layout.inventoryPanelWidth / 2,
                                                craftingTitleY, "Crafting");

        // Render crafting input slots
        renderCraftingInputSlots(layout);

        // Draw crafting arrow
        renderCraftingArrow(layout);

        // Render crafting output slot
        renderCraftingOutputSlot(layout);

        // Render recipe button
        renderRecipeButton(layout);

        // Render craft all button
        renderCraftAllButton(layout);
    }

    private void renderCraftingInputSlots(InventoryLayoutCalculator.InventoryLayout layout) {
        ItemStack[] craftingInputSlots = craftingManager.getCraftingInputSlots();

        for (int i = 0; i < InventoryLayoutCalculator.getCraftingInputSlotsCount(); i++) {
            int r = i / InventoryLayoutCalculator.getCraftingGridSize();
            int c = i % InventoryLayoutCalculator.getCraftingGridSize();
            int slotX = layout.craftingElementsStartX +
                       c * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.craftingGridStartY +
                       r * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            InventorySlotRenderer.drawInventorySlotWithHover(craftingInputSlots[i], slotX, slotY, false, -1,
                                                          uiRenderer, renderer, inputHandler);
            checkHover(craftingInputSlots[i], slotX, slotY);
        }
    }

    private void renderCraftingArrow(InventoryLayoutCalculator.InventoryLayout layout) {
        int arrowX = layout.craftingElementsStartX + layout.craftInputGridVisualWidth +
                    InventoryLayoutCalculator.getSlotPadding() +
                    (InventoryLayoutCalculator.getSlotSize() - 20) / 2;
        int arrowY = layout.craftingGridStartY + (InventoryLayoutCalculator.getSlotSize() - 20) / 2;
        InventoryCraftingRenderer.drawCraftingArrow(uiRenderer, arrowX, arrowY, 20, 20);
    }

    private void renderCraftingOutputSlot(InventoryLayoutCalculator.InventoryLayout layout) {
        ItemStack craftingOutputSlot = craftingManager.getCraftingOutputSlot();
        InventorySlotRenderer.drawInventorySlotWithHover(craftingOutputSlot, layout.outputSlotX, layout.outputSlotY,
                                                      false, -1, uiRenderer, renderer, inputHandler);
        checkHover(craftingOutputSlot, layout.outputSlotX, layout.outputSlotY);
    }

    private void renderRecipeButton(InventoryLayoutCalculator.InventoryLayout layout) {
        // Ensure button bounds are calculated before rendering
        inputManager.updateRecipeButtonBoundsForRendering(layout);

        InventoryButtonRenderer.drawRecipeButton(uiRenderer, inputHandler,
                                                inputManager.getRecipeButtonX(),
                                                inputManager.getRecipeButtonY(),
                                                inputManager.getRecipeButtonWidth(),
                                                inputManager.getRecipeButtonHeight(),
                                                RECIPE_BUTTON_TEXT);
    }

    private void renderCraftAllButton(InventoryLayoutCalculator.InventoryLayout layout) {
        // Only render if there's something to craft
        if (craftingManager.getCraftingOutputSlot() != null &&
            !craftingManager.getCraftingOutputSlot().isEmpty()) {

            // Ensure button bounds are calculated before rendering
            inputManager.updateCraftAllButtonBoundsForRendering(layout);

            // Use smaller font size for the compact button
            float smallerFontSize = RecipeUIStyleRenderer.RecipeFonts.BODY_MEDIUM;

            InventoryButtonRenderer.drawRecipeButton(uiRenderer, inputHandler,
                                                    inputManager.getCraftAllButtonX(),
                                                    inputManager.getCraftAllButtonY(),
                                                    inputManager.getCraftAllButtonWidth(),
                                                    inputManager.getCraftAllButtonHeight(),
                                                    "Craft All",
                                                    smallerFontSize);
        }
    }

    private void renderInventorySection(InventoryLayoutCalculator.InventoryLayout layout) {
        // Draw "Inventory" title
        InventoryPanelRenderer.drawInventoryTitle(uiRenderer, layout.panelStartX + layout.inventoryPanelWidth / 2,
                                                layout.mainInvContentStartY - 20, "Inventory");

        // Render main inventory slots
        renderMainInventorySlots(layout);

        // Render hotbar slots
        renderHotbarSlots(layout);
    }

    private void renderMainInventorySlots(InventoryLayoutCalculator.InventoryLayout layout) {
        ItemStack[] mainSlots = inventory.getMainInventorySlots();

        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.inventorySectionStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.mainInvContentStartY + InventoryLayoutCalculator.getSlotPadding() +
                       row * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());

            InventorySlotRenderer.drawInventorySlotWithHover(mainSlots[i], slotX, slotY, false, -1,
                                                          uiRenderer, renderer, inputHandler);
            checkHover(mainSlots[i], slotX, slotY);
        }
    }

    private void renderHotbarSlots(InventoryLayoutCalculator.InventoryLayout layout) {
        ItemStack[] hotbarSlots = inventory.getHotbarSlots();

        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int slotX = layout.inventorySectionStartX + InventoryLayoutCalculator.getSlotPadding() +
                       col * (InventoryLayoutCalculator.getSlotSize() + InventoryLayoutCalculator.getSlotPadding());
            int slotY = layout.hotbarRowY;

            InventorySlotRenderer.drawInventorySlotWithHover(hotbarSlots[i], slotX, slotY, true, i,
                                                          uiRenderer, renderer, inputHandler);
            checkHover(hotbarSlots[i], slotX, slotY);
        }
    }

    private void checkHover(ItemStack itemStack, int slotX, int slotY) {
        if (itemStack == null || itemStack.isEmpty()) {
            return;
        }

        Vector2f mousePos = inputHandler.getMousePosition();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;

        if (mouseX >= slotX && mouseX <= slotX + InventoryLayoutCalculator.getSlotSize() &&
            mouseY >= slotY && mouseY <= slotY + InventoryLayoutCalculator.getSlotSize()) {
            controller.setHoveredItemStack(itemStack);
        }
    }

    private void drawDraggedItem(Item item, int x, int y, int count) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            uiRenderer.endFrame();

            if (item instanceof BlockType bt) {
                uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt, x, y,
                                          InventoryLayoutCalculator.getSlotSize() - 4,
                                          InventoryLayoutCalculator.getSlotSize() - 4,
                                          renderer.getTextureAtlas(), true);
            } else {
                uiRenderer.renderItemIcon(x, y,
                                        InventoryLayoutCalculator.getSlotSize() - 4,
                                        InventoryLayoutCalculator.getSlotSize() - 4,
                                        item, renderer.getTextureAtlas());
            }

            uiRenderer.beginFrame(Game.getWindowWidth(), Game.getWindowHeight(), 1.0f);

            if (count > 1) {
                String countText = String.valueOf(count);
                RecipeUIStyleRenderer.RecipeFonts.setBodyFont(vg, RecipeUIStyleRenderer.RecipeFonts.BODY_SMALL);
                nvgTextAlign(vg, NVG_ALIGN_RIGHT | NVG_ALIGN_BOTTOM);

                // Text shadow
                nvgFillColor(vg, nvgRGBA(0, 0, 0, 200, NVGColor.malloc(stack)));
                nvgText(vg, x + InventoryLayoutCalculator.getSlotSize() - 2,
                       y + InventoryLayoutCalculator.getSlotSize() - 2, countText);

                // Main text
                nvgFillColor(vg, nvgRGBA(255, 220, 0, 255, NVGColor.malloc(stack)));
                nvgText(vg, x + InventoryLayoutCalculator.getSlotSize() - 3,
                       y + InventoryLayoutCalculator.getSlotSize() - 3, countText);
            }
        }
    }

    private NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return org.lwjgl.nanovg.NanoVG.nvgRGBA((byte)r, (byte)g, (byte)b, (byte)a, color);
    }
}