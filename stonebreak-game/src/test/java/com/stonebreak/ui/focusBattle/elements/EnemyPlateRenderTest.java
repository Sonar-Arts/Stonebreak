package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.ui.focusBattle.BattleHelpText;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import com.stonebreak.ui.focusBattle.FocusBattleTheme;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pixel tests for the enemy plate (E5), its ATB / telegraph bar (E6), the help strip and the mode tag. */
class EnemyPlateRenderTest {

    private static final int W = 520;
    private static final int H = 140;
    private static final float[] PLATE = {30f, 20f, 460f, 98f};
    private static final float SCALE = 1f;
    private static final float[] GAUGE = FocusBattleLayout.enemyGaugeRect(PLATE, SCALE);

    private static BattleRasterFixture plate(Consumer<FakeBattleView> setup) {
        FakeBattleView view = new FakeBattleView();
        setup.accept(view);
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        EnemyPlate.paint(fx.ui, fx.canvas, PLATE, view.archon(), SCALE);
        return fx;
    }

    private static BattleRasterFixture gauge(float atb, TelegraphView telegraph) {
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        FocusBattleTheme.battleWindow(fx.canvas, PLATE);
        EnemyGaugeBar.paint(fx.ui, fx.canvas, GAUGE, atb, telegraph, SCALE);
        return fx;
    }

    private static TelegraphView overhead(float elapsed, boolean cancelled) {
        float impact = EnemyAction.OVERHEAD.impactTime();
        return new TelegraphView(EnemyAction.OVERHEAD, elapsed, impact, impact * 0.75f, impact * 0.95f, cancelled);
    }

    private static int exact(BattleRasterFixture fx, int color, float x0, float y0, float x1, float y1) {
        return fx.countExactly(color, (int) x0, (int) y0, (int) Math.ceil(x1), (int) Math.ceil(y1));
    }

    // ── E5 ───────────────────────────────────────────────────────────────────

    @Test
    void thePlateShowsNameAndExactHp() {
        BattleRasterFixture full = plate(v -> { });
        BattleRasterFixture again = plate(v -> { });
        BattleRasterFixture hurt = plate(v -> v.archon.hp = 90f);
        BattleRasterFixture renamed = plate(v -> v.archon.displayName = "Frost Warden");
        float[] hp = FocusBattleLayout.enemyHpBarRect(PLATE, SCALE);
        float[] name = FocusBattleLayout.enemyNameRect(PLATE, SCALE);

        assertEquals(0, full.diff(again), "deterministic");
        assertTrue(full.countPainted(PLATE) > 40_000);
        assertTrue(full.diff(hurt, hp) > 2000, "900/900 and 90/900 differ across the bar");
        assertTrue(exact(full, FocusBattleTheme.HP_HIGH, hp[0], hp[1], hp[0] + hp[2], hp[1] + hp[3]) > 1500);
        assertTrue(exact(hurt, FocusBattleTheme.HP_LOW, hp[0], hp[1], hp[0] + hp[2], hp[1] + hp[3]) > 100,
                "10% HP is red");
        assertTrue(full.diff(renamed, name) > 30, "the name is drawn from the view");
        assertEquals(0, full.diff(renamed, hp), "and nothing else moves");
    }

    @Test
    void enemyStatusChipsAppear() {
        float[] name = FocusBattleLayout.enemyNameRect(PLATE, SCALE);
        BattleRasterFixture bare = plate(v -> { });
        BattleRasterFixture stunned = plate(v -> v.archon.statuses.add(new StatusView(BattleStatus.STUNNED, 4f, 1)));
        assertTrue(stunned.diff(bare, name) > 150, "STUNNED gets a chip on the name line");
        assertTrue(exact(stunned, FocusBattleTheme.CHIP_BAD, name[0] + name[2] / 2f, name[1], name[0] + name[2],
                name[1] + name[3]) > 10, "harmful statuses use the warning colour");
    }

    @Test
    void theHitFlashAndGhostTrailAreAnimationInputs() {
        FakeBattleView view = new FakeBattleView();
        view.archon.hp = 450f;
        BattleHudAnimState anim = new BattleHudAnimState();
        anim.archonGhostHpFraction = 0.8f;
        BattleRasterFixture ghost = new BattleRasterFixture(W, H);
        EnemyPlate.paint(ghost.ui, ghost.canvas, PLATE, view.archon(), SCALE, anim);
        float[] hp = FocusBattleLayout.enemyHpBarRect(PLATE, SCALE);
        assertTrue(exact(ghost, FocusBattleTheme.HP_GHOST, hp[0] + hp[2] * 0.5f, hp[1], hp[0] + hp[2] * 0.8f,
                hp[1] + hp[3]) > 300, "the ghost trail spans 50%..80%");

        anim.archonGhostHpFraction = -1f;
        anim.enemyHitFlash = 1f;
        BattleRasterFixture flash = new BattleRasterFixture(W, H);
        EnemyPlate.paint(flash.ui, flash.canvas, PLATE, view.archon(), SCALE, anim);
        assertTrue(flash.diff(plate(v -> v.archon.hp = 450f), PLATE) > 30_000, "the hit flash tints the plate");
    }

