package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleMenu;
import com.stonebreak.battle.api.CommandAvailability;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.rendering.UI.masonryUI.MMenuList;
import com.stonebreak.rendering.UI.masonryUI.MMenuList.Adornment;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.ui.focusBattle.BattleMenuState;
import com.stonebreak.ui.focusBattle.BattlePalette;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1 + E2: the command menu as a composition over two {@link MMenuList}s. Covers the row data the
 * window builds from the view, the pixels that data produces (probed row by row through the lists'
 * own {@code rowRect}), the static state, and hit-testing through {@code rowAt}.
 */
class CommandWindowRenderTest {

    private static final int W = 560;
    private static final int H = 260;
    private static final float[] RECT = {20f, 20f, 250f, 210f};
    private static final float[] SUB_RECT = {276f, 84f, 236f, 114f};
    private static final float SCALE = 1f;
    private static final int ROWS = BattleMenu.ROOT.size();

    private static final int STRIKE = 0;
    private static final int QI_ARTS = FocusBattleLayout.qiArtsRowIndex();
    private static final int MEDITATE = 3;
    private static final int FOCUS_COMBO = 5;

    /** A rendered window plus the instance that drew it (its lists answer geometry questions). */
    private record Shot(BattleRasterFixture fx, CommandWindow window) {
        float[] row(int i) { return window.rootList().rowRect(i); }
        float[] subRow(int i) { return window.artsList().rowRect(i); }
        int exact(int color, float[] r) {
            return fx.countExactly(color, (int) r[0], (int) r[1], (int) Math.ceil(r[0] + r[2]), (int) Math.ceil(r[1] + r[3]));
        }
    }

    private static Shot command(Consumer<FakeBattleView> setup, Consumer<BattleMenuState> cursor) {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        setup.accept(view);
        BattleMenuState menu = new BattleMenuState();
        cursor.accept(menu);
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        CommandWindow window = new CommandWindow();
        window.render(fx.ui, RECT, SUB_RECT, view, menu, SCALE, 1f, 1f);
        return new Shot(fx, window);
    }

    private static Shot submenu(Consumer<FakeBattleView> setup, int cursor) {
        return command(setup, m -> {
            m.selectRoot(QI_ARTS);
            m.openSubmenu();
            m.selectSubmenu(cursor);
        });
    }

    // ── row data ─────────────────────────────────────────────────────────────

    @Test
    void rowsAreBuiltAsDataFromTheMenuAndTheView() {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        view.focus = 42f;
        view.meditateCharges = 2;
        view.qi = 1;
        view.unavailable.put(BattleCommand.STRIKE, "Busy.");
        List<MMenuList.Row> root = CommandWindow.rows(BattleMenu.ROOT, view);

        assertEquals(ROWS, root.size());
        for (int i = 0; i < ROWS; i++) assertEquals(BattleMenu.ROOT.get(i).label(), root.get(i).label());
        assertFalse(root.get(STRIKE).enabled(), "the model's refusal dims the row");
        assertTrue(root.get(1).enabled());
        assertEquals(Adornment.chevron(BattlePalette.QI), root.get(QI_ARTS).adornment(), "the opener carries a chevron");
        assertEquals(Adornment.count("x2"), root.get(MEDITATE).adornment());
        assertEquals("42%", assertInstanceOf(Adornment.Tag.class, root.get(FOCUS_COMBO).adornment()).text());
        assertEquals(Adornment.NONE, root.get(STRIKE).adornment());

        view.focus = view.maxFocus;
        assertEquals(Adornment.tag("READY", BattlePalette.FOCUS),
                CommandWindow.rows(BattleMenu.ROOT, view).get(FOCUS_COMBO).adornment());

        List<MMenuList.Row> arts = CommandWindow.rows(BattleMenu.QI_ARTS, view);
        for (int i = 0; i < arts.size(); i++) {
            BattleCommand command = BattleMenu.QI_ARTS.get(i).command();
            assertEquals(Adornment.pips(command.qiCost(), BattlePalette.QI, view.qi >= command.qiCost()),
                    arts.get(i).adornment(), command + " shows its Qi cost, greyed when unaffordable");
        }
    }

