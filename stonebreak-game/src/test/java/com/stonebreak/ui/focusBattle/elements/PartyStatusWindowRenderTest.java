package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleEvent.DamageFlavor;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.rendering.UI.masonryUI.MGauge;
import com.stonebreak.rendering.UI.masonryUI.MPipRow;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.ui.focusBattle.BattlePalette;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E3 over the headless raster rig. Every gauge must visibly track its value, and each probe is
 * confined to the bar rect the widget itself reports, so a bar drawn in the wrong row fails here
 * instead of passing on a whole-window diff. The animations are the widgets' own: the tests drive
 * them with battle events through {@code update}, never by poking numbers in.
 */
class PartyStatusWindowRenderTest {

    private static final int W = 480;
    private static final int H = 180;
    private static final float[] RECT = {20f, 20f, 430f, 140f};
    private static final float SCALE = 1f;
    private static final float DT = 1f / 60f;

    private record Shot(BattleRasterFixture fx, PartyStatusWindow window) {
        float[] hp() { return window.hpGauge().barRect(fx.ui); }
        float[] atb() { return window.atbGauge().barRect(fx.ui); }
        float[] focus() { return window.focusGauge().barRect(fx.ui); }
        float[] row(int i) { return PartyStatusWindow.rowRect(RECT, i, SCALE); }
        int exact(int color, float[] r) {
            return fx.countExactly(color, (int) r[0], (int) r[1], (int) Math.ceil(r[0] + r[2]), (int) Math.ceil(r[1] + r[3]));
        }
    }

    private static Shot shot(PartyStatusWindow window, FakeBattleView view) {
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        window.render(fx.ui, RECT, view, SCALE);
        return new Shot(fx, window);
    }

    private static Shot render(Consumer<FakeBattleView> setup) {
        FakeBattleView view = new FakeBattleView();
        setup.accept(view);
        return shot(new PartyStatusWindow(), view);
    }

    @Test
    void paintingIsDeterministic() {
        Shot a = render(v -> { v.focus = 40f; v.monk.atb = 0.5f; });
        Shot b = render(v -> { v.focus = 40f; v.monk.atb = 0.5f; });
        assertEquals(0, a.fx.diff(b.fx), "same state, same pixels: the diff assertions below rely on it");
        assertTrue(a.fx.countPainted(RECT) > 50_000, "the HUD frame covers its rect");
        assertTrue(a.exact(BattlePalette.ACCENT_MONK, new float[]{RECT[0], RECT[1], RECT[2], 6f}) > 300,
                "with the monk's accent hairline");
    }

    @Test
    void theBarsShareOneColumn() {
        Shot s = render(v -> { });
        float[] hp = s.hp(), atb = s.atb(), focus = s.focus();
        assertEquals(hp[0], atb[0], 0.01f);
        assertEquals(hp[0], focus[0], 0.01f);
        assertEquals(hp[0] + hp[2], atb[0] + atb[2], 0.01f, "the value-less ATB bar ends where the others end");
        assertEquals(hp[0], s.window.qiPips().pipRect(0)[0], 0.01f, "and the pips start under the bars");
        for (int i = 0; i < PartyStatusWindow.ROWS; i++) {
            float[] r = s.row(i);
            assertTrue(r[1] >= RECT[1] && r[1] + r[3] <= RECT[1] + RECT[3] + 0.01f, "row " + i + " inside the frame");
        }
    }

