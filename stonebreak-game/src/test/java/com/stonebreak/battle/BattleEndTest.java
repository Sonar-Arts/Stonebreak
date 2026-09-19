package com.stonebreak.battle;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.EnemyAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hit reactions and how the battle ends. */
class BattleEndTest {

    @Test
    void recoilRisesAfterTheContactFrameThenDecaysLinearly() {
        BattleDriver d = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.exact()), 1).start();
        d.command(BattleCommand.STRIKE);
        d.assertImpactAt(BattleDriver.DASH + MonkClips.STRIKE.cue(0));
        assertEquals(0f, d.sim.archon().pose().recoil(),
                "the frame that shows the fist landing still shows the Archon where the fist met it");
        assertEquals(0f, d.sim.monk().pose().recoil());

        d.step(0.04f);
        assertEquals(0.5f, d.sim.archon().pose().recoil(), 1.0e-4f, "knocked back over 0.08 s");
        d.step(0.04f);
        assertEquals(1f, d.sim.archon().pose().recoil(), 1.0e-4f);
        d.step(0.175f);
        assertEquals(0.5f, d.sim.archon().pose().recoil(), 1.0e-4f);
        d.step(0.175f);
        assertEquals(0f, d.sim.archon().pose().recoil(), 1.0e-4f);
        d.step(1f);
        assertEquals(0f, d.sim.archon().pose().recoil());
    }

    @Test
    void victoryEndsTheBattleTheArchonCollapsesAndTheMonkCelebratesOnceHome() {
        BattleConfig config = BattleDriver.passiveArchon(BattleDriver.exact());
        BattleDriver d = new BattleDriver(config.withArchon(config.archon().withMaxHp(40f)), 1).start();
        d.command(BattleCommand.STRIKE);
        assertTrue(d.runUntil(s -> s.phase() == BattlePhase.RESULT, 3f));

        assertEquals(BattleOutcome.VICTORY, d.sim.outcome());
        assertEquals(0f, d.sim.archon().hp());
        assertFalse(d.sim.archon().alive());
        assertNull(d.sim.currentAction());
        assertNull(d.sim.prompt());
        assertFalse(d.sim.commandWindowOpen());
        assertEquals(new BattleEvent.Ended(BattleOutcome.VICTORY), d.log.get(d.log.size() - 1));
        assertEquals(1, d.count(BattleEvent.Ended.class));
        assertEquals(0, d.count(BattleEvent.ActionFinished.class), "the killing action is simply over");
        assertEquals("death", d.sim.archon().pose().sbeState(), "not a flinch: the killing blow goes straight to the collapse");
        assertEquals("strike", d.sim.monk().pose().sbeState(), "the killing blow follows through");
        assertEquals(1f, d.sim.monk().pose().dashProgress());

        float frozenClock = d.sim.elapsedSeconds();
        d.forget();
        // The strike clip has 0.58 s left after its contact, then the 0.45 s dash home.
        d.run(0.4f);
        assertEquals("strike", d.sim.monk().pose().sbeState());
        d.run(0.4f);
        assertEquals("combat_dash", d.sim.monk().pose().sbeState());
        float dash = d.sim.monk().pose().dashProgress();
        assertTrue(dash > 0f && dash < 1f);
        d.run(0.4f);
        assertEquals("victory", d.sim.monk().pose().sbeState(), "only once the monk is back home");
        assertEquals(0f, d.sim.monk().pose().dashProgress());
        assertEquals(0f, d.sim.archon().pose().recoil(), "cosmetic timers keep running in RESULT");
        assertEquals("death", d.sim.archon().pose().sbeState());
        assertEquals(1.2f, d.sim.archon().pose().clipTime(), 0.05f);

        d.run(2.5f); // 3.7 s after the blow
        assertEquals("defeated", d.sim.archon().pose().sbeState(), "death is clamped, then the fallen loop");
        assertEquals("victory", d.sim.monk().pose().sbeState());
        d.run(1.0f); // victory ran 0.58 + 0.45 .. + 3.2
        assertEquals("victory_loop", d.sim.monk().pose().sbeState());
        float loopTime = d.sim.monk().pose().clipTime();
        d.run(5f);
        assertEquals("victory_loop", d.sim.monk().pose().sbeState());
        assertTrue(d.sim.monk().pose().clipTime() <= MonkClips.VICTORY_LOOP.duration());
        assertTrue(d.sim.monk().pose().clipTime() != loopTime, "the loops keep animating in RESULT");
        assertEquals("defeated", d.sim.archon().pose().sbeState());

        // Nothing else moves, and input is ignored without even a rejection.
        assertFalse(d.sim.submit(BattleCommand.STRIKE));
        d.sim.pressConfirm();
        d.sim.pressDirection(ComboDirection.UP);
        d.sim.introFinished();
        d.run(0.5f);
        assertEquals(List.of(), d.log);
        assertEquals(frozenClock, d.sim.elapsedSeconds());
        assertEquals(BattlePhase.RESULT, d.sim.phase());
        assertEquals("Not your turn", d.sim.availability(BattleCommand.STRIKE).reason());
    }

    @Test
    void theDefeatedArchonNeverSinksOrFades() {
        BattleConfig config = BattleDriver.passiveArchon(BattleDriver.exact());
        BattleDriver d = new BattleDriver(config.withArchon(config.archon().withMaxHp(40f)), 1);
        for (int i = 0; i < 64 * 3; i++) { // intro
            d.step(BattleDriver.DT);
            assertEquals(1f, d.sim.archon().pose().presence());
        }
        d.start();
        d.command(BattleCommand.STRIKE);
        for (int i = 0; i < 64 * 12; i++) {
            d.step(BattleDriver.DT);
            assertEquals(1f, d.sim.archon().pose().presence(), "the death clip carries its own collapse");
            assertEquals(1f, d.sim.monk().pose().presence());
        }
        assertEquals(BattlePhase.RESULT, d.sim.phase());
    }

    @Test
    void aBattleThatEndsMidFrameStartsItsEndingsFromThatMoment() {
        BattleConfig config = BattleDriver.passiveArchon(BattleDriver.exact());
        BattleDriver d = new BattleDriver(config.withArchon(config.archon().withMaxHp(40f)), 1).start();
        d.command(BattleCommand.STRIKE);
        d.step(0.45f + 0.38f + 1.0f); // the blow lands 1.0 s before the end of this one update
        assertEquals(BattlePhase.RESULT, d.sim.phase());
        assertEquals("death", d.sim.archon().pose().sbeState());
        assertEquals(1.0f, d.sim.archon().pose().clipTime(), 1.0e-4f);
    }

    @Test
    void defeatClearsTheTelegraphAndPrompt() {
        BattleConfig config = BattleDriver.duel(EnemyAction.SLASH);
        config = new BattleConfig(30f, 10, config.atb(), config.resources(), config.damage(), config.melee(),
                config.flurry(), config.combo(), config.support(), config.archon());
        BattleDriver d = new BattleDriver(config, 1).start();
        d.awaitTelegraph();
        d.sim.submit(BattleCommand.STRIKE); // queued, must never run
        assertTrue(d.runUntil(s -> s.phase() == BattlePhase.RESULT, 3f));

        assertEquals(BattleOutcome.DEFEAT, d.sim.outcome());
        assertEquals(0f, d.sim.monk().hp());
        assertEquals(30f, d.sim.stats().damageTaken(), "the totals count HP actually lost: overkill is not damage taken");
        assertTrue(d.all(BattleEvent.DamageDealt.class).stream().anyMatch(e -> e.amount() == 38f),
                "while the popup still shows the whole blow");
        assertNull(d.sim.telegraph());
        assertNull(d.sim.prompt());
        assertNull(d.sim.currentAction());
        assertEquals(new BattleEvent.Ended(BattleOutcome.DEFEAT), d.log.get(d.log.size() - 1));
        assertEquals("defeat", d.sim.monk().pose().sbeState(), "not the hurt flinch: straight into the collapse");
        assertEquals("attack_slash", d.sim.archon().pose().sbeState(), "the Archon finishes its swing");
        d.run(1.0f);
        assertEquals("defeat", d.sim.monk().pose().sbeState());
        assertEquals(1.0f, d.sim.monk().pose().clipTime(), 0.05f);

        d.forget();
        d.run(3f);
        assertEquals(List.of(), d.log);
        assertEquals(1f, d.sim.archon().pose().presence());
        assertEquals(0f, d.sim.archon().pose().dashProgress(), "the Archon glided home");
        assertEquals("combat_idle", d.sim.archon().pose().sbeState());
        assertEquals("defeated", d.sim.monk().pose().sbeState());
        assertEquals(1f, d.sim.monk().pose().presence(), "the defeat clip contains its own collapse");
        assertEquals(0, d.sim.stats().turnsTaken(), "the queued Strike never started");
    }

    @Test
    void aKillingFlurryBlowEndsTheBattleWhenItsDamageIsDealt() {
        BattleConfig config = BattleDriver.passiveArchon(BattleDriver.exact());
        float contact = BattleDriver.DASH + MonkClips.FLURRY.cue(0) / config.flurry().playbackSpeed();

        // Pressed before the contact: graded at the press, the kill lands with the fist.
        BattleDriver early = new BattleDriver(config.withArchon(config.archon().withMaxHp(30f)), 1).start();
        early.command(BattleCommand.FLURRY);
        early.runActionTo(contact - 0.04f);
        early.sim.pressConfirm(); // PERFECT: 33 damage
        assertEquals(BattlePhase.ACTION, early.sim.phase());
        early.step(0.05f);
        assertEquals(BattlePhase.RESULT, early.sim.phase());
        assertEquals(new BattleEvent.Ended(BattleOutcome.VICTORY), early.log.get(early.log.size() - 1));

        // Pressed just after it: the fist has landed, so the press itself deals the damage and ends the battle.
        BattleDriver late = new BattleDriver(config.withArchon(config.archon().withMaxHp(30f)), 1).start();
        late.command(BattleCommand.FLURRY);
        late.runActionTo(contact + 0.04f);
        assertEquals(BattlePhase.ACTION, late.sim.phase());
        late.sim.pressConfirm();
        assertEquals(BattlePhase.RESULT, late.sim.phase());
        assertNull(late.sim.prompt());
        late.step(BattleDriver.DT);
        assertEquals(new BattleEvent.Ended(BattleOutcome.VICTORY), late.log.get(late.log.size() - 1));
    }
}
