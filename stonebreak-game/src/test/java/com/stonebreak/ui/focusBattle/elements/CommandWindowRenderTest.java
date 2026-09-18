package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleMenu;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.ui.focusBattle.BattleMenuState;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pixel tests for E1 (command window) and E2 (Qi Arts submenu), probed row by row. */
class CommandWindowRenderTest {

    private static final int W = 300;
    private static final int H = 260;
    private static final float[] RECT = {20f, 20f, 250f, 210f};
    private static final float SCALE = 1f;
    private static final int ROWS = BattleMenu.ROOT.size();

    private static final int STRIKE = 0;
    private static final int QI_ARTS = FocusBattleLayout.qiArtsRowIndex();
    private static final int MEDITATE = 3;
    private static final int FOCUS_COMBO = 5;

    private static BattleRasterFixture command(Consumer<FakeBattleView> setup, Consumer<BattleMenuState> cursor) {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        setup.accept(view);
        BattleMenuState menu = new BattleMenuState();
        cursor.accept(menu);
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        CommandWindow.paint(fx.ui, fx.canvas, RECT, view, menu, SCALE);
        return fx;
    }

    private static BattleRasterFixture submenu(Consumer<FakeBattleView> setup, int cursor) {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        setup.accept(view);
        BattleMenuState menu = new BattleMenuState();
        menu.selectRoot(QI_ARTS);
        menu.openSubmenu();
        menu.selectSubmenu(cursor);
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        QiArtsSubmenu.paint(fx.ui, fx.canvas, SUB_RECT, view, menu, SCALE);
        return fx;
    }

    private static final float[] SUB_RECT = {20f, 20f, 236f, 114f};

    private static float[] row(int i) {
        return FocusBattleLayout.rowRect(RECT, i, ROWS, SCALE);
    }

    private static float[] subRow(int i) {
        return FocusBattleLayout.rowRect(SUB_RECT, i, BattleMenu.QI_ARTS.size(), SCALE);
    }

    @Test
    void paintingIsDeterministicAndFillsTheWindow() {
        BattleRasterFixture a = command(v -> { }, m -> { });
        BattleRasterFixture b = command(v -> { }, m -> { });
        assertEquals(0, a.diff(b));
        assertTrue(a.countPainted(RECT) > 40_000, "slate glass covers the window rect");
        for (int i = 0; i < ROWS; i++) {
            // Probe right of the cursor gutter so the hand does not count as a label.
            float[] r = row(i);
            assertTrue(a.countExactly(BattleRasterFixture.BACKGROUND, (int) r[0], (int) r[1],
                    (int) (r[0] + r[2]), (int) (r[1] + r[3])) == 0, "row " + i + " sits on the window");
        }
    }

    @Test
    void theSelectedRowIsHighlightedAndCarriesTheCursor() {
        BattleRasterFixture first = command(v -> { }, m -> { });
        BattleRasterFixture second = command(v -> { }, m -> m.moveDown());

        assertTrue(first.diff(second, row(0)) > 800, "row 0: selected vs not");
        assertTrue(first.diff(second, row(1)) > 800, "row 1: not vs selected");
        for (int i = 2; i < ROWS; i++) {
            assertEquals(0, first.diff(second, row(i)), "row " + i + " is untouched by the cursor move");
        }

        float[] r = row(0);
        int gx1 = (int) (r[0] + MenuRowPainter.gutter(r, SCALE));
        assertTrue(first.countExactly(FocusBattleTheme.CURSOR, (int) r[0], (int) r[1], gx1, (int) (r[1] + r[3])) > 30,
                "the white hand sits in the selected row's gutter");
        assertEquals(0, second.countExactly(FocusBattleTheme.CURSOR, (int) r[0], (int) r[1], gx1, (int) (r[1] + r[3])),
                "and only there");
    }

    @Test
    void anUnavailableRowIsDimmed() {
        BattleRasterFixture enabled = command(v -> { }, m -> m.selectRoot(FOCUS_COMBO));
        BattleRasterFixture disabled = command(v -> v.unavailable.put(BattleCommand.STRIKE, "Busy."),
                m -> m.selectRoot(FOCUS_COMBO));
        assertTrue(enabled.diff(disabled, row(STRIKE)) > 60, "a disabled label differs from an enabled one");
        assertEquals(0, enabled.diff(disabled, row(1)), "only the affected row changes");
    }

