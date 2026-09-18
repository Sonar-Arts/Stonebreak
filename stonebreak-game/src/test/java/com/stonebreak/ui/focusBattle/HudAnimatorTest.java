package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleEvent.DamageFlavor;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.FakeBattleView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The frame-level motion layer: every BattleHudAnimState field, its trigger and its decay. */
class HudAnimatorTest {

    private static final float DT = 1f / 60f;

    private FakeBattleView view;
    private BattleMenuState menu;
    private BattleHudAnimState anim;
    private HudAnimator animator;

    @BeforeEach
    void freshAnimator() {
        view = new FakeBattleView();
        menu = new BattleMenuState();
        anim = new BattleHudAnimState();
        animator = new HudAnimator(anim);
        animator.reset(view, menu);
    }

    /** One frame with the given events, which are then gone (as the model republishes). */
    private void frame(float dt, BattleEvent... events) {
        view.events.clear();
        view.events.addAll(List.of(events));
        animator.update(dt, view, menu);
        view.events.clear();
    }

    private void run(float seconds) {
        int frames = Math.round(seconds / DT);
        for (int i = 0; i < frames; i++) frame(DT);
    }

    // ── shake ────────────────────────────────────────────────────────────────

    private float peakShake(CombatantId target, float amount, DamageFlavor flavor) {
        freshAnimator();
        frame(DT);
        frame(DT, new BattleEvent.DamageDealt(target, amount, flavor));
        float peak = 0f;
        for (int i = 0; i < 18; i++) {
            float x = target == CombatantId.MONK ? anim.partyShakeX : anim.enemyShakeX;
            float y = target == CombatantId.MONK ? anim.partyShakeY : anim.enemyShakeY;
            peak = Math.max(peak, (float) Math.hypot(x, y));
            frame(DT);
        }
        return peak;
    }

    @Test
    void theDamagedSideShakesThenSettles() {
        frame(DT);
        frame(DT, new BattleEvent.DamageDealt(CombatantId.MONK, 30f, DamageFlavor.NORMAL));
        assertEquals(0f, anim.enemyShakeX, 0f, "the Archon was not hit");
        frame(DT);
        float moved = Math.abs(anim.partyShakeX) + Math.abs(anim.partyShakeY);
        assertTrue(moved > 0.5f, "the window moves");

        run(HudAnimator.SHAKE_SECONDS + DT);
        assertEquals(0f, anim.partyShakeX, 0f);
        assertEquals(0f, anim.partyShakeY, 0f);

        frame(DT, new BattleEvent.DamageDealt(CombatantId.ARCHON, 200f, DamageFlavor.NORMAL));
        frame(DT);
        assertTrue(Math.abs(anim.enemyShakeX) + Math.abs(anim.enemyShakeY) > 0.5f, "an Archon hit shakes the plate instead");
        assertEquals(0f, anim.partyShakeX, 0f);
    }

    @Test
    void shakeScalesWithDamageAndFlavor() {
        float graze = peakShake(CombatantId.ARCHON, 5f, DamageFlavor.NORMAL);
        float heavy = peakShake(CombatantId.ARCHON, 300f, DamageFlavor.NORMAL);
        float critical = peakShake(CombatantId.ARCHON, 300f, DamageFlavor.CRITICAL);
        float blocked = peakShake(CombatantId.MONK, 30f, DamageFlavor.BLOCKED);
        float normalOnMonk = peakShake(CombatantId.MONK, 30f, DamageFlavor.NORMAL);
        float parried = peakShake(CombatantId.MONK, 30f, DamageFlavor.PARRIED);

        assertTrue(heavy > graze * 1.5f, "bigger hit, bigger shake: " + graze + " vs " + heavy);
        assertTrue(critical > heavy * 1.3f, "criticals shake harder");
        assertTrue(blocked > 0f && blocked < normalOnMonk * 0.5f, "a block barely moves the window");
        assertEquals(0f, parried, 0f, "a parry does not shake at all");
    }

    @Test
    void shakeFollowsTheHudScale() {
        float small = peakShake(CombatantId.ARCHON, 300f, DamageFlavor.NORMAL);
        freshAnimator();
        animator.setHudScale(3f);
        frame(DT);
        frame(DT, new BattleEvent.DamageDealt(CombatantId.ARCHON, 300f, DamageFlavor.NORMAL));
        float peak = 0f;
        for (int i = 0; i < 18; i++) {
            peak = Math.max(peak, (float) Math.hypot(anim.enemyShakeX, anim.enemyShakeY));
            frame(DT);
        }
        assertEquals(small * 3f, peak, small * 0.01f);
    }