    @Test
    void theHpBarTracksHealthOnTheVitalRamp() {
        Shot full = render(v -> v.monk.hp = v.monk.maxHp);
        Shot empty = render(v -> v.monk.hp = 0f);
        Shot low = render(v -> v.monk.hp = v.monk.maxHp * 0.1f);
        float[] hp = full.hp();

        assertTrue(full.fx.diff(empty.fx, hp) > 1500, "full and empty HP bars differ across the bar");
        assertTrue(full.exact(MStyle.VITAL_OK, hp) > 500, "healthy HP is green");
        assertEquals(0, empty.exact(MStyle.VITAL_OK, hp), "an empty bar shows only its track");
        assertTrue(empty.exact(MStyle.GAUGE_TRACK, hp) > 1500);
        assertTrue(low.exact(MStyle.VITAL_CRIT, hp) > 50, "critical HP is red");
        assertEquals("180/180", full.window.hpGauge().resolvedValueText());
        assertEquals("18/180", low.window.hpGauge().resolvedValueText());
        float[] value = {hp[0] + hp[2], hp[1] - 4f, RECT[0] + RECT[2] - hp[0] - hp[2], hp[3] + 8f};
        assertTrue(full.fx.diff(empty.fx, value) > 20, "the hp/max numbers are drawn beside the bar");
    }

    @Test
    void aHitLeavesATrailAndFlashesTheHpGauge() {
        FakeBattleView view = new FakeBattleView();
        PartyStatusWindow window = new PartyStatusWindow();
        window.update(DT, view, List.of());
        assertTrue(window.hpGauge().ghostFraction() < 0f, "no trail at rest");

        view.monk.hp = view.monk.maxHp * 0.5f;
        window.update(DT, view, List.of(new BattleEvent.DamageDealt(CombatantId.MONK, 90f, DamageFlavor.NORMAL)));
        MGauge hp = window.hpGauge();
        assertEquals(1f, hp.ghostFraction(), 1e-5f, "the trail starts at the pre-hit level");
        assertTrue(hp.flashStrength() > 0.9f, "and the gauge flashes");

        for (int i = 0; i < Math.round(MGauge.DEFAULT_FLASH_SECONDS / DT) + 1; i++) window.update(DT, view, List.of());
        assertEquals(0f, hp.flashStrength(), 0f);
        Shot trailing = shot(window, view);
        float[] bar = trailing.hp();
        assertTrue(trailing.exact(MStyle.GAUGE_GHOST, new float[]{bar[0] + bar[2] * 0.5f, bar[1], bar[2] * 0.5f, bar[3]}) > 300,
                "the pale trail spans the lost half");

        for (int i = 0; i < 90; i++) window.update(DT, view, List.of());
        assertTrue(hp.ghostFraction() < 0f, "and drains away");
        assertEquals(0, shot(window, view).exact(MStyle.GAUGE_GHOST, bar));
    }

    @Test
    void aParryDoesNotFlashAndABlockFlashesLess() {
        FakeBattleView view = new FakeBattleView();
        PartyStatusWindow window = new PartyStatusWindow();
        window.update(DT, view, List.of(new BattleEvent.DamageDealt(CombatantId.MONK, 0f, DamageFlavor.PARRIED)));
        assertEquals(0f, window.hpGauge().flashStrength(), 0f, "a parry is the player's win");
        window.update(DT, view, List.of(new BattleEvent.DamageDealt(CombatantId.ARCHON, 50f, DamageFlavor.NORMAL)));
        assertEquals(0f, window.hpGauge().flashStrength(), 0f, "nor does a blow to the other side");

        window.update(0f, view, List.of(new BattleEvent.DamageDealt(CombatantId.MONK, 5f, DamageFlavor.BLOCKED)));
        Shot blocked = shot(window, view);
        PartyStatusWindow other = new PartyStatusWindow();
        other.update(0f, view, List.of(new BattleEvent.DamageDealt(CombatantId.MONK, 5f, DamageFlavor.CRITICAL)));
        Shot critical = shot(other, view);
        assertTrue(blocked.fx.diff(critical.fx, blocked.hp()) > 1000, "the flash is as strong as the blow");
    }

