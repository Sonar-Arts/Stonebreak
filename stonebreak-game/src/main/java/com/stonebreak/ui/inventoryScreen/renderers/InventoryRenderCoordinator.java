package com.stonebreak.ui.inventoryScreen.renderers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
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
 * Coordinates rendering phases for the inventory screen using MasonryUI (Skija).
 *
 * Rendering is split into three sequential phases per frame so that Skija and
 * the game's OpenGL item rendering never overlap:
 *   A) Skija  – panel, titles, slot backgrounds, buttons, crafting arrow
 *   B) GL     – item icons drawn directly into the framebuffer
 *   C) Skija  – item count text
 *
 * Tooltips and the dragged-item overlay are handled by separate entry points
 * (renderTooltipsOnly / renderDraggedItemOnly) so callers can interleave world
 * block-drop rendering between the inventory chrome and the tooltips.
 */
public class InventoryRenderCoordinator {

    private final UIRenderer uiRenderer;
    private final Renderer renderer;
    private final InputHandler inputHandler;
    private final Inventory inventory;
    private final InventoryController controller;
    private final InventoryInputManager inputManager;
    private final InventoryCraftingManager craftingManager;

    private final MasonryUI ui;
    private final MButton recipeButton;
    private final MButton craftAllButton;
    private final MHotbarRenderer mHotbarRenderer;

    // Tab bar buttons — visual only; click detection is in InventoryInputManager
    private final MButton tabInventory;
    private final MButton tabCharacter;
    private final MButton tabClasses;
    private final MButton tabSkills;
    private final MButton tabFeats;

    // Crafting arrow fill: ARGB equivalent of old InventoryTheme.Crafting.ARROW_FILL (140,140,140,180)
    private static final int ARROW_FILL = 0xB48C8C8C;

    // Semi-transparent inventory panel (75% opaque) — lets the game world show through.
    // MStyle.PANEL_FILL is kept fully opaque for settings menus; we override here.
    private static final int PANEL_FILL_TRANS = 0xBF6B6B6B;

    // Tab bar constants — shared with InventoryInputManager for click-detection bounds
    public static final int INV_TAB_WIDTH  = 84;
    public static final int INV_TAB_HEIGHT = 28;
    public static final int INV_TAB_GAP    = 4;

    public InventoryRenderCoordinator(UIRenderer uiRenderer,
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

        // Buttons are purely visual here; click detection remains in InventoryInputManager.
        this.recipeButton   = new MButton("Recipes");
        this.craftAllButton = new MButton("Craft All").fontSize(MStyle.FONT_META);

        // Tab buttons — visual only; click detection is in InventoryInputManager
        this.tabInventory = new MButton("Inventory").fontSize(MStyle.FONT_META);
        this.tabCharacter = new MButton("Character").fontSize(MStyle.FONT_META);
        this.tabClasses   = new MButton("Classes").fontSize(MStyle.FONT_META);
        this.tabSkills    = new MButton("Skills").fontSize(MStyle.FONT_META);
        this.tabFeats     = new MButton("Feats").fontSize(MStyle.FONT_META);
    }

    // ─────────────────────────────────────────────── Public entry points

    public void render(int screenWidth, int screenHeight) {
        controller.setHoveredItemStack(null);

        InventoryLayoutCalculator.InventoryLayout layout =
                InventoryLayoutCalculator.calculateLayout(screenWidth, screenHeight);

        Vector2f mouse = inputHandler.getMousePosition();
        float mouseX = mouse.x;
        float mouseY = mouse.y;

        updateButtonPositions(layout);
        recipeButton.updateHover(mouseX, mouseY);
        craftAllButton.updateHover(mouseX, mouseY);
        updateTabBounds(layout);
        tabInventory.updateHover(mouseX, mouseY);
        tabCharacter.updateHover(mouseX, mouseY);
        tabClasses  .updateHover(mouseX, mouseY);
        tabSkills   .updateHover(mouseX, mouseY);
        tabFeats    .updateHover(mouseX, mouseY);

        // Phase A — Skija: panel, titles, slot backgrounds, buttons, arrow
        if (ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
            Canvas canvas = ui.canvas();
            drawTabBar(canvas, layout);
            drawPanel(canvas, layout);
            drawTitles(canvas, layout);
            drawCraftingSection(canvas, layout, mouseX, mouseY);
            drawInventorySection(layout, mouseX, mouseY);
            ui.renderOverlays();
            ui.endFrame();
        }

        // Phase B — GL: item icons composited onto the framebuffer
        renderItemIcons(layout);

        // Phase C — Skija: count texts on top of item icons
        if (ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
            Canvas canvas = ui.canvas();
            drawAllCountTexts(canvas, layout);
            ui.endFrame();
        }
    }

