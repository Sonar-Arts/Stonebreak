package com.stonebreak.ui.furnace.renderers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.UI.components.MHotbarRenderer;
import com.stonebreak.rendering.UI.masonryUI.MItemSlot;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MTooltip;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.furnace.core.FurnaceController;
import com.stonebreak.ui.furnace.core.FurnaceInputManager;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Vector2f;

/**
 * Renders the furnace screen in the same three-phase pattern as inventory/workbench:
 *   A) Skija – panel, furnace chrome, slot backgrounds, progress bars
 *   B) GL    – item icons
 *   C) Skija – count text overlays
 */
public class FurnaceRenderCoordinator {

    private static final int ARROW_FILL       = 0xB48C8C8C;
    private static final int PANEL_FILL_TRANS = 0xBF6B6B6B;
    private static final int PROGRESS_FILL    = 0xFFE87D1C;   // orange smelt-fill
    private static final int FUEL_FILL        = 0xFF44AADD;   // blue fuel-fill

    private final UIRenderer uiRenderer;
    private final Renderer renderer;
    private final InputHandler inputHandler;
    private final Inventory inventory;
    private final FurnaceController controller;
    private final FurnaceInputManager inputManager;
    private final MasonryUI ui;
    private final MHotbarRenderer mHotbarRenderer;

    public FurnaceRenderCoordinator(UIRenderer uiRenderer,
                                    Renderer renderer,
                                    InputHandler inputHandler,
                                    Inventory inventory,
                                    FurnaceController controller,
                                    FurnaceInputManager inputManager,
                                    com.stonebreak.crafting.SmeltingManager smeltingManager) {
        this.uiRenderer      = uiRenderer;
        this.renderer        = renderer;
        this.inputHandler    = inputHandler;
        this.inventory       = inventory;
        this.controller      = controller;
        this.inputManager    = inputManager;
        this.ui              = new MasonryUI(renderer.getSkijaBackend());
        this.mHotbarRenderer = new MHotbarRenderer(uiRenderer, renderer);
    }

    /* ── Public entry points ─────────────────────────────── */

    public void render(int screenWidth, int screenHeight) {
        renderWithoutTooltips(screenWidth, screenHeight);
        renderTooltipsOnly(screenWidth, screenHeight);
    }

    public void renderWithoutTooltips(int screenWidth, int screenHeight) {
        controller.setHoveredItemStack(null);

        InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateWorkbenchLayout(screenWidth, screenHeight);

        Vector2f mouse = inputHandler.getMousePosition();
        float mouseX = mouse.x, mouseY = mouse.y;

        // Phase A – Skija: chrome
        if (ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
            Canvas canvas = ui.canvas();
            drawPanel(canvas, layout);
            drawFurnaceSection(canvas, layout, mouseX, mouseY);
            drawInventorySection(layout, mouseX, mouseY);
            ui.renderOverlays();
            ui.endFrame();
        }

        // Phase B – GL: item icons
        renderItemIcons(layout);

        // Phase C – Skija: count text
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
        InventoryDragDropHandler.DragState ds = inputManager.getDragState();
        if (!ds.isDragging()) return;
        Item item = ds.draggedItemStack.getItem();
        if (item == null || !item.hasIcon()) return;

        Vector2f mouse = inputHandler.getMousePosition();
        int iconSize = InventoryLayoutCalculator.getSlotSize() - 4;
        int iconX = (int)(mouse.x - iconSize / 2f);
        int iconY = (int)(mouse.y - iconSize / 2f);

        if (item instanceof BlockType bt) {
            uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt, iconX, iconY,
                    iconSize, iconSize, renderer.getTextureAtlas(), true);
        } else {
            uiRenderer.renderItemIcon(iconX, iconY, iconSize, iconSize, item, renderer.getTextureAtlas());
        }

        int count = ds.draggedItemStack.getCount();
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

    /* ── Phase A helpers ─────────────────────────────────── */

    private void drawPanel(Canvas canvas, InventoryLayoutCalculator.InventoryLayout layout) {
        MPainter.stoneSurface(canvas,
                layout.panelStartX, layout.panelStartY,
                layout.inventoryPanelWidth, layout.inventoryPanelHeight,
                MStyle.PANEL_RADIUS,
                PANEL_FILL_TRANS, MStyle.PANEL_BORDER,
                MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, MStyle.PANEL_DROP_SHADOW,
                MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);
    }

