package com.stonebreak.rendering.UI.components;

import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.UI.components.hotbar.ClassGauge;
import com.stonebreak.rendering.UI.components.hotbar.DodgeIndicator;
import com.stonebreak.rendering.UI.components.hotbar.DoubtGauge;
import com.stonebreak.rendering.UI.components.hotbar.GaugePanel;
import com.stonebreak.rendering.UI.components.hotbar.HealthHeartsRenderer;
import com.stonebreak.rendering.UI.components.hotbar.HotbarSlotRenderer;
import com.stonebreak.rendering.UI.components.hotbar.HotbarTooltipRenderer;
import com.stonebreak.rendering.UI.components.hotbar.MomentumGauge;
import com.stonebreak.rendering.UI.components.hotbar.QuarryGauge;
import com.stonebreak.rendering.UI.components.hotbar.RageGauge;
import com.stonebreak.rendering.UI.components.hotbar.ResonanceGauge;
import com.stonebreak.rendering.UI.components.hotbar.StaminaBarRenderer;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.HotbarScreen;
import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.List;

/**
 * MasonryUI/Skija-based hotbar renderer.
 *
 * Replaces the NanoVG {@code HotbarRenderer} using the same 3-phase pattern
 * as {@code InventoryRenderCoordinator}:
 *   A) Skija  – background panel, slot backgrounds, health hearts, bars and gauges
 *   B) GL     – item icons drawn directly into the framebuffer
 *   C) Skija  – item count text
 *   D) Skija  – tooltip (separate call, layered after block drops)
 *
 * Thin coordinator: the drawing lives in {@code components.hotbar}; this class only lays
 * the components out and calls them in order.
 */
public class MHotbarRenderer {

    private final MasonryUI ui;

    private final HotbarSlotRenderer    slots;
    private final HealthHeartsRenderer  hearts;
    private final StaminaBarRenderer    bars;
    private final List<ClassGauge>      classGauges;
    private final DodgeIndicator        dodge;
    private final HotbarTooltipRenderer tooltip;

    public MHotbarRenderer(UIRenderer uiRenderer, Renderer renderer) {
        this.ui          = new MasonryUI(renderer.getSkijaBackend());
        this.slots       = new HotbarSlotRenderer(uiRenderer, renderer, ui);
        this.hearts      = new HealthHeartsRenderer();
        this.bars        = new StaminaBarRenderer(hearts);
        this.classGauges = List.of(new RageGauge(), new QuarryGauge(), new ResonanceGauge(),
                                   new DoubtGauge(), new MomentumGauge());
        this.dodge       = new DodgeIndicator();
        this.tooltip     = new HotbarTooltipRenderer(ui);
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
        ItemStack[] slotStacks = hotbarScreen.getHotbarSlots();
        int         selected   = hotbarScreen.getSelectedSlotIndex();

        // ── Phase A: Skija ────────────────────────────────────────────────
        if (ui.beginFrame(sw, sh, 1.0f)) {
            Canvas canvas = ui.canvas();
            slots.drawBackground(canvas, layout);
            slots.drawSlots(layout, selected);
            slots.drawSbtItemIcons(canvas, slotStacks, layout);
            drawPlayerHud(canvas, layout);
            ui.renderOverlays();
            ui.endFrame();
        }

        // ── Phase B: GL item icons ────────────────────────────────────────
        slots.renderItemIcons(slotStacks, layout);

        // ── Phase C: Skija count texts ────────────────────────────────────
        if (ui.beginFrame(sw, sh, 1.0f)) {
            slots.drawCountTexts(ui.canvas(), slotStacks, layout);
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
            tooltip.draw(ui.canvas(), text, alpha, selectedIndex, layout, sw, sh);
            ui.endFrame();
        }
    }

    // ─────────────────────────────────────────────── Phase A helpers

    /** Hearts, stamina/mana bars, the selected class's gauge and the dodge indicator. */
    private void drawPlayerHud(Canvas canvas, HotbarLayoutCalculator.HotbarLayout layout) {
        Player player = Game.getInstance().getPlayer();
        if (player == null) return;

        hearts.draw(canvas, player, layout);
        bars.drawStamina(canvas, player, layout);
        bars.drawMana(canvas, player, layout);

        Font   font          = ui.fonts().getScaled(MStyle.FONT_META);
        String selectedClass = player.getCharacterStats().getSelectedClassId();
        float  gaugeX        = layout.backgroundX + layout.backgroundWidth + GaugePanel.PANEL_GAP;
        for (ClassGauge gauge : classGauges) {
            if (!gauge.classId().equals(selectedClass)) continue;
            gauge.draw(new GaugePanel(canvas, font, gaugeX, layout.backgroundY, ClassGauge.PANEL_WIDTH), player);
        }

        dodge.draw(canvas, font, player, layout);
    }
}
