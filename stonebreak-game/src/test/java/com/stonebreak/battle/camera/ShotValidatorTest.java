package com.stonebreak.battle.camera;

import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.camera.CameraShot.Motion;
import com.stonebreak.battle.camera.CameraShot.Subject;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.stonebreak.battle.camera.CameraShot.shot;
import static org.junit.jupiter.api.Assertions.*;

/** The validator must actually reject bad shots, or the library test proves nothing. */
class ShotValidatorTest {

    private static final float[] PILLAR = {-3f, 0f, 2f, -2f, 4f, 3f};

    private static BattleStageLayout layout(float radius, float[]... boxes) {
        return new BattleStageLayout(new Vector3f(0f, 0.06f, 9f), new Vector3f(0f, 0.06f, -9f),
                1.8f, 2.72f, 2.2f, 0.35f, radius, 0.06f, List.of(boxes));
    }

    /** The shipped 7-block stand-off, without scenery: composition needs the actors close enough to share a frame. */
    private static BattleStageLayout closeLayout() {
        return new BattleStageLayout(new Vector3f(0f, 0.06f, 3.5f), new Vector3f(0f, 0.06f, -3.5f),
                1.8f, 2.72f, 1.35f, 0.45f, 48f, 0.06f, List.of(), 2.6f);
    }

    private static CameraShot staticShot(AnchoredPoint eye, AnchoredPoint target) {
        return shot("T", Motion.STATIC).eye(eye).target(target).subject(Subject.NONE).build();
    }

    private static AnchoredPoint mid(float r, float u, float f) {
        return AnchoredPoint.at(CameraAnchor.MIDPOINT, r, u, f);
    }

    private static String firstProblem(BattleStageLayout layout, CameraShot shot, boolean authoring) {
        List<ShotValidator.Issue> issues = new ShotValidator(layout).check(shot, authoring);
        return issues.isEmpty() ? null : issues.getFirst().problem();
    }

    @Test void cleanShotPasses() {
        assertNull(firstProblem(layout(48f, PILLAR), staticShot(mid(-6f, 3f, -6f), mid(0f, 1f, 4f)), true));
    }

    @Test void eyeInsideAnObstacleOrItsMarginIsRejected() {
        assertTrue(firstProblem(layout(48f, PILLAR), staticShot(mid(-2.5f, 1f, -2.5f), mid(0f, 1f, 4f)), false).contains("inside"));
        // 0.1 outside the box face is still inside the 0.2 margin
        assertTrue(firstProblem(layout(48f, PILLAR), staticShot(mid(-3.1f, 1f, -2.5f), mid(-9f, 1f, -2.5f)), false).contains("inside"));
    }

    @Test void blockedLineOfSightIsRejectedButLookingOverTheBoxIsFine() {
        BattleStageLayout l = layout(48f, PILLAR);
        assertTrue(firstProblem(l, staticShot(mid(-6f, 1f, -2.5f), mid(2f, 1f, -2.5f)), false).contains("blocked"));
        assertNull(firstProblem(l, staticShot(mid(-6f, 5f, -2.5f), mid(2f, 4.5f, -2.5f)), false));
    }

    @Test void floorAndArenaRadiusAreEnforced() {
        assertTrue(firstProblem(layout(48f), staticShot(mid(-4f, 0.2f, 0f), mid(0f, 1f, 0f)), false).contains("floor"));
        assertTrue(firstProblem(layout(10f), staticShot(mid(-12f, 3f, 0f), mid(0f, 1f, 0f)), false).contains("radius"));
    }

    @Test void oneEightyRuleAppliesOnlyToUnflaggedShotsAndOnlyWhenAuthoring() {
        BattleStageLayout l = layout(48f);
        CameraShot wrongSide = staticShot(mid(4f, 2f, 0f), mid(0f, 1f, 0f));
        assertTrue(firstProblem(l, wrongSide, true).contains("180"));
        assertNull(firstProblem(l, wrongSide, false));
        CameraShot flagged = shot("T", Motion.STATIC).eye(mid(4f, 2f, 0f)).target(mid(0f, 1f, 0f)).crossesLine().build();
        assertNull(firstProblem(l, flagged, true));
    }

