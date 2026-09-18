package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.ui.focusBattle.FocusBattleLayout;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static com.stonebreak.ui.focusBattle.timed.TimedScenes.H;
import static com.stonebreak.ui.focusBattle.timed.TimedScenes.SCALE;
import static com.stonebreak.ui.focusBattle.timed.TimedScenes.W;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The timing math behind the pictures: if these hold, "press when they meet" is true on screen. */
class TimedLayoutTest {

    private static final float EPS = 1.0e-4f;

    // ─────────────────────────────────────────────── E9 ring geometry

    @Test
    void theShrinkingRingMeetsTheTargetExactlyAtThePerfectMidpoint() {
        float mid = (TimedScenes.PERFECT_START + TimedScenes.PERFECT_END) / 2f;
        assertEquals(100f, TimedLayout.outerRadius(TimedScenes.ring(0, mid), 100f), 1.0e-3f);
        assertEquals(100f * TimedLayout.RING_START_SCALE, TimedLayout.outerRadius(TimedScenes.ring(0, 0f), 100f), 1.0e-3f);
        // Holds for any window, not just the default tuning.
        PromptView.Ring odd = new PromptView.Ring(0, 5, 0.30f, 2f, 0.2f, 0.4f, 0.1f, 0.9f);
        assertEquals(64f, TimedLayout.outerRadius(odd, 64f), 1.0e-3f);
    }

    @Test
    void theRingShrinksMonotonicallyAndNeverCollapses() {
        float previous = Float.MAX_VALUE;
        for (int i = 0; i <= 90; i++) {
            float r = TimedLayout.outerRadius(TimedScenes.ring(0, i / 100f), 100f);
            assertTrue(r < previous, "radius must keep shrinking at t=" + i / 100f);
            assertTrue(r >= 100f * TimedLayout.RING_MIN_SCALE - EPS);
            previous = r;
        }
        PromptView.Ring late = new PromptView.Ring(0, 3, 50f, 60f, 0.5f, 0.7f, 0.4f, 0.8f);
        assertEquals(100f * TimedLayout.RING_MIN_SCALE, TimedLayout.outerRadius(late, 100f), EPS);
    }

    @Test
    void theBandsAreExactlyThePressWindows() {
        PromptView.Ring shape = TimedScenes.ring(0, 0f);
        float[] good = TimedLayout.goodBand(shape, 100f);
        float[] perfect = TimedLayout.perfectBand(shape, 100f);
        assertTrue(good[0] < perfect[0] && perfect[0] < 100f && 100f < perfect[1] && perfect[1] < good[1],
                "perfect band nests inside the good band, both straddling the target circle");
        assertEquals(100f, (perfect[0] + perfect[1]) / 2f, 1.0e-3f, "target circle is the middle of the perfect band");
        // The band's thickness is the window's length at the ring's closing speed.
        float speed = 100f * (TimedLayout.RING_START_SCALE - 1f) / ((TimedScenes.PERFECT_START + TimedScenes.PERFECT_END) / 2f);
        assertEquals((TimedScenes.PERFECT_END - TimedScenes.PERFECT_START) * speed, perfect[1] - perfect[0], 1.0e-2f);
        assertEquals((TimedScenes.GOOD_END - TimedScenes.GOOD_START) * speed, good[1] - good[0], 1.0e-2f);

        for (int i = 0; i <= 90; i++) {
            float t = i / 100f + 0.005f;
            float r = TimedLayout.outerRadius(TimedScenes.ring(0, t), 100f);
            boolean inPerfect = t >= TimedScenes.PERFECT_START && t <= TimedScenes.PERFECT_END;
            boolean inGood = t >= TimedScenes.GOOD_START && t <= TimedScenes.GOOD_END;
            assertEquals(inPerfect, r >= perfect[0] && r <= perfect[1], "perfect band at t=" + t);
            assertEquals(inGood, r >= good[0] && r <= good[1], "good band at t=" + t);
        }
    }

    // ─────────────────────────────────────────────── Anchors

