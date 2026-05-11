package com.stonebreak.ui.inventoryScreen.renderers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.UI.components.MHotbarRenderer;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MItemSlot;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MTooltip;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.inventoryScreen.core.InventoryController;
import com.stonebreak.ui.inventoryScreen.core.InventoryCraftingManager;
import com.stonebreak.ui.inventoryScreen.core.InventoryInputManager;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Vector2f;

/**
 * Coordinates rendering phases for the workbench (crafting-table) screen.
 *
 * Mirrors {@link InventoryRenderCoordinator}'s three-phase pattern but uses
 * the workbench-specific 3×3 crafting grid and "Workbench" title. Drag-overlay
 * and tooltip pathways are identical to inventory's so the player gets the
 * same visual response everywhere a crafting UI shows up.
 */
public class WorkbenchRenderCoordinator {

    private static final String RECIPE_BUTTON_TEXT = "Recipes";
    // Same translucent panel + arrow tints as the inventory.
    private static final int ARROW_FILL       = 0xB48C8C8C;
    private static final int PANEL_FILL_TRANS = 0xBF6B6B6B;

    private final UIRenderer uiRenderer;
    private final Renderer renderer;
    private final InputHandler inputHandler;
    private final Inventory inventory;
    private final InventoryController controller;
    private final InventoryInputManager inputManager;
    private final InventoryCraftingManager craftingManager;

    private final MasonryUI ui;
    private final MButton recipeButton;
    private final MHotbarRenderer mHotbarRenderer;

    public WorkbenchRenderCoordinator(UIRenderer uiRenderer,
                                      Renderer renderer,
                                      InputHandler inputHandler,
                                      Inventory inventory,
                                      InventoryController controller,
                                      InventoryInputManager inputManager,
                                      InventoryCraftingManager craftingManager) {
        this.uiRenderer      = uiRenderer;
        this.renderer        = renderer;
        this.inputHandler    = inputHandler;
        this.inventory       = inventory;
        this.controller      = controller;
        this.inputManager    = inputManager;
        this.craftingManager = craftingManager;

        this.ui              = new MasonryUI(renderer.getSkijaBackend());
        this.mHotbarRenderer = new MHotbarRenderer(uiRenderer, renderer);

        this.recipeButton    = new MButton(RECIPE_BUTTON_TEXT);
    }

    // ─────────────────────────────────────────────── Public entry points

    public void render(int screenWidth, int screenHeight) {
        renderWithoutTooltips(screenWidth, screenHeight);
        renderTooltipsOnly(screenWidth, screenHeight);
    }

    public void renderWithoutTooltips(int screenWidth, int screenHeight) {
        controller.setHoveredItemStack(null);

        InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(screenWidth, screenHeight);

        Vector2f mouse = inputHandler.getMousePosition();
        float mouseX = mouse.x;
        float mouseY = mouse.y;

        updateButtonPositions(layout);
        recipeButton.updateHover(mouseX, mouseY);

        // Phase A — Skija: chrome
        if (ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
            Canvas canvas = ui.canvas();
            drawPanel(canvas, layout);
            drawTitles(canvas, layout);
            drawCraftingSection(canvas, layout, mouseX, mouseY);
            drawInventorySection(layout, mouseX, mouseY);
            ui.renderOverlays();
            ui.endFrame();
        }

        // Phase B — GL: item icons
        renderItemIcons(layout);

        // Phase C — Skija: count text on top
        if (ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
            drawAllCountTexts(ui.canvas(), layout);
            ui.endFrame();
        }
    }

    public void renderTooltipsOnly(int screenWidth, int screenHeight) {
        ItemStack hovered = controller.getHoveredItemStack();
        if (hovered == null || hovered.isEmpty() || inputManager.getDragState().isDragging()) return;

        Item item = hovered.getItem();
        if (item == null || item == BlockType.AIR) return;

        Vector2f mouse = inputHandler.getMousePosition();
        if (ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
            MTooltip.draw(ui, item.getName(), mouse.x + 15, mouse.y + 15,
                          screenWidth, screenHeight);
            ui.endFrame();
        }
    }

