package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleMenu;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.rendering.UI.masonryUI.MMenuList;
import com.stonebreak.rendering.UI.masonryUI.MMenuList.Adornment;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.ui.focusBattle.BattleHudRules;
import com.stonebreak.ui.focusBattle.BattleMenuState;
import com.stonebreak.ui.focusBattle.BattlePalette;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * E1 + E2: the battle command menu, a composition over two {@link MMenuList}s (the root list and
 * the Qi Arts submenu). This class owns no drawing: it turns {@link BattleMenu} and the
 * {@link BattleView} into row <em>data</em> each frame and tells the lists where the cursor is.
 *
 * <p>The window is on screen for the whole fight. While {@link BattleHudRules#menuLive} is false it
 * rests in the list's static state (every row listed, no cursor, veiled); on waking the veil fades
 * with {@code wake}. {@link BattleHudRules#rowUsable} is the one dimming rule for both lists.
 *
 * <p>{@link BattleMenuState} stays the state machine; the lists only display it. Hit-testing goes
 * through {@link MMenuList#rowAt}, the formula the rows are drawn with.
 */
public final class CommandWindow {

    /** Glow behind a ready Focus Combo row: pulsing around full while live, steady while resting. */
    static final float LIVE_READY_GLOW = 1f;
    static final float RESTING_READY_GLOW = 0.7f;

    private final MMenuList root = new MMenuList().accent(BattlePalette.ACCENT_MONK);
    private final MMenuList arts = new MMenuList().accent(BattlePalette.ACCENT_MONK);

    public CommandWindow() {
        reset();
    }

    public MMenuList rootList() { return root; }

    public MMenuList artsList() { return arts; }

    /** Back to a resting menu for a new encounter. */
    public void reset() {
        sync(null, null);
    }

    /** Advances the cursor bob and the ready glow; both hold still while a target is being chosen. */
    public void update(float dt, BattleView view, BattleMenuState menu) {
        sync(view, menu);
        if (menu != null && menu.targeting()) return;
        root.update(dt);
        arts.update(dt);
    }

    /**
     * Draws the menu into {@code commandRect} (and, while open, the submenu into {@code submenuRect});
     * the caller has already applied any whole-HUD slide to both.
     *
     * @param wake          0 = just woken and still veiled, 1 = fully awake
     * @param submenuSlideIn 0 = submenu tucked behind the command window, 1 = in place
     */
    public void render(MasonryUI ui, float[] commandRect, float[] submenuRect, BattleView view,
                       BattleMenuState menu, float scale, float wake, float submenuSlideIn) {
        Canvas canvas = ui == null ? null : ui.canvas();
        if (canvas == null || view == null || commandRect == null) return;
        sync(view, menu);
        boolean live = BattleHudRules.menuLive(view);

        if (live && menu != null && menu.submenuOpen() && submenuRect != null) {
            // Submenu first: it emerges from behind the command window, clipped at that window's
            // right edge so a half-slid list never shows through the frame.
            float tuck = (1f - MColor.clamp01(submenuSlideIn)) * -(submenuRect[2] * 0.5f);
            place(arts, FocusBattleLayout.offset(submenuRect, tuck, 0f), scale);
            int save = canvas.save();
            try {
                float edge = commandRect[0] + commandRect[2];
                canvas.clipRect(Rect.makeLTRB(edge, -1.0e6f, 1.0e6f, 1.0e6f));
                arts.render(ui);
            } finally {
                canvas.restoreToCount(save);
            }
        }

        place(root, commandRect, scale);
        root.veil(live ? 1f - MColor.clamp01(wake) : 1f).render(ui);
    }

    /** Root row under the pointer, or -1; {@code commandRect} as drawn. */
    public int rootRowAt(float px, float py, float[] commandRect, float scale) {
        return place(root, commandRect, scale).rowAt(px, py);
    }

    /** Submenu row under the pointer, or -1; {@code submenuRect} as drawn at rest. */
    public int artsRowAt(float px, float py, float[] submenuRect, float scale) {
        return place(arts, submenuRect, scale).rowAt(px, py);
    }

    private static MMenuList place(MMenuList list, float[] rect, float scale) {
        return list.scale(scale).bounds(rect[0], rect[1], rect[2], rect[3]);
    }

    // ─────────────────────────────────────────────── View → row data

    private void sync(BattleView view, BattleMenuState menu) {
        boolean live = BattleHudRules.menuLive(view);
        int opener = FocusBattleLayout.qiArtsRowIndex();
        boolean submenuOpen = menu != null && menu.submenuOpen();

        root.rows(rows(BattleMenu.ROOT, view))
                .active(live)
                .cursor(menu == null ? 0 : menu.rootIndex())
                .held(submenuOpen ? opener : -1)
                .clearRowGlows();
        for (int i = 0; i < BattleMenu.ROOT.size(); i++) {
            boolean ready = BattleMenu.ROOT.get(i).command() == BattleCommand.FOCUS_COMBO
                    && view != null && view.focusReady();
            if (ready) root.rowGlow(i, BattlePalette.FOCUS, live ? LIVE_READY_GLOW : RESTING_READY_GLOW);
        }

        arts.rows(rows(BattleMenu.QI_ARTS, view))
                .active(live)
                .cursor(menu == null ? 0 : menu.submenuIndex())
                .held(-1);
    }

    static List<MMenuList.Row> rows(List<BattleMenu.Row> entries, BattleView view) {
        List<MMenuList.Row> rows = new ArrayList<>(entries.size());
        for (BattleMenu.Row entry : entries) {
            rows.add(new MMenuList.Row(entry.label(), BattleHudRules.rowUsable(view, entry.command()),
                    adornment(entry, view)));
        }
        return rows;
    }

    /** What a row shows on its right: decided by what the row <em>is</em>, never by where it sits. */
    static Adornment adornment(BattleMenu.Row entry, BattleView view) {
        if (entry.opensQiArts()) return Adornment.chevron(BattlePalette.QI);
        BattleCommand command = entry.command();
        if (command == null || view == null) return Adornment.NONE;
        if (command == BattleCommand.FOCUS_COMBO) {
            if (view.focusReady()) return Adornment.tag("READY", BattlePalette.FOCUS);
            return Adornment.tag(Math.round(view.focusFraction() * 100f) + "%",
                    MColor.lerp(MStyle.TEXT_DISABLED, BattlePalette.FOCUS, 0.55f));
        }
        if (command == BattleCommand.MEDITATE) return Adornment.count("x" + Math.max(0, view.meditateCharges()));
        if (command.qiCost() > 0) {
            return Adornment.pips(command.qiCost(), BattlePalette.QI, view.qi() >= command.qiCost());
        }
        return Adornment.NONE;
    }
}
