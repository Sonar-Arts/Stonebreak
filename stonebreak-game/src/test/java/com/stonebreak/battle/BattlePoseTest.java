package com.stonebreak.battle;

import com.stonebreak.battle.api.ActorPose;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.PromptView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which authored clip each combatant shows when, outside its own attacks: stance, guard, parry,
 * hit reactions, stun. (Attack timelines are in MonkActionTest / FocusComboTest, endings in BattleEndTest.)
 */
class BattlePoseTest {

    /** Guard is up and settled before the telegraph starts; returns with the telegraph just begun. */
    private static BattleDriver guardedAgainst(EnemyAction action) {
        BattleDriver d = new BattleDriver(BattleDriver.duel(action), 1).start();
        assertTrue(d.command(BattleCommand.GUARD));
        assertTrue(d.awaitTelegraph());
        return d;
    }

    /** One update that brings the telegraph clock to {@code time} (the attack is the executing action). */
    private static void telegraphTo(BattleDriver d, float time) {
        d.runActionTo(time);
    }

    /** The distinct pose states seen, in order, while stepping {@code seconds}. */
    private static List<String> statesOver(BattleDriver d, CombatantViewPick who, float seconds) {
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < Math.round(seconds / BattleDriver.DT); i++) {
            String state = who.pose(d).sbeState();
            if (seen.isEmpty() || !seen.get(seen.size() - 1).equals(state)) seen.add(state);
            d.step(BattleDriver.DT);
        }
        return seen;
    }

    private interface CombatantViewPick {
        ActorPose pose(BattleDriver d);
        CombatantViewPick MONK = d -> d.sim.monk().pose();
        CombatantViewPick ARCHON = d -> d.sim.archon().pose();
    }

    // ---- stance -----------------------------------------------------------------------------------

    @Test
    void bothRestInTheirLegacyIdleDuringTheIntroWithTheClipRunning() {
        BattleDriver d = new BattleDriver(BattleDriver.baseline(200f, 10), 1);
        assertEquals("idle", d.sim.monk().pose().sbeState());
        assertEquals("idle", d.sim.archon().pose().sbeState());
        d.run(1f);
        assertEquals("idle", d.sim.monk().pose().sbeState());
        assertEquals(1f, d.sim.monk().pose().clipTime(), 1.0e-4f);
        assertEquals(1f, d.sim.archon().pose().clipTime(), 1.0e-4f);
        d.run(3f); // the monk's idle loop is 3.2 s, the Archon's 6.0 s
        assertEquals(0.8f, d.sim.monk().pose().clipTime(), 1.0e-3f);
        assertEquals(4f, d.sim.archon().pose().clipTime(), 1.0e-3f);
    }

    @Test
    void theMonkEntersItsStanceWhenTheIntroEndsWhileTheGaugesAlreadyRun() {
        BattleDriver d = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.baseline(200f, 10)), 1);
        d.run(2f);
        d.sim.introFinished();
        assertEquals("combat_enter", d.sim.monk().pose().sbeState());
        assertEquals(0f, d.sim.monk().pose().clipTime());
        assertEquals("combat_idle", d.sim.archon().pose().sbeState());

        d.run(0.5f);
        assertEquals("combat_enter", d.sim.monk().pose().sbeState());
        assertEquals(0.5f, d.sim.monk().pose().clipTime(), 1.0e-4f);
        assertTrue(d.sim.monk().atb() > 0.5f, "the gauges do not wait for the entrance");
        d.run(0.3f);
        assertEquals("combat_idle", d.sim.monk().pose().sbeState());
        assertEquals(0.8f - 0.72f, d.sim.monk().pose().clipTime(), 0.02f, "the loop picks up where the entrance ended");
        d.run(4f);
        assertEquals("combat_idle", d.sim.monk().pose().sbeState());
        assertTrue(d.sim.monk().pose().clipTime() < MonkClips.COMBAT_IDLE.duration());
    }

    // ---- guard ------------------------------------------------------------------------------------

    @Test
    void guardEntersHoldsBlocksExitsAndReturnsToTheReadyStance() {
        BattleDriver d = new BattleDriver(BattleDriver.duel(EnemyAction.SLASH), 1).start();
        assertTrue(d.command(BattleCommand.GUARD));
        assertEquals(0.32f, d.sim.currentAction().duration(), "the Guard action is only the stance going up");
        assertTrue(d.sim.monk().has(BattleStatus.GUARDING));

        List<String> seen = statesOver(d, CombatantViewPick.MONK, 3.2f);
        assertEquals(List.of("guard_enter", "guard", "block", "guard_exit", "combat_idle"), seen);
        assertEquals(1, d.sim.stats().blocks());
    }

    @Test
    void theBlockClipStartsOnTheImpact() {
        BattleDriver d = guardedAgainst(EnemyAction.OVERHEAD);
        telegraphTo(d, 1.03f - 0.01f);
        assertEquals("guard", d.sim.monk().pose().sbeState());
        telegraphTo(d, 1.03f + 0.1f);
        assertEquals("block", d.sim.monk().pose().sbeState());
        assertEquals(0.1f, d.sim.monk().pose().clipTime(), 1.0e-4f);
        telegraphTo(d, 1.03f + 0.58f + 0.1f);
        assertEquals("guard_exit", d.sim.monk().pose().sbeState());
        assertEquals(0.1f, d.sim.monk().pose().clipTime(), 1.0e-4f);
        telegraphTo(d, 1.03f + 0.58f + 0.38f + 0.02f);
        assertEquals("combat_idle", d.sim.monk().pose().sbeState());
    }

    @Test
    void theParryDeflectMeetsTheBladeHoweverEarlyOrLateThePressWas() {
        float impact = EnemyAction.OVERHEAD.impactTime();
        float contact = MonkClips.PARRY.cue(0);
        // Window is [impact - 0.25, impact]: first press waits for impact - 0.20, the others enter the clip late.
        for (float pressAt : new float[] {impact - 0.24f, impact - 0.20f, impact - 0.12f, impact - 0.01f}) {
            BattleDriver d = guardedAgainst(EnemyAction.OVERHEAD);
            telegraphTo(d, pressAt);
            d.sim.pressConfirm();
            if (pressAt < impact - contact - 1.0e-3f) {
                assertEquals("guard", d.sim.monk().pose().sbeState(), "pressed early: the clip waits for its moment");
                telegraphTo(d, impact - contact - 0.005f);
                assertEquals("guard", d.sim.monk().pose().sbeState());
            } else {
                assertEquals("parry", d.sim.monk().pose().sbeState(), "pressed late: the clip starts at once, part-way in");
                assertEquals(pressAt - (impact - contact), d.sim.monk().pose().clipTime(), 1.0e-3f);
            }

            telegraphTo(d, impact); // one update that ends exactly on the blow
            assertEquals(1, d.sim.stats().parries(), "press at " + pressAt);
            assertEquals("parry", d.sim.monk().pose().sbeState());
            assertEquals(contact, d.sim.monk().pose().clipTime(), 1.0e-4f, "press at " + pressAt);
            assertEquals(impact, d.sim.archon().pose().clipTime(), 1.0e-4f, "both clips are on their contact frames");

            telegraphTo(d, impact + (MonkClips.PARRY.duration() - contact) + 0.05f);
            assertEquals("guard_exit", d.sim.monk().pose().sbeState());
            d.finishAction();
            assertEquals("combat_idle", d.sim.monk().pose().sbeState());
            assertEquals(200f, d.sim.monk().hp());
        }
    }

    @Test
    void aFailedParryStillBlocksWithTheBlockClip() {
        BattleDriver d = guardedAgainst(EnemyAction.SLASH);
        telegraphTo(d, 0.2f);
        d.sim.pressConfirm(); // far too early
        assertEquals("guard", d.sim.monk().pose().sbeState());
        telegraphTo(d, 0.66f + 0.05f);
        assertEquals("block", d.sim.monk().pose().sbeState());
    }

    @Test
    void aReactionGuardPlaysTheSameClipsWithoutAnActionOfItsOwn() {
        BattleDriver d = new BattleDriver(BattleDriver.duel(EnemyAction.OVERHEAD), 1).start();
        d.awaitTelegraph();
        assertTrue(d.sim.submit(BattleCommand.GUARD));
        assertEquals(EnemyAction.OVERHEAD, d.sim.currentAction().enemyAction(), "no action lock");
        assertEquals("guard_enter", d.sim.monk().pose().sbeState());
        assertEquals(List.of("guard_enter", "guard", "block", "guard_exit", "combat_idle"),
                statesOver(d, CombatantViewPick.MONK, 2.5f));
    }

    @Test
    void anotherCommandDropsTheGuardWithoutTheExitClip() {
        BattleDriver d = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.exact()), 1).start();
        d.command(BattleCommand.GUARD);
        d.finishAction();
        assertEquals("guard", d.sim.monk().pose().sbeState());
        d.awaitWindow();
        assertEquals("guard", d.sim.monk().pose().sbeState(), "held for as long as the status lasts");
        assertTrue(d.sim.submit(BattleCommand.STRIKE));
        List<String> seen = statesOver(d, CombatantViewPick.MONK, 2.2f);
        assertEquals(List.of("combat_dash", "strike", "combat_dash", "combat_idle"), seen);
    }

    @Test
    void aFreeActionKeepsTheGuardAndTheStanceGoesBackUpAfterIt() {
        BattleDriver d = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.exact()), 1).start();
        d.command(BattleCommand.GUARD);
        d.finishAction();
        d.awaitWindow();
        assertTrue(d.sim.submit(BattleCommand.MARTIAL_SURGE));
        assertEquals(List.of("martial_surge", "guard_enter", "guard"), statesOver(d, CombatantViewPick.MONK, 2.5f));
        assertTrue(d.sim.monk().has(BattleStatus.GUARDING));
    }

    // ---- hit reactions ----------------------------------------------------------------------------

    @Test
    void anUnguardedBlowPlaysHurtFromTheImpactThenTheReadyStance() {
        BattleDriver d = new BattleDriver(BattleDriver.duel(EnemyAction.SLASH), 1).start();
        d.awaitTelegraph();
        telegraphTo(d, 0.66f - 0.01f);
        assertEquals("combat_idle", d.sim.monk().pose().sbeState());
        telegraphTo(d, 0.66f + 0.2f);
        assertEquals("hurt", d.sim.monk().pose().sbeState());
        assertEquals(0.2f, d.sim.monk().pose().clipTime(), 1.0e-4f);
        d.step(BattleDriver.DT);
        assertTrue(d.sim.monk().pose().recoil() > 0f, "the knock-back is kept");
        telegraphTo(d, 0.66f + 0.72f + 0.05f);
        assertEquals("combat_idle", d.sim.monk().pose().sbeState());
        assertEquals(0f, d.sim.monk().pose().recoil());
    }

    @Test
    void theArchonFlinchesOnEveryHitAndANewHitRestartsTheFlinch() {
        BattleDriver d = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.exact()), 1).start();
        assertEquals("combat_idle", d.sim.archon().pose().sbeState());
        d.command(BattleCommand.FLURRY);
        float speed = BattleDriver.exact().flurry().playbackSpeed();
        float c0 = BattleDriver.DASH + MonkClips.FLURRY.cue(0) / speed;
        float c1 = BattleDriver.DASH + MonkClips.FLURRY.cue(1) / speed;
        d.runActionTo(c0);
        d.sim.pressConfirm();
        d.runActionTo(c0 + 0.3f);
        assertEquals("hurt", d.sim.archon().pose().sbeState());
        assertEquals(0.3f, d.sim.archon().pose().clipTime(), 1.0e-3f);

        d.runActionTo(c1 - 0.05f);
        d.sim.pressConfirm(); // graded early: the damage, and so the flinch, lands with the fist
        d.runActionTo(c1 + 0.1f);
        assertEquals("hurt", d.sim.archon().pose().sbeState());
        assertEquals(0.1f, d.sim.archon().pose().clipTime(), 1.0e-3f, "restarted by the second hit");

        d.finishAction();
        assertEquals("combat_idle", d.sim.archon().pose().sbeState());
    }

    // ---- stun -------------------------------------------------------------------------------------

    @Test
    void stunPlaysEnterLoopExitAndHitsDoNotBreakIt() {
        BattleDriver d = new BattleDriver(BattleDriver.passiveArchon(BattleDriver.exact()), 1).start();
        d.command(BattleCommand.STUNNING_STRIKE);
        float contact = BattleDriver.DASH + MonkClips.STUNNING_STRIKE.cue(0);
        d.runActionTo(contact + 0.3f);
        assertEquals("stunned_enter", d.sim.archon().pose().sbeState(), "stun outranks the flinch of the same blow");
        assertEquals(0.3f, d.sim.archon().pose().clipTime(), 1.0e-3f);
        d.runActionTo(contact + 0.66f + 0.2f);
        assertEquals("stunned", d.sim.archon().pose().sbeState());
        assertEquals(0.2f, d.sim.archon().pose().clipTime(), 1.0e-3f);
        d.finishAction();

        d.run(2f);
        d.sim.stunArchonNow(); // refreshed while still down: the loop carries on, no second stagger
        assertEquals("stunned", d.sim.archon().pose().sbeState());
        d.command(BattleCommand.STRIKE);
        assertTrue(d.sim.archon().has(BattleStatus.STUNNED));
        d.runActionTo(BattleDriver.DASH + 0.38f + 0.1f);
        assertEquals("stunned", d.sim.archon().pose().sbeState(), "a hit on a stunned Archon stays in the stun clips");
        d.step(BattleDriver.DT);
        assertTrue(d.sim.archon().pose().recoil() > 0f);

        assertTrue(d.runUntil(s -> !s.archon().has(BattleStatus.STUNNED), 8f));
        assertEquals(List.of("stunned_exit", "combat_idle"), statesOver(d, CombatantViewPick.ARCHON, 1.2f));
    }

    // ---- names ------------------------------------------------------------------------------------

    private static void observe(ActorPose pose, Set<String> authored, Set<String> seen) {
        assertTrue(authored.contains(pose.sbeState()), () -> "the asset has no state '" + pose.sbeState() + "'");
        assertEquals(1f, pose.presence());
        assertTrue(pose.clipTime() >= 0f);
        seen.add(pose.sbeState());
    }

    @Test
    void everyPoseStateOfAFullFightIsAStateTheAssetsReallyHave() {
        Set<String> monkStates = new HashSet<>(ClipCatalog.monkJson().keySet());
        monkStates.addAll(ClipCatalog.monkLegacyStates());
        Set<String> archonStates = new HashSet<>(ClipCatalog.archonJson().keySet());
        archonStates.addAll(ClipCatalog.archonAttackImpacts().keySet());
        archonStates.add("idle");
        archonStates.add("wandering");

        Set<String> monkSeen = new HashSet<>();
        Set<String> archonSeen = new HashSet<>();
        for (long seed : new long[] {3L, 11L}) {
            // Seed 3 wins with a sturdy monk; seed 11 is a frail one that loses.
            BattleConfig config = BattleDriver.baseline(seed == 3L ? 2000f : 60f, 14);
            BattleDriver d = new BattleDriver(config.withArchon(config.archon().withMaxHp(1500f)), seed);
            Random whim = new Random(seed);
            BattleCommand[] plan = {BattleCommand.GUARD, BattleCommand.STRIKE, BattleCommand.GUARD,
                    BattleCommand.FLURRY, BattleCommand.SWIFT_STEP, BattleCommand.MARTIAL_SURGE, BattleCommand.STRIKE,
                    BattleCommand.STUNNING_STRIKE, BattleCommand.MEDITATE, BattleCommand.MARTIAL_SURGE,
                    BattleCommand.FLURRY};
            int next = 0;
            int guards = 0;
            for (int frame = 0; frame < 64 * 400 && d.sim.phase() != BattlePhase.RESULT; frame++) {
                if (frame == 64) d.sim.introFinished();
                // Like a player: a raised guard is held until the blow it was raised for.
                if (d.sim.commandWindowOpen() && !d.sim.monk().has(BattleStatus.GUARDING)) {
                    BattleCommand command = d.sim.focusReady() ? BattleCommand.FOCUS_COMBO : plan[next % plan.length];
                    if (command != BattleCommand.FOCUS_COMBO) next++;
                    if (d.sim.submit(command) && command == BattleCommand.GUARD) guards++;
                }
                PromptView prompt = d.sim.prompt();
                if (prompt instanceof PromptView.Ring ring && ring.elapsed() >= ring.perfectStart()) {
                    d.sim.pressConfirm();
                } else if (prompt instanceof PromptView.Parry && (guards % 2 == 1 || d.sim.telegraph().inParryWindow())) {
                    d.sim.pressConfirm(); // every other guard far too early: that one only blocks
                } else if (prompt instanceof PromptView.Combo combo && combo.stepElapsed() > 0.1f + whim.nextInt(5) * 0.1f) {
                    ComboDirection right = combo.sequence().get(combo.index());
                    d.sim.pressDirection(whim.nextInt(12) == 0 ? ComboDirection.values()[whim.nextInt(4)] : right);
                }
                d.step(BattleDriver.DT);

                observe(d.sim.monk().pose(), monkStates, monkSeen);
                observe(d.sim.archon().pose(), archonStates, archonSeen);
            }
            assertEquals(BattlePhase.RESULT, d.sim.phase(), "seed " + seed + " should reach a result");
            for (int frame = 0; frame < 64 * 8; frame++) { // the endings: death / victory, defeat
                d.step(BattleDriver.DT);
                observe(d.sim.monk().pose(), monkStates, monkSeen);
                observe(d.sim.archon().pose(), archonStates, archonSeen);
            }
        }
        // The fights really exercised the clip set (so the name check above means something).
        for (BattleClip clip : MonkClips.USED) {
            assertTrue(monkSeen.contains(clip.state()), () -> "never played: monk " + clip.state());
        }
        for (BattleClip clip : ArchonClips.REACTIONS) {
            assertTrue(archonSeen.contains(clip.state()), () -> "never played: Archon " + clip.state());
        }
        for (EnemyAction action : EnemyAction.values()) assertTrue(archonSeen.contains(action.sbeState()));
        for (BattleClip unused : MonkClips.UNUSED) assertFalse(monkSeen.contains(unused.state()));
    }
}