    public void renderDraggedItemOnly(int screenWidth, int screenHeight) {
        InventoryDragDropHandler.DragState dragState = inputManager.getDragState();
        if (!dragState.isDragging()) return;

        ItemStack dragged = dragState.draggedItemStack;
        Item item = dragged.getItem();
        if (item == null || !item.hasIcon()) return;

        Vector2f mouse = inputHandler.getMousePosition();
        int iconSize = InventoryLayoutCalculator.getSlotSize() - 4;
        int iconX = (int)(mouse.x - iconSize / 2.0f);
        int iconY = (int)(mouse.y - iconSize / 2.0f);

        if (item instanceof BlockType bt) {
            uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt, iconX, iconY,
                    iconSize, iconSize, renderer.getTextureAtlas(), true);
        } else {
            uiRenderer.renderItemIcon(iconX, iconY, iconSize, iconSize, item, renderer.getTextureAtlas());
        }

        int count = dragged.getCount();
        if (count > 1 && ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
            Canvas canvas = ui.canvas();
            Font font = ui.fonts().get(MStyle.FONT_META);
            String countStr = String.valueOf(count);
            float textX = iconX + iconSize - MPainter.measureWidth(font, countStr) - 2f;
            float textY = iconY + iconSize - 2f;
            MPainter.drawStringWithShadow(canvas, countStr, textX, textY,
                    font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
            ui.endFrame();
        }
    }

    public void renderHotbar(int screenWidth, int screenHeight) {
        mHotbarRenderer.renderHotbar(controller.getHotbarScreen(), screenWidth, screenHeight);
        mHotbarRenderer.renderHotbarTooltip(controller.getHotbarScreen(), screenWidth, screenHeight);
    }

    public void renderHotbarWithoutTooltips(int screenWidth, int screenHeight) {
        mHotbarRenderer.renderHotbar(controller.getHotbarScreen(), screenWidth, screenHeight);
    }

    public void renderHotbarTooltipsOnly(int screenWidth, int screenHeight) {
        mHotbarRenderer.renderHotbarTooltip(controller.getHotbarScreen(), screenWidth, screenHeight);
    }

    // ─────────────────────────────────────────────── Phase A helpers

    private void drawPanel(Canvas canvas, InventoryLayoutCalculator.InventoryLayout layout) {
        MPainter.stoneSurface(canvas,
                layout.panelStartX, layout.panelStartY,
                layout.inventoryPanelWidth, layout.inventoryPanelHeight,
                MStyle.PANEL_RADIUS,
                PANEL_FILL_TRANS, MStyle.PANEL_BORDER,
                MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, MStyle.PANEL_DROP_SHADOW,
                MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);
    }

