package com.stonebreak.battle.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stonebreak.battle.api.ActorPose;
import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battletest.BattleTestArena;
import com.stonebreak.rendering.models.entities.SbePoseSolver;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/** GL-free checks of where the two battle actors are drawn and which way they face. */
class ActorPlacementTest {

    private static final float EPS = 1.0e-4f;
    /** Both shipped models are authored facing −Z. */
    private static final Vector3f AUTHORED_FORWARD = new Vector3f(0f, 0f, -1f);

    private static BattleStageLayout arenaLayout() throws Exception {
        return BattleStageLayouts.fromArena(BattleTestArena.load());
    }

    /** Monk at the origin, Archon 10 blocks away at 37° off the +X axis, court at y = 4. */
    private static BattleStageLayout rotatedLayout() {
        double a = Math.toRadians(37);
        return new BattleStageLayout(new Vector3f(0f, 4f, 0f),
                new Vector3f((float) (10 * Math.cos(a)), 4f, (float) (10 * Math.sin(a))),
                1.8f, 2.72f, 2.2f, 0.35f, 48f, 4f, List.of());
    }

    private static ActorPose pose(float dash, float recoil, float presence) {
        return new ActorPose("idle", 0f, dash, recoil, presence);
    }

    /** Pushes the authored forward axis through the real SBE base transform for this placement. */
    private static Vector3f renderedForward(ActorPlacement.Placement at) {
        Matrix4f base = SbePoseSolver.baseMatrix(at.position(), at.yawDegrees(), new Vector3f(1f));
        return base.transformDirection(new Vector3f(AUTHORED_FORWARD)).normalize();
    }