    /** Same as render — tooltip is drawn by renderTooltipsOnly after block drops. */
    public void renderWithoutTooltips(int screenWidth, int screenHeight) {
        render(screenWidth, screenHeight);
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
        if (item == null || item.getAtlasX() == -1 || item.getAtlasY() == -1) return;

        Vector2f mouse = inputHandler.getMousePosition();
        int iconSize = InventoryLayoutCalculator.getSlotSize() - 4;
        int iconX = (int)(mouse.x - iconSize / 2.0f);
        int iconY = (int)(mouse.y - iconSize / 2.0f);

        // GL item icon — no frame active here
        if (item instanceof BlockType bt) {
            uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt, iconX, iconY,
                    iconSize, iconSize, renderer.getTextureAtlas(), true);
        } else {
            uiRenderer.renderItemIcon(iconX, iconY, iconSize, iconSize, item, renderer.getTextureAtlas());
        }

        // Count text in its own Skija frame
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

    // ─── Hotbar (MasonryUI/Skija — MHotbarRenderer) ───────────────────────────

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

        // "Crafting" title — match old panelStartY + 20 baseline position
        float craftY = layout.panelStartY + 20 + MStyle.FONT_BUTTON / 3f;
        MPainter.drawCenteredStringWithShadow(canvas, "Crafting", centerX, craftY,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

        // "Inventory" title — match old mainInvContentStartY - 20
        float invY = layout.mainInvContentStartY - 20 + MStyle.FONT_BUTTON / 3f;
        MPainter.drawCenteredStringWithShadow(canvas, "Inventory", centerX, invY,
                font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    }

    private void drawCraftingSection(Canvas canvas,
                                     InventoryLayoutCalculator.InventoryLayout layout,
                                     float mouseX, float mouseY) {
        int slotSize    = InventoryLayoutCalculator.getSlotSize();
        int slotPadding = InventoryLayoutCalculator.getSlotPadding();
        int gridSize    = InventoryLayoutCalculator.getCraftingGridSize();
        int inputCount  = InventoryLayoutCalculator.getCraftingInputSlotsCount();
        ItemStack[] craftingInput = craftingManager.getCraftingInputSlots();

        // 2×2 crafting input slots
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

        // Buttons (positions already set in updateButtonPositions before Phase A)
        recipeButton.render(ui);

        boolean hasCraftOutput = craftingManager.getCraftingOutputSlot() != null
                && !craftingManager.getCraftingOutputSlot().isEmpty();
        if (hasCraftOutput) {
            craftAllButton.render(ui);
        }
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

    /** Render a single slot background via MItemSlot. */
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
        int iconInset   = 3; // pixels inset from slot edge
        int iconSize    = slotSize - iconInset * 2;
        int gridSize    = InventoryLayoutCalculator.getCraftingGridSize();
        int inputCount  = InventoryLayoutCalculator.getCraftingInputSlotsCount();
        ItemStack[] craftingInput = craftingManager.getCraftingInputSlots();

        // Crafting inputs
        for (int i = 0; i < inputCount; i++) {
            int row  = i / gridSize;
            int col  = i % gridSize;
            int sx = layout.craftingElementsStartX + col * (slotSize + slotPadding);
            int sy = layout.craftingGridStartY      + row * (slotSize + slotPadding);
            drawItemIcon(craftingInput[i], sx + iconInset, sy + iconInset, iconSize);
        }

        // Crafting output
        drawItemIcon(craftingManager.getCraftingOutputSlot(),
                layout.outputSlotX + iconInset, layout.outputSlotY + iconInset, iconSize);

        // Main inventory
        ItemStack[] mainSlots = inventory.getMainInventorySlots();
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            int row  = i / Inventory.MAIN_INVENTORY_COLS;
            int col  = i % Inventory.MAIN_INVENTORY_COLS;
            int sx = layout.inventorySectionStartX + slotPadding + col * (slotSize + slotPadding);
            int sy = layout.mainInvContentStartY   + slotPadding + row * (slotSize + slotPadding);
            drawItemIcon(mainSlots[i], sx + iconInset, sy + iconInset, iconSize);
        }

        // Hotbar
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
        if (item == null) return;
        boolean isSboItem = item instanceof ItemType it
                && com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer.isSboBackedItem(it);
        if (!isSboItem && (item.getAtlasX() == -1 || item.getAtlasY() == -1)) return;

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
        int gridSize    = InventoryLayoutCalculator.getCraftingGridSize();
        int inputCount  = InventoryLayoutCalculator.getCraftingInputSlotsCount();
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

    /** Keep button widget bounds in sync with inputManager's click-test bounds. */
    private void updateButtonPositions(InventoryLayoutCalculator.InventoryLayout layout) {
        inputManager.updateRecipeButtonBoundsForRendering(layout);
        recipeButton.bounds(inputManager.getRecipeButtonX(), inputManager.getRecipeButtonY(),
                inputManager.getRecipeButtonWidth(), inputManager.getRecipeButtonHeight());

        inputManager.updateCraftAllButtonBoundsForRendering(layout);
        craftAllButton.bounds(inputManager.getCraftAllButtonX(), inputManager.getCraftAllButtonY(),
                inputManager.getCraftAllButtonWidth(), inputManager.getCraftAllButtonHeight());
    }

    // ─────────────────────────────────────────────── Tab bar

    /** Positions all five tab buttons flush above the inventory panel. */
    private void updateTabBounds(InventoryLayoutCalculator.InventoryLayout layout) {
        float tabY = layout.panelStartY - INV_TAB_HEIGHT;
        float stride = INV_TAB_WIDTH + INV_TAB_GAP;
        tabInventory.bounds(layout.panelStartX,              tabY, INV_TAB_WIDTH, INV_TAB_HEIGHT);
        tabCharacter.bounds(layout.panelStartX + stride,     tabY, INV_TAB_WIDTH, INV_TAB_HEIGHT);
        tabClasses  .bounds(layout.panelStartX + stride * 2, tabY, INV_TAB_WIDTH, INV_TAB_HEIGHT);
        tabSkills   .bounds(layout.panelStartX + stride * 3, tabY, INV_TAB_WIDTH, INV_TAB_HEIGHT);
        tabFeats    .bounds(layout.panelStartX + stride * 4, tabY, INV_TAB_WIDTH, INV_TAB_HEIGHT);
    }

    /** Draws all five tabs — Inventory is always active here. */
    private void drawTabBar(Canvas canvas, InventoryLayoutCalculator.InventoryLayout layout) {
        float tabY = layout.panelStartY - INV_TAB_HEIGHT;
        float stride = INV_TAB_WIDTH + INV_TAB_GAP;
        drawTab(canvas, layout.panelStartX,              tabY, "Inventory", true,  tabInventory.isHovered());
        drawTab(canvas, layout.panelStartX + stride,     tabY, "Character", false, tabCharacter.isHovered());
        drawTab(canvas, layout.panelStartX + stride * 2, tabY, "Classes",   false, tabClasses.isHovered());
        drawTab(canvas, layout.panelStartX + stride * 3, tabY, "Skills",    false, tabSkills.isHovered());
        drawTab(canvas, layout.panelStartX + stride * 4, tabY, "Feats",     false, tabFeats.isHovered());
    }

    private void drawTab(Canvas canvas, float x, float y, String label, boolean active, boolean hovered) {
        int fill = active ? 0xFF7A7A7A
                : hovered ? MStyle.BUTTON_FILL_HI
                : MStyle.BUTTON_FILL;
        MPainter.stoneSurface(canvas, x, y, INV_TAB_WIDTH, INV_TAB_HEIGHT, MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
        Font font  = ui.fonts().get(MStyle.FONT_META);
        int  color = active ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
        float ty   = y + INV_TAB_HEIGHT * 0.5f + MStyle.FONT_META * 0.38f;
        MPainter.drawCenteredStringWithShadow(canvas, label,
                x + INV_TAB_WIDTH / 2f, ty, font, color, MStyle.TEXT_SHADOW);
    }

    private void checkHover(ItemStack itemStack, float sx, float sy, int slotSize,
                            float mouseX, float mouseY) {
        if (itemStack == null || itemStack.isEmpty()) return;
        if (mouseX >= sx && mouseX <= sx + slotSize && mouseY >= sy && mouseY <= sy + slotSize) {
            controller.setHoveredItemStack(itemStack);
        }
    }
}
