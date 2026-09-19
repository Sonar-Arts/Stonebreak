package com.stonebreak.battle;

import com.stonebreak.battle.api.ActorPose;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleEvent.DamageFlavor;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.battle.api.TimedGrade;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Telegraphs, Guard, parry, Frost Cast and the Archon's attack pick. */
class ArchonAttackTest {

    /** Guard is up before the telegraph starts; returns with the telegraph just begun. */
    private static BattleDriver guardedAgainst(EnemyAction action) {
        BattleDriver d = new BattleDriver(BattleDriver.duel(action), 1).start();
        assertTrue(d.command(BattleCommand.GUARD));
        assertTrue(d.awaitTelegraph());
        return d;
    }

    private static void runToTelegraphTime(BattleDriver d, float time) {
        assertTrue(d.runUntil(s -> s.telegraph() == null || s.telegraph().elapsed() >= time, 5f));
    }

    @Test
    void anAttackStartsWithItsTelegraphAndFollowsTheAuthoredClip() {
        BattleDriver d = new BattleDriver(BattleDriver.duel(EnemyAction.OVERHEAD), 1).start();
        d.awaitTelegraph();
        int started = d.indexOf(e -> e instanceof BattleEvent.ActionStarted);
        assertEquals(new BattleEvent.ActionStarted(CombatantId.ARCHON, "Glacial Overhead", null,
                EnemyAction.OVERHEAD, 2.05f), d.log.get(started));
        assertEquals(new BattleEvent.TelegraphStarted(EnemyAction.OVERHEAD, 1.03f), d.log.get(started + 1));
        assertEquals(1, d.count(BattleEvent.PromptOpened.class));
        assertFalse(assertInstanceOf(PromptView.Parry.class, d.sim.prompt()).canParry());

        TelegraphView t = d.sim.telegraph();
        assertEquals(EnemyAction.OVERHEAD, t.action());
        assertEquals(1.03f, t.impactTime());
        assertEquals(1.03f - 0.25f, t.parryWindowStart(), 1.0e-6f);
        assertEquals(1.03f, t.parryWindowEnd(), 1.0e-6f);
        assertFalse(t.cancelled());

        d.run(0.5f);
        ActorPose gliding = d.sim.archon().pose();
        assertEquals("attack_overhead", gliding.sbeState());
        assertEquals(d.sim.currentAction().elapsed(), gliding.clipTime());
        assertTrue(gliding.dashProgress() > 0.5f && gliding.dashProgress() < 1f);

        runToTelegraphTime(d, 0.7f); // glide reaches the monk at 60% of the windup
        assertEquals(1f, d.sim.archon().pose().dashProgress());

        assertTrue(d.runUntil(s -> s.telegraph() == null, 2f));
        assertEquals(BattlePhase.ACTION, d.sim.phase(), "the clip plays out after the blow");
        assertEquals(new BattleEvent.DamageDealt(CombatantId.MONK, 60f, DamageFlavor.NORMAL),
                d.last(BattleEvent.DamageDealt.class));
        assertEquals(140f, d.sim.monk().hp());
        assertEquals(60f, d.sim.stats().damageTaken());
        assertEquals(9f, d.sim.focus(), 1.0e-5f); // 0.15 × damage taken

        d.finishAction();
        assertEquals(new BattleEvent.ActionFinished(CombatantId.ARCHON), d.last(BattleEvent.ActionFinished.class));
        assertEquals(0f, d.sim.archon().atb(), 0.01f);
        assertEquals("combat_idle", d.sim.archon().pose().sbeState());
        assertEquals(0f, d.sim.archon().pose().dashProgress());
    }

    @Test
    void frostCastStaysOnItsRing() {
        BattleDriver d = new BattleDriver(BattleDriver.duel(EnemyAction.FROST_CAST), 1).start();
        d.awaitTelegraph();
        d.run(0.8f);
        assertEquals("attack_frost_cast", d.sim.archon().pose().sbeState());
        assertEquals(0f, d.sim.archon().pose().dashProgress());
    }