    @Test void subjectMustBeInFrameAndNotHiddenBehindAnObstacle() {
        BattleStageLayout open = layout(48f);
        CameraShot lookingAway = shot("T", Motion.STATIC).eye(mid(-4f, 2f, 0f)).target(mid(-12f, 2f, 0f))
                .subject(Subject.MONK).build();
        assertNotNull(firstProblem(open, lookingAway, true));
        // A wall between the camera and the monk, below the eye→target line.
        BattleStageLayout walled = layout(48f, new float[]{-3f, 0f, 6f, -2f, 2.3f, 12f});
        CameraShot overWall = shot("T", Motion.STATIC).eye(mid(-6f, 3.2f, -9f)).target(mid(0f, 2.6f, -9f))
                .subject(Subject.MONK).establishing().build(); // framing only: composition has its own tests
        assertNull(firstProblem(open, overWall, true));
        assertTrue(firstProblem(walled, overWall, true).contains("hidden"));
    }

    @Test void followShotsAreAlsoCheckedAlongTheDash() {
        // Clear at home, but the box sits where the camera ends up once the monk has dashed.
        BattleStageLayout l = layout(48f, new float[]{-5f, 0f, -8f, -3f, 3f, -6f});
        CameraShot track = shot("T", Motion.TRACK).eye(AnchoredPoint.following(CameraAnchor.MONK, -4f, 1.4f, 0f))
                .target(AnchoredPoint.following(CameraAnchor.MONK, 0f, 1.1f, 0f)).build();
        List<ShotValidator.Issue> issues = new ShotValidator(l).check(track, false);
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().allMatch(i -> i.poses().startsWith("monk dash")));
        assertFalse(new ShotValidator(l).usable(ShotSequence.sequence("S").cut(track, ShotSequence.AdvanceOn.NEVER).build()));
    }

    // ---- actors as occluders ----------------------------------------------------------------------

    private static AnchoredPoint monk(float r, float u, float f) {
        return AnchoredPoint.at(CameraAnchor.MONK, r, u, f);
    }

    private static CameraShot.Builder on(Subject subject, AnchoredPoint eye, AnchoredPoint target) {
        return shot("T", Motion.STATIC).eye(eye).target(target).fov(56f).subject(subject);
    }

    @Test void aSubjectHiddenBehindTheOtherActorIsRejectedAtItsLivePosition() {
        BattleStageLayout l = layout(48f);
        // Dead behind the monk, looking down the line at the Archon: the monk's back fills the view.
        CameraShot through = on(Subject.ARCHON, monk(0f, 1.2f, -3f), monk(0f, 1.5f, 18f)).build();
        assertTrue(firstProblem(l, through, true).contains("ARCHON hidden behind MONK"), firstProblem(l, through, true));
        assertNull(firstProblem(l, through, false), "actor rules are authoring rules: the director's usable() ignores them");

        // Clean from the side while the Archon is home; once it has advanced to the monk it stands in the way.
        AnchoredPoint eye = monk(-0.4f, 1.3f, 4.2f);
        AnchoredPoint target = monk(0f, 1.2f, 0f);
        assertNull(firstProblem(l, on(Subject.MONK, eye, target).build(), true));
        List<ShotValidator.Issue> issues = new ShotValidator(l)
                .check(on(Subject.MONK, eye, target).during(CameraShot.Staging.ARCHON_ADVANCES).build(), true);
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().allMatch(i -> i.poses().startsWith("archon dash")), issues.toString());
    }

    @Test void theEyeMayNotSitInsideEitherActor() {
        BattleStageLayout l = layout(48f);
        CameraShot insideMonk = on(Subject.NONE, monk(-0.2f, 1.0f, 0.1f), monk(0f, 1f, 9f)).build();
        assertTrue(firstProblem(l, insideMonk, true).contains("eye inside MONK"));
        CameraShot insideArchon = on(Subject.NONE, monk(-0.5f, 2.0f, 18.4f), monk(-6f, 1f, 9f)).build();
        assertTrue(firstProblem(l, insideArchon, true).contains("eye inside ARCHON"));
        CameraShot aboveArchon = on(Subject.NONE, monk(-0.5f, 3.4f, 18.4f), monk(-6f, 1f, 9f)).build();
        assertNull(firstProblem(l, aboveArchon, true), "over its head is not inside it");
    }

    @Test void overTheShoulderIsFineUntilTheShoulderCoversTheSubject() {
        BattleStageLayout l = closeLayout();
        AnchoredPoint target = monk(0.3f, 1.0f, 5f);
        assertNull(firstProblem(l, on(Subject.BOTH, monk(-1.4f, 1.9f, -2.4f), target).build(), true));
        String covered = firstProblem(l, on(Subject.BOTH, monk(-0.1f, 1.5f, -2.4f), target).build(), true);
        assertTrue(covered.contains("ARCHON hidden behind MONK"), covered);
    }

    @Test void theOtherActorMayNotClutterTheForegroundOfASingleSubjectShot() {
        // Tight staging: the Archon (dashed) stands next to the monk, between him and a camera on its far side.
        BattleStageLayout l = layout(48f);
        CameraShot react = on(Subject.MONK, monk(-2.4f, 2.2f, 4.0f), monk(0f, 1.0f, 0f))
                .during(CameraShot.Staging.ARCHON_ADVANCES).build();
        List<ShotValidator.Issue> issues = new ShotValidator(l).check(react, true);
        assertTrue(issues.stream().anyMatch(i -> i.problem().contains("foreground")), issues.toString());
        // The same picture is legitimate once it is declared a two-shot.
        CameraShot twoShot = on(Subject.BOTH, monk(-5.5f, 1.2f, 1.6f), monk(0f, 1.3f, 1.6f))
                .during(CameraShot.Staging.ARCHON_ADVANCES).build();
        assertTrue(new ShotValidator(l).check(twoShot, true).stream().noneMatch(i -> i.problem().contains("foreground")));
    }

    // ---- composition ------------------------------------------------------------------------------

    @Test void aTwoShotThatLeavesTheActorsTinyIsRejected() {
        BattleStageLayout l = closeLayout();
        CameraShot far = shot("T", Motion.STATIC).eye(mid(-9f, 4.5f, -17f)).target(mid(0f, 1.6f, 2f)).subject(Subject.BOTH).build();
        assertTrue(firstProblem(l, far, true).contains("two-shot too far"), firstProblem(l, far, true));
        CameraShot composed = shot("T", Motion.STATIC).eye(mid(-8f, 1.4f, 0f)).target(mid(0f, 1.4f, 0f)).fov(50f)
                .subject(Subject.BOTH).build();
        assertNull(firstProblem(l, composed, true));
        assertNull(firstProblem(l, far, false), "composition is an authoring rule");
    }

    @Test void aSingleSubjectMustReadBetweenThirtyAndNinetyFivePercentOfTheFrame() {
        BattleStageLayout l = layout(48f);
        AnchoredPoint target = monk(0f, 1.0f, 0f);
        assertTrue(firstProblem(l, on(Subject.MONK, monk(-9f, 1.2f, 0f), target).build(), true).contains("too small"));
        // Chest and head still in frame, but the feet-to-head span overflows it.
        String tight = firstProblem(l, on(Subject.MONK, monk(-1.7f, 1.34f, 0f), monk(0f, 1.34f, 0f)).build(), true);
        assertTrue(tight.contains("too large"), tight);
        assertNull(firstProblem(l, on(Subject.MONK, monk(-3.2f, 1.2f, 0f), target).build(), true));
        // An establishing sweep is exempt from size, never from having its subject in frame.
        assertNull(firstProblem(l, on(Subject.MONK, monk(-9f, 1.2f, 0f), target).establishing().build(), true));
        assertNotNull(firstProblem(l, on(Subject.MONK, monk(-9f, 1.2f, 0f), monk(-20f, 1f, 0f)).establishing().build(), true));
    }

    @Test void aSubjectWhoseHipsSinkBehindTheHudIsRejected() {
        BattleStageLayout l = layout(48f);
        // Same eye, same distance: looking at his head pushes him down into the HUD, looking at his knees lifts him clear.
        String low = firstProblem(l, on(Subject.MONK, monk(-3.4f, 1.6f, 0f), monk(0f, 2.4f, 0f)).build(), true);
        assertTrue(low.contains("behind the HUD"), low);
        assertNull(firstProblem(l, on(Subject.MONK, monk(-3.4f, 1.6f, 0f), monk(0f, 0.8f, 0f)).build(), true));
    }

    @Test void projectedHeightIsTheFeetToHeadSpanAsAFractionOfTheFrame() {
        BattleStageLayout l = layout(48f);
        ShotValidator v = new ShotValidator(l);
        // Level camera 5 blocks away at 60°: the frame is 2·5·tan30° = 5.77 blocks tall there.
        CameraFrame level = new CameraFrame(new Vector3f(-5f, 0.96f, 9f), new Vector3f(0f, 0.96f, 9f), 0f, 60f);
        assertEquals(1.8f / 5.7735f, v.projectedHeight(level, com.stonebreak.battle.api.CombatantId.MONK, StagePoses.HOME), 0.01f);
        CameraFrame rolled = new CameraFrame(level.eye(), level.target(), 25f, 60f);
        assertEquals(v.projectedHeight(level, com.stonebreak.battle.api.CombatantId.MONK, StagePoses.HOME),
                v.projectedHeight(rolled, com.stonebreak.battle.api.CombatantId.MONK, StagePoses.HOME), 1.0e-5f);
        CameraFrame away = new CameraFrame(new Vector3f(-5f, 0.96f, 9f), new Vector3f(-10f, 0.96f, 9f), 0f, 60f);
        assertEquals(0f, v.projectedHeight(away, com.stonebreak.battle.api.CombatantId.MONK, StagePoses.HOME));
    }

    @Test void stagingDecidesWhichPosesAShotIsJudgedAgainst() {
        AnchoredPoint eye = mid(-6f, 2f, 0f);
        AnchoredPoint target = mid(0f, 1f, 0f);
        assertEquals(List.of("home"), List.copyOf(ShotValidator.poseSets(staticShot(eye, target)).keySet()));
        assertEquals(List.of("home", "archon dash 0.5", "archon dash 1.0"), List.copyOf(ShotValidator.poseSets(
                shot("T", Motion.STATIC).eye(eye).target(target).during(CameraShot.Staging.ARCHON_ADVANCES).build()).keySet()));
        assertEquals(List.of("monk dash 1.0"), List.copyOf(ShotValidator.poseSets(
                shot("T", Motion.STATIC).eye(eye).target(target).during(CameraShot.Staging.MONK_ENGAGED).build()).keySet()));
        // Unstated: whoever the anchors follow. Stated: the statement wins.
        CameraShot follow = shot("T", Motion.TRACK).eye(AnchoredPoint.following(CameraAnchor.MONK, -4f, 1.4f, 0f)).target(target).build();
        assertEquals(CameraShot.Staging.MONK_ADVANCES, follow.staging());
        CameraShot pinned = shot("T", Motion.TRACK).eye(AnchoredPoint.following(CameraAnchor.MONK, -4f, 1.4f, 0f)).target(target)
                .during(CameraShot.Staging.HOME).build();
        assertEquals(List.of("home"), List.copyOf(ShotValidator.poseSets(pinned).keySet()));
    }

    @Test void anImpactShotMayOpenTightAsLongAsItHasPulledBackBeforeTheAttackerLeaves() {
        BattleStageLayout l = closeLayout();
        AnchoredPoint tight = AnchoredPoint.following(CameraAnchor.MIDPOINT, -3.6f, 1.1f, 0f);
        AnchoredPoint pulledBack = AnchoredPoint.following(CameraAnchor.MIDPOINT, -5.6f, 1.2f, 0f);
        AnchoredPoint target = AnchoredPoint.following(CameraAnchor.MIDPOINT, 0f, 1.3f, 0f);
        CameraShot.Builder impact = shot("T", Motion.DOLLY).eye(tight, pulledBack).target(target).fov(50f, 58f)
                .duration(0.4f).subject(Subject.BOTH);
        // Judged as "the monk may be anywhere at any time", the tight opening loses the actors at home...
        List<ShotValidator.Issue> anywhere = new ShotValidator(l).check(impact.during(CameraShot.Staging.MONK_ADVANCES).build(), true);
        assertTrue(anywhere.stream().anyMatch(i -> i.poses().equals("home") && i.time() < 0.4f), anywhere.toString());
        // ...but he cannot be home before the shot has settled: only its end frame owes him a place.
        assertTrue(new ShotValidator(l).check(impact.during(CameraShot.Staging.MONK_STRIKES).build(), true).isEmpty());
        // A shot that never pulls back is still caught, on that end frame.
        List<ShotValidator.Issue> stuck = new ShotValidator(l)
                .check(impact.eye(tight, tight).fov(50f, 50f).during(CameraShot.Staging.MONK_STRIKES).build(), true);
        assertFalse(stuck.isEmpty());
        assertTrue(stuck.stream().allMatch(i -> i.time() == 0.4f && !i.poses().equals("monk dash 1.0")), stuck.toString());
    }

    @Test void aFullOrbitMayPassBehindAnActorForAnInstantButNotLingerThere() {
        BattleStageLayout l = closeLayout();
        AnchoredPoint centre = mid(0f, 1.4f, 0f);
        CameraShot.Builder orbit = shot("T", Motion.ORBIT).eye(mid(-7f, 1.4f, 0f)).target(centre).fov(60f)
                .duration(6f).easing(com.stonebreak.ui.startupIntro.tween.EasingType.Linear).crossesLine().subject(Subject.BOTH);
        assertTrue(new ShotValidator(l).check(orbit.sweep(360f).build(), true).isEmpty(),
                "two instants on the line out of thirteen samples");
        // A quarter turn that ends ON the line has no such excuse.
        List<ShotValidator.Issue> parked = new ShotValidator(l).check(orbit.sweep(90f).build(), true);
        assertTrue(parked.stream().anyMatch(i -> i.problem().contains("hidden behind")), parked.toString());
    }

    @Test void cylinderTestCoversGrazingVerticalAndOverheadSegments() {
        assertTrue(ShotValidator.segmentHitsCylinder(new Vector3f(-3f, 1f, 0f), new Vector3f(3f, 1f, 0f), 0f, 0f, 0.5f, 0f, 2f));
        assertFalse(ShotValidator.segmentHitsCylinder(new Vector3f(-3f, 1f, 0.6f), new Vector3f(3f, 1f, 0.6f), 0f, 0f, 0.5f, 0f, 2f), "passes beside");
        assertFalse(ShotValidator.segmentHitsCylinder(new Vector3f(-3f, 2.5f, 0f), new Vector3f(3f, 2.5f, 0f), 0f, 0f, 0.5f, 0f, 2f), "passes overhead");
        assertFalse(ShotValidator.segmentHitsCylinder(new Vector3f(-3f, 1f, 0f), new Vector3f(-1f, 1f, 0f), 0f, 0f, 0.5f, 0f, 2f), "stops short");
        assertTrue(ShotValidator.segmentHitsCylinder(new Vector3f(0.1f, 5f, 0f), new Vector3f(0.1f, 1f, 0f), 0f, 0f, 0.5f, 0f, 2f), "straight down into it");
        assertFalse(ShotValidator.segmentHitsCylinder(new Vector3f(2f, 5f, 0f), new Vector3f(2f, 1f, 0f), 0f, 0f, 0.5f, 0f, 2f));
        // Dives over the near rim and lands inside the footprint below the top: a hit.
        assertTrue(ShotValidator.segmentHitsCylinder(new Vector3f(-3f, 4f, 0f), new Vector3f(0f, 1f, 0f), 0f, 0f, 0.5f, 0f, 2f));
    }

    @Test void slabTestHandlesAxisParallelAndShortSegments() {
        float[] box = {0f, 0f, 0f, 1f, 1f, 1f};
        assertTrue(ShotValidator.segmentHitsBox(new Vector3f(-1f, 0.5f, 0.5f), new Vector3f(2f, 0.5f, 0.5f), box));
        assertFalse(ShotValidator.segmentHitsBox(new Vector3f(-1f, 1.5f, 0.5f), new Vector3f(2f, 1.5f, 0.5f), box));
        assertFalse(ShotValidator.segmentHitsBox(new Vector3f(-2f, 0.5f, 0.5f), new Vector3f(-1f, 0.5f, 0.5f), box), "stops short");
        assertTrue(ShotValidator.segmentHitsBox(new Vector3f(-1f, -1f, -1f), new Vector3f(2f, 2f, 2f), box));
    }
}
