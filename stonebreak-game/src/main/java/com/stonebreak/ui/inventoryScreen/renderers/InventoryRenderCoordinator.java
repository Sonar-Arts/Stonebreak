package com.stonebreak.ui.inventoryScreen.renderers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.UI.components.MHotbarRenderer;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MEquipSlot;
import com.stonebreak.rendering.UI.masonryUI.MItemSlot;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStatRow;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MTooltip;
import com.stonebreak.rendering.UI.masonryUI.MVitalBar;
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
 * Three sequential phases per frame keep Skija and OpenGL from overlapping:
 *   A) Skija  — full 3-column panel backgrounds, slots, buttons, vital bars, stat rows
 *   B) GL     — item icons drawn directly into the framebuffer (center column only)
 *   C) Skija  — item count text overlays (center column only)
 */
public class InventoryRenderCoordinator {

  private final UIRenderer uiRenderer;
  private final Renderer renderer;
  private final InputHandler inputHandler;
  private final Inventory inventory;
  private final InventoryController controller;
  private final InventoryInputManager inputManager;
  private final InventoryCraftingManager craftingManager;
  private final CharacterStats stats;

  private final MasonryUI ui;
  private final MButton recipeButton;
  private final MButton craftAllButton;
  private final MHotbarRenderer mHotbarRenderer;

  // Tab buttons — visual only; click detection is in InventoryInputManager
  private final MButton tabInventory;
  private final MButton tabCharacter;
  private final MButton tabClasses;
  private final MButton tabSkills;
  private final MButton tabFeats;

  // Crafting arrow fill
  private static final int ARROW_FILL = 0xB48C8C8C;

  // Semi-transparent panel fill
  private static final int PANEL_FILL_TRANS = 0xBF6B6B6B;

  // Tab bar base constants (unscaled) — use the getScaledTab* methods for rendering/hit-testing
  public static final int INV_TAB_WIDTH = 84;
  public static final int INV_TAB_HEIGHT = 28;
  public static final int INV_TAB_GAP = 4;

  // Equipment slot layout constants (unscaled)
  private static final int EQUIP_SLOT_SIZE = 30;
  private static final int EQUIP_SLOT_COL_GAP = 8;
  private static final int EQUIP_ROW_STRIDE = 50; // slot + label clearance + gap

  // Vital bar row height (unscaled)
  private static final int VITAL_BAR_HEIGHT = 20;
  private static final int VITAL_BAR_GAP = 6;

  // Ability score bar colors (ARGB)
  private static final int[] ATTR_BAR_COLORS = {
    0xFFDC5050, // STR — red
    0xFF50C878, // DEX — green
    0xFFE6BE3C, // CON — amber
    0xFF5080DC, // INT — blue
    0xFFAA55CC, // WIS — purple
    0xFFE67E22  // CHA — orange
  };

  private static final String[] ATTR_ABBREV = {"STR", "DEX", "CON", "INT", "WIS", "CHA"};

  // Equipment slot labels aligned to EquipmentSlot ordinal order
  private static final String[] EQUIP_LABELS = {
    "Head", "Chest", "Legs", "Boots",
    "Neck", "Ring 1", "Ring 2", "Brace",
    "Trnk 1", "Trnk 2"
  };

  public static int getScaledTabWidth() {
    return Math.round(INV_TAB_WIDTH * com.stonebreak.config.Settings.getInstance().getUiScale());
  }

  public static int getScaledTabHeight() {
    return Math.round(INV_TAB_HEIGHT * com.stonebreak.config.Settings.getInstance().getUiScale());
  }

  public static int getScaledTabGap() {
    return Math.round(INV_TAB_GAP * com.stonebreak.config.Settings.getInstance().getUiScale());
  }