    @Test
    void unguardedTimingBlocksEveryAttackWithoutPerfectParry() {
        for (EnemyAction action : EnemyAction.values()) {
            BattleDriver d = new BattleDriver(BattleDriver.duel(action), 1).start();
            assertTrue(d.awaitTelegraph());
            assertFalse(assertInstanceOf(PromptView.Parry.class, d.sim.prompt()).canParry());
            runToTelegraphTime(d, d.sim.telegraph().impactTime() - 0.1f);
            d.forget();
            d.sim.pressConfirm();
            assertNull(d.sim.prompt());
            assertFalse(d.sim.monk().has(BattleStatus.GUARDING));
            assertTrue(d.runUntil(s -> s.telegraph() == null, 1f));
            assertEquals("block", d.sim.monk().pose().sbeState());
            d.finishAction();
            float damage = switch (action) {
                case SLASH -> 19f;
                case OVERHEAD -> 30f;
                case FROST_CAST -> 15f;
            };
            assertEquals(List.of(new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.GOOD, 0)),
                    d.all(BattleEvent.PromptResolved.class));
            assertEquals(new BattleEvent.DamageDealt(CombatantId.MONK, damage, DamageFlavor.BLOCKED),
                    d.last(BattleEvent.DamageDealt.class));
            assertEquals(200f - damage, d.sim.monk().hp());
            assertEquals(damage, d.sim.stats().damageTaken());
            assertEquals(5f + 0.15f * damage, d.sim.focus(), 1.0e-5f);
            assertEquals(0, d.sim.stats().parries());
            assertEquals(1, d.sim.stats().blocks());
            assertEquals(action == EnemyAction.FROST_CAST, d.sim.monk().has(BattleStatus.CHILLED));
        }
    }

    @Test
    void earlyUnguardedPressCannotBeRetriedAndLatePressCannotUndoDamage() {
        BattleDriver d = new BattleDriver(BattleDriver.duel(EnemyAction.SLASH), 1).start();
        assertTrue(d.awaitTelegraph());
        d.forget();
        d.sim.pressConfirm();
        runToTelegraphTime(d, 0.5f);
        d.sim.pressConfirm();
        assertTrue(d.runUntil(s -> s.telegraph() == null, 1f));
        d.sim.pressConfirm();
        d.finishAction();
        assertEquals(List.of(new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.MISS, 0)),
                d.all(BattleEvent.PromptResolved.class));
        assertEquals(162f, d.sim.monk().hp());
        assertEquals(0, d.sim.stats().blocks());
    }

    @Test
    void reactionGuardUpgradesTheOpenPromptWithoutOpeningItTwice() {
        BattleDriver d = new BattleDriver(BattleDriver.duel(EnemyAction.SLASH), 1).start();
        assertTrue(d.awaitTelegraph());
        assertTrue(d.sim.submit(BattleCommand.GUARD));
        assertTrue(assertInstanceOf(PromptView.Parry.class, d.sim.prompt()).canParry());
        runToTelegraphTime(d, 0.5f);
        d.sim.pressConfirm();
        d.finishAction();
        assertEquals(1, d.count(BattleEvent.PromptOpened.class));
        assertEquals(1, d.sim.stats().parries());
        assertEquals(200f, d.sim.monk().hp());
    }

    @Test
    void parryInsideTheWindowNegatesTheBlow() {
        BattleDriver d = guardedAgainst(EnemyAction.SLASH);
        assertEquals(1, d.count(BattleEvent.PromptOpened.class));
        PromptView.Parry prompt = assertInstanceOf(PromptView.Parry.class, d.sim.prompt());
        assertTrue(prompt.canParry());
        assertEquals(0.66f, prompt.impactTime());
        assertEquals(0.66f - 0.25f, prompt.windowStart(), 1.0e-6f);
        assertTrue(d.sim.promptSafeRequired());

        runToTelegraphTime(d, 0.5f);
        assertTrue(d.sim.telegraph().inParryWindow());
        d.forget();
        d.sim.pressConfirm();
        assertNull(d.sim.prompt(), "the attempt is spent");
        d.finishAction();

        assertEquals(List.of(new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.PERFECT, 0)),
                d.all(BattleEvent.PromptResolved.class));
        assertEquals(List.of(new BattleEvent.DamageDealt(CombatantId.MONK, 0f, DamageFlavor.PARRIED)),
                d.all(BattleEvent.DamageDealt.class));
        assertEquals(1, d.count(BattleEvent.Impact.class), "Impact is raised even when parried");
        assertEquals(200f, d.sim.monk().hp());
        assertEquals(18f, d.sim.focus());
        assertEquals(1, d.sim.stats().parries());
        assertEquals(0, d.sim.stats().blocks());
        assertEquals(0f, d.sim.monk().pose().recoil(), "a parried blow does not stagger");
        assertFalse(d.sim.monk().has(BattleStatus.GUARDING));
        assertTrue(d.log.contains(new BattleEvent.StatusExpired(CombatantId.MONK, BattleStatus.GUARDING)));
    }

    @Test
    void parryOutsideTheWindowIsSpentAndTheGuardOnlyBlocks() {
        BattleDriver d = guardedAgainst(EnemyAction.SLASH);
        runToTelegraphTime(d, 0.2f);
        assertFalse(d.sim.telegraph().inParryWindow());
        d.forget();
        d.sim.pressConfirm();
        assertNull(d.sim.prompt());

        runToTelegraphTime(d, 0.5f);
        d.sim.pressConfirm(); // inside the window now, but there is only one attempt per telegraph
        d.finishAction();

        assertEquals(List.of(new BattleEvent.PromptResolved(PromptKind.PARRY, TimedGrade.MISS, 0)),
                d.all(BattleEvent.PromptResolved.class));
        assertEquals(new BattleEvent.DamageDealt(CombatantId.MONK, 19f, DamageFlavor.BLOCKED),
                d.last(BattleEvent.DamageDealt.class));
        assertEquals(181f, d.sim.monk().hp());
        assertEquals(5f + 0.15f * 19f, d.sim.focus(), 1.0e-5f);
        assertEquals(0, d.sim.stats().parries());
        assertEquals(1, d.sim.stats().blocks());
        assertFalse(d.sim.monk().has(BattleStatus.GUARDING), "consumed by the blow it met");
    }

    @Test
    void parryWindowEdgesInsideTheSimulation() {
        // Pressing on the first frame at or past the window start parries; the frame before does not.
        for (boolean early : new boolean[] {true, false}) {
            BattleDriver d = guardedAgainst(EnemyAction.OVERHEAD);
            float start = d.sim.telegraph().parryWindowStart();
            runToTelegraphTime(d, start - (early ? 2f * BattleDriver.DT : 0f));
            if (early) assertTrue(d.sim.telegraph().elapsed() < start);
            d.sim.pressConfirm();
            d.finishAction();
            assertEquals(early ? 0 : 1, d.sim.stats().parries());
            assertEquals(early ? 1 : 0, d.sim.stats().blocks());
            assertEquals(early ? 170f : 200f, d.sim.monk().hp());
        }
    }

    @Test
    void guardLastsUntilABlowMeetsItAndIsNotAParryByItself() {
        BattleDriver d = guardedAgainst(EnemyAction.OVERHEAD);
        d.finishAction();
        assertEquals(new BattleEvent.DamageDealt(CombatantId.MONK, 30f, DamageFlavor.BLOCKED),
                d.last(BattleEvent.DamageDealt.class));
        assertEquals(0, d.count(BattleEvent.PromptResolved.class));

        // Second attack: no guard any more, full damage.
        d.awaitTelegraph();
        assertFalse(assertInstanceOf(PromptView.Parry.class, d.sim.prompt()).canParry());
        d.sim.pressConfirm(); // too early to block
        d.finishAction();
        assertEquals(new BattleEvent.DamageDealt(CombatantId.MONK, 60f, DamageFlavor.NORMAL),
                d.last(BattleEvent.DamageDealt.class));
        assertEquals(110f, d.sim.monk().hp());
    }

    @Test
    void frostCastChillsUnlessParried() {
        BattleDriver hit = new BattleDriver(BattleDriver.duel(EnemyAction.FROST_CAST), 1).start();
        hit.awaitTelegraph();
        hit.finishAction();
        assertEquals(170f, hit.sim.monk().hp());
        assertTrue(hit.sim.monk().has(BattleStatus.CHILLED));
        assertTrue(hit.log.contains(new BattleEvent.StatusApplied(CombatantId.MONK, BattleStatus.CHILLED, 10f)));

        BattleDriver blocked = guardedAgainst(EnemyAction.FROST_CAST);
        blocked.finishAction();
        assertEquals(185f, blocked.sim.monk().hp());
        assertTrue(blocked.sim.monk().has(BattleStatus.CHILLED), "a mere block still chills");

        BattleDriver parried = guardedAgainst(EnemyAction.FROST_CAST);
        runToTelegraphTime(parried, 1.0f);
        parried.sim.pressConfirm();
        parried.finishAction();
        assertEquals(200f, parried.sim.monk().hp());
        assertFalse(parried.sim.monk().has(BattleStatus.CHILLED));
    }

    @Test
    void chilledWearsOffAfterTenSeconds() {
        BattleDriver d = new BattleDriver(BattleDriver.duel(EnemyAction.FROST_CAST), 1).start();
        d.awaitTelegraph();
        assertTrue(d.runUntil(s -> s.monk().has(BattleStatus.CHILLED), 3f));
        float chilledAt = d.sim.elapsedSeconds();
        assertTrue(d.runUntil(s -> !s.monk().has(BattleStatus.CHILLED), 12f));
        assertEquals(10f, d.sim.elapsedSeconds() - chilledAt, 2f * BattleDriver.DT);
        assertTrue(d.log.contains(new BattleEvent.StatusExpired(CombatantId.MONK, BattleStatus.CHILLED)));
    }

    @Test
    void pickFollowsTheWeights() {
        ArchonScript script = new ArchonScript(BattleConfig.Archon.DEFAULTS);
        Random random = new Random(11);
        int[] counts = new int[EnemyAction.values().length];
        int samples = 20_000;
        for (int i = 0; i < samples; i++) counts[script.pick(random, false).ordinal()]++;
        // Frost Cast is barred after itself, which shifts the long-run shares away from 45/30/25:
        // stationary share f = 0.25 (1 - f) = 0.2, the rest split 45:30.
        assertEquals(0.48, counts[EnemyAction.SLASH.ordinal()] / (double) samples, 0.02);
        assertEquals(0.32, counts[EnemyAction.OVERHEAD.ordinal()] / (double) samples, 0.02);
        assertEquals(0.20, counts[EnemyAction.FROST_CAST.ordinal()] / (double) samples, 0.02);
    }

    @Test
    void frostCastIsNeverPickedTwiceRunningNorWhileTheMonkIsChilled() {
        for (long seed = 0; seed < 20; seed++) {
            ArchonScript script = new ArchonScript(BattleConfig.Archon.DEFAULTS.withWeights(1, 1, 50));
            Random random = new Random(seed);
            EnemyAction previous = null;
            int frosts = 0;
            for (int i = 0; i < 200; i++) {
                EnemyAction pick = script.pick(random, false);
                if (previous == EnemyAction.FROST_CAST) assertNotEquals(EnemyAction.FROST_CAST, pick);
                if (pick == EnemyAction.FROST_CAST) frosts++;
                previous = pick;
            }
            assertTrue(frosts > 50, "the heavy weight still wins whenever it is allowed");

            ArchonScript chilled = new ArchonScript(BattleConfig.Archon.DEFAULTS.withWeights(1, 1, 50));
            for (int i = 0; i < 200; i++) assertNotEquals(EnemyAction.FROST_CAST, chilled.pick(random, true));
        }
    }

    @Test
    void aFrostOnlyArchonFallsBackToSlashWhenFrostIsBarred() {
        BattleDriver d = new BattleDriver(BattleDriver.duel(EnemyAction.FROST_CAST), 3).start();
        d.awaitTelegraph();
        assertEquals(EnemyAction.FROST_CAST, d.sim.telegraph().action());
        d.finishAction();
        d.awaitTelegraph();
        assertEquals(EnemyAction.SLASH, d.sim.telegraph().action());
    }
}