    private void drawFurnaceSection(Canvas canvas,
                                   InventoryLayoutCalculator.InventoryLayout layout,
                                   float mouseX, float mouseY) {
        int slotSize  = InventoryLayoutCalculator.getSlotSize();
        int padding   = InventoryLayoutCalculator.getSlotPadding();
        int panelPad  = InventoryLayoutCalculator.getPanelPadding();
        int titleH    = InventoryLayoutCalculator.getTitleHeight();

        // Title
        Font font = ui.fonts().get(MStyle.FONT_BUTTON);
        float centerX = layout.panelStartX + layout.inventoryPanelWidth / 2f;
        float titleY = layout.panelStartY + panelPad + titleH + InventoryLayoutCalculator.getSectionSpacing();
        MPainter.drawCenteredStringWithShadow(canvas, "Furnace", centerX, titleY,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

        // Slot positions
        int furnaceStartX = layout.panelStartX + panelPad + (layout.inventoryPanelWidth - panelPad * 2
                - (slotSize * 2 + padding)) / 2;
        int furnaceY = (int) titleY + (int)(titleH / 2f);

        int ingredientX = furnaceStartX;
        int ingredientY = furnaceY;
        int fuelX       = furnaceStartX;
        int fuelY       = furnaceY + slotSize + padding;
        int outputX     = furnaceStartX + slotSize + padding;
        int outputY     = furnaceY;

        // Draw slots
        drawSlot(ingredientX, ingredientY, slotSize, mouseX, mouseY, false);
        checkHover(controller.getIngredientSlot(), ingredientX, ingredientY, slotSize, mouseX, mouseY);

        drawSlot(fuelX, fuelY, slotSize, mouseX, mouseY, false);
        checkHover(controller.getFuelSlot(), fuelX, fuelY, slotSize, mouseX, mouseY);

        drawSlot(outputX, outputY, slotSize, mouseX, mouseY, false);
        checkHover(controller.getOutputSlot(), outputX, outputY, slotSize, mouseX, mouseY);

        // Smelt arrow (centered between ingredient and output)
        float arrowX = ingredientX + slotSize + 4;
        float arrowY = ingredientY + (slotSize - 18) / 2f;
        MPainter.craftingArrow(canvas, arrowX, arrowY, outputX - (int)arrowX - 4, 18, ARROW_FILL);

        // Progress bar below arrow
        float barX = arrowX;
        float barY = ingredientY + slotSize - 6f;
        float barW = outputX - (int)arrowX - 4;
        float barH = 4f;
        MPainter.fillRect(canvas, barX, barY, barW, barH, 0x40000000);
        MPainter.fillRect(canvas, barX, barY, barW * controller.getCookProgressRatio(), barH, PROGRESS_FILL);

        // Fuel bar below ingredient slot
        float fuelBarX = fuelX;
        float fuelBarY = fuelY + slotSize - 5f;
        float fuelBarW = slotSize - 2f;
        float fuelBarH = 3f;
        MPainter.fillRect(canvas, fuelBarX + 1f, fuelBarY, fuelBarW, fuelBarH, 0x40000000);
        MPainter.fillRect(canvas, fuelBarX + 1f, fuelBarY, fuelBarW * controller.getFuelRatio(), fuelBarH, FUEL_FILL);

        // Player inventory title
        float invTitleY = layout.mainInvContentStartY - 20;
        MPainter.drawCenteredStringWithShadow(canvas, "Inventory", centerX, invTitleY,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    }

    private void drawInventorySection(InventoryLayoutCalculator.InventoryLayout layout,
                                      float mouseX, float mouseY) {
        int slotSize  = InventoryLayoutCalculator.getSlotSize();
        int padding   = InventoryLayoutCalculator.getSlotPadding();
        ItemStack[] mainSlots   = inventory.getMainInventorySlots();
        ItemStack[] hotbarSlots = inventory.getHotbarSlots();
        int selectedHotbar      = inventory.getSelectedHotbarSlotIndex();

        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            float sx = layout.inventorySectionStartX + padding + col * (slotSize + padding);
            float sy = layout.mainInvContentStartY + padding + row * (slotSize + padding);
            drawSlot(sx, sy, slotSize, mouseX, mouseY, false);
            checkHover(mainSlots[i], sx, sy, slotSize, mouseX, mouseY);
        }

        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            float sx = layout.inventorySectionStartX + padding + i * (slotSize + padding);
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

    /* ── Phase B – GL item icons ──────────────────────────── */

    private void renderItemIcons(InventoryLayoutCalculator.InventoryLayout layout) {
        int slotSize  = InventoryLayoutCalculator.getSlotSize();
        int padding   = InventoryLayoutCalculator.getSlotPadding();
        int panelPad  = InventoryLayoutCalculator.getPanelPadding();
        int titleH    = InventoryLayoutCalculator.getTitleHeight();
        int iconInset = 3;
        int iconSize  = slotSize - iconInset * 2;

        float titleY = layout.panelStartY + panelPad + titleH + InventoryLayoutCalculator.getSectionSpacing();
        int furnaceStartX = layout.panelStartX + panelPad + (layout.inventoryPanelWidth - panelPad * 2
                - (slotSize * 2 + padding)) / 2;
        int furnaceY = (int) titleY + (int)(titleH / 2f);

        // Furnace slots
        drawItemIcon(controller.getIngredientSlot(), furnaceStartX + iconInset, furnaceY + iconInset, iconSize);
        drawItemIcon(controller.getFuelSlot(), furnaceStartX + iconInset, furnaceY + slotSize + padding + iconInset, iconSize);
        drawItemIcon(controller.getOutputSlot(), furnaceStartX + slotSize + padding + iconInset, furnaceY + iconInset, iconSize);

        // Inventory
        ItemStack[] mainSlots = inventory.getMainInventorySlots();
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            int sx = layout.inventorySectionStartX + padding + col * (slotSize + padding);
            int sy = layout.mainInvContentStartY + padding + row * (slotSize + padding);
            drawItemIcon(mainSlots[i], sx + iconInset, sy + iconInset, iconSize);
        }

        ItemStack[] hotbarSlots = inventory.getHotbarSlots();
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            int sx = layout.inventorySectionStartX + padding + i * (slotSize + padding);
            int sy = layout.hotbarRowY;
            drawItemIcon(hotbarSlots[i], sx + iconInset, sy + iconInset, iconSize);
        }
    }

