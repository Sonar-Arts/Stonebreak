package com.stonebreak.mobs.goose;

import org.joml.Vector3f;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.Player;

/**
 * AI controller for the goose — a flighted passive mob.
 *
 * <p>On the ground the goose idles and waddles much like the cow/chicken. It additionally:
 * <ul>
 *   <li><b>Floats</b> on water surfaces ({@link GooseBehaviorState#FLOATING}).</li>
 *   <li><b>Flees</b> the player within 5 blocks (walking) / 7 blocks (sprinting), running
 *       directly away without forming a formation.</li>
 *   <li><b>Migrates</b>: periodically takes off, forms a V-formation {@link GooseFlock},
 *       cruises to a far destination, and lands. A lone or fleeing goose can also fold into
 *       a nearby airborne flock instead of leading its own.</li>
 * </ul>
 *
 * <p>While airborne ({@link #isAirborne()}) the {@link Goose} reports itself self-propelled
 * so the external physics step (gravity/ground-snap/terrain collision) is skipped and this
 * AI owns the full 3D motion.
 */
public class GooseAI {

    // ─── Ground behavior tuning ───────────────────────────────────────────────
    private static final float MIN_STATE_DURATION = 3.0f;
    private static final float MAX_STATE_DURATION = 7.0f;
    private static final float WANDER_DISTANCE = 8.0f;
    private static final float WANDER_SPEED_MULT = 0.7f;
    private static final float FLEE_SPEED_MULT = 1.0f;
    private static final float ROTATION_SPEED = 200.0f;   // degrees/sec
    private static final float JUMP_COOLDOWN = 1.0f;
    private static final float OBSTACLE_CHECK_DISTANCE = 0.8f;

    // ─── Flee tuning ──────────────────────────────────────────────────────────
    private static final float FLEE_WALK_RANGE = 5.0f;
    private static final float FLEE_SPRINT_RANGE = 7.0f;
    /** Stop fleeing once the player is at least this far away. */
    private static final float FLEE_SAFE_RANGE = 11.0f;

    // ─── Flight tuning ────────────────────────────────────────────────────────
    /** Top flight speed — used by joiners/trailers to catch the formation. */
    private static final float MAX_FLY_SPEED = 9.0f;
    /** Cruise (formation) speed — slower than max so others can join. */
    public static final float CRUISE_FLY_SPEED = 6.0f;
    private static final float CLIMB_SPEED = 4.5f;
    private static final float DESCEND_SPEED = 3.5f;
    private static final float CRUISE_ALT_ABOVE_GROUND = 45.0f;
    private static final float SLOT_ARRIVE = 1.5f;
    private static final float DEST_ARRIVE_XZ = 8.0f;
    private static final float LANDING_GROUND_CLEAR = 1.5f;
    private static final float FLIGHT_TURN_SPEED = 160.0f; // degrees/sec while flying
    /** Brief window after takeoff where the goose phases through terrain so it can
        lift clear of the launch point instead of embedding in rising ground. */
    private static final float TAKEOFF_NOCLIP_DURATION = 1.0f; // seconds (~4.5 blocks of climb)

    // ─── Terrain look-ahead tuning (leader / lone flyer only) ─────────────────
    private static final float TERRAIN_SCAN_INTERVAL = 0.5f; // seconds between scans
    private static final float LOOKAHEAD_DISTANCE = 28.0f;    // how far ahead to probe
    private static final int LOOKAHEAD_SAMPLES = 3;           // columns sampled along heading
    private static final float PATH_CLEARANCE = 6.0f;         // blocks to clear terrain by
    private static final int CORRIDOR_SCAN_RANGE = 24;        // vertical window to find a peak
    private static final float GO_AROUND_DEG = 25.0f;         // heading offset while side-stepping
    private static final float GO_AROUND_DURATION = 1.5f;     // seconds a side-step stays armed

    // ─── Stuck recovery (any airborne goose pinned against a block) ───────────
    private static final float STUCK_POP_THRESHOLD = 0.8f;            // seconds pinned before a hard pop
    private static final float STUCK_POP_SPEED = CLIMB_SPEED * 1.6f;  // burst climb to clear a wall top

    // ─── Migration / flocking tuning ──────────────────────────────────────────
    private static final float MIGRATE_MIN_COOLDOWN = 25.0f;
    private static final float MIGRATE_MAX_COOLDOWN = 60.0f;
    private static final float MIGRATE_MIN_DISTANCE = 300.0f;
    private static final float MIGRATE_MAX_DISTANCE = 600.0f;
    private static final float JOIN_RADIUS = 70.0f;
    private static final float JOIN_SCAN_INTERVAL = 1.5f;
    private static final float JOIN_PROBABILITY = 0.6f;
    private static final int FLOCK_MAX_SIZE = 8;
    /** Seconds a goose stays grounded after touchdown before it may join/lead again. */
    private static final float SETTLE_COOLDOWN = 8.0f;

