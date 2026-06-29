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
 * <p>The leader flies at {@link #getCruiseSpeed() cruise speed} — deliberately slower
 * than a goose's maximum flight speed — so trailing geese (and newcomers) can fly
 * faster to catch up and slot in. When a member leaves or dies, the list compacts and
 * the next member naturally becomes leader.
 *
 * <p>Not thread-safe; mutated only from the entity update tick (single-threaded).
 */
public class GooseFlock {

    /** Distance between successive V ranks, measured along the leader's heading. */
    private static final float BACK_SPACING = 2.2f;
    /** Sideways spread between successive V ranks. */
    private static final float SIDE_SPACING = 1.6f;

    private final List<Goose> members = new ArrayList<>();
    private final Vector3f destination = new Vector3f();
    private final float cruiseSpeed;
    /** Once the flock begins its landing descent it is no longer joinable. One-way. */
    private boolean landing = false;

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

    /** Removes a goose from the formation. Index 0 (leader) compacts automatically. */
    public void leave(Goose goose) {
        members.remove(goose);
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

    /**
     * World-space target point for a member's slot in the V. The leader's slot is its
     * own position (it follows the destination directly); followers are placed behind
     * the leader along its heading, offset to alternating wings.
     */
    public Vector3f slotTargetFor(Goose goose) {
        Goose leader = getLeader();
        if (leader == null) {
            return goose.getPosition();
        }
        Vector3f leaderPos = leader.getPosition();

        int rank = members.indexOf(goose);
        if (rank <= 0) {
            // Leader steers toward the destination itself.
            return leaderPos;
        }

        // Heading: prefer the leader's actual travel direction; fall back to the
        // direction toward the destination if it is momentarily still.
        Vector3f forward = new Vector3f(leader.getVelocity());
        forward.y = 0;
        if (forward.lengthSquared() < 0.0001f) {
            forward.set(destination).sub(leaderPos);
            forward.y = 0;
        }
        if (forward.lengthSquared() < 0.0001f) {
            forward.set(0, 0, 1);
        }
        forward.normalize();

        // Right-hand perpendicular in the XZ plane.
        Vector3f right = new Vector3f(forward.z, 0, -forward.x);

        int wingIndex = (rank + 1) / 2;          // 1,1,2,2,3,3,...
        float sideSign = (rank % 2 == 1) ? -1f : 1f; // odd ranks → left wing, even → right

        Vector3f slot = new Vector3f(leaderPos)
                .fma(-wingIndex * BACK_SPACING, forward)
                .fma(sideSign * wingIndex * SIDE_SPACING, right);
        slot.y = leaderPos.y; // hold formation altitude
        return slot;
    }
}
