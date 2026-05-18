package com.stonebreak.ui.characterCreation.renderers;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.characterCreation.CharacterCreationActionHandler;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout.Rect;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Renders the Ability Score tab: a 3x2 grid of stat tiles each with a name
 * abbreviation, current score, modifier, and [−]/[+] buttons.
 */
public final class AbilityScoreTabRenderer {

    private static final String[] ABBREV = {"STR", "DEX", "CON", "INT", "WIS", "CHA"};

    private static final float TILE_W   = 90f;
    private static final float TILE_H   = 100f;
    private static final float TILE_GAP = 12f;
    private static final float BTN_W    = 20f;
    private static final float BTN_H    = 16f;

    private static final int TILE_FILL   = 0xFF252525;
    private static final int TILE_BORDER = 0xFF151515;

    private final MButton[] plusButtons  = new MButton[6];
    private final MButton[] minusButtons = new MButton[6];

    public AbilityScoreTabRenderer() {
        for (int i = 0; i < 6; i++) {
            plusButtons[i]  = new MButton("+").fontSize(MStyle.FONT_META);
            minusButtons[i] = new MButton("−").fontSize(MStyle.FONT_META);
        }
    }

    public void render(Canvas canvas, MasonryUI ui, CharacterStats stats,
                       Rect content, float mx, float my) {
        Font metaFont   = ui.fonts().get(MStyle.FONT_META);
        Font valueFont  = ui.fonts().get(MStyle.FONT_BUTTON);

        String apLabel = "AP Remaining: " + stats.getRemainingAp();
        MPainter.drawCenteredStringWithShadow(canvas, apLabel,
            content.centerX(), content.y() + 20f,
            metaFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

        // Centre the 3x2 grid horizontally in the content area
        float gridW = 3 * TILE_W + 2 * TILE_GAP;
        float gridX = content.x() + (content.width() - gridW) / 2f;
        float gridY = content.y() + 36f;

        int[] scores = stats.getAbilityScores();

        for (int i = 0; i < 6; i++) {
            int col = i % 3;
            int row = i / 3;

            float tx = gridX + col * (TILE_W + TILE_GAP);
            float ty = gridY + row * (TILE_H + TILE_GAP);

            MPainter.stoneSurface(canvas, tx, ty, TILE_W, TILE_H, 3f,
                TILE_FILL, TILE_BORDER,
                0x55000000, 0x1AFFFFFF, 0,
                MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);

            if (mx >= tx && mx <= tx + TILE_W && my >= ty && my <= ty + TILE_H) {
                MPainter.fillRoundedRect(canvas, tx + 1f, ty + 1f, TILE_W - 2f, TILE_H - 2f, 3f, 0x22FFFFFF);
            }

            float tileCX = tx + TILE_W / 2f;

            // Abbreviation label
            MPainter.drawCenteredStringWithShadow(canvas, ABBREV[i],
                tileCX, ty + 16f, metaFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

            // Score value
            MPainter.drawCenteredStringWithShadow(canvas, String.valueOf(scores[i]),
                tileCX, ty + 60f, valueFont, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);

            // [−] button — left side, vertically centred on the score
            float btnY = ty + 44f;
            minusButtons[i].bounds(tx + 2f, btnY, BTN_W, BTN_H);
            minusButtons[i].updateHover(mx, my);

            boolean minusEnabled = scores[i] > 1;
            int minusFill = !minusEnabled ? MStyle.BUTTON_FILL_DIS
                : minusButtons[i].isHovered() ? MStyle.BUTTON_FILL_HI
                : MStyle.BUTTON_FILL;
            MPainter.stoneSurface(canvas, tx + 2f, btnY, BTN_W, BTN_H, 2f,
                minusFill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
            MPainter.drawCenteredStringWithShadow(canvas, "−",
                tx + 2f + BTN_W / 2f, btnY + BTN_H * 0.72f,
                metaFont,
                minusEnabled ? MStyle.TEXT_PRIMARY : MStyle.TEXT_DISABLED,
                MStyle.TEXT_SHADOW);

            // [+] button — right side
            float plusX = tx + TILE_W - BTN_W - 2f;
            plusButtons[i].bounds(plusX, btnY, BTN_W, BTN_H);
            plusButtons[i].updateHover(mx, my);

            boolean plusEnabled = stats.getRemainingAp() > 0;
            int plusFill = !plusEnabled ? MStyle.BUTTON_FILL_DIS
                : plusButtons[i].isHovered() ? MStyle.BUTTON_FILL_HI
                : MStyle.BUTTON_FILL;
            MPainter.stoneSurface(canvas, plusX, btnY, BTN_W, BTN_H, 2f,
                plusFill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
            MPainter.drawCenteredStringWithShadow(canvas, "+",
                plusX + BTN_W / 2f, btnY + BTN_H * 0.72f,
                metaFont,
                plusEnabled ? MStyle.TEXT_PRIMARY : MStyle.TEXT_DISABLED,
                MStyle.TEXT_SHADOW);

            // Modifier
            int mod = stats.getModifier(scores[i]);
            String modStr = (mod >= 0 ? "+" : "") + mod;
            MPainter.drawCenteredStringWithShadow(canvas, modStr,
                tileCX, ty + TILE_H - 10f,
                metaFont, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
        }
    }

    public boolean handleClick(float mx, float my, CharacterStats stats,
                               CharacterCreationActionHandler actions) {
        for (int i = 0; i < 6; i++) {
            if (plusButtons[i].contains(mx, my)) {
                actions.onAbilityIncrement(i);
                return true;
            }
            if (minusButtons[i].contains(mx, my)) {
                actions.onAbilityDecrement(i);
                return true;
            }
        }
        return false;
    }
}