    /** Shortest arc between two yaws, so 359.9999° and 0° compare equal. */
    private static float angularDistance(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    private static void assertVectorEquals(Vector3f expected, Vector3f actual, String what) {
        assertEquals(expected.x, actual.x, EPS, what + " x");
        assertEquals(expected.y, actual.y, EPS, what + " y");
        assertEquals(expected.z, actual.z, EPS, what + " z");
    }

    @Test
    void actorsFaceEachOtherInTheShippedArena() throws Exception {
        BattleStageLayout layout = arenaLayout();
        var monk = ActorPlacement.place(layout, CombatantId.MONK, ActorPose.IDLE, 0f, 1f, false);
        var archon = ActorPlacement.place(layout, CombatantId.ARCHON, ActorPose.IDLE, 0f, 1f, true);

        // Monk stands at +Z looking toward −Z; the Archon looks back toward +Z.
        assertVectorEquals(new Vector3f(0f, 0f, -1f), renderedForward(monk), "monk forward");
        assertVectorEquals(new Vector3f(0f, 0f, 1f), renderedForward(archon), "archon forward");
        // −Z-authored model facing −Z needs no turn; facing +Z needs a half turn.
        assertEquals(0f, angularDistance(monk.yawDegrees(), 0f), EPS);
        assertEquals(0f, angularDistance(archon.yawDegrees(), 180f), EPS);
    }

    @Test
    void actorsFaceEachOtherInARotatedLayout() {
        BattleStageLayout layout = rotatedLayout();
        for (CombatantId id : CombatantId.values()) {
            var at = ActorPlacement.place(layout, id, ActorPose.IDLE, 0f, 1f, false);
            Vector3f towardOpponent = layout.home(id.opponent()).sub(layout.home(id)).normalize();
            assertVectorEquals(towardOpponent, renderedForward(at), id + " forward");
            assertVectorEquals(layout.facing(id),
                    ActorPlacement.facingOf(at.yawDegrees(), ActorPlacement.modelYawOffsetDegrees(id)),
                    id + " facingOf");
            assertTrue(at.yawDegrees() >= 0f && at.yawDegrees() < 360f, "yaw is normalized");
        }
    }

    @Test
    void yawFollowsTheMobFrameworkConvention() {
        // atan2(dir.x, dir.z) + offset: a +Z-authored model faces +X at 90°, a −Z-authored one at 270°.
        assertEquals(90f, ActorPlacement.yawDegrees(new Vector3f(1f, 0f, 0f), 0f), EPS);
        assertEquals(270f, ActorPlacement.yawDegrees(new Vector3f(1f, 0f, 0f), 180f), EPS);
        assertEquals(0f, ActorPlacement.yawDegrees(new Vector3f(0f, 0f, 5f), 0f), EPS);
        // Vertical component and length of the direction do not matter.
        assertEquals(ActorPlacement.yawDegrees(new Vector3f(1f, 0f, 1f), 180f),
                ActorPlacement.yawDegrees(new Vector3f(3f, 9f, 3f), 180f), EPS);
    }

    @Test
    void feetStandOnTheCourtWhateverTheModelOrigin() throws Exception {
        BattleStageLayout layout = arenaLayout();
        float courtY = layout.monkHome().y;

        // Origin at the feet: drawn exactly at the ring.
        var originAtFeet = ActorPlacement.place(layout, CombatantId.MONK, ActorPose.IDLE, 0f, 1f, false);
        assertVectorEquals(layout.monkHome(), originAtFeet.position(), "origin-at-feet monk");

        // Origin 1.1 above the lowest vertex: the origin is lifted so that vertex lands on the court.
        var originAtHip = ActorPlacement.place(layout, CombatantId.ARCHON, ActorPose.IDLE, -1.1f, 1f, true);
        assertEquals(courtY + 1.1f, originAtHip.position().y, EPS);
        assertEquals(courtY, originAtHip.position().y + -1.1f, EPS, "lowest vertex on the court");

        // Scaled models scale the correction with them.
        var scaled = ActorPlacement.place(layout, CombatantId.ARCHON, ActorPose.IDLE, -1.1f, 2f, true);
        assertEquals(courtY + 2.2f, scaled.position().y, EPS);
    }

    @Test
    void presenceSinksTheArchonButNotTheMonk() throws Exception {
        BattleStageLayout layout = arenaLayout();
        float courtY = layout.archonHome().y;
        float[] presences = {1f, 0.75f, 0.5f, 0f};
        float previousY = Float.MAX_VALUE;
        for (float presence : presences) {
            var archon = ActorPlacement.place(layout, CombatantId.ARCHON, pose(0f, 0f, presence), 0f, 1f, true);
            assertEquals(courtY - (1f - presence) * layout.archonHeight(), archon.position().y, EPS);
            assertTrue(archon.position().y <= previousY, "sinks monotonically");
            previousY = archon.position().y;

            var monk = ActorPlacement.place(layout, CombatantId.MONK, pose(0f, 0f, presence), 0f, 1f, false);
            assertEquals(layout.monkHome().y, monk.position().y, EPS);
        }
        // Fully gone: the top of the head is level with the court.
        assertEquals(layout.archonHeight(), ActorPlacement.sinkDepth(0f, layout.archonHeight()), EPS);
        assertEquals(0f, ActorPlacement.sinkDepth(1f, layout.archonHeight()), EPS);
        // Out-of-range presence is clamped rather than launching the actor into the air.
        assertEquals(0f, ActorPlacement.sinkDepth(3f, 2f), EPS);
        assertEquals(2f, ActorPlacement.sinkDepth(-1f, 2f), EPS);
    }

    @Test
    void feetFollowDashAndRecoilAlongTheLineBetweenTheRings() throws Exception {
        for (BattleStageLayout layout : new BattleStageLayout[] {arenaLayout(), rotatedLayout()}) {
            float gap = layout.monkHome().distance(layout.archonHome());
            for (CombatantId id : CombatantId.values()) {
                Vector3f home = layout.home(id);
                Vector3f opponent = layout.home(id.opponent());

                // Each attacker stops at its OWN reach; the Archon also side-steps so its blade line
                // (down its right side) crosses the monk.
                float reach = layout.strikeDistance(id);
                float lateral = id == CombatantId.ARCHON ? layout.archonStrikeLateral() : 0f;
                var full = ActorPlacement.place(layout, id, pose(1f, 0f, 1f), 0f, 1f, false);
                assertEquals(Math.hypot(reach, lateral), full.position().distance(opponent), 1e-3,
                        id + " stops at its own reach from its opponent");

                var half = ActorPlacement.place(layout, id, pose(0.5f, 0f, 1f), 0f, 1f, false);
                assertEquals(Math.hypot((gap - reach) * 0.5f, lateral * 0.5f),
                        half.position().distance(home), 1e-3);

                var recoiled = ActorPlacement.place(layout, id, pose(0f, 1f, 1f), 0f, 1f, false);
                assertEquals(layout.recoilDistance(), recoiled.position().distance(home), EPS);
                assertEquals(gap + layout.recoilDistance(), recoiled.position().distance(opponent), EPS,
                        id + " recoils away from its opponent");

                // Moving along the line never changes which way the actor faces.
                var idle = ActorPlacement.place(layout, id, ActorPose.IDLE, 0f, 1f, false);
                assertEquals(idle.yawDegrees(), full.yawDegrees(), EPS);
                assertEquals(idle.yawDegrees(), recoiled.yawDegrees(), EPS);
                assertVectorEquals(home, idle.position(), id + " idle at home");
            }
        }
    }
}
