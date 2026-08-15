package com.stonebreak.mobs.goose;

import org.joml.Vector3f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a wingman is told to fly.
 *
 * <p>Every assertion here is really about one thing: a formation going round a mountain has to bend
 * with its leader instead of pivoting about it. Offsetting a slot from the leader's <em>current</em>
 * position and heading is fine in a straight line and wrong in exactly the situation the formation
 * exists to survive — the turn — because it swings the whole V across the corner the leader just
 * flew round, and the outer wing into whatever the leader was avoiding.
 *
 * <p>No world is needed: a slot is geometry over the leader's recorded path, and terrain only
 * enters afterwards when the slot is resolved for clearance.
 */
class GooseFormationTest {

    private static final float EPSILON = 1e-3f;

    private static Goose goose(float x, float y, float z) {
        return new Goose(null, new Vector3f(x, y, z));
    }

    /** A leader with a follower behind it, flying toward +X by default. */
    private static GooseFlock flockOfTwo(Goose leader, Goose follower) {
        GooseFlock flock = new GooseFlock(leader, new Vector3f(500, 64, 0), 6.0f);
        flock.join(follower);
        return flock;
    }

    /**
     * Records a straight leg of leader travel in one-block steps — comfortably above the flock's
     * own sampling threshold, so every step becomes a trail point and the arithmetic below is
     * exact rather than dependent on which ticks happened to land far enough apart.
     */
    private static void flyLeg(GooseFlock flock, Vector3f from, Vector3f to) {
        float length = new Vector3f(to).sub(from).length();
        int steps = Math.max(1, Math.round(length));
        Vector3f step = new Vector3f(to).sub(from).div(steps);
        Vector3f at = new Vector3f(from);
        for (int i = 0; i <= steps; i++) {
            flock.recordLeaderTrail(at);
            at.add(step);
        }
    }

    @Test
    @DisplayName("a rank sits behind along the path flown, not along the current heading")
    void ranksFollowTheTrailRoundACorner() {
        Goose leader = goose(20, 64, 2);
        Goose follower = goose(0, 64, 0);
        GooseFlock flock = flockOfTwo(leader, follower);

        // A right-angle turn: east along X to the corner at (20, 64, 0), then north along Z.
        flyLeg(flock, new Vector3f(0, 64, 0), new Vector3f(20, 64, 0));
        flyLeg(flock, new Vector3f(20, 64, 0), new Vector3f(20, 64, 2));

        // Rank 1 sits 2.2 back. Two of those blocks are on the northbound leg and the remaining
        // 0.2 is round the corner on the eastbound one.
        Vector3f spine = flock.spineTargetFor(follower);

        assertEquals(19.8f, spine.x, EPSILON, "the rank should be back round the corner, not short of it");
        assertEquals(0.0f, spine.z, EPSILON);
        assertEquals(64.0f, spine.y, EPSILON);
    }

    @Test
    @DisplayName("the wing offset is perpendicular to the trail at the rank, not to the leader")
    void wingOffsetFollowsTheTrailDirection() {
        Goose leader = goose(20, 64, 2);
        Goose follower = goose(0, 64, 0);
        GooseFlock flock = flockOfTwo(leader, follower);

        flyLeg(flock, new Vector3f(0, 64, 0), new Vector3f(20, 64, 0));
        flyLeg(flock, new Vector3f(20, 64, 0), new Vector3f(20, 64, 2));

        Vector3f spine = flock.spineTargetFor(follower);
        Vector3f slot = flock.slotTargetFor(follower);

        // At the rank's position the trail still runs east, so its wing spreads along Z — the
        // leader's own northbound heading does not enter into it.
        assertEquals(spine.x, slot.x, EPSILON);
        assertEquals(spine.y, slot.y, EPSILON);
        assertEquals(1.6f, Math.abs(slot.z - spine.z), EPSILON, "rank 1 sits one side-spacing off the spine");
    }

