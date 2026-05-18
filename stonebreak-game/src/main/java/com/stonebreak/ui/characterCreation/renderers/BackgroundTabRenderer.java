package com.stonebreak.ui.characterCreation.renderers;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rpg.backgrounds.BackgroundDefinition;
import com.stonebreak.rpg.backgrounds.BackgroundRegistry;
import com.stonebreak.ui.characterCreation.CharacterCreationActionHandler;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout.Rect;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.List;

/**
 * Renders the Background tab: a simple selectable list of the four placeholder
 * backgrounds with a description strip below the selected entry.
 */
public final class BackgroundTabRenderer {

    private static final float ROW_H       = 44f;
    private static final float ROW_PAD_X   = 16f;
    private static final float ROW_GAP     = 4f;
    private static final float LIST_TOP_PAD= 16f;

    public void render(Canvas canvas, MasonryUI ui, CharacterStats stats,
                       Rect content, float mx, float my) {
        Font metaFont = ui.fonts().get(MStyle.FONT_META);
        Font itemFont = ui.fonts().get(MStyle.FONT_ITEM);

        MPainter.drawStringWithShadow(canvas, "Choose your character's background:",
            content.x() + ROW_PAD_X, content.y() + LIST_TOP_PAD,
            metaFont, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

        List<BackgroundDefinition> bgs = BackgroundRegistry.ALL;
        float rowsStartY = content.y() + LIST_TOP_PAD + 20f;

        for (int i = 0; i < bgs.size(); i++) {
            BackgroundDefinition bg = bgs.get(i);
            float rowX = content.x() + ROW_PAD_X;
            float rowY = rowsStartY + i * (ROW_H + ROW_GAP);
            float rowW = content.width() - ROW_PAD_X * 2f;

            boolean selected = bg.id().equals(stats.getSelectedBackground());
            boolean hovered  = mx >= rowX && mx <= rowX + rowW
                            && my >= rowY && my <= rowY + ROW_H;

            int fill;
            if (selected) {
                fill = MStyle.BUTTON_FILL_HI;
            } else if (hovered) {
                fill = 0xFF3C3C3C;
            } else {
                fill = MStyle.BUTTON_FILL;
            }

            MPainter.stoneSurface(canvas, rowX, rowY, rowW, ROW_H, MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

            int nameColor = selected ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
            float nameY   = rowY + ROW_H * 0.38f + MStyle.FONT_ITEM * 0.38f;
            MPainter.drawStringWithShadow(canvas, bg.name(),
                rowX + 12f, nameY, itemFont, nameColor, MStyle.TEXT_SHADOW);

            float descY = rowY + ROW_H * 0.72f + MStyle.FONT_META * 0.38f;
            MPainter.drawStringWithShadow(canvas, bg.description(),
                rowX + 12f, descY, metaFont, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
        }
    }

    /**
     * Returns true if a background row was clicked and selection changed.
     */
    public boolean handleClick(float mx, float my, Rect content,
                               CharacterStats stats,
                               CharacterCreationActionHandler actions) {
        List<BackgroundDefinition> bgs = BackgroundRegistry.ALL;
        float rowsStartY = content.y() + LIST_TOP_PAD + 20f;

        for (int i = 0; i < bgs.size(); i++) {
            BackgroundDefinition bg = bgs.get(i);
            float rowX = content.x() + ROW_PAD_X;
            float rowY = rowsStartY + i * (ROW_H + ROW_GAP);
            float rowW = content.width() - ROW_PAD_X * 2f;

            if (mx >= rowX && mx <= rowX + rowW && my >= rowY && my <= rowY + ROW_H) {
                actions.onSelectBackground(bg.id());
                return true;
            }
        }
        return false;
    }
}
