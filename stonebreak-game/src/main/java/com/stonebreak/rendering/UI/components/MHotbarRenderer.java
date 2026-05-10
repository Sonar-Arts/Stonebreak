package com.stonebreak.rendering.UI.components;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.UI.masonryUI.MItemSlot;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rendering.UI.masonryUI.textures.MTexture;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import com.stonebreak.ui.HotbarScreen;
import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * MasonryUI/Skija-based hotbar renderer.
 *
 * Replaces the NanoVG {@code HotbarRenderer} using the same 3-phase pattern
 * as {@code InventoryRenderCoordinator}:
 *   A) Skija  – background panel, slot backgrounds, health hearts
 *   B) GL     – item icons drawn directly into the framebuffer
 *   C) Skija  – item count text
 *   D) Skija  – tooltip (separate call, layered after block drops)
 */
public class MHotbarRenderer {

    private final UIRenderer uiRenderer;
    private final Renderer renderer;
    private final MasonryUI ui;

    // ── Background panel (matches HotbarTheme.Background values) ─────────────
    private static final int   BG_FILL      = 0xC8282828; // RGBA(40,40,40,200)
    private static final int   BG_BORDER    = 0xFF505050; // RGBA(80,80,80,255)
    private static final int   BG_HIGHLIGHT = 0x22FFFFFF; // subtle top bevel
    private static final int   BG_SHADOW    = 0x44000000; // subtle bottom bevel
    private static final int   BG_DROP      = 0x78000000; // drop shadow alpha=120
    private static final float BG_RADIUS    = 8f;

    // ── Health hearts ─────────────────────────────────────────────────────────
    /** Target heart edge length, in pixels. Snapped at draw time to the
     *  nearest integer multiple of the source texture's native size to avoid
     *  nearest-neighbour sampling asymmetry. */
    private static final int   HEART_SIZE_TARGET         = 28;
    private static final int   HEART_SPACING             = 2;
    private static final int   HEART_Y_GAP               = 38; // pixels above hotbar background
    private static final int   HEART_ROW_GAP             = 4;
    private static final float HEART_MIN_VISIBLE_FRACTION = 0.40f;

    // ── Stamina bar ───────────────────────────────────────────────────────────
    private static final int STAMINA_BAR_HEIGHT = 8;
    private static final int STAMINA_BAR_GAP    = 6;    // pixels above top heart row
    private static final int STAMINA_BG         = 0xC83C3C3C;
    private static final int STAMINA_FILL       = 0xDC50C850;
    private static final int STAMINA_BORDER     = 0xFF000000;

    private record HeartLayout(int heartsPerRow, int numRows, float step) {}

    private static final String HEART_EMPTY_SBT = "/ui/HUD/Health Icon/SB_Empty_Health_Icon.sbt";
    private static final String HEART_HALF_SBT  = "/ui/HUD/Health Icon/SB_Half_Health_Icon.sbt";
    private static final String HEART_FULL_SBT  = "/ui/HUD/Health Icon/SB_Full_Health_Icon.sbt";

    public MHotbarRenderer(UIRenderer uiRenderer, Renderer renderer) {
        this.uiRenderer = uiRenderer;
        this.renderer   = renderer;
        this.ui         = new MasonryUI(renderer.getSkijaBackend());
    }

    // ─────────────────────────────────────────────── Public API

    /**
     * Renders the complete hotbar (background, slots, items, hearts, counts).
     * Tooltip is NOT rendered here — call {@link #renderHotbarTooltip} separately.
     */
    public void renderHotbar(HotbarScreen hotbarScreen, int sw, int sh) {
        if (hotbarScreen == null) return;

        HotbarLayoutCalculator.HotbarLayout layout =
                hotbarScreen.calculateLayout(sw, sh);
        ItemStack[] slots    = hotbarScreen.getHotbarSlots();
        int         selected = hotbarScreen.getSelectedSlotIndex();

        // ── Phase A: Skija ────────────────────────────────────────────────
        if (ui.beginFrame(sw, sh, 1.0f)) {
            Canvas canvas = ui.canvas();
            drawBackground(canvas, layout);
            drawSlots(layout, selected);
            drawSbtItemIcons(canvas, slots, layout);
            drawHealthHearts(canvas, layout);
            drawStaminaBar(canvas, layout);
            ui.renderOverlays();
            ui.endFrame();
        }

        // ── Phase B: GL item icons ────────────────────────────────────────
        renderItemIcons(slots, layout, hotbarScreen);

        // ── Phase C: Skija count texts ────────────────────────────────────
        if (ui.beginFrame(sw, sh, 1.0f)) {
            drawCountTexts(ui.canvas(), slots, layout, hotbarScreen);
            ui.endFrame();
        }
    }

