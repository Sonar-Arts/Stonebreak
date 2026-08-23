package com.stonebreak.rendering.UI.components.hotbar;

import com.stonebreak.player.Player;
import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Universal dodge cooldown indicator — a small labelled bar to the left of the hotbar that
 * fills as the dodge recharges and brightens to "Ready" when available. Shown for every
 * class (dodge is not class-gated).
 */
public final class DodgeIndicator {

    private static final int PANEL_WIDTH  = 120;
    private static final int BAR_HEIGHT   = 10;
    private static final int LABEL_GAP    = 6;
    private static final int BAR_CHARGING = 0xDC4A7EA8; // dim steel-blue while recharging
    private static final int BAR_READY    = 0xDC50B8DC; // bright cyan when ready

    public void draw(Canvas canvas, Font font, Player player, HotbarLayoutCalculator.HotbarLayout layout) {
        float progress = player.getDodge().getCooldownProgress(); // 0..1, 1 = ready
        boolean ready = progress >= 1f;

        float panelX = layout.backgroundX - GaugePanel.PANEL_GAP - PANEL_WIDTH;
        GaugePanel panel = new GaugePanel(canvas, font, panelX, layout.backgroundY, PANEL_WIDTH);

        panel.header(null, ready ? "Dodge: Ready" : "Dodge", LABEL_GAP);
        panel.bar(BAR_HEIGHT, Math.max(0f, Math.min(1f, progress)), ready ? BAR_READY : BAR_CHARGING, 0f);
    }
}
