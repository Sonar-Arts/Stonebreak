package com.stonebreak.battle;

import com.stonebreak.battle.api.ActorPose;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TimedGrade;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.stonebreak.battle.BattleDriver.DASH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Focus gauge and the Focus Combo: one continuous clip that waits for the player before each contact. */
class FocusComboTest {

    private static final BattleClip CLIP = MonkClips.FOCUS_COMBO;
    private static final float LEAD = 0.45f;
    private static final float FREEZE_OFFSET = 0.08f;
    private static final float STEP = 0.9f;

    private static BattleDriver monkOnly(long seed) {
        return new BattleDriver(BattleDriver.passiveArchon(BattleDriver.exact()), seed).start();
    }

    /** Full Focus, combo submitted, nothing stepped yet (action clock 0). */
    private static BattleDriver comboSubmitted(long seed) {
        BattleDriver d = monkOnly(seed);
        d.awaitWindow();
        d.sim.context().focus.gain(100f);
        d.step(BattleDriver.DT);
        d.forget();
        assertTrue(d.sim.submit(BattleCommand.FOCUS_COMBO));
        return d;
    }

    /** Runs to the update that opens the first prompt (step clock within {@link BattleDriver#EPS} of 0). */
    private static BattleDriver comboAtFirstPrompt(long seed) {
        BattleDriver d = comboSubmitted(seed);
        d.runActionTo(DASH + CLIP.cue(0) - LEAD + BattleDriver.EPS);
        assertEquals(0f, combo(d).stepElapsed(), 2f * BattleDriver.EPS);
        return d;
    }

    private static PromptView.Combo combo(BattleDriver d) {
        return assertInstanceOf(PromptView.Combo.class, d.sim.prompt());
    }

    private static ComboDirection wrong(ComboDirection right) {
        return right == ComboDirection.UP ? ComboDirection.DOWN : ComboDirection.UP;
    }

    private static float clipTime(BattleDriver d) {
        ActorPose pose = d.sim.monk().pose();
        assertEquals("focus_combo", pose.sbeState());
        return pose.clipTime();
    }

    /** Steps until the next prompt is open, answers it at once (always before the freeze point). */
    private static void answerNextPromptEarly(BattleDriver d, List<ComboDirection> sequence, int index) {
        assertTrue(d.runUntil(s -> s.prompt() != null, 2f));
        assertEquals(index, combo(d).index());
        d.sim.pressDirection(sequence.get(index));
    }

    @Test
    void focusFullIsRaisedOnceWhenTheGaugeTopsOut() {
        BattleDriver d = monkOnly(1);
        d.sim.context().focus.gain(95f);
        d.command(BattleCommand.STRIKE);
        d.finishAction();
        assertEquals(100f, d.sim.focus());
        assertTrue(d.sim.focusReady());
        assertEquals(new BattleEvent.FocusChanged(5f, 100f), d.last(BattleEvent.FocusChanged.class));
        assertEquals(1, d.count(BattleEvent.FocusFull.class));

        int changes = d.count(BattleEvent.FocusChanged.class);
        d.command(BattleCommand.STRIKE);
        d.finishAction();
        assertEquals(changes, d.count(BattleEvent.FocusChanged.class), "a full gauge does not change");
        assertEquals(1, d.count(BattleEvent.FocusFull.class));
    }