    @Test
    void oneRuleDimsBothLists() {
        // Resting: "not your turn" alone never dims; a resource shortfall dims in both states.
        FakeBattleView view = new FakeBattleView();
        for (BattleCommand command : BattleCommand.values()) {
            view.unavailable.put(command, CommandAvailability.NOT_YOUR_TURN);
        }
        view.unavailable.put(BattleCommand.MEDITATE, "No charges left");
        view.unavailable.put(BattleCommand.SWIFT_STEP, "Not enough Qi");
        List<MMenuList.Row> root = CommandWindow.rows(BattleMenu.ROOT, view);
        List<MMenuList.Row> arts = CommandWindow.rows(BattleMenu.QI_ARTS, view);
        assertTrue(root.get(STRIKE).enabled());
        assertFalse(root.get(MEDITATE).enabled());
        assertTrue(arts.get(0).enabled());
        assertFalse(arts.get(1).enabled());
    }

    // ── E1 pixels ────────────────────────────────────────────────────────────

    @Test
    void paintingIsDeterministicAndFillsTheWindow() {
        Shot a = command(v -> { }, m -> { });
        Shot b = command(v -> { }, m -> { });
        assertEquals(0, a.fx.diff(b.fx));
        assertTrue(a.fx.countPainted(RECT) > 40_000, "the HUD frame covers the window rect");
        for (int i = 0; i < ROWS; i++) {
            assertEquals(0, a.exact(BattleRasterFixture.BACKGROUND, a.row(i)), "row " + i + " sits on the frame");
        }
        assertEquals(0, a.fx.countPainted(SUB_RECT), "the submenu stays closed until opened");
    }

    @Test
    void theSelectedRowIsHighlightedInTheHouseStyleAndCarriesTheHand() {
        Shot first = command(v -> { }, m -> { });
        Shot second = command(v -> { }, m -> m.moveDown());

        assertTrue(first.fx.diff(second.fx, first.row(0)) > 800, "row 0: selected vs not");
        assertTrue(first.fx.diff(second.fx, first.row(1)) > 800, "row 1: not vs selected");
        for (int i = 2; i < ROWS; i++) {
            assertEquals(0, first.fx.diff(second.fx, first.row(i)), "row " + i + " is untouched by the cursor move");
        }

        float[] r = first.row(0);
        assertTrue(first.exact(MStyle.ROW_CURRENT, r) > 2000, "the library's current-row fill");
        assertEquals(0, second.exact(MStyle.ROW_CURRENT, r));
        assertTrue(first.exact(MStyle.TEXT_ACCENT, new float[]{r[0], r[1] + 4f, 3f, r[3] - 8f}) > 40,
                "with the gold bar on its left edge");
        float[] gutter = {r[0] + 4f, r[1], 30f, r[3]};
        assertTrue(first.exact(MStyle.TEXT_PRIMARY, gutter) > 30, "the hand sits in the selected row's gutter");
        assertEquals(0, second.exact(MStyle.TEXT_PRIMARY, gutter), "and only there");
    }

    @Test
    void anUnavailableRowIsDimmed() {
        Shot enabled = command(v -> { }, m -> m.selectRoot(FOCUS_COMBO));
        Shot disabled = command(v -> v.unavailable.put(BattleCommand.STRIKE, "Busy."), m -> m.selectRoot(FOCUS_COMBO));
        assertTrue(enabled.fx.diff(disabled.fx, enabled.row(STRIKE)) > 60);
        assertTrue(disabled.exact(MStyle.TEXT_DISABLED, disabled.row(STRIKE)) > 40, "in the library's disabled colour");
        assertEquals(0, enabled.exact(MStyle.TEXT_DISABLED, enabled.row(STRIKE)));
        assertEquals(0, enabled.fx.diff(disabled.fx, enabled.row(1)), "only the affected row changes");
    }

    @Test
    void theFocusComboRowGlowsGoldWhenReady() {
        Shot charging = command(v -> v.focus = 86f, m -> { });
        Shot nearly = command(v -> v.focus = 12f, m -> { });
        Shot ready = command(v -> v.focus = v.maxFocus, m -> { });
        float[] r = ready.row(FOCUS_COMBO);

        assertTrue(charging.fx.diff(nearly.fx, r) > 10, "the percentage is part of the row");
        assertTrue(ready.fx.diff(charging.fx, r) > 2500, "the ready glow floods the whole row");
        assertTrue(ready.exact(BattlePalette.FOCUS, r) > 40, "READY and the label are gold");
        assertEquals(0, ready.fx.diff(charging.fx, ready.row(4)), "the glow stays on its own row");
    }

