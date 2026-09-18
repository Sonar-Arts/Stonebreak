package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleEvent.DamageFlavor;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.rendering.UI.masonryUI.MCastBar;
import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.ui.focusBattle.BattleHelpText;
import com.stonebreak.ui.focusBattle.BattlePalette;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.SkijaFocusBattleRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The enemy plate (E5) with its ATB / cast bar (E6), the help strip (E4) and the mode tag (E13). */
class EnemyPlateRenderTest {

    private static final int W = 520;
    private static final int H = 140;
    private static final float[] PLATE = {30f, 20f, 460f, 98f};
    private static final float SCALE = 1f;
    private static final float DT = 1f / 60f;

    private record Shot(BattleRasterFixture fx, EnemyPlate plate) {
        float[] hp() { return plate.hpGauge().barRect(fx.ui); }
        float[] bar() { return plate.castBar().barRect(fx.ui); }
        int exact(int color, float x0, float y0, float x1, float y1) {
            return fx.countExactly(color, (int) x0, (int) y0, (int) Math.ceil(x1), (int) Math.ceil(y1));
        }
        int exact(int color, float[] r) { return exact(color, r[0], r[1], r[0] + r[2], r[1] + r[3]); }
    }

    private static Shot shot(EnemyPlate plate, FakeBattleView view) {
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        plate.render(fx.ui, PLATE, view, SCALE);
        return new Shot(fx, plate);
    }

    private static Shot plate(Consumer<FakeBattleView> setup) {
        FakeBattleView view = new FakeBattleView();
        setup.accept(view);
        return shot(new EnemyPlate(), view);
    }

    private static Shot gauge(float atb, TelegraphView telegraph) {
        return plate(v -> { v.archon.atb = atb; v.telegraph = telegraph; });
    }

    private static TelegraphView overhead(float elapsed, boolean cancelled) {
        float impact = EnemyAction.OVERHEAD.impactTime();
        return new TelegraphView(EnemyAction.OVERHEAD, elapsed, impact, impact * 0.75f, impact * 0.95f, cancelled);
    }

    // ── E5 ───────────────────────────────────────────────────────────────────

    @Test
    void thePlateShowsNameAndExactHp() {
        Shot full = plate(v -> { });
        Shot again = plate(v -> { });
        Shot hurt = plate(v -> v.archon.hp = 90f);
        Shot renamed = plate(v -> v.archon.displayName = "Frost Warden");
        float[] hp = full.hp();
        float[] name = EnemyPlate.lines(PLATE, SCALE)[0];

        assertEquals(0, full.fx.diff(again.fx), "deterministic");
        assertTrue(full.fx.countPainted(PLATE) > 40_000);
        assertTrue(full.exact(BattlePalette.ACCENT_ARCHON, PLATE[0], PLATE[1], PLATE[0] + PLATE[2], PLATE[1] + 6f) > 300,
                "the Archon's accent hairline");
        assertTrue(full.fx.diff(hurt.fx, hp) > 2000, "900/900 and 90/900 differ across the bar");
        assertTrue(full.exact(MStyle.VITAL_OK, hp) > 1500);
        assertTrue(hurt.exact(MStyle.VITAL_CRIT, hp) > 100, "10% HP is red");
        assertEquals("900/900", full.plate.hpGauge().resolvedValueText());
        assertEquals("90/900", hurt.plate.hpGauge().resolvedValueText());
        assertTrue(full.fx.diff(renamed.fx, name) > 30, "the name is drawn from the view");
        assertEquals(0, full.fx.diff(renamed.fx, hp), "and nothing else moves");
    }

