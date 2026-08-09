package com.stonebreak.mobs.goose;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.ai.nav.Steering;
import org.joml.Vector3f;

/**
 * Flying, as opposed to routing: how an airborne goose holds a heading, clears terrain and gets
 * itself unstuck.
 *
 * <p>Deliberately not a pathfinding domain. A migration crosses several hundred blocks of open sky
 * where the only obstacles are the occasional peak — searching a 3D graph for that would cost far
 * more than the look-ahead it replaces and produce the same straight line. What is worth having is
 * exactly what is here: a periodic probe of the corridor ahead that raises the cruise altitude over
 * hills, an arc around walls too tall to out-climb, and a recovery for a goose that flew into
 * something anyway.
 *
 * <p>Ground movement, by contrast, does route — see {@code PathAgent}. The split is between an
 * environment that is mostly empty and one that is mostly not.
 */
final class FlightSteering {

    // ─── Speeds ──────────────────────────────────────────────────────────────
    static final float MAX_FLY_SPEED = 9.0f;
    static final float CRUISE_FLY_SPEED = 6.0f;
    static final float CLIMB_SPEED = 4.5f;
    static final float DESCEND_SPEED = 3.5f;
    static final float FLIGHT_TURN_SPEED = 160.0f; // degrees/sec

    // ─── Terrain look-ahead ──────────────────────────────────────────────────
    private static final float TERRAIN_SCAN_INTERVAL = 0.5f;
    private static final float LOOKAHEAD_DISTANCE = 28.0f;
    private static final int LOOKAHEAD_SAMPLES = 3;
    private static final float PATH_CLEARANCE = 6.0f;
    private static final int CORRIDOR_SCAN_RANGE = 24;
    private static final float GO_AROUND_DEG = 25.0f;
    private static final float GO_AROUND_DURATION = 1.5f;

    // ─── Stuck recovery ──────────────────────────────────────────────────────
    private static final float STUCK_POP_THRESHOLD = 0.8f;
    private static final float STUCK_POP_SPEED = CLIMB_SPEED * 1.6f;

    private final Goose goose;
    private final Steering steering;

    private float cruiseAltitude;
    private float steerAltitude;
    private float terrainScanTimer;
    private float lateralBiasTimer;
    private int lateralBiasSign;
    private float stuckTimer;

    private final Vector3f heading = new Vector3f();
    private final Vector3f waypoint = new Vector3f();

    FlightSteering(Goose goose, Steering steering) {
        this.goose = goose;
        this.steering = steering;
    }

    void setCruiseAltitude(float altitude) {
        this.cruiseAltitude = altitude;
        this.steerAltitude = altitude;
    }

    float cruiseAltitude() {
        return cruiseAltitude;
    }

    /** Clears the per-flight state so a new flight does not inherit the last one's detours. */
    void reset() {
        terrainScanTimer = 0.0f;
        lateralBiasTimer = 0.0f;
        lateralBiasSign = 0;
        stuckTimer = 0.0f;
    }

    /**
     * Steers toward a 3D point at the given horizontal speed, matching its altitude with a clamped
     * vertical rate.
     */
    void steerTo(Vector3f target, float speed, float deltaTime) {
        Vector3f position = goose.getPosition();
        Vector3f velocity = goose.getVelocity();

        heading.set(target.x - position.x, 0.0f, target.z - position.z);
        if (heading.lengthSquared() > 0.01f) {
            heading.normalize();
            steering.faceDirection(heading, FLIGHT_TURN_SPEED, deltaTime);
            velocity.x = heading.x * speed;
            velocity.z = heading.z * speed;
        } else {
            velocity.x = 0.0f;
            velocity.z = 0.0f;
        }

        float dy = target.y - position.y;
        velocity.y = Math.max(-DESCEND_SPEED, Math.min(CLIMB_SPEED, dy * 2.0f));
        goose.setVelocity(velocity);
    }

    /**
     * Steers toward a destination like {@link #steerTo}, but periodically probes the terrain ahead
     * and raises the flight altitude to clear peaks — or arcs around walls it cannot out-climb.
     *
     * <p>Only a flock leader or a lone flyer does this; followers track the leader's slot, so the
     * avoidance path propagates to the whole formation for free.
     */
    void steerToWithAvoidance(Vector3f destination, float speed, float deltaTime) {
        terrainScanTimer -= deltaTime;
        lateralBiasTimer -= deltaTime;
        if (terrainScanTimer <= 0.0f) {
            terrainScanTimer = TERRAIN_SCAN_INTERVAL;
            scanAhead(destination, speed);
        }

        Vector3f position = goose.getPosition();
        waypoint.set(destination.x, steerAltitude, destination.z);
        if (lateralBiasTimer > 0.0f) {
            heading.set(destination.x - position.x, 0.0f, destination.z - position.z);
            if (heading.lengthSquared() > 0.01f) {
                heading.normalize();
                rotateY(heading, GO_AROUND_DEG * lateralBiasSign);
                waypoint.set(position.x + heading.x * LOOKAHEAD_DISTANCE,
                        steerAltitude,
                        position.z + heading.z * LOOKAHEAD_DISTANCE);
            }
        }
        steerTo(waypoint, speed, deltaTime);
    }