    @Test
    void theRingAnchorsToTheProjectedArchonAndSizesFromItsDepth() {
        FakeBattleView view = TimedScenes.ringView(0, 0.2f);
        Matrix4f camera = TimedScenes.cameraPlacing(TimedScenes.archonChest(), 800f, 300f, 100f, W, H);
        TimedLayout.Anchor a = TimedLayout.ringAnchor(TimedScenes.LAYOUT, view, camera, W, H, SCALE);
        assertTrue(a.anchored());
        assertEquals(800f, a.x(), 0.5f);
        assertEquals(300f, a.y(), 0.5f);
        assertEquals(100f * TimedLayout.RING_RADIUS_BLOCKS, a.radius(), 0.5f);

        // A dashing Archon drags the ring with it: the anchor follows the pose, not the home ring.
        view.archon.pose = new com.stonebreak.battle.api.ActorPose("idle", 0f, 1f, 0f, 1f);
        Matrix4f side = TimedScenes.cameraPlacing(TimedScenes.archonChest(), 640f, 300f, 10f, W, H)
                .rotateY((float) Math.toRadians(90));
        TimedLayout.Anchor home = TimedLayout.ringAnchor(TimedScenes.LAYOUT, new FakeBattleView(), side, W, H, SCALE);
        TimedLayout.Anchor dashed = TimedLayout.ringAnchor(TimedScenes.LAYOUT, view, side, W, H, SCALE);
        assertTrue(home.anchored() && dashed.anchored());
        assertTrue(Math.abs(home.x() - dashed.x()) > 100f, "anchor follows the dash");

        TimedLayout.Anchor far = TimedLayout.ringAnchor(TimedScenes.LAYOUT, view,
                TimedScenes.cameraPlacing(TimedScenes.archonChest(), 800f, 300f, 2f, W, H), W, H, SCALE);
        assertTrue(far.radius() >= 40f * SCALE - EPS, "a distant Archon still gets a readable ring");
        TimedLayout.Anchor close = TimedLayout.ringAnchor(TimedScenes.LAYOUT, view,
                TimedScenes.cameraPlacing(TimedScenes.archonChest(), 640f, 360f, 900f, W, H), W, H, SCALE);
        assertTrue(close.radius() <= H * 0.2f + EPS, "a close-up never fills the screen with ring");
    }

    @Test
    void theRingFallsBackToTheScreenCentre() {
        FakeBattleView view = TimedScenes.ringView(0, 0.2f);
        TimedLayout.Anchor none = TimedLayout.ringAnchor(TimedScenes.LAYOUT, view, null, W, H, SCALE);
        assertFalse(none.anchored());
        assertEquals(W / 2f, none.x(), EPS);
        assertEquals(H / 2f, none.y(), EPS);
        assertTrue(none.radius() > 0f);

        Matrix4f offScreen = TimedScenes.cameraPlacing(TimedScenes.archonChest(), -600f, 300f, 100f, W, H);
        TimedLayout.Anchor off = TimedLayout.ringAnchor(TimedScenes.LAYOUT, view, offScreen, W, H, SCALE);
        assertFalse(off.anchored());
        assertEquals(W / 2f, off.x(), EPS);

        // Behind the camera: w <= 0.
        Matrix4f behind = new Matrix4f().perspective(1f, 16f / 9f, 0.1f, 100f).lookAt(0f, 1.5f, -20f, 0f, 1.5f, -40f, 0f, 1f, 0f);
        assertFalse(TimedLayout.ringAnchor(TimedScenes.LAYOUT, view, behind, W, H, SCALE).anchored());
        assertFalse(TimedLayout.ringAnchor(null, view, offScreen, W, H, SCALE).anchored(), "no stage layout");
    }

    @Test
    void aRingNearTheEdgeIsKeptOnScreen() {
        Matrix4f edge = TimedScenes.cameraPlacing(TimedScenes.archonChest(), 10f, 715f, 100f, W, H);
        TimedLayout.Anchor a = TimedLayout.ringAnchor(TimedScenes.LAYOUT, new FakeBattleView(), edge, W, H, SCALE);
        assertTrue(a.anchored());
        assertTrue(a.x() - a.radius() >= -EPS && a.y() + a.radius() <= H + EPS);
    }

    // ─────────────────────────────────────────────── E12 parry

