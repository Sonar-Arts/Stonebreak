package com.stonebreak.rendering.UI.components.hotbar;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.UI.masonryUI.MItemSlot;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rendering.UI.masonryUI.textures.MTexture;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Hotbar background panel, the slot frames with the selection highlight, and the slot
 * contents: SBT-backed item icons (Skija), block/sprite icons (GL), and stack count text.
 */
public final class HotbarSlotRenderer {

    // ── Background panel (matches HotbarTheme.Background values) ─────────────
    private static final int   BG_FILL      = 0xC8282828; // RGBA(40,40,40,200)
    private static final int   BG_BORDER    = 0xFF505050; // RGBA(80,80,80,255)
    private static final int   BG_HIGHLIGHT = 0x22FFFFFF; // subtle top bevel
    private static final int   BG_SHADOW    = 0x44000000; // subtle bottom bevel
    private static final int   BG_DROP      = 0x78000000; // drop shadow alpha=120
    private static final float BG_RADIUS    = 8f;

    private final UIRenderer uiRenderer;
    private final Renderer renderer;
    private final MasonryUI ui;

    public HotbarSlotRenderer(UIRenderer uiRenderer, Renderer renderer, MasonryUI ui) {
        this.uiRenderer = uiRenderer;
        this.renderer   = renderer;
        this.ui         = ui;
    }

    public void drawBackground(Canvas canvas, HotbarLayoutCalculator.HotbarLayout layout) {
        MPainter.stoneSurface(canvas,
                layout.backgroundX, layout.backgroundY,
                layout.backgroundWidth, layout.backgroundHeight,
                BG_RADIUS,
                BG_FILL, BG_BORDER,
                BG_HIGHLIGHT, BG_SHADOW, BG_DROP,
                MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);
    }

    public void drawSlots(HotbarLayoutCalculator.HotbarLayout layout, int selectedIndex) {
        for (int i = 0; i < layout.slotCount; i++) {
            HotbarLayoutCalculator.SlotPosition pos =
                    HotbarLayoutCalculator.calculateSlotPosition(i, layout);
            new MItemSlot()
                    .hotbarSelected(i == selectedIndex)
                    .bounds(pos.x, pos.y, pos.width, pos.height)
                    .render(ui);
        }
    }

    /** Skija pass: icons for SBO-backed items, drawn from the SBT texture registry. */
    public void drawSbtItemIcons(Canvas canvas, ItemStack[] slots,
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

    /** GL pass: 3D block icons and sprite item icons drawn directly into the framebuffer. */
    public void renderItemIcons(ItemStack[] slots, HotbarLayoutCalculator.HotbarLayout layout) {
        int iconSize    = HotbarLayoutCalculator.calculateIconSize();
        int iconPadding = HotbarLayoutCalculator.calculateIconPadding();

        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = slots[i];
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item == null || !item.hasIcon()) continue;

            HotbarLayoutCalculator.SlotPosition pos =
                    HotbarLayoutCalculator.calculateSlotPosition(i, layout);
            int iconX = pos.x + iconPadding;
            int iconY = pos.y + iconPadding;

            if (item instanceof BlockType bt) {
                uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt,
                        iconX, iconY, iconSize, iconSize, renderer.getBlockTextureArray());
            } else if (!(item instanceof ItemType it && SpriteVoxelizer.isSboBackedItem(it))) {
                uiRenderer.renderItemIcon(iconX, iconY, iconSize, iconSize,
                        item, renderer.getBlockTextureArray());
            }
        }
    }

    /** Skija pass: stack count text in the bottom-right corner of each slot. */
    public void drawCountTexts(Canvas canvas, ItemStack[] slots,
                               HotbarLayoutCalculator.HotbarLayout layout) {
        Font font = ui.fonts().getScaled(MStyle.FONT_META);
        for (int i = 0; i < slots.length; i++) {
            ItemStack stack = slots[i];
            if (stack == null || stack.isEmpty() || stack.getCount() <= 1) continue;
            HotbarLayoutCalculator.SlotPosition pos =
                    HotbarLayoutCalculator.calculateSlotPosition(i, layout);
            String countStr = String.valueOf(stack.getCount());
            float  countMargin = 2f * com.stonebreak.config.Settings.getInstance().getUiScale();
            float  textX    = pos.x + pos.width  - MPainter.measureWidth(font, countStr) - countMargin;
            float  textY    = pos.y + pos.height - countMargin;
            MPainter.drawStringWithShadow(canvas, countStr, textX, textY,
                    font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        }
    }
}
