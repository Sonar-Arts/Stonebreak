package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.ActionView;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleEvent.DamageFlavor;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.battle.api.TelegraphView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The event-driven motion layer: every BattleHudAnimState field, its trigger and its decay. */
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

    // ── ghost trails ─────────────────────────────────────────────────────────

    @Test
    void theGhostHoldsThePreHitValueThenEasesDownToTheRealOne() {
        frame(DT);
        assertTrue(anim.archonGhostHpFraction < 0f, "no ghost at rest");

        view.archon.hp = 450f;   // 900 → 450
        frame(DT, new BattleEvent.DamageDealt(CombatantId.ARCHON, 450f, DamageFlavor.NORMAL));
        assertEquals(1f, anim.archonGhostHpFraction, 1e-5f, "the trail starts at the pre-hit fraction");

        run(HudAnimator.GHOST_HOLD_SECONDS - 0.05f);
        assertEquals(1f, anim.archonGhostHpFraction, 1e-5f, "and holds");

        run(0.05f + HudAnimator.GHOST_EASE_SECONDS * 0.5f);
        assertTrue(anim.archonGhostHpFraction > 0.5f && anim.archonGhostHpFraction < 1f,
                "then eases: " + anim.archonGhostHpFraction);
        float mid = anim.archonGhostHpFraction;
        frame(DT);
        assertTrue(anim.archonGhostHpFraction < mid, "monotonically down");

        run(HudAnimator.GHOST_EASE_SECONDS);
        assertTrue(anim.archonGhostHpFraction < 0f, "and disappears once it has caught up");
        assertTrue(anim.monkGhostHpFraction < 0f, "the other bar never moved");
    }

    @Test
    void aSecondHitKeepsTheTrailsHeadAndRestartsTheHold() {
        frame(DT);
        view.monk.hp = 140f;
        frame(DT, new BattleEvent.DamageDealt(CombatantId.MONK, 40f, DamageFlavor.NORMAL));
        run(0.2f);
        view.monk.hp = 100f;
        frame(DT, new BattleEvent.DamageDealt(CombatantId.MONK, 40f, DamageFlavor.NORMAL));
        assertEquals(1f, anim.monkGhostHpFraction, 1e-5f, "still the fraction before the first hit");
        run(HudAnimator.GHOST_HOLD_SECONDS - 0.05f);
        assertEquals(1f, anim.monkGhostHpFraction, 1e-5f, "the second hit bought a fresh hold");
    }

    @Test
    void aHealSnapsTheGhostAway() {
        frame(DT);
        view.monk.hp = 90f;
        frame(DT, new BattleEvent.DamageDealt(CombatantId.MONK, 90f, DamageFlavor.NORMAL));
        assertTrue(anim.monkGhostHpFraction > 0.9f);
        view.monk.hp = 180f;
        frame(DT, new BattleEvent.Healed(CombatantId.MONK, 90f));
        assertTrue(anim.monkGhostHpFraction < 0f, "healed past the trail: nothing left to trail");
    }

    // ── shake + flash ────────────────────────────────────────────────────────

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
    void theDamagedSideShakesAndFlashesThenSettles() {
        frame(DT);
        frame(DT, new BattleEvent.DamageDealt(CombatantId.MONK, 30f, DamageFlavor.NORMAL));
        assertTrue(anim.partyHitFlash > 0.6f, "red flash on the party window");
        assertEquals(0f, anim.enemyHitFlash, 0f, "the Archon was not hit");
        assertEquals(0f, anim.enemyShakeX, 0f);
        frame(DT);
        assertTrue(Math.abs(anim.partyShakeX) + Math.abs(anim.partyShakeY) > 0.5f, "the window moves");

        float flash = anim.partyHitFlash;
        run(0.1f);
        assertTrue(anim.partyHitFlash < flash, "decaying");
        run(HudAnimator.SHAKE_SECONDS);
        assertEquals(0f, anim.partyShakeX, 0f);
        assertEquals(0f, anim.partyShakeY, 0f);
        assertEquals(0f, anim.partyHitFlash, 0f);

        frame(DT, new BattleEvent.DamageDealt(CombatantId.ARCHON, 200f, DamageFlavor.NORMAL));
        assertTrue(anim.enemyHitFlash > 0.6f, "an Archon hit flashes the enemy plate instead");
        assertEquals(0f, anim.partyHitFlash, 0f);
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

        freshAnimator();
        frame(DT, new BattleEvent.DamageDealt(CombatantId.MONK, 30f, DamageFlavor.PARRIED));
        assertEquals(0f, anim.partyHitFlash, 0f, "nor flash");
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
    void theCommandWindowSlidesInEachTimeItOpens() {
        frame(DT);
        assertEquals(1f, anim.commandSlideIn, 1e-6f, "at rest while closed");

        view.commandWindowOpen = true;
        frame(DT);
        assertEquals(0f, anim.commandSlideIn, 1e-6f, "the opening edge parks it off-screen");
        run(HudAnimator.COMMAND_SLIDE_SECONDS / 2f);
        assertTrue(anim.commandSlideIn > 0.5f && anim.commandSlideIn < 1f, "ease-out: past halfway at half time");
        run(HudAnimator.COMMAND_SLIDE_SECONDS);
        assertEquals(1f, anim.commandSlideIn, 1e-6f);

        view.commandWindowOpen = false;
        frame(DT);
        view.commandWindowOpen = true;
        frame(DT);
        assertEquals(0f, anim.commandSlideIn, 1e-6f, "and again on the next turn");
    }

    @Test
    void theSubmenuSlidesWhenItOpens() {
        view.commandWindowOpen = true;
        run(0.3f);
        menu.selectRoot(FocusBattleLayout.qiArtsRowIndex());
        assertTrue(menu.openSubmenu());
        frame(DT);
        assertEquals(0f, anim.submenuSlideIn, 1e-6f);
        assertEquals(1f, anim.commandSlideIn, 1e-6f, "the command window stays put");
        run(HudAnimator.SUBMENU_SLIDE_SECONDS + DT);
        assertEquals(1f, anim.submenuSlideIn, 1e-6f);
    }

    @Test
    void theBottomHudSlidesOutForTheCinematicMoments() {
        frame(DT);
        assertEquals(0f, anim.bottomHudSlideOut, 0f);

        view.phase = BattlePhase.ACTION;
        view.currentAction = new ActionView(CombatantId.MONK, "Focus Combo", BattleCommand.FOCUS_COMBO, null, 0f, 6f);
        run(HudAnimator.CINEMATIC_SLIDE_SECONDS / 2f);
        assertTrue(anim.bottomHudSlideOut > 0f && anim.bottomHudSlideOut < 1f, "sliding");
        run(HudAnimator.CINEMATIC_SLIDE_SECONDS);
        assertEquals(1f, anim.bottomHudSlideOut, 1e-6f, "gone for the Focus Combo");

        view.currentAction = null;
        view.phase = BattlePhase.RUNNING;
        run(HudAnimator.CINEMATIC_SLIDE_SECONDS + DT);
        assertEquals(0f, anim.bottomHudSlideOut, 1e-6f, "and back afterwards");

        view.currentAction = new ActionView(CombatantId.MONK, "Strike", BattleCommand.STRIKE, null, 0f, 1f);
        run(0.3f);
        assertEquals(0f, anim.bottomHudSlideOut, 0f, "an ordinary action never hides the HUD");
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

    // ── gauges ───────────────────────────────────────────────────────────────

    @Test
    void theAtbFlashesWhiteWhenTheMonksTurnComes() {
        run(0.5f);
        float resting = anim.atbFullFlash;
        assertTrue(resting < 0.6f, "a calm blink at rest");
        frame(DT, new BattleEvent.TurnReady(CombatantId.ARCHON));
        assertTrue(anim.atbFullFlash < 0.6f, "the Archon's turn is not the monk's flash");
        frame(DT, new BattleEvent.TurnReady(CombatantId.MONK));
        assertEquals(1f, anim.atbFullFlash, 1e-5f, "full white on the frame the gauge fills");
        run(HudAnimator.ATB_FLASH_SECONDS + DT);
        assertTrue(anim.atbFullFlash < 0.6f, "then back to the blink");
    }

    @Test
    void focusShimmersWhileReadyAndPulsesOnceWhenItFills() {
        frame(DT);
        assertTrue(anim.focusShimmer < 0f, "no band below 100%");
        assertEquals(0f, anim.focusFullPulse, 0f);

        view.focus = 100f;
        frame(DT, new BattleEvent.FocusFull());
        assertEquals(1f, anim.focusFullPulse, 1e-5f);
        assertTrue(anim.focusShimmer >= 0f && anim.focusShimmer < 1f);
        float first = anim.focusShimmer;
        run(0.2f);
        assertNotEquals(first, anim.focusShimmer, "the band travels");
        run(HudAnimator.FOCUS_PULSE_SECONDS);
        assertEquals(0f, anim.focusFullPulse, 0f, "the pulse is one-off");
        assertTrue(anim.focusShimmer >= 0f, "the shimmer loops for as long as Focus is full");

        view.focus = 0f;
        frame(DT);
        assertTrue(anim.focusShimmer < 0f);
    }

    @Test
    void qiPipsPopOnGainAndShatterOnSpend() {
        frame(DT, new BattleEvent.QiChanged(1, 4));
        assertEquals(1f, anim.qiPipPop, 1e-5f);
        assertEquals(0f, anim.qiSpendFlash, 0f);
        run(HudAnimator.QI_POP_SECONDS + DT);
        assertEquals(0f, anim.qiPipPop, 0f);

        view.qi = 2;
        frame(DT, new BattleEvent.QiChanged(-2, 2));
        assertEquals(1f, anim.qiSpendFlash, 1e-5f);
        assertEquals(2, anim.qiSpendFirstPip, "pips 2 and 3 were the ones emptied");
        assertEquals(2, anim.qiSpendCount);
        assertEquals(0f, anim.qiPipPop, 0f, "a spend is not a pop");
        run(HudAnimator.QI_SPEND_SECONDS + DT);
        assertEquals(0f, anim.qiSpendFlash, 0f);
    }

    @Test
    void theParryMarkerPulsesOnlyWhileGuardingATelegraph() {
        TelegraphView overhead = new TelegraphView(EnemyAction.OVERHEAD, 0.2f, 1.03f, 0.78f, 1.03f, false);
        view.telegraph = overhead;
        run(0.2f);
        assertEquals(0f, anim.parryMarkerPulse, 0f, "not guarding");

        view.monk.statuses.add(new StatusView(BattleStatus.GUARDING, -1f, 1));
        float min = 1f, max = 0f;
        for (int i = 0; i < 60; i++) {
            frame(DT);
            min = Math.min(min, anim.parryMarkerPulse);
            max = Math.max(max, anim.parryMarkerPulse);
        }
        assertTrue(max > 0.9f && min < 0.1f, "pulses across its range: " + min + ".." + max);

        view.telegraph = new TelegraphView(EnemyAction.OVERHEAD, 0.2f, 1.03f, 0.78f, 1.03f, true);
        frame(DT);
        assertEquals(0f, anim.parryMarkerPulse, 0f, "a cancelled telegraph has nothing to parry");
        view.telegraph = null;
        frame(DT);
        assertEquals(0f, anim.parryMarkerPulse, 0f);
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
    void theListCursorHoldsStillWhileTheTargetCursorBobs() {
        view.commandWindowOpen = true;
        run(0.37f);
        assertNotEquals(0f, anim.cursorBob);
        menu.beginTargeting(BattleCommand.STRIKE);
        float min = 1f, max = -1f;
        for (int i = 0; i < 60; i++) {
            frame(DT);
            assertEquals(0f, anim.cursorBob, 0f);
            assertEquals(0f, anim.selectedPulse, 0f);
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
                new BattleEvent.QiChanged(-1, 2), new BattleEvent.FocusFull(),
                new BattleEvent.Ended(BattleOutcome.DEFEAT));
        frame(DT);
        assertTrue(anim.partyHitFlash > 0f && anim.monkGhostHpFraction > 0f && anim.qiSpendFlash > 0f);

        FakeBattleView next = new FakeBattleView();
        animator.reset(next, new BattleMenuState());
        assertEquals(0f, anim.time, 0f);
        assertTrue(anim.monkGhostHpFraction < 0f && anim.archonGhostHpFraction < 0f);
        assertEquals(0f, anim.partyShakeX, 0f);
        assertEquals(0f, anim.partyHitFlash, 0f);
        assertEquals(0f, anim.qiSpendFlash, 0f);
        assertEquals(0f, anim.focusFullPulse, 0f);
        assertEquals(1f, anim.commandSlideIn, 1e-6f);
        assertEquals(0f, anim.bottomHudSlideOut, 0f);
        assertTrue(animator.secondsSinceEnd() < 0f);

        // The retried battle's full HP must not read as damage against the old battle's bar.
        animator.update(DT, next, new BattleMenuState(), List.of());
        assertTrue(anim.monkGhostHpFraction < 0f);
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
        float[] samples = new float[30 * 8];
        for (int i = 0; i < 30; i++) {
            if (i == 3) view.commandWindowOpen = true;
            if (i == 5) {
                view.archon.hp -= 120f;
                frame(0.013f + i * 0.0007f, new BattleEvent.DamageDealt(CombatantId.ARCHON, 120f, DamageFlavor.CRITICAL),
                        new BattleEvent.TurnReady(CombatantId.MONK), new BattleEvent.QiChanged(1, 4));
            } else {
                frame(0.013f + i * 0.0007f);
            }
            int o = i * 8;
            samples[o] = anim.archonGhostHpFraction;
            samples[o + 1] = anim.enemyShakeX;
            samples[o + 2] = anim.enemyShakeY;
            samples[o + 3] = anim.enemyHitFlash;
            samples[o + 4] = anim.commandSlideIn;
            samples[o + 5] = anim.atbFullFlash;
            samples[o + 6] = anim.qiPipPop;
            samples[o + 7] = anim.cursorBob;
        }
        assertFalse(samples[5 * 8 + 3] == 0f, "the trace really exercised the hit");
        return samples;
    }
}