    @Test
    void theBracketsShutExactlyAtTheWindowStart() {
        float closed = 50f;
        assertEquals(closed * TimedLayout.PARRY_OPEN_SCALE,
                TimedLayout.bracketHalfGap(closed, TimedLayout.bracketClosure(new TimedLayout.ParryTimeline(0f, 1f, 1.25f, 1.25f))), EPS);
        float just = TimedLayout.bracketClosure(new TimedLayout.ParryTimeline(0.999f, 1f, 1.25f, 1.25f));
        assertTrue(just < 1f && TimedLayout.bracketHalfGap(closed, just) > closed);
        assertEquals(1f, TimedLayout.bracketClosure(new TimedLayout.ParryTimeline(1f, 1f, 1.25f, 1.25f)), 0f);
        assertEquals(closed, TimedLayout.bracketHalfGap(closed, 1f), EPS);
        assertEquals(1f, TimedLayout.bracketClosure(new TimedLayout.ParryTimeline(1.2f, 1f, 1.25f, 1.25f)), 0f, "stays shut through the window");
        // Constant closing speed.
        float a = TimedLayout.bracketHalfGap(closed, TimedLayout.bracketClosure(new TimedLayout.ParryTimeline(0.25f, 1f, 1.25f, 1.25f)));
        float b = TimedLayout.bracketHalfGap(closed, TimedLayout.bracketClosure(new TimedLayout.ParryTimeline(0.50f, 1f, 1.25f, 1.25f)));
        float c = TimedLayout.bracketHalfGap(closed, TimedLayout.bracketClosure(new TimedLayout.ParryTimeline(0.75f, 1f, 1.25f, 1.25f)));
        assertEquals(a - b, b - c, 1.0e-3f);
        assertEquals(1f, TimedLayout.bracketClosure(new TimedLayout.ParryTimeline(0f, 0f, 0.25f, 0.25f)), 0f, "degenerate window");
    }

    @Test
    void theParryTimelineFollowsTheTelegraphClock() {
        PromptView.Parry prompt = new PromptView.Parry(0.2f, 1.0f, 1.25f, 1.25f);
        TelegraphView telegraph = new TelegraphView(EnemyAction.SLASH, 0.5f, 0.66f, 0.41f, 0.66f, false);
        TimedLayout.ParryTimeline synced = TimedLayout.parryTimeline(prompt, telegraph);
        assertEquals(0.5f, synced.elapsed(), 0f);
        assertEquals(0.41f, synced.windowStart(), 0f);
        assertTrue(synced.inWindow());
        assertEquals(telegraph.inParryWindow(), synced.inWindow());

        TimedLayout.ParryTimeline alone = TimedLayout.parryTimeline(prompt, null);
        assertEquals(0.2f, alone.elapsed(), 0f);
        assertFalse(alone.inWindow());
        TelegraphView cancelled = new TelegraphView(EnemyAction.SLASH, 0.5f, 0.66f, 0.41f, 0.66f, true);
        assertEquals(0.2f, TimedLayout.parryTimeline(prompt, cancelled).elapsed(), 0f, "a cancelled telegraph is no clock");
    }

    @Test
    void theParryReticleAnchorsToTheMonkOrFallsBack() {
        FakeBattleView view = TimedScenes.parryView(0.5f);
        Matrix4f camera = TimedScenes.cameraPlacing(TimedScenes.monkTorso(), 900f, 500f, 120f, W, H);
        TimedLayout.Anchor a = TimedLayout.monkAnchor(TimedScenes.LAYOUT, view, camera, W, H, SCALE);
        assertTrue(a.anchored());
        assertEquals(900f, a.x(), 0.5f);
        assertEquals(500f, a.y(), 0.5f);
        assertEquals(120f * TimedLayout.PARRY_CLOSED_BLOCKS, a.radius(), 0.5f);
        TimedLayout.Anchor none = TimedLayout.monkAnchor(TimedScenes.LAYOUT, view, null, W, H, SCALE);
        assertFalse(none.anchored());
        assertEquals(W / 2f, none.x(), EPS);
    }

    // ─────────────────────────────────────────────── E10 combo strip

