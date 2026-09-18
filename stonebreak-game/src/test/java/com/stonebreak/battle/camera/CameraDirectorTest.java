package com.stonebreak.battle.camera;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleEvent.DamageFlavor;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.EnemyAction;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.battle.api.TimedGrade;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** The director's rules, driven end to end through {@link BattleCameraSystem} with a fake battle. */
class CameraDirectorTest {

    private static final ShotLibrary LIBRARY = ShotLibrary.standard();

    private static void assertCovers(CameraHarness h, Situation situation) {
        assertEquals(situation, h.camera.situation());
        assertTrue(LIBRARY.variants(situation).stream().anyMatch(s -> s.name().equals(h.camera.sequenceName())),
                h.camera.sequenceName() + " is not a " + situation + " variant");
    }

    private static CameraShot liveShot(CameraHarness h) {
        for (ShotSequence.Step step : LIBRARY.byName(h.camera.sequenceName()).steps()) {
            if (step.shot().name().equals(h.camera.shotName())) return step.shot();
        }
        throw new AssertionError("unknown shot " + h.camera.shotName());
    }

    // ---- intro -------------------------------------------------------------------------------------

    @Test void introPlaysDuringTheIntroPhaseThenHandsOverToIdle() {
        CameraHarness h = new CameraHarness(1L);
        h.view.phase = BattlePhase.INTRO;
        assertFalse(h.camera.introFinished());
        assertNotNull(h.camera.frame(), "a frame exists before the first update");

        h.tick();
        assertEquals(Situation.INTRO, h.camera.situation());
        assertEquals("INTRO_CRANE", h.camera.shotName());
        List<String> shots = new ArrayList<>();
        float elapsed = 0f;
        while (!h.camera.introFinished() && elapsed < 10f) {
            if (shots.isEmpty() || !shots.getLast().equals(h.camera.shotName())) shots.add(h.camera.shotName());
            h.tick();
            elapsed += CameraHarness.DT;
        }
        assertEquals(List.of("INTRO_CRANE", "INTRO_ARCHON_HERO", "INTRO_MONK", "INTRO_SETTLE"), shots);
        assertTrue(elapsed > 3.5f && elapsed < 5f, "intro took " + elapsed + " s");
        assertEquals(1f, h.camera.timeScale());

        h.view.phase = BattlePhase.RUNNING;
        h.run(1f);
        assertCovers(h, Situation.IDLE);
        assertEquals("WIDE_DRIFT", h.camera.shotName());
    }

    @Test void skipIntroCutsStraightToTheWideShot() {
        CameraHarness h = new CameraHarness(1L);
        h.view.phase = BattlePhase.INTRO;
        h.run(0.5f);
        assertFalse(h.camera.introFinished());
        h.camera.skipIntro();
        assertTrue(h.camera.introFinished());
        h.tick(); // the model has not left INTRO yet; the camera must not restart the intro
        assertEquals("WIDE_DRIFT", h.camera.shotName());
        assertFalse(h.camera.blending(), "a skip is a cut");
        h.run(1f);
        assertCovers(h, Situation.IDLE);
    }

    @Test void skipBeforeTheFirstUpdateAndBattlesThatNeverShowIntroBothFinishAtOnce() {
        CameraHarness skipped = new CameraHarness(1L);
        skipped.view.phase = BattlePhase.INTRO;
        skipped.camera.skipIntro();
        assertTrue(skipped.camera.introFinished());
        skipped.tick();
        assertCovers(skipped, Situation.IDLE);

        CameraHarness running = new CameraHarness(1L);
        running.view.phase = BattlePhase.RUNNING;
        running.tick();
        assertTrue(running.camera.introFinished());
        assertCovers(running, Situation.IDLE);
    }

    @Test void modelLeavingIntroEarlyEndsTheCameraIntro() {
        CameraHarness h = new CameraHarness(1L);
        h.view.phase = BattlePhase.INTRO;
        h.run(1f);
        h.view.phase = BattlePhase.RUNNING;
        h.tick();
        assertTrue(h.camera.introFinished());
        assertCovers(h, Situation.IDLE);
    }

    // ---- event → sequence --------------------------------------------------------------------------

    @Test void everyMonkCommandMapsToItsSituation() {
        Map<BattleCommand, Situation> expected = new EnumMap<>(BattleCommand.class);
        expected.put(BattleCommand.STRIKE, Situation.STRIKE);
        expected.put(BattleCommand.FLURRY, Situation.FLURRY);
        expected.put(BattleCommand.STUNNING_STRIKE, Situation.STUNNING_STRIKE);
        expected.put(BattleCommand.SWIFT_STEP, Situation.SWIFT_STEP);
        expected.put(BattleCommand.MARTIAL_SURGE, Situation.MARTIAL_SURGE);
        expected.put(BattleCommand.MEDITATE, Situation.MEDITATE);
        expected.put(BattleCommand.GUARD, Situation.GUARD);
        expected.put(BattleCommand.FOCUS_COMBO, Situation.COMBO_WINDUP);
        assertEquals(BattleCommand.values().length, expected.size());
        for (long seed = 0; seed < 4; seed++) {
            for (Map.Entry<BattleCommand, Situation> e : expected.entrySet()) {
                CameraHarness h = new CameraHarness(seed);
                h.run(0.2f);
                h.monkStarts(e.getKey()).tick();
                assertCovers(h, e.getValue());
                assertEquals(0f, h.camera.shakeTrauma(), "starting an action is not a hit");
            }
        }
    }

