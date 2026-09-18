package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.ui.focusBattle.BattleRasterFixture;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static com.stonebreak.ui.focusBattle.timed.TimedScenes.H;
import static com.stonebreak.ui.focusBattle.timed.TimedScenes.W;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E9 on a CPU canvas: the strokes are where the geometry says, per state, and nowhere else. */
class TimingRingOverlayTest {

    private static final float CX = 700f, CY = 330f, PPB = 100f;
    private static final float RT = PPB * TimedLayout.RING_RADIUS_BLOCKS;
    private static final Matrix4f CAMERA = TimedScenes.cameraPlacing(TimedScenes.archonChest(), CX, CY, PPB, W, H);

    private static BattleRasterFixture paint(FakeBattleView view, Matrix4f camera, BattleEvent... events) {
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        TimedScenes.frame(layers, view, 0.016f, events);
        return TimedScenes.paintLayers(layers, view, camera);
    }

    /** Exact-colour pixels within 3 px of the point at {@code radius} on the 45° ray (clear of the notches). */
    private static int exactAt(BattleRasterFixture fx, float cx, float cy, float radius, int color) {
        float d = radius * (float) Math.sqrt(0.5);
        int x = Math.round(cx + d), y = Math.round(cy - d);
        return fx.countExactly(color, x - 3, y - 3, x + 4, y + 4);
    }

    @Test
    void theShrinkingRingIsDrawnAtItsGeometricRadius() {
        FakeBattleView early = TimedScenes.ringView(0, 0.2f);
        BattleRasterFixture fx = paint(early, CAMERA);
        float outer = TimedLayout.outerRadius(TimedScenes.ring(0, 0.2f), RT);
        assertTrue(exactAt(fx, CX, CY, outer, TimedTheme.RING_OUTER) > 0, "white outer ring at its radius");
        assertTrue(exactAt(fx, CX, CY, RT, TimedTheme.RING_TARGET) > 0, "fixed target circle");
        assertEquals(0, exactAt(fx, CX, CY, outer + 30f, TimedTheme.RING_OUTER), "nothing outside it");
        assertEquals(0, fx.countPainted(0, 0, 100, 100), "the ring is local to its target");

        // Every bright stroke carries a dark under-stroke: darker-than-snow pixels hug the ring.
        float d = (outer + 4f) * (float) Math.sqrt(0.5);
        int dark = 0;
        for (int i = -4; i <= 4; i++) {
            int c = fx.bitmap.getColor(Math.round(CX + d) + i, Math.round(CY - d) - i);
            if (((c >> 16) & 0xFF) < 0x60 && ((c >> 8) & 0xFF) < 0x70) dark++;
        }
        assertTrue(dark > 0, "dark under-stroke beside the white ring");
    }

    @Test
    void theRingsMeetInGoldAtThePerfectMidpoint() {
        BattleRasterFixture fx = paint(TimedScenes.ringView(1, 0.62f), CAMERA);
        assertTrue(exactAt(fx, CX, CY, RT, TimedTheme.PERFECT) > 0, "gold outer ring sitting on the target radius");
        BattleRasterFixture good = paint(TimedScenes.ringView(1, 0.49f), CAMERA);
        float r = TimedLayout.outerRadius(TimedScenes.ring(1, 0.49f), RT);
        assertTrue(exactAt(good, CX, CY, r, TimedTheme.RING_OUTER_GOOD) > 0, "blue while only GOOD is on offer");
        BattleRasterFixture late = paint(TimedScenes.ringView(1, 0.85f), CAMERA);
        float rl = TimedLayout.outerRadius(TimedScenes.ring(1, 0.85f), RT);
        assertTrue(exactAt(late, CX, CY, rl, TimedTheme.RING_OUTER_LATE) > 0, "red once the window has passed");
    }

    @Test
    void differentMomentsAndHitsPaintDifferently() {
        BattleRasterFixture a = paint(TimedScenes.ringView(0, 0.2f), CAMERA);
        BattleRasterFixture b = paint(TimedScenes.ringView(0, 0.5f), CAMERA);
        assertTrue(a.diff(b) > 1500, "the ring moves");
        BattleRasterFixture hit2 = paint(TimedScenes.ringView(2, 0.2f), CAMERA);
        float[] pips = TimingRingOverlay.pipRowRect(new TimedLayout.Anchor(CX, CY, RT, true), TimedScenes.ring(0, 0.2f), TimedScenes.SCALE);
        float[] around = {pips[0] - 6f, pips[1] - 6f, pips[2] + 12f, pips[3] + 12f};
        assertTrue(a.countPainted(around) > 100, "hit pips under the ring");
        assertTrue(a.diff(hit2, around) > 40, "pips show which hit this is");
        assertTrue(a.countPainted((int) CX - 80, (int) (pips[1] + pips[3] + 4), (int) CX + 80, (int) (pips[1] + pips[3] + 34)) > 150,
                "key hint under the pips");
    }

