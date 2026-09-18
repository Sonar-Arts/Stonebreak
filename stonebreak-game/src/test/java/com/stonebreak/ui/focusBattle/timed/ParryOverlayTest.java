package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.CombatantId;
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

/** E12 on a CPU canvas: closing brackets, the window glow, and the three outcomes. */
class ParryOverlayTest {

    private static final float MX = 820f, MY = 440f, PPB = 120f;
    private static final float HALF = PPB * TimedLayout.PARRY_CLOSED_BLOCKS;
    private static final Matrix4f CAMERA = TimedScenes.cameraPlacing(TimedScenes.monkTorso(), MX, MY, PPB, W, H);

    private static BattleRasterFixture paint(FakeBattleView view, float dt, BattleEvent... events) {
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        TimedScenes.frame(layers, view, 0.016f, events);
        if (dt > 0f) TimedScenes.frame(layers, view, dt);
        return TimedScenes.paintLayers(layers, view, CAMERA);
    }

    /** Exact-colour pixels in a thin vertical slab around x, over the bracket's height. */
    private static int spineAt(BattleRasterFixture fx, float x, int color) {
        float h = ParryOverlay.bracketHeight(HALF);
        return fx.countExactly(color, Math.round(x) - 2, Math.round(MY - h / 4f), Math.round(x) + 3, Math.round(MY + h / 4f));
    }

    @Test
    void theBracketsTravelInwardAndShutAtTheWindowStart() {
        // parryView: window opens at 1.0 s.
        BattleRasterFixture open = paint(TimedScenes.parryView(0f), 0f);
        float wide = TimedLayout.bracketHalfGap(HALF, 0f);
        assertTrue(spineAt(open, MX - wide, TimedTheme.PARRY_BRACKET) > 20, "left bracket fully open");
        assertTrue(spineAt(open, MX + wide, TimedTheme.PARRY_BRACKET) > 20, "right bracket fully open");

        BattleRasterFixture half = paint(TimedScenes.parryView(0.5f), 0f);
        float mid = TimedLayout.bracketHalfGap(HALF, 0.5f);
        assertTrue(spineAt(half, MX - mid, TimedTheme.PARRY_BRACKET) > 20, "halfway in at half time");
        assertEquals(0, spineAt(half, MX - wide, TimedTheme.PARRY_BRACKET), "and no longer at the open position");

        BattleRasterFixture shut = paint(TimedScenes.parryView(1.0f), 0f);
        int live = 0;
        for (int x = Math.round(MX - HALF) - 3; x <= Math.round(MX - HALF) + 3; x++) {
            int c = shut.bitmap.getColor(x, Math.round(MY));
            // Live brackets are gold-to-white: strong red + green.
            if (((c >> 16) & 0xFF) > 0xE0 && ((c >> 8) & 0xFF) > 0xE0) live++;
        }
        assertTrue(live > 0, "shut on the monk exactly at the window start");
        assertEquals(0, spineAt(shut, MX - mid, TimedTheme.PARRY_BRACKET));
    }

    @Test
    void theWindowGlowsAndReadsDifferentlyFromTheApproach() {
        BattleRasterFixture before = paint(TimedScenes.parryView(0.95f), 0f);
        BattleRasterFixture during = paint(TimedScenes.parryView(1.1f), 0f);
        int cx = Math.round(MX), cy = Math.round(MY);
        assertEquals(BattleRasterFixture.BACKGROUND, before.bitmap.getColor(cx, cy), "no glow before the window");
        assertTrue(during.bitmap.getColor(cx, cy) != BattleRasterFixture.BACKGROUND, "gold wash between the shut brackets");
        assertTrue(before.diff(during) > 2000);
        // The label above the reticle is there in both.
        float top = MY - ParryOverlay.bracketHeight(HALF) / 2f;
        assertTrue(before.countPainted(cx - 60, Math.round(top) - 40, cx + 60, Math.round(top) - 4) > 150, "PARRY label");
    }

    @Test
    void aLandedParryFlashesTheWholeScreenFromTheMonk() {
        BattleRasterFixture blank = paint(new FakeBattleView(), 0f);
        FakeBattleView view = new FakeBattleView();
        BattleRasterFixture flash = paint(view, 0.06f, new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.PERFECT, 0));
        assertEquals(W * H, flash.countPainted(0, 0, W, H), "the wash covers every pixel");
        int corner = flash.bitmap.getColor(3, 3);
        assertTrue(((corner >> 16) & 0xFF) > 0x91 && ((corner >> 8) & 0xFF) > 0xBA, "and it is a brightening, white-blue one");
        assertTrue(flash.countExactly(0xFFFFFFFF, 0, 0, W, H) > 400, "big white PARRY! + ring core");

