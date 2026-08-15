package com.stonebreak.mobs.goose;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates a flock of geese flying in a V formation.
 *
 * <p>A flock owns an ordered list of members; the member at index 0 is the
 * <b>leader</b> and steers the whole formation toward a shared {@link #destination}.
 * Every other member is assigned a slot behind-and-beside the leader, alternating
 * left/right wings and stepping back + outward per rank, which produces the classic
 * migratory "V".
 *
 * <p><b>Slots sit on the leader's trail, not on its current heading.</b> The flock records where
 * the leader has been and places each rank that far back <em>along the path actually flown</em>,
 * with the wing offset taken perpendicular to the trail at that point. The difference only shows up
 * when the leader turns — which is precisely when it matters, because the leader turns to get round
 * a mountain. Offsetting from the leader's present position instead swings the whole formation
 * across the corner it just cut, and the outer wing into the rock the leader avoided. Rank altitude
 * comes from the trail too, so a wing follows the leader up over a ridge rather than being pinned
 * at the altitude the leader has already left.
 *
 * <p>The leader flies at {@link #getCruiseSpeed() cruise speed} — deliberately slower
 * than a goose's maximum flight speed — so trailing geese (and newcomers) can fly
 * faster to catch up and slot in. When a member leaves or dies, the list compacts and
 * the next member naturally becomes leader.
 *
 * <p>Not thread-safe; mutated only from the entity update tick (single-threaded).
 */
public class GooseFlock {

    /** Distance between successive V ranks, measured along the leader's trail. */
    private static final float BACK_SPACING = 2.2f;
    /** Sideways spread between successive V ranks. */
    private static final float SIDE_SPACING = 1.6f;

    /** How far the leader flies between trail samples. Finer than the tightest rank spacing. */
    private static final float TRAIL_SPACING = 0.75f;

    /**
     * Trail samples retained. The deepest slot of a full flock sits {@code 4 × BACK_SPACING} back,
     * so this holds several times the history any rank can ask for — enough that a turn stays in
     * the record until the last wingman has flown through it.
     */
    private static final int TRAIL_CAPACITY = 64;

    private final List<Goose> members = new ArrayList<>();
    private final Vector3f destination = new Vector3f();
    private final float cruiseSpeed;
    /** Once the flock begins its landing descent it is no longer joinable. One-way. */
    private boolean landing = false;

    /** Leader positions, newest last; a ring buffer of x,y,z triples. */
    private final float[] trail = new float[TRAIL_CAPACITY * 3];
    private int trailCount;
    private int trailNewest = -1;

    // Scratch for trail sampling — this runs for every wingman, every tick.
    private final Vector3f trailPoint = new Vector3f();
    private final Vector3f trailForward = new Vector3f();

    public GooseFlock(Goose leader, Vector3f destination, float cruiseSpeed) {
        this.cruiseSpeed = cruiseSpeed;
        this.destination.set(destination);
        members.add(leader);
    }

    /** Adds a goose to the back of the formation if not already a member. */
    public void join(Goose goose) {
        if (!members.contains(goose)) {
            members.add(goose);
        }
    }

    /**
     * Removes a goose from the formation. Index 0 (leader) compacts automatically — and when the
     * leader is the one leaving, its trail goes with it: the new leader has flown a different line,
     * and holding slots on a path nobody is on any more is worse than holding none.
     */
    public void leave(Goose goose) {
        boolean wasLeader = isLeader(goose);
        members.remove(goose);
        if (wasLeader) {
            clearTrail();
        }
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public int size() {
        return members.size();
    }

    /** The current leader (formation apex), or {@code null} if the flock is empty. */
    public Goose getLeader() {
        return members.isEmpty() ? null : members.get(0);
    }

    public boolean isLeader(Goose goose) {
        return getLeader() == goose;
    }

    public Vector3f getDestination() {
        return new Vector3f(destination);
    }

    public void setDestination(Vector3f dest) {
        this.destination.set(dest);
    }

    public float getCruiseSpeed() {
        return cruiseSpeed;
    }

    /** Whether the flock has begun its landing descent (and is thus no longer joinable). */
    public boolean isLanding() {
        return landing;
    }

    /** Marks the flock as landing — newcomers can no longer join from this point on. */
    public void markLanding() {
        this.landing = true;
    }

    // ── Leader trail ─────────────────────────────────────────────────────────

    /**
     * Records where the leader is, if it has moved far enough to be worth a new sample. Called once
     * per tick by the leader itself; sampling by distance rather than by time keeps the trail's
     * resolution independent of frame rate and of how fast the flock is flying.
     */
    public void recordLeaderTrail(Vector3f position) {
        if (trailCount > 0) {
            int newest = trailNewest * 3;
            float dx = position.x - trail[newest];
            float dy = position.y - trail[newest + 1];
            float dz = position.z - trail[newest + 2];
            if (dx * dx + dy * dy + dz * dz < TRAIL_SPACING * TRAIL_SPACING) {
                return;
            }
        }
        trailNewest = (trailNewest + 1) % TRAIL_CAPACITY;
        int slot = trailNewest * 3;
        trail[slot] = position.x;
        trail[slot + 1] = position.y;
        trail[slot + 2] = position.z;
        trailCount = Math.min(trailCount + 1, TRAIL_CAPACITY);
    }

    /** Forgets the recorded path — used when the flock changes leader. */
    public void clearTrail() {
        trailCount = 0;
        trailNewest = -1;
    }

    /** Trail samples currently held. Exposed for tests and the debug overlay. */
    public int trailLength() {
        return trailCount;
    }

    // ── Formation slots ──────────────────────────────────────────────────────

    /**
     * The point on the leader's trail a member's slot is offset from — its place in the formation's
     * spine, with no wing offset at all.
     *
     * <p>This is the fallback a wingman collapses onto when its own slot is blocked: the leader
     * flew through here moments ago, so it is guaranteed clear in a way no computed offset is.
     */
    public Vector3f spineTargetFor(Goose goose) {
        Goose leader = getLeader();
        if (leader == null) {
            return goose.getPosition();
        }
        int rank = members.indexOf(goose);
        if (rank <= 0) {
            return leader.getPosition();
        }
        sampleTrail(wingIndex(rank) * BACK_SPACING, leader);
        return new Vector3f(trailPoint);
    }

    /**
     * World-space target point for a member's slot in the V: its place on the leader's trail,
     * offset onto the left or right wing perpendicular to the trail's direction there.
     */
    public Vector3f slotTargetFor(Goose goose) {
        Goose leader = getLeader();
        if (leader == null) {
            return goose.getPosition();
        }

        int rank = members.indexOf(goose);
        if (rank <= 0) {
            // Leader steers toward the destination itself.
            return leader.getPosition();
        }

        int wing = wingIndex(rank);
        float sideSign = (rank % 2 == 1) ? -1f : 1f; // odd ranks → left wing, even → right
        sampleTrail(wing * BACK_SPACING, leader);

        // Right-hand perpendicular in the XZ plane of the trail's direction here.
        return new Vector3f(trailPoint)
                .fma(sideSign * wing * SIDE_SPACING,
                        new Vector3f(trailForward.z, 0.0f, -trailForward.x));
    }

    /** Rank 1,2 → wing 1; ranks 3,4 → wing 2; and so on. */
    private static int wingIndex(int rank) {
        return (rank + 1) / 2;
    }

    /**
     * Walks {@code backDistance} back along the recorded trail, writing the point there into
     * {@link #trailPoint} and the direction of travel at that point into {@link #trailForward}.
     *
     * <p>Falls back to a straight line behind the leader when the trail is shorter than asked for —
     * the first seconds of a flight, or just after a leader change. That is the old behaviour, and
     * it is the right one while there is no history to bend around.
     */
    private void sampleTrail(float backDistance, Goose leader) {
        Vector3f leaderPosition = leader.getPosition();

        if (trailCount >= 2) {
            float remaining = backDistance;
            for (int step = 0; step < trailCount - 1; step++) {
                int newer = index(step);
                int older = index(step + 1);
                float dx = trail[newer] - trail[older];
                float dy = trail[newer + 1] - trail[older + 1];
                float dz = trail[newer + 2] - trail[older + 2];
                float segment = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (segment < 1e-4f) {
                    continue;
                }
                if (remaining <= segment) {
                    float t = remaining / segment;
                    trailPoint.set(trail[newer] - dx * t,
                            trail[newer + 1] - dy * t,
                            trail[newer + 2] - dz * t);
                    setForward(dx, dz);
                    return;
                }
                remaining -= segment;
            }
            // The trail is shorter than the rank sits back: hold its far end rather than
            // extrapolating past terrain nobody has flown.
            int oldest = index(trailCount - 1);
            int nextOldest = index(Math.max(0, trailCount - 2));
            trailPoint.set(trail[oldest], trail[oldest + 1], trail[oldest + 2]);
            setForward(trail[nextOldest] - trail[oldest], trail[nextOldest + 2] - trail[oldest + 2]);
            return;
        }

        // No usable trail yet — fall back to the leader's own heading.
        fallbackForward(leader, leaderPosition);
        trailPoint.set(leaderPosition).fma(-backDistance, trailForward);
    }

    /** Direction of travel along a trail segment, flattened — wings spread horizontally. */
    private void setForward(float dx, float dz) {
        trailForward.set(dx, 0.0f, dz);
        if (trailForward.lengthSquared() < 1e-8f) {
            trailForward.set(0.0f, 0.0f, 1.0f);
        } else {
            trailForward.normalize();
        }
    }

    private void fallbackForward(Goose leader, Vector3f leaderPosition) {
        trailForward.set(leader.getVelocity());
        trailForward.y = 0.0f;
        if (trailForward.lengthSquared() < 0.0001f) {
            trailForward.set(destination).sub(leaderPosition);
            trailForward.y = 0.0f;
        }
        if (trailForward.lengthSquared() < 0.0001f) {
            trailForward.set(0.0f, 0.0f, 1.0f);
        }
        trailForward.normalize();
    }

    /** Offset of the {@code stepsBack}-th newest trail sample into {@link #trail}. */
    private int index(int stepsBack) {
        int slot = Math.floorMod(trailNewest - stepsBack, TRAIL_CAPACITY);
        return slot * 3;
    }
}