    // ─── State ────────────────────────────────────────────────────────────────
    private final Goose goose;
    private GooseBehaviorState currentState;
    private float stateTimer;
    private float stateChangeTimer;

    private final Vector3f wanderTarget = new Vector3f();
    private boolean hasWanderTarget;
    private float jumpCooldownTimer;

    private GooseFlock flock;
    private final Vector3f flightDestination = new Vector3f();
    private float cruiseAltitude;
    private float migrateCooldown;
    private float joinScanTimer;
    private float settleCooldown;

    // Terrain look-ahead state (only the leader / lone flyer drives these).
    private float terrainScanTimer;   // throttles scanAheadForTerrain()
    private float steerAltitude;      // dynamic flight altitude the flyer steers to
    private float lateralBiasTimer;   // >0 while a go-around side-step is active
    private int lateralBiasSign;      // -1 / +1 chosen clear side during go-around
    private float stuckTimer;         // seconds the goose has been pinned against terrain
    private float takeoffNoClipTimer; // >0 while the goose phases through terrain just after takeoff

    public enum GooseBehaviorState {
        IDLE,       // grounded, standing
        WANDERING,  // grounded, waddling to a nearby target
        FLOATING,   // resting/paddling on a water surface
        FLEEING,    // running directly away from the player (no formation)
        TAKEOFF,    // climbing to cruise altitude
        FORMATION,  // airborne member of a V-formation flock
        FREE_FLY,   // airborne solo (fallback) heading to destination
        LANDING     // descending to ground/water
    }

    public GooseAI(Goose goose) {
        this.goose = goose;
        this.currentState = GooseBehaviorState.IDLE;
        this.stateChangeTimer = randomStateDuration();
        this.migrateCooldown = randomMigrateCooldown();
        this.joinScanTimer = (float) (Math.random() * JOIN_SCAN_INTERVAL);
    }

    public GooseBehaviorState getCurrentState() {
        return currentState;
    }

    public boolean isAirborne() {
        return currentState == GooseBehaviorState.TAKEOFF
                || currentState == GooseBehaviorState.FORMATION
                || currentState == GooseBehaviorState.FREE_FLY
                || currentState == GooseBehaviorState.LANDING;
    }

    /** True during the brief post-takeoff window where the goose phases through terrain
        to lift clear of the launch point instead of embedding in rising ground. */
    public boolean isTakeoffNoClipActive() {
        return takeoffNoClipTimer > 0f;
    }

    // ─── Main tick ────────────────────────────────────────────────────────────

    public void update(float deltaTime) {
        if (!goose.isAlive()) return;

        stateTimer += deltaTime;
        stateChangeTimer -= deltaTime;
        jumpCooldownTimer -= deltaTime;
        migrateCooldown -= deltaTime;
        joinScanTimer -= deltaTime;
        settleCooldown -= deltaTime;

        if (!isAirborne()) {
            updateGrounded(deltaTime);
        } else {
            updateAirborne(deltaTime);
        }
    }

    // ─── Grounded behavior ────────────────────────────────────────────────────

    private void updateGrounded(float deltaTime) {
        // Fleeing takes priority over everything else on the ground.
        float fleeThreshold = playerFleeThreshold();
        if (fleeThreshold > 0) {
            if (currentState != GooseBehaviorState.FLEEING) {
                setState(GooseBehaviorState.FLEEING);
            }
        } else if (currentState == GooseBehaviorState.FLEEING) {
            // Player is far enough now — settle back into a passive state.
            setState(overWater() ? GooseBehaviorState.FLOATING : GooseBehaviorState.IDLE);
        }

        // Opportunistically join a nearby airborne V (even while fleeing — taking off to
        // a passing flock is a fine escape). Suppressed briefly after a touchdown so a
        // just-landed goose doesn't instantly re-launch toward a passing neighbor.
        if (settleCooldown <= 0 && joinScanTimer <= 0) {
            joinScanTimer = JOIN_SCAN_INTERVAL;
            GooseFlock nearby = scanForJoinableFlock();
            if (nearby != null && Math.random() < JOIN_PROBABILITY) {
                joinAndTakeoff(nearby);
                return;
            }
        }

        // Periodically consider initiating a migration (becoming a flock leader). Never
        // while fleeing — a panicking goose flees on foot, it does not lead a formation.
        if (currentState != GooseBehaviorState.FLEEING
                && migrateCooldown <= 0
                && !overWater()) {
            startMigrationAsLeader();
            migrateCooldown = randomMigrateCooldown();
            return;
        }

        // Passive state churn (idle/wander/float), excluding flee which manages itself.
        if (currentState != GooseBehaviorState.FLEEING && stateChangeTimer <= 0) {
            pickPassiveState();
            stateChangeTimer = randomStateDuration();
        }

        switch (currentState) {
            case IDLE -> handleIdle();
            case WANDERING -> handleWander(deltaTime);
            case FLOATING -> handleFloating(deltaTime);
            case FLEEING -> handleFleeing(deltaTime);
            default -> handleIdle();
        }
    }

