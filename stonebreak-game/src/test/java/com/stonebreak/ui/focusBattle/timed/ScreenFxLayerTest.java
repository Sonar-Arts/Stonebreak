package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.ui.focusBattle.BattleHudAnimState;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import org.junit.jupiter.api.Test;

import static com.stonebreak.ui.focusBattle.timed.TimedScenes.H;
import static com.stonebreak.ui.focusBattle.timed.TimedScenes.SCALE;
import static com.stonebreak.ui.focusBattle.timed.TimedScenes.UI_SCALE;
import static com.stonebreak.ui.focusBattle.timed.TimedScenes.W;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E14 on a CPU canvas: what triggers each effect, and that the scene stays readable under all of them. */
class ScreenFxLayerTest {

    private static final int BG = BattleRasterFixture.BACKGROUND;

    private static TimedInputLayers settled(FakeBattleView view) {
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        for (int i = 0; i < 40; i++) TimedScenes.frame(layers, view, 0.033f);
        return layers;
    }

    private static BattleRasterFixture fxOnly(TimedInputLayers layers, FakeBattleView view) {
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        layers.screenFxLayer().paint(fx.ui, fx.canvas, W, H, SCALE, UI_SCALE, view, BattleHudAnimState.NEUTRAL, null);
        return fx;
    }

    /** Opacity of whatever was composited over the snow at a pixel, judged by the largest channel shift. */
    private static float coverage(BattleRasterFixture fx, int x, int y, int tint) {
        int c = fx.bitmap.getColor(x, y);
        float best = 0f;
        for (int shift = 0; shift <= 16; shift += 8) {
            int bg = (BG >> shift) & 0xFF, fg = (tint >> shift) & 0xFF, got = (c >> shift) & 0xFF;
            if (Math.abs(fg - bg) > 40) best = Math.max(best, (got - bg) / (float) (fg - bg));
        }
        return best;
    }

    private static void assertCentreUntouched(BattleRasterFixture fx, String what) {
        // The centre 50%: |dx| <= w/4 and |dy| <= h/4 lies inside normalised radius ~0.707; the
        // guaranteed-clear zone is the ellipse r <= 0.5. Check that whole ellipse's bounding core.
        int clear = 0, total = 0;
        for (int y = 0; y < H; y += 3) {
            for (int x = 0; x < W; x += 3) {
                if (TimedLayout.normalisedRadius(x, y, W, H) <= TimedLayout.VIGNETTE_CLEAR_RADIUS - 0.01f) {
                    total++;
                    if (fx.bitmap.getColor(x, y) == BG) clear++;
                }
            }
        }
        assertEquals(total, clear, what + ": centre 50% must be pixel-identical to the scene");
    }

    @Test
    void aNeutralBattlePaintsNothing() {
        FakeBattleView view = new FakeBattleView();
        assertEquals(0, fxOnly(settled(view), view).countPainted(0, 0, W, H));
    }

    @Test
    void lowHpIsARedVignetteThatSparesTheCentre() {
        FakeBattleView view = new FakeBattleView();
        view.monk.hp = view.monk.maxHp * 0.1f;
        BattleRasterFixture fx = fxOnly(settled(view), view);
        assertCentreUntouched(fx, "low HP");
        float corner = coverage(fx, 2, 2, TimedTheme.FX_LOW_HP);
        float edge = coverage(fx, W / 2, 2, TimedTheme.FX_LOW_HP);
        assertTrue(corner > 0.2f && corner <= TimedLayout.VIGNETTE_MAX_ALPHA + 0.02f, "corner alpha " + corner);
        assertTrue(edge > 0.05f && edge < corner, "edges tinted, less than corners: " + edge);
        int c = fx.bitmap.getColor(2, 2);
        assertTrue(((c >> 16) & 0xFF) > ((BG >> 16) & 0xFF) && ((c >> 8) & 0xFF) < ((BG >> 8) & 0xFF) - 30
                && (c & 0xFF) < (BG & 0xFF) - 30, "it pulls the snow toward red");

        view.monk.hp = view.monk.maxHp * 0.5f;
        assertEquals(0, fxOnly(settled(view), view).countPainted(0, 0, W, H), "not below the threshold → nothing");
    }

