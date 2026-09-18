package com.stonebreak.battle;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.PromptView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phases, gauges and the event publication contract. */
class BattleClockTest {

    private static final float TIMING_SLACK = 2f * BattleDriver.DT;

    /** Seconds of battle time until the monk's first TurnReady. */
    private static float monkFillTime(BattleDriver d) {
        assertTrue(d.runUntil(s -> d.count(BattleEvent.TurnReady.class) > 0, 30f));
        return d.sim.elapsedSeconds();
    }

    @Test
    void introFreezesEverythingUntilItFinishes() {
        BattleDriver d = new BattleDriver(BattleDriver.baseline(200f, 10), 1);
        d.run(3f);
        assertEquals(BattlePhase.INTRO, d.sim.phase());
        assertEquals(0.5f, d.sim.monk().atb());
        assertEquals(0.2f, d.sim.archon().atb());
        assertEquals(0f, d.sim.elapsedSeconds());
        assertFalse(d.sim.commandWindowOpen());
        assertTrue(d.log.isEmpty());

        d.sim.introFinished();
        assertEquals(BattlePhase.RUNNING, d.sim.phase());
        d.step(BattleDriver.DT);
        assertEquals(List.of(new BattleEvent.BattleStarted()), d.sim.frameEvents());
        assertTrue(d.sim.monk().atb() > 0.5f);

        d.sim.introFinished(); // no-op outside INTRO
        d.step(BattleDriver.DT);
        assertEquals(1, d.count(BattleEvent.BattleStarted.class));
    }

    @Test
    void startingValuesMatchTheBrief() {
        BattleState sim = new BattleState(BattleDriver.baseline(180f, 10), new Random(1));
        assertEquals("Monk", sim.monk().displayName());
        assertEquals(180f, sim.monk().maxHp());
        assertEquals(180f, sim.monk().hp());
        assertEquals("Ice Archon", sim.archon().displayName());
        assertEquals(900f, sim.archon().maxHp());
        assertEquals(2, sim.qi());
        assertEquals(5, sim.maxQi());
        assertEquals(0f, sim.focus());
        assertEquals(100f, sim.maxFocus());
        assertEquals(3, sim.meditateCharges());
        assertEquals(0, sim.queuedSurgeHits());
        assertTrue(sim.frameEvents().isEmpty());
    }

