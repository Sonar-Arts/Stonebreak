package com.stonebreak.ui.characterCreation.renderers;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rpg.classes.ClassAbility;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.classes.PlayerClassDefinition;
import com.stonebreak.ui.characterCreation.CharacterCreationActionHandler;
import com.stonebreak.ui.characterCreation.CharacterCreationLayout;
import com.stonebreak.ui.characterCreation.CharacterCreationStateManager;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.List;
import java.util.Optional;

/**
 * Renders the Class Abilities tab.
 *
 * Left sidebar (~160px): clickable list of all 6 classes.
 * Right area: selected class header, CP counter, and scrollable ability rows with Spend buttons.
 */
public final class ClassAbilitiesTabRenderer {

    private static final float SIDEBAR_W      = 160f;
    private static final float SIDEBAR_PAD    = 8f;
    private static final float CLASS_ROW_H    = 36f;
    private static final float CLASS_ROW_GAP  = 2f;

    private static final float CONTENT_PAD    = 12f;
    private static final float ABILITY_ROW_H  = 64f;
    private static final float SPEND_BTN_W    = 110f;
    private static final float SPEND_BTN_H    = 24f;
    private static final float ABILITY_CLIP_H = 300f;

    /** One spend button per ability slot (ClassRegistry max 5). */
    private final MButton[] spendButtons = new MButton[5];

    public ClassAbilitiesTabRenderer() {
        for (int i = 0; i < spendButtons.length; i++) {
            spendButtons[i] = new MButton("Spend CP").fontSize(MStyle.FONT_META);
        }
    }

    public void render(Canvas canvas, MasonryUI ui, CharacterStats stats,
                       CharacterCreationStateManager state,
                       CharacterCreationLayout.Rect content,
                       float mx, float my) {
        drawSidebar(canvas, ui, stats, state, content, mx, my);
        drawContentArea(canvas, ui, stats, state, content, mx, my);
    }

    public boolean handleClick(float mx, float my, CharacterStats stats,
                               CharacterCreationStateManager state,
                               CharacterCreationActionHandler actions,
                               CharacterCreationLayout.Rect content) {
        return handleSidebarClick(mx, my, state, actions, content)
            || handleAbilityClick(mx, my, stats, state, actions);
    }

    // ─────────────────────────────────────────────── Sidebar