    @Test
    void theLowHpVignettePulses() {
        FakeBattleView view = new FakeBattleView();
        view.monk.hp = view.monk.maxHp * 0.05f;
        TimedInputLayers layers = settled(view);
        float min = 1f, max = 0f;
        for (int i = 0; i < 40; i++) {
            TimedScenes.frame(layers, view, 0.02f);
            float peak = ScreenFxLayer.vignettePeaks(view, layers.state())[0];
            min = Math.min(min, peak);
            max = Math.max(max, peak);
            assertTrue(peak <= TimedLayout.VIGNETTE_MAX_ALPHA + 1.0e-4f);
        }
        assertTrue(max - min > 0.08f, "heartbeat swing " + (max - min));
        assertTrue(min > 0.2f, "never fully gone while HP is low");
    }

    @Test
    void chilledFrostsTheEdgesAndGrowsCornerCrystals() {
        FakeBattleView view = new FakeBattleView();
        view.monk.statuses.add(new StatusView(BattleStatus.CHILLED, 8f, 1));
        BattleRasterFixture fx = fxOnly(settled(view), view);
        assertCentreUntouched(fx, "frost");
        int reach = (int) ScreenFxLayer.crystalReach(W, H);
        for (int corner = 0; corner < 4; corner++) {
            int x0 = (corner & 1) == 0 ? 0 : W - reach, y0 = corner < 2 ? 0 : H - reach;
            // Crystals are much brighter than the soft vignette around them.
            int bright = 0;
            for (int y = y0; y < y0 + reach; y++) {
                for (int x = x0; x < x0 + reach; x++) {
                    int c = fx.bitmap.getColor(x, y);
                    if (((c >> 16) & 0xFF) > 0xBC) bright++;
                }
            }
            assertTrue(bright > 1500, "crystals in corner " + corner + ": " + bright);
        }
        int c = fx.bitmap.getColor(W / 2, 3);
        assertTrue(((c >> 16) & 0xFF) > 0x91, "frost lightens the top edge");

        view.monk.statuses.clear();
        TimedInputLayers thawed = settled(view);
        assertEquals(0, fxOnly(thawed, view).countPainted(0, 0, W, H));
    }

    @Test
    void fullFocusIsAGoldAuraThatOnlyHugsTheEdges() {
        FakeBattleView view = new FakeBattleView();
        view.focus = view.maxFocus;
        BattleRasterFixture fx = fxOnly(settled(view), view);
        assertCentreUntouched(fx, "focus aura");
        // Further in than the other vignettes: at r ≈ 0.75 there is still nothing.
        assertEquals(BG, fx.bitmap.getColor(W / 2 + (int) (W / 2 * 0.75f), H / 2));
        int corner = fx.bitmap.getColor(2, 2);
        assertTrue((corner & 0xFF) < (BG & 0xFF) && ((corner >> 16) & 0xFF) > ((BG >> 16) & 0xFF), "gold: more red, less blue");
        assertTrue(coverage(fx, 2, 2, TimedTheme.FX_FOCUS) <= ScreenFxLayer.FOCUS_PEAK + 0.02f);

        view.focus = 99f;
        assertEquals(0, fxOnly(settled(view), view).countPainted(0, 0, W, H));
    }

    @Test
    void stackedVignettesStillRespectTheCap() {
        FakeBattleView view = new FakeBattleView();
        view.monk.hp = 1f;
        view.focus = view.maxFocus;
        view.monk.statuses.add(new StatusView(BattleStatus.CHILLED, 8f, 1));
        TimedInputLayers layers = settled(view);
        for (int i = 0; i < 30; i++) {
            TimedScenes.frame(layers, view, 0.03f);
            float[] p = ScreenFxLayer.vignettePeaks(view, layers.state());
            float stacked = ScreenFxLayer.stacked(p[0], p[1], p[2], 1f);
            assertTrue(stacked <= TimedLayout.VIGNETTE_MAX_ALPHA + 1.0e-3f, "stacked peak " + stacked);
            assertTrue(p[0] > 0f && p[1] > 0f && p[2] > 0f, "all three still present");
        }
        assertCentreUntouched(fxOnly(layers, view), "all three");
    }