    private void pickPassiveState() {
        if (overWater()) {
            setState(GooseBehaviorState.FLOATING);
            return;
        }
        setState(Math.random() < 0.45 ? GooseBehaviorState.IDLE : GooseBehaviorState.WANDERING);
    }

    private void handleIdle() {
        Vector3f v = goose.getVelocity();
        v.x = 0;
        v.z = 0;
        goose.setVelocity(v);
        goose.startIdling();
    }

    private void handleWander(float deltaTime) {
        if (!hasWanderTarget || goose.distanceTo(wanderTarget) < 1.5f) {
            generateWanderTarget();
        }
        if (hasWanderTarget) {
            moveTowardGround(wanderTarget, WANDER_SPEED_MULT, deltaTime, true);
        }
    }

    private void handleFloating(float deltaTime) {
        // Spring the goose's feet toward the water surface so it rests on top.
        float surfaceY = findWaterSurface();
        Vector3f v = goose.getVelocity();
        if (surfaceY != Float.NEGATIVE_INFINITY) {
            float diff = surfaceY - goose.getPosition().y;
            v.y = Math.max(-1.5f, Math.min(1.5f, diff * 6.0f));
        }
        // Gentle paddling: drift slowly toward a nearby target now and then.
        if (!hasWanderTarget || goose.distanceTo(wanderTarget) < 1.5f) {
            generateWanderTarget();
        }
        if (hasWanderTarget) {
            Vector3f dir = new Vector3f(wanderTarget).sub(goose.getPosition());
            dir.y = 0;
            if (dir.lengthSquared() > 0.01f) {
                dir.normalize();
                faceHeading(dir, deltaTime, ROTATION_SPEED);
                v.x = dir.x * goose.getMoveSpeed() * 0.4f;
                v.z = dir.z * goose.getMoveSpeed() * 0.4f;
            }
        }
        goose.setVelocity(v);
        // Leave the water if it dried up beneath us.
        if (surfaceY == Float.NEGATIVE_INFINITY) {
            setState(GooseBehaviorState.IDLE);
        }
    }

    private void handleFleeing(float deltaTime) {
        Player player = Game.getPlayer();
        if (player == null) {
            setState(GooseBehaviorState.IDLE);
            return;
        }
        Vector3f goosePos = goose.getPosition();
        Vector3f fleeDir = new Vector3f(goosePos).sub(player.getPosition());
        fleeDir.y = 0;
        if (fleeDir.lengthSquared() < 0.0001f) {
            fleeDir.set(1, 0, 0); // arbitrary when exactly on top of the player
        }
        fleeDir.normalize();

        faceHeading(fleeDir, deltaTime, ROTATION_SPEED);
        if (checkObstacleAhead(fleeDir) && goose.isOnGround() && jumpCooldownTimer <= 0) {
            goose.jump();
            jumpCooldownTimer = JUMP_COOLDOWN;
        }

        Vector3f v = goose.getVelocity();
        v.x = fleeDir.x * goose.getMoveSpeed() * FLEE_SPEED_MULT;
        v.z = fleeDir.z * goose.getMoveSpeed() * FLEE_SPEED_MULT;
        goose.setVelocity(v);
    }

    // ─── Airborne behavior ────────────────────────────────────────────────────

    private void updateAirborne(float deltaTime) {
        if (takeoffNoClipTimer > 0f) {
            takeoffNoClipTimer -= deltaTime;
        }
        switch (currentState) {
            case TAKEOFF -> handleTakeoff(deltaTime);
            case FORMATION -> handleFormation(deltaTime);
            case FREE_FLY -> handleFreeFly(deltaTime);
            case LANDING -> handleLanding(deltaTime);
            default -> { /* unreachable */ }
        }
        // Last-word steering: read this tick's collision flags and un-pin a goose that the
        // hard block-collision slid into a wall (followers and climbers included).
        applyStuckRecovery(deltaTime);
    }