    // ── E6 ───────────────────────────────────────────────────────────────────

    @Test
    void thePlainGaugeIsAThinCyanAtbBar() {
        BattleRasterFixture empty = gauge(0f, null);
        BattleRasterFixture full = gauge(1f, null);
        float[] bar = FocusBattleLayout.gaugeBarRect(GAUGE, false);
        assertTrue(full.diff(empty, bar) > 1500, "full and empty enemy ATB differ");
        assertTrue(exact(full, FocusBattleTheme.ATB, bar[0], bar[1], bar[0] + bar[2], bar[1] + bar[3]) > 1000);
        assertEquals(0, full.diff(empty, FocusBattleLayout.gaugeLabelRect(GAUGE)), "no label while idle");
    }

    @Test
    void aTelegraphTurnsTheGaugeIntoALabelledCastBar() {
        BattleRasterFixture plain = gauge(0.5f, null);
        BattleRasterFixture cast = gauge(0.5f, overhead(0.5f, false));
        float[] label = FocusBattleLayout.gaugeLabelRect(GAUGE);
        assertTrue(cast.diff(plain, label) > 150, "the attack name and PARRY caption appear");
        assertTrue(cast.diff(plain, FocusBattleLayout.gaugeBarRect(GAUGE, true)) > 1000, "the bar grows and recolours");
        assertTrue(FocusBattleLayout.gaugeBarRect(GAUGE, true)[3] > FocusBattleLayout.gaugeBarRect(GAUGE, false)[3]);
    }

    @Test
    void theCastBarShiftsFromBlueToRedAsImpactNears() {
        float[] bar = FocusBattleLayout.gaugeBarRect(GAUGE, true);
        BattleRasterFixture early = gauge(0f, overhead(0.1f, false));
        BattleRasterFixture late = gauge(0f, overhead(1.0f, false));
        int earlyColor = FocusBattleTheme.telegraphColor(overhead(0.1f, false).progress());
        int lateColor = FocusBattleTheme.telegraphColor(overhead(1.0f, false).progress());

        assertTrue(exact(early, earlyColor, bar[0], bar[1], bar[0] + bar[2], bar[1] + bar[3]) > 100);
        assertTrue(exact(late, lateColor, bar[0], bar[1], bar[0] + bar[2], bar[1] + bar[3]) > 1000);
        assertTrue(((earlyColor >> 16) & 0xFF) < ((lateColor >> 16) & 0xFF), "red rises with progress");
        assertTrue((earlyColor & 0xFF) > (lateColor & 0xFF), "blue falls with progress");
    }

    @Test
    void theParryWindowIsMarkedOnTheSameTimeline() {
        TelegraphView t = overhead(0.2f, false);
        BattleRasterFixture cast = gauge(0f, t);
        float[] bar = FocusBattleLayout.gaugeBarRect(GAUGE, true);
        float[] span = EnemyGaugeBar.parryMarkerSpan(bar, t);

        assertEquals(bar[0] + bar[2] * 0.75f, span[0], 0.01f, "start maps to 75% of the bar");
        assertEquals(bar[0] + bar[2] * 0.95f, span[1], 0.01f, "end maps to 95% of the bar");

        float top = bar[1] - 4f, bottom = bar[1] + bar[3] + 4f;
        assertTrue(exact(cast, FocusBattleTheme.PARRY_MARKER, span[0] - 1f, top, span[1] + 1f, bottom) > 150,
                "a solid bracket frames the parry window");
        assertEquals(0, exact(cast, FocusBattleTheme.PARRY_MARKER, bar[0], top, span[0] - 2f, bottom),
                "nothing marker-coloured on the bar before the window opens");
        assertEquals(0, exact(cast, FocusBattleTheme.PARRY_MARKER, span[1] + 2f, top, bar[0] + bar[2] + 2f, bottom),
                "or after it closes");
        // The translucent window fill is visible against the still-empty track.
        BattleRasterFixture plainTrack = gauge(0f, null);
        assertTrue(cast.diff(plainTrack, (int) span[0] + 3, (int) bar[1] + 2, (int) span[1] - 3,
                (int) (bar[1] + bar[3]) - 2) > 300);
    }

