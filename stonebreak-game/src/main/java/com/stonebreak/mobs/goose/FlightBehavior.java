package com.stonebreak.mobs.goose;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.Behavior;
import com.stonebreak.mobs.entities.ai.nav.AirPathAgent;
import com.stonebreak.mobs.entities.ai.nav.GroundProbe;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * A goose's whole flight: taking off, cruising in formation or alone, and coming back down.
 *
 * <p>The phases are internal rather than four separate behaviours because they are not independent
 * — they share a flock, a destination and a cruise altitude, and no scheduler could sensibly
 * interleave them. Once a goose is airborne this behaviour owns it until touchdown, which is also
 * what makes {@link Goose#isSelfPropelled()} a simple question.
 *
 * <p>Two things start a flight: the migration timer coming due, or spotting a passing formation
 * worth joining. Joining is allowed even with the player close by — leaving the ground is a fine
 * escape — while leading a migration is not, so a panicking goose flees on foot as it used to.
 */
public final class FlightBehavior implements Behavior {

    /** Above fleeing: a goose would rather leave the ground than run. */
    private static final int PRIORITY = 15;

    private static final float CRUISE_ALT_ABOVE_GROUND = 45.0f;
    private static final float SLOT_ARRIVE = 1.5f;
    private static final float DEST_ARRIVE_XZ = 8.0f;

    /** Sub-visual float-precision guard, not a safety margin. */
    private static final float TOUCHDOWN_EPSILON = 0.02f;

    /**
     * Water touchdown tolerance. Water landings have no collision backstop — flight collision
     * treats water as passable — so this is the only stop signal; any residual gap is smoothed away
     * by the floating behaviour's spring to the surface.
     */
    private static final float WATER_LANDING_CLEAR = 0.5f;

    /**
     * How close to the flight direction the goose must be pointing before it leaves the ground, and
     * how long it may spend getting there. The timeout only exists so a goose that somehow cannot
     * settle on a heading still takes off — at {@code GROUND_TURN_SPEED} a full about-face is under
     * a second, so it is slack, not a budget.
     */
    private static final float TAKEOFF_ALIGN_DEGREES = 12.0f;
    private static final float ORIENT_TIMEOUT = 2.0f;

    /**
     * Blocks of straight-up climb before a launching goose starts translating.
     *
     * <p>This is what replaced the old "phase through terrain for a second" hack. Going up first
     * and outward second is both what a bird does and what makes collision safe to leave on: the
     * failure the hack was hiding was a goose accelerating to full flight speed while still at
     * ground level, which on any rising ground means flying straight into the hill it launched
     * from. Climb clear, then fly.
     */
    private static final float TAKEOFF_LIFT_CLEARANCE = 3.0f;

    /**
     * Backstop on the climb-out. A goose that launches into a cave never reaches cruise and never
     * meets a ceiling square-on; without this it would climb at the roof forever.
     */
    private static final float TAKEOFF_TIMEOUT = 20.0f;

    private static final float MIGRATE_MIN_COOLDOWN = 25.0f;
    private static final float MIGRATE_MAX_COOLDOWN = 60.0f;
    private static final float MIGRATE_MIN_DISTANCE = 300.0f;
    private static final float MIGRATE_MAX_DISTANCE = 600.0f;
    private static final float JOIN_RADIUS = 70.0f;
    private static final float JOIN_SCAN_INTERVAL = 1.5f;
    private static final float JOIN_PROBABILITY = 0.6f;
    private static final int FLOCK_MAX_SIZE = 8;

    /** Seconds a goose stays grounded after touchdown before it may join or lead again. */
    private static final float SETTLE_COOLDOWN = 8.0f;

    /** A migration is not led while the player is this close; the goose flees on foot instead. */
    private static final float MIGRATE_PLAYER_CLEARANCE = 11.0f;

    private enum Phase {
        /** Still on the ground, turning to face the way the flight is about to go. */
        ORIENTING,
        /** Climbing to cruise altitude. */
        TAKEOFF,
        /** Airborne member of a V formation. */
        FORMATION,
        /** Airborne alone, heading for the destination. */
        FREE_FLY,
        /** Descending to ground or water. */
        LANDING
    }

    private final Goose goose;
    private final FlightSteering flight;

    private Phase phase = Phase.ORIENTING;
    private boolean airborne;
    private GooseFlock flock;
    private GooseFlock pendingJoin;

    /** Seconds spent turning on the spot before launch. */
    private float orientTimer;
    /** Seconds spent climbing out, against {@link #TAKEOFF_TIMEOUT}. */
    private float takeoffTimer;
    /** Y the straight-up part of the launch is climbing to. */
    private float liftTargetY;
    /** Whether the launch is still in its vertical-only stage. */
    private boolean liftingOff;

    private final Vector3f destination = new Vector3f();
    /** Scratch for the route's current waypoint; reused every tick. */
    private final Vector3f routeTarget = new Vector3f();
    private float migrateCooldown;
    private float joinScanTimer;
    private float settleCooldown;

    FlightBehavior(Goose goose, FlightSteering flight) {
        this.goose = goose;
        this.flight = flight;
        // Jitter the first migration and join scan so a flock spawned together does not all take
        // off on the same tick.
        this.migrateCooldown = MIGRATE_MIN_COOLDOWN
                + (float) (Math.random() * (MIGRATE_MAX_COOLDOWN - MIGRATE_MIN_COOLDOWN));
        this.joinScanTimer = (float) (Math.random() * JOIN_SCAN_INTERVAL);
    }

    /** Whether the goose is off the ground — drives its physics mode. */
    public boolean isAirborne() {
        return airborne;
    }

    /**
     * The air route this goose is flying, for the debug overlay. Only a flock leader or a lone
     * flyer plans one; wingmen hold slots on the leader's trail and route implicitly through it.
     */
    public AirPathAgent route() {
        return flight.route();
    }

    // ── Behavior ─────────────────────────────────────────────────────────────

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public EnumSet<Flag> flags() {
        return EnumSet.of(Flag.MOVE);
    }

    /** A goose working up to a launch is flapping on the ground, not yet flying. */
    @Override
    public MobBehaviorState animationState() {
        return phase == Phase.ORIENTING ? MobBehaviorState.WING_FLAP : MobBehaviorState.FLYING;
    }

    @Override
    public String debugName() {
        return "Flight:" + phase;
    }

    /**
     * Also where the grounded timers advance: {@code canStart} is the one hook called every tick
     * while the behaviour is not running, which is exactly when a migration cooldown should be
     * counting down.
     */
    @Override
    public boolean canStart(AiContext context) {
        float deltaTime = context.deltaTime();
        migrateCooldown -= deltaTime;
        settleCooldown -= deltaTime;
        joinScanTimer -= deltaTime;

        if (settleCooldown > 0.0f) {
            return false;
        }

        if (joinScanTimer <= 0.0f) {
            joinScanTimer = JOIN_SCAN_INTERVAL;
            GooseFlock nearby = findJoinableFlock();
            if (nearby != null && context.random().nextFloat() < JOIN_PROBABILITY) {
                pendingJoin = nearby;
                return true;
            }
        }

        return migrateCooldown <= 0.0f
                && context.distanceToNearestPlayer() > MIGRATE_PLAYER_CLEARANCE
                && !overWater();
    }

    /** Also true while still on the ground orienting — the goose is committed to the flight. */
    @Override
    public boolean shouldContinue(AiContext context) {
        return airborne || phase == Phase.ORIENTING;
    }

    @Override
    public void start(AiContext context) {
        flight.reset();
        if (pendingJoin != null) {
            joinFlock(pendingJoin);
            pendingJoin = null;
        } else {
            leadMigration(context);
        }
        // Point the right way before leaving the ground; `airborne` stays false so the goose keeps
        // its normal ground physics and simply pivots on the spot.
        phase = Phase.ORIENTING;
        airborne = false;
        orientTimer = 0.0f;
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        switch (phase) {
            case ORIENTING -> tickOrienting(deltaTime);
            case TAKEOFF -> tickTakeoff(deltaTime);
            case FORMATION -> tickFormation(deltaTime);
            case FREE_FLY -> tickFreeFly(deltaTime);
            case LANDING -> tickLanding(deltaTime);
        }

        // Last word: read this tick's collision flags and un-pin a goose the flight collision slid
        // into a wall. Followers and climbers included.
        flight.applyStuckRecovery(deltaTime);
    }

    @Override
    public void stop(AiContext context) {
        leaveFlock();
        // A landed goose has no business holding a search in flight, or a route through the sky.
        flight.releaseRoute();
        airborne = false;
        liftingOff = false;
        migrateCooldown = randomMigrateCooldown(context);
        settleCooldown = SETTLE_COOLDOWN;
    }

    @Override
    public void onDamaged(AiContext context, float damage) {
        // Being hurt on the ground is a reason to run, not to migrate — hold off the next flight.
        if (!airborne) {
            migrateCooldown = Math.max(migrateCooldown, SETTLE_COOLDOWN);
        }
    }

    // ── Phases ───────────────────────────────────────────────────────────────

    /**
     * On the ground, turning to face the way the flight is about to go.
     *
     * <p>The route is planned here too, which is the reason this phase is worth its own tick rather
     * than being folded into the first moments of the climb: by the time the goose leaves the
     * ground the search has landed, so it lifts off already pointing along its first leg instead of
     * pointing at the destination and correcting once airborne.
     */
    private void tickOrienting(float deltaTime) {
        orientTimer += deltaTime;
        flight.stopHorizontal(); // pivot on the spot; ground physics still holds it down

        float yawError = flight.faceFlightDirection(destination, deltaTime);
        if (yawError <= TAKEOFF_ALIGN_DEGREES || orientTimer >= ORIENT_TIMEOUT) {
            beginTakeoff();
        }
    }

    private void beginTakeoff() {
        phase = Phase.TAKEOFF;
        airborne = true;
        takeoffTimer = 0.0f;
        liftingOff = true;
        liftTargetY = goose.getPosition().y + TAKEOFF_LIFT_CLEARANCE;
    }

    private void tickTakeoff(float deltaTime) {
        takeoffTimer += deltaTime;
        Vector3f position = goose.getPosition();
        Vector3f velocity = goose.getVelocity();
        velocity.y = FlightSteering.CLIMB_SPEED;

        // The route is advanced every tick of the climb, so it is current the moment the goose
        // starts translating — and so a takeoff into a hillside is planned around rather than flown
        // into. Nothing here disables collision: the goose is a solid body from the first frame.
        Vector3f target = flight.routeTarget(destination, deltaTime, routeTarget);

        boolean wasLifting = liftingOff;
        if (liftingOff && (position.y >= liftTargetY || goose.wasFlightBlockedVertically())) {
            liftingOff = false;
        }

        if (liftingOff) {
            // Straight up, out of the terrain it launched from. Full flight speed at ground level
            // is what used to drive geese into rising ground.
            velocity.x = 0.0f;
            velocity.z = 0.0f;
        } else {
            Vector3f toTarget = new Vector3f(target).sub(position);
            toTarget.y = 0.0f;
            if (toTarget.lengthSquared() > 0.01f) {
                toTarget.normalize();
                // Keep turning through the climb: the pre-launch pivot only aimed at the route's
                // first leg, and a climb-out lasts long enough for the route to bend away from it.
                flight.faceTravel(toTarget, deltaTime);
                velocity.x = toTarget.x * FlightSteering.MAX_FLY_SPEED;
                velocity.z = toTarget.z * FlightSteering.MAX_FLY_SPEED;
            }
        }
        goose.setVelocity(velocity);

        // At cruise, or a ceiling stopped a climb that is already translating: start cruising so
        // the goose steers out horizontally instead of grinding up into the block. A ceiling met
        // during the vertical lift is handled above by ending the lift, not the takeoff — that is
        // a goose under a canopy, which needs to scoot out from under it, not give up climbing.
        // `wasLifting`, not `liftingOff`: a ceiling met during the lift ends the lift on this very
        // tick, and reading the post-update flag would then read that same ceiling as "stopped a
        // translating climb" and abandon the takeoff — turning the canopy case back into the bug it
        // is meant to fix. Only a ceiling met while already translating ends the climb-out.
        boolean ceilingWhileTranslating = !wasLifting && goose.wasFlightBlockedVertically();
        if (position.y >= flight.cruiseAltitude() || ceilingWhileTranslating
                || takeoffTimer >= TAKEOFF_TIMEOUT) {
            phase = flock != null ? Phase.FORMATION : Phase.FREE_FLY;
            liftingOff = false;
            if (flock != null && !flock.isLeader(goose)) {
                // A wingman routes through its leader's trail from here on, so it has no use for a
                // route of its own — and holding one would keep a search in flight for nobody.
                flight.releaseRoute();
            }
        }
    }

    private void tickFormation(float deltaTime) {
        if (flock == null || flock.isEmpty()) {
            phase = Phase.FREE_FLY;
            return;
        }

        if (flock.isLeader(goose)) {
            // Lay down the trail first: the wingmen behind hold slots on the line actually flown,
            // and this tick's position is the newest part of it.
            flock.recordLeaderTrail(goose.getPosition());
            Vector3f target = flock.getDestination();
            flight.steerAlongRoute(target, flock.getCruiseSpeed(), deltaTime);
            if (horizontalDistance(goose.getPosition(), target) < DEST_ARRIVE_XZ) {
                beginFlockLanding();
            }
            return;
        }

        // Follower: sprint to close a big gap, ease toward cruise as the slot nears, and only match
        // the leader's speed once actually lined up — otherwise the gap never closes.
        //
        // The slot is resolved against the terrain before it is flown at: in open sky it is the
        // formation's V, and in a pass it collapses toward the leader's own trail, which is the one
        // line through the gap that is known to be clear.
        Vector3f slot = flight.resolveFormationSlot(
                flock.slotTargetFor(goose), flock.spineTargetFor(goose));
        float distance = goose.distanceTo(slot);
        float cruise = flock.getCruiseSpeed();
        float speed;
        if (distance > SLOT_ARRIVE * 2.0f) {
            speed = FlightSteering.MAX_FLY_SPEED;
        } else if (distance > SLOT_ARRIVE * 0.5f) {
            float closing = (distance - SLOT_ARRIVE * 0.5f) / (SLOT_ARRIVE * 1.5f);
            speed = cruise + closing * (FlightSteering.MAX_FLY_SPEED - cruise);
        } else {
            speed = cruise;
        }
        flight.steerTo(slot, speed, deltaTime);
    }

    private void tickFreeFly(float deltaTime) {
        flight.steerAlongRoute(destination, FlightSteering.CRUISE_FLY_SPEED, deltaTime);
        if (horizontalDistance(goose.getPosition(), destination) < DEST_ARRIVE_XZ) {
            phase = Phase.LANDING;
        }
    }

    private void tickLanding(float deltaTime) {
        Vector3f position = goose.getPosition();
        Vector3f velocity = goose.getVelocity();

        float groundY = GroundProbe.groundLevel(goose.getWorld(), position.x, position.z, position.y);
        float waterY = GroundProbe.waterSurface(goose.getWorld(), position.x, position.y, position.z);

        velocity.y = -FlightSteering.DESCEND_SPEED;
        velocity.x *= 0.92f; // bleed off horizontal speed as it settles
        velocity.z *= 0.92f;
        goose.setVelocity(velocity);

        // Flight collision already snaps the goose flush onto a solid surface the instant its
        // descent step would cross into one, so this fires at true zero clearance.
        boolean landedOnBlock = goose.wasFlightBlockedVertically() && velocity.y <= 0.0f;

        // Backstop bounded by exactly one tick of descent, so it can only fire when the goose is
        // within a physics step of the surface — for the cases the column scan catches and the
        // collision shape might not, such as partial-height blocks.
        float groundClearance = FlightSteering.DESCEND_SPEED * deltaTime + TOUCHDOWN_EPSILON;
        boolean overGround = groundY != Float.NEGATIVE_INFINITY
                && (position.y - groundY) <= groundClearance;
        boolean overWaterSurface = waterY != Float.NEGATIVE_INFINITY
                && (position.y - waterY) <= WATER_LANDING_CLEAR;

        if (landedOnBlock || overGround || overWaterSurface) {
            velocity.y = 0.0f;
            goose.setVelocity(velocity);
            airborne = false; // shouldContinue ends the behaviour, and stop() does the cleanup
        }
    }

    // ── Flock ────────────────────────────────────────────────────────────────

    private void leadMigration(AiContext context) {
        Vector3f position = goose.getPosition();
        float angle = context.random().nextFloat() * (float) (Math.PI * 2.0);
        float distance = context.randomBetween(MIGRATE_MIN_DISTANCE, MIGRATE_MAX_DISTANCE);

        float cruiseAltitude = position.y + CRUISE_ALT_ABOVE_GROUND;
        flight.setCruiseAltitude(cruiseAltitude);
        destination.set(
                position.x + (float) Math.cos(angle) * distance,
                cruiseAltitude,
                position.z + (float) Math.sin(angle) * distance);

        flock = new GooseFlock(goose, destination, FlightSteering.CRUISE_FLY_SPEED);
    }

    private void joinFlock(GooseFlock target) {
        target.join(goose);
        flock = target;
        destination.set(target.getDestination());
        flight.setCruiseAltitude(destination.y);
    }

    private void beginFlockLanding() {
        if (flock == null) {
            phase = Phase.LANDING;
            return;
        }
        // Mark the flock landing so no grounded goose joins a formation already descending — that
        // was the source of the endless takeoff/land bounce.
        flock.markLanding();
        for (Goose member : flockMembers()) {
            member.flight().requestLanding();
        }
    }

    /** Asks this goose to begin descending; called by its flock leader. */
    void requestLanding() {
        if (phase == Phase.ORIENTING) {
            // It never left the ground, so there is nothing to descend. Abandoning the flight is
            // the honest answer — and with `airborne` already false, shouldContinue ends the
            // behaviour on this tick and stop() does the cleanup.
            phase = Phase.LANDING;
            return;
        }
        if (airborne && phase != Phase.LANDING) {
            phase = Phase.LANDING;
        }
    }

    private void leaveFlock() {
        if (flock != null) {
            flock.leave(goose);
            flock = null;
        }
    }

    private List<Goose> flockMembers() {
        List<Goose> members = new ArrayList<>();
        EntityManager entities = entityManager();
        if (entities == null || flock == null) {
            return members;
        }
        for (Entity entity : entities.getEntitiesByType(EntityType.GOOSE)) {
            if (entity instanceof Goose other && other.flight().flock == flock) {
                members.add(other);
            }
        }
        return members;
    }

    /** The nearest airborne formation with room, within the join radius, or null. */
    private GooseFlock findJoinableFlock() {
        EntityManager entities = entityManager();
        if (entities == null) {
            return null;
        }
        Vector3f position = goose.getPosition();
        GooseFlock best = null;
        float bestDistance = Float.MAX_VALUE;

        for (Entity entity : entities.getEntitiesByType(EntityType.GOOSE)) {
            if (!(entity instanceof Goose other) || other == goose) {
                continue;
            }
            FlightBehavior otherFlight = other.flight();
            GooseFlock candidate = otherFlight.flock;
            if (candidate == null || candidate == flock || !otherFlight.isAirborne()) {
                continue;
            }
            if (candidate.isLanding() || candidate.size() >= FLOCK_MAX_SIZE) {
                continue;
            }
            float distance = horizontalDistance(position, other.getPosition());
            if (distance <= JOIN_RADIUS && distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean overWater() {
        Vector3f position = goose.getPosition();
        return GroundProbe.waterSurface(goose.getWorld(), position.x, position.y, position.z)
                != Float.NEGATIVE_INFINITY;
    }

    private EntityManager entityManager() {
        return goose.getWorld() != null ? goose.getWorld().getEntityManager() : null;
    }

    private static float randomMigrateCooldown(AiContext context) {
        return context.randomBetween(MIGRATE_MIN_COOLDOWN, MIGRATE_MAX_COOLDOWN);
    }

    private static float horizontalDistance(Vector3f a, Vector3f b) {
        float dx = a.x - b.x;
        float dz = a.z - b.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }
}