    /**
     * Cheap, scan-free recovery for an airborne goose that flew into a solid block. Because the
     * flight collision slides along walls (keeping forward velocity), a goose can end up pinned;
     * here we climb the face (walls are finite, cruise leaves ~45 blocks of headroom), arc to the
     * clearer side via the existing {@link #armGoAround}, and — if still stuck after a moment —
     * pop straight up over the wall, or back out when a ceiling blocks the climb. Reads only the
     * collision flags plus, at most once per {@link #GO_AROUND_DURATION}, the two existing side
     * probes; no new world scans.
     */
    private void applyStuckRecovery(float deltaTime) {
        if (!goose.wasFlightBlockedHorizontally()) {
            stuckTimer = Math.max(0f, stuckTimer - deltaTime * 2f);
            return;
        }

        stuckTimer += deltaTime;
        Vector3f pos = goose.getPosition();
        Vector3f v = goose.getVelocity();

        // 1) Climb the wall face. Raise steerAltitude too so the leader's periodic scan
        //    (which would otherwise relax altitude) doesn't immediately undo the climb.
        v.y = Math.max(v.y, CLIMB_SPEED);
        steerAltitude = Math.max(steerAltitude, pos.y + PATH_CLEARANCE);

        // 2) Arm a lateral arc toward the clearer side if one isn't already running.
        if (lateralBiasTimer <= 0) {
            Vector3f heading = new Vector3f(v.x, 0, v.z);
            if (heading.lengthSquared() < 0.01f) {
                heading.set(flightDestination.x - pos.x, 0, flightDestination.z - pos.z);
            }
            if (heading.lengthSquared() > 0.01f) {
                armGoAround(pos, heading.normalize());
            }
        }

        // 3) Still pinned: pop over the wall (open sky), or back out (ceiling above).
        if (stuckTimer > STUCK_POP_THRESHOLD) {
            if (goose.wasFlightBlockedVertically()) {
                v.x = -v.x;
                v.z = -v.z;
            } else {
                v.y = STUCK_POP_SPEED;
            }
        }
        goose.setVelocity(v);
    }

    private void handleTakeoff(float deltaTime) {
        Vector3f pos = goose.getPosition();
        Vector3f v = goose.getVelocity();
        v.y = CLIMB_SPEED;

        // Head toward the destination while climbing so the climb covers ground.
        Vector3f dir = new Vector3f(flightDestination).sub(pos);
        dir.y = 0;
        if (dir.lengthSquared() > 0.01f) {
            dir.normalize();
            faceHeading(dir, deltaTime, FLIGHT_TURN_SPEED);
            v.x = dir.x * MAX_FLY_SPEED;
            v.z = dir.z * MAX_FLY_SPEED;
        }
        goose.setVelocity(v);

        // Reached cruise, or a ceiling/overhang stopped the climb — switch to cruising so the
        // goose steers out horizontally instead of grinding straight up into the block.
        if (pos.y >= cruiseAltitude || goose.wasFlightBlockedVertically()) {
            setState(flock != null ? GooseBehaviorState.FORMATION : GooseBehaviorState.FREE_FLY);
        }
    }

    private void handleFormation(float deltaTime) {
        if (flock == null || flock.isEmpty()) {
            setState(GooseBehaviorState.FREE_FLY);
            return;
        }

        if (flock.isLeader(goose)) {
            // Leader cruises toward the destination, climbing over / arcing around terrain;
            // followers slot off the leader so the whole flock follows the avoidance path.
            Vector3f dest = flock.getDestination();
            steerWithAvoidance(dest, flock.getCruiseSpeed(), deltaTime);
            if (horizontalDistance(goose.getPosition(), dest) < DEST_ARRIVE_XZ) {
                beginFlockLanding();
            }
        } else {
            // Follower steers to its V slot, sprinting to close big gaps, easing toward
            // cruise as it arrives, and only matching the leader's speed once truly in slot.
            Vector3f slot = flock.slotTargetFor(goose);
            float dist = goose.distanceTo(slot);
            float cruise = flock.getCruiseSpeed();
            float speed;
            if (dist > SLOT_ARRIVE * 2f) {
                speed = MAX_FLY_SPEED;                 // far: full sprint to catch up
            } else if (dist > SLOT_ARRIVE * 0.5f) {
                // closing: lerp cruise -> max so the follower stays faster than the
                // leader and the remaining gap actually shrinks
                float t = (dist - SLOT_ARRIVE * 0.5f) / (SLOT_ARRIVE * 1.5f);
                speed = cruise + t * (MAX_FLY_SPEED - cruise);
            } else {
                speed = cruise;                        // lined up: match the leader
            }
            steerFlight(slot, speed, deltaTime);
        }
    }

    private void handleFreeFly(float deltaTime) {
        // Fallback: no flock — fly to the destination (avoiding terrain), then land.
        steerWithAvoidance(flightDestination, CRUISE_FLY_SPEED, deltaTime);
        if (horizontalDistance(goose.getPosition(), flightDestination) < DEST_ARRIVE_XZ) {
            setState(GooseBehaviorState.LANDING);
        }
    }

