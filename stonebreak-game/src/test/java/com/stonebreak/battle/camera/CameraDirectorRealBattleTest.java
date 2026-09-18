package com.stonebreak.battle.camera;

import com.stonebreak.battle.BattleConfig;
import com.stonebreak.battle.BattleState;
import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.PromptKind;
import com.stonebreak.battle.api.PromptView;
import com.stonebreak.battle.api.TelegraphView;
import com.stonebreak.battle.api.TimedGrade;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The director against the REAL battle model, stepped the way the stage coordinator steps them
 * (model with the camera's time scale, then camera, intro hand-over). Everything here asserts event
 * ORDER and shot names, never absolute times: the model's timelines are tuned independently.
 */
class CameraDirectorRealBattleTest {

    private static final float DT = 1f / 60f;
    private static final ShotLibrary LIBRARY = ShotLibrary.standard();

    /** One stepped frame: what the model published and what the camera showed for it. */
    private record Frame(List<BattleEvent> events, String sequence, String shot, Situation situation, boolean cut,
                         boolean blending, float timeScale, Vector3f eye, boolean promptSafeRequired) {
        <T extends BattleEvent> T event(Class<T> type, Predicate<T> match) {
            for (BattleEvent e : events) {
                if (type.isInstance(e) && match.test(type.cast(e))) return type.cast(e);
            }
            return null;
        }

        boolean has(Class<? extends BattleEvent> type) {
            return event(type, e -> true) != null;
        }
    }

    /** Model + camera + a per-frame player. */
    private static final class Fight {
        final BattleState sim;
        final BattleCameraSystem camera;
        final List<Frame> frames = new ArrayList<>();
        Consumer<Fight> player = f -> { };

        Fight(BattleConfig config, long battleSeed) {
            sim = new BattleState(config, new Random(battleSeed));
            camera = new BattleCameraSystem(CameraHarness.realLayout(), 99L, false);
            camera.skipIntro();
        }

        Frame step() {
            player.accept(this);
            float scale = camera.timeScale();
            sim.update(DT * scale);
            camera.update(sim, DT);
            if (sim.phase() == BattlePhase.INTRO && camera.introFinished()) sim.introFinished();
            CameraFrame f = camera.frame();
            assertTrue(f.eye().isFinite() && f.target().isFinite(), "finite frame during " + camera.shotName());
            Frame frame = new Frame(List.copyOf(sim.frameEvents()), camera.sequenceName(), camera.shotName(),
                    camera.situation(), camera.cutThisFrame(), camera.blending(), scale, new Vector3f(f.eye()),
                    sim.promptSafeRequired());
            frames.add(frame);
            return frame;
        }

        /** Steps until a frame matches; fails the test when none does within {@code maxSeconds}. */
        Frame until(String what, float maxSeconds, Predicate<Frame> match) {
            for (int i = 0; i < Math.round(maxSeconds / DT); i++) {
                Frame frame = step();
                if (match.test(frame)) return frame;
            }
            return fail("never saw: " + what + " (last shot " + camera.shotName() + ", phase " + sim.phase() + ")");
        }

        Frame untilEvent(Class<? extends BattleEvent> type, float maxSeconds) {
            return until(type.getSimpleName(), maxSeconds, f -> f.has(type));
        }
    }

    // ---- players -----------------------------------------------------------------------------------

    /** Answers every timed prompt perfectly. */
    private static void answerPrompts(Fight f) {
        PromptView prompt = f.sim.prompt();
        if (prompt instanceof PromptView.Ring ring) {
            if (ring.elapsed() >= (ring.perfectStart() + ring.perfectEnd()) * 0.5f) f.sim.pressConfirm();
        } else if (prompt instanceof PromptView.Combo combo) {
            if (combo.stepElapsed() >= 0.2f && combo.index() < combo.sequence().size()) {
                f.sim.pressDirection(combo.sequence().get(combo.index()));
            }
        } else if (prompt instanceof PromptView.Parry) {
            TelegraphView telegraph = f.sim.telegraph();
            if (telegraph != null && telegraph.inParryWindow()) f.sim.pressConfirm();
        }
    }

    /** Builds Focus as fast as the rules allow while staying alive, then fires the Focus Combo. */
    private static void focusBuilder(Fight f) {
        answerPrompts(f);
        if (!f.sim.commandWindowOpen() || f.sim.monk().has(BattleStatus.GUARDING)) return;
        if (f.sim.availability(BattleCommand.FOCUS_COMBO).available()) {
            f.sim.submit(BattleCommand.FOCUS_COMBO);
        } else if (f.sim.monk().hpFraction() < 0.5f && f.sim.availability(BattleCommand.MEDITATE).available()) {
            f.sim.submit(BattleCommand.MEDITATE);
        } else if (f.sim.archon().atb() > 0.6f && f.sim.availability(BattleCommand.GUARD).available()) {
            f.sim.submit(BattleCommand.GUARD); // a parry is worth more Focus than a Flurry and costs no HP
        } else {
            f.sim.submit(BattleCommand.FLURRY);
        }
    }

    private static BattleConfig defaults() {
        return BattleConfig.defaults(180, 10);
    }

    private static boolean archonStarted(Frame f) {
        return f.event(BattleEvent.ActionStarted.class, a -> a.actor() == CombatantId.ARCHON) != null;
    }

    private static boolean finished(Frame f, CombatantId who) {
        return f.event(BattleEvent.ActionFinished.class, a -> a.actor() == who) != null;
    }

    private static boolean impact(Frame f, CombatantId who) {
        return f.event(BattleEvent.Impact.class, a -> a.actor() == who) != null;
    }

    private static CameraShot shotOf(Frame f) {
        for (ShotSequence.Step step : LIBRARY.byName(f.sequence()).steps()) {
            if (step.shot().name().equals(f.shot())) return step.shot();
        }
        throw new AssertionError("unknown shot " + f.shot());
    }

    private static boolean restful(Situation s) {
        return s == Situation.IDLE || s == Situation.TURN_READY;
    }

    // ---- Archon attacks ----------------------------------------------------------------------------

    @Test void archonAttacksStartOnTheAttackerCutOnTheBlowAndEndWithTheAction() {
        Fight fight = new Fight(defaults(), 7L);
        int melee = 0;
        int casts = 0;
        for (int attack = 0; attack < 4 && fight.sim.phase() != BattlePhase.RESULT; attack++) {
            Frame start = fight.until("Archon attack", 60f, CameraDirectorRealBattleTest::archonStarted);
            boolean cast = start.situation() == Situation.FROST_CAST;
            assertTrue(cast || start.situation() == Situation.ARCHON_MELEE, "covered as " + start.situation());
            assertTrue(start.cut(), "an attack opens on a cut");
            ShotSequence sequence = LIBRARY.byName(start.sequence());
            assertEquals(sequence.steps().getFirst().shot().name(), start.shot());
            assertEquals(CameraShot.Subject.ARCHON, shotOf(start).subject(), "starts on the attacker");

            Frame blow = fight.until("its impact", 10f, f -> {
                if (!impact(f, CombatantId.ARCHON)) {
                    assertEquals(start.shot(), f.shot(), "nothing cuts the windup before the blow");
                    assertFalse(f.cut());
                }
                return impact(f, CombatantId.ARCHON);
            });
            assertTrue(blow.cut(), "the cut lands on the Impact frame");
            assertEquals(sequence.steps().get(1).shot().name(), blow.shot());
            assertEquals(CameraShot.Subject.BOTH, shotOf(blow).subject(), "blow and reaction share the frame");

            Frame end = fight.until("its end", 10f, f -> {
                if (!finished(f, CombatantId.ARCHON) && f.events().stream().noneMatch(e -> e instanceof BattleEvent.Ended)) {
                    assertEquals(blow.shot(), f.shot(), "the impact shot holds to the end of the clip");
                }
                return finished(f, CombatantId.ARCHON) || fight.sim.phase() == BattlePhase.RESULT;
            });
            if (fight.sim.phase() == BattlePhase.RESULT) break;
            assertTrue(restful(end.situation()), "released with the ActionFinished frame, not later: " + end.situation());
            if (cast) casts++; else melee++;
        }
        assertTrue(melee + casts >= 2, "saw " + melee + " melee and " + casts + " casts");
    }

    @Test void archonGlideKeepsItFramedAndTheLiveImpactShotShowsBothActors() {
        Fight fight = new Fight(defaults(), 7L);
        ShotValidator validator = new ShotValidator(CameraHarness.realLayout());
        Frame start = fight.until("a melee attack", 120f, f -> archonStarted(f) && f.situation() == Situation.ARCHON_MELEE);
        float furthest = 0f;
        boolean sawBlow = false;
        for (int i = 0; i < 600; i++) {
            Frame f = fight.step();
            if (f.situation() != Situation.ARCHON_MELEE) break;
            StagePoses poses = StagePoses.of(fight.sim);
            furthest = Math.max(furthest, poses.archon().dashProgress());
            CameraFrame live = fight.camera.frame();
            float archon = validator.projectedHeight(live, CombatantId.ARCHON, poses);
            assertTrue(archon > 0.3f, f.shot() + ": Archon is " + archon + " of the frame at glide " + poses.archon().dashProgress());
            if (shotOf(f).subject() == CameraShot.Subject.BOTH) {
                sawBlow = true;
                assertTrue(validator.projectedHeight(live, CombatantId.MONK, poses) > 0.2f, f.shot() + " lost the monk");
            }
        }
        assertNotNull(start);
        assertTrue(furthest > 0.95f, "the Archon really did glide in: " + furthest);
        assertTrue(sawBlow);
    }

    @Test void aCommandQueuedDuringAnArchonActionStartsItsShotOnTheVeryFrameTheArchonFinishes() {
        Fight fight = new Fight(defaults(), 7L);
        boolean[] queued = {false};
        fight.player = f -> {
            boolean archonActing = f.sim.currentAction() != null && f.sim.currentAction().actor() == CombatantId.ARCHON;
            if (!queued[0] && archonActing && f.sim.telegraph() == null && f.sim.commandWindowOpen()) {
                queued[0] = f.sim.submit(BattleCommand.STRIKE);
            }
        };
        Frame handover = fight.until("the Archon finishing", 60f, f -> finished(f, CombatantId.ARCHON));
        assertTrue(queued[0], "the monk's gauge was full while the Archon swung");
        assertNotNull(handover.event(BattleEvent.ActionStarted.class, a -> a.command() == BattleCommand.STRIKE),
                "the model starts the queued command in the same update");
        assertEquals(Situation.STRIKE, handover.situation());
        assertTrue(handover.shot().startsWith("STRIKE_TRACK"), handover.shot());
        assertTrue(handover.cut());

        Frame blow = fight.until("the strike landing", 10f, f -> impact(f, CombatantId.MONK));
        assertTrue(blow.shot().startsWith("STRIKE_IMPACT"), blow.shot());
        assertTrue(blow.cut());
        Frame end = fight.until("the strike ending", 10f, f -> finished(f, CombatantId.MONK));
        assertTrue(restful(end.situation()), end.situation().toString());
        assertTrue(end.blending(), "back to the wide on a blend");
    }

    // ---- guard -------------------------------------------------------------------------------------

    @Test void reactionGuardMidTelegraphGetsOneForcedCutToThePromptSafeShotThenFreezes() {
        Fight fight = new Fight(defaults(), 7L);
        Frame start = fight.until("an Archon telegraph", 60f, CameraDirectorRealBattleTest::archonStarted);
        assertNotEquals(Situation.GUARD, start.situation());
        fight.step();
        fight.step();
        assertNotNull(fight.sim.telegraph(), "still winding up");
        assertTrue(fight.sim.submit(BattleCommand.GUARD), "a full gauge buys an instant guard");

        Frame guarded = fight.step();
        assertTrue(guarded.promptSafeRequired(), "the parry prompt opened with the guard");
        assertEquals(Situation.GUARD, guarded.situation());
        assertTrue(guarded.cut(), "one forced cut");
        assertFalse(guarded.blending());
        assertTrue(shotOf(guarded).promptSafe());

        int framesOpen = 0;
        while (fight.sim.promptSafeRequired() && framesOpen < 600) {
            Frame f = fight.step();
            if (!f.promptSafeRequired()) break;
            framesOpen++;
            assertFalse(f.cut(), "frozen while the prompt is open");
            assertEquals(guarded.shot(), f.shot());
            assertFalse(f.blending());
        }
        assertTrue(framesOpen > 5, "the prompt stayed open for a while: " + framesOpen);
        Frame end = fight.until("the attack ending", 10f, f -> {
            if (!finished(f, CombatantId.ARCHON)) assertEquals(guarded.shot(), f.shot(), "guard shot holds to the end of the attack");
            return finished(f, CombatantId.ARCHON);
        });
        assertTrue(restful(end.situation()) || end.situation() == Situation.GUARD, end.situation().toString());
    }

    @Test void aGuardRaisedInAdvanceIsAlreadyOnItsShotWhenTheTelegraphStartsAndAParryNeverCuts() {
        Fight fight = new Fight(defaults(), 7L);
        boolean[] guarding = {false};
        fight.player = f -> {
            answerPrompts(f);
            if (!guarding[0] && f.sim.commandWindowOpen()) guarding[0] = f.sim.submit(BattleCommand.GUARD);
        };
        Frame start = fight.until("the Archon attacking into the guard", 60f, CameraDirectorRealBattleTest::archonStarted);
        assertEquals(Situation.GUARD, start.situation());
        assertFalse(start.cut(), "held, not restarted");
        assertTrue(shotOf(start).promptSafe());
        Frame end = fight.until("the attack ending", 10f, f -> {
            assertFalse(f.cut() && !finished(f, CombatantId.ARCHON), "no cut from telegraph to recovery");
            return finished(f, CombatantId.ARCHON);
        });
        assertNotNull(end);
        assertTrue(fight.frames.stream().anyMatch(f -> f.event(BattleEvent.PromptResolved.class,
                p -> p.kind() == PromptKind.PARRY && p.grade() == TimedGrade.PERFECT) != null), "the bot parried");
    }

    // ---- monk commands -----------------------------------------------------------------------------

    @Test void martialSurgeGetsItsBeatThenTheSurgedStrikeCutsOnceOnItsFirstImpact() {
        Fight fight = new Fight(defaults(), 7L);
        fight.until("the first turn", 30f, f -> fight.sim.commandWindowOpen());
        assertTrue(fight.sim.submit(BattleCommand.MARTIAL_SURGE));
        Frame surge = fight.step();
        assertEquals(Situation.MARTIAL_SURGE, surge.situation(), "covered whether or not the model raises an ActionStarted for it");
        assertTrue(surge.cut());
        // It is released by its ActionFinished when there is one and by the orphan rule when there is not.
        Frame after = fight.until("the surge beat ending", 6f, f -> f.situation() != Situation.MARTIAL_SURGE);
        assertEquals(Situation.TURN_READY, after.situation(), "the gauge stayed full: back to the menu shot");

        fight.until("the window reopening", 6f, f -> fight.sim.commandWindowOpen());
        assertTrue(fight.sim.submit(BattleCommand.STRIKE));
        Frame strike = fight.until("the strike starting", 2f, f -> f.situation() == Situation.STRIKE);
        assertTrue(strike.shot().startsWith("STRIKE_TRACK"));
        int impacts = 0;
        int cutsAfterStart = 0;
        for (int i = 0; i < 900; i++) {
            Frame f = fight.step();
            if (impact(f, CombatantId.MONK)) impacts++;
            if (finished(f, CombatantId.MONK)) break;
            if (f.cut()) {
                cutsAfterStart++;
                assertTrue(impact(f, CombatantId.MONK), "the only cut inside the action is on a blow");
                assertTrue(f.shot().startsWith("STRIKE_IMPACT"), f.shot());
            }
        }
        assertTrue(impacts >= 2, "the surge added a hit: " + impacts);
        assertEquals(1, cutsAfterStart, "the bonus hit does not cut again");
    }

    @Test void everyTurnEndingCommandIsCoveredFromItsActionStartedToItsActionFinished() {
        BattleCommand[] plan = {BattleCommand.STRIKE, BattleCommand.FLURRY, BattleCommand.SWIFT_STEP,
                BattleCommand.MEDITATE, BattleCommand.STUNNING_STRIKE};
        Situation[] expected = {Situation.STRIKE, Situation.FLURRY, Situation.SWIFT_STEP, Situation.MEDITATE,
                Situation.STUNNING_STRIKE};
        Fight fight = new Fight(defaults(), 7L);
        fight.player = CameraDirectorRealBattleTest::answerPrompts;
        for (int i = 0; i < plan.length; i++) {
            BattleCommand command = plan[i];
            Situation situation = expected[i];
            fight.until("a turn with nothing else going on for " + command, 120f,
                    f -> fight.sim.commandWindowOpen() && fight.sim.phase() == BattlePhase.RUNNING
                            && fight.sim.availability(command).available());
            assertTrue(fight.sim.submit(command), command.toString());
            Frame start = fight.until(command + " starting", 2f, f -> f.event(BattleEvent.ActionStarted.class,
                    a -> a.command() == command) != null);
            assertEquals(situation, start.situation());
            boolean flurry = command == BattleCommand.FLURRY;
            Frame end = fight.until(command + " finishing", 20f, f -> {
                if (!finished(f, CombatantId.MONK)) {
                    assertEquals(situation, f.situation(), command + " lost its coverage mid-action");
                    if (f.promptSafeRequired()) {
                        assertTrue(shotOf(f).promptSafe(), f.shot() + " is live over a timing ring");
                        assertFalse(f.cut() && flurry, "no cut while a ring is open");
                    }
                }
                return finished(f, CombatantId.MONK);
            });
            assertNotEquals(situation, end.situation(), command + " released on its ActionFinished frame");
            assertEquals(1f, fight.camera.timeScale(), "normal time once " + command + " is over");
        }
    }

    // ---- focus combo -------------------------------------------------------------------------------

    /** Plays until the Focus Combo starts; returns the frame of its ActionStarted. */
    private static Frame playToTheCombo(Fight fight) {
        fight.player = CameraDirectorRealBattleTest::focusBuilder;
        return fight.until("a full Focus gauge and the combo", 900f, f -> f.event(BattleEvent.ActionStarted.class,
                a -> a.command() == BattleCommand.FOCUS_COMBO) != null);
    }

    @Test void focusComboCutsOncePerInputInAuthoredOrderAndAFlawlessStringEndsOnTheSlowMotionOrbit() {
        Fight fight = new Fight(defaults().withArchon(defaults().archon().withMaxHp(1.0e6f)), 7L);
        Frame start = playToTheCombo(fight);
        assertEquals(Situation.COMBO_WINDUP, start.situation());
        assertEquals("COMBO_PUSH_IN", start.shot());

        List<String> angles = new ArrayList<>();
        for (ShotSequence s : LIBRARY.variants(Situation.COMBO_ANGLE)) angles.add(s.name());
        List<String> seen = new ArrayList<>();
        Frame finisher = fight.until("the flawless finish", 60f, f -> {
            boolean input = f.event(BattleEvent.PromptResolved.class,
                    p -> p.kind() == PromptKind.COMBO && p.grade() != TimedGrade.MISS) != null;
            boolean done = f.has(BattleEvent.ComboFinished.class);
            if (input && !done) {
                assertTrue(f.cut(), "every correct input is a cut");
                assertEquals(Situation.COMBO_ANGLE, f.situation());
                seen.add(f.shot());
            } else if (!done) {
                assertFalse(f.cut() && !seen.isEmpty(), "and nothing else cuts the string");
                assertTrue(f.situation() == Situation.COMBO_WINDUP || f.situation() == Situation.COMBO_ANGLE);
            }
            return done;
        });
        assertTrue(finisher.event(BattleEvent.ComboFinished.class, BattleEvent.ComboFinished::flawless) != null, "the bot is flawless");
        assertFalse(seen.isEmpty());
        assertEquals(angles.subList(0, seen.size()), seen, "angles come in authored order");
        assertTrue(seen.size() >= angles.size() - 1, "one per input: " + seen);
        assertEquals(Situation.COMBO_FINISHER, finisher.situation());
        assertEquals("COMBO_FINISHER_ORBIT", finisher.shot());
        assertTrue(finisher.cut());
        assertTrue(fight.camera.timeScale() < 1f, "slow motion starts with the finisher");

        // The flourish plays out whatever the action under it does, then the camera goes home at normal speed.
        boolean sawNormalSpeedOrbit = false;
        boolean actionEnded = false;
        for (int i = 0; i < 600; i++) {
            Frame f = fight.step();
            actionEnded |= finished(f, CombatantId.MONK);
            if (f.situation() != Situation.COMBO_FINISHER) break;
            sawNormalSpeedOrbit |= fight.camera.timeScale() == 1f;
        }
        assertTrue(actionEnded, "the combo action finished");
        assertTrue(sawNormalSpeedOrbit, "slow motion is only the first beat of the orbit");
        assertNotEquals(Situation.Priority.ULTIMATE, fight.camera.situation().priority(), "released");
        assertEquals(1f, fight.camera.timeScale());
    }

    @Test void victoryInTheMiddleOfAComboGoesStraightToTheVictorySequenceWithoutAComboFinished() {
        // First pass: how much the Archon has taken by the time the combo starts (same seed, same fight).
        Fight probe = new Fight(defaults().withArchon(defaults().archon().withMaxHp(1.0e6f)), 7L);
        playToTheCombo(probe);
        float damageBeforeCombo = probe.sim.archon().maxHp() - probe.sim.archon().hp();
        assertTrue(damageBeforeCombo > 0f);

        // Second pass: it dies a blow or two into the string.
        Fight fight = new Fight(defaults().withArchon(defaults().archon().withMaxHp(damageBeforeCombo + 50f)), 7L);
        Frame start = playToTheCombo(fight);
        assertEquals(Situation.COMBO_WINDUP, start.situation());
        Frame ended = fight.untilEvent(BattleEvent.Ended.class, 60f);
        assertEquals(BattleOutcome.VICTORY, fight.sim.outcome());
        assertTrue(fight.frames.stream().noneMatch(f -> f.has(BattleEvent.ComboFinished.class)), "the string never finished");
        assertEquals(Situation.VICTORY, ended.situation(), "on the very frame the battle ends");
        assertEquals("VICTORY_ARCHON_FALLS", ended.shot());
        assertEquals(1f, fight.camera.timeScale());

        float trauma = 0f;
        for (int i = 0; i < Math.round(2.5f / DT); i++) {
            Frame f = fight.step();
            assertEquals("VICTORY_ARCHON_FALLS", f.shot(), "the collapse plays out in one shot");
            trauma = Math.max(trauma, fight.camera.shakeTrauma());
        }
        assertTrue(trauma > 0.4f, "the body hitting the floor shakes the camera: " + trauma);
        Frame orbit = fight.until("the hero orbit", 5f, f -> f.shot().equals("VICTORY_ORBIT"));
        assertEquals(Situation.VICTORY, orbit.situation());
        for (int i = 0; i < 600; i++) assertEquals("VICTORY_ORBIT", fight.step().shot(), "nothing pre-empts the result");
        StagePoses poses = StagePoses.of(fight.sim);
        assertEquals(0f, poses.monk().dashProgress(), 1.0e-3f, "the orbit is authored round a monk who went home to celebrate");
    }

    @Test void defeatPullsBackFromTheMonkAndHolds() {
        Fight fight = new Fight(BattleConfig.defaults(60, 10), 7L); // never acts: the Archon wins
        Frame ended = fight.untilEvent(BattleEvent.Ended.class, 300f);
        assertEquals(BattleOutcome.DEFEAT, fight.sim.outcome());
        assertEquals(Situation.DEFEAT, ended.situation());
        assertEquals("DEFEAT_PULL_UP", ended.shot());
        float startHeight = ended.eye().y;
        Frame later = null;
        for (int i = 0; i < Math.round(8f / DT); i++) {
            later = fight.step();
            assertEquals("DEFEAT_PULL_UP", later.shot());
            assertFalse(later.cut());
        }
        assertTrue(later.eye().y > startHeight + 3f, "a rising pull-back: " + startHeight + " → " + later.eye().y);
        Vector3f held = later.eye();
        assertEquals(held, fight.step().eye(), "and it holds");
    }
}