    @Test
    void theLetterboxBarsAreTheLayoutRects() {
        FakeBattleView view = new FakeBattleView();
        view.phase = com.stonebreak.battle.api.BattlePhase.INTRO;
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        TimedScenes.frame(layers, view, TimedInputState.LETTERBOX_SECONDS / 2f);
        BattleRasterFixture half = fxOnly(layers, view);
        float amount = ScreenFxLayer.letterboxAmount(layers.state());
        float[] topHalf = FocusBattleLayout.letterboxTopRect(W, H, amount);
        assertTrue(topHalf[3] > 0f && topHalf[3] < FocusBattleLayout.letterboxTopRect(W, H, 1f)[3], "easing in");
        assertEquals((int) (topHalf[3] * W), half.countExactly(0xFF000000, 0, 0, W, H / 2));

        TimedScenes.frame(layers, view, TimedInputState.LETTERBOX_SECONDS);
        BattleRasterFixture full = fxOnly(layers, view);
        float[] top = FocusBattleLayout.letterboxTopRect(W, H, 1f), bottom = FocusBattleLayout.letterboxBottomRect(W, H, 1f);
        assertEquals((int) (top[2] * top[3]), full.countExactly(0xFF000000, 0, 0, W, (int) top[3]));
        assertEquals((int) (bottom[2] * bottom[3]), full.countExactly(0xFF000000, 0, (int) bottom[1], W, H));
        assertEquals(0, full.countPainted(0, (int) top[3], W, (int) bottom[1]), "only the bars");

        view.phase = com.stonebreak.battle.api.BattlePhase.RUNNING;
        TimedScenes.frame(layers, view, TimedInputState.LETTERBOX_SECONDS + 0.01f);
        assertEquals(0, fxOnly(layers, view).countPainted(0, 0, W, H), "eased back out");
    }

    @Test
    void victoryKeepsTheBarsForTheCinematicHold() {
        FakeBattleView view = new FakeBattleView();
        view.outcome = BattleOutcome.VICTORY;
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        TimedScenes.frame(layers, view, 0.016f, new BattleEvent.Ended(BattleOutcome.VICTORY));
        TimedScenes.frame(layers, view, 1f);
        assertTrue(fxOnly(layers, view).countExactly(0xFF000000, 0, 0, W, H) > W * 100);
        TimedScenes.frame(layers, view, 4f);
        TimedScenes.frame(layers, view, 0.3f);
        assertEquals(0, fxOnly(layers, view).countPainted(0, 0, W, H));
    }

    @Test
    void criticalHitsFlashTheWholeScreenBrieflyAndGently() {
        for (CombatantId target : CombatantId.values()) {
            FakeBattleView view = new FakeBattleView();
            TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
            TimedScenes.frame(layers, view, 0.016f, new BattleEvent.DamageDealt(target, 80f, BattleEvent.DamageFlavor.CRITICAL));
            TimedScenes.frame(layers, view, 0.04f);
            BattleRasterFixture fx = fxOnly(layers, view);
            assertEquals(W * H, fx.countPainted(0, 0, W, H), "full screen");
            int tint = target == CombatantId.MONK ? TimedTheme.FX_CRIT_TAKEN : TimedTheme.FX_CRIT_DEALT;
            assertTrue(coverage(fx, W / 2, H / 2, tint) <= ScreenFxLayer.CRIT_FLASH_PEAK + 0.02f, "subtle");
            TimedScenes.frame(layers, view, TimedInputState.CRIT_FLASH_SECONDS);
            assertEquals(0, fxOnly(layers, view).countPainted(0, 0, W, H), "brief");
        }
        FakeBattleView view = new FakeBattleView();
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        TimedScenes.frame(layers, view, 0.016f, new BattleEvent.DamageDealt(CombatantId.ARCHON, 80f, BattleEvent.DamageFlavor.NORMAL));
        assertEquals(0, fxOnly(layers, view).countPainted(0, 0, W, H), "ordinary hits do not flash");
    }

    @Test
    void defeatSlowlyDrainsColourAndLight() {
        FakeBattleView view = new FakeBattleView();
        view.outcome = BattleOutcome.DEFEAT;
        view.monk.hp = 0f;
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        TimedScenes.frame(layers, view, 0.016f, new BattleEvent.Ended(BattleOutcome.DEFEAT));
        int previousLuma = luma(BG), previousChroma = chroma(BG);
        for (int i = 0; i < 5; i++) {
            TimedScenes.frame(layers, view, TimedInputState.DEFEAT_FADE_SECONDS / 5f);
            int c = fxOnly(layers, view).bitmap.getColor(W / 2, H / 2);
            assertTrue(luma(c) < previousLuma, "darker each step");
            assertTrue(chroma(c) < previousChroma, "and less colourful");
            previousLuma = luma(c);
            previousChroma = chroma(c);
        }
        assertTrue(previousLuma > 40, "never a black screen: the result panel still has a scene behind it");
    }

    private static int luma(int c) {
        return (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
    }

    private static int chroma(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }
}