    @Test
    void aWindowPastTheImpactIsClampedToTheBar() {
        float[] bar = {0f, 0f, 200f, 10f};
        float[] span = EnemyGaugeBar.parryMarkerSpan(bar, new TelegraphView(EnemyAction.SLASH, 0f, 1f, 0.9f, 1.4f, false));
        assertEquals(180f, span[0], 0.01f);
        assertEquals(200f, span[1], 0.01f);
    }

    @Test
    void aCancelledTelegraphGoesGrey() {
        BattleRasterFixture live = gauge(0f, overhead(0.8f, false));
        BattleRasterFixture cancelled = gauge(0f, overhead(0.8f, true));
        float[] bar = FocusBattleLayout.gaugeBarRect(GAUGE, true);
        assertTrue(live.diff(cancelled, GAUGE) > 1500);
        assertTrue(exact(cancelled, FocusBattleTheme.TELEGRAPH_CANCELLED, bar[0], bar[1], bar[0] + bar[2],
                bar[1] + bar[3]) > 500, "the fill turns grey");
        assertEquals(0, exact(cancelled, FocusBattleTheme.PARRY_MARKER, 0, 0, W, H), "and the parry marker dies with it");
    }

    @Test
    void theParryMarkerPulseIsAnAnimationInput() {
        BattleHudAnimState anim = new BattleHudAnimState();
        anim.parryMarkerPulse = 1f;
        BattleRasterFixture pulsing = new BattleRasterFixture(W, H);
        FocusBattleTheme.battleWindow(pulsing.canvas, PLATE);
        EnemyGaugeBar.paint(pulsing.ui, pulsing.canvas, GAUGE, 0f, overhead(0.2f, false), SCALE, anim);
        assertTrue(pulsing.diff(gauge(0f, overhead(0.2f, false)), GAUGE) > 200);
    }

    // ── E4 + E13 ─────────────────────────────────────────────────────────────

    @Test
    void theHelpStripDrawsOneLineAndNothingWhenEmpty() {
        float[] strip = {10f, 50f, 500f, 28f};
        BattleRasterFixture empty = new BattleRasterFixture(W, H);
        HelpStrip.paint(empty.ui, empty.canvas, strip, BattleHelpText.Line.EMPTY, SCALE);
        assertEquals(0, empty.countPainted(0, 0, W, H), "an empty line draws no window at all");

        BattleRasterFixture help = new BattleRasterFixture(W, H);
        HelpStrip.paint(help.ui, help.canvas, strip, new BattleHelpText.Line("A single focused blow.", false), SCALE);
        BattleRasterFixture warn = new BattleRasterFixture(W, H);
        HelpStrip.paint(warn.ui, warn.canvas, strip, new BattleHelpText.Line("A single focused blow.", true), SCALE);
        BattleRasterFixture faded = new BattleRasterFixture(W, H);
        HelpStrip.paint(faded.ui, faded.canvas, strip, new BattleHelpText.Line("A single focused blow.", false), SCALE, 0f);

        assertTrue(help.countPainted(strip) > 12_000);
        assertTrue(help.diff(warn, strip) > 100, "a refusal reads in the warning colour");
        assertTrue(help.diff(faded, strip) > 100, "fade 0 hides the text but keeps the window");
        assertTrue(faded.countPainted(strip) > 12_000);
    }

    @Test
    void longHelpTextShrinksToStayInsideTheStrip() {
        float[] strip = {10f, 50f, 260f, 28f};
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        HelpStrip.paint(fx.ui, fx.canvas, strip, new BattleHelpText.Line(
                "Three rapid strikes. Time each hit on the ring.", false), SCALE);
        assertEquals(0, fx.countPainted((int) (strip[0] + strip[2]) + 6, 0, W, H),
                "no glyph escapes past the strip's right edge");
    }

    @Test
    void theModeTagPaintsItsChip() {
        float[] tag = {16f, 16f, 96f, 26f};
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        ModeTag.paint(fx.ui, fx.canvas, tag, ModeTag.ACTIVE, SCALE);
        assertTrue(fx.countPainted(tag) > 2000);
        assertTrue(exact(fx, FocusBattleTheme.ATB, tag[0], tag[1], tag[0] + tag[3], tag[1] + tag[3]) > 10,
                "the cyan live dot");
        assertEquals(0, fx.countPainted(130, 0, W, H), "and nothing outside it");
    }
}