    @Test
    void theFocusComboRowGlowsGoldWhenReady() {
        BattleRasterFixture charging = command(v -> v.focus = 86f, m -> { });
        BattleRasterFixture nearly = command(v -> v.focus = 12f, m -> { });
        BattleRasterFixture ready = command(v -> v.focus = v.maxFocus, m -> { });
        float[] r = row(FOCUS_COMBO);

        assertTrue(charging.diff(nearly, r) > 10, "the percentage is part of the row");
        assertTrue(ready.diff(charging, r) > 2500, "the ready glow floods the whole row");
        assertTrue(ready.countExactly(FocusBattleTheme.FOCUS, (int) r[0], (int) r[1], (int) (r[0] + r[2]),
                (int) (r[1] + r[3])) > 40, "READY and the label are gold");
        assertEquals(0, ready.diff(charging, row(4)), "the glow stays on its own row");
    }

    @Test
    void meditateShowsItsRemainingCharges() {
        BattleRasterFixture three = command(v -> v.meditateCharges = 3, m -> { });
        BattleRasterFixture one = command(v -> v.meditateCharges = 1, m -> { });
        float[] r = row(MEDITATE);
        assertTrue(three.diff(one, r) > 5, "x3 and x1 read differently");
        assertEquals(0, three.diff(one, (int) r[0], (int) r[1], (int) (r[0] + r[2] / 2f), (int) (r[1] + r[3])),
                "the count is right-aligned; the label half is unchanged");
    }

    @Test
    void theQiArtsRowCarriesAChevronAndStaysHighlightedWhileItsSubmenuIsOpen() {
        BattleRasterFixture idle = command(v -> { }, m -> { });
        float[] r = row(QI_ARTS);
        int right = (int) (r[0] + r[2]);
        assertTrue(idle.countExactly(FocusBattleTheme.QI, right - 34, (int) r[1], right, (int) (r[1] + r[3])) > 15,
                "a jade chevron marks the submenu opener");

        BattleRasterFixture selected = command(v -> { }, m -> m.selectRoot(QI_ARTS));
        BattleRasterFixture held = command(v -> { }, m -> { m.selectRoot(QI_ARTS); m.openSubmenu(); });
        assertTrue(held.diff(idle, r) > 800, "the opener stays marked while the submenu has the cursor");
        assertTrue(held.diff(selected, r) > 800, "but dimmer than a live selection");
    }

    // ── E2 ───────────────────────────────────────────────────────────────────

    @Test
    void submenuRowsShowTheirCostAsJadePips() {
        BattleRasterFixture rich = submenu(v -> v.qi = 5, 0);
        int jadeStun = 0, jadeStep = 0;
        float[] stun = subRow(0), step = subRow(1);
        jadeStun = rich.countExactly(FocusBattleTheme.QI, (int) (stun[0] + stun[2] / 2f), (int) stun[1],
                (int) (stun[0] + stun[2]), (int) (stun[1] + stun[3]));
        jadeStep = rich.countExactly(FocusBattleTheme.QI, (int) (step[0] + step[2] / 2f), (int) step[1],
                (int) (step[0] + step[2]), (int) (step[1] + step[3]));
        assertTrue(jadeStep > 20, "a 1-Qi art shows a pip");
        assertTrue(jadeStun > jadeStep * 3 / 2, "a 2-Qi art shows more pip than a 1-Qi art");
    }

    @Test
    void unaffordableArtsDim() {
        BattleRasterFixture rich = submenu(v -> v.qi = 5, 2);
        BattleRasterFixture oneQi = submenu(v -> v.qi = 1, 2);
        BattleRasterFixture broke = submenu(v -> v.qi = 0, 2);

        assertTrue(rich.diff(oneQi, subRow(0)) > 80, "Stunning Strike (2 Qi) dims at 1 Qi");
        assertEquals(0, rich.diff(oneQi, subRow(1)), "Swift Step (1 Qi) is still affordable");
        assertTrue(rich.diff(broke, subRow(1)) > 80, "and dims at 0 Qi");
        float[] r = subRow(0);
        assertEquals(0, broke.countExactly(FocusBattleTheme.QI, (int) r[0], (int) r[1], (int) (r[0] + r[2]),
                (int) (r[1] + r[3])), "unaffordable pips lose their jade");
    }

    @Test
    void aModelRefusalDimsAnAffordableArt() {
        BattleRasterFixture ok = submenu(v -> v.qi = 5, 2);
        BattleRasterFixture refused = submenu(v -> {
            v.qi = 5;
            v.unavailable.put(BattleCommand.SWIFT_STEP, "Already hasted.");
        }, 2);
        assertTrue(ok.diff(refused, subRow(1)) > 40, "the label dims");
        assertEquals(0, ok.diff(refused, subRow(0)));
    }

    @Test
    void theSubmenuCursorFollowsItsIndex() {
        BattleRasterFixture top = submenu(v -> { }, 0);
        BattleRasterFixture bottom = submenu(v -> { }, 2);
        assertTrue(top.diff(bottom, subRow(0)) > 800);
        assertTrue(top.diff(bottom, subRow(2)) > 800);
        assertEquals(0, top.diff(bottom, subRow(1)));
    }
}