    private void drawTitles(Canvas canvas, InventoryLayoutCalculator.InventoryLayout layout) {
        Font font = ui.fonts().get(MStyle.FONT_BUTTON);
        float centerX = layout.panelStartX + layout.inventoryPanelWidth / 2f;

        float craftY = layout.panelStartY + 20 + MStyle.FONT_BUTTON / 3f;
        MPainter.drawCenteredStringWithShadow(canvas, "Workbench", centerX, craftY,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

        float invY = layout.mainInvContentStartY - 20 + MStyle.FONT_BUTTON / 3f;
        MPainter.drawCenteredStringWithShadow(canvas, "Inventory", centerX, invY,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    }

    private void drawCraftingSection(Canvas canvas,
                                     InventoryLayoutCalculator.InventoryLayout layout,
                                     float mouseX, float mouseY) {
        int slotSize    = InventoryLayoutCalculator.getSlotSize();
        int slotPadding = InventoryLayoutCalculator.getSlotPadding();
        int gridSize    = InventoryLayoutCalculator.getWorkbenchCraftingGridSize();
        int inputCount  = InventoryLayoutCalculator.getWorkbenchCraftingInputSlotsCount();
        ItemStack[] craftingInput = craftingManager.getCraftingInputSlots();

        // 3×3 crafting input slots
        for (int i = 0; i < inputCount; i++) {
            int row  = i / gridSize;
            int col  = i % gridSize;
            float sx = layout.craftingElementsStartX + col * (slotSize + slotPadding);
            float sy = layout.craftingGridStartY      + row * (slotSize + slotPadding);
            drawSlot(sx, sy, slotSize, mouseX, mouseY, false);
            checkHover(craftingInput[i], sx, sy, slotSize, mouseX, mouseY);
        }

        // Arrow
        float arrowX = layout.craftingElementsStartX + layout.craftInputGridVisualWidth
                + slotPadding + (slotSize - 20) / 2f;
        float arrowY = layout.craftingGridStartY + (slotSize - 20) / 2f;
        MPainter.craftingArrow(canvas, arrowX, arrowY, 20, 20, ARROW_FILL);

        // Output slot
        float ox = layout.outputSlotX;
        float oy = layout.outputSlotY;
        drawSlot(ox, oy, slotSize, mouseX, mouseY, false);
        checkHover(craftingManager.getCraftingOutputSlot(), ox, oy, slotSize, mouseX, mouseY);

        // Recipe button (visual only — click bounds tracked by inputManager)
        recipeButton.render(ui);
    }

    private void drawInventorySection(InventoryLayoutCalculator.InventoryLayout layout,
                                      float mouseX, float mouseY) {
        int slotSize    = InventoryLayoutCalculator.getSlotSize();
        int slotPadding = InventoryLayoutCalculator.getSlotPadding();
        ItemStack[] mainSlots   = inventory.getMainInventorySlots();
        ItemStack[] hotbarSlots = inventory.getHotbarSlots();
        int selectedHotbar      = inventory.getSelectedHotbarSlotIndex();

        // Main inventory 3×9
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row  = i / Inventory.MAIN_INVENTORY_COLS;
            int col  = i % Inventory.MAIN_INVENTORY_COLS;
            float sx = layout.inventorySectionStartX + slotPadding + col * (slotSize + slotPadding);
            float sy = layout.mainInvContentStartY   + slotPadding + row * (slotSize + slotPadding);
            drawSlot(sx, sy, slotSize, mouseX, mouseY, false);
            checkHover(mainSlots[i], sx, sy, slotSize, mouseX, mouseY);
        }

        // Hotbar 1×9
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            float sx = layout.inventorySectionStartX + slotPadding + i * (slotSize + slotPadding);
            float sy = layout.hotbarRowY;
            boolean selected = (i == selectedHotbar);
            drawSlot(sx, sy, slotSize, mouseX, mouseY, selected);
            checkHover(hotbarSlots[i], sx, sy, slotSize, mouseX, mouseY);
        }
    }

    private void drawSlot(float x, float y, int size, float mouseX, float mouseY,
                          boolean hotbarSelected) {
        MItemSlot slot = new MItemSlot()
                .hotbarSelected(hotbarSelected)
                .bounds(x, y, size, size);
        slot.updateHover(mouseX, mouseY);
        slot.render(ui);
    }

    // ─────────────────────────────────────────────── Phase B — GL item icons

    private void renderItemIcons(InventoryLayoutCalculator.InventoryLayout layout) {
        int slotSize    = InventoryLayoutCalculator.getSlotSize();
        int slotPadding = InventoryLayoutCalculator.getSlotPadding();
        int iconInset   = 3;
        int iconSize    = slotSize - iconInset * 2;
        int gridSize    = InventoryLayoutCalculator.getWorkbenchCraftingGridSize();
        int inputCount  = InventoryLayoutCalculator.getWorkbenchCraftingInputSlotsCount();
        ItemStack[] craftingInput = craftingManager.getCraftingInputSlots();

        for (int i = 0; i < inputCount; i++) {
            int row  = i / gridSize;
            int col  = i % gridSize;
            int sx = layout.craftingElementsStartX + col * (slotSize + slotPadding);
            int sy = layout.craftingGridStartY      + row * (slotSize + slotPadding);
            drawItemIcon(craftingInput[i], sx + iconInset, sy + iconInset, iconSize);
        }

        drawItemIcon(craftingManager.getCraftingOutputSlot(),
                layout.outputSlotX + iconInset, layout.outputSlotY + iconInset, iconSize);

        ItemStack[] mainSlots = inventory.getMainInventorySlots();
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row  = i / Inventory.MAIN_INVENTORY_COLS;
            int col  = i % Inventory.MAIN_INVENTORY_COLS;
            int sx = layout.inventorySectionStartX + slotPadding + col * (slotSize + slotPadding);
            int sy = layout.mainInvContentStartY   + slotPadding + row * (slotSize + slotPadding);
            drawItemIcon(mainSlots[i], sx + iconInset, sy + iconInset, iconSize);
        }