    @Test
    void theRingFollowsTheArchonAndFallsBackToTheCentre() {
        FakeBattleView view = TimedScenes.ringView(0, 0.62f);
        BattleRasterFixture anchored = paint(view, CAMERA);
        assertTrue(exactAt(anchored, CX, CY, RT, TimedTheme.PERFECT) > 0);

        TimedLayout.Anchor fallback = TimedLayout.ringAnchor(TimedScenes.LAYOUT, view, null, W, H, TimedScenes.SCALE);
        BattleRasterFixture noCamera = paint(view, null);
        assertTrue(exactAt(noCamera, W / 2f, H / 2f, fallback.radius(), TimedTheme.PERFECT) > 0, "null matrix → centre");
        Matrix4f offScreen = TimedScenes.cameraPlacing(TimedScenes.archonChest(), W + 900f, 300f, PPB, W, H);
        BattleRasterFixture off = paint(view, offScreen);
        assertEquals(0, off.diff(noCamera), "off-screen anchor → the same centred ring");
    }

    @Test
    void eachGradeGetsItsOwnFeedbackWhichThenExpires() {
        BattleRasterFixture blank = paint(new FakeBattleView(), CAMERA);
        BattleRasterFixture[] shots = new BattleRasterFixture[3];
        for (TimedGrade grade : TimedGrade.values()) {
            TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
            FakeBattleView view = new FakeBattleView();
            TimedScenes.frame(layers, view, 0.016f, new BattleEvent.PromptResolved(PromptKind.RING, grade, 0));
            TimedScenes.frame(layers, view, 0.08f);
            BattleRasterFixture fx = TimedScenes.paintLayers(layers, view, CAMERA);
            shots[grade.ordinal()] = fx;
            assertTrue(fx.diff(blank) > 800, grade + " feedback paints");
            int color = switch (grade) {
                case PERFECT -> TimedTheme.PERFECT;
                case GOOD -> TimedTheme.GOOD;
                case MISS -> TimedTheme.MISS;
            };
            assertTrue(fx.countExactly(color, 0, 0, W, H) > 150, grade + " uses its own colour");
            // The word sits above the ring.
            assertTrue(fx.countPainted((int) CX - 120, (int) (CY - RT * 1.4f - 60f), (int) CX + 120, (int) (CY - RT * 1.4f)) > 200,
                    grade + " word above the ring");

            TimedScenes.frame(layers, view, TimedInputState.RING_FEEDBACK_SECONDS);
            assertEquals(0, TimedScenes.paintLayers(layers, view, CAMERA).diff(blank), grade + " feedback expires");
        }
        assertTrue(shots[0].diff(shots[1]) > 500 && shots[1].diff(shots[2]) > 500 && shots[0].diff(shots[2]) > 500);
    }

    @Test
    void feedbackNeverHidesTheNextRing() {
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        FakeBattleView next = TimedScenes.ringView(1, 0.1f);
        TimedScenes.frame(layers, next, 0.016f, new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.PERFECT, 0));
        TimedScenes.frame(layers, next, 0.3f);
        BattleRasterFixture fx = TimedScenes.paintLayers(layers, next, CAMERA);
        float outer = TimedLayout.outerRadius(TimedScenes.ring(1, 0.1f), RT);
        assertTrue(exactAt(fx, CX, CY, RT, TimedTheme.RING_TARGET) > 0, "next target circle visible through the burst");
        // The new ring is still fading in, so look for its under-stroke + bright core rather than an exact colour.
        assertTrue(fx.countPainted(Math.round(CX + outer * 0.7071f) - 4, Math.round(CY - outer * 0.7071f) - 4,
                Math.round(CX + outer * 0.7071f) + 5, Math.round(CY - outer * 0.7071f) + 5) > 20, "next outer ring visible");
    }

    @Test
    void gradeWordsCanBeTurnedOffWithoutLosingTheBurst() {
        FakeBattleView view = new FakeBattleView();
        TimedInputLayers with = new TimedInputLayers(TimedScenes.LAYOUT), without = new TimedInputLayers(TimedScenes.LAYOUT);
        without.setGradeWordsEnabled(false);
        for (TimedInputLayers l : new TimedInputLayers[]{with, without}) {
            TimedScenes.frame(l, view, 0.016f, new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.PERFECT, 0));
            TimedScenes.frame(l, view, 0.08f);
        }
        BattleRasterFixture a = TimedScenes.paintLayers(with, view, CAMERA), b = TimedScenes.paintLayers(without, view, CAMERA);
        assertTrue(a.diff(b) > 200, "the word is gone");
        assertTrue(b.countPainted(0, 0, W, H) > 800, "the burst stays");
    }

    @Test
    void nothingPaintsWithoutARingOrFeedback() {
        assertEquals(0, paint(new FakeBattleView(), CAMERA).countPainted(0, 0, W, H));
        // Another prompt kind is not a ring: the ring layer alone stays silent for it.
        FakeBattleView parry = TimedScenes.parryView(0.2f);
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        TimedScenes.frame(layers, parry, 0.016f);
        BattleRasterFixture fx = new BattleRasterFixture(W, H);
        layers.timingRingLayer().paint(fx.ui, fx.canvas, W, H, TimedScenes.SCALE, TimedScenes.UI_SCALE, parry,
                com.stonebreak.ui.focusBattle.BattleHudAnimState.NEUTRAL, CAMERA);
        assertEquals(0, fx.countPainted(0, 0, W, H));
    }
}
