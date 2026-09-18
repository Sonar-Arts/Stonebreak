package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel tests for E3 over the headless raster rig: every gauge must visibly track its value, and
 * each probe is confined to the layout cell that gauge owns — so a bar drawn in the wrong row fails
 * here instead of passing on a whole-window diff.
 */
class PartyStatusWindowRenderTest {

    private static final int W = 480;
    private static final int H = 180;
    private static final float[] RECT = {20f, 20f, 430f, 140f};
    private static final float SCALE = 1f;

    private static BattleRasterFixture render(Consumer<FakeBattleView> setup) {
        return render(setup, BattleHudAnimState.NEUTRAL);
    }

    private static BattleRasterFixture render(Consumer<FakeBattleView> setup, BattleHudAnimState anim) {
        FakeBattleView view = new FakeBattleView();
        setup.accept(view);
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        PartyStatusWindow.paint(fx.ui, fx.canvas, RECT, view, SCALE, anim);
        return fx;
    }

    private static float[] bar(int row) {
        return FocusBattleLayout.partyBarRect(RECT, row, SCALE);
    }

    private static int exact(BattleRasterFixture fx, int color, float[] r) {
        return fx.countExactly(color, (int) r[0], (int) r[1], (int) Math.ceil(r[0] + r[2]), (int) Math.ceil(r[1] + r[3]));
    }

    @Test
    void paintingIsDeterministic() {
        BattleRasterFixture a = render(v -> { v.focus = 40f; v.monk.atb = 0.5f; });
        BattleRasterFixture b = render(v -> { v.focus = 40f; v.monk.atb = 0.5f; });
        assertEquals(0, a.diff(b), "same state, same pixels — the diff assertions below rely on it");
        assertTrue(a.countPainted(RECT) > 20_000, "the slate window covers its rect");
    }

    @Test
    void theHpBarTracksHealthAndTurnsRedWhenLow() {
        BattleRasterFixture full = render(v -> v.monk.hp = v.monk.maxHp);
        BattleRasterFixture empty = render(v -> v.monk.hp = 0f);
        BattleRasterFixture low = render(v -> v.monk.hp = v.monk.maxHp * 0.1f);
        float[] hp = bar(FocusBattleLayout.PARTY_ROW_HP);

        assertTrue(full.diff(empty, hp) > 1500, "full and empty HP bars differ across the bar");
        assertTrue(exact(full, FocusBattleTheme.HP_HIGH, hp) > 500, "healthy HP is green");
        assertEquals(0, exact(empty, FocusBattleTheme.HP_HIGH, hp), "an empty bar shows only its track");
        assertTrue(exact(low, FocusBattleTheme.HP_LOW, hp) > 50, "critical HP is red");
        assertTrue(full.diff(empty, FocusBattleLayout.partyValueRect(RECT, FocusBattleLayout.PARTY_ROW_HP, SCALE)) > 20,
                "the hp/max numbers change too");
    }

    @Test
    void theHpGhostTrailPaintsBetweenHealthAndItsOldValue() {
        BattleHudAnimState anim = new BattleHudAnimState();
        anim.monkGhostHpFraction = 0.9f;
        BattleRasterFixture trail = render(v -> v.monk.hp = v.monk.maxHp * 0.5f, anim);
        BattleRasterFixture plain = render(v -> v.monk.hp = v.monk.maxHp * 0.5f);
        float[] hp = bar(FocusBattleLayout.PARTY_ROW_HP);

        assertTrue(exact(trail, FocusBattleTheme.HP_GHOST, hp) > 300, "the trail fills 50%..90%");
        assertEquals(0, exact(plain, FocusBattleTheme.HP_GHOST, hp), "neutral animation state draws no ghost");
    }

    @Test
    void theAtbGaugeFillsAndFlashesWhenFull() {
        BattleRasterFixture empty = render(v -> v.monk.atb = 0f);
        BattleRasterFixture half = render(v -> v.monk.atb = 0.5f);
        BattleRasterFixture full = render(v -> v.monk.atb = 1f);
        float[] atb = bar(FocusBattleLayout.PARTY_ROW_ATB);

        assertTrue(full.diff(empty, atb) > 1000, "full and empty ATB gauges differ");
        assertTrue(exact(half, FocusBattleTheme.ATB, atb) > 300, "a filling gauge is cyan");
        assertEquals(0, exact(full, FocusBattleTheme.ATB, atb), "a full gauge leaves cyan…");
        assertTrue(exact(full, FocusBattleTheme.ATB_FULL, atb) > 600, "…for the flash colour");
    }

