package com.stonebreak.battle;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleEvent.DamageFlavor;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.StatusView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When the monk may act, what each command costs, and why a command is refused. */
class CommandWindowTest {

    private static BattleDriver monkOnly() {
        return new BattleDriver(BattleDriver.passiveArchon(BattleDriver.exact()), 1).start();
    }

    private static void assertRejected(BattleDriver d, BattleCommand command, String reason) {
        assertFalse(d.sim.availability(command).available());
        assertEquals(reason, d.sim.availability(command).reason());
        d.forget();
        assertFalse(d.sim.submit(command));
        d.step(BattleDriver.DT);
        assertEquals(List.of(new BattleEvent.CommandRejected(command, reason)), d.all(BattleEvent.CommandRejected.class));
    }

    @Test
    void windowOpensWithAFullGaugeAndClosesWhileTheMonkActs() {
        BattleDriver d = monkOnly();
        assertFalse(d.sim.commandWindowOpen());
        assertRejected(d, BattleCommand.STRIKE, "Not your turn");

        assertTrue(d.awaitWindow());
        assertEquals(BattlePhase.RUNNING, d.sim.phase());
        assertTrue(d.sim.availability(BattleCommand.STRIKE).available());
        assertEquals("", d.sim.availability(BattleCommand.STRIKE).reason());

        assertTrue(d.sim.submit(BattleCommand.STRIKE));
        assertFalse(d.sim.commandWindowOpen());
        assertEquals(BattleCommand.STRIKE, d.sim.currentAction().command());
        assertEquals(CombatantId.MONK, d.sim.currentAction().actor());
        assertRejected(d, BattleCommand.GUARD, "Not your turn");
        assertEquals(1, d.sim.stats().turnsTaken());
    }

    @Test
    void qiArtsCostQiAndAreRefusedWithoutIt() {
        BattleDriver d = monkOnly();
        d.awaitWindow();
        assertEquals(3, d.sim.qi());
        assertTrue(d.sim.submit(BattleCommand.STUNNING_STRIKE));
        assertEquals(1, d.sim.qi());
        d.finishAction();

        d.awaitWindow(); // +1 Qi → 2
        assertTrue(d.sim.submit(BattleCommand.SWIFT_STEP));
        assertEquals(1, d.sim.qi());
        d.finishAction();

        d.awaitWindow(); // → 2
        assertTrue(d.sim.submit(BattleCommand.STUNNING_STRIKE));
        assertEquals(0, d.sim.qi());
        d.finishAction();

        d.awaitWindow(); // → 1
        assertRejected(d, BattleCommand.STUNNING_STRIKE, "Not enough Qi");
        assertEquals(1, d.sim.qi());
        assertTrue(d.sim.commandWindowOpen(), "a refused command does not spend the turn");
        assertTrue(d.all(BattleEvent.QiChanged.class).isEmpty());
    }

