package com.stonebreak.battle;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.EnemyAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Fixed-step driver for battle model tests: steps the simulation, records every published event in
 * order, and offers the handful of "run until…" helpers the scenarios need. Shared fixture, not a test.
 */
final class BattleDriver {

    /** 1/64 s: exactly representable, so accumulated times stay clean. */
    static final float DT = 1f / 64f;
    /** Half-width of the "lands exactly here" bracket used by {@link #assertImpactAt}. */
    static final float EPS = 1.0e-4f;
    /** Dash out / dash home length of the default melee tuning. */
    static final float DASH = 0.45f;

    final BattleState sim;
    /** Every event published since construction, in publication order. */
    final List<BattleEvent> log = new ArrayList<>();

    BattleDriver(BattleConfig config, long seed) {
        this.sim = new BattleState(config, new Random(seed));
    }

    // ---- configs ----------------------------------------------------------------------------------

    /** Exact damage (no variance / crits), 200 HP monk at baseline DEX. */
    static BattleConfig exact() {
        return baseline(200f, 10).withDamage(BattleConfig.Damage.EXACT);
    }

    /** The Archon's gauge effectively never fills, so monk-side scenarios run undisturbed. */
    static BattleConfig passiveArchon(BattleConfig base) {
        return base.withAtb(atb(base.atb().monkInitial(), 0f, 1.0e7f));
    }

    /** Monk starts ready; the Archon attacks ~0.55 s in and only ever uses {@code only}. */
    static BattleConfig duel(EnemyAction only) {
        BattleConfig base = exact();
        return base.withAtb(atb(1f, 0.9f, 5.5f)).withArchon(base.archon().withWeights(
                only == EnemyAction.SLASH ? 1 : 0, only == EnemyAction.OVERHEAD ? 1 : 0,
                only == EnemyAction.FROST_CAST ? 1 : 0));
    }

    /**
     * Rules baseline for tests: shipped defaults with the gauge speeds and the Archon's HP pinned, so
     * playtest tuning of those numbers in {@link BattleConfig} never rewrites rule expectations.
     */
    static BattleConfig baseline(float monkMaxHp, int monkDexterity) {
        BattleConfig d = BattleConfig.defaults(monkMaxHp, monkDexterity);
        BattleConfig.Atb a = d.atb();
        return d.withAtb(new BattleConfig.Atb(4.0f, a.baselineDex(), a.speedPerDexPoint(), a.minDexSpeed(),
                        a.maxDexSpeed(), a.hasteMultiplier(), a.chilledMultiplier(), 5.5f, a.monkInitial(),
                        a.archonInitial()))
                .withArchon(d.archon().withMaxHp(900f));
    }

    static BattleConfig.Atb atb(float monkInitial, float archonInitial, float archonFillSeconds) {
        BattleConfig.Atb d = baseline(200f, 10).atb();
        return new BattleConfig.Atb(d.monkFillSeconds(), d.baselineDex(), d.speedPerDexPoint(), d.minDexSpeed(),
                d.maxDexSpeed(), d.hasteMultiplier(), d.chilledMultiplier(), archonFillSeconds, monkInitial,
                archonInitial);
    }

    // ---- stepping ---------------------------------------------------------------------------------

    /** One update of exactly {@code dt}; returns that update's events. */
    List<BattleEvent> step(float dt) {
        sim.update(dt);
        log.addAll(sim.frameEvents());
        return sim.frameEvents();
    }

    BattleDriver start() {
        sim.introFinished();
        step(DT);
        return this;
    }

    void run(float seconds) {
        int steps = Math.round(seconds / DT);
        for (int i = 0; i < steps; i++) step(DT);
    }

    /** Steps until the condition holds (checked before each step). False when it never did. */
    boolean runUntil(Predicate<BattleState> condition, float maxSeconds) {
        int steps = Math.round(maxSeconds / DT);
        for (int i = 0; i <= steps; i++) {
            if (condition.test(sim)) return true;
            step(DT);
        }
        return condition.test(sim);
    }

    boolean awaitWindow() {
        return runUntil(BattleState::commandWindowOpen, 60f);
    }

    boolean awaitTelegraph() {
        return runUntil(s -> s.telegraph() != null, 60f);
    }

    boolean awaitPrompt() {
        return runUntil(s -> s.prompt() != null, 60f);
    }

    /** One update that brings the executing action's own clock to {@code actionTime}. */
    void runActionTo(float actionTime) {
        step(actionTime - sim.currentAction().elapsed());
    }

    /**
     * Asserts that exactly one Impact is published within {@link #EPS} of {@code actionTime} on the
     * executing action's clock, and none in the run-up from wherever the clock is now.
     */
    void assertImpactAt(float actionTime) {
        int before = count(BattleEvent.Impact.class);
        runActionTo(actionTime - EPS);
        org.junit.jupiter.api.Assertions.assertEquals(before, count(BattleEvent.Impact.class),
                () -> "a blow landed before " + actionTime);
        step(2f * EPS);
        org.junit.jupiter.api.Assertions.assertEquals(before + 1, count(BattleEvent.Impact.class),
                () -> "no blow landed at " + actionTime);
    }

    /** Runs until no action is executing. */
    boolean finishAction() {
        return runUntil(s -> s.phase() != BattlePhase.ACTION, 60f);
    }

    /** Waits for the window, submits, and returns whether the command was accepted. */
    boolean command(BattleCommand command) {
        awaitWindow();
        return sim.submit(command);
    }

    // ---- event queries ----------------------------------------------------------------------------

    <T extends BattleEvent> List<T> all(Class<T> type) {
        return of(log, type);
    }

    static <T extends BattleEvent> List<T> of(List<BattleEvent> events, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (BattleEvent e : events) {
            if (type.isInstance(e)) out.add(type.cast(e));
        }
        return out;
    }

    <T extends BattleEvent> T last(Class<T> type) {
        List<T> found = all(type);
        return found.isEmpty() ? null : found.get(found.size() - 1);
    }

    int count(Class<? extends BattleEvent> type) {
        return all(type).size();
    }

    int indexOf(Predicate<BattleEvent> match) {
        for (int i = 0; i < log.size(); i++) {
            if (match.test(log.get(i))) return i;
        }
        return -1;
    }

    /** Drops the recorded history so a scenario can assert on "what happened since". */
    void forget() {
        log.clear();
    }
}
