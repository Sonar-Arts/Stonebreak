package com.stonebreak.battle.camera;

import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.camera.CameraShot.Motion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every authored shot, sampled over its duration against the REAL arena (home poses, plus mid-dash
 * and end-of-dash for whoever the shot's staging says is on the move): finite, inside the arena, above
 * the floor, outside every obstacle and both actors, clear eye→target line, 180° rule, subject in a
 * 16:9 / 70° frustum and not hidden behind scenery or the other actor, composed (two-shots close
 * enough to read, single subjects 30–95 % of the frame, no foreground clutter), FOV in range.
 */
class ShotLibraryValidationTest {

    private final BattleStageLayout layout = CameraHarness.realLayout();
    private final ShotValidator validator = new ShotValidator(layout);
    private final ShotLibrary library = ShotLibrary.standard();

    @Test void arenaFixtureCarriesTheRealObstacles() {
        assertTrue(layout.obstacles().size() >= 20, "walls, pillars, cover and posts expected, got " + layout.obstacles().size());
        assertTrue(ShotValidator.SAMPLES >= 12);
    }

    @Test void everyShotOfEverySequenceIsValidInTheRealArena() {
        List<String> problems = new ArrayList<>();
        for (ShotSequence sequence : library.all()) {
            for (ShotSequence.Step step : sequence.steps()) {
                for (ShotValidator.Issue issue : validator.check(step.shot(), true)) {
                    problems.add(sequence.name() + " :: " + issue);
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> problems.size() + " invalid samples:\n" + String.join("\n", problems));
    }

    @Test void everySituationHasAUsableVariantAndCommonOnesHaveSeveral() {
        for (Situation situation : Situation.values()) {
            List<ShotSequence> variants = library.variants(situation);
            assertFalse(variants.isEmpty(), situation + " has no coverage");
            assertTrue(variants.stream().anyMatch(validator::usable), situation + " has no usable variant");
            assertNotSame(library.wide(), variants.getFirst(), situation + " must be authored, not the fallback");
        }
        for (Situation common : new Situation[]{Situation.IDLE, Situation.TURN_READY, Situation.STRIKE, Situation.FLURRY,
                Situation.GUARD, Situation.ARCHON_MELEE, Situation.FROST_CAST}) {
            assertTrue(library.variants(common).size() >= 2, common + " needs 2-3 variants");
        }
        assertEquals(3, library.variants(Situation.IDLE).size(), "three wide angles");
        assertEquals(6, library.variants(Situation.COMBO_ANGLE).size(), "six combo angles");
    }

    @Test void compositionRulesAreLiveNotVacuous() {
        // The pre-refinement wide (authored for an 18-block stand-off) must be what the rules reject.
        CameraShot oldWide = CameraShot.shot("OLD_WIDE", Motion.STATIC)
                .eye(AnchoredPoint.at(CameraAnchor.MIDPOINT, -9f, 4.5f, -17f))
                .target(AnchoredPoint.at(CameraAnchor.MIDPOINT, 0f, 1.6f, 2f)).subject(CameraShot.Subject.BOTH).build();
        assertTrue(validator.check(oldWide, true).stream().anyMatch(i -> i.problem().contains("two-shot too far")));
        int twoShots = 0, singles = 0, advanced = 0;
        for (ShotSequence sequence : library.all()) {
            for (ShotSequence.Step step : sequence.steps()) {
                CameraShot shot = step.shot();
                assertNotEquals(CameraShot.Subject.NONE, shot.subject(), shot.name() + " must declare what it frames");
                if (shot.subject() == CameraShot.Subject.BOTH) twoShots++; else singles++;
                if (shot.staging() != CameraShot.Staging.HOME) advanced++;
            }
        }
        assertTrue(twoShots >= 15 && singles >= 10, twoShots + " two-shots, " + singles + " singles");
        assertTrue(advanced >= 15, "action shots are judged with the attacker advanced: " + advanced);
    }

    @Test void idleAnglesAreComposedTwoShots() {
        for (ShotSequence sequence : library.variants(Situation.IDLE)) {
            CameraShot shot = sequence.steps().getFirst().shot();
            assertEquals(CameraShot.Subject.BOTH, shot.subject(), shot.name());
            for (float t : new float[]{0f, shot.duration() * 0.5f, shot.duration()}) {
                CameraFrame frame = ShotEvaluator.evaluate(shot, t, layout, StagePoses.HOME);
                float monk = validator.projectedHeight(frame, com.stonebreak.battle.api.CombatantId.MONK, StagePoses.HOME);
                float archon = validator.projectedHeight(frame, com.stonebreak.battle.api.CombatantId.ARCHON, StagePoses.HOME);
                assertTrue(archon >= 0.25f && archon <= 0.5f, shot.name() + ": Archon " + archon);
                assertTrue(monk >= 0.15f && monk <= 0.45f, shot.name() + ": monk " + monk);
                assertTrue(frame.eye().y < 3f, shot.name() + " is a low angle, not a security camera");
                assertTrue(validator.lineSide(frame.eye()) < -3f, shot.name() + " sits well off-axis on the −X side");
            }
        }
    }

    @Test void shotsThatHoldWhileTheCommandMenuIsUpKeepTheirFarSubjectStandingAboveTheHud() {
        List<ShotSequence> menuShots = new ArrayList<>(List.of(library.wide()));
        for (Situation s : new Situation[]{Situation.IDLE, Situation.TURN_READY, Situation.GUARD}) menuShots.addAll(library.variants(s));
        for (ShotSequence sequence : menuShots) {
            CameraShot shot = sequence.steps().getFirst().shot();
            for (var poses : ShotValidator.poseSets(shot).values()) {
                for (float t : new float[]{0f, shot.duration()}) {
                    CameraFrame frame = ShotEvaluator.evaluate(shot, t, layout, poses);
                    var who = validator.hudSubject(shot, frame, poses);
                    float shins = validator.ndcHeight(frame, who, poses, 0.2f);
                    assertTrue(shins >= ShotValidator.HUD_LINE_NDC, shot.name() + ": " + who + "'s shins at ndc " + shins);
                    float head = validator.ndcHeight(frame, who, poses, 1f);
                    assertTrue(head <= 0.78f, shot.name() + ": " + who + "'s head under the enemy bar at ndc " + head);
                }
            }
        }
    }

    @Test void onlyTheIntroSweepAndTheDefeatPullBackAreEstablishing() {
        Set<String> establishing = new HashSet<>();
        for (ShotSequence sequence : library.all()) {
            for (ShotSequence.Step step : sequence.steps()) {
                if (step.shot().establishing()) establishing.add(step.shot().name());
            }
        }
        assertEquals(Set.of("INTRO_CRANE", "DEFEAT_PULL_UP"), establishing);
    }

    @Test void orbitsStayUnderNinetyDegreesPerSecondExceptTheFinisherAndOnlyTheComboRolls() {
        float dt = 1f / 120f;
        for (ShotSequence sequence : library.all()) {
            for (ShotSequence.Step step : sequence.steps()) {
                CameraShot shot = step.shot();
                if (!sequence.name().startsWith("COMBO")) {
                    assertEquals(0f, shot.rollFrom(), shot.name());
                    assertEquals(0f, shot.rollTo(), shot.name());
                }
                if (shot.name().equals("COMBO_FINISHER_ORBIT")) continue;
                float peak = 0f;
                float previous = Float.NaN;
                for (float t = 0f; t <= shot.duration(); t += dt) {
                    CameraFrame f = ShotEvaluator.evaluate(shot, t, layout, StagePoses.HOME);
                    float bearing = (float) Math.toDegrees(Math.atan2(f.eye().x - f.target().x, f.eye().z - f.target().z));
                    if (!Float.isNaN(previous)) {
                        float delta = Math.abs(bearing - previous);
                        if (delta > 180f) delta = 360f - delta;
                        peak = Math.max(peak, delta / dt);
                    }
                    previous = bearing;
                }
                assertTrue(peak <= 95f, shot.name() + " swings at " + peak + "°/s");
            }
        }
    }

    @Test void impactsLandOnATwoShotThatHoldsBothActorsThroughTheWholeAdvance() {
        for (Situation situation : new Situation[]{Situation.STRIKE, Situation.STUNNING_STRIKE, Situation.ARCHON_MELEE}) {
            for (ShotSequence sequence : library.variants(situation)) {
                assertEquals(ShotSequence.AdvanceOn.IMPACT, sequence.steps().getFirst().advanceOn(), sequence.name());
                CameraShot impact = sequence.steps().get(1).shot();
                assertEquals(CameraShot.Subject.BOTH, impact.subject(), impact.name() + ": blow and reaction in one frame");
                assertEquals(situation == Situation.ARCHON_MELEE ? CameraShot.Staging.ARCHON_STRIKES : CameraShot.Staging.MONK_STRIKES,
                        impact.staging(), impact.name());
                assertTrue(impact.duration() <= 0.4f, impact.name() + " must have pulled back before the attacker leaves (0.4 s glide-back delay)");
                CameraFrame open = ShotEvaluator.evaluate(impact, 0f, layout, StagePoses.dashing(
                        situation == Situation.ARCHON_MELEE ? com.stonebreak.battle.api.CombatantId.ARCHON : com.stonebreak.battle.api.CombatantId.MONK, 1f));
                assertTrue(open.fovDeg() <= 54f, impact.name() + " opens tight on the blow");
                assertEquals(ShotSequence.AdvanceOn.ACTION_FINISHED, sequence.steps().get(1).advanceOn());
            }
        }
        // The Archon's follow shot keeps it framed from its ring to the monk's face (validated in all three poses).
        for (ShotSequence sequence : library.variants(Situation.ARCHON_MELEE)) {
            CameraShot follow = sequence.steps().getFirst().shot();
            assertTrue(follow.follows(CameraAnchor.ARCHON), follow.name());
            assertEquals(3, ShotValidator.poseSets(follow).size());
        }
    }

    @Test void guardAndFlurryShotsAreJudgedWithTheAttackerAtArmsLength() {
        for (ShotSequence sequence : library.variants(Situation.GUARD)) {
            assertEquals(CameraShot.Staging.ARCHON_ADVANCES, sequence.steps().getFirst().shot().staging(), sequence.name());
        }
        for (ShotSequence sequence : library.variants(Situation.FLURRY)) {
            assertEquals(CameraShot.Staging.MONK_ENGAGED, sequence.steps().getFirst().shot().staging(), sequence.name());
        }
    }

    @Test void wideFallbackIsValidStaticAndPromptSafe() {
        assertTrue(validator.usable(library.wide()));
        CameraShot wide = library.wide().steps().getFirst().shot();
        assertEquals("WIDE", wide.name());
        assertEquals(Motion.STATIC, wide.motion());
        assertTrue(wide.promptSafe());
        assertTrue(validator.check(wide, true).isEmpty());
    }

    @Test void promptSituationsOpenOnAStaticPromptSafeShot() {
        for (Situation situation : new Situation[]{Situation.FLURRY, Situation.GUARD}) {
            for (ShotSequence sequence : library.variants(situation)) {
                CameraShot first = sequence.steps().getFirst().shot();
                assertTrue(first.promptSafe(), sequence.name() + " must be prompt-safe from its first frame");
                assertEquals(Motion.STATIC, first.motion(), sequence.name());
                assertFalse(first.followsPose(), sequence.name() + " must not swing with the dash");
                assertEquals(1, sequence.steps().size(), sequence.name() + " must hold one shot through every prompt");
            }
        }
    }

    @Test void promptSafeShotsBarelyMove() {
        for (ShotSequence sequence : library.all()) {
            for (ShotSequence.Step step : sequence.steps()) {
                CameraShot shot = step.shot();
                if (!shot.promptSafe()) continue;
                CameraFrame a = ShotEvaluator.evaluate(shot, 0f, layout, StagePoses.HOME);
                CameraFrame b = ShotEvaluator.evaluate(shot, shot.duration(), layout, StagePoses.HOME);
                float speed = a.eye().distance(b.eye()) / Math.max(shot.duration(), 1.0e-3f);
                assertTrue(speed < 0.35f, shot.name() + " moves " + speed + " blocks/s");
                assertEquals(a.fovDeg(), b.fovDeg(), 1.0e-4f, shot.name() + " must not zoom");
            }
        }
    }

    @Test void onlyFlaggedShotsCrossTheLineAndTheFlagIsUsedSparingly() {
        Set<String> crossing = new HashSet<>();
        for (ShotSequence sequence : library.all()) {
            for (ShotSequence.Step step : sequence.steps()) {
                if (step.shot().crossesLine()) crossing.add(sequence.name());
            }
        }
        for (String name : crossing) {
            assertTrue(name.startsWith("COMBO") || name.equals("INTRO") || name.equals("VICTORY"),
                    name + " is not one of the neutral / scripted exceptions");
        }
    }

    @Test void archonHeroShotOwnsTheNameCardWindowOfTheIntro() {
        ShotSequence intro = library.variants(Situation.INTRO).getFirst();
        float start = 0f;
        for (ShotSequence.Step step : intro.steps()) {
            float end = start + step.shot().duration();
            if (step.shot().name().equals("INTRO_ARCHON_HERO")) {
                assertTrue(start <= 1.7f + 1.0e-4f && end >= 3.2f - 1.0e-4f, "hero shot runs " + start + "–" + end + " s");
                assertEquals(CameraShot.Subject.ARCHON, step.shot().subject());
                return;
            }
            start = end;
        }
        fail("no INTRO_ARCHON_HERO");
    }

    @Test void victoryOrbitKeepsTheMonkInTheRightThirdClearOfTheCentredResultPanel() {
        CameraShot orbit = library.variants(Situation.VICTORY).getFirst().steps().getLast().shot();
        assertEquals("VICTORY_ORBIT", orbit.name());
        for (int i = 0; i <= 28; i++) {
            CameraFrame f = ShotEvaluator.evaluate(orbit, i * 0.5f, layout, StagePoses.HOME);
            org.joml.Vector4f chest = new org.joml.Vector4f(layout.bodyPoint(com.stonebreak.battle.api.CombatantId.MONK,
                    com.stonebreak.battle.api.ActorPose.IDLE, 0.6f), 1f).mul(new org.joml.Matrix4f()
                    .perspective((float) Math.toRadians(f.fovDeg()), 16f / 9f, 0.1f, 100f)
                    .lookAt(f.eye(), f.target(), new org.joml.Vector3f(0f, 1f, 0f)));
            float x = chest.x / chest.w;
            assertTrue(x >= 0.3f && x <= 0.55f, "monk at ndc x " + x + " after " + i * 0.5f + " s");
        }
    }

    @Test void introRunsAboutFourSecondsAndEndsOnWide() {
        ShotSequence intro = library.variants(Situation.INTRO).getFirst();
        float total = 0f;
        for (ShotSequence.Step step : intro.steps()) {
            assertEquals(ShotSequence.AdvanceOn.TIME, step.advanceOn());
            total += step.shot().duration();
        }
        assertTrue(total >= 3.5f && total <= 5f, "intro is " + total + " s");
        CameraShot last = intro.steps().getLast().shot();
        CameraShot wide = library.wide().steps().getFirst().shot();
        assertTrue(intro.steps().getLast().entry().blend());
        assertEquals(ShotEvaluator.evaluate(wide, 0f, layout, StagePoses.HOME).eye(),
                ShotEvaluator.evaluate(last, last.duration(), layout, StagePoses.HOME).eye());
    }

    @Test void sequenceNamesAreUniqueAndAuthoredNumbersAreSane() {
        Set<String> names = new HashSet<>();
        for (ShotSequence sequence : library.all()) {
            assertTrue(names.add(sequence.name()), "duplicate sequence " + sequence.name());
            assertSame(sequence, library.byName(sequence.name()));
            for (ShotSequence.Step step : sequence.steps()) {
                CameraShot shot = step.shot();
                assertTrue(shot.duration() > 0f, shot.name());
                assertTrue(shot.fovFrom() >= 30f && shot.fovFrom() <= 90f && shot.fovTo() >= 30f && shot.fovTo() <= 90f, shot.name());
                assertTrue(shot.timeScale() > 0f && shot.timeScale() <= 1f, shot.name());
                assertTrue(shot.timeScale() == 1f || shot.timeScaleSeconds() <= 1f, shot.name() + ": slow motion must be brief");
            }
        }
    }

    @Test void slowMotionBeatsAreAuthoredWhereThePlanAsksForThem() {
        for (ShotSequence sequence : library.variants(Situation.STUNNING_STRIKE)) {
            CameraShot impact = sequence.steps().get(1).shot();
            assertEquals(0.5f, impact.timeScale(), 1.0e-6f);
            assertEquals(0.3f, impact.timeScaleSeconds(), 1.0e-6f);
            assertEquals(ShotSequence.AdvanceOn.IMPACT, sequence.steps().getFirst().advanceOn());
        }
        CameraShot finisher = library.variants(Situation.COMBO_FINISHER).getFirst().steps().getFirst().shot();
        assertEquals(0.4f, finisher.timeScale(), 1.0e-6f);
        assertEquals(360f, Math.abs(finisher.sweepDeg()), 1.0e-6f);
        assertTrue(finisher.fovFrom() > finisher.fovTo(), "wide → narrow");
    }
}
