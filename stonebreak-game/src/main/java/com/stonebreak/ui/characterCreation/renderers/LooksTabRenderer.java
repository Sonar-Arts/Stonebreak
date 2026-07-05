package com.stonebreak.ui.characterCreation.renderers;

import com.stonebreak.player.PlayerLooks;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.characterCreation.CharacterCreationActionHandler;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.List;

/**
 * Renders the Looks tab: cosmetic hat options from PlayerLooks.HAT_OPTIONS.
 * Each row shows the hat name and an Equip button (or "Equipped" for the
 * current selection). Selecting applies immediately, so the change shows on
 * the rotating preview model in the left panel.
 */
public final class LooksTabRenderer {

    private static final float ROW_H       = 54f;
    private static final float ROW_PAD_X   = 16f;
    private static final float ROW_GAP     = 4f;
    private static final float HEADER_H    = 36f;
    private static final float EQUIP_BTN_W = 100f;
    private static final float EQUIP_BTN_H = 28f;

    private final MButton[] equipButtons;

    public LooksTabRenderer() {
        int count = PlayerLooks.HAT_OPTIONS.size();
        this.equipButtons = new MButton[count];
        for (int i = 0; i < count; i++) {
            equipButtons[i] = new MButton("Equip").fontSize(MStyle.FONT_META);
        }
    }

    public void render(Canvas canvas, MasonryUI ui,
                       CharacterCreationLayout.Rect content,
                       float mx, float my) {
        Font metaFont = ui.fonts().get(MStyle.FONT_META);

        MPainter.drawStringWithShadow(canvas, "Head",
            content.x() + ROW_PAD_X, content.y() + 22f,
            metaFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        drawRule(canvas, content.x() + ROW_PAD_X, content.y() + 28f,
            content.width() - ROW_PAD_X * 2f);

        List<PlayerLooks.HatOption> hats = PlayerLooks.HAT_OPTIONS;
        String selectedId = PlayerLooks.getSelectedHatId();

        float listX = content.x() + ROW_PAD_X;
        float listY = content.y() + HEADER_H;
        float rowW  = content.width() - ROW_PAD_X * 2f;

        for (int i = 0; i < hats.size(); i++) {
            PlayerLooks.HatOption hat = hats.get(i);
            float rowY = listY + i * (ROW_H + ROW_GAP);

            boolean equipped = hat.id().equalsIgnoreCase(selectedId);
            boolean rowHovered = mx >= listX && mx <= listX + rowW
                              && my >= rowY  && my <= rowY + ROW_H;
            if (rowHovered && !equipped) {
                MPainter.fillRoundedRect(canvas, listX, rowY, rowW, ROW_H, 3f, 0x22FFFFFF);
            }
            if (equipped) {
                MPainter.fillRoundedRect(canvas, listX, rowY, rowW, ROW_H, 3f, 0x1A55AA55);
            }

            float nameY = rowY + ROW_H * 0.5f + MStyle.FONT_META * 0.38f;
            int nameColor = equipped ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
            MPainter.drawStringWithShadow(canvas, hat.displayName(),
                listX + 6f, nameY, metaFont, nameColor, MStyle.TEXT_SHADOW);

            float btnX = listX + rowW - EQUIP_BTN_W;
            float btnY = rowY + (ROW_H - EQUIP_BTN_H) / 2f;
            equipButtons[i].bounds(btnX, btnY, EQUIP_BTN_W, EQUIP_BTN_H);

            if (equipped) {
                MPainter.drawCenteredStringWithShadow(canvas, "Equipped",
                    btnX + EQUIP_BTN_W / 2f,
                    btnY + EQUIP_BTN_H * 0.5f + MStyle.FONT_META * 0.38f,
                    metaFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
            } else {
                equipButtons[i].updateHover(mx, my);

                int btnFill = equipButtons[i].isHovered() ? MStyle.BUTTON_FILL_HI
                    : MStyle.BUTTON_FILL;
                MPainter.stoneSurface(canvas, btnX, btnY, EQUIP_BTN_W, EQUIP_BTN_H,
                    MStyle.BUTTON_RADIUS, btnFill, MStyle.BUTTON_BORDER,
                    MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
                    MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

                MPainter.drawCenteredStringWithShadow(canvas, "Equip",
                    btnX + EQUIP_BTN_W / 2f,
                    btnY + EQUIP_BTN_H * 0.5f + MStyle.FONT_META * 0.38f,
                    metaFont, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
            }

            if (i < hats.size() - 1) {
                drawRule(canvas, listX, rowY + ROW_H + 1f, rowW);
            }
        }
    }

    public boolean handleClick(float mx, float my, CharacterCreationActionHandler actions) {
        List<PlayerLooks.HatOption> hats = PlayerLooks.HAT_OPTIONS;
        String selectedId = PlayerLooks.getSelectedHatId();
        for (int i = 0; i < hats.size() && i < equipButtons.length; i++) {
            if (!hats.get(i).id().equalsIgnoreCase(selectedId)
                    && equipButtons[i].contains(mx, my)) {
                actions.onSelectHat(hats.get(i).id());
                return true;
            }
        }
        return false;
    }

    private void drawRule(Canvas canvas, float x, float y, float w) {
        MPainter.fillRect(canvas, x, y,      w, 1f, 0x66000000);
        MPainter.fillRect(canvas, x, y + 2f, w, 1f, 0x33FFFFFF);
    }
}