    @Test
    void theFocusGaugeFillsShowsItsPercentageAndShimmersWhenFull() {
        BattleRasterFixture empty = render(v -> v.focus = 0f);
        BattleRasterFixture full = render(v -> v.focus = v.maxFocus);
        float[] focus = bar(FocusBattleLayout.PARTY_ROW_FOCUS);
        float[] value = FocusBattleLayout.partyValueRect(RECT, FocusBattleLayout.PARTY_ROW_FOCUS, SCALE);

        assertTrue(full.diff(empty, focus) > 1500, "full and empty Focus gauges differ");
        assertTrue(exact(full, FocusBattleTheme.FOCUS, focus) > 800, "Focus is gold");
        assertTrue(full.diff(empty, value) > 20, "0% and 100% read differently");

        BattleHudAnimState anim = new BattleHudAnimState();
        anim.focusShimmer = 0.5f;
        BattleRasterFixture shimmer = render(v -> v.focus = v.maxFocus, anim);
        assertTrue(shimmer.diff(full, focus) > 100, "the shimmer hook paints a band on a full gauge");
        BattleRasterFixture notReady = render(v -> v.focus = 50f, anim);
        assertEquals(0, notReady.diff(render(v -> v.focus = 50f), focus), "and nothing until the gauge is full");
    }

    @Test
    void qiPipsShowSpentAndAvailableSlots() {
        BattleRasterFixture none = render(v -> v.qi = 0);
        BattleRasterFixture all = render(v -> v.qi = 5);
        BattleRasterFixture three = render(v -> v.qi = 3);
        float[] qi = bar(FocusBattleLayout.PARTY_ROW_QI);

        assertTrue(all.diff(none, qi) > 200, "0 and 5 Qi differ");
        assertEquals(0, exact(none, FocusBattleTheme.QI, qi), "no jade without Qi");
        int jadeAll = exact(all, FocusBattleTheme.QI, qi);
        int jadeThree = exact(three, FocusBattleTheme.QI, qi);
        assertTrue(jadeAll > 100, "filled pips are jade");
        assertTrue(jadeThree > 0 && jadeThree < jadeAll, "3 of 5 pips is between the two");
        assertTrue(exact(none, FocusBattleTheme.QI_EMPTY, qi) > 100, "empty slots stay visible as sockets");
    }

    @Test
    void statusChipsAppearWithTheirTimers() {
        float[] nameRow = FocusBattleLayout.partyRowRect(RECT, FocusBattleLayout.PARTY_ROW_NAME, SCALE);
        BattleRasterFixture bare = render(v -> { });
        BattleRasterFixture haste = render(v -> v.monk.statuses.add(new StatusView(BattleStatus.HASTE, 12f, 1)));
        BattleRasterFixture hasteLater = render(v -> v.monk.statuses.add(new StatusView(BattleStatus.HASTE, 3f, 1)));
        BattleRasterFixture guard = render(v -> v.monk.statuses.add(new StatusView(BattleStatus.GUARDING, -1f, 1)));

        assertTrue(haste.diff(bare, nameRow) > 150, "a status chip appears on the name row");
        assertTrue(haste.diff(hasteLater, nameRow) > 5, "its timer counts down");
        assertTrue(guard.diff(bare, nameRow) > 100, "an untimed status still gets a chip");
        // Chips are right-aligned: the left half of the row (the name) is untouched.
        assertEquals(0, haste.diff(bare, (int) nameRow[0], (int) nameRow[1],
                (int) (nameRow[0] + nameRow[2] / 2f), (int) (nameRow[1] + nameRow[3])));
    }

    @Test
    void queuedSurgeHitsGetTheirOwnChip() {
        float[] nameRow = FocusBattleLayout.partyRowRect(RECT, FocusBattleLayout.PARTY_ROW_NAME, SCALE);
        BattleRasterFixture bare = render(v -> { });
        BattleRasterFixture surge = render(v -> v.queuedSurgeHits = 2);
        BattleRasterFixture surgeWithStatus = render(v -> {
            v.queuedSurgeHits = 2;
            v.monk.statuses.add(new StatusView(BattleStatus.SURGE, -1f, 2));
        });
        assertTrue(surge.diff(bare, nameRow) > 150, "the Surge chip appears");
        assertEquals(0, surge.diff(surgeWithStatus), "and is not duplicated when the model also lists SURGE");
    }

    @Test
    void shakeMovesTheWholeWindow() {
        BattleHudAnimState anim = new BattleHudAnimState();
        anim.partyShakeX = 6f;
        BattleRasterFixture shaken = render(v -> { }, anim);
        BattleRasterFixture still = render(v -> { });
        assertTrue(shaken.diff(still) > 500, "a shake offset redraws the window elsewhere");
        assertEquals(0, shaken.countPainted(0, 0, (int) RECT[0] + 4, H), "to the right, not the left");
    }
}