    @Test
    void theLinesStackInsideThePlateAtAnyHeight() {
        for (float[] plate : new float[][]{PLATE, {30f, 20f, 460f, 60f}, {0f, 0f, 920f, 196f}}) {
            float scale = plate[2] > 900f ? 2f : 1f;
            float[][] lines = EnemyPlate.lines(plate, scale);
            float bottom = plate[1];
            for (float[] line : lines) {
                assertTrue(line[1] >= bottom - 0.01f, "lines never overlap");
                bottom = line[1] + line[3];
                assertTrue(line[0] >= plate[0] && line[0] + line[2] <= plate[0] + plate[2]);
            }
            assertTrue(bottom <= plate[1] + plate[3] + 0.01f, "and the last one ends inside the plate");
        }
    }

    @Test
    void enemyStatusChipsAppear() {
        float[] name = EnemyPlate.lines(PLATE, SCALE)[0];
        Shot bare = plate(v -> { });
        Shot stunned = plate(v -> v.archon.statuses.add(new StatusView(BattleStatus.STUNNED, 4f, 1)));
        assertTrue(stunned.fx.diff(bare.fx, name) > 150, "STUNNED gets a chip on the name line");
        assertTrue(stunned.exact(BattlePalette.STATUS_BAD, name[0] + name[2] / 2f, name[1], name[0] + name[2],
                name[1] + name[3]) > 10, "harmful statuses use the warning colour");
        assertEquals("4s", stunned.plate.chipRow().chips().get(0).trailing());
    }

    @Test
    void aHitLeavesATrailAndFlashesTheHpGauge() {
        FakeBattleView view = new FakeBattleView();
        EnemyPlate plate = new EnemyPlate();
        plate.update(DT, view, List.of());
        view.archon.hp = 450f;
        plate.update(0f, view, List.of(new BattleEvent.DamageDealt(CombatantId.ARCHON, 450f, DamageFlavor.CRITICAL)));
        assertEquals(1f, plate.hpGauge().ghostFraction(), 1e-5f);
        assertEquals(1f, plate.hpGauge().flashStrength(), 1e-6f);

        plate.update(0.31f, view, List.of());
        assertEquals(0f, plate.hpGauge().flashStrength(), 0f);
        Shot trailing = shot(plate, view);
        float[] hp = trailing.hp();
        assertTrue(trailing.exact(MStyle.GAUGE_GHOST, hp[0] + hp[2] * 0.5f, hp[1], hp[0] + hp[2], hp[1] + hp[3]) > 1000,
                "the pale trail spans the lost half");

        plate.update(0f, view, List.of(new BattleEvent.DamageDealt(CombatantId.MONK, 20f, DamageFlavor.NORMAL)));
        assertEquals(0f, plate.hpGauge().flashStrength(), 0f, "a blow to the monk is not the Archon's");

        plate.reset();
        plate.update(0f, view, List.of());
        assertTrue(plate.hpGauge().ghostFraction() < 0f, "a retry starts with no trail");
    }

    // ── E6 ───────────────────────────────────────────────────────────────────

    @Test
    void thePlainGaugeIsAThinCyanAtbBar() {
        Shot empty = gauge(0f, null);
        Shot full = gauge(1f, null);
        float[] bar = full.bar();
        assertTrue(full.fx.diff(empty.fx, bar) > 1500, "full and empty enemy ATB differ");
        assertTrue(full.exact(BattlePalette.ATB, bar) > 1000);
        assertFalse(full.plate.castBar().hasWindow(), "no window marker while idle");
        float[] cast = EnemyPlate.lines(PLATE, SCALE)[2];
        assertEquals(0, full.fx.diff(empty.fx, (int) cast[0], (int) cast[1], (int) (cast[0] + cast[2]), (int) bar[1] - 1),
                "and only its steady caption above it");
    }