    @Test
    void meditateShowsItsRemainingCharges() {
        Shot three = command(v -> v.meditateCharges = 3, m -> { });
        Shot one = command(v -> v.meditateCharges = 1, m -> { });
        float[] r = three.row(MEDITATE);
        assertTrue(three.fx.diff(one.fx, r) > 5, "x3 and x1 read differently");
        assertEquals(0, three.fx.diff(one.fx, (int) r[0], (int) r[1], (int) (r[0] + r[2] / 2f), (int) (r[1] + r[3])),
                "the count is right-aligned; the label half is unchanged");
    }

    @Test
    void theQiArtsRowCarriesAChevronAndStaysMarkedWhileItsSubmenuIsOpen() {
        Shot idle = command(v -> { }, m -> { });
        float[] r = idle.row(QI_ARTS);
        assertTrue(idle.exact(BattlePalette.QI, new float[]{r[0] + r[2] - 34f, r[1], 34f, r[3]}) > 15,
                "a jade chevron marks the submenu opener");

        Shot selected = command(v -> { }, m -> m.selectRoot(QI_ARTS));
        Shot held = command(v -> { }, m -> { m.selectRoot(QI_ARTS); m.openSubmenu(); });
        assertTrue(held.fx.diff(idle.fx, r) > 800, "the opener stays marked while the submenu has the cursor");
        assertTrue(held.fx.diff(selected.fx, r) > 800, "but quieter than a live selection");
        assertEquals(0, held.exact(MStyle.ROW_CURRENT, r), "the live fill has moved into the submenu");
        assertTrue(held.exact(MStyle.ROW_CURRENT, held.subRow(0)) > 2000);
    }

    // ── static state ─────────────────────────────────────────────────────────

    @Test
    void betweenTurnsTheWindowRestsVeiledWithNoCursor() {
        Shot live = command(v -> { }, m -> { });
        Shot resting = command(v -> v.commandWindowOpen = false, m -> { });
        assertTrue(resting.fx.countPainted(RECT) > 40_000, "still on screen");
        assertTrue(resting.fx.diff(live.fx, RECT) > 10_000, "veiled");
        assertEquals(0, resting.exact(MStyle.ROW_CURRENT, RECT), "no selection");
        assertFalse(resting.window.rootList().active());
        assertEquals(1f, resting.window.rootList().veil(), 1e-6f);

        // Waking: live at once (cursor shown), the veil fading with the wake value.
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        CommandWindow waking = new CommandWindow();
        waking.render(fx.ui, RECT, SUB_RECT, view, new BattleMenuState(), SCALE, 0.25f, 1f);
        assertTrue(waking.rootList().active());
        assertEquals(0.75f, waking.rootList().veil(), 1e-6f);
    }

    @Test
    void staticMeansStatic() {
        FakeBattleView view = new FakeBattleView();
        view.focus = view.maxFocus;   // the READY glow is the one thing that pulses while live
        BattleMenuState menu = new BattleMenuState();
        CommandWindow window = new CommandWindow();

        BattleRasterFixture a = new BattleRasterFixture(W, H);
        window.render(a.ui, RECT, SUB_RECT, view, menu, SCALE, 1f, 1f);
        for (int i = 0; i < 20; i++) window.update(0.05f, view, menu);
        BattleRasterFixture b = new BattleRasterFixture(W, H);
        window.render(b.ui, RECT, SUB_RECT, view, menu, SCALE, 1f, 1f);
        assertEquals(0, a.diff(b), "nothing in the resting window animates, the READY glow included");

        view.commandWindowOpen = true;
        BattleRasterFixture c = new BattleRasterFixture(W, H);
        window.render(c.ui, RECT, SUB_RECT, view, menu, SCALE, 1f, 1f);
        window.update(0.3f, view, menu);
        BattleRasterFixture d = new BattleRasterFixture(W, H);
        window.render(d.ui, RECT, SUB_RECT, view, menu, SCALE, 1f, 1f);
        assertTrue(c.diff(d, RECT) > 50, "while the live one bobs its cursor and pulses the glow");

        // The list cursor holds still while the target cursor has the player's attention.
        menu.beginTargeting(BattleCommand.STRIKE);
        window.update(0.3f, view, menu);
        BattleRasterFixture e = new BattleRasterFixture(W, H);
        window.render(e.ui, RECT, SUB_RECT, view, menu, SCALE, 1f, 1f);
        assertEquals(0, d.diff(e, RECT));
    }

    // ── E2 ───────────────────────────────────────────────────────────────────