    @Test void archonAttacksMapToMeleeOrFrostCastAndToTheGuardShotWhenGuarding() {
        for (EnemyAction action : EnemyAction.values()) {
            CameraHarness open = new CameraHarness(3L);
            open.run(0.2f);
            open.archonStarts(action).tick();
            assertCovers(open, action == EnemyAction.FROST_CAST ? Situation.FROST_CAST : Situation.ARCHON_MELEE);

            CameraHarness guarded = new CameraHarness(3L);
            guarded.view.monk.statuses.add(new StatusView(BattleStatus.GUARDING, 5f, 1));
            guarded.run(0.2f);
            assertCovers(guarded, Situation.GUARD);
            String before = guarded.camera.shotName();
            guarded.archonStarts(action).tick();
            assertCovers(guarded, Situation.GUARD);
            assertEquals(before, guarded.camera.shotName(), "the guard shot is held, not restarted");
        }
    }

    @Test void telegraphSeenOnlyInTheViewStillGetsItsShot() {
        CameraHarness h = new CameraHarness(3L);
        h.run(0.2f);
        h.archonStarts(EnemyAction.OVERHEAD);
        h.view.events.clear(); // director created mid-windup / events lost
        h.tick();
        assertCovers(h, Situation.ARCHON_MELEE);
    }

    @Test void strikeCutsOnImpactAndBlendsBackToWideWhenTheActionFinishes() {
        CameraHarness h = new CameraHarness(5L);
        h.run(0.3f);
        h.monkStarts(BattleCommand.STRIKE).tick();
        assertCovers(h, Situation.STRIKE);
        assertTrue(h.camera.cutThisFrame());
        String track = h.camera.shotName();
        assertTrue(track.startsWith("STRIKE_TRACK"));
        h.run(1.5f);
        assertEquals(track, h.camera.shotName(), "the track holds until the blow lands, however late");

        h.impact(CombatantId.MONK).emit(new BattleEvent.DamageDealt(CombatantId.ARCHON, 30f, DamageFlavor.NORMAL)).tick();
        assertTrue(h.camera.shotName().startsWith("STRIKE_IMPACT"), h.camera.shotName());
        assertEquals(CameraShot.Subject.BOTH, liveShot(h).subject(), "the blow lands in a two-shot");
        assertTrue(h.camera.cutThisFrame(), "impact is a hard cut");
        assertTrue(h.camera.shakeTrauma() > 0f);
        h.impact(CombatantId.MONK).tick(); // Martial Surge bonus hit: no further cut
        assertFalse(h.camera.cutThisFrame());

        h.run(0.5f);
        h.monkFinishes().tick();
        assertCovers(h, Situation.IDLE);
        assertEquals("WIDE_DRIFT", h.camera.shotName(), "idle re-enters on its home angle");
        assertTrue(h.camera.blending());
        assertFalse(h.camera.cutThisFrame());
        h.run(1f);
        assertFalse(h.camera.blending());
    }

    @Test void archonMeleeCutsToATwoShotOfTheBlowOnImpactThenReturnsToIdle() {
        CameraHarness h = new CameraHarness(5L);
        h.run(0.3f);
        h.archonStarts(EnemyAction.SLASH).tick();
        assertTrue(h.camera.shotName().startsWith("ARCHON_LOW"));
        h.impact(CombatantId.MONK).tick(); // someone else's impact must not cut this sequence
        assertTrue(h.camera.shotName().startsWith("ARCHON_LOW"));
        h.run(0.6f);
        h.impact(CombatantId.ARCHON).tick();
        assertTrue(h.camera.shotName().startsWith("ARCHON_BLOW"), h.camera.shotName());
        assertEquals(CameraShot.Subject.BOTH, liveShot(h).subject(), "blow and reaction share the frame");
        h.run(0.5f);
        h.archonFinishes().tick();
        assertCovers(h, Situation.IDLE);
    }

    @Test void stunCancellingTheTelegraphReleasesTheArchonShot() {
        CameraHarness h = new CameraHarness(5L);
        h.run(0.3f);
        h.archonStarts(EnemyAction.OVERHEAD).tick();
        assertCovers(h, Situation.ARCHON_MELEE);
        h.view.telegraph = null;
        h.view.currentAction = null;
        h.emit(new BattleEvent.TelegraphCancelled(EnemyAction.OVERHEAD)).tick();
        assertCovers(h, Situation.IDLE);
    }

    @Test void frostCastCutsWideAtImpact() {
        CameraHarness h = new CameraHarness(2L);
        h.run(0.3f);
        h.archonStarts(EnemyAction.FROST_CAST).tick();
        assertTrue(h.camera.shotName().startsWith("FROST_ORBIT"));
        h.run(1.1f);
        h.impact(CombatantId.ARCHON).tick();
        assertTrue(h.camera.cutThisFrame());
        assertTrue(h.camera.shotName().contains("WIDE"), h.camera.shotName());
    }