    @Test
    void comboCellsSitInsideTheStripInOrderAtEverySize() {
        int[][] windows = {{1024, 600}, {1280, 720}, {1920, 1080}, {3840, 2160}};
        for (int[] win : windows) {
            for (float ui : new float[]{0.75f, 1f, 2f}) {
                float s = FocusBattleLayout.effectiveScale(win[0], win[1], ui);
                float[] strip = FocusBattleLayout.comboStripRect(win[0], win[1], ui);
                float previousRight = strip[0];
                for (int i = 0; i < 6; i++) {
                    float[] cell = TimedLayout.comboCellRect(strip, i, 6, s);
                    float[] big = TimedLayout.scaled(cell, TimedLayout.COMBO_CURRENT_SCALE + 0.04f);
                    float[] track = TimedLayout.comboTimerTrack(cell, s);
                    assertTrue(cell[0] >= previousRight, "cells do not overlap at rest");
                    assertTrue(big[0] >= strip[0] && big[0] + big[2] <= strip[0] + strip[2], "enlarged cell inside strip (x)");
                    assertTrue(big[1] >= strip[1] && track[1] + track[3] <= strip[1] + strip[3],
                            "enlarged cell + timer inside strip (y) at " + win[0] + "x" + win[1] + " ui " + ui);
                    previousRight = cell[0] + cell[2];
                }
                float[] first = TimedLayout.comboCellRect(strip, 0, 6, s), last = TimedLayout.comboCellRect(strip, 5, 6, s);
                assertEquals(first[0] - strip[0], strip[0] + strip[2] - (last[0] + last[2]), 1f, "row is centred");
                float[] label = TimedLayout.comboLabelRect(strip, s), counter = TimedLayout.comboCounterRect(strip, s);
                assertTrue(label[0] + label[2] <= first[0] + 1f && counter[0] >= last[0] + last[2] - 1f,
                        "captions clear the cells");
            }
        }
    }

    @Test
    void theTimerUnderlineDrainsTowardItsLeftEdge() {
        float[] track = {100f, 50f, 60f, 5f};
        assertEquals(1f, TimedLayout.comboTimeLeft(0f, 0.9f), EPS);
        assertEquals(0.5f, TimedLayout.comboTimeLeft(0.45f, 0.9f), EPS);
        assertEquals(0f, TimedLayout.comboTimeLeft(2f, 0.9f), EPS);
        assertEquals(0f, TimedLayout.comboTimeLeft(0.1f, 0f), EPS);
        float previous = Float.MAX_VALUE;
        for (int i = 0; i <= 9; i++) {
            float[] fill = TimedLayout.comboTimerFill(track, TimedLayout.comboTimeLeft(i / 10f, 0.9f));
            assertEquals(100f, fill[0], 0f);
            assertTrue(fill[2] < previous);
            previous = fill[2];
        }
    }

    @Test
    void theStripSlidesFromBelowTheScreenToRest() {
        float[] strip = FocusBattleLayout.comboStripRect(W, H, 1f);
        assertTrue(strip[1] + TimedLayout.comboSlideOffset(strip, H, 0f, SCALE) >= H, "fully out = below the window");
        assertEquals(0f, TimedLayout.comboSlideOffset(strip, H, 1f, SCALE), EPS);
        assertTrue(TimedLayout.comboSlideOffset(strip, H, 0.5f, SCALE) > 0f);
    }

    // ─────────────────────────────────────────────── E14 vignette curve

    @Test
    void vignettesLeaveTheCentreAloneAndNeverExceedTheCap() {
        for (float peak : new float[]{0.1f, 0.3f, 0.45f, 0.9f, 5f}) {
            float previous = 0f;
            for (int i = 0; i <= 150; i++) {
                float r = i / 100f;
                float a = TimedLayout.vignetteAlpha(r, peak, 0f);
                if (r <= TimedLayout.VIGNETTE_CLEAR_RADIUS) assertEquals(0f, a, 0f, "centre 50% untouched at r=" + r);
                assertTrue(a <= TimedLayout.VIGNETTE_MAX_ALPHA + EPS, "capped");
                assertTrue(a >= previous - EPS, "monotonic outward");
                previous = a;
            }
        }
        assertEquals(0.3f, TimedLayout.vignetteAlpha(TimedLayout.VIGNETTE_CORNER_RADIUS, 0.3f, 0.5f), EPS, "peak reached in the corner");
        assertEquals(0f, TimedLayout.vignetteAlpha(0.8f, 0.3f, 0.82f), 0f, "an edge aura starts further out");
        assertEquals(0f, TimedLayout.normalisedRadius(W / 2f, H / 2f, W, H), EPS);
        assertEquals(1f, TimedLayout.normalisedRadius(W, H / 2f, W, H), EPS);
        assertEquals(TimedLayout.VIGNETTE_CORNER_RADIUS, TimedLayout.normalisedRadius(0f, 0f, W, H), EPS);
        assertEquals(0.5f, TimedLayout.normalisedRadius(W * 0.75f, H / 2f, W, H), EPS, "the centre 50% is r <= 0.5");
    }
}