    // ── slides ───────────────────────────────────────────────────────────────

    @Test
    void theCommandWindowWakesEachTimeItOpens() {
        frame(DT);
        assertEquals(1f, anim.commandWake, 1e-6f, "at rest while closed");

        view.commandWindowOpen = true;
        frame(DT);
        assertEquals(0f, anim.commandWake, 1e-6f, "the opening edge starts it veiled, like the static state");
        run(HudAnimator.COMMAND_WAKE_SECONDS / 2f);
        assertTrue(anim.commandWake > 0.5f && anim.commandWake < 1f, "ease-out: past halfway at half time");
        run(HudAnimator.COMMAND_WAKE_SECONDS);
        assertEquals(1f, anim.commandWake, 1e-6f);

        view.commandWindowOpen = false;
        frame(DT);
        view.commandWindowOpen = true;
        frame(DT);
        assertEquals(0f, anim.commandWake, 1e-6f, "and again on the next turn");
    }

    @Test
    void theSubmenuSlidesWhenItOpens() {
        view.commandWindowOpen = true;
        run(0.3f);
        menu.selectRoot(FocusBattleLayout.qiArtsRowIndex());
        assertTrue(menu.openSubmenu());
        frame(DT);
        assertEquals(0f, anim.submenuSlideIn, 1e-6f);
        assertEquals(1f, anim.commandWake, 1e-6f, "the command window stays put");
        run(HudAnimator.SUBMENU_SLIDE_SECONDS + DT);
        assertEquals(1f, anim.submenuSlideIn, 1e-6f);
    }

    @Test
    void theBottomHudSlidesOutForTheCinematicMoments() {
        frame(DT);
        assertEquals(0f, anim.bottomHudSlideOut, 0f);

        view.phase = BattlePhase.INTRO;
        run(HudAnimator.CINEMATIC_SLIDE_SECONDS / 2f);
        assertTrue(anim.bottomHudSlideOut > 0f && anim.bottomHudSlideOut < 1f, "sliding");
        run(HudAnimator.CINEMATIC_SLIDE_SECONDS);
        assertEquals(1f, anim.bottomHudSlideOut, 1e-6f, "gone for the intro");

        view.phase = BattlePhase.RUNNING;
        run(HudAnimator.CINEMATIC_SLIDE_SECONDS + DT);
        assertEquals(0f, anim.bottomHudSlideOut, 1e-6f, "and back when the fight begins");

        // Playtest rule: no action animation ever takes the HUD away, the ultimate included.
        view.phase = BattlePhase.ACTION;
        for (BattleCommand command : BattleCommand.values()) {
            view.currentAction = new ActionView(CombatantId.MONK, command.displayName(), command, null, 0f, 6f);
            run(0.3f);
            assertEquals(0f, anim.bottomHudSlideOut, 0f, command + " never hides the HUD");
        }
    }

    @Test
    void victoryHoldsTheHudOutForTheCinematicThenReturnsIt() {
        frame(DT);
        view.phase = BattlePhase.RESULT;
        view.outcome = BattleOutcome.VICTORY;
        frame(DT, new BattleEvent.Ended(BattleOutcome.VICTORY));
        run(1f);
        assertEquals(1f, anim.bottomHudSlideOut, 1e-6f);
        run(BattleHudRules.VICTORY_CINEMATIC_SECONDS - 1.2f);
        assertEquals(1f, anim.bottomHudSlideOut, 1e-6f, "held for the whole victory cinematic");
        run(0.2f + HudAnimator.CINEMATIC_SLIDE_SECONDS + 2 * DT);
        assertEquals(0f, anim.bottomHudSlideOut, 1e-6f);
        assertTrue(animator.secondsSinceEnd() > BattleHudRules.VICTORY_CINEMATIC_SECONDS);
    }

    @Test
    void defeatDoesNotHideTheHud() {
        view.phase = BattlePhase.RESULT;
        view.outcome = BattleOutcome.DEFEAT;
        frame(DT, new BattleEvent.Ended(BattleOutcome.DEFEAT));
        run(1f);
        assertEquals(0f, anim.bottomHudSlideOut, 0f);
    }