    @Test void martialSurgeIsCoveredAsAFreeActionAndAsARealActionAndReturnsToTheOpenMenu() {
        // Free action: the SURGE status is the only cue. No currentAction, no ActionFinished.
        CameraHarness free = new CameraHarness(4L);
        free.view.commandWindowOpen = true;
        free.run(0.6f);
        assertCovers(free, Situation.TURN_READY);
        free.emit(new BattleEvent.StatusApplied(CombatantId.MONK, BattleStatus.SURGE, -1f)).tick();
        assertCovers(free, Situation.MARTIAL_SURGE);
        assertTrue(free.camera.cutThisFrame());
        free.run(0.5f);
        assertCovers(free, Situation.MARTIAL_SURGE);
        free.run(0.8f); // nothing backs it: released like any orphan
        assertCovers(free, Situation.TURN_READY);

        // Real action: status and ActionStarted arrive together and are ONE surge (one cut).
        CameraHarness action = new CameraHarness(4L);
        action.view.commandWindowOpen = true;
        action.run(0.6f);
        action.emit(new BattleEvent.StatusApplied(CombatantId.MONK, BattleStatus.SURGE, -1f));
        action.monkStarts(BattleCommand.MARTIAL_SURGE).tick();
        assertCovers(action, Situation.MARTIAL_SURGE);
        String shot = action.camera.shotName();
        int cuts = 0;
        for (int i = 0; i < 90; i++) {
            action.tick();
            if (action.camera.cutThisFrame()) cuts++;
            assertEquals(shot, action.camera.shotName());
        }
        assertEquals(0, cuts, "held for the whole action");
        action.view.commandWindowOpen = true; // the gauge stayed full
        action.monkFinishes().tick();
        assertCovers(action, Situation.TURN_READY);
    }

    @Test void swiftStepHoldsItsOrbitUntilTheActionFinishes() {
        CameraHarness h = new CameraHarness(4L);
        h.run(0.3f);
        h.monkStarts(BattleCommand.SWIFT_STEP).tick();
        assertCovers(h, Situation.SWIFT_STEP);
        h.run(2.0f); // longer than the authored arc: it holds, it does not wander off
        assertCovers(h, Situation.SWIFT_STEP);
        h.monkFinishes().tick();
        assertCovers(h, Situation.IDLE);
    }

    @Test void aLostActionFinishedCannotStrandTheCamera() {
        CameraHarness h = new CameraHarness(4L);
        h.run(0.2f);
        h.monkStarts(BattleCommand.MEDITATE).tick();
        h.view.currentAction = null; // the model moved on but the event never reached us
        h.run(0.5f);
        assertCovers(h, Situation.MEDITATE);
        h.run(0.8f);
        assertCovers(h, Situation.IDLE);
    }

    // ---- priority ----------------------------------------------------------------------------------

    @Test void turnReadyPushesInHoldsWhileTheMenuIsOpenAndYieldsToActions() {
        CameraHarness h = new CameraHarness(7L);
        h.run(0.3f);
        assertEquals(Situation.Priority.IDLE, h.camera.situation().priority());

        h.view.commandWindowOpen = true;
        h.emit(new BattleEvent.TurnReady(CombatantId.MONK)).tick();
        assertCovers(h, Situation.TURN_READY);
        assertTrue(h.camera.blending(), "0.4 s push-in, not a cut");
        h.run(0.45f);
        assertFalse(h.camera.blending());
        String held = h.camera.shotName();
        Vector3f eye = new Vector3f(h.camera.frame().eye());
        h.run(12f);
        assertEquals(held, h.camera.shotName(), "no idle soft cuts while the menu is open");
        assertEquals(eye, h.camera.frame().eye());

        h.monkStarts(BattleCommand.STRIKE);
        h.view.commandWindowOpen = true; // even if the window flag lingers, the action wins
        h.tick().run(0.3f);
        assertCovers(h, Situation.STRIKE);

        h.monkFinishes().tick();
        assertCovers(h, Situation.TURN_READY);
        h.view.commandWindowOpen = false;
        h.tick();
        assertCovers(h, Situation.IDLE);
    }

    @Test void monkActionPreemptsAnArchonWindupAndTheWindupResumesAfterwards() {
        CameraHarness h = new CameraHarness(7L);
        h.run(0.2f);
        h.archonStarts(EnemyAction.OVERHEAD).tick();
        assertCovers(h, Situation.ARCHON_MELEE);
        var telegraph = h.view.telegraph;
        h.monkStarts(BattleCommand.STRIKE).tick();
        assertCovers(h, Situation.STRIKE);
        h.view.telegraph = telegraph;
        h.monkFinishes().tick();
        assertCovers(h, Situation.ARCHON_MELEE);
    }

    @Test void ultimateIsOnlyPreemptedByTheResult() {
        CameraHarness h = new CameraHarness(7L);
        h.run(0.2f);
        h.monkStarts(BattleCommand.FOCUS_COMBO).tick();
        assertEquals(Situation.Priority.ULTIMATE, h.camera.situation().priority());
        String shot = h.camera.shotName();

        h.view.commandWindowOpen = true;
        h.emit(new BattleEvent.TurnReady(CombatantId.MONK));
        h.emit(new BattleEvent.TelegraphStarted(EnemyAction.SLASH, 0.66f));
        h.emit(new BattleEvent.ActionStarted(CombatantId.MONK, "Strike", BattleCommand.STRIKE, null, 1f));
        h.tick().run(0.3f);
        assertEquals(shot, h.camera.shotName());
        assertEquals(Situation.COMBO_WINDUP, h.camera.situation());

        h.view.phase = BattlePhase.RESULT;
        h.view.outcome = BattleOutcome.VICTORY;
        h.emit(new BattleEvent.Ended(BattleOutcome.VICTORY)).tick();
        assertCovers(h, Situation.VICTORY);

        // Nothing preempts the result.
        h.monkStarts(BattleCommand.STRIKE).tick();
        h.archonStarts(EnemyAction.SLASH).tick();
        assertCovers(h, Situation.VICTORY);
    }