    @Test
    void submenuRowsShowTheirCostAsJadePips() {
        Shot rich = submenu(v -> v.qi = 5, 0);
        float[] stun = rich.subRow(0), step = rich.subRow(1);
        int jadeStun = rich.exact(BattlePalette.QI, new float[]{stun[0] + stun[2] / 2f, stun[1], stun[2] / 2f, stun[3]});
        int jadeStep = rich.exact(BattlePalette.QI, new float[]{step[0] + step[2] / 2f, step[1], step[2] / 2f, step[3]});
        assertTrue(jadeStep > 20, "a 1-Qi art shows a pip");
        assertTrue(jadeStun > jadeStep * 3 / 2, "a 2-Qi art shows more pip than a 1-Qi art");
    }

    @Test
    void unaffordablePipsLoseTheirJadeAndAModelRefusalDimsTheLabel() {
        Shot rich = submenu(v -> v.qi = 5, 2);
        Shot oneQi = submenu(v -> v.qi = 1, 2);
        assertEquals(0, oneQi.exact(BattlePalette.QI, oneQi.subRow(0)), "Stunning Strike (2 Qi) greys its pips at 1 Qi");
        assertEquals(0, rich.fx.diff(oneQi.fx, rich.subRow(1)), "Swift Step (1 Qi) is still affordable");

        Shot refused = submenu(v -> {
            v.qi = 5;
            v.unavailable.put(BattleCommand.SWIFT_STEP, "Already hasted.");
        }, 2);
        assertTrue(rich.fx.diff(refused.fx, rich.subRow(1)) > 40, "the label dims");
        assertTrue(refused.exact(MStyle.TEXT_DISABLED, refused.subRow(1)) > 40);
        assertEquals(0, rich.fx.diff(refused.fx, rich.subRow(0)));
    }

    @Test
    void theSubmenuCursorFollowsItsIndex() {
        Shot top = submenu(v -> { }, 0);
        Shot bottom = submenu(v -> { }, 2);
        assertTrue(top.fx.diff(bottom.fx, top.subRow(0)) > 800);
        assertTrue(top.fx.diff(bottom.fx, top.subRow(2)) > 800);
        assertEquals(0, top.fx.diff(bottom.fx, top.subRow(1)));
    }

    @Test
    void aSlidingSubmenuNeverShowsThroughTheCommandWindow() {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        BattleMenuState menu = new BattleMenuState();
        menu.selectRoot(QI_ARTS);
        menu.openSubmenu();
        // Drawn alone, a half-slid submenu would overlap the command window's right half.
        float[] touching = {RECT[0] + RECT[2], SUB_RECT[1], SUB_RECT[2], SUB_RECT[3]};
        BattleRasterFixture tucked = new BattleRasterFixture(W, H);
        new CommandWindow().render(tucked.ui, RECT, touching, view, menu, SCALE, 1f, 0f);
        BattleRasterFixture inPlace = new BattleRasterFixture(W, H);
        new CommandWindow().render(inPlace.ui, RECT, touching, view, menu, SCALE, 1f, 1f);
        assertEquals(0, tucked.diff(inPlace, RECT), "clipped at the command window's right edge");
        assertTrue(tucked.diff(inPlace, touching) > 2000, "while it really is tucked half-way in");
    }

    // ── hit-testing ──────────────────────────────────────────────────────────

    @Test
    void hitTestingUsesTheRectsTheListsDraw() {
        CommandWindow window = new CommandWindow();
        for (int i = 0; i < ROWS; i++) {
            float[] r = FocusBattleLayout.rowRect(RECT, i, ROWS, SCALE);
            assertEquals(i, window.rootRowAt(r[0] + r[2] / 2f, r[1] + r[3] / 2f, RECT, SCALE));
            float[] drawn = window.rootList().rowRect(i);
            for (int k = 0; k < 4; k++) assertEquals(r[k], drawn[k], 1e-4f, "one slot formula");
        }
        for (int i = 0; i < BattleMenu.QI_ARTS.size(); i++) {
            float[] r = FocusBattleLayout.rowRect(SUB_RECT, i, BattleMenu.QI_ARTS.size(), SCALE);
            assertEquals(i, window.artsRowAt(r[0] + r[2] / 2f, r[1] + r[3] / 2f, SUB_RECT, SCALE));
        }
        assertEquals(-1, window.rootRowAt(RECT[0] - 5f, RECT[1] + 40f, RECT, SCALE));
        assertEquals(-1, window.rootRowAt(RECT[0] + 40f, RECT[1] + 2f, RECT, SCALE), "the frame's padding is not a row");
        assertEquals(-1, window.artsRowAt(RECT[0] + 40f, RECT[1] + 40f, SUB_RECT, SCALE));
    }
}