    /**
     * Renders the tooltip for the selected hotbar slot.
     * Call this after block drops so the tooltip layers on top.
     */
    public void renderHotbarTooltip(HotbarScreen hotbarScreen, int sw, int sh) {
        if (hotbarScreen == null || !hotbarScreen.shouldShowTooltip()) return;
        String text  = hotbarScreen.getTooltipText();
        float  alpha = hotbarScreen.getTooltipAlpha();
        if (text == null || alpha <= 0f) return;

        HotbarLayoutCalculator.HotbarLayout layout =
                hotbarScreen.calculateLayout(sw, sh);
        int selectedIndex = hotbarScreen.getSelectedSlotIndex();

        if (ui.beginFrame(sw, sh, 1.0f)) {
            drawTooltip(ui.canvas(), text, alpha, selectedIndex, layout, sw, sh);
            ui.endFrame();
        }
    }

    // ─────────────────────────────────────────────── Phase A helpers

    private void drawBackground(Canvas canvas,
                                HotbarLayoutCalculator.HotbarLayout layout) {
        MPainter.stoneSurface(canvas,
                layout.backgroundX, layout.backgroundY,
                layout.backgroundWidth, layout.backgroundHeight,
                BG_RADIUS,
                BG_FILL, BG_BORDER,
                BG_HIGHLIGHT, BG_SHADOW, BG_DROP,
                MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);
    }

    private void drawSlots(HotbarLayoutCalculator.HotbarLayout layout, int selectedIndex) {
        for (int i = 0; i < layout.slotCount; i++) {
            HotbarLayoutCalculator.SlotPosition pos =
                    HotbarLayoutCalculator.calculateSlotPosition(i, layout);
            new MItemSlot()
                    .hotbarSelected(i == selectedIndex)
                    .bounds(pos.x, pos.y, pos.width, pos.height)
                    .render(ui);
        }
    }

    private void drawHealthHearts(Canvas canvas,
                                  HotbarLayoutCalculator.HotbarLayout layout) {
        Player player = Game.getInstance().getPlayer();
        if (player == null) return;

        float health      = player.getHealth();
        float maxHealth   = player.getMaxHealth();
        int   totalHearts = (int) Math.ceil(maxHealth / 2.0f);
        float filled      = health / 2.0f;

        MTexture empty = MTextureRegistry.get(HEART_EMPTY_SBT);
        MTexture half  = MTextureRegistry.get(HEART_HALF_SBT);
        MTexture full  = MTextureRegistry.get(HEART_FULL_SBT);

        int nativeSize = nativeHeartSize(empty, half, full);
        int scale      = Math.max(1, Math.round((float) HEART_SIZE_TARGET / nativeSize));
        int heartSize  = nativeSize * scale;

        HeartLayout hl = computeHeartLayout(totalHearts, heartSize, layout.backgroundWidth);
        if (hl.heartsPerRow() == 0) return;

        for (int i = 0; i < totalHearts; i++) {
            int   row  = i / hl.heartsPerRow();
            int   col  = i % hl.heartsPerRow();
            float x    = layout.backgroundX + col * hl.step();
            float y    = layout.backgroundY - HEART_Y_GAP - row * (heartSize + HEART_ROW_GAP);
            float fill = Math.max(0f, Math.min(1f, filled - i));

            MTexture sprite = fill >= 0.75f ? full : fill >= 0.25f ? half : empty;
            if (sprite != null) {
                MPainter.drawImage(canvas, sprite.image(), x, y, heartSize, heartSize);
            }
        }
    }