    @Test void victoryShowsTheFallThenLoopsTheOrbitAndDefeatHolds() {
        CameraHarness won = new CameraHarness(7L);
        won.run(0.2f);
        won.view.phase = BattlePhase.RESULT;
        won.view.outcome = BattleOutcome.VICTORY;
        won.emit(new BattleEvent.Ended(BattleOutcome.VICTORY)).tick();
        assertEquals("VICTORY_ARCHON_FALLS", won.camera.shotName());
        won.run(1.5f);
        assertEquals(0f, won.camera.shakeTrauma(), "no hit, no shake, until the body lands");
        won.run(ShotLibrary.ARCHON_GROUND_CONTACT_SECONDS - 1.5f + 0.05f);
        assertTrue(won.camera.shakeTrauma() > 0.4f, "the collapse hits the floor");
        assertEquals("VICTORY_ARCHON_FALLS", won.camera.shotName());
        won.run(1.4f);
        assertEquals("VICTORY_ORBIT", won.camera.shotName());
        Vector3f a = new Vector3f(won.camera.frame().eye());
        won.run(14f); // one full lap later
        assertEquals("VICTORY_ORBIT", won.camera.shotName());
        assertTrue(a.distance(won.camera.frame().eye()) < 0.05f, "the orbit loops seamlessly");

        CameraHarness lost = new CameraHarness(7L);
        lost.run(0.2f);
        lost.view.phase = BattlePhase.RESULT; // Ended event missed: the phase alone is enough
        lost.view.outcome = BattleOutcome.DEFEAT;
        lost.tick();
        assertCovers(lost, Situation.DEFEAT);
        lost.run(8f);
        Vector3f held = new Vector3f(lost.camera.frame().eye());
        lost.run(3f);
        assertEquals(held, lost.camera.frame().eye(), "the pull-up holds");
    }

    // ---- prompt-safe rule --------------------------------------------------------------------------

    @Test void flurryIsOnItsPromptSafeShotBeforeTheFirstRingAndNothingCutsWhileRingsAreOpen() {
        CameraHarness h = new CameraHarness(9L);
        h.run(0.3f);
        // Worst case: the first ring opens in the very frame the action starts.
        h.monkStarts(BattleCommand.FLURRY);
        h.view.prompt = CameraHarness.ring(0);
        h.emit(new BattleEvent.PromptOpened(PromptKind.RING)).tick();
        assertCovers(h, Situation.FLURRY);
        assertTrue(liveShot(h).promptSafe());
        assertFalse(h.camera.blending());
        String shot = h.camera.shotName();
        Vector3f eye = new Vector3f(h.camera.frame().eye());

        // Everything that would normally move the camera arrives while the ring is open.
        h.view.commandWindowOpen = true;
        h.emit(new BattleEvent.TurnReady(CombatantId.MONK)).tick();
        h.impact(CombatantId.MONK).tick();
        h.archonStarts(EnemyAction.SLASH).tick();
        h.view.currentAction = null;
        h.emit(new BattleEvent.ActionFinished(CombatantId.MONK)).tick();
        for (int i = 0; i < 90; i++) {
            h.tick();
            assertEquals(shot, h.camera.shotName());
            assertFalse(h.camera.cutThisFrame());
            assertFalse(h.camera.blending());
            assertEquals(eye, h.camera.frame().eye(), "prompt-safe means still");
        }

        h.view.prompt = null;
        h.tick();
        assertNotEquals(shot, h.camera.shotName(), "released once the prompt closes");
    }

    @Test void flurryHoldsOneShotAcrossAllThreeRingsAndTheGapsBetweenThem() {
        CameraHarness h = new CameraHarness(9L);
        h.run(0.3f);
        h.monkStarts(BattleCommand.FLURRY).tick();
        String shot = h.camera.shotName();
        for (int hit = 0; hit < 3; hit++) {
            h.run(0.25f); // gap: no prompt
            h.view.prompt = CameraHarness.ring(hit);
            h.emit(new BattleEvent.PromptOpened(PromptKind.RING)).run(0.5f);
            h.view.prompt = null;
            h.emit(new BattleEvent.PromptResolved(PromptKind.RING, TimedGrade.PERFECT, hit));
            h.impact(CombatantId.MONK).emit(new BattleEvent.DamageDealt(CombatantId.ARCHON, 20f, DamageFlavor.NORMAL)).tick();
            assertEquals(shot, h.camera.shotName());
        }
        h.monkFinishes().tick();
        assertCovers(h, Situation.IDLE);
    }