        ItemStack[] hotbarSlots = inventory.getHotbarSlots();
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int sx = layout.inventorySectionStartX + slotPadding + i * (slotSize + slotPadding);
            int sy = layout.hotbarRowY;
            drawItemIcon(hotbarSlots[i], sx + iconInset, sy + iconInset, iconSize);
        }
    }

    private void drawItemIcon(ItemStack itemStack, int x, int y, int size) {
        if (itemStack == null || itemStack.isEmpty()) return;
        Item item = itemStack.getItem();
        if (item == null || !item.hasIcon()) return;

        if (item instanceof BlockType bt) {
            uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt, x, y, size, size,
                    renderer.getTextureAtlas());
        } else {
            uiRenderer.renderItemIcon(x, y, size, size, item, renderer.getTextureAtlas());
        }
    }

    // ─────────────────────────────────────────────── Phase C — count texts

    private void drawAllCountTexts(Canvas canvas,
                                   InventoryLayoutCalculator.InventoryLayout layout) {
        Font font       = ui.fonts().get(MStyle.FONT_META);
        int slotSize    = InventoryLayoutCalculator.getSlotSize();
        int slotPadding = InventoryLayoutCalculator.getSlotPadding();
        int gridSize    = InventoryLayoutCalculator.getWorkbenchCraftingGridSize();
        int inputCount  = InventoryLayoutCalculator.getWorkbenchCraftingInputSlotsCount();
        ItemStack[] craftingInput = craftingManager.getCraftingInputSlots();

        for (int i = 0; i < inputCount; i++) {
            int row  = i / gridSize;
            int col  = i % gridSize;
            float sx = layout.craftingElementsStartX + col * (slotSize + slotPadding);
            float sy = layout.craftingGridStartY      + row * (slotSize + slotPadding);
            drawCountText(canvas, font, craftingInput[i], sx, sy, slotSize);
        }

        drawCountText(canvas, font, craftingManager.getCraftingOutputSlot(),
                layout.outputSlotX, layout.outputSlotY, slotSize);

        ItemStack[] mainSlots = inventory.getMainInventorySlots();
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row  = i / Inventory.MAIN_INVENTORY_COLS;
            int col  = i % Inventory.MAIN_INVENTORY_COLS;
            float sx = layout.inventorySectionStartX + slotPadding + col * (slotSize + slotPadding);
            float sy = layout.mainInvContentStartY   + slotPadding + row * (slotSize + slotPadding);
            drawCountText(canvas, font, mainSlots[i], sx, sy, slotSize);
        }

        ItemStack[] hotbarSlots = inventory.getHotbarSlots();
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            float sx = layout.inventorySectionStartX + slotPadding + i * (slotSize + slotPadding);
            float sy = layout.hotbarRowY;
            drawCountText(canvas, font, hotbarSlots[i], sx, sy, slotSize);
        }
    }

    private void drawCountText(Canvas canvas, Font font, ItemStack itemStack,
                               float slotX, float slotY, int slotSize) {
        if (itemStack == null || itemStack.isEmpty() || itemStack.getCount() <= 1) return;
        String countStr = String.valueOf(itemStack.getCount());
        float textX = slotX + slotSize - MPainter.measureWidth(font, countStr) - 2f;
        float textY = slotY + slotSize - 2f;
        MPainter.drawStringWithShadow(canvas, countStr, textX, textY,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    }

    // ─────────────────────────────────────────────── Utilities

    private void updateButtonPositions(InventoryLayoutCalculator.InventoryLayout layout) {
        inputManager.updateRecipeButtonBoundsForRendering(layout);
        recipeButton.bounds(inputManager.getRecipeButtonX(), inputManager.getRecipeButtonY(),
                inputManager.getRecipeButtonWidth(), inputManager.getRecipeButtonHeight());
    }

    private void checkHover(ItemStack itemStack, float sx, float sy, int slotSize,
                            float mouseX, float mouseY) {
        if (itemStack == null || itemStack.isEmpty()) return;
        if (mouseX >= sx && mouseX <= sx + slotSize && mouseY >= sy && mouseY <= sy + slotSize) {
            controller.setHoveredItemStack(itemStack);
        }
    }
}