    private void handleLanding(float deltaTime) {
        Vector3f pos = goose.getPosition();
        Vector3f v = goose.getVelocity();

        float surfaceY = landingSurfaceBelow();
        v.y = -DESCEND_SPEED;
        // Bleed off horizontal speed as we settle.
        v.x *= 0.92f;
        v.z *= 0.92f;
        goose.setVelocity(v);

        // Touchdown when within clearance of the surface below, OR when the descent was stopped
        // by a solid block (landing onto a ledge/cliff the column scan didn't see beneath us).
        boolean landedOnBlock = goose.wasFlightBlockedVertically() && v.y <= 0f;
        if ((surfaceY != Float.NEGATIVE_INFINITY && (pos.y - surfaceY) <= LANDING_GROUND_CLEAR)
                || landedOnBlock) {
            // Touchdown: drop out of the flock and hand control back to ground physics.
            touchDown(surfaceY != Float.NEGATIVE_INFINITY ? surfaceY : pos.y);
        }
    }

    private void touchDown(float surfaceY) {
        Vector3f v = goose.getVelocity();
        v.y = 0;
        goose.setVelocity(v);
        leaveFlock();
        // Next frame isAirborne() is false → normal mob physics resumes and settles it.
        setState(overWater() ? GooseBehaviorState.FLOATING : GooseBehaviorState.IDLE);
        migrateCooldown = randomMigrateCooldown();
        settleCooldown = SETTLE_COOLDOWN;
    }

    /**
     * Steers the goose toward a 3D target at the given horizontal speed, matching the
     * target altitude with a clamped vertical rate.
     */
    private void steerFlight(Vector3f target, float speed, float deltaTime) {
        Vector3f pos = goose.getPosition();
        Vector3f v = goose.getVelocity();

        Vector3f horiz = new Vector3f(target.x - pos.x, 0, target.z - pos.z);
        if (horiz.lengthSquared() > 0.01f) {
            horiz.normalize();
            faceHeading(horiz, deltaTime, FLIGHT_TURN_SPEED);
            v.x = horiz.x * speed;
            v.z = horiz.z * speed;
        } else {
            v.x = 0;
            v.z = 0;
        }

        float dy = target.y - pos.y;
        v.y = Math.max(-DESCEND_SPEED, Math.min(CLIMB_SPEED, dy * 2.0f));
        goose.setVelocity(v);
    }

    // ─── Terrain look-ahead (leader / lone flyer) ─────────────────────────────

    /**
     * Steers toward {@code dest} like {@link #steerFlight}, but periodically scans the terrain
     * ahead and raises {@link #steerAltitude} to clear peaks — or, for walls it cannot out-climb,
     * arcs around the obstacle. Only the flock leader and lone {@code FREE_FLY} geese call this;
     * followers track the leader's slot, so the avoidance path propagates to the whole flock.
     */
    private void steerWithAvoidance(Vector3f dest, float speed, float deltaTime) {
        terrainScanTimer -= deltaTime;
        lateralBiasTimer -= deltaTime;
        if (terrainScanTimer <= 0) {
            terrainScanTimer = TERRAIN_SCAN_INTERVAL;
            scanAheadForTerrain(dest, speed);
        }

        Vector3f pos = goose.getPosition();
        Vector3f target = new Vector3f(dest.x, steerAltitude, dest.z);
        if (lateralBiasTimer > 0) {
            // Arc around the obstacle: steer toward a waypoint offset to the clear side.
            Vector3f heading = new Vector3f(dest.x - pos.x, 0, dest.z - pos.z);
            if (heading.lengthSquared() > 0.01f) {
                Vector3f arc = rotateY(heading.normalize(), GO_AROUND_DEG * lateralBiasSign);
                target.set(pos.x + arc.x * LOOKAHEAD_DISTANCE,
                        steerAltitude,
                        pos.z + arc.z * LOOKAHEAD_DISTANCE);
            }
        }
        steerFlight(target, speed, deltaTime);
    }