        // The ring expands: later frame, larger radius (its bright core moves away from the monk).
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        TimedScenes.frame(layers, view, 0.016f, new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.PERFECT, 0));
        TimedScenes.frame(layers, view, 0.02f);
        BattleRasterFixture early = TimedScenes.paintLayers(layers, view, CAMERA);
        TimedScenes.frame(layers, view, 0.10f);
        BattleRasterFixture later = TimedScenes.paintLayers(layers, view, CAMERA);
        assertTrue(early.diff(later) > 5000);

        // Flash over at 0.25 s (word lingers), everything over at 0.6 s.
        TimedScenes.frame(layers, view, TimedInputState.PARRY_FLASH_SECONDS);
        BattleRasterFixture wordOnly = TimedScenes.paintLayers(layers, view, CAMERA);
        assertEquals(BattleRasterFixture.BACKGROUND, wordOnly.bitmap.getColor(3, 3), "wash gone after ~0.25 s");
        assertTrue(wordOnly.diff(blank) > 300, "the word is still readable");
        TimedScenes.frame(layers, view, TimedInputState.PARRY_TEXT_SECONDS);
        assertEquals(0, TimedScenes.paintLayers(layers, view, CAMERA).diff(blank));
    }

    @Test
    void theFlashStaysSubtle() {
        assertTrue(ParryOverlay.FLASH_PEAK_ALPHA <= 0.45f);
    }

    @Test
    void blockAndTooEarlyAreSmallLocalAndDistinct() {
        BattleRasterFixture blank = paint(new FakeBattleView(), 0f);
        BattleRasterFixture block = paint(new FakeBattleView(), 0.08f,
                new BattleEvent.DamageDealt(CombatantId.MONK, 19f, BattleEvent.DamageFlavor.BLOCKED));
        BattleRasterFixture early = paint(new FakeBattleView(), 0.08f,
                new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.MISS, 0));
        assertTrue(block.diff(blank) > 1500, "shield + BLOCK");
        assertTrue(early.diff(blank) > 300, "TOO EARLY");
        assertTrue(block.diff(early) > 1000);
        // Both stay near the monk: the far side of the screen is untouched.
        assertEquals(0, block.countPainted(0, 0, W / 3, H));
        assertEquals(0, early.countPainted(0, 0, W / 3, H));
        // TOO EARLY is grey, not a celebration colour.
        assertTrue(early.countExactly(TimedTheme.NEUTRAL_GREY, 0, 0, W, H) > 100);
        assertEquals(0, early.countExactly(TimedTheme.PERFECT, 0, 0, W, H));
        // A shield sits on the monk.
        assertTrue(block.bitmap.getColor(Math.round(MX), Math.round(MY)) != BattleRasterFixture.BACKGROUND);

        BattleRasterFixture blockGone = paint(new FakeBattleView(), TimedInputState.BLOCK_SECONDS + 0.01f,
                new BattleEvent.DamageDealt(CombatantId.MONK, 19f, BattleEvent.DamageFlavor.BLOCKED));
        BattleRasterFixture earlyGone = paint(new FakeBattleView(), TimedInputState.TOO_EARLY_SECONDS + 0.01f,
                new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.MISS, 0));
        assertEquals(0, blockGone.diff(blank));
        assertEquals(0, earlyGone.diff(blank));
    }

    @Test
    void theReticleFallsBackWhenTheMonkCannotBeProjected() {
        FakeBattleView view = TimedScenes.parryView(0.5f);
        TimedInputLayers layers = new TimedInputLayers(TimedScenes.LAYOUT);
        TimedScenes.frame(layers, view, 0.016f);
        BattleRasterFixture fx = TimedScenes.paintLayers(layers, view, null);
        TimedLayout.Anchor a = TimedLayout.monkAnchor(TimedScenes.LAYOUT, view, null, W, H, TimedScenes.SCALE);
        assertTrue(fx.countPainted((int) (a.x() - a.radius() * 3.2f), (int) (a.y() - a.radius() * 2f),
                (int) (a.x() + a.radius() * 3.2f), (int) (a.y() + a.radius() * 2f)) > 500);
    }
}
