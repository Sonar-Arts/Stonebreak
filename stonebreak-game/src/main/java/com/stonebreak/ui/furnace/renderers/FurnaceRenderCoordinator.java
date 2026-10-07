package com.stonebreak.ui.furnace.renderers;

import com.stonebreak.ui.LegacyUiClock;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.UI.components.MHotbarRenderer;
import com.openmason.engine.ui.masonry.MItemSlot;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.masonry.MTooltip;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.stonebreak.ui.furnace.core.FurnaceController;
import com.stonebreak.ui.furnace.core.FurnaceInputManager;
import com.stonebreak.ui.furnace.core.FurnaceLayout;
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
                    iconSize, iconSize, renderer.getBlockTextureArray(), true);
        } else {
            uiRenderer.renderItemIcon(iconX, iconY, iconSize, iconSize, item, renderer.getBlockTextureArray());
        }

        int count = ds.draggedItemStack.getCount();
        if (count > 1 && ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
            Canvas canvas = ui.canvas();
            Font font = ui.fonts().getScaled(MStyle.FONT_META);
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
        MPainter.containerPanel(canvas,
                layout.panelStartX, layout.panelStartY,
                layout.inventoryPanelWidth, layout.inventoryPanelHeight);
    }

    private void drawFurnaceSection(Canvas canvas,
                                   InventoryLayoutCalculator.InventoryLayout layout,
                                   float mouseX, float mouseY) {
        int panelPad  = InventoryLayoutCalculator.getPanelPadding();
        int titleH    = InventoryLayoutCalculator.getTitleHeight();

        // Title
        Font font = ui.fonts().getScaled(MStyle.FONT_BUTTON);
        float centerX = layout.panelStartX + layout.inventoryPanelWidth / 2f;
        float titleY = layout.panelStartY + panelPad + titleH + InventoryLayoutCalculator.getSectionSpacing();
        MPainter.drawCenteredStringWithShadow(canvas, "Furnace", centerX, titleY,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

        FurnaceLayout.Slots s = FurnaceLayout.compute(layout);

        // Crucible (heat-colored disk + chutes to slots) drawn BEFORE the slots
        // so the slot frames sit on top of the chutes' endpoints.
        float fuelRatio = controller.getFuelRatio();          // 0..1 of currently-lit unit
        CruciblePainter.paintBowl(canvas, s, fuelRatio, controller.isCooking() || fuelRatio > 0f, animTime());

        // Slots — top (ingredient), bottom (fuel), right (output)
        drawSlot(s.ingredientX, s.ingredientY, s.slotSize, mouseX, mouseY, false);
        checkHover(controller.getIngredientSlot(), s.ingredientX, s.ingredientY, s.slotSize, mouseX, mouseY);

        drawSlot(s.fuelX, s.fuelY, s.slotSize, mouseX, mouseY, false);
        checkHover(controller.getFuelSlot(), s.fuelX, s.fuelY, s.slotSize, mouseX, mouseY);

        drawSlot(s.outputX, s.outputY, s.slotSize, mouseX, mouseY, false);
        checkHover(controller.getOutputSlot(), s.outputX, s.outputY, s.slotSize, mouseX, mouseY);

        // Progress ring sweeping clockwise from ingredient (12 o'clock) toward output (3 o'clock)
        CruciblePainter.paintRings(canvas, s, controller.getCookProgressRatio(), controller.getFuelRatio());

        // Player inventory title
        float invTitleY = layout.mainInvContentStartY - 20;
        MPainter.drawCenteredStringWithShadow(canvas, "Inventory", centerX, invTitleY,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    }

    private float animTime() {
        return (float) LegacyUiClock.seconds(); // pinned for fidelity captures (#296)
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
        int iconInset = 3;
        int iconSize  = slotSize - iconInset * 2;

        FurnaceLayout.Slots s = FurnaceLayout.compute(layout);
        drawItemIcon(controller.getIngredientSlot(), s.ingredientX + iconInset, s.ingredientY + iconInset, iconSize);
        drawItemIcon(controller.getFuelSlot(),       s.fuelX + iconInset,       s.fuelY + iconInset,       iconSize);
        drawItemIcon(controller.getOutputSlot(),     s.outputX + iconInset,     s.outputY + iconInset,     iconSize);

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
                    renderer.getBlockTextureArray());
        } else {
            uiRenderer.renderItemIcon(x, y, size, size, item, renderer.getBlockTextureArray());
        }
    }

    /* ── Phase C – count texts ────────────────────────────── */

    private void drawAllCountTexts(Canvas canvas, InventoryLayoutCalculator.InventoryLayout layout) {
        Font font       = ui.fonts().getScaled(MStyle.FONT_META);
        int slotSize    = InventoryLayoutCalculator.getSlotSize();
        int padding     = InventoryLayoutCalculator.getSlotPadding();
        FurnaceLayout.Slots s = FurnaceLayout.compute(layout);
        drawCountText(canvas, font, controller.getIngredientSlot(), s.ingredientX, s.ingredientY, slotSize);
        drawCountText(canvas, font, controller.getFuelSlot(),       s.fuelX,       s.fuelY,       slotSize);
        drawCountText(canvas, font, controller.getOutputSlot(),     s.outputX,     s.outputY,     slotSize);

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