    private void drawStaminaBar(Canvas canvas,
                                HotbarLayoutCalculator.HotbarLayout layout) {
        Player player = Game.getInstance().getPlayer();
        if (player == null) return;
        float maxStamina = player.getMaxStamina();
        if (maxStamina <= 0) return;

        int totalHearts = (int) Math.ceil(player.getMaxHealth() / 2.0f);
        MTexture empty = MTextureRegistry.get(HEART_EMPTY_SBT);
        MTexture half  = MTextureRegistry.get(HEART_HALF_SBT);
        MTexture full  = MTextureRegistry.get(HEART_FULL_SBT);
        int nativeSize = nativeHeartSize(empty, half, full);
        int heartSize  = nativeSize * Math.max(1, Math.round((float) HEART_SIZE_TARGET / nativeSize));

        HeartLayout hl     = computeHeartLayout(totalHearts, heartSize, layout.backgroundWidth);
        int         rows   = Math.max(1, hl.numRows());
        float       topRowY = layout.backgroundY - HEART_Y_GAP - (rows - 1) * (heartSize + HEART_ROW_GAP);
        float       barY    = topRowY - STAMINA_BAR_GAP - STAMINA_BAR_HEIGHT;
        float       fillW   = layout.backgroundWidth
                * Math.max(0f, Math.min(1f, player.getStamina() / maxStamina));

        MPainter.fillRect(canvas, layout.backgroundX, barY, layout.backgroundWidth, STAMINA_BAR_HEIGHT, STAMINA_BG);
        if (fillW > 0) {
            MPainter.fillRect(canvas, layout.backgroundX, barY, fillW, STAMINA_BAR_HEIGHT, STAMINA_FILL);
        }
        MPainter.strokeRect(canvas, layout.backgroundX, barY, layout.backgroundWidth, STAMINA_BAR_HEIGHT, STAMINA_BORDER, 1f);
    }

    private HeartLayout computeHeartLayout(int totalHearts, int heartSize, int availableWidth) {
        if (totalHearts <= 0) return new HeartLayout(0, 0, 0f);

        float naturalStep   = heartSize + HEART_SPACING;
        int   naturalPerRow = Math.max(1, (int) Math.floor((availableWidth + HEART_SPACING) / naturalStep));

        if (totalHearts <= naturalPerRow) {
            return new HeartLayout(totalHearts, 1, naturalStep);
        }

        float minStep   = heartSize * HEART_MIN_VISIBLE_FRACTION;
        int   maxPerRow = availableWidth <= heartSize ? 1
                : (int) Math.floor((availableWidth - heartSize) / minStep) + 1;

        int numRows      = (int) Math.ceil((float) totalHearts / Math.max(1, maxPerRow));
        int heartsPerRow = (int) Math.ceil((float) totalHearts / numRows);
        float step       = heartsPerRow <= 1 ? 0f
                : (float) (availableWidth - heartSize) / (heartsPerRow - 1);

        return new HeartLayout(heartsPerRow, numRows, Math.min(naturalStep, step));
    }