    private void drawItemIcon(ItemStack stack, int x, int y, int size) {
        if (stack == null || stack.isEmpty()) return;
        Item item = stack.getItem();
        if (item == null || !item.hasIcon()) return;

        if (item instanceof BlockType bt) {
            uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt, x, y, size, size,
                    renderer.getTextureAtlas());
        } else {
            uiRenderer.renderItemIcon(x, y, size, size, item, renderer.getTextureAtlas());
        }
    }

    /* ── Phase C – count texts ────────────────────────────── */

    private void drawAllCountTexts(Canvas canvas, InventoryLayoutCalculator.InventoryLayout layout) {
        Font font       = ui.fonts().get(MStyle.FONT_META);
        int slotSize    = InventoryLayoutCalculator.getSlotSize();
        int padding     = InventoryLayoutCalculator.getSlotPadding();
        int panelPad    = InventoryLayoutCalculator.getPanelPadding();
        int titleH      = InventoryLayoutCalculator.getTitleHeight();

        float titleY = layout.panelStartY + panelPad + titleH + InventoryLayoutCalculator.getSectionSpacing();
        int furnaceStartX = layout.panelStartX + panelPad + (layout.inventoryPanelWidth - panelPad * 2
                - (slotSize * 2 + padding)) / 2;
        int furnaceY = (int) titleY + (int)(titleH / 2f);

        drawCountText(canvas, font, controller.getIngredientSlot(), furnaceStartX, furnaceY, slotSize);
        drawCountText(canvas, font, controller.getFuelSlot(), furnaceStartX, furnaceY + slotSize + padding, slotSize);
        drawCountText(canvas, font, controller.getOutputSlot(), furnaceStartX + slotSize + padding, furnaceY, slotSize);

        // Inventory
        ItemStack[] mainSlots = inventory.getMainInventorySlots();
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row = i / Inventory.MAIN_INVENTORY_COLS;
            int col = i % Inventory.MAIN_INVENTORY_COLS;
            float sx = layout.inventorySectionStartX + padding + col * (slotSize + padding);
            float sy = layout.mainInvContentStartY + padding + row * (slotSize + padding);
            drawCountText(canvas, font, mainSlots[i], sx, sy, slotSize);
        }

        ItemStack[] hotbarSlots = inventory.getHotbarSlots();
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            float sx = layout.inventorySectionStartX + padding + i * (slotSize + padding);
            float sy = layout.hotbarRowY;
            drawCountText(canvas, font, hotbarSlots[i], sx, sy, slotSize);
        }
    }

    private void drawCountText(Canvas canvas, Font font, ItemStack stack,
                               float slotX, float slotY, int slotSize) {
        if (stack == null || stack.isEmpty() || stack.getCount() <= 1) return;
        String countStr = String.valueOf(stack.getCount());
        float textX = slotX + slotSize - MPainter.measureWidth(font, countStr) - 2f;
        float textY = slotY + slotSize - 2f;
        MPainter.drawStringWithShadow(canvas, countStr, textX, textY,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    }

    /* ── Utilities ───────────────────────────────────────── */

    private void checkHover(ItemStack stack, float sx, float sy, int size,
                            float mouseX, float mouseY) {
        if (stack == null || stack.isEmpty()) return;
        if (mouseX >= sx && mouseX <= sx + size && mouseY >= sy && mouseY <= sy + size) {
            controller.setHoveredItemStack(stack);
        }
    }
}