    @Test void guardHoldsItsTwoShotFromTheCommandThroughTheParryPromptToTheEndOfTheAttack() {
        CameraHarness h = new CameraHarness(11L);
        h.run(0.3f);
        h.monkStarts(BattleCommand.GUARD);
        h.view.monk.statuses.add(new StatusView(BattleStatus.GUARDING, 8f, 1));
        h.tick();
        assertCovers(h, Situation.GUARD);
        String shot = h.camera.shotName();
        h.run(0.2f);
        h.monkFinishes().run(2f); // stance persists after the command's own animation
        assertEquals(shot, h.camera.shotName());
        assertFalse(h.camera.blending(), "settled long before the telegraph");

        h.archonStarts(EnemyAction.SLASH);
        h.view.prompt = CameraHarness.parry();
        h.emit(new BattleEvent.PromptOpened(PromptKind.PARRY));
        Vector3f eye = null;
        for (int i = 0; i < 40; i++) {
            h.tick();
            assertEquals(shot, h.camera.shotName());
            assertFalse(h.camera.cutThisFrame());
            assertTrue(liveShot(h).promptSafe());
            if (eye == null) eye = new Vector3f(h.camera.frame().eye());
            assertEquals(eye, h.camera.frame().eye());
        }

        // Parried: stance and prompt drop at impact, the Archon's clip plays on. Still no cut, but a snap + shake.
        h.view.prompt = null;
        h.view.monk.statuses.clear();
        h.impact(CombatantId.ARCHON).emit(new BattleEvent.DamageDealt(CombatantId.MONK, 0f, DamageFlavor.PARRIED)).tick();
        assertEquals(shot, h.camera.shotName());
        assertTrue(h.camera.shakeTrauma() >= 0.4f);
        assertTrue(h.camera.frame().fovDeg() < liveShot(h).fovFrom() - 3f, "parry snap zoom");
        h.run(0.6f);
        assertEquals(shot, h.camera.shotName(), "guard shot holds to the end of the attack");
        h.archonFinishes().tick();
        assertCovers(h, Situation.IDLE);
    }

    @Test void aPromptOverAnUnsafeShotForcesOneCutToThePromptSafeShotThenFreezes() {
        CameraHarness h = new CameraHarness(11L);
        h.run(0.3f);
        h.monkStarts(BattleCommand.STRIKE).tick().run(0.2f);
        assertFalse(liveShot(h).promptSafe());
        h.view.prompt = CameraHarness.ring(0); // e.g. a Surge bonus hit with a ring
        h.tick();
        assertTrue(liveShot(h).promptSafe());
        assertTrue(h.camera.cutThisFrame());
        String shot = h.camera.shotName();
        for (int i = 0; i < 60; i++) {
            h.tick();
            assertEquals(shot, h.camera.shotName());
            assertFalse(h.camera.cutThisFrame());
        }
    }

    @Test void reactionGuardMidTelegraphReachesTheGuardShotByOneCutThenFreezes() {
        CameraHarness h = new CameraHarness(11L);
        h.run(0.3f);
        h.archonStarts(EnemyAction.OVERHEAD).tick().run(0.3f);
        assertCovers(h, Situation.ARCHON_MELEE);
        // Instant guard: no ActionStarted, only the status and the parry prompt.
        h.view.monk.statuses.add(new StatusView(BattleStatus.GUARDING, -1f, 1));
        h.view.prompt = CameraHarness.parry();
        h.emit(new BattleEvent.StatusApplied(CombatantId.MONK, BattleStatus.GUARDING, -1f));
        h.emit(new BattleEvent.PromptOpened(PromptKind.PARRY)).tick();
        assertCovers(h, Situation.GUARD);
        assertTrue(h.camera.cutThisFrame(), "one forced cut");
        assertFalse(h.camera.blending(), "a blend would still be moving while the player times the parry");
        assertTrue(liveShot(h).promptSafe());
        Vector3f eye = new Vector3f(h.camera.frame().eye());
        for (int i = 0; i < 40; i++) {
            h.tick();
            assertFalse(h.camera.cutThisFrame());
            assertEquals(eye, h.camera.frame().eye());
        }
    }

    @Test void shakeIsDampedWhileAPromptIsOpen() {
        CameraHarness open = new CameraHarness(13L);
        CameraHarness prompt = new CameraHarness(13L);
        for (CameraHarness h : List.of(open, prompt)) {
            h.run(0.2f);
            h.monkStarts(BattleCommand.FLURRY).tick().run(0.3f);
        }
        prompt.view.prompt = CameraHarness.ring(1);
        float freeMax = 0f;
        float promptMax = 0f;
        Vector3f rest = new Vector3f(open.camera.frame().eye());
        for (CameraHarness h : List.of(open, prompt)) {
            h.emit(new BattleEvent.DamageDealt(CombatantId.ARCHON, 60f, DamageFlavor.CRITICAL));
        }
        for (int i = 0; i < 20; i++) {
            freeMax = Math.max(freeMax, open.tick().camera.frame().eye().distance(rest));
            promptMax = Math.max(promptMax, prompt.tick().camera.frame().eye().distance(rest));
        }
        assertTrue(freeMax > 0.02f, "hits shake: " + freeMax);
        assertTrue(promptMax > 0f && promptMax < freeMax * 0.5f, promptMax + " vs " + freeMax);
    }

    // ---- focus combo -------------------------------------------------------------------------------

    private static PromptView.Combo combo(int index) {
        List<ComboDirection> sequence = List.of(ComboDirection.UP, ComboDirection.LEFT, ComboDirection.DOWN,
                ComboDirection.RIGHT, ComboDirection.UP, ComboDirection.DOWN, ComboDirection.LEFT);
        List<TimedGrade> results = new ArrayList<>();
        for (int i = 0; i < index; i++) results.add(TimedGrade.GOOD);
        return new PromptView.Combo(sequence, index, 0.1f, 0.9f, results);
    }