  public InventoryRenderCoordinator(UIRenderer uiRenderer,
                                    Renderer renderer,
                                    InputHandler inputHandler,
                                    Inventory inventory,
                                    InventoryController controller,
                                    InventoryInputManager inputManager,
                                    InventoryCraftingManager craftingManager,
                                    CharacterStats stats) {
    this.uiRenderer = uiRenderer;
    this.renderer = renderer;
    this.inputHandler = inputHandler;
    this.inventory = inventory;
    this.controller = controller;
    this.inputManager = inputManager;
    this.craftingManager = craftingManager;
    this.stats = stats;

    this.ui = new MasonryUI(renderer.getSkijaBackend());
    this.mHotbarRenderer = new MHotbarRenderer(uiRenderer, renderer);

    this.recipeButton = new MButton("Recipes");
    this.craftAllButton = new MButton("Craft All").fontSize(MStyle.FONT_META);

    this.tabInventory = new MButton("Inventory").fontSize(MStyle.FONT_META);
    this.tabCharacter = new MButton("Character").fontSize(MStyle.FONT_META);
    this.tabClasses = new MButton("Classes").fontSize(MStyle.FONT_META);
    this.tabSkills = new MButton("Skills").fontSize(MStyle.FONT_META);
    this.tabFeats = new MButton("Feats").fontSize(MStyle.FONT_META);
  }

  // ─── Public entry points ───────────────────────────────────────────────────

  public void render(int screenWidth, int screenHeight) {
    controller.setHoveredItemStack(null);

    InventoryLayoutCalculator.InventoryLayout3Col layout3 =
        InventoryLayoutCalculator.calculateThreeColumnLayout(screenWidth, screenHeight);
    InventoryLayoutCalculator.InventoryLayout center = layout3.center;

    Vector2f mouse = inputHandler.getMousePosition();
    float mx = mouse.x;
    float my = mouse.y;

    updateButtonPositions(center);
    recipeButton.updateHover(mx, my);
    craftAllButton.updateHover(mx, my);
    updateTabBounds(layout3);
    tabInventory.updateHover(mx, my);
    tabCharacter.updateHover(mx, my);
    tabClasses.updateHover(mx, my);
    tabSkills.updateHover(mx, my);
    tabFeats.updateHover(mx, my);

    // Phase A — Skija: all panel chrome + slot backgrounds + widgets
    if (ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
      Canvas canvas = ui.canvas();
      drawTabBar(canvas, layout3);
      drawFullPanel(canvas, layout3);
      drawLeftColumn(canvas, layout3, mx, my);
      drawCenterColumn(canvas, layout3, mx, my);
      drawRightColumn(canvas, layout3);
      ui.renderOverlays();
      ui.endFrame();
    }

    // Phase B — GL: item icons for center column only
    renderItemIcons(center);

    // Phase C — Skija: count texts on top of item icons
    if (ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
      Canvas canvas = ui.canvas();
      drawAllCountTexts(canvas, center);
      ui.endFrame();
    }
  }

  public void renderWithoutTooltips(int screenWidth, int screenHeight) {
    render(screenWidth, screenHeight);
  }

  public void renderTooltipsOnly(int screenWidth, int screenHeight) {
    ItemStack hovered = controller.getHoveredItemStack();
    if (hovered == null || hovered.isEmpty() || inputManager.getDragState().isDragging()) {
      return;
    }

    Item item = hovered.getItem();
    if (item == null || item == BlockType.AIR) {
      return;
    }

    Vector2f mouse = inputHandler.getMousePosition();
    if (ui.beginFrame(screenWidth, screenHeight, 1.0f)) {
      MTooltip.draw(ui, item.getName(), mouse.x + 15, mouse.y + 15, screenWidth, screenHeight);
      ui.endFrame();
    }
  }