    /**
     * Cheap, scan-free recovery for an airborne goose that flew into a solid block. Flight collision
     * slides along walls rather than stopping, so a goose can end up pinned: it climbs the face
     * (walls are finite and cruise leaves plenty of headroom), arcs toward the clearer side, and if
     * still pinned pops straight over — or backs out when a ceiling blocks the climb.
     */
    void applyStuckRecovery(float deltaTime) {
        if (!goose.wasFlightBlockedHorizontally()) {
            stuckTimer = Math.max(0.0f, stuckTimer - deltaTime * 2.0f);
            return;
        }

        stuckTimer += deltaTime;
        Vector3f position = goose.getPosition();
        Vector3f velocity = goose.getVelocity();

        velocity.y = Math.max(velocity.y, CLIMB_SPEED);
        // Raise the steering altitude too, or the next periodic scan relaxes the climb away.
        steerAltitude = Math.max(steerAltitude, position.y + PATH_CLEARANCE);

        if (lateralBiasTimer <= 0.0f) {
            heading.set(velocity.x, 0.0f, velocity.z);
            if (heading.lengthSquared() > 0.01f) {
                armGoAround(position, heading.normalize());
            }
        }

        if (stuckTimer > STUCK_POP_THRESHOLD) {
            if (goose.wasFlightBlockedVertically()) {
                velocity.x = -velocity.x;
                velocity.z = -velocity.z;
            } else {
                velocity.y = STUCK_POP_SPEED;
            }
        }
        goose.setVelocity(velocity);
    }

    /**
     * Probes a few columns ahead, raising the flight altitude to clear the tallest peak found and
     * arming a side-step when the nearest one cannot be out-climbed before the goose reaches it.
     */
    private void scanAhead(Vector3f destination, float speed) {
        Vector3f position = goose.getPosition();
        heading.set(destination.x - position.x, 0.0f, destination.z - position.z);
        if (heading.lengthSquared() < 0.01f) {
            steerAltitude = cruiseAltitude;
            return;
        }
        heading.normalize();

        float needed = cruiseAltitude;
        float nearestPeak = Float.NEGATIVE_INFINITY;
        float nearestDistance = 0.0f;
        for (int i = 1; i <= LOOKAHEAD_SAMPLES; i++) {
            float distance = LOOKAHEAD_DISTANCE * i / LOOKAHEAD_SAMPLES;
            float peak = corridorPeakAt(position.x + heading.x * distance,
                    position.z + heading.z * distance, position.y);
            if (peak != Float.NEGATIVE_INFINITY) {
                needed = Math.max(needed, peak + PATH_CLEARANCE);
                if (nearestPeak == Float.NEGATIVE_INFINITY) {
                    nearestPeak = peak;
                    nearestDistance = distance;
                }
            }
        }
        steerAltitude = needed;

        if (nearestPeak != Float.NEGATIVE_INFINITY) {
            // Climb capacity over the remaining gap ≈ distance × (climb rate / forward speed).
            float climbNeeded = (nearestPeak + PATH_CLEARANCE) - position.y;
            float climbBudget = nearestDistance * (CLIMB_SPEED / Math.max(0.1f, speed));
            if (climbNeeded > climbBudget) {
                armGoAround(position, heading);
            }
        }
    }

    /** Probes one column to each side and arms a side-step toward the clearer one. */
    private void armGoAround(Vector3f position, Vector3f forward) {
        Vector3f left = rotateY(new Vector3f(forward), GO_AROUND_DEG);
        float leftPeak = corridorPeakAt(position.x + left.x * LOOKAHEAD_DISTANCE,
                position.z + left.z * LOOKAHEAD_DISTANCE, position.y);

        Vector3f right = rotateY(new Vector3f(forward), -GO_AROUND_DEG);
        float rightPeak = corridorPeakAt(position.x + right.x * LOOKAHEAD_DISTANCE,
                position.z + right.z * LOOKAHEAD_DISTANCE, position.y);

        // The lower peak is the better side (NEGATIVE_INFINITY means clear); ties favour left.
        lateralBiasSign = leftPeak <= rightPeak ? 1 : -1;
        lateralBiasTimer = GO_AROUND_DURATION;
    }

    /**
     * Top face of the highest solid block within the scan range of {@code currentY} at a column, or
     * {@link Float#NEGATIVE_INFINITY} when the corridor is clear.
     */
    private float corridorPeakAt(float x, float z, float currentY) {
        if (goose.getWorld() == null) {
            return Float.NEGATIVE_INFINITY;
        }
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int top = (int) Math.floor(currentY) + CORRIDOR_SCAN_RANGE;
        int bottom = (int) Math.floor(currentY) - CORRIDOR_SCAN_RANGE;
        for (int y = top; y >= bottom; y--) {
            BlockType block = goose.getWorld().getBlockAt(blockX, y, blockZ);
            if (block != null && block.isSolid()) {
                return y + 1.0f;
            }
        }
        return Float.NEGATIVE_INFINITY;
    }

    /** Rotates a horizontal direction about Y, in place. */
    private static Vector3f rotateY(Vector3f direction, float degrees) {
        double radians = Math.toRadians(degrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float x = direction.x * cos - direction.z * sin;
        float z = direction.x * sin + direction.z * cos;
        return direction.set(x, 0.0f, z);
    }
}