    @Test
    void aTelegraphTurnsTheGaugeIntoALabelledCastBar() {
        Shot plain = gauge(0.5f, null);
        Shot cast = gauge(0.5f, overhead(0.5f, false));
        float[] line = EnemyPlate.lines(PLATE, SCALE)[2];
        float[] bar = cast.bar();
        assertTrue(cast.fx.diff(plain.fx, (int) line[0], (int) line[1], (int) (line[0] + line[2]), (int) bar[1] - 3) > 150,
                "the attack name and PARRY caption appear");
        assertTrue(cast.fx.diff(plain.fx, bar) > 1000, "the bar grows and recolours");
        assertTrue(bar[3] > plain.bar()[3], "thin as a plain ATB gauge, full while casting");
        assertEquals(bar[1] + bar[3] / 2f, plain.bar()[1] + plain.bar()[3] / 2f, 0.01f, "without jumping");
    }

    @Test
    void theCastBarShiftsFromCalmToHotAsImpactNears() {
        Shot early = gauge(0f, overhead(0.1f, false));
        Shot late = gauge(0f, overhead(EnemyAction.OVERHEAD.impactTime(), false));
        int earlyColor = MColor.lerp(BattlePalette.CAST_START, BattlePalette.CAST_END, overhead(0.1f, false).progress());
        float[] bar = late.bar();
        // The reaction window tints the fill it covers: probe the part of the bar before it.
        float before = bar[0] + bar[2] * 0.7f;
        assertTrue(early.exact(earlyColor, bar) > 50);
        assertTrue(late.exact(BattlePalette.CAST_END, bar[0], bar[1], before, bar[1] + bar[3]) > 1000);
        assertTrue(((earlyColor >> 16) & 0xFF) < ((BattlePalette.CAST_END >> 16) & 0xFF), "red rises with progress");
        assertTrue((earlyColor & 0xFF) > (BattlePalette.CAST_END & 0xFF), "blue falls with progress");
    }

    @Test
    void theParryWindowIsMarkedOnTheSameTimeline() {
        TelegraphView t = overhead(0.2f, false);
        Shot cast = gauge(0f, t);
        MCastBar bar = cast.plate.castBar();
        float[] rect = cast.bar();
        float[] span = bar.windowSpan(cast.fx.ui);

        assertEquals(rect[0] + rect[2] * 0.75f, span[0], 0.01f, "start maps to 75% of the bar");
        assertEquals(rect[0] + rect[2] * 0.95f, span[1], 0.01f, "end maps to 95% of the bar");
        assertFalse(bar.inWindow());
        assertTrue(gauge(0f, overhead(EnemyAction.OVERHEAD.impactTime() * 0.8f, false)).plate.castBar().inWindow());

        float top = rect[1] - 4f, bottom = rect[1] + rect[3] + 4f;
        assertTrue(cast.exact(BattlePalette.REACT_WINDOW, span[0] - 1f, top, span[1] + 1f, bottom) > 150,
                "a solid bracket frames the parry window");
        assertEquals(0, cast.exact(BattlePalette.REACT_WINDOW, rect[0], top, span[0] - 2f, bottom),
                "nothing marker-coloured on the bar before the window opens");
    }

    @Test
    void aWindowPastTheImpactIsClampedToTheBar() {
        Shot cast = gauge(0f, new TelegraphView(EnemyAction.SLASH, 0f, 1f, 0.9f, 1.4f, false));
        float[] rect = cast.bar();
        float[] span = cast.plate.castBar().windowSpan(cast.fx.ui);
        assertEquals(rect[0] + rect[2] * 0.9f, span[0], 0.01f);
        assertEquals(rect[0] + rect[2], span[1], 0.01f);
    }

    @Test
    void aCancelledTelegraphGoesGrey() {
        Shot live = gauge(0f, overhead(0.8f, false));
        Shot cancelled = gauge(0f, overhead(0.8f, true));
        float[] bar = cancelled.bar();
        assertTrue(live.fx.diff(cancelled.fx, EnemyPlate.lines(PLATE, SCALE)[2]) > 1500);
        assertTrue(cancelled.exact(MStyle.TEXT_DISABLED, bar) > 300, "the fill turns grey");
        assertEquals(0, cancelled.exact(BattlePalette.REACT_WINDOW, 0, 0, W, H), "and the parry marker dies with it");
    }