  public void renderDraggedItemOnly(int screenWidth, int screenHeight) {
    InventoryDragDropHandler.DragState dragState = inputManager.getDragState();
    if (!dragState.isDragging()) {
      return;
    }

    ItemStack dragged = dragState.draggedItemStack;
    Item item = dragged.getItem();
    if (item == null || !item.hasIcon()) {
      return;
    }

    Vector2f mouse = inputHandler.getMousePosition();
    int iconSize = InventoryLayoutCalculator.getSlotSize() - 4;
    int iconX = (int) (mouse.x - iconSize / 2.0f);
    int iconY = (int) (mouse.y - iconSize / 2.0f);

    if (item instanceof BlockType bt) {
      uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt, iconX, iconY,
          iconSize, iconSize, renderer.getBlockTextureArray(), true);
    } else {
      uiRenderer.renderItemIcon(iconX, iconY, iconSize, iconSize, item,
          renderer.getBlockTextureArray());
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

  // ─── Hotbar ───────────────────────────────────────────────────────────────

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

  // ─── Phase A helpers — panel and tab bar ──────────────────────────────────

  private void drawFullPanel(Canvas canvas,
                             InventoryLayoutCalculator.InventoryLayout3Col layout3) {
    MPainter.stoneSurface(canvas,
        layout3.panelStartX, layout3.panelStartY,
        layout3.totalPanelWidth, layout3.totalPanelHeight,
        MStyle.PANEL_RADIUS,
        PANEL_FILL_TRANS, MStyle.PANEL_BORDER,
        MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, MStyle.PANEL_DROP_SHADOW,
        MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);
  }

  // ─── Phase A helpers — left column ────────────────────────────────────────

  private void drawLeftColumn(Canvas canvas,
                              InventoryLayoutCalculator.InventoryLayout3Col layout3,
                              float mx, float my) {
    float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
    float colX = layout3.leftColX;
    float colY = layout3.leftColY;
    float colW = layout3.leftColW;
    float padX = 12f * scale;
    float padY = 14f * scale;

    float cursorY = colY + padY;

    cursorY = drawSectionHeader(canvas, "Equipment", colX + padX, cursorY, scale);

    cursorY = drawEquipmentSlots(canvas, colX, cursorY, colW, padX, scale, mx, my);

    cursorY += 10f * scale;
    cursorY = drawSectionHeader(canvas, "Vitals", colX + padX, cursorY, scale);

    cursorY = drawVitalBars(canvas, colX + padX, cursorY, colW - padX * 2, scale);

    cursorY += 8f * scale;
    drawStatusEffectSlots(canvas, colX + padX, cursorY, colW - padX * 2, scale, mx, my);
  }

  /** Draws a small section header and returns the Y position after it. */
  private float drawSectionHeader(Canvas canvas, String title, float x, float y, float scale) {
    Font font = ui.fonts().get(MStyle.FONT_META);
    MPainter.drawStringWithShadow(canvas, title.toUpperCase(), x, y + MStyle.FONT_META * 0.85f,
        font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
    float ruleY = y + MStyle.FONT_META + 4f * scale;
    MPainter.fillRoundedRect(canvas, x, ruleY, 120f * scale, 1f, 0f, 0x33FFFFFF);
    return ruleY + 8f * scale;
  }

  private float drawEquipmentSlots(Canvas canvas, float colX, float startY, float colW,
                                   float padX, float scale, float mx, float my) {
    int slotPx = Math.round(EQUIP_SLOT_SIZE * scale);
    int colGapPx = Math.round(EQUIP_SLOT_COL_GAP * scale);
    int rowStridePx = Math.round(EQUIP_ROW_STRIDE * scale);

    float gridW = 2 * slotPx + colGapPx;
    float gridOffsetX = colX + (colW - gridW) / 2f;

    for (int i = 0; i < Inventory.EquipmentSlot.COUNT; i++) {
      int col = i % 2;
      int row = i / 2;
      float sx = gridOffsetX + col * (slotPx + colGapPx);
      float sy = startY + row * rowStridePx;

      MEquipSlot slot = new MEquipSlot()
          .slotLabel(EQUIP_LABELS[i])
          .bounds(sx, sy, slotPx, slotPx);
      slot.updateHover(mx, my);
      slot.render(ui);
    }

    int rows = (Inventory.EquipmentSlot.COUNT + 1) / 2;
    return startY + rows * rowStridePx + 2f * scale;
  }

  private float drawVitalBars(Canvas canvas, float x, float startY, float barW, float scale) {
    int barH = Math.round(VITAL_BAR_HEIGHT * scale);
    int gap = Math.round(VITAL_BAR_GAP * scale);

    float[][] vitals = {
      {stats.getHealth(), stats.getMaxHealth()},
      {stats.getMana(), stats.getMaxMana()},
      {stats.getStamina(), stats.getMaxStamina()}
    };
    String[] labels = {"HP", "MP", "SP"};
    int[] colors = {0xFFCC2222, 0xFF3366CC, 0xFF33AA55};

    float cursorY = startY;
    for (int i = 0; i < 3; i++) {
      new MVitalBar()
          .label(labels[i])
          .value(vitals[i][0])
          .max(vitals[i][1])
          .fillColor(colors[i])
          .bounds(x, cursorY, barW, barH)
          .render(ui);
      cursorY += barH + gap;
    }
    return cursorY;
  }

  private void drawStatusEffectSlots(Canvas canvas, float x, float y, float availW,
                                     float scale, float mx, float my) {
    Font labelFont = ui.fonts().get(MStyle.FONT_META);
    MPainter.drawStringWithShadow(canvas, "STATUS", x, y + MStyle.FONT_META * 0.85f,
        labelFont, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

    int slotPx = Math.round(28 * scale);
    int slotGap = Math.round(4 * scale);
    Font qFont = ui.fonts().get(MStyle.FONT_META);
    float slotY = y + MStyle.FONT_META + 6f * scale;

    for (int i = 0; i < 6; i++) {
      float sx = x + i * (slotPx + slotGap);
      MItemSlot slot = new MItemSlot().bounds(sx, slotY, slotPx, slotPx);
      slot.updateHover(mx, my);
      slot.render(ui);

      float qX = sx + slotPx / 2f;
      float qY = slotY + slotPx / 2f + MStyle.FONT_META * 0.35f;
      MPainter.drawCenteredStringWithShadow(canvas, "?", qX, qY, qFont,
          MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
    }
  }

  // ─── Phase A helpers — center column ──────────────────────────────────────

  private void drawCenterColumn(Canvas canvas,
                                InventoryLayoutCalculator.InventoryLayout3Col layout3,
                                float mx, float my) {
    InventoryLayoutCalculator.InventoryLayout center = layout3.center;
    float scale = com.stonebreak.config.Settings.getInstance().getUiScale();

    drawTitles(canvas, center, scale);
    drawCraftingSection(canvas, center, mx, my);
    drawInventorySection(center, mx, my);
  }

  private void drawTitles(Canvas canvas, InventoryLayoutCalculator.InventoryLayout center,
                          float scale) {
    Font font = ui.fonts().get(MStyle.FONT_BUTTON);
    float centerX = center.panelStartX + center.inventoryPanelWidth / 2f;

    float craftY = center.panelStartY + 20 * scale + MStyle.FONT_BUTTON / 3f;
    MPainter.drawCenteredStringWithShadow(canvas, "Crafting", centerX, craftY,
        font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

    float invY = center.mainInvContentStartY - 20 * scale + MStyle.FONT_BUTTON / 3f;
    MPainter.drawCenteredStringWithShadow(canvas, "Inventory", centerX, invY,
        font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
  }

  private void drawCraftingSection(Canvas canvas,
                                   InventoryLayoutCalculator.InventoryLayout layout,
                                   float mouseX, float mouseY) {
    int slotSize = InventoryLayoutCalculator.getSlotSize();
    int slotPadding = InventoryLayoutCalculator.getSlotPadding();
    int gridSize = InventoryLayoutCalculator.getCraftingGridSize();
    int inputCount = InventoryLayoutCalculator.getCraftingInputSlotsCount();
    ItemStack[] craftingInput = craftingManager.getCraftingInputSlots();

    for (int i = 0; i < inputCount; i++) {
      int row = i / gridSize;
      int col = i % gridSize;
      float sx = layout.craftingElementsStartX + col * (slotSize + slotPadding);
      float sy = layout.craftingGridStartY + row * (slotSize + slotPadding);
      drawSlot(sx, sy, slotSize, mouseX, mouseY, false);
      checkHover(craftingInput[i], sx, sy, slotSize, mouseX, mouseY);
    }

    int arrowSize = Math.round(20 * com.stonebreak.config.Settings.getInstance().getUiScale());
    float arrowX = layout.craftingElementsStartX + layout.craftInputGridVisualWidth
        + slotPadding + (slotSize - arrowSize) / 2f;
    float arrowY = layout.craftingGridStartY + (slotSize - arrowSize) / 2f;
    MPainter.craftingArrow(canvas, arrowX, arrowY, arrowSize, arrowSize, ARROW_FILL);

    float ox = layout.outputSlotX;
    float oy = layout.outputSlotY;
    drawSlot(ox, oy, slotSize, mouseX, mouseY, false);
    checkHover(craftingManager.getCraftingOutputSlot(), ox, oy, slotSize, mouseX, mouseY);

    recipeButton.render(ui);

    boolean hasCraftOutput = craftingManager.getCraftingOutputSlot() != null
        && !craftingManager.getCraftingOutputSlot().isEmpty();
    if (hasCraftOutput) {
      craftAllButton.render(ui);
    }
  }

  private void drawInventorySection(InventoryLayoutCalculator.InventoryLayout layout,
                                    float mouseX, float mouseY) {
    int slotSize = InventoryLayoutCalculator.getSlotSize();
    int slotPadding = InventoryLayoutCalculator.getSlotPadding();
    ItemStack[] mainSlots = inventory.getMainInventorySlots();
    ItemStack[] hotbarSlots = inventory.getHotbarSlots();
    int selectedHotbar = inventory.getSelectedHotbarSlotIndex();

    for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
      int row = i / Inventory.MAIN_INVENTORY_COLS;
      int col = i % Inventory.MAIN_INVENTORY_COLS;
      float sx = layout.inventorySectionStartX + slotPadding + col * (slotSize + slotPadding);
      float sy = layout.mainInvContentStartY + slotPadding + row * (slotSize + slotPadding);
      drawSlot(sx, sy, slotSize, mouseX, mouseY, false);
      checkHover(mainSlots[i], sx, sy, slotSize, mouseX, mouseY);
    }

    for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
      float sx = layout.inventorySectionStartX + slotPadding + i * (slotSize + slotPadding);
      float sy = layout.hotbarRowY;
      drawSlot(sx, sy, slotSize, mouseX, mouseY, i == selectedHotbar);
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

  // ─── Phase A helpers — right column ───────────────────────────────────────

  private void drawRightColumn(Canvas canvas,
                               InventoryLayoutCalculator.InventoryLayout3Col layout3) {
    float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
    float colX = layout3.rightColX;
    float colY = layout3.rightColY;
    float colW = layout3.rightColW;
    float padX = 12f * scale;
    float padY = 14f * scale;
    float rowW = colW - padX * 2;
    float rowH = Math.round(18 * scale);

    float cursorY = colY + padY;

    cursorY = drawSectionHeader(canvas, "Core Attributes", colX + padX, cursorY, scale);

    int[] scores = {
      stats.getStrength(), stats.getDexterity(), stats.getConstitution(),
      stats.getIntelligence(), stats.getWisdom(), stats.getCharisma()
    };
    for (int i = 0; i < 6; i++) {
      int mod = stats.getModifier(scores[i]);
      String modStr = (mod >= 0 ? "+" : "") + mod;
      String valStr = scores[i] + " (" + modStr + ")";
      new MStatRow()
          .label(ATTR_ABBREV[i])
          .value(valStr)
          .bar(ATTR_BAR_COLORS[i], scores[i] / 30f)
          .bounds(colX + padX, cursorY, rowW, rowH)
          .render(ui);
      cursorY += rowH + 2f * scale;
    }

    cursorY += 6f * scale;
    cursorY = drawSectionHeader(canvas, "Resistances", colX + padX, cursorY, scale);

    String[] resistLabels = {"Fire", "Cold", "Lightning", "Physical", "Poison"};
    for (String label : resistLabels) {
      new MStatRow()
          .label(label)
          .value("—") // em dash placeholder
          .bounds(colX + padX, cursorY, rowW, rowH)
          .render(ui);
      cursorY += rowH + 2f * scale;
    }

    cursorY += 6f * scale;
    cursorY = drawSectionHeader(canvas, "Offensive", colX + padX, cursorY, scale);
    cursorY = drawStubRow(canvas, "Attack", colX + padX, cursorY, rowW, rowH, scale);
    cursorY = drawStubRow(canvas, "Crit", colX + padX, cursorY, rowW, rowH, scale);

    cursorY += 6f * scale;
    cursorY = drawSectionHeader(canvas, "Defensive", colX + padX, cursorY, scale);
    cursorY = drawStubRow(canvas, "Armor", colX + padX, cursorY, rowW, rowH, scale);
    drawStubRow(canvas, "Block", colX + padX, cursorY, rowW, rowH, scale);
  }

  private float drawStubRow(Canvas canvas, String label, float x, float y, float w, float h,
                            float scale) {
    new MStatRow()
        .label(label)
        .value("—")
        .bounds(x, y, w, h)
        .render(ui);
    return y + h + 2f * scale;
  }

  // ─── Phase B — GL item icons ───────────────────────────────────────────────

  private void renderItemIcons(InventoryLayoutCalculator.InventoryLayout center) {
    int slotSize = InventoryLayoutCalculator.getSlotSize();
    int slotPadding = InventoryLayoutCalculator.getSlotPadding();
    int iconInset = 3;
    int iconSize = slotSize - iconInset * 2;
    int gridSize = InventoryLayoutCalculator.getCraftingGridSize();
    int inputCount = InventoryLayoutCalculator.getCraftingInputSlotsCount();
    ItemStack[] craftingInput = craftingManager.getCraftingInputSlots();

    for (int i = 0; i < inputCount; i++) {
      int row = i / gridSize;
      int col = i % gridSize;
      int sx = center.craftingElementsStartX + col * (slotSize + slotPadding);
      int sy = center.craftingGridStartY + row * (slotSize + slotPadding);
      drawItemIcon(craftingInput[i], sx + iconInset, sy + iconInset, iconSize);
    }

    drawItemIcon(craftingManager.getCraftingOutputSlot(),
        center.outputSlotX + iconInset, center.outputSlotY + iconInset, iconSize);

    ItemStack[] mainSlots = inventory.getMainInventorySlots();
    for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
      int row = i / Inventory.MAIN_INVENTORY_COLS;
      int col = i % Inventory.MAIN_INVENTORY_COLS;
      int sx = center.inventorySectionStartX + slotPadding + col * (slotSize + slotPadding);
      int sy = center.mainInvContentStartY + slotPadding + row * (slotSize + slotPadding);
      drawItemIcon(mainSlots[i], sx + iconInset, sy + iconInset, iconSize);
    }

    ItemStack[] hotbarSlots = inventory.getHotbarSlots();
    for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
      int sx = center.inventorySectionStartX + slotPadding + i * (slotSize + slotPadding);
      int sy = center.hotbarRowY;
      drawItemIcon(hotbarSlots[i], sx + iconInset, sy + iconInset, iconSize);
    }
  }

  private void drawItemIcon(ItemStack itemStack, int x, int y, int size) {
    if (itemStack == null || itemStack.isEmpty()) {
      return;
    }
    Item item = itemStack.getItem();
    if (item == null || !item.hasIcon()) {
      return;
    }

    if (item instanceof BlockType bt) {
      uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt, x, y, size, size,
          renderer.getBlockTextureArray());
    } else {
      uiRenderer.renderItemIcon(x, y, size, size, item, renderer.getBlockTextureArray());
    }
  }

  // ─── Phase C — count texts ────────────────────────────────────────────────

  private void drawAllCountTexts(Canvas canvas,
                                 InventoryLayoutCalculator.InventoryLayout center) {
    Font font = ui.fonts().get(MStyle.FONT_META);
    int slotSize = InventoryLayoutCalculator.getSlotSize();
    int slotPadding = InventoryLayoutCalculator.getSlotPadding();
    int gridSize = InventoryLayoutCalculator.getCraftingGridSize();
    int inputCount = InventoryLayoutCalculator.getCraftingInputSlotsCount();
    ItemStack[] craftingInput = craftingManager.getCraftingInputSlots();

    for (int i = 0; i < inputCount; i++) {
      int row = i / gridSize;
      int col = i % gridSize;
      float sx = center.craftingElementsStartX + col * (slotSize + slotPadding);
      float sy = center.craftingGridStartY + row * (slotSize + slotPadding);
      drawCountText(canvas, font, craftingInput[i], sx, sy, slotSize);
    }

    drawCountText(canvas, font, craftingManager.getCraftingOutputSlot(),
        center.outputSlotX, center.outputSlotY, slotSize);

    ItemStack[] mainSlots = inventory.getMainInventorySlots();
    for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
      int row = i / Inventory.MAIN_INVENTORY_COLS;
      int col = i % Inventory.MAIN_INVENTORY_COLS;
      float sx = center.inventorySectionStartX + slotPadding + col * (slotSize + slotPadding);
      float sy = center.mainInvContentStartY + slotPadding + row * (slotSize + slotPadding);
      drawCountText(canvas, font, mainSlots[i], sx, sy, slotSize);
    }

    ItemStack[] hotbarSlots = inventory.getHotbarSlots();
    for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
      float sx = center.inventorySectionStartX + slotPadding + i * (slotSize + slotPadding);
      float sy = center.hotbarRowY;
      drawCountText(canvas, font, hotbarSlots[i], sx, sy, slotSize);
    }
  }

  private void drawCountText(Canvas canvas, Font font, ItemStack itemStack,
                             float slotX, float slotY, int slotSize) {
    if (itemStack == null || itemStack.isEmpty() || itemStack.getCount() <= 1) {
      return;
    }
    String countStr = String.valueOf(itemStack.getCount());
    float textX = slotX + slotSize - MPainter.measureWidth(font, countStr) - 2f;
    float textY = slotY + slotSize - 2f;
    MPainter.drawStringWithShadow(canvas, countStr, textX, textY,
        font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
  }

  // ─── Tab bar ──────────────────────────────────────────────────────────────

  private void updateTabBounds(InventoryLayoutCalculator.InventoryLayout3Col layout3) {
    int tw = getScaledTabWidth();
    int th = getScaledTabHeight();
    int tg = getScaledTabGap();
    float tabY = layout3.center.panelStartY - th;
    float stride = tw + tg;
    float startX = layout3.center.panelStartX;
    tabInventory.bounds(startX, tabY, tw, th);
    tabCharacter.bounds(startX + stride, tabY, tw, th);
    tabClasses.bounds(startX + stride * 2, tabY, tw, th);
    tabSkills.bounds(startX + stride * 3, tabY, tw, th);
    tabFeats.bounds(startX + stride * 4, tabY, tw, th);
  }

  private void drawTabBar(Canvas canvas,
                          InventoryLayoutCalculator.InventoryLayout3Col layout3) {
    int tw = getScaledTabWidth();
    int th = getScaledTabHeight();
    int tg = getScaledTabGap();
    float tabY = layout3.center.panelStartY - th;
    float stride = tw + tg;
    float startX = layout3.center.panelStartX;
    drawTab(canvas, startX, tabY, "Inventory", true, tabInventory.isHovered(), tw, th);
    drawTab(canvas, startX + stride, tabY, "Character", false, tabCharacter.isHovered(), tw, th);
    drawTab(canvas, startX + stride * 2, tabY, "Classes", false, tabClasses.isHovered(), tw, th);
    drawTab(canvas, startX + stride * 3, tabY, "Skills", false, tabSkills.isHovered(), tw, th);
    drawTab(canvas, startX + stride * 4, tabY, "Feats", false, tabFeats.isHovered(), tw, th);
  }

  private void drawTab(Canvas canvas, float x, float y, String label, boolean active,
                       boolean hovered, int tabW, int tabH) {
    int fill = active ? 0xFF7A7A7A : hovered ? MStyle.BUTTON_FILL_HI : MStyle.BUTTON_FILL;
    MPainter.stoneSurface(canvas, x, y, tabW, tabH, MStyle.BUTTON_RADIUS,
        fill, MStyle.BUTTON_BORDER,
        MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
        MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
    Font font = ui.fonts().get(MStyle.FONT_META);
    int color = active ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
    float ty = y + tabH * 0.5f + MStyle.FONT_META * 0.38f;
    MPainter.drawCenteredStringWithShadow(canvas, label, x + tabW / 2f, ty,
        font, color, MStyle.TEXT_SHADOW);
  }

  // ─── Utilities ────────────────────────────────────────────────────────────

  private void updateButtonPositions(InventoryLayoutCalculator.InventoryLayout center) {
    inputManager.updateRecipeButtonBoundsForRendering(center);
    recipeButton.bounds(inputManager.getRecipeButtonX(), inputManager.getRecipeButtonY(),
        inputManager.getRecipeButtonWidth(), inputManager.getRecipeButtonHeight());

    inputManager.updateCraftAllButtonBoundsForRendering(center);
    craftAllButton.bounds(inputManager.getCraftAllButtonX(), inputManager.getCraftAllButtonY(),
        inputManager.getCraftAllButtonWidth(), inputManager.getCraftAllButtonHeight());
  }

  private void checkHover(ItemStack itemStack, float sx, float sy, int slotSize,
                          float mouseX, float mouseY) {
    if (itemStack == null || itemStack.isEmpty()) {
      return;
    }
    if (mouseX >= sx && mouseX <= sx + slotSize && mouseY >= sy && mouseY <= sy + slotSize) {
      controller.setHoveredItemStack(itemStack);
    }
  }
}
