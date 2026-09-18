package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.ui.focusBattle.BattleHudRules;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Events in, animation state out — and nothing moves except through {@code update(dt)}. */
class TimedInputStateTest {

    private static void frame(TimedInputState state, FakeBattleView view, float dt, BattleEvent... events) {
        view.events.clear();
        view.events.addAll(List.of(events));
        state.update(view, dt);
        view.events.clear();
    }

    // ─────────────────────────────────────────────── E9

    @Test
    void ringFeedbackAppearsPerGradeAndExpires() {
        for (TimedGrade grade : TimedGrade.values()) {
            TimedInputState state = new TimedInputState();
            FakeBattleView view = new FakeBattleView();
            frame(state, view, 0.016f, new BattleEvent.PromptResolved(PromptKind.RING, grade, 1));
            assertEquals(grade, state.ringGrade());
            assertEquals(0f, state.ringFeedbackAge(), 0f, "fresh on the frame it happens");
            frame(state, view, TimedInputState.RING_FEEDBACK_SECONDS - 0.05f);
            assertEquals(grade, state.ringGrade());
            frame(state, view, 0.06f);
            assertNull(state.ringGrade(), "gone after ~0.35 s");
        }
    }

    @Test
    void aNewRingResultReplacesTheOldOne() {
        TimedInputState state = new TimedInputState();
        FakeBattleView view = new FakeBattleView();
        frame(state, view, 0.016f, new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.MISS, 0));
        frame(state, view, 0.2f, new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.PERFECT, 1));
        assertEquals(TimedGrade.PERFECT, state.ringGrade());
        assertEquals(0f, state.ringFeedbackAge(), 0f);
    }

    // ─────────────────────────────────────────────── E12

    @Test
    void parryEventsMapToParryBlockAndTooEarly() {
        TimedInputState state = new TimedInputState();
        FakeBattleView view = new FakeBattleView();
        frame(state, view, 0.016f, new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.PERFECT, 0));
        assertEquals(TimedInputState.ParryFeedback.PARRY, state.parryFeedback());
        // The parried blow's own damage event must not downgrade the celebration.
        frame(state, view, 0.1f, new BattleEvent.DamageDealt(CombatantId.MONK, 0f, BattleEvent.DamageFlavor.PARRIED));
        assertEquals(TimedInputState.ParryFeedback.PARRY, state.parryFeedback());
        frame(state, view, TimedInputState.PARRY_TEXT_SECONDS);
        assertEquals(TimedInputState.ParryFeedback.NONE, state.parryFeedback());

        frame(state, view, 0.016f, new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.MISS, 0));
        assertEquals(TimedInputState.ParryFeedback.TOO_EARLY, state.parryFeedback());
        // …and the guarded blow that follows the wasted press is still a BLOCK.
        frame(state, view, 0.2f, new BattleEvent.DamageDealt(CombatantId.MONK, 19f, BattleEvent.DamageFlavor.BLOCKED));
        assertEquals(TimedInputState.ParryFeedback.BLOCK, state.parryFeedback());
        assertEquals(0f, state.parryFeedbackAge(), 0f);
        frame(state, view, TimedInputState.BLOCK_SECONDS + 0.01f);
        assertEquals(TimedInputState.ParryFeedback.NONE, state.parryFeedback());

        frame(state, view, 0.016f, new BattleEvent.DamageDealt(CombatantId.MONK, 30f, BattleEvent.DamageFlavor.NORMAL),
                new BattleEvent.DamageDealt(CombatantId.ARCHON, 30f, BattleEvent.DamageFlavor.BLOCKED));
        assertEquals(TimedInputState.ParryFeedback.NONE, state.parryFeedback(), "only the monk's guarded hits");
    }

    // ─────────────────────────────────────────────── E10

    @Test
    void theComboStripLivesFromPromptOpenedUntilAfterComboFinished() {
        TimedInputState state = new TimedInputState();
        FakeBattleView view = TimedScenes.comboView(0, 0f);
        frame(state, view, 0.016f, new BattleEvent.PromptOpened(PromptKind.COMBO));
        assertTrue(state.comboActive());
        assertEquals(0f, state.comboSlide(), 0f, "starts off-screen");
        frame(state, view, TimedInputState.COMBO_SLIDE_SECONDS / 2f);
        assertTrue(state.comboSlide() > 0f && state.comboSlide() < 1f);
        frame(state, view, TimedInputState.COMBO_SLIDE_SECONDS);
        assertEquals(1f, state.comboSlide(), 0f);
        assertEquals(0, state.comboIndex);
        assertEquals(6, state.comboSequence.size());

        // First prompt answered: the grade arrives by event, the next prompt by view.
        FakeBattleView second = TimedScenes.comboView(1, 0.1f, TimedGrade.PERFECT);
        frame(state, second, 0.016f, new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.PERFECT, 0));
        assertEquals(TimedGrade.PERFECT, state.comboResults[0]);
        assertEquals(0f, state.comboStampAge[0], 0f);
        assertEquals(1, state.comboIndex);
        assertEquals(0.1f, state.comboStepElapsed, 0f);
        assertEquals(1, state.comboHits());

        // A miss: the prompt disappears in the same frame the grade arrives; the strip must keep both.
        second.prompt = null;
        frame(state, second, 0.016f, new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.MISS, 1),
                new BattleEvent.ComboFinished(1, false));
        assertEquals(TimedGrade.MISS, state.comboResults[1]);
        assertEquals(-1, state.comboIndex);
        assertTrue(state.comboFinished());
        assertFalse(state.flawlessShowing());
        assertEquals(1, state.comboHits(), "a MISS is not a hit");
        assertEquals(1f, state.comboSlide(), 0f, "holds before leaving");
        frame(state, second, TimedInputState.COMBO_HOLD_SECONDS - 0.05f);
        assertEquals(1f, state.comboSlide(), 0f, "still holding");
        frame(state, second, 0.06f + TimedInputState.COMBO_SLIDE_SECONDS / 2f);
        assertTrue(state.comboSlide() < 1f && state.comboSlide() > 0f, "sliding out");
        frame(state, second, TimedInputState.COMBO_SLIDE_SECONDS);
        assertEquals(0f, state.comboSlide(), 0f);
        assertFalse(state.comboActive());
    }

    @Test
    void aFlawlessStringStaysThroughTheFinisherAndFlashes() {
        TimedInputState state = new TimedInputState();
        FakeBattleView view = TimedScenes.comboView(5, 0.3f, TimedGrade.PERFECT, TimedGrade.PERFECT, TimedGrade.GOOD,
                TimedGrade.PERFECT, TimedGrade.PERFECT);
        frame(state, view, 0.016f, new BattleEvent.PromptOpened(PromptKind.COMBO));
        frame(state, view, 0.3f);
        view.prompt = null; // finisher wait: no prompt, action still running
        frame(state, view, 0.016f, new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.PERFECT, 5));
        frame(state, view, 0.5f);
        assertTrue(state.comboActive() && !state.comboFinished(), "waits for the finisher");
        assertEquals(1f, state.comboSlide(), 0f);
        assertEquals(6, state.comboHits());
        frame(state, view, 0.016f, new BattleEvent.ComboFinished(6, true));
        assertTrue(state.flawlessShowing());
        frame(state, view, TimedInputState.FLAWLESS_SECONDS - 0.1f);
        assertTrue(state.flawlessShowing());
        assertEquals(1f, state.comboSlide(), 0f, "the strip outlasts its own flash");
        frame(state, view, 0.2f);
        assertFalse(state.flawlessShowing());
    }

    @Test
    void aStripIsNeverStrandedWhenTheStringVanishesWithoutAnEvent() {
        TimedInputState state = new TimedInputState();
        FakeBattleView view = TimedScenes.comboView(2, 0.3f, TimedGrade.GOOD, TimedGrade.GOOD);
        frame(state, view, 0.3f); // bound mid-string: no PromptOpened seen
        assertTrue(state.comboActive());
        view.prompt = null;
        view.currentAction = null;
        frame(state, view, 0.016f);
        for (int i = 0; i < 20; i++) frame(state, view, 0.05f);
        assertFalse(state.comboActive());
        assertEquals(0f, state.comboSlide(), 0f);
    }

    // ─────────────────────────────────────────────── E14

    @Test
    void theLetterboxFollowsTheCinematicRuleOverAQuarterSecond() {
        TimedInputState state = new TimedInputState();
        FakeBattleView view = TimedScenes.comboView(0, 0f);
        assertTrue(BattleHudRules.cinematic(view));
        frame(state, view, TimedInputState.LETTERBOX_SECONDS / 2f);
        assertEquals(0.5f, state.letterbox(), 1.0e-4f);
        assertTrue(ScreenFxLayer.letterboxAmount(state) > 0f && ScreenFxLayer.letterboxAmount(state) < 1f);
        frame(state, view, TimedInputState.LETTERBOX_SECONDS);
        assertEquals(1f, state.letterbox(), 0f);
        assertEquals(1f, ScreenFxLayer.letterboxAmount(state), 0f);

        view.prompt = null;
        view.currentAction = null;
        frame(state, view, TimedInputState.LETTERBOX_SECONDS / 2f);
        assertEquals(0.5f, state.letterbox(), 1.0e-4f, "eases out at the same rate");
        frame(state, view, TimedInputState.LETTERBOX_SECONDS);
        assertEquals(0f, state.letterbox(), 0f);

        FakeBattleView intro = new FakeBattleView();
        intro.phase = BattlePhase.INTRO;
        frame(state, intro, 1f);
        assertEquals(1f, state.letterbox(), 0f, "the intro is cinematic too");
    }

    @Test
    void victoryHoldsTheLetterboxThenReleasesIt() {
        TimedInputState state = new TimedInputState();
        FakeBattleView view = new FakeBattleView();
        view.outcome = BattleOutcome.VICTORY;
        view.phase = BattlePhase.RESULT;
        assertFalse(BattleHudRules.cinematic(view), "the rule itself does not cover the victory hold");
        frame(state, view, 0.016f, new BattleEvent.Ended(BattleOutcome.VICTORY));
        assertEquals(BattleHudRules.VICTORY_CINEMATIC_SECONDS, state.victoryHold(), 0f);
        frame(state, view, 0.5f);
        assertEquals(1f, state.letterbox(), 0f);
        frame(state, view, BattleHudRules.VICTORY_CINEMATIC_SECONDS - 1f);
        assertEquals(1f, state.letterbox(), 0f, "still held");
        frame(state, view, 0.6f);
        frame(state, view, TimedInputState.LETTERBOX_SECONDS + 0.01f);
        assertEquals(0f, state.letterbox(), 0f, "released after the hold");
        assertEquals(0f, state.defeatFade(), 0f);
    }

    @Test
    void lowHpChilledAndFocusReadyTriggerTheirEffects() {
        TimedInputState state = new TimedInputState();
        FakeBattleView view = new FakeBattleView();
        frame(state, view, 1f);
        assertTrue(state.idle(), "a healthy, unchilled, unfocused monk triggers nothing");

        view.monk.hp = view.monk.maxHp * 0.31f;
        frame(state, view, 1f);
        assertEquals(0f, state.lowHp(), 0f, "0.3 is the threshold");
        view.monk.hp = view.monk.maxHp * 0.29f;
        frame(state, view, TimedInputState.FX_EASE_SECONDS / 2f);
        assertEquals(0.5f, state.lowHp(), 1.0e-4f, "eased, not snapped");
        frame(state, view, 1f);
        assertEquals(1f, state.lowHp(), 0f);

        view.monk.statuses.add(new StatusView(BattleStatus.CHILLED, 9f, 1));
        view.focus = view.maxFocus;
        frame(state, view, 1f);
        assertEquals(1f, state.frost(), 0f);
        assertEquals(1f, state.focusAura(), 0f);

        view.monk.statuses.clear();
        view.focus = 50f;
        view.monk.hp = view.monk.maxHp;
        frame(state, view, 1f);
        assertTrue(state.idle(), "and they all ease back out");
    }

    @Test
    void theHeartbeatQuickensAsHpDrops() {
        FakeBattleView hurt = new FakeBattleView();
        hurt.monk.hp = hurt.monk.maxHp * 0.28f;
        FakeBattleView dying = new FakeBattleView();
        dying.monk.hp = dying.monk.maxHp * 0.03f;
        TimedInputState a = new TimedInputState(), b = new TimedInputState();
        for (int i = 0; i < 60; i++) {
            frame(a, hurt, 1f / 60f);
            frame(b, dying, 1f / 60f);
        }
        assertTrue(b.lowHpPhase > a.lowHpPhase * 1.8f, "near death the pulse runs much faster");
        assertTrue(TimedInputState.lowHpSeverity(dying) > TimedInputState.lowHpSeverity(hurt));
    }

    @Test
    void criticalHitsFlashBrieflyAndDefeatFadesSlowly() {
        TimedInputState state = new TimedInputState();
        FakeBattleView view = new FakeBattleView();
        frame(state, view, 0.016f, new BattleEvent.DamageDealt(CombatantId.ARCHON, 99f, BattleEvent.DamageFlavor.CRITICAL));
        assertTrue(state.critFlashing());
        assertEquals(CombatantId.ARCHON, state.critFlashTarget);
        frame(state, view, TimedInputState.CRIT_FLASH_SECONDS + 0.01f);
        assertFalse(state.critFlashing());
        frame(state, view, 0.016f, new BattleEvent.DamageDealt(CombatantId.ARCHON, 99f, BattleEvent.DamageFlavor.NORMAL));
        assertFalse(state.critFlashing());

        view.monk.hp = 0f;
        view.outcome = BattleOutcome.DEFEAT;
        frame(state, view, 0.016f, new BattleEvent.Ended(BattleOutcome.DEFEAT));
        frame(state, view, TimedInputState.DEFEAT_FADE_SECONDS / 2f);
        assertEquals(0.5f, state.defeatFade(), 1.0e-3f);
        frame(state, view, TimedInputState.DEFEAT_FADE_SECONDS);
        assertEquals(1f, state.defeatFade(), 0f);
        assertEquals(0f, state.lowHp(), 0f, "the red heartbeat yields to the grey fade");
        assertEquals(0f, state.letterbox(), 0f, "no letterbox for a defeat");
    }

    // ─────────────────────────────────────────────── Whole-state rules

    @Test
    void resetClearsEverything() {
        TimedInputState state = new TimedInputState();
        FakeBattleView view = TimedScenes.comboView(2, 0.3f, TimedGrade.GOOD, TimedGrade.GOOD);
        view.monk.hp = 5f;
        view.focus = 100f;
        view.monk.statuses.add(new StatusView(BattleStatus.CHILLED, 9f, 1));
        frame(state, view, 0.5f, new BattleEvent.PromptOpened(PromptKind.COMBO),
                new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.GOOD, 0),
                new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.PERFECT, 0),
                new BattleEvent.DamageDealt(CombatantId.MONK, 50f, BattleEvent.DamageFlavor.CRITICAL),
                new BattleEvent.ComboFinished(6, true),
                new BattleEvent.Ended(BattleOutcome.DEFEAT));
        frame(state, view, 0.2f);
        assertFalse(state.idle());
        state.reset();
        assertTrue(state.idle());
        assertEquals(0f, state.time, 0f);
        assertEquals(0f, state.victoryHold(), 0f);
        assertEquals(0, state.comboSequence.size());
        assertEquals(0, state.comboHits());
        assertFalse(state.defeated);
    }

    @Test
    void identicalFramesGiveIdenticalState() {
        TimedInputState a = new TimedInputState(), b = new TimedInputState();
        for (TimedInputState state : new TimedInputState[]{a, b}) {
            FakeBattleView view = TimedScenes.comboView(1, 0.2f, TimedGrade.PERFECT);
            view.monk.hp = 20f;
            frame(state, view, 0.016f, new BattleEvent.PromptOpened(PromptKind.COMBO));
            for (int i = 0; i < 25; i++) frame(state, view, 0.0167f);
            frame(state, view, 0.0167f, new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.GOOD, 1));
        }
        assertEquals(a.time, b.time, 0f);
        assertEquals(a.comboSlide, b.comboSlide, 0f);
        assertEquals(a.lowHpPhase, b.lowHpPhase, 0f);
        assertEquals(a.letterbox, b.letterbox, 0f);
        assertEquals(a.comboStampAge[1], b.comboStampAge[1], 0f);
    }

    @Test
    void aFrameRenderedTwiceDoesNotReplayItsEvents() {
        // The real model hands out the same immutable list until its next update.
        List<BattleEvent> once = List.of(new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.PERFECT, 0));
        FakeBattleView fake = new FakeBattleView();
        BattleView view = (BattleView) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{BattleView.class},
                (proxy, method, args) -> method.getName().equals("frameEvents") ? once
                        : method.isDefault() ? java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args)
                        : method.invoke(fake, args));
        TimedInputState state = new TimedInputState();
        state.update(view, 0.016f);
        state.update(view, 0.2f);
        assertEquals(0.2f, state.ringFeedbackAge(), 1.0e-5f, "age kept advancing: the event was not re-consumed");
    }

    @Test
    void nullAndNegativeInputsAreHarmless() {
        TimedInputState state = new TimedInputState();
        state.update(null, 0.1f);
        state.update(new FakeBattleView(), -5f);
        assertEquals(0.1f, state.time, 1.0e-6f);
        assertTrue(state.idle());
    }
}