    @Test void focusComboCutsOncePerCorrectInputThroughTheSixAnglesAndAFlawlessRunGetsTheSlowMotionOrbit() {
        CameraHarness h = new CameraHarness(21L);
        h.run(0.3f);
        h.monkStarts(BattleCommand.FOCUS_COMBO).tick();
        assertEquals("COMBO_PUSH_IN", h.camera.shotName());
        h.run(1.0f);
        assertEquals("COMBO_PUSH_IN", h.camera.shotName(), "the push-in holds until the first input");

        List<String> angles = new ArrayList<>();
        for (ShotSequence s : LIBRARY.variants(Situation.COMBO_ANGLE)) angles.add(s.name());

        int correct = 0;
        for (int i = 0; i < 7; i++) {
            h.view.prompt = combo(i); // COMBO prompts do not freeze the director
            h.run(0.3f);
            String before = h.camera.shotName();
            if (i == 2) {
                h.emit(new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.MISS, i)).tick();
                assertEquals(before, h.camera.shotName(), "a miss earns no cut");
                assertFalse(h.camera.cutThisFrame());
                continue;
            }
            h.emit(new BattleEvent.PromptResolved(PromptKind.COMBO, i % 2 == 0 ? TimedGrade.PERFECT : TimedGrade.GOOD, i)).tick();
            assertTrue(h.camera.cutThisFrame(), "input " + i);
            assertEquals(angles.get(correct % angles.size()), h.camera.shotName());
            assertEquals(Situation.COMBO_ANGLE, h.camera.situation());
            correct++;
        }
        assertEquals(6, correct);
        assertEquals(angles.getLast(), h.camera.shotName());

        h.view.prompt = null;
        h.emit(new BattleEvent.ComboFinished(6, true)).tick();
        assertCovers(h, Situation.COMBO_FINISHER);
        assertEquals(0.4f, h.camera.timeScale(), 1.0e-6f);
        assertEquals(1f, h.camera.shakeTrauma(), 0.05f);
        float fovStart = liveShot(h).fovFrom();
        h.run(0.5f);
        assertEquals(0.4f, h.camera.timeScale(), 1.0e-6f);
        h.run(0.5f);
        assertEquals(1f, h.camera.timeScale(), "slow motion is brief");
        h.run(2f);
        assertTrue(h.camera.frame().fovDeg() < fovStart - 30f, "wide → narrow");