    @Test
    void theAtbGaugeFillsBlinksWhenFullAndFlashesOnTheTurn() {
        Shot empty = render(v -> v.monk.atb = 0f);
        Shot half = render(v -> v.monk.atb = 0.5f);
        Shot full = render(v -> v.monk.atb = 1f);
        float[] atb = full.atb();

        assertTrue(full.fx.diff(empty.fx, atb) > 1000, "full and empty ATB gauges differ");
        assertTrue(half.exact(BattlePalette.ATB, atb) > 300, "a filling gauge is cyan");
        assertEquals(0, full.exact(BattlePalette.ATB, atb), "a full gauge leaves plain cyan for the ready glow");
        assertTrue(full.window.atbGauge().glowStrength() > 0f);
        assertEquals(0f, half.window.atbGauge().glowStrength(), 0f);

        FakeBattleView view = new FakeBattleView();
        view.monk.atb = 1f;
        PartyStatusWindow window = new PartyStatusWindow();
        window.update(0f, view, List.of(new BattleEvent.TurnReady(CombatantId.ARCHON)));
        assertEquals(0f, window.atbGauge().flashStrength(), 0f, "the Archon's turn is not the monk's");
        window.update(0f, view, List.of(new BattleEvent.TurnReady(CombatantId.MONK)));
        assertEquals(1f, window.atbGauge().flashStrength(), 1e-6f);
    }

    @Test
    void theFocusGaugeFillsShowsItsPercentageAndShimmersWhenReady() {
        Shot empty = render(v -> v.focus = 0f);
        Shot half = render(v -> v.focus = 50f);
        Shot full = render(v -> v.focus = v.maxFocus);
        float[] focus = full.focus();

        assertTrue(full.fx.diff(empty.fx, focus) > 1500, "full and empty Focus gauges differ");
        assertTrue(half.exact(BattlePalette.FOCUS, focus) > 400, "Focus is gold");
        assertEquals("50%", half.window.focusGauge().resolvedValueText());
        assertEquals("100%", full.window.focusGauge().resolvedValueText());
        assertTrue(full.window.focusGauge().shimmerPhase() >= 0f, "a ready gauge shimmers");
        assertTrue(half.window.focusGauge().shimmerPhase() < 0f, "and nothing until it is full");

        FakeBattleView view = new FakeBattleView();
        view.focus = view.maxFocus;
        PartyStatusWindow window = new PartyStatusWindow();
        window.update(DT, view, List.of());
        Shot before = shot(window, view);
        window.update(0.4f, view, List.of());
        assertTrue(shot(window, view).fx.diff(before.fx, focus) > 100, "the band really moves across the bar");
        window.update(0f, view, List.of(new BattleEvent.FocusFull()));
        assertEquals(1f, window.focusGauge().flashStrength(), 1e-6f, "reaching the maximum bursts on the bar");
    }

    @Test
    void qiPipsShowSpentAndAvailableSlotsAndAnimateThemselves() {
        Shot none = render(v -> v.qi = 0);
        Shot all = render(v -> v.qi = 5);
        Shot three = render(v -> v.qi = 3);
        float[] qi = none.row(PartyStatusWindow.ROW_QI);

        assertTrue(all.fx.diff(none.fx, qi) > 200, "0 and 5 Qi differ");
        assertEquals(0, none.exact(BattlePalette.QI, qi), "no jade without Qi");
        int jadeAll = all.exact(BattlePalette.QI, qi);
        int jadeThree = three.exact(BattlePalette.QI, qi);
        assertTrue(jadeAll > 100, "filled pips are jade");
        assertTrue(jadeThree > 0 && jadeThree < jadeAll, "3 of 5 pips is between the two");
        assertTrue(none.exact(MStyle.GAUGE_TRACK, qi) > 100, "empty slots stay visible as sockets");

        FakeBattleView view = new FakeBattleView();
        view.qi = 4;
        PartyStatusWindow window = new PartyStatusWindow();
        window.update(DT, view, List.of());
        MPipRow pips = window.qiPips();
        assertEquals(0f, pips.popAmount(), 0f, "the first binding never animates");
        view.qi = 2;
        window.update(0f, view, List.of(new BattleEvent.QiChanged(-2, 2)));
        assertEquals(1f, pips.spendAmount(), 1e-6f, "a spend flashes the pips it emptied");
        view.qi = 3;
        window.update(0f, view, List.of(new BattleEvent.QiChanged(1, 3)));
        assertEquals(1f, pips.popAmount(), 1e-6f, "a gain pops the newest pip");

        window.reset();
        view.qi = 0;
        window.update(0f, view, List.of());
        assertEquals(0f, pips.spendAmount(), 0f, "a new fight starts clean");
    }

