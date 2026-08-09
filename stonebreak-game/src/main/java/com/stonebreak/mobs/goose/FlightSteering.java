package com.stonebreak.mobs.goose;

import com.stonebreak.mobs.entities.ai.nav.AirPathAgent;
import com.stonebreak.mobs.entities.ai.nav.AirProbe;
import com.stonebreak.mobs.entities.ai.nav.Steering;
import org.joml.Vector3f;

/**
 * Flying: how an airborne goose turns a route into motion, clears the terrain the route was too
 * coarse to see, and gets itself unstuck.
 *
 * <p>Two layers, and the split between them is the whole design:
 *
 * <ul>
 *   <li><b>The route</b> ({@link AirPathAgent}) decides <em>which way round the mountain</em>. It
 *       is a real A* search over coarse air cells, re-planned to a moving horizon, and it is the
 *       only thing here that can see an obstacle it would take ten seconds to fly past.</li>
 *   <li><b>The look-ahead</b> below decides <em>what to do about the tree</em>. It probes a few
 *       columns along the direction actually being flown and lifts the goose over anything the
 *       route's four-block cells rounded away, without ever pulling it below the route.</li>
 * </ul>
 *
 * <p>When there is no route — the first second of a flight, a saturated pathfinder, a search that
 * came back empty — the look-ahead is also the whole avoidance, arcing around walls it cannot
 * out-climb exactly as it did before routing existed. That fallback is why a goose never freezes
 * waiting for a search.
 *
 * <p>Ground movement routes too, through a different domain — see {@code PathAgent}. The split
 * between them is a graph of surfaces versus a graph of free space.
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
    private static final float GO_AROUND_DEG = 25.0f;
    private static final float GO_AROUND_DURATION = 1.5f;

    // ─── Stuck recovery ──────────────────────────────────────────────────────
    private static final float STUCK_POP_THRESHOLD = 0.8f;
    private static final float STUCK_POP_SPEED = CLIMB_SPEED * 1.6f;

    // ─── Formation slots ─────────────────────────────────────────────────────
    /** Airspace a wingman needs to hold its slot without clipping anything. */
    private static final float SLOT_CLEARANCE = 1.5f;

    /**
     * How far a blocked slot is pulled in toward the leader's own line, in order. The V narrows
     * into an echelon and then into a column as the corridor tightens, which is the shape a
     * formation has to take to get through a pass at all.
     */
    private static final float[] SLOT_COMPRESSION = {0.6f, 0.3f, 0.0f};

    /** Lifts tried after compression fails — over a ridge the wing rides above the spine. */
    private static final float[] SLOT_LIFTS = {2.0f, 4.0f, 7.0f};

    private final Goose goose;
    private final Steering steering;
    private final AirPathAgent route;

    private float cruiseAltitude;
    private float steerAltitude;
    /**
     * Altitude the scanned terrain demands regardless of cruise, or {@link Float#NEGATIVE_INFINITY}
     * when the corridor ahead is clear. Kept separate from {@link #steerAltitude} so that a route
     * deliberately dipping through a pass is never yanked back up to cruise, while still being
     * floored by the ridge the route's coarse cells could not see.
     */
    private float terrainFloor = Float.NEGATIVE_INFINITY;
    private float terrainScanTimer;
    private float lateralBiasTimer;
    private int lateralBiasSign;
    private float stuckTimer;

    private final Vector3f heading = new Vector3f();
    private final Vector3f waypoint = new Vector3f();
    private final Vector3f slotScratch = new Vector3f();

    FlightSteering(Goose goose, Steering steering) {
        this.goose = goose;
        this.steering = steering;
        this.route = new AirPathAgent(goose);
    }

    void setCruiseAltitude(float altitude) {
        this.cruiseAltitude = altitude;
        this.steerAltitude = altitude;
        route.setCruiseAltitude(altitude);
    }

    float cruiseAltitude() {
        return cruiseAltitude;
    }

    /** The planned route, for the debug overlay. */
    AirPathAgent route() {
        return route;
    }

    /** Clears the per-flight state so a new flight does not inherit the last one's detours. */
    void reset() {
        terrainScanTimer = 0.0f;
        lateralBiasTimer = 0.0f;
        lateralBiasSign = 0;
        stuckTimer = 0.0f;
        terrainFloor = Float.NEGATIVE_INFINITY;
        route.stop();
    }

    /** Releases the route when the goose lands. */
    void releaseRoute() {
        route.stop();
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
     * Advances the route plan toward {@code destination} and writes the point to fly at into
     * {@code out} — the next waypoint of a planned route, or the bearing to the destination while
     * one is being searched for.
     *
     * <p>Called once per tick by whichever phase owns the goose, so takeoff climbs along the route
     * rather than straight at a ridge it is about to have to go round.
     */
    Vector3f routeTarget(Vector3f destination, float deltaTime, Vector3f out) {
        // Plan at the altitude the goose actually needs, not the one it set out at. Cruise is fixed
        // at takeoff and stays an absolute Y for the whole flight, so over rising ground it can sit
        // below the terrain — and a route planned at a buried altitude spends its search snapping
        // the goal out of a hillside instead of finding the way past it.
        route.setCruiseAltitude(terrainFloor == Float.NEGATIVE_INFINITY
                ? cruiseAltitude
                : Math.max(cruiseAltitude, terrainFloor));
        route.moveTo(destination);
        route.tick(deltaTime);
        return route.steerTarget(out);
    }

    /**
     * Cruises toward a destination along the planned route, with the terrain look-ahead layered
     * underneath it.
     *
     * <p>Only a flock leader or a lone flyer does this; followers hold slots on the leader's trail,
     * so the whole formation inherits the route without every goose paying for a search.
     */
    void steerAlongRoute(Vector3f destination, float speed, float deltaTime) {
        routeTarget(destination, deltaTime, waypoint);

        terrainScanTimer -= deltaTime;
        lateralBiasTimer -= deltaTime;
        boolean routing = route.isFollowing();
        if (terrainScanTimer <= 0.0f) {
            terrainScanTimer = TERRAIN_SCAN_INTERVAL;
            // Scan toward where the goose is actually going, not toward the far destination — during
            // a detour those are different directions, and scanning the wrong one is scanning
            // nothing.
            scanAhead(waypoint, speed, routing);
        }

        Vector3f position = goose.getPosition();
        if (routing) {
            // The route owns the line; the scan only ever raises the floor under it.
            if (terrainFloor != Float.NEGATIVE_INFINITY) {
                waypoint.y = Math.max(waypoint.y, terrainFloor);
            }
        } else {
            waypoint.y = steerAltitude;
            if (lateralBiasTimer > 0.0f) {
                heading.set(waypoint.x - position.x, 0.0f, waypoint.z - position.z);
                if (heading.lengthSquared() > 0.01f) {
                    heading.normalize();
                    rotateY(heading, GO_AROUND_DEG * lateralBiasSign);
                    waypoint.set(position.x + heading.x * LOOKAHEAD_DISTANCE,
                            steerAltitude,
                            position.z + heading.z * LOOKAHEAD_DISTANCE);
                }
            }
        }
        steerTo(waypoint, speed, deltaTime);
    }

    /**
     * Resolves a wingman's formation slot against the terrain: the slot itself when the air there
     * is clear, otherwise the formation compressed toward the leader's own line, otherwise the same
     * line flown higher.
     *
     * <p>This is what lets a V get through a mountain pass. The leader's line is known-good — it is
     * being flown — so collapsing toward it is always an improvement, and a formation that arrives
     * at a gap as a V and leaves it as a column has done exactly the right thing.
     *
     * @param slot  the wingman's ideal position in the formation
     * @param spine the point on the leader's trail the slot is offset from
     * @return a point to fly at; never the inside of a hillside if any of the candidates is clear
     */
    Vector3f resolveFormationSlot(Vector3f slot, Vector3f spine) {
        if (isSlotClear(slot)) {
            return slot;
        }

        for (float keep : SLOT_COMPRESSION) {
            slotScratch.set(spine).lerp(slot, keep);
            if (isSlotClear(slotScratch)) {
                return slotScratch;
            }
        }
        for (float lift : SLOT_LIFTS) {
            slotScratch.set(spine.x, spine.y + lift, spine.z);
            if (isSlotClear(slotScratch)) {
                return slotScratch;
            }
        }
        // Nothing clear anywhere near: fly the spine regardless. It is where the leader is, and the
        // leader is not inside a mountain.
        return slotScratch.set(spine);
    }

    /**
     * Cheap, scan-free recovery for an airborne goose that flew into a solid block. Flight collision
     * slides along walls rather than stopping, so a goose can end up pinned: it climbs the face
     * (walls are finite and cruise leaves plenty of headroom), arcs toward the clearer side, and if
     * still pinned pops straight over — or backs out when a ceiling blocks the climb.
     *
     * <p>Hitting something is also proof the route was wrong about this bit of sky, so the first
     * frame of contact throws the route away and asks for a new one.
     */
    void applyStuckRecovery(float deltaTime) {
        if (!goose.wasFlightBlockedHorizontally()) {
            stuckTimer = Math.max(0.0f, stuckTimer - deltaTime * 2.0f);
            return;
        }

        if (stuckTimer == 0.0f) {
            route.replan();
        }
        stuckTimer += deltaTime;
        Vector3f position = goose.getPosition();
        Vector3f velocity = goose.getVelocity();

        velocity.y = Math.max(velocity.y, CLIMB_SPEED);
        // Raise both altitude sources, or the next periodic scan relaxes the climb away.
        steerAltitude = Math.max(steerAltitude, position.y + PATH_CLEARANCE);
        terrainFloor = Math.max(terrainFloor, position.y + PATH_CLEARANCE);

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
     * Probes a few columns along the direction of travel, recording the altitude the terrain there
     * demands and — when there is no route to trust — arming a side-step around anything that
     * cannot be out-climbed before the goose reaches it.
     */
    private void scanAhead(Vector3f target, float speed, boolean routing) {
        Vector3f position = goose.getPosition();
        heading.set(target.x - position.x, 0.0f, target.z - position.z);
        if (heading.lengthSquared() < 0.01f) {
            terrainFloor = Float.NEGATIVE_INFINITY;
            steerAltitude = cruiseAltitude;
            return;
        }
        heading.normalize();

        float floor = Float.NEGATIVE_INFINITY;
        float nearestPeak = Float.NEGATIVE_INFINITY;
        float nearestDistance = 0.0f;
        for (int i = 1; i <= LOOKAHEAD_SAMPLES; i++) {
            float distance = LOOKAHEAD_DISTANCE * i / LOOKAHEAD_SAMPLES;
            float peak = AirProbe.columnPeak(goose.getWorld(),
                    position.x + heading.x * distance,
                    position.z + heading.z * distance,
                    position.y);
            if (peak != Float.NEGATIVE_INFINITY) {
                floor = Math.max(floor, peak + PATH_CLEARANCE);
                if (nearestPeak == Float.NEGATIVE_INFINITY) {
                    nearestPeak = peak;
                    nearestDistance = distance;
                }
            }
        }
        terrainFloor = floor;
        steerAltitude = floor == Float.NEGATIVE_INFINITY ? cruiseAltitude
                : Math.max(cruiseAltitude, floor);

        // A route already knows how to get round this; a second opinion swerving at the same time
        // would just fight it.
        if (!routing && nearestPeak != Float.NEGATIVE_INFINITY) {
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
        float leftPeak = AirProbe.columnPeak(goose.getWorld(),
                position.x + left.x * LOOKAHEAD_DISTANCE,
                position.z + left.z * LOOKAHEAD_DISTANCE, position.y);

        Vector3f right = rotateY(new Vector3f(forward), -GO_AROUND_DEG);
        float rightPeak = AirProbe.columnPeak(goose.getWorld(),
                position.x + right.x * LOOKAHEAD_DISTANCE,
                position.z + right.z * LOOKAHEAD_DISTANCE, position.y);

        // The lower peak is the better side (NEGATIVE_INFINITY means clear); ties favour left.
        lateralBiasSign = leftPeak <= rightPeak ? 1 : -1;
        lateralBiasTimer = GO_AROUND_DURATION;
    }

    private boolean isSlotClear(Vector3f slot) {
        return AirProbe.isClear(goose.getWorld(), slot.x, slot.y, slot.z, SLOT_CLEARANCE);
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