    /**
     * Probes a few columns ahead along the heading toward {@code dest}. Raises
     * {@link #steerAltitude} to clear the tallest peak found (relaxing to {@link #cruiseAltitude}
     * when the corridor is clear), and arms a lateral side-step when the nearest peak cannot be
     * out-climbed before the goose reaches it.
     */
    private void scanAheadForTerrain(Vector3f dest, float speed) {
        Vector3f pos = goose.getPosition();
        Vector3f heading = new Vector3f(dest.x - pos.x, 0, dest.z - pos.z);
        if (heading.lengthSquared() < 0.01f) {
            steerAltitude = cruiseAltitude;
            return;
        }
        heading.normalize();

        float needed = cruiseAltitude;
        float nearPeak = Float.NEGATIVE_INFINITY;
        float nearDist = 0;
        for (int i = 1; i <= LOOKAHEAD_SAMPLES; i++) {
            float dist = LOOKAHEAD_DISTANCE * i / LOOKAHEAD_SAMPLES;
            float peak = corridorPeakAt(pos.x + heading.x * dist, pos.z + heading.z * dist, pos.y);
            if (peak != Float.NEGATIVE_INFINITY) {
                needed = Math.max(needed, peak + PATH_CLEARANCE);
                if (nearPeak == Float.NEGATIVE_INFINITY) {
                    nearPeak = peak;
                    nearDist = dist;
                }
            }
        }
        steerAltitude = needed;

        // Go-around: if the nearest intruding peak can't be out-climbed before we reach it,
        // side-step. Climb capacity over the gap ≈ dist * (climb rate / horizontal speed).
        if (nearPeak != Float.NEGATIVE_INFINITY) {
            float climbNeeded = (nearPeak + PATH_CLEARANCE) - pos.y;
            float climbBudget = nearDist * (CLIMB_SPEED / Math.max(0.1f, speed));
            if (climbNeeded > climbBudget) {
                armGoAround(pos, heading);
            }
        }
    }

    /** Probes one column to each side and arms a side-step toward the clearer one. */
    private void armGoAround(Vector3f pos, Vector3f heading) {
        Vector3f leftDir = rotateY(heading, GO_AROUND_DEG);   // sign +1
        Vector3f rightDir = rotateY(heading, -GO_AROUND_DEG); // sign -1
        float leftPeak = corridorPeakAt(
                pos.x + leftDir.x * LOOKAHEAD_DISTANCE, pos.z + leftDir.z * LOOKAHEAD_DISTANCE, pos.y);
        float rightPeak = corridorPeakAt(
                pos.x + rightDir.x * LOOKAHEAD_DISTANCE, pos.z + rightDir.z * LOOKAHEAD_DISTANCE, pos.y);
        // Lower peak (or NEGATIVE_INFINITY = clear) is the better side; ties favor left.
        lateralBiasSign = (leftPeak <= rightPeak) ? 1 : -1;
        lateralBiasTimer = GO_AROUND_DURATION;
    }