    @Test
    void statusChipsAppearWithTheirTimers() {
        Shot bare = render(v -> { });
        Shot haste = render(v -> v.monk.statuses.add(new StatusView(BattleStatus.HASTE, 12f, 1)));
        Shot hasteLater = render(v -> v.monk.statuses.add(new StatusView(BattleStatus.HASTE, 3f, 1)));
        Shot guard = render(v -> v.monk.statuses.add(new StatusView(BattleStatus.GUARDING, -1f, 1)));
        float[] nameRow = bare.row(PartyStatusWindow.ROW_NAME);

        assertTrue(haste.fx.diff(bare.fx, nameRow) > 150, "a status chip appears on the name row");
        assertTrue(haste.fx.diff(hasteLater.fx, nameRow) > 5, "its timer counts down");
        assertEquals("12s", haste.window.chipRow().chips().get(0).trailing());
        assertEquals("", guard.window.chipRow().chips().get(0).trailing(), "an untimed status has no timer");
        assertTrue(guard.fx.diff(bare.fx, nameRow) > 100, "but still gets a chip");
        // Chips are right-aligned: the left half of the row (the name) is untouched.
        assertEquals(0, haste.fx.diff(bare.fx, (int) nameRow[0], (int) nameRow[1],
                (int) (nameRow[0] + nameRow[2] / 2f), (int) (nameRow[1] + nameRow[3])));
    }

    @Test
    void queuedSurgeHitsGetTheirOwnChip() {
        Shot bare = render(v -> { });
        Shot surge = render(v -> v.queuedSurgeHits = 2);
        Shot surgeWithStatus = render(v -> {
            v.queuedSurgeHits = 2;
            v.monk.statuses.add(new StatusView(BattleStatus.SURGE, -1f, 2));
        });
        assertTrue(surge.fx.diff(bare.fx, bare.row(PartyStatusWindow.ROW_NAME)) > 150, "the Surge chip appears");
        assertEquals(1, surgeWithStatus.window.chipRow().chips().size());
        assertEquals(0, surge.fx.diff(surgeWithStatus.fx), "and is not duplicated when the model also lists SURGE");
    }

    @Test
    void theWindowDrawsWhereverItIsPut() {
        FakeBattleView view = new FakeBattleView();
        PartyStatusWindow window = new PartyStatusWindow();
        BattleRasterFixture still = new BattleRasterFixture(W, H);
        window.render(still.ui, RECT, view, SCALE);
        BattleRasterFixture shaken = new BattleRasterFixture(W, H);
        window.render(shaken.ui, new float[]{RECT[0] + 6f, RECT[1], RECT[2], RECT[3]}, view, SCALE);
        assertTrue(shaken.diff(still) > 500, "a shake offset redraws the window elsewhere");
        assertEquals(0, shaken.countPainted(0, 0, (int) RECT[0] + 4, H), "to the right, not the left");
    }

    @Test
    void itScalesAndSurvivesDegenerateInput() {
        FakeBattleView view = new FakeBattleView();
        PartyStatusWindow window = new PartyStatusWindow();
        BattleRasterFixture big = new BattleRasterFixture(960, 360);
        window.render(big.ui, new float[]{20f, 20f, 860f, 280f}, view, 2f);
        float[] bar = window.hpGauge().barRect(big.ui);
        assertTrue(bar[3] > 24f, "the bar grew with the scale: " + bar[3]);

        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        window.render(fx.ui, new float[]{20f, 20f, 0f, 0f}, view, SCALE);
        window.render(fx.ui, RECT, null, SCALE);
        window.render(fx.ui, RECT, view, 0f);
        window.render(null, RECT, view, SCALE);
        window.update(Float.NaN, null, null);
        assertEquals(0, fx.countPainted(0, 0, W, H));
    }
}