    @Test
    void aBattleThatOpensOnItsIntroStartsWithTheHudAlreadyOut() {
        view.phase = BattlePhase.INTRO;
        animator.reset(view, menu);
        assertEquals(1f, anim.bottomHudSlideOut, 1e-6f, "no one-frame flash of the windows under the transition");
        view.phase = BattlePhase.RUNNING;
        run(HudAnimator.CINEMATIC_SLIDE_SECONDS + DT);
        assertEquals(0f, anim.bottomHudSlideOut, 1e-6f, "they slide in as the intro lands");
    }

    // ── help cross-fade ──────────────────────────────────────────────────────

    @Test
    void theHelpLineFadesOutThenInWhenItChanges() {
        view.commandWindowOpen = true;
        run(0.5f);
        BattleHelpText.Line strike = BattleHelpText.lineFor(view, menu);
        assertEquals(strike, anim.helpLine);
        assertEquals(1f, anim.helpFade, 1e-6f);

        menu.moveDown();
        frame(DT);
        assertEquals(strike, anim.helpLine, "the old line is still the one fading out");
        assertTrue(anim.helpFade < 1f);
        run(HudAnimator.HELP_FADE_OUT_SECONDS + DT);
        assertEquals(BattleHelpText.lineFor(view, menu), anim.helpLine, "swapped at the bottom of the fade");
        assertTrue(anim.helpFade < 1f);
        run(HudAnimator.HELP_FADE_IN_SECONDS + DT);
        assertEquals(1f, anim.helpFade, 1e-6f);
    }

    // ── targeting ────────────────────────────────────────────────────────────

    @Test
    void theTargetCursorBobsWhileAiming() {
        view.commandWindowOpen = true;
        run(0.37f);
        menu.beginTargeting(BattleCommand.STRIKE);
        float min = 1f, max = -1f;
        for (int i = 0; i < 60; i++) {
            frame(DT);
            min = Math.min(min, anim.targetBob);
            max = Math.max(max, anim.targetBob);
        }
        assertTrue(max > 0.9f && min < -0.9f, "the target cursor bobs through its whole range");
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    @Test
    void resetReturnsEveryFieldToRest() {
        view.commandWindowOpen = true;
        view.monk.hp = 50f;
        view.focus = 100f;
        frame(DT);
        view.monk.hp = 20f;
        frame(DT, new BattleEvent.DamageDealt(CombatantId.MONK, 30f, DamageFlavor.CRITICAL),
                new BattleEvent.Ended(BattleOutcome.DEFEAT));
        frame(DT);
        assertTrue(Math.abs(anim.partyShakeX) + Math.abs(anim.partyShakeY) > 0f);
        assertTrue(animator.secondsSinceEnd() >= 0f);

        FakeBattleView next = new FakeBattleView();
        animator.reset(next, new BattleMenuState());
        assertEquals(0f, anim.time, 0f);
        assertEquals(0f, anim.partyShakeX, 0f);
        assertEquals(0f, anim.partyShakeY, 0f);
        assertEquals(1f, anim.commandWake, 1e-6f);
        assertEquals(0f, anim.bottomHudSlideOut, 0f);
        assertTrue(animator.secondsSinceEnd() < 0f);
    }

    @Test
    void theSameInputsGiveTheSameMotionBitForBit() {
        float[] a = trace();
        float[] b = trace();
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(Float.floatToIntBits(a[i]), Float.floatToIntBits(b[i]), "sample " + i);
        }
    }

    /** A scripted half second touching every driver, sampled every frame. */
    private float[] trace() {
        freshAnimator();
        float[] samples = new float[30 * 6];
        for (int i = 0; i < 30; i++) {
            if (i == 3) view.commandWindowOpen = true;
            if (i == 5) {
                view.archon.hp -= 120f;
                frame(0.013f + i * 0.0007f, new BattleEvent.DamageDealt(CombatantId.ARCHON, 120f, DamageFlavor.CRITICAL),
                        new BattleEvent.TurnReady(CombatantId.MONK));
            } else {
                frame(0.013f + i * 0.0007f);
            }
            int o = i * 6;
            samples[o] = anim.enemyShakeX;
            samples[o + 1] = anim.enemyShakeY;
            samples[o + 2] = anim.commandWake;
            samples[o + 3] = anim.helpFade;
            samples[o + 4] = anim.targetBob;
            samples[o + 5] = anim.bottomHudSlideOut;
        }
        assertFalse(samples[6 * 6] == 0f, "the trace really exercised the hit");
        return samples;
    }
}