    private void drawSbtItemIcons(Canvas canvas, ItemStack[] slots,
                                  HotbarLayoutCalculator.HotbarLayout layout) {
        int iconSize    = HotbarLayoutCalculator.calculateIconSize();
        int iconPadding = HotbarLayoutCalculator.calculateIconPadding();

        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = slots[i];
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (!(item instanceof ItemType itemType)) continue;

            if (!SpriteVoxelizer.isSboBackedItem(itemType)) continue;

            MTexture tex = MTextureRegistry.getForSboItem(itemType, stack.getState());
            if (tex == null) continue;

            HotbarLayoutCalculator.SlotPosition pos =
                    HotbarLayoutCalculator.calculateSlotPosition(i, layout);
            MPainter.drawImage(canvas, tex.image(),
                    pos.x + iconPadding, pos.y + iconPadding, iconSize, iconSize);
        }
    }

    /** Largest native edge across the loaded heart variants, or the target
     *  size as a fallback when no SBT loaded. */
    private static int nativeHeartSize(MTexture... variants) {
        int max = 0;
        for (MTexture t : variants) {
            if (t == null) continue;
            int side = Math.max(t.width(), t.height());
            if (side > max) max = side;
        }
        return max > 0 ? max : HEART_SIZE_TARGET;
    }

    // ─────────────────────────────────────────────── Phase B helpers

    private void renderItemIcons(ItemStack[] slots,
                                 HotbarLayoutCalculator.HotbarLayout layout,
                                 HotbarScreen hotbarScreen) {
        int iconSize    = HotbarLayoutCalculator.calculateIconSize();
        int iconPadding = HotbarLayoutCalculator.calculateIconPadding();

        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = slots[i];
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item == null || item.getAtlasX() == -1 || item.getAtlasY() == -1) continue;

            HotbarLayoutCalculator.SlotPosition pos =
                    HotbarLayoutCalculator.calculateSlotPosition(i, layout);
            int iconX = pos.x + iconPadding;
            int iconY = pos.y + iconPadding;

            if (item instanceof BlockType bt) {
                uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt,
                        iconX, iconY, iconSize, iconSize, renderer.getTextureAtlas());
            } else if (!(item instanceof ItemType it && SpriteVoxelizer.isSboBackedItem(it))) {
                uiRenderer.renderItemIcon(iconX, iconY, iconSize, iconSize,
                        item, renderer.getTextureAtlas());
            }
        }
    }

    // ─────────────────────────────────────────────── Phase C helpers

    private void drawCountTexts(Canvas canvas, ItemStack[] slots,
                                HotbarLayoutCalculator.HotbarLayout layout,
                                HotbarScreen hotbarScreen) {
        Font font = ui.fonts().get(MStyle.FONT_META);
        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = slots[i];
            if (stack == null || stack.isEmpty() || stack.getCount() <= 1) continue;
            HotbarLayoutCalculator.SlotPosition pos =
                    HotbarLayoutCalculator.calculateSlotPosition(i, layout);
            String countStr = String.valueOf(stack.getCount());
            float  textX    = pos.x + pos.width  - MPainter.measureWidth(font, countStr) - 2f;
            float  textY    = pos.y + pos.height - 2f;
            MPainter.drawStringWithShadow(canvas, countStr, textX, textY,
                    font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        }
    }

    // ─────────────────────────────────────────────── Tooltip

    private void drawTooltip(Canvas canvas, String text, float alpha,
                             int selectedIndex,
                             HotbarLayoutCalculator.HotbarLayout layout,
                             int sw, int sh) {
        Font  font  = ui.fonts().get(MStyle.FONT_ITEM);
        float textW = MPainter.measureWidth(font, text);
        float pad   = 8f;
        float boxW  = textW + pad * 2.5f;
        float boxH  = MStyle.FONT_ITEM + pad * 2f;

        // Centre tooltip above the selected slot
        HotbarLayoutCalculator.SlotPosition slotPos =
                HotbarLayoutCalculator.calculateSlotPosition(selectedIndex, layout);
        float bx = slotPos.centerX - boxW / 2f;
        float by = layout.backgroundY - boxH - 8f;

        // Keep within screen
        float margin = 8f;
        bx = Math.max(margin, Math.min(bx, sw - boxW - margin));
        by = Math.max(margin, Math.min(by, sh - boxH - margin));

        MPainter.stoneSurface(canvas, bx, by, boxW, boxH, MStyle.PANEL_RADIUS,
                a(MStyle.PANEL_FILL_DEEP, alpha), a(MStyle.PANEL_BORDER, alpha),
                a(MStyle.PANEL_HIGHLIGHT, alpha), a(MStyle.PANEL_SHADOW, alpha),
                a(MStyle.PANEL_DROP_SHADOW, alpha),
                a(MStyle.PANEL_NOISE_DARK, alpha), a(MStyle.PANEL_NOISE_LIGHT, alpha));

        float textBaseline = by + boxH / 2f + MStyle.FONT_ITEM * 0.35f;
        MPainter.drawCenteredStringWithShadow(canvas, text, bx + boxW / 2f, textBaseline,
                font, a(MStyle.TEXT_PRIMARY, alpha), a(MStyle.TEXT_SHADOW, alpha));
    }

    /** Multiply the alpha channel of an ARGB colour by {@code factor} (0–1). */
    private static int a(int argb, float factor) {
        int   newA = Math.min(255, (int)(((argb >>> 24) & 0xFF) * factor));
        return (newA << 24) | (argb & 0x00FFFFFF);
    }
}
