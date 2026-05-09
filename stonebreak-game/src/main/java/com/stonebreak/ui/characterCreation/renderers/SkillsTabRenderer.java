package com.stonebreak.ui.characterCreation.renderers;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rpg.skills.SkillDefinition;
import com.stonebreak.rpg.skills.SkillRegistry;
import com.stonebreak.ui.characterCreation.CharacterCreationActionHandler;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout;
import com.stonebreak.ui.characterCreation.CharacterCreationStateManager;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.List;

/**
 * Renders the Skills tab: a scrollable list of all 10 skills with level display
 * and a [+1 SP] button per row.
 */
public final class SkillsTabRenderer {

    private static final float ROW_H        = 40f;
    private static final float ROW_PAD_X    = 16f;
    private static final float ROW_GAP      = 2f;
    private static final float HEADER_H     = 36f;
    private static final float CLIP_H       = 320f;
    private static final float BTN_W        = 80f;
    private static final float BTN_H        = 26f;

    private final MButton[] investButtons = new MButton[SkillRegistry.ALL.size()];

    public SkillsTabRenderer() {
        for (int i = 0; i < investButtons.length; i++) {
            investButtons[i] = new MButton("+1 SP").fontSize(MStyle.FONT_META);
        }
    }

    public void render(Canvas canvas, MasonryUI ui, CharacterStats stats,
                       CharacterCreationStateManager state,
                       CharacterCreationLayout.Rect content,
                       float mx, float my) {
        Font metaFont = ui.fonts().get(MStyle.FONT_META);
        Font itemFont = ui.fonts().get(MStyle.FONT_ITEM);

        // Header
        MPainter.drawStringWithShadow(canvas,
            "SP Remaining: " + stats.getRemainingSkillPoints(),
            content.x() + ROW_PAD_X, content.y() + 22f,
            metaFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        drawRule(canvas, content.x() + ROW_PAD_X, content.y() + 28f,
            content.width() - ROW_PAD_X * 2f);

        List<SkillDefinition> skills = SkillRegistry.ALL;
        float totalH    = skills.size() * (ROW_H + ROW_GAP);
        float maxScroll = Math.max(0f, totalH - CLIP_H);
        float scroll    = Math.clamp(state.getSkillScroll(), 0f, maxScroll);
        state.setSkillScroll(scroll);

        float listX   = content.x() + ROW_PAD_X;
        float listY   = content.y() + HEADER_H;
        float rowW    = content.width() - ROW_PAD_X * 2f;

        int save = canvas.save();
        canvas.clipRect(io.github.humbleui.types.Rect.makeXYWH(listX, listY, rowW, CLIP_H));

        for (int i = 0; i < skills.size(); i++) {
            SkillDefinition skill = skills.get(i);
            float rowY = listY + i * (ROW_H + ROW_GAP) - scroll;

            boolean rowHovered = mx >= listX && mx <= listX + rowW
                              && my >= rowY  && my <= rowY + ROW_H;
            if (rowHovered) {
                MPainter.fillRoundedRect(canvas, listX, rowY, rowW, ROW_H, 3f, 0x22FFFFFF);
            }

            float textY = rowY + ROW_H * 0.5f + MStyle.FONT_META * 0.38f;

            MPainter.drawStringWithShadow(canvas, skill.name(),
                listX + 4f, textY, metaFont, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);

            int level = stats.getSkillLevel(skill.id());
            MPainter.drawStringWithShadow(canvas, "Lvl: " + level,
                listX + rowW * 0.55f, textY, metaFont, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

            boolean canInvest = stats.getRemainingSkillPoints() > 0;
            float btnX = listX + rowW - BTN_W;
            float btnY = rowY + (ROW_H - BTN_H) / 2f;
            investButtons[i].bounds(btnX, btnY, BTN_W, BTN_H);
            investButtons[i].updateHover(mx, my);

            int btnFill = !canInvest ? MStyle.BUTTON_FILL_DIS
                : investButtons[i].isHovered() ? MStyle.BUTTON_FILL_HI
                : MStyle.BUTTON_FILL;
            MPainter.stoneSurface(canvas, btnX, btnY, BTN_W, BTN_H,
                MStyle.BUTTON_RADIUS, btnFill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

            int btnTextColor = canInvest ? MStyle.TEXT_PRIMARY : MStyle.TEXT_DISABLED;
            MPainter.drawCenteredStringWithShadow(canvas, "+1 SP",
                btnX + BTN_W / 2f, btnY + BTN_H * 0.5f + MStyle.FONT_META * 0.38f,
                metaFont, btnTextColor, MStyle.TEXT_SHADOW);
        }

        canvas.restoreToCount(save);
    }

    public boolean handleClick(float mx, float my, CharacterStats stats,
                               CharacterCreationStateManager state,
                               CharacterCreationActionHandler actions) {
        if (stats.getRemainingSkillPoints() <= 0) return false;

        List<SkillDefinition> skills = SkillRegistry.ALL;
        for (int i = 0; i < skills.size() && i < investButtons.length; i++) {
            if (investButtons[i].contains(mx, my)) {
                actions.onInvestSkill(skills.get(i).id());
                return true;
            }
        }
        return false;
    }

    private void drawRule(Canvas canvas, float x, float y, float w) {
        MPainter.fillRect(canvas, x, y,       w, 1f, 0x66000000);
        MPainter.fillRect(canvas, x, y + 2f, w, 1f, 0x33FFFFFF);
    }
}
