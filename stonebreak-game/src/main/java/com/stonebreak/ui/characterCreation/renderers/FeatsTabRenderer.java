package com.stonebreak.ui.characterCreation.renderers;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rpg.feats.FeatDefinition;
import com.stonebreak.rpg.feats.FeatRegistry;
import com.stonebreak.ui.characterCreation.CharacterCreationActionHandler;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout;
import com.stonebreak.ui.characterCreation.CharacterCreationStateManager;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.List;

/**
 * Renders the Feats tab: a scrollable list of feats from FeatRegistry.ALL. Each
 * row shows the feat name, description, and an Acquire button (or a checkmark
 * if already acquired). FP counter pinned at the top.
 */
public final class FeatsTabRenderer {

    private static final float ROW_H        = 54f;
    private static final float ROW_PAD_X    = 16f;
    private static final float ROW_GAP      = 4f;
    private static final float HEADER_H     = 36f;
    private static final float CLIP_H       = 320f;
    private static final float ACQUIRE_BTN_W = 100f;
    private static final float ACQUIRE_BTN_H = 28f;

    private final MButton[] acquireButtons;

    public FeatsTabRenderer() {
        int count = FeatRegistry.ALL.size();
        this.acquireButtons = new MButton[count];
        for (int i = 0; i < count; i++) {
            acquireButtons[i] = new MButton("Acquire").fontSize(MStyle.FONT_META);
        }
    }

    public void render(Canvas canvas, MasonryUI ui, CharacterStats stats,
                       CharacterCreationStateManager state,
                       CharacterCreationLayout.Rect content,
                       float mx, float my) {
        Font metaFont = ui.fonts().get(MStyle.FONT_META);
        Font itemFont = ui.fonts().get(MStyle.FONT_ITEM);

        MPainter.drawStringWithShadow(canvas,
            "FP Remaining: " + stats.getRemainingFeatPoints(),
            content.x() + ROW_PAD_X, content.y() + 22f,
            metaFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        drawRule(canvas, content.x() + ROW_PAD_X, content.y() + 28f,
            content.width() - ROW_PAD_X * 2f);

        List<FeatDefinition> feats = FeatRegistry.ALL;
        float totalH    = feats.size() * (ROW_H + ROW_GAP);
        float maxScroll = Math.max(0f, totalH - CLIP_H);
        float scroll    = Math.clamp(state.getFeatScroll(), 0f, maxScroll);
        state.setFeatScroll(scroll);

        float listX = content.x() + ROW_PAD_X;
        float listY = content.y() + HEADER_H;
        float rowW  = content.width() - ROW_PAD_X * 2f;

        int save = canvas.save();
        canvas.clipRect(io.github.humbleui.types.Rect.makeXYWH(listX, listY, rowW, CLIP_H));

        for (int i = 0; i < feats.size(); i++) {
            FeatDefinition feat = feats.get(i);
            float rowY = listY + i * (ROW_H + ROW_GAP) - scroll;

            boolean acquired = stats.hasFeat(feat.id());
            boolean canAfford = stats.getRemainingFeatPoints() > 0;

            boolean rowHovered = mx >= listX && mx <= listX + rowW
                              && my >= rowY  && my <= rowY + ROW_H;
            if (rowHovered && !acquired) {
                MPainter.fillRoundedRect(canvas, listX, rowY, rowW, ROW_H, 3f, 0x22FFFFFF);
            }
            if (acquired) {
                MPainter.fillRoundedRect(canvas, listX, rowY, rowW, ROW_H, 3f, 0x1A55AA55);
            }

            float nameY = rowY + ROW_H * 0.32f + MStyle.FONT_META * 0.38f;
            float descY = rowY + ROW_H * 0.65f + MStyle.FONT_META * 0.38f;

            int nameColor = acquired ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
            MPainter.drawStringWithShadow(canvas, feat.name(),
                listX + 6f, nameY, metaFont, nameColor, MStyle.TEXT_SHADOW);
            MPainter.drawStringWithShadow(canvas, feat.description(),
                listX + 6f, descY, metaFont, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

            float btnX = listX + rowW - ACQUIRE_BTN_W;
            float btnY = rowY + (ROW_H - ACQUIRE_BTN_H) / 2f;

            if (acquired) {
                // Checkmark label instead of a button
                MPainter.drawCenteredStringWithShadow(canvas, "Acquired",
                    btnX + ACQUIRE_BTN_W / 2f,
                    btnY + ACQUIRE_BTN_H * 0.5f + MStyle.FONT_META * 0.38f,
                    metaFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
                acquireButtons[i].bounds(btnX, btnY, ACQUIRE_BTN_W, ACQUIRE_BTN_H);
            } else {
                acquireButtons[i].bounds(btnX, btnY, ACQUIRE_BTN_W, ACQUIRE_BTN_H);
                acquireButtons[i].updateHover(mx, my);

                int btnFill = !canAfford ? MStyle.BUTTON_FILL_DIS
                    : acquireButtons[i].isHovered() ? MStyle.BUTTON_FILL_HI
                    : MStyle.BUTTON_FILL;
                MPainter.stoneSurface(canvas, btnX, btnY, ACQUIRE_BTN_W, ACQUIRE_BTN_H,
                    MStyle.BUTTON_RADIUS, btnFill, MStyle.BUTTON_BORDER,
                    MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
                    MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

                int btnTextColor = canAfford ? MStyle.TEXT_PRIMARY : MStyle.TEXT_DISABLED;
                MPainter.drawCenteredStringWithShadow(canvas, "Acquire",
                    btnX + ACQUIRE_BTN_W / 2f,
                    btnY + ACQUIRE_BTN_H * 0.5f + MStyle.FONT_META * 0.38f,
                    metaFont, btnTextColor, MStyle.TEXT_SHADOW);
            }

            if (i < feats.size() - 1) {
                drawRule(canvas, listX, rowY + ROW_H + 1f, rowW);
            }
        }

        canvas.restoreToCount(save);
    }

    public boolean handleClick(float mx, float my, CharacterStats stats,
                               CharacterCreationStateManager state,
                               CharacterCreationActionHandler actions) {
        if (stats.getRemainingFeatPoints() <= 0) return false;

        List<FeatDefinition> feats = FeatRegistry.ALL;
        for (int i = 0; i < feats.size() && i < acquireButtons.length; i++) {
            if (!stats.hasFeat(feats.get(i).id()) && acquireButtons[i].contains(mx, my)) {
                actions.onAcquireFeat(feats.get(i).id());
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