    @Test
    @DisplayName("a rank inherits the altitude the leader held there, not the one it holds now")
    void rankAltitudeComesFromTheTrail() {
        Goose leader = goose(10, 74, 0);
        Goose follower = goose(0, 64, 0);
        GooseFlock flock = flockOfTwo(leader, follower);

        // The leader climbs ten blocks over the last ten of travel — a ridge crossing.
        flyLeg(flock, new Vector3f(0, 64, 0), new Vector3f(10, 74, 0));

        Vector3f spine = flock.spineTargetFor(follower);

        assertTrue(spine.y < 74.0f, "a wingman behind a climbing leader is still below it");
        assertTrue(spine.y > 64.0f, "and is still on the climb, not back at the bottom of it");
    }

    @Test
    @DisplayName("deeper ranks sit further back along the trail")
    void deeperRanksSitFurtherBack() {
        Goose leader = goose(40, 64, 0);
        Goose second = goose(0, 64, 0);
        Goose third = goose(0, 64, 0);
        Goose fourth = goose(0, 64, 0);
        GooseFlock flock = flockOfTwo(leader, second);
        flock.join(third);
        flock.join(fourth);

        flyLeg(flock, new Vector3f(0, 64, 0), new Vector3f(40, 64, 0));

        // Ranks 1 and 2 share a wing index, so they sit at the same depth on opposite wings;
        // rank 3 opens the next wing and sits a full spacing further back.
        assertEquals(flock.spineTargetFor(second).x, flock.spineTargetFor(third).x, EPSILON);
        assertTrue(flock.spineTargetFor(fourth).x < flock.spineTargetFor(third).x,
                "the second wing trails the first");
    }

    @Test
    @DisplayName("with no trail yet, slots fall back to the leader's own heading")
    void freshFlockFallsBackToTheLeaderHeading() {
        Goose leader = goose(0, 64, 0);
        leader.setVelocity(new Vector3f(6, 0, 0));
        Goose follower = goose(0, 64, 0);
        GooseFlock flock = flockOfTwo(leader, follower);

        Vector3f spine = flock.spineTargetFor(follower);

        assertEquals(-2.2f, spine.x, EPSILON, "one rank behind a leader heading east");
        assertEquals(0.0f, spine.z, EPSILON);
    }

    @Test
    @DisplayName("the trail samples by distance flown, so a hovering leader does not fill it")
    void trailSamplesByDistanceNotByCall() {
        Goose leader = goose(0, 64, 0);
        Goose follower = goose(0, 64, 0);
        GooseFlock flock = flockOfTwo(leader, follower);

        Vector3f stationary = new Vector3f(0, 64, 0);
        for (int i = 0; i < 200; i++) {
            flock.recordLeaderTrail(stationary);
        }

        assertEquals(1, flock.trailLength(), "standing still is one point on a path, however long for");
    }

    @Test
    @DisplayName("a leader leaving takes its trail with it")
    void leaderChangeForgetsTheTrail() {
        Goose leader = goose(20, 64, 0);
        Goose follower = goose(0, 64, 0);
        GooseFlock flock = flockOfTwo(leader, follower);
        flyLeg(flock, new Vector3f(0, 64, 0), new Vector3f(20, 64, 0));
        assertTrue(flock.trailLength() > 1);

        flock.leave(leader);

        assertEquals(0, flock.trailLength(),
                "the new leader has flown a different line; holding slots on the old one is worse than none");
        assertTrue(flock.isLeader(follower));
    }

    @Test
    @DisplayName("a wingman leaving does not disturb the trail")
    void wingmanLeavingKeepsTheTrail() {
        Goose leader = goose(20, 64, 0);
        Goose follower = goose(0, 64, 0);
        GooseFlock flock = flockOfTwo(leader, follower);
        flyLeg(flock, new Vector3f(0, 64, 0), new Vector3f(20, 64, 0));
        int before = flock.trailLength();

        flock.leave(follower);

        assertEquals(before, flock.trailLength());
    }
}