    /**
     * Top face Y of the highest solid block within ±{@link #CORRIDOR_SCAN_RANGE} of
     * {@code currentY} at a column, or {@link Float#NEGATIVE_INFINITY} if the corridor is clear.
     */
    private float corridorPeakAt(float x, float z, float currentY) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int top = (int) Math.floor(currentY) + CORRIDOR_SCAN_RANGE;
        int bottom = (int) Math.floor(currentY) - CORRIDOR_SCAN_RANGE;
        for (int y = top; y >= bottom; y--) {
            BlockType block = goose.getWorld().getBlockAt(bx, y, bz);
            if (block != null && block.isSolid()) {
                return y + 1.0f;
            }
        }
        return Float.NEGATIVE_INFINITY;
    }

    /** Rotates a horizontal direction about the Y axis by {@code deg} degrees (new vector). */
    private static Vector3f rotateY(Vector3f dir, float deg) {
        double rad = Math.toRadians(deg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        return new Vector3f(dir.x * cos - dir.z * sin, 0, dir.x * sin + dir.z * cos);
    }

    // ─── Flock entry points ───────────────────────────────────────────────────

    private void startMigrationAsLeader() {
        Vector3f pos = goose.getPosition();
        double angle = Math.random() * Math.PI * 2;
        float distance = MIGRATE_MIN_DISTANCE
                + (float) (Math.random() * (MIGRATE_MAX_DISTANCE - MIGRATE_MIN_DISTANCE));
        cruiseAltitude = pos.y + CRUISE_ALT_ABOVE_GROUND;
        steerAltitude = cruiseAltitude;

        flightDestination.set(
                pos.x + (float) Math.cos(angle) * distance,
                cruiseAltitude,
                pos.z + (float) Math.sin(angle) * distance);

        this.flock = new GooseFlock(goose, flightDestination, CRUISE_FLY_SPEED);
        setState(GooseBehaviorState.TAKEOFF);
    }

    private void joinAndTakeoff(GooseFlock target) {
        target.join(goose);
        this.flock = target;
        Vector3f dest = target.getDestination();
        this.flightDestination.set(dest);
        this.cruiseAltitude = dest.y;
        this.steerAltitude = dest.y;
        setState(GooseBehaviorState.TAKEOFF);
    }

    /**
     * Places this goose directly into an existing airborne formation (used by the spawner
     * for flocks that spawn already in flight). Assumes the goose is positioned at altitude.
     */
    public void enterFormation(GooseFlock target) {
        target.join(goose);
        this.flock = target;
        Vector3f dest = target.getDestination();
        this.flightDestination.set(dest);
        this.cruiseAltitude = dest.y;
        this.steerAltitude = dest.y;
        setState(GooseBehaviorState.FORMATION);
    }

    private void beginFlockLanding() {
        if (flock == null) {
            setState(GooseBehaviorState.LANDING);
            return;
        }
        // Mark the flock landing so no grounded goose joins a formation that is already
        // descending — that was the source of the endless takeoff/land bounce.
        flock.markLanding();
        // Land every member; copy the list defensively in case landing mutates membership.
        for (Goose member : flockMembersSnapshot()) {
            member.getAI().requestLanding();
        }
    }

    /** Asks this goose to begin descending (invoked by its flock leader). */
    void requestLanding() {
        if (isAirborne() && currentState != GooseBehaviorState.LANDING) {
            setState(GooseBehaviorState.LANDING);
        }
    }

    private java.util.List<Goose> flockMembersSnapshot() {
        java.util.List<Goose> result = new java.util.ArrayList<>();
        EntityManager em = entityManager();
        if (em == null || flock == null) return result;
        for (Entity e : em.getEntitiesByType(EntityType.GOOSE)) {
            if (e instanceof Goose g && g.getAI().flock == flock) {
                result.add(g);
            }
        }
        return result;
    }

    private void leaveFlock() {
        if (flock != null) {
            flock.leave(goose);
            flock = null;
        }
    }

    /**
     * Finds the nearest airborne flock with open capacity within {@link #JOIN_RADIUS}
     * (horizontal), or {@code null} if none.
     */
    private GooseFlock scanForJoinableFlock() {
        EntityManager em = entityManager();
        if (em == null) return null;

        Vector3f pos = goose.getPosition();
        GooseFlock best = null;
        float bestDist = Float.MAX_VALUE;
        for (Entity e : em.getEntitiesByType(EntityType.GOOSE)) {
            if (!(e instanceof Goose other) || other == goose) continue;
            GooseAI otherAI = other.getAI();
            GooseFlock f = otherAI.flock;
            if (f == null || f == this.flock || !otherAI.isAirborne()) continue;
            // A flock that has begun its landing descent is no longer joinable.
            if (f.isLanding()) continue;
            if (f.size() >= FLOCK_MAX_SIZE) continue;
            float d = horizontalDistance(pos, other.getPosition());
            if (d <= JOIN_RADIUS && d < bestDist) {
                bestDist = d;
                best = f;
            }
        }
        return best;
    }

    // ─── External events ──────────────────────────────────────────────────────

    public void onDamaged(float damage) {
        // Damage panics the goose into fleeing on foot (handled each tick by the flee
        // threshold; force it now so it reacts even from a slightly larger range).
        if (!isAirborne()) {
            setState(GooseBehaviorState.FLEEING);
        }
    }

    public void cleanup() {
        leaveFlock();
        hasWanderTarget = false;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Flee threshold for the current player state, or 0 if the player is out of range. */
    private float playerFleeThreshold() {
        Player player = Game.getPlayer();
        if (player == null) return 0;
        float dist = goose.distanceTo(player.getPosition());
        float trigger = player.isSprinting() ? FLEE_SPRINT_RANGE : FLEE_WALK_RANGE;
        if (currentState == GooseBehaviorState.FLEEING) {
            // Hysteresis: keep fleeing until safely clear.
            return dist <= FLEE_SAFE_RANGE ? trigger : 0;
        }
        return dist <= trigger ? trigger : 0;
    }

    private void generateWanderTarget() {
        Vector3f pos = goose.getPosition();
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = Math.random() * Math.PI * 2;
            float distance = 3.0f + (float) (Math.random() * (WANDER_DISTANCE - 3.0f));
            float tx = pos.x + (float) Math.cos(angle) * distance;
            float tz = pos.z + (float) Math.sin(angle) * distance;
            int bx = (int) Math.floor(tx);
            int bz = (int) Math.floor(tz);
            float cx = bx + 0.5f;
            float cz = bz + 0.5f;
            float groundY = findGroundLevel(cx, cz, pos.y);
            if (groundY != Float.NEGATIVE_INFINITY) {
                wanderTarget.set(cx, groundY, cz);
                hasWanderTarget = true;
                return;
            }
        }
        hasWanderTarget = false;
    }

    /** Moves the goose along the ground toward {@code target}, hopping over obstacles. */
    private void moveTowardGround(Vector3f target, float speedMult, float deltaTime, boolean canJump) {
        Vector3f pos = goose.getPosition();
        Vector3f dir = new Vector3f(target).sub(pos);
        dir.y = 0;
        if (dir.lengthSquared() <= 0.01f) return;
        dir.normalize();

        faceHeading(dir, deltaTime, ROTATION_SPEED);
        if (canJump && checkObstacleAhead(dir) && goose.isOnGround() && jumpCooldownTimer <= 0) {
            goose.jump();
            jumpCooldownTimer = JUMP_COOLDOWN;
        }

        Vector3f v = goose.getVelocity();
        v.x = dir.x * goose.getMoveSpeed() * speedMult;
        v.z = dir.z * goose.getMoveSpeed() * speedMult;
        goose.setVelocity(v);
    }

    private void faceHeading(Vector3f horizontalDir, float deltaTime, float turnSpeed) {
        // The SB_Goose.sbe model faces +Z (like the chicken, unlike the cow/sheep which
        // face -Z), so no 180° offset is applied — the goose faces its travel direction.
        float targetYaw = (float) Math.toDegrees(Math.atan2(horizontalDir.x, horizontalDir.z));
        Vector3f rot = goose.getRotation();
        float delta = targetYaw - rot.y;
        while (delta > 180.0f) delta -= 360.0f;
        while (delta < -180.0f) delta += 360.0f;
        float maxStep = turnSpeed * deltaTime;
        if (Math.abs(delta) > maxStep) {
            delta = Math.signum(delta) * maxStep;
        }
        goose.setRotation(new Vector3f(rot.x, rot.y + delta, rot.z));
    }

    private boolean checkObstacleAhead(Vector3f direction) {
        Vector3f pos = goose.getPosition();
        int bx = (int) Math.floor(pos.x + direction.x * OBSTACLE_CHECK_DISTANCE);
        int by = (int) Math.floor(pos.y);
        int bz = (int) Math.floor(pos.z + direction.z * OBSTACLE_CHECK_DISTANCE);
        BlockType foot = goose.getWorld().getBlockAt(bx, by, bz);
        if (foot != null && foot.isSolid()) {
            BlockType above1 = goose.getWorld().getBlockAt(bx, by + 1, bz);
            BlockType above2 = goose.getWorld().getBlockAt(bx, by + 2, bz);
            return (above1 == null || !above1.isSolid()) && (above2 == null || !above2.isSolid());
        }
        return false;
    }

    private float findGroundLevel(float x, float z, float startY) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int startBlockY = (int) Math.floor(startY);
        for (int y = startBlockY + 5; y >= startBlockY - 10; y--) {
            BlockType block = goose.getWorld().getBlockAt(bx, y, bz);
            BlockType above = goose.getWorld().getBlockAt(bx, y + 1, bz);
            if (block != null && block.isSolid() && (above == null || !above.isSolid())) {
                return y + 1.0f;
            }
        }
        return Float.NEGATIVE_INFINITY;
    }

    /** Topmost water-surface Y at the goose's column near its feet, or NEGATIVE_INFINITY. */
    private float findWaterSurface() {
        Vector3f pos = goose.getPosition();
        int bx = (int) Math.floor(pos.x);
        int bz = (int) Math.floor(pos.z);
        for (int y = (int) Math.floor(pos.y) + 1; y >= (int) Math.floor(pos.y) - 3; y--) {
            BlockType b = goose.getWorld().getBlockAt(bx, y, bz);
            if (b == BlockType.WATER) {
                return y + 1.0f; // top face of the topmost water block
            }
        }
        return Float.NEGATIVE_INFINITY;
    }

    private boolean overWater() {
        return findWaterSurface() != Float.NEGATIVE_INFINITY;
    }

    /** Highest landable surface (solid ground or water top) beneath the goose. */
    private float landingSurfaceBelow() {
        Vector3f pos = goose.getPosition();
        float ground = findGroundLevel(pos.x, pos.z, pos.y);
        float water = findWaterSurface();
        return Math.max(ground, water);
    }

    private static float horizontalDistance(Vector3f a, Vector3f b) {
        float dx = a.x - b.x;
        float dz = a.z - b.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    private EntityManager entityManager() {
        return goose.getWorld() != null ? goose.getWorld().getEntityManager() : null;
    }

    private void setState(GooseBehaviorState newState) {
        if (newState != currentState) {
            currentState = newState;
            stateTimer = 0;
            if (newState == GooseBehaviorState.WANDERING || newState == GooseBehaviorState.FLOATING) {
                hasWanderTarget = false;
            }
            if (newState == GooseBehaviorState.TAKEOFF) {
                takeoffNoClipTimer = TAKEOFF_NOCLIP_DURATION;
            }
        }
    }

    private static float randomStateDuration() {
        return MIN_STATE_DURATION + (float) (Math.random() * (MAX_STATE_DURATION - MIN_STATE_DURATION));
    }

    private static float randomMigrateCooldown() {
        return MIGRATE_MIN_COOLDOWN + (float) (Math.random() * (MIGRATE_MAX_COOLDOWN - MIGRATE_MIN_COOLDOWN));
    }
}