    @Test
    void theParryMarkerPulsesOnlyWhileTheMonkGuards() {
        FakeBattleView view = new FakeBattleView();
        view.telegraph = overhead(0.2f, false);
        EnemyPlate plate = new EnemyPlate();
        plate.update(0.1f, view, List.of());
        assertEquals(0f, plate.castBar().pulseAmount(), 0f);

        view.monk.statuses.add(new StatusView(BattleStatus.GUARDING, -1f, 1));
        plate.update(0f, view, List.of());
        Shot a = shot(plate, view);
        plate.update(0.12f, view, List.of());
        Shot b = shot(plate, view);
        assertTrue(plate.castBar().pulseAmount() > 0f);
        assertTrue(a.fx.diff(b.fx, a.bar()) > 100, "the marker visibly pulses");

        view.telegraph = overhead(0.2f, true);
        plate.update(0.1f, view, List.of());
        assertEquals(0f, plate.castBar().pulseAmount(), 0f, "a cancelled windup has nothing to parry");
    }

    // ── E4 + E13 ─────────────────────────────────────────────────────────────

    @Test
    void theHelpStripDrawsOneLineAndNothingWhenEmpty() {
        float[] strip = {10f, 50f, 500f, 28f};
        BattleRasterFixture empty = new BattleRasterFixture(W, H);
        HelpStrip.render(empty.ui, strip, BattleHelpText.Line.EMPTY, SCALE, 1f);
        assertEquals(0, empty.countPainted(0, 0, W, H), "an empty line draws no frame at all");

        BattleHelpText.Line line = new BattleHelpText.Line("A single focused blow.", false);
        BattleRasterFixture help = new BattleRasterFixture(W, H);
        HelpStrip.render(help.ui, strip, line, SCALE, 1f);
        BattleRasterFixture warn = new BattleRasterFixture(W, H);
        HelpStrip.render(warn.ui, strip, new BattleHelpText.Line(line.text(), true), SCALE, 1f);
        BattleRasterFixture faded = new BattleRasterFixture(W, H);
        HelpStrip.render(faded.ui, strip, line, SCALE, 0f);

        assertTrue(help.countPainted(strip) > 12_000);
        assertTrue(help.countExactly(MStyle.TEXT_PRIMARY, 10, 50, 510, 78) > 100);
        assertTrue(warn.countExactly(MStyle.TEXT_WARN, 10, 50, 510, 78) > 100, "a refusal reads in the warning colour");
        assertEquals(0, faded.countExactly(MStyle.TEXT_PRIMARY, 10, 50, 510, 78), "fade 0 hides the text");
        assertTrue(faded.countPainted(strip) > 12_000, "but keeps the frame");
    }

    @Test
    void longHelpTextShrinksToStayInsideTheStrip() {
        float[] strip = {10f, 50f, 260f, 28f};
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        HelpStrip.render(fx.ui, strip, new BattleHelpText.Line("Three rapid strikes. Time each hit on the ring.", false),
                SCALE, 1f);
        assertEquals(0, fx.countPainted((int) (strip[0] + strip[2]) + 6, 0, W, H),
                "no glyph escapes past the strip's right edge");
    }

    @Test
    void theModeTagIsALibraryBadgeWithALiveDot() {
        float[] tag = {16f, 16f, 96f, 26f};
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        new SkijaFocusBattleRenderer.Windows().modeTag.scale(SCALE).bounds(tag[0], tag[1], tag[2], tag[3]).render(fx.ui);
        assertTrue(fx.countPainted(tag) > 2000);
        assertTrue(fx.countExactly(BattlePalette.ATB, (int) tag[0], (int) tag[1], (int) (tag[0] + tag[2] / 2f),
                (int) (tag[1] + tag[3])) > 10, "the cyan live dot");
        assertEquals(0, fx.countPainted(130, 0, W, H), "and nothing outside it");
    }
}