    @Test
    void startingTheComboSpendsAllFocusAndTheFirstPromptOpensBeforeTheFirstContact() {
        BattleDriver d = comboSubmitted(1);
        assertEquals(0f, d.sim.focus());
        assertEquals(0f, d.sim.monk().atb());
        assertNull(d.sim.prompt());
        assertEquals(DASH + CLIP.duration() + DASH, d.sim.currentAction().duration(), 1.0e-5f);

        d.runActionTo(DASH - 0.01f);
        assertEquals("combat_dash", d.sim.monk().pose().sbeState());
        d.runActionTo(DASH + CLIP.cue(0) - LEAD - 0.01f);
        assertNull(d.sim.prompt(), "the clip has started, the first prompt has not");
        assertEquals(1f, d.sim.monk().pose().dashProgress());
        assertEquals(CLIP.cue(0) - LEAD - 0.01f, clipTime(d), 1.0e-4f);

        d.runActionTo(DASH + CLIP.cue(0) - LEAD + 0.01f);
        PromptView.Combo prompt = combo(d);
        assertEquals(6, prompt.sequence().size());
        assertEquals(0, prompt.index());
        assertEquals(0.01f, prompt.stepElapsed(), 1.0e-4f);
        assertEquals(STEP, prompt.stepDuration());
        assertTrue(prompt.results().isEmpty());
        assertFalse(d.sim.promptSafeRequired(), "the camera may cut during a combo");

        assertTrue(d.log.contains(new BattleEvent.FocusChanged(-100f, 0f)));
        assertEquals(List.of(new BattleEvent.PromptOpened(PromptKind.COMBO)), d.all(BattleEvent.PromptOpened.class));
    }

    @Test
    void theStringIsSeeded() {
        assertEquals(combo(comboAtFirstPrompt(21)).sequence(), combo(comboAtFirstPrompt(21)).sequence());
        boolean differs = false;
        for (long seed = 0; seed < 5 && !differs; seed++) {
            differs = !combo(comboAtFirstPrompt(seed)).sequence().equals(combo(comboAtFirstPrompt(seed + 100)).sequence());
        }
        assertTrue(differs);
    }

    @Test
    void anAnswerBeforeTheFreezeIsPerfectAndTheClipNeverStops() {
        BattleDriver d = comboAtFirstPrompt(3);
        List<ComboDirection> sequence = combo(d).sequence();
        d.step(0.2f);
        d.sim.pressDirection(sequence.get(0));
        assertNull(d.sim.prompt(), "answered: the blow is on its way");
        d.step(BattleDriver.EPS);
        assertEquals(List.of(new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.PERFECT, 0)),
                d.all(BattleEvent.PromptResolved.class));
        assertEquals(0, d.count(BattleEvent.Impact.class), "graded at the press, landed on the contact");

        d.assertImpactAt(DASH + CLIP.cue(0)); // zero freeze: action time == dash + clip time
        assertEquals(CLIP.cue(0), clipTime(d), 3f * BattleDriver.EPS);
        assertEquals(List.of(new BattleEvent.Impact(CombatantId.MONK, CombatantId.ARCHON, 0, 6)),
                d.all(BattleEvent.Impact.class));
        assertEquals(44f, d.last(BattleEvent.DamageDealt.class).amount());
        assertEquals(1, d.sim.stats().comboHits());