    @Test
    void resourceReasonsOutrankTheTurnReason() {
        BattleDriver d = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.exact()
                .withResources(BattleConfig.Resources.DEFAULTS.withStartQi(0))), 1).start();
        assertFalse(d.sim.commandWindowOpen());
        // 0 Qi now, but the coming turn grants 1: a 1-Qi art WILL be affordable, a 2-Qi art will not.
        // (Judging by today's Qi made a resting HUD dim a row that lit up the moment the window opened.)
        assertEquals("Not your turn", d.sim.availability(BattleCommand.SWIFT_STEP).reason());
        assertEquals("Not enough Qi", d.sim.availability(BattleCommand.STUNNING_STRIKE).reason());
        assertTrue(d.sim.availability(BattleCommand.SWIFT_STEP).onlyWaitingForTurn());
        assertFalse(d.sim.availability(BattleCommand.STUNNING_STRIKE).onlyWaitingForTurn());
        assertEquals("Focus gauge not full", d.sim.availability(BattleCommand.FOCUS_COMBO).reason());
        assertEquals("Not your turn", d.sim.availability(BattleCommand.STRIKE).reason());
        assertEquals("Not your turn", d.sim.availability(null).reason());
    }

    @Test
    void focusComboNeedsAFullGauge() {
        BattleDriver d = monkOnly();
        d.awaitWindow();
        d.sim.context().focus.gain(99f);
        assertRejected(d, BattleCommand.FOCUS_COMBO, "Focus gauge not full");
        d.sim.context().focus.gain(1f);
        assertTrue(d.sim.focusReady());
        assertTrue(d.sim.availability(BattleCommand.FOCUS_COMBO).available());
    }

    @Test
    void meditateRunsOutOfCharges() {
        BattleDriver d = monkOnly();
        for (int use = 0; use < 3; use++) {
            assertTrue(d.command(BattleCommand.MEDITATE));
            assertEquals(2 - use, d.sim.meditateCharges());
            d.finishAction();
        }
        d.awaitWindow();
        assertRejected(d, BattleCommand.MEDITATE, "No charges left");
    }

    @Test
    void martialSurgeIsAnActionThatLeavesTheGaugeFullAndCannotBeDoubleQueued() {
        BattleDriver d = monkOnly();
        d.awaitWindow();
        d.forget();
        assertTrue(d.sim.submit(BattleCommand.MARTIAL_SURGE));
        assertEquals(2, d.sim.qi());
        assertEquals(BattlePhase.ACTION, d.sim.phase(), "it plays its clip like any other action");
        assertEquals(BattleCommand.MARTIAL_SURGE, d.sim.currentAction().command());
        assertEquals(1.58f, d.sim.currentAction().duration());
        assertEquals(1f, d.sim.monk().atb(), "a free action never spends the gauge");
        assertFalse(d.sim.commandWindowOpen(), "but nothing can be picked while it plays");
        assertEquals("martial_surge", d.sim.monk().pose().sbeState());
        assertEquals(0, d.sim.queuedSurgeHits(), "the surge is queued on the clip's effect cue");

        d.runActionTo(0.84f);
        assertEquals(0, d.sim.queuedSurgeHits());
        d.runActionTo(0.88f);
        assertEquals(1, d.sim.queuedSurgeHits());
        assertTrue(d.sim.monk().statuses().contains(new StatusView(BattleStatus.SURGE, -1f, 1)));
        assertEquals(BattlePhase.ACTION, d.sim.phase());

        d.finishAction();
        assertEquals(List.of(new BattleEvent.QiChanged(-1, 2),
                new BattleEvent.ActionStarted(CombatantId.MONK, "Martial Surge", BattleCommand.MARTIAL_SURGE, null, 1.58f),
                new BattleEvent.StatusApplied(CombatantId.MONK, BattleStatus.SURGE, -1f),
                new BattleEvent.ActionFinished(CombatantId.MONK)), d.log);
        assertEquals(BattlePhase.RUNNING, d.sim.phase());
        assertEquals(1f, d.sim.monk().atb());
        assertTrue(d.sim.commandWindowOpen(), "the command window reopens the moment it ends");
        assertEquals(0, d.sim.stats().turnsTaken());
        assertEquals("combat_idle", d.sim.monk().pose().sbeState());

        assertRejected(d, BattleCommand.MARTIAL_SURGE, "Surge already queued");
        assertEquals(2, d.sim.qi());
    }

    @Test
    void surgeAddsOneStrikeHitAndIsConsumed() {
        BattleDriver d = monkOnly();
        d.awaitWindow();
        d.sim.submit(BattleCommand.MARTIAL_SURGE);
        d.finishAction();
        d.forget();
        assertTrue(d.sim.submit(BattleCommand.STRIKE));
        assertEquals(0, d.sim.queuedSurgeHits());
        assertFalse(d.sim.monk().has(BattleStatus.SURGE));
        // dash + strike clip + kick clip + dash
        assertEquals(0.45f + 0.96f + 1.42f + 0.45f, d.sim.currentAction().duration(), 1.0e-5f);
        d.finishAction();

        assertEquals(List.of(new BattleEvent.Impact(CombatantId.MONK, CombatantId.ARCHON, 0, 2),
                new BattleEvent.Impact(CombatantId.MONK, CombatantId.ARCHON, 1, 2)), d.all(BattleEvent.Impact.class));
        assertEquals(900f - 84f, d.sim.archon().hp());
        assertEquals(12f, d.sim.focus());
        assertEquals(1, d.count(BattleEvent.StatusExpired.class));

        // The next Strike is back to a single hit.
        d.command(BattleCommand.STRIKE);
        d.finishAction();
        assertEquals(900f - 126f, d.sim.archon().hp());
    }

    @Test
    void surgeTurnsAFlurryIntoFiveHitsButIgnoresOtherCommands() {
        BattleDriver d = monkOnly();
        d.awaitWindow();
        d.sim.submit(BattleCommand.MARTIAL_SURGE);
        d.finishAction();
        assertTrue(d.sim.submit(BattleCommand.GUARD));
        d.finishAction();
        assertEquals(1, d.sim.queuedSurgeHits(), "Guard does not spend the surge");

        d.command(BattleCommand.FLURRY);
        assertEquals(0, d.sim.queuedSurgeHits());
        d.awaitPrompt();
        assertEquals(5, assertInstanceOf(PromptView.Ring.class, d.sim.prompt()).hitCount());
        d.finishAction();
        assertEquals(5, d.count(BattleEvent.PromptResolved.class));
    }

    @Test
    void aCommandGivenDuringTheArchonsActionIsQueuedUntilItFinishes() {
        BattleDriver d = new BattleDriver(BattleDriver.duel(EnemyAction.SLASH), 1).start();
        assertTrue(d.awaitTelegraph());
        assertEquals(BattlePhase.ACTION, d.sim.phase());
        assertTrue(d.sim.commandWindowOpen(), "FF7 rule: input is allowed while the enemy animates");

        assertTrue(d.sim.submit(BattleCommand.STRIKE));
        assertFalse(d.sim.commandWindowOpen());
        assertEquals(CombatantId.ARCHON, d.sim.currentAction().actor());
        assertEquals(1f, d.sim.monk().atb(), "the gauge is only spent when the action starts");
        assertRejected(d, BattleCommand.GUARD, "Not your turn");

        assertTrue(d.runUntil(s -> s.currentAction() != null && s.currentAction().actor() == CombatantId.MONK, 5f));
        int archonDone = d.indexOf(e -> e.equals(new BattleEvent.ActionFinished(CombatantId.ARCHON)));
        int monkStart = d.indexOf(e -> e instanceof BattleEvent.ActionStarted a && a.command() == BattleCommand.STRIKE);
        assertEquals(archonDone + 1, monkStart, "the queued command starts the instant the Archon is done");
        assertEquals(0f, d.sim.monk().atb());
        assertEquals(200f - 38f, d.sim.monk().hp());
    }

    @Test
    void guardDuringATelegraphIsAnInstantReaction() {
        BattleDriver d = new BattleDriver(BattleDriver.duel(EnemyAction.SLASH), 1).start();
        d.awaitTelegraph();
        d.forget();

        assertTrue(d.sim.submit(BattleCommand.GUARD));
        assertTrue(d.sim.monk().has(BattleStatus.GUARDING));
        assertTrue(d.sim.monk().statuses().contains(new StatusView(BattleStatus.GUARDING, -1f, 1)));
        assertEquals(0f, d.sim.monk().atb());
        assertEquals(CombatantId.ARCHON, d.sim.currentAction().actor(), "no animation lock of its own");
        assertFalse(d.sim.commandWindowOpen());
        assertInstanceOf(PromptView.Parry.class, d.sim.prompt());
        assertEquals(1, d.sim.stats().turnsTaken());

        d.step(BattleDriver.DT);
        assertEquals(List.of(new BattleEvent.StatusApplied(CombatantId.MONK, BattleStatus.GUARDING, -1f)), d.log,
                "the existing block prompt upgrades without opening a second prompt");

        d.finishAction();
        assertEquals(new BattleEvent.DamageDealt(CombatantId.MONK, 19f, DamageFlavor.BLOCKED),
                d.last(BattleEvent.DamageDealt.class));
        assertEquals(0, d.count(BattleEvent.ActionStarted.class), "the reaction never became an action");
    }

    @Test
    void guardAfterTheBlowHasLandedIsQueuedLikeAnyCommand() {
        BattleDriver d = new BattleDriver(BattleDriver.duel(EnemyAction.SLASH), 1).start();
        d.awaitTelegraph();
        assertTrue(d.runUntil(s -> s.telegraph() == null, 3f));
        assertEquals(BattlePhase.ACTION, d.sim.phase());

        assertTrue(d.sim.submit(BattleCommand.GUARD));
        assertFalse(d.sim.monk().has(BattleStatus.GUARDING));
        assertTrue(d.runUntil(s -> s.currentAction() != null && s.currentAction().command() == BattleCommand.GUARD, 3f));
        assertTrue(d.sim.monk().has(BattleStatus.GUARDING));
        assertEquals(0.32f, d.sim.currentAction().duration(), "the Guard action is only the stance going up");
    }

    @Test
    void guardCannotBeStackedAndAnotherCommandDropsIt() {
        BattleDriver d = monkOnly();
        d.command(BattleCommand.GUARD);
        d.finishAction();
        d.awaitWindow();
        assertRejected(d, BattleCommand.GUARD, "Already guarding");

        d.forget();
        assertTrue(d.sim.submit(BattleCommand.MARTIAL_SURGE));
        assertFalse(d.sim.submit(BattleCommand.STRIKE), "not while the surge clip plays");
        d.finishAction();
        assertTrue(d.sim.monk().has(BattleStatus.GUARDING), "a free action keeps the stance");
        assertTrue(d.sim.submit(BattleCommand.STRIKE));
        assertFalse(d.sim.monk().has(BattleStatus.GUARDING));
        d.step(BattleDriver.DT);
        assertTrue(d.log.contains(new BattleEvent.StatusExpired(CombatantId.MONK, BattleStatus.GUARDING)));
    }
}