    @Test
    void monkGaugeFillsInFourSecondsAtBaselineDex() {
        BattleDriver d = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.baseline(200f, 10)), 1).start();
        assertEquals(2.0f, monkFillTime(d), TIMING_SLACK); // starts half full
    }

    @Test
    void dexterityScalesFillSpeedWithinItsClamp() {
        assertEquals(1.3f, BattleDriver.baseline(200f, 20).dexteritySpeed(), 1.0e-5f);
        assertEquals(1.6f, BattleDriver.baseline(200f, 99).dexteritySpeed(), 1.0e-5f);
        assertEquals(0.7f, BattleDriver.baseline(200f, 0).dexteritySpeed(), 1.0e-5f);
        assertEquals(0.6f, BattleDriver.baseline(200f, -40).dexteritySpeed(), 1.0e-5f);

        BattleDriver fast = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.baseline(200f, 20)), 1).start();
        assertEquals(2.0f / 1.3f, monkFillTime(fast), TIMING_SLACK);
    }

    @Test
    void hasteAndChilledChangeTheFillTime() {
        BattleDriver hasted = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.baseline(200f, 10)), 1).start();
        hasted.sim.context().applyStatus(hasted.sim.context().monk, BattleStatus.HASTE, 15f);
        assertEquals(2.0f / 1.5f, monkFillTime(hasted), TIMING_SLACK);

        BattleDriver chilled = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.baseline(200f, 10)), 1).start();
        chilled.sim.context().applyStatus(chilled.sim.context().monk, BattleStatus.CHILLED, 10f);
        assertEquals(2.0f / 0.7f, monkFillTime(chilled), TIMING_SLACK);
    }

    @Test
    void aStatusExpiringMidFillOnlySpeedsTheGaugeWhileItLasts() {
        BattleDriver d = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.baseline(200f, 10)), 1).start();
        d.sim.context().applyStatus(d.sim.context().monk, BattleStatus.HASTE, 1f);
        // 1 s hasted covers 0.375 of the gauge, the remaining 0.125 takes 0.5 s: one big step must agree.
        // Had Haste applied to the whole step, 1.4 s would already have filled the gauge.
        d.step(1.4f);
        assertEquals(0, d.count(BattleEvent.TurnReady.class));
        d.step(0.2f);
        assertEquals(1, d.count(BattleEvent.TurnReady.class));
        assertEquals(1, d.all(BattleEvent.StatusExpired.class).size());
    }

    @Test
    void archonGaugeFillsInFiveAndAHalfSeconds() {
        BattleDriver d = new BattleDriver(BattleDriver.baseline(200f, 10), 1).start();
        assertTrue(d.awaitTelegraph());
        // 0.2 → 1.0 of 5.5 s; the monk held its turn, so nothing paused the clock.
        assertEquals(0.8f * 5.5f, d.sim.elapsedSeconds(), TIMING_SLACK);
        assertEquals(new BattleEvent.TurnReady(CombatantId.ARCHON),
                d.log.get(d.indexOf(e -> e instanceof BattleEvent.TurnReady t && t.who() == CombatantId.ARCHON)));
    }

    @Test
    void bothGaugesPauseWhileAnActionExecutes() {
        BattleDriver d = new BattleDriver(BattleDriver.baseline(200f, 10), 1).start();
        assertTrue(d.command(BattleCommand.STRIKE));
        assertEquals(BattlePhase.ACTION, d.sim.phase());
        assertEquals(0f, d.sim.monk().atb());
        float archonBefore = d.sim.archon().atb();
        d.run(1.0f);
        assertEquals(BattlePhase.ACTION, d.sim.phase());
        assertEquals(0f, d.sim.monk().atb());
        assertEquals(archonBefore, d.sim.archon().atb());

        float clock = d.sim.elapsedSeconds();
        d.run(0.25f);
        assertTrue(d.sim.elapsedSeconds() > clock, "battle time keeps counting during ACTION");
    }

    @Test
    void turnReadyIsRaisedOncePerFillAndGrantsQi() {
        BattleDriver d = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.baseline(200f, 10)), 1).start();
        assertTrue(d.awaitWindow());
        d.run(3f); // holding the turn must not re-announce it
        assertEquals(1, d.count(BattleEvent.TurnReady.class));
        assertEquals(3, d.sim.qi());
        assertEquals(List.of(new BattleEvent.QiChanged(1, 3)), d.all(BattleEvent.QiChanged.class));
        assertEquals(1f, d.sim.monk().atb());
    }

    @Test
    void qiIsCappedAtItsMaximum() {
        BattleDriver d = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.exact()), 1).start();
        for (int turn = 0; turn < 6; turn++) {
            assertTrue(d.command(BattleCommand.GUARD));
            d.finishAction();
            d.sim.context().expireStatus(d.sim.context().monk, BattleStatus.GUARDING);
        }
        d.awaitWindow();
        assertEquals(5, d.sim.qi());
        assertEquals(3, d.count(BattleEvent.QiChanged.class)); // 2 → 5, then silent at the cap
    }

    @Test
    void inputEventsArePublishedByTheNextUpdateAndTheListIsReplacedEveryUpdate() {
        BattleDriver d = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.exact()), 1).start();
        d.step(BattleDriver.DT);
        assertTrue(d.sim.frameEvents().isEmpty());

        List<BattleEvent> before = d.sim.frameEvents();
        assertFalse(d.sim.submit(BattleCommand.STRIKE)); // gauge not full yet
        assertSame(before, d.sim.frameEvents(), "input alone never republishes");
        assertTrue(d.sim.frameEvents().isEmpty());

        d.step(BattleDriver.DT);
        assertEquals(List.of(new BattleEvent.CommandRejected(BattleCommand.STRIKE, "Not your turn")), d.sim.frameEvents());
        assertSame(d.sim.frameEvents(), d.sim.frameEvents());
        assertThrows(UnsupportedOperationException.class, () -> d.sim.frameEvents().add(new BattleEvent.BattleStarted()));

        List<BattleEvent> published = d.sim.frameEvents();
        d.step(BattleDriver.DT);
        assertNotSame(published, d.sim.frameEvents());
        assertTrue(d.sim.frameEvents().isEmpty(), "an update that raises nothing publishes an empty list");
        assertEquals(1, published.size(), "a published list is never mutated afterwards");
    }

    @Test
    void oneLargeStepFiresEveryThresholdInOrder() {
        BattleDriver d = new BattleDriver(BattleDriver.duel(EnemyAction.SLASH), 1);
        d.sim.introFinished();
        List<BattleEvent> frame = d.step(5f);

        int started = indexOf(frame, BattleEvent.ActionStarted.class);
        int telegraph = indexOf(frame, BattleEvent.TelegraphStarted.class);
        int impact = indexOf(frame, BattleEvent.Impact.class);
        int damage = indexOf(frame, BattleEvent.DamageDealt.class);
        int finished = indexOf(frame, BattleEvent.ActionFinished.class);
        assertTrue(started >= 0 && started < telegraph && telegraph < impact && impact < damage && damage < finished,
                () -> "out of order: " + frame);
        assertEquals(200f - 38f, d.sim.monk().hp());
        assertEquals(BattlePhase.RUNNING, d.sim.phase());
        assertEquals(5f, d.sim.elapsedSeconds(), 1.0e-3f);
    }

    @Test
    void largeAndSmallStepsAgreeOnWhatHappened() {
        BattleDriver coarse = new BattleDriver(BattleDriver.duel(EnemyAction.OVERHEAD), 9);
        BattleDriver fine = new BattleDriver(BattleDriver.duel(EnemyAction.OVERHEAD), 9);
        coarse.sim.introFinished();
        fine.sim.introFinished();
        for (int i = 0; i < 100; i++) coarse.step(0.1f);
        for (int i = 0; i < 640; i++) fine.step(BattleDriver.DT);
        assertEquals(fine.log, coarse.log);
        assertEquals(fine.sim.monk().hp(), coarse.sim.monk().hp());
    }

    @Test
    void largeAndSmallStepsAgreeThroughTheMonksTimedActions() {
        // Unpressed rings, a surged Strike, a stun and its recovery, a combo that freezes and times
        // out: every one of those cues sits on the action clock, so the frame size must not matter.
        List<List<BattleEvent>> logs = new ArrayList<>();
        for (float dt : new float[] {BattleDriver.DT, 0.37f}) {
            BattleDriver d = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.exact()), 4);
            d.sim.introFinished();
            BattleCommand[] plan = {BattleCommand.FLURRY, BattleCommand.MARTIAL_SURGE, BattleCommand.STRIKE,
                    BattleCommand.GUARD, BattleCommand.STUNNING_STRIKE, BattleCommand.FOCUS_COMBO};
            int next = 0;
            for (float clock = 0f; clock < 60f; clock += dt) {
                if (next < plan.length && d.sim.commandWindowOpen()) {
                    if (plan[next] == BattleCommand.FOCUS_COMBO) d.sim.context().focus.gain(100f);
                    if (d.sim.submit(plan[next])) next++;
                }
                d.step(dt);
            }
            assertEquals(plan.length, next);
            assertEquals(BattlePhase.RUNNING, d.sim.phase());
            logs.add(d.log);
        }
        assertTrue(logs.get(0).size() > 40);
        assertEquals(logs.get(0), logs.get(1));
    }

    @Test
    void degenerateStepsAreHarmless() {
        BattleDriver d = new BattleDriver(BattleDriver.baseline(200f, 10), 1).start();
        float clock = d.sim.elapsedSeconds();
        d.step(0f);
        d.step(-1f);
        d.step(Float.NaN);
        d.step(Float.POSITIVE_INFINITY);
        assertEquals(clock, d.sim.elapsedSeconds());
        assertFalse(d.sim.submit(null));
        d.sim.pressDirection(null);
        d.sim.pressConfirm();
        d.step(BattleDriver.DT);
        assertTrue(d.sim.elapsedSeconds() > clock);
    }

    @Test
    void sameSeedAndScriptGiveIdenticalEventStreams() {
        List<BattleEvent> first = playScripted(42L);
        List<BattleEvent> second = playScripted(42L);
        assertTrue(first.size() > 100, "the script should exercise a real battle");
        assertEquals(first, second);
        assertNotEquals(first, playScripted(43L));
    }

    /** A bot with its own seeded whims: mixes every command, presses rings/parries/combos imperfectly. */
    private static List<BattleEvent> playScripted(long seed) {
        BattleDriver d = new BattleDriver(BattleDriver.baseline(400f, 14), seed);
        Random whim = new Random(99);
        BattleCommand[] menu = BattleCommand.values();
        d.sim.introFinished();
        for (int frame = 0; frame < 64 * 150; frame++) {
            if (d.sim.commandWindowOpen() && whim.nextInt(20) == 0) {
                d.sim.submit(menu[whim.nextInt(menu.length)]);
            }
            PromptView prompt = d.sim.prompt();
            if (prompt instanceof PromptView.Ring ring && ring.elapsed() >= 0.25f + whim.nextInt(4) * 0.08f) {
                d.sim.pressConfirm();
            } else if (prompt instanceof PromptView.Parry parry && parry.elapsed() >= parry.windowStart() - 0.05f
                    && whim.nextBoolean()) {
                d.sim.pressConfirm();
            } else if (prompt instanceof PromptView.Combo combo && combo.stepElapsed() > 0.2f) {
                ComboDirection right = combo.sequence().get(combo.index());
                d.sim.pressDirection(whim.nextInt(8) == 0 ? ComboDirection.values()[whim.nextInt(4)] : right);
            }
            d.step(frame % 7 == 0 ? 0.05f : BattleDriver.DT);
        }
        return new ArrayList<>(d.log);
    }

    private static int indexOf(List<BattleEvent> events, Class<? extends BattleEvent> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) return i;
        }
        return -1;
    }
}
