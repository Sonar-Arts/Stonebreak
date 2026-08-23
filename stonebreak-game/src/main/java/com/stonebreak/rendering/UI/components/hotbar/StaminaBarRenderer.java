package com.stonebreak.rendering.UI.components.hotbar;

import com.stonebreak.player.Player;
import com.stonebreak.player.combat.arcanist.ArcanistAbilityController;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator;
import io.github.humbleui.skija.Canvas;

/**
 * Stamina bar stacked directly above the top heart row, plus the mana bar stacked above it
 * (mana renders only when the selected class spends mana — currently just the Arcanist —
 * to avoid HUD clutter for the others).
 */
public final class StaminaBarRenderer {

    private static final int STAMINA_BAR_HEIGHT = 8;
    private static final int STAMINA_BAR_GAP    = 6;    // pixels above top heart row
    private static final int STAMINA_BG         = 0xC83C3C3C;
    private static final int STAMINA_FILL       = 0xDC50C850;
    private static final int STAMINA_BORDER     = 0xFF000000;

    private static final int MANA_BAR_HEIGHT = 8;
    private static final int MANA_BAR_GAP    = 6;    // pixels above the stamina bar
    private static final int MANA_FILL       = 0xDC3C78DC; // arcane blue

    private final HealthHeartsRenderer hearts;

    public StaminaBarRenderer(HealthHeartsRenderer hearts) {
        this.hearts = hearts;
    }

    public void drawStamina(Canvas canvas, Player player, HotbarLayoutCalculator.HotbarLayout layout) {
        float maxStamina = player.getMaxStamina();
        if (maxStamina <= 0) return;

        float barY = staminaBarY(player, layout);
        drawBar(canvas, layout, barY, STAMINA_BAR_HEIGHT, player.getStamina() / maxStamina, STAMINA_FILL);
    }

    public void drawMana(Canvas canvas, Player player, HotbarLayoutCalculator.HotbarLayout layout) {
        if (!ArcanistAbilityController.CLASS_ID.equals(player.getCharacterStats().getSelectedClassId())) return;
        float maxMana = player.getMaxMana();
        if (maxMana <= 0) return;

        float barY = staminaBarY(player, layout) - MANA_BAR_GAP - MANA_BAR_HEIGHT;
        drawBar(canvas, layout, barY, MANA_BAR_HEIGHT, player.getMana() / maxMana, MANA_FILL);
    }

    private float staminaBarY(Player player, HotbarLayoutCalculator.HotbarLayout layout) {
        return hearts.topRowY(player, layout) - STAMINA_BAR_GAP - STAMINA_BAR_HEIGHT;
    }

    private static void drawBar(Canvas canvas, HotbarLayoutCalculator.HotbarLayout layout,
                                float barY, int height, float fraction, int fillColor) {
        float fillW = layout.backgroundWidth * Math.max(0f, Math.min(1f, fraction));

        MPainter.fillRect(canvas, layout.backgroundX, barY, layout.backgroundWidth, height, STAMINA_BG);
        if (fillW > 0) {
            MPainter.fillRect(canvas, layout.backgroundX, barY, fillW, height, fillColor);
        }
        MPainter.strokeRect(canvas, layout.backgroundX, barY, layout.backgroundWidth, height, STAMINA_BORDER, 1f);
    }
}