        h.monkFinishes().tick();
        assertCovers(h, Situation.IDLE);
        assertTrue(h.camera.cutThisFrame(), "coming back across the line is a cut, never a glide through the actors");
        assertEquals(1f, h.camera.timeScale());
    }

    @Test void anImperfectComboSkipsTheFinisher() {
        CameraHarness h = new CameraHarness(21L);
        h.run(0.3f);
        h.monkStarts(BattleCommand.FOCUS_COMBO).tick();
        h.emit(new BattleEvent.PromptResolved(PromptKind.COMBO, TimedGrade.GOOD, 0)).tick();
        String angle = h.camera.shotName();
        h.emit(new BattleEvent.ComboFinished(3, false)).tick();
        assertEquals(angle, h.camera.shotName());
        assertEquals(1f, h.camera.timeScale());
        h.monkFinishes().tick();
        assertCovers(h, Situation.IDLE);
    }

    @Test void stunningStrikeSlowsTheBattleClockForAThirdOfASecondAtImpact() {
        CameraHarness h = new CameraHarness(23L);
        h.run(0.3f);
        h.monkStarts(BattleCommand.STUNNING_STRIKE).tick();
        h.run(0.7f);
        assertEquals(1f, h.camera.timeScale(), "no slow motion before the blow");
        h.impact(CombatantId.MONK).tick();
        assertEquals(0.5f, h.camera.timeScale(), 1.0e-6f);
        h.run(0.25f);
        assertEquals(0.5f, h.camera.timeScale(), 1.0e-6f);
        h.run(0.1f);
        assertEquals(1f, h.camera.timeScale());
        h.monkFinishes().tick();
        assertEquals(1f, h.camera.timeScale());
    }

    @Test void interruptingASlowMotionShotRestoresNormalTime() {
        CameraHarness h = new CameraHarness(23L);
        h.run(0.3f);
        h.monkStarts(BattleCommand.STUNNING_STRIKE).tick();
        h.impact(CombatantId.MONK).tick();
        assertEquals(0.5f, h.camera.timeScale(), 1.0e-6f);
        h.view.phase = BattlePhase.RESULT;
        h.view.outcome = BattleOutcome.VICTORY;
        h.emit(new BattleEvent.Ended(BattleOutcome.VICTORY)).tick();
        assertEquals(1f, h.camera.timeScale());
    }

    // ---- idle --------------------------------------------------------------------------------------

    @Test void idleSoftCutsComeEverySixToTenSecondsAmongTheThreeWideAngles() {
        for (long seed = 0; seed < 3; seed++) {
            CameraHarness h = new CameraHarness(seed);
            h.tick();
            String shot = h.camera.shotName();
            List<String> seen = new ArrayList<>(List.of(shot));
            float sinceCut = 0f;
            int cutCount = 0;
            for (int i = 0; i < 60 * 80; i++) {
                h.tick();
                sinceCut += CameraHarness.DT;
                if (!h.camera.shotName().equals(shot)) {
                    assertTrue(sinceCut >= 6f - 0.05f && sinceCut <= 10f + 0.05f, "soft cut after " + sinceCut + " s");
                    assertTrue(h.camera.cutThisFrame());
                    shot = h.camera.shotName();
                    seen.add(shot);
                    sinceCut = 0f;
                    cutCount++;
                } else {
                    assertTrue(sinceCut <= 10.1f, "idle sat on one angle for " + sinceCut + " s");
                }
                assertTrue(shot.startsWith("WIDE"), shot);
                assertEquals(Situation.IDLE, h.camera.situation());
            }
            assertTrue(cutCount >= 7, "cuts: " + cutCount);
            assertEquals(3, seen.stream().distinct().count(), "all three wide angles get used: " + seen);
        }
    }

    // ---- static mode -------------------------------------------------------------------------------

    @Test void staticOnlyPinsWideWithNoCutsNoShakeAndNormalTime() {
        CameraHarness h = new CameraHarness(CameraHarness.realLayout(), 5L, true);
        assertTrue(h.camera.introFinished(), "no intro in reduced-motion mode");
        CameraFrame wide = h.camera.frame();
        assertEquals("WIDE", h.camera.shotName());

        h.view.phase = BattlePhase.INTRO;
        h.run(0.5f);
        h.view.phase = BattlePhase.RUNNING;
        h.view.commandWindowOpen = true;
        h.monkStarts(BattleCommand.STUNNING_STRIKE).tick();
        h.impact(CombatantId.MONK).emit(new BattleEvent.DamageDealt(CombatantId.ARCHON, 80f, DamageFlavor.CRITICAL)).tick();
        h.monkStarts(BattleCommand.FOCUS_COMBO).tick();
        h.emit(new BattleEvent.ComboFinished(6, true)).tick();
        h.run(12f);
        h.emit(new BattleEvent.Ended(BattleOutcome.VICTORY)).run(1f);

        for (int i = 0; i < h.frames.size(); i++) {
            assertEquals(wide, h.frames.get(i), "frame " + i);
            assertFalse(h.cuts.get(i));
        }
        assertEquals("WIDE", h.camera.shotName());
        assertEquals(0f, h.camera.shakeTrauma());
        assertEquals(1f, h.camera.timeScale());
        assertEquals(LIBRARY.wide().steps().getFirst().shot().fovFrom(), wide.fovDeg());
        assertEquals(0f, wide.rollDeg());
    }

    // ---- determinism + continuity ------------------------------------------------------------------

    /** A busy fight: intro, menu, strike with a crit, idle cuts, an Archon attack, a guard + parry. */
    private static final Consumer<CameraHarness> SCRIPT = h -> {
        h.view.phase = BattlePhase.INTRO;
        h.run(5f);
        h.view.phase = BattlePhase.RUNNING;
        h.run(7.5f);
        h.view.commandWindowOpen = true;
        h.emit(new BattleEvent.TurnReady(CombatantId.MONK)).run(1.2f);
        h.monkStarts(BattleCommand.STRIKE).run(0.5f);
        h.impact(CombatantId.MONK).emit(new BattleEvent.DamageDealt(CombatantId.ARCHON, 44f, DamageFlavor.CRITICAL)).run(0.6f);
        h.monkFinishes().run(9f);
        h.archonStarts(EnemyAction.FROST_CAST).run(1.1f);
        h.impact(CombatantId.ARCHON).emit(new BattleEvent.DamageDealt(CombatantId.MONK, 25f, DamageFlavor.NORMAL)).run(1f);
        h.archonFinishes().run(2f);
        h.view.commandWindowOpen = true;
        h.run(0.8f);
        h.monkStarts(BattleCommand.MEDITATE).run(2f);
        h.monkFinishes().run(1.5f);
    };

    @Test void identicalSeedAndInputsGiveIdenticalFramesAndAnotherSeedCanChooseDifferently() {
        CameraHarness a = new CameraHarness(1234L);
        CameraHarness b = new CameraHarness(1234L);
        SCRIPT.accept(a);
        SCRIPT.accept(b);
        assertEquals(a.frames.size(), b.frames.size());
        for (int i = 0; i < a.frames.size(); i++) {
            assertEquals(a.frames.get(i), b.frames.get(i), "frame " + i);
        }
        boolean anyDifferent = false;
        for (long seed = 0; seed < 6 && !anyDifferent; seed++) {
            CameraHarness c = new CameraHarness(seed);
            SCRIPT.accept(c);
            anyDifferent = !c.frames.equals(a.frames);
        }
        assertTrue(anyDifferent, "the seed drives variant choice");
    }

    @Test void blendsAreContinuousAndOnlyCutsJump() {
        CameraHarness h = new CameraHarness(99L);
        SCRIPT.accept(h);
        int cuts = 0;
        float worstBlendStep = 0f;
        for (int i = 1; i < h.frames.size(); i++) {
            float step = h.frames.get(i).eye().distance(h.frames.get(i - 1).eye());
            if (h.cuts.get(i)) {
                cuts++;
                continue;
            }
            worstBlendStep = Math.max(worstBlendStep, step);
            assertTrue(step < 1.0f, "frame " + i + " jumped " + step + " without a cut");
            assertTrue(Math.abs(h.frames.get(i).fovDeg() - h.frames.get(i - 1).fovDeg()) < 8f, "fov pop at frame " + i);
        }
        assertTrue(cuts >= 6, "the script contains real cuts: " + cuts);
        assertTrue(worstBlendStep > 0.05f, "and real blends: " + worstBlendStep);
    }

    // ---- occlusion ---------------------------------------------------------------------------------

    private static BattleStageLayout withExtraObstacle(float[] box) {
        BattleStageLayout real = CameraHarness.realLayout();
        List<float[]> obstacles = new ArrayList<>(real.obstacles());
        obstacles.add(box);
        return new BattleStageLayout(real.monkHome(), real.archonHome(), real.monkHeight(), real.archonHeight(),
                real.strikeDistance(), real.recoilDistance(), real.arenaRadius(), real.floorY(), obstacles,
                real.archonStrikeDistance());
    }

    /** World-space eye of a sequence's opening shot on the real stage. */
    private static Vector3f openingEye(String sequenceName) {
        CameraShot shot = LIBRARY.byName(sequenceName).steps().getFirst().shot();
        return ShotEvaluator.evaluate(shot, 0f, CameraHarness.realLayout(), StagePoses.HOME).eye();
    }

    /** A box enclosing every given point with {@code margin} to spare: geometry follows the authored shots. */
    private static float[] boxAround(float margin, Vector3f... points) {
        Vector3f lo = new Vector3f(points[0]);
        Vector3f hi = new Vector3f(points[0]);
        for (Vector3f p : points) {
            lo.min(p);
            hi.max(p);
        }
        return new float[]{lo.x - margin, lo.y - margin, lo.z - margin, hi.x + margin, hi.y + margin, hi.z + margin};
    }

    @Test void aBlockedVariantIsSkippedForTheNextOne() {
        // A block sitting exactly where TURN_OTS puts its eye (and nowhere near TURN_OTS_HIGH's).
        Vector3f blocked = openingEye("TURN_OTS");
        assertTrue(blocked.distance(openingEye("TURN_OTS_HIGH")) > 0.8f, "the variants must be distinct places");
        BattleStageLayout layout = withExtraObstacle(boxAround(0.25f, blocked));
        assertTrue(new ShotValidator(layout).usable(LIBRARY.byName("TURN_OTS_HIGH")));
        for (long seed = 0; seed < 8; seed++) {
            CameraHarness h = new CameraHarness(layout, seed, false);
            h.view.commandWindowOpen = true;
            h.tick();
            assertEquals("TURN_OTS_HIGH", h.camera.sequenceName(), "seed " + seed);
        }
    }

    @Test void whenEveryVariantIsBlockedTheDirectorFallsBackToWide() {
        // A slab swallowing every over-the-shoulder eye, wherever the staging currently puts them.
        List<Vector3f> eyes = new ArrayList<>();
        for (ShotSequence s : LIBRARY.variants(Situation.TURN_READY)) eyes.add(openingEye(s.name()));
        BattleStageLayout layout = withExtraObstacle(boxAround(0.3f, eyes.toArray(new Vector3f[0])));
        ShotValidator validator = new ShotValidator(layout);
        for (ShotSequence s : LIBRARY.variants(Situation.TURN_READY)) assertFalse(validator.usable(s), s.name());
        assertTrue(validator.usable(LIBRARY.wide()), "the slab must not take the fallback with it");
        for (long seed = 0; seed < 4; seed++) {
            CameraHarness h = new CameraHarness(layout, seed, false);
            h.view.commandWindowOpen = true;
            h.tick();
            assertEquals(Situation.TURN_READY, h.camera.situation());
            assertEquals("WIDE", h.camera.shotName(), "seed " + seed);
        }
    }

    // ---- robustness --------------------------------------------------------------------------------

    @Test void hostileInputsNeverProduceABadFrame() {
        CameraHarness h = new CameraHarness(3L);
        h.camera.update(null, 0.016f);
        h.camera.update(h.view, Float.NaN);
        h.camera.update(h.view, -1f);
        h.camera.update(h.view, 30f); // a long hitch
        h.view.monk.pose = new com.stonebreak.battle.api.ActorPose("x", 0f, Float.NaN, Float.NaN, 1f);
        h.monkStarts(BattleCommand.STRIKE).tick();
        h.view.monk.pose = new com.stonebreak.battle.api.ActorPose("x", 0f, 1f, 0f, 1f);
        h.run(0.5f);
        h.view.archon.pose = new com.stonebreak.battle.api.ActorPose("x", 0f, 1f, 1f, 0f);
        h.impact(CombatantId.MONK).run(0.5f);
        assertNotNull(h.camera.frame());
        assertFalse(h.camera.sequenceNames().isEmpty());
    }

    @Test void debugPinHoldsAnyLibrarySequenceUntilReleased() {
        CameraHarness h = new CameraHarness(3L);
        h.run(0.2f);
        h.camera.debugPinSequence("FROST_ORBIT");
        h.monkStarts(BattleCommand.STRIKE).run(0.3f);
        assertEquals("FROST_ORBIT", h.camera.sequenceName());
        h.camera.debugPinSequence(null);
        h.tick();
        assertCovers(h, Situation.IDLE); // events raised while pinned are deliberately not replayed
    }
}