        d.runActionTo(DASH + CLIP.cue(1) - LEAD + 0.01f);
        PromptView.Combo next = combo(d);
        assertEquals(1, next.index());
        assertEquals(List.of(TimedGrade.PERFECT), next.results());
        assertEquals(1, d.count(BattleEvent.PromptOpened.class), "announced once per string, not per prompt");
    }

    @Test
    void anUnansweredPromptFreezesTheClipJustShortOfTheContact() {
        BattleDriver d = comboAtFirstPrompt(3);
        List<ComboDirection> sequence = combo(d).sequence();
        float freezeAt = CLIP.cue(0) - FREEZE_OFFSET;

        d.step(LEAD - FREEZE_OFFSET - 0.01f);
        assertEquals(freezeAt - 0.01f, clipTime(d), 1.0e-3f, "still running");
        d.step(0.21f); // 0.2 s past the freeze point
        assertEquals(freezeAt, clipTime(d), "held");
        float actionClock = d.sim.currentAction().elapsed();
        d.step(0.1f);
        assertEquals(freezeAt, clipTime(d), "the clip holds while the action and step clocks run on");
        assertEquals(actionClock + 0.1f, d.sim.currentAction().elapsed(), 1.0e-5f);
        assertEquals(LEAD - FREEZE_OFFSET + 0.3f, combo(d).stepElapsed(), 1.0e-3f);
        assertEquals(0, d.count(BattleEvent.Impact.class));

        d.sim.pressDirection(sequence.get(0)); // during the freeze: GOOD
        d.step(BattleDriver.EPS);
        assertEquals(List.of(new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.GOOD, 0)),
                d.all(BattleEvent.PromptResolved.class));
        // The clip runs on from where it was held: the blow lands FREEZE_OFFSET later, on its contact frame.
        float resumedAt = d.sim.currentAction().elapsed() - BattleDriver.EPS;
        d.assertImpactAt(resumedAt + FREEZE_OFFSET);
        assertEquals(CLIP.cue(0), clipTime(d), 3f * BattleDriver.EPS);
        assertEquals(35f, d.last(BattleEvent.DamageDealt.class).amount());
        assertEquals(DASH + CLIP.duration() + DASH + 0.3f, d.sim.currentAction().duration(), 1.0e-3f,
                "the projection grows by the time spent frozen");
    }

    @Test
    void aWrongDirectionEndsTheStringAndTheMonkBreaksOff() {
        BattleDriver d = comboAtFirstPrompt(3);
        List<ComboDirection> sequence = combo(d).sequence();
        answerNextPromptEarly(d, sequence, 0);
        answerNextPromptEarly(d, sequence, 1);
        assertTrue(d.runUntil(s -> s.prompt() != null, 2f));
        d.step(0.1f);
        d.sim.pressDirection(wrong(sequence.get(2)));
        assertNull(d.sim.prompt());
        assertEquals("combat_idle", d.sim.monk().pose().sbeState(), "breaks off at once");
        assertEquals(1f, d.sim.monk().pose().dashProgress());
        d.sim.pressDirection(sequence.get(2)); // too late, the string is over
        d.step(BattleDriver.DT);

        assertEquals(List.of(
                new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.PERFECT, 0),
                new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.PERFECT, 1),
                new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.MISS, 2)),
                d.all(BattleEvent.PromptResolved.class));
        assertEquals(List.of(44f, 44f), d.all(BattleEvent.DamageDealt.class).stream()
                .map(BattleEvent.DamageDealt::amount).toList());
        assertEquals(List.of(new BattleEvent.Impact(CombatantId.MONK, CombatantId.ARCHON, 0, 6),
                new BattleEvent.Impact(CombatantId.MONK, CombatantId.ARCHON, 1, 6)), d.all(BattleEvent.Impact.class));
        assertEquals(new BattleEvent.ComboFinished(2, false), d.last(BattleEvent.ComboFinished.class));
        assertEquals(2, d.sim.stats().comboHits());
        assertEquals(0f, d.sim.focus(), "combo hits do not refill the gauge they spent");

        // Break-off: 0.3 s in the ready stance, then the dash home.
        d.run(0.4f);
        assertEquals("combat_dash", d.sim.monk().pose().sbeState());
        assertEquals(BattlePhase.ACTION, d.sim.phase());
        d.run(0.5f);
        assertEquals(BattlePhase.RUNNING, d.sim.phase());
        assertEquals(0f, d.sim.monk().pose().dashProgress());
        assertEquals("combat_idle", d.sim.monk().pose().sbeState());
        assertEquals(1, d.count(BattleEvent.ComboFinished.class));
    }

    @Test
    void aPromptLeftUnansweredTimesOutAsAMiss() {
        BattleDriver d = comboAtFirstPrompt(3);
        d.step(STEP - 0.01f);
        assertEquals(0, d.count(BattleEvent.PromptResolved.class));
        assertEquals(CLIP.cue(0) - FREEZE_OFFSET, clipTime(d), "frozen the whole time");
        d.step(0.02f);
        assertEquals(List.of(new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.MISS, 0)),
                d.all(BattleEvent.PromptResolved.class));
        assertEquals(new BattleEvent.ComboFinished(0, false), d.last(BattleEvent.ComboFinished.class));
        assertEquals(900f, d.sim.archon().hp());
        assertEquals(0, d.count(BattleEvent.Impact.class));
        assertEquals("combat_idle", d.sim.monk().pose().sbeState());
    }

    @Test
    void everyBlowOfAFlawlessStringLandsOnItsContactAndTheSixthIsTheFinisher() {
        BattleDriver d = comboSubmitted(8);
        List<ComboDirection> sequence = null;
        float previousClip = -1f;
        for (int i = 0; i < 6; i++) {
            d.runActionTo(DASH + CLIP.cue(i) - LEAD + 0.05f);
            if (sequence == null) sequence = combo(d).sequence();
            assertEquals(i, combo(d).index());
            d.sim.pressDirection(sequence.get(i));
            assertTrue(clipTime(d) > previousClip, "one clip, always moving forward: never restarted");
            previousClip = clipTime(d);

            d.assertImpactAt(DASH + CLIP.cue(i)); // never frozen, so the action clock is dash + clip time
            assertEquals(CLIP.cue(i), clipTime(d), 3f * BattleDriver.EPS);
            assertEquals(new BattleEvent.Impact(CombatantId.MONK, CombatantId.ARCHON, i, 6),
                    d.last(BattleEvent.Impact.class));
            if (i == MonkClips.FOCUS_COMBO_KICK_CONTACT) {
                assertEquals(BattleConfig.Melee.DEFAULTS.kickStandoff(), d.sim.monk().pose().recoil(),
                        "the front kick is thrown from kicking distance");
            } else {
                assertEquals(0f, d.sim.monk().pose().recoil(), "punches and the palm from punching distance");
            }
        }
        assertNull(d.sim.prompt());
        assertEquals(6, d.count(BattleEvent.Impact.class), "the finisher IS the sixth contact, not a seventh blow");
        assertEquals(44f + 150f, d.last(BattleEvent.DamageDealt.class).amount());
        assertEquals(new BattleEvent.ComboFinished(6, true), d.last(BattleEvent.ComboFinished.class));
        int impact = d.indexOf(e -> e.equals(new BattleEvent.Impact(CombatantId.MONK, CombatantId.ARCHON, 5, 6)));
        assertInstanceOf(BattleEvent.DamageDealt.class, d.log.get(impact + 1));
        assertInstanceOf(BattleEvent.ComboFinished.class, d.log.get(impact + 2));
        assertEquals(900f - 6 * 44f - 150f, d.sim.archon().hp());
        assertEquals(6, d.sim.stats().comboHits());

        // The rest of the clip plays out, then the dash home.
        d.runActionTo(DASH + CLIP.duration() - 0.05f);
        assertEquals(CLIP.duration() - 0.05f, clipTime(d), 1.0e-3f);
        d.runActionTo(DASH + CLIP.duration() + 0.05f);
        assertEquals("combat_dash", d.sim.monk().pose().sbeState());
        assertEquals(BattlePhase.ACTION, d.sim.phase());
        d.step(DASH);
        assertEquals(BattlePhase.RUNNING, d.sim.phase());
        assertEquals(DASH + CLIP.duration() + DASH, d.all(BattleEvent.ActionStarted.class).get(0).duration(), 1.0e-5f);
    }

    @Test
    void theMonkStepsOutForTheFrontKickAndBackInForThePalm() {
        BattleDriver d = comboSubmitted(8);
        List<ComboDirection> sequence = null;
        for (int i = 0; i < 6; i++) {
            d.runActionTo(DASH + CLIP.cue(i) - LEAD + 0.02f);
            if (sequence == null) sequence = combo(d).sequence();
            if (i == 4) {
                float recoil = d.sim.monk().pose().recoil();
                assertTrue(recoil > 0f && recoil < BattleConfig.Melee.DEFAULTS.kickStandoff(),
                        "easing out to kicking distance as the prompt opens: " + recoil);
            }
            d.sim.pressDirection(sequence.get(i));
        }
        assertEquals(0f, d.sim.monk().pose().recoil(), "back at punching range well before the palm");
    }
}