    private void drawSidebar(Canvas canvas, MasonryUI ui, CharacterStats stats,
                             CharacterCreationStateManager state,
                             CharacterCreationLayout.Rect content,
                             float mx, float my) {
        Font metaFont = ui.fonts().get(MStyle.FONT_META);

        float sideX = content.x() + SIDEBAR_PAD;
        float sideY = content.y() + SIDEBAR_PAD;

        MPainter.drawStringWithShadow(canvas, "Class",
            sideX, sideY + 12f, metaFont, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
        drawRule(canvas, sideX, sideY + 18f, SIDEBAR_W - SIDEBAR_PAD * 2f);

        float listY  = sideY + 24f;
        float rowW   = SIDEBAR_W - SIDEBAR_PAD * 2f;
        List<PlayerClassDefinition> classes = ClassRegistry.ALL;

        for (int i = 0; i < classes.size(); i++) {
            PlayerClassDefinition cls = classes.get(i);
            float rowX = sideX;
            float rowY = listY + i * (CLASS_ROW_H + CLASS_ROW_GAP);

            boolean selected = i == state.getSelectedClassIndex();
            boolean hovered  = mx >= rowX && mx <= rowX + rowW
                            && my >= rowY && my <= rowY + CLASS_ROW_H;

            int fill = selected ? MStyle.BUTTON_FILL_HI
                : hovered      ? 0xFF3C3C3C
                : MStyle.BUTTON_FILL;

            MPainter.stoneSurface(canvas, rowX, rowY, rowW, CLASS_ROW_H, MStyle.BUTTON_RADIUS,
                fill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

            int nameColor = selected ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
            float textY   = rowY + CLASS_ROW_H * 0.5f + MStyle.FONT_META * 0.38f;
            MPainter.drawStringWithShadow(canvas, cls.name(),
                rowX + 6f, textY, metaFont, nameColor, MStyle.TEXT_SHADOW);
        }
    }

    private boolean handleSidebarClick(float mx, float my,
                                       CharacterCreationStateManager state,
                                       CharacterCreationActionHandler actions,
                                       CharacterCreationLayout.Rect content) {
        float sideX = content.x() + SIDEBAR_PAD;
        float listY = content.y() + SIDEBAR_PAD + 24f;
        float rowW  = SIDEBAR_W - SIDEBAR_PAD * 2f;

        List<PlayerClassDefinition> classes = ClassRegistry.ALL;
        for (int i = 0; i < classes.size(); i++) {
            float rowX = sideX;
            float rowY = listY + i * (CLASS_ROW_H + CLASS_ROW_GAP);
            if (mx >= rowX && mx <= rowX + rowW && my >= rowY && my <= rowY + CLASS_ROW_H) {
                actions.onSelectClass(i);
                return true;
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────── Content area

    private void drawContentArea(Canvas canvas, MasonryUI ui, CharacterStats stats,
                                 CharacterCreationStateManager state,
                                 CharacterCreationLayout.Rect content,
                                 float mx, float my) {
        Font metaFont = ui.fonts().get(MStyle.FONT_META);
        Font itemFont = ui.fonts().get(MStyle.FONT_ITEM);

        float cx = content.x() + SIDEBAR_W + CONTENT_PAD;
        float cy = content.y() + SIDEBAR_PAD;
        float cw = content.width() - SIDEBAR_W - CONTENT_PAD * 2f;

        int selIdx = state.getSelectedClassIndex();
        Optional<PlayerClassDefinition> classOpt =
            selIdx >= 0 && selIdx < ClassRegistry.ALL.size()
                ? Optional.of(ClassRegistry.ALL.get(selIdx))
                : Optional.empty();

        String className = classOpt.map(PlayerClassDefinition::name).orElse("Select a class →");
        String classDesc = classOpt.map(PlayerClassDefinition::description)
            .orElse("Click a class on the left to view its abilities.");

        MPainter.drawStringWithShadow(canvas, className,
            cx, cy + 16f, itemFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        MPainter.drawStringWithShadow(canvas, classDesc,
            cx, cy + 34f, metaFont, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
        drawRule(canvas, cx, cy + 42f, cw);

        MPainter.drawStringWithShadow(canvas,
            "CP Remaining: " + stats.getRemainingCp(),
            cx, cy + 58f, metaFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

        if (classOpt.isEmpty()) return;

        drawAbilityList(canvas, ui, stats, state, classOpt.get(), cx, cy + 70f, cw, mx, my);
    }

    private void drawAbilityList(Canvas canvas, MasonryUI ui, CharacterStats stats,
                                 CharacterCreationStateManager state,
                                 PlayerClassDefinition cls,
                                 float cx, float startY, float cw,
                                 float mx, float my) {
        Font metaFont = ui.fonts().get(MStyle.FONT_META);
        List<ClassAbility> abilities = cls.abilities();

        float totalH    = abilities.size() * ABILITY_ROW_H;
        float maxScroll = Math.max(0f, totalH - ABILITY_CLIP_H);
        float scroll    = Math.clamp(state.getClassScroll(), 0f, maxScroll);
        state.setClassScroll(scroll);

        int save = canvas.save();
        canvas.clipRect(io.github.humbleui.types.Rect.makeXYWH(cx, startY, cw, ABILITY_CLIP_H));

        for (int i = 0; i < abilities.size(); i++) {
            ClassAbility ability = abilities.get(i);
            String key      = cls.id() + ":" + i;
            int    spent    = stats.getSpentCp(key);
            boolean canSpend = stats.getRemainingCp() > 0;

            float rowY = startY + i * ABILITY_ROW_H - scroll;

            MPainter.drawStringWithShadow(canvas, ability.name(),
                cx, rowY + 16f, metaFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
            MPainter.drawStringWithShadow(canvas, ability.description(),
                cx, rowY + 32f, metaFont, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);

            float btnX = cx + cw - SPEND_BTN_W;
            float btnY = rowY + (ABILITY_ROW_H - SPEND_BTN_H) / 2f;
            spendButtons[i].bounds(btnX, btnY, SPEND_BTN_W, SPEND_BTN_H);
            spendButtons[i].updateHover(mx, my);

            int btnFill = !canSpend ? MStyle.BUTTON_FILL_DIS
                : spendButtons[i].isHovered() ? MStyle.BUTTON_FILL_HI
                : MStyle.BUTTON_FILL;
            MPainter.stoneSurface(canvas, btnX, btnY, SPEND_BTN_W, SPEND_BTN_H,
                MStyle.BUTTON_RADIUS, btnFill, MStyle.BUTTON_BORDER,
                MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, 0,
                MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

            String btnLabel     = "Spend " + ability.cpCost() + " CP (" + spent + ")";
            int    btnTextColor = canSpend ? MStyle.TEXT_PRIMARY : MStyle.TEXT_DISABLED;
            MPainter.drawCenteredStringWithShadow(canvas, btnLabel,
                btnX + SPEND_BTN_W / 2f,
                btnY + SPEND_BTN_H * 0.5f + MStyle.FONT_META * 0.38f,
                metaFont, btnTextColor, MStyle.TEXT_SHADOW);

            if (i < abilities.size() - 1) {
                drawRule(canvas, cx, rowY + ABILITY_ROW_H - 4f, cw);
            }
        }

        canvas.restoreToCount(save);
    }

    private boolean handleAbilityClick(float mx, float my, CharacterStats stats,
                                       CharacterCreationStateManager state,
                                       CharacterCreationActionHandler actions) {
        int selIdx = state.getSelectedClassIndex();
        if (selIdx < 0 || selIdx >= ClassRegistry.ALL.size()) return false;
        if (stats.getRemainingCp() <= 0) return false;

        PlayerClassDefinition cls = ClassRegistry.ALL.get(selIdx);
        for (int i = 0; i < spendButtons.length; i++) {
            if (spendButtons[i].contains(mx, my)) {
                actions.onSpendCp(cls.id() + ":" + i);
                return true;
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────── Helpers

    private void drawRule(Canvas canvas, float x, float y, float w) {
        MPainter.fillRect(canvas, x, y,       w, 1f, 0x66000000);
        MPainter.fillRect(canvas, x, y + 2f, w, 1f, 0x33FFFFFF);
    }
}
