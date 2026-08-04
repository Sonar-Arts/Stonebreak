package com.stonebreak.mobs.goose;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.Behavior;
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
     * Brief window after takeoff where the goose phases through terrain, so it lifts clear of the
     * launch point instead of embedding itself in rising ground. ~4.5 blocks of climb.
     */
    private static final float TAKEOFF_NOCLIP_DURATION = 1.0f;

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

    private Phase phase = Phase.TAKEOFF;
    private boolean airborne;
    private GooseFlock flock;
    private GooseFlock pendingJoin;

    private final Vector3f destination = new Vector3f();
    private float migrateCooldown;
    private float joinScanTimer;
    private float settleCooldown;
    private float takeoffNoClipTimer;

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

    /** Whether the goose is still in the post-takeoff window where it passes through terrain. */
    public boolean isTakeoffNoClipActive() {
        return takeoffNoClipTimer > 0.0f;
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

    @Override
    public MobBehaviorState animationState() {
        return MobBehaviorState.FLYING;
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

    @Override
    public boolean shouldContinue(AiContext context) {
        return airborne;
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
        phase = Phase.TAKEOFF;
        airborne = true;
        takeoffNoClipTimer = TAKEOFF_NOCLIP_DURATION;
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        takeoffNoClipTimer = Math.max(0.0f, takeoffNoClipTimer - deltaTime);

        switch (phase) {
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
        airborne = false;
        takeoffNoClipTimer = 0.0f;
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

    private void tickTakeoff(float deltaTime) {
        Vector3f position = goose.getPosition();
        Vector3f velocity = goose.getVelocity();
        velocity.y = FlightSteering.CLIMB_SPEED;

        // Cover ground while climbing rather than going straight up.
        Vector3f toDestination = new Vector3f(destination).sub(position);
        toDestination.y = 0.0f;
        if (toDestination.lengthSquared() > 0.01f) {
            toDestination.normalize();
            velocity.x = toDestination.x * FlightSteering.MAX_FLY_SPEED;
            velocity.z = toDestination.z * FlightSteering.MAX_FLY_SPEED;
        }
        goose.setVelocity(velocity);

        // At cruise, or a ceiling stopped the climb: start cruising so the goose steers out
        // horizontally instead of grinding up into the block.
        if (position.y >= flight.cruiseAltitude() || goose.wasFlightBlockedVertically()) {
            phase = flock != null ? Phase.FORMATION : Phase.FREE_FLY;
        }
    }

    private void tickFormation(float deltaTime) {
        if (flock == null || flock.isEmpty()) {
            phase = Phase.FREE_FLY;
            return;
        }

        if (flock.isLeader(goose)) {
            Vector3f target = flock.getDestination();
            flight.steerToWithAvoidance(target, flock.getCruiseSpeed(), deltaTime);
            if (horizontalDistance(goose.getPosition(), target) < DEST_ARRIVE_XZ) {
                beginFlockLanding();
            }
            return;
        }

        // Follower: sprint to close a big gap, ease toward cruise as the slot nears, and only match
        // the leader's speed once actually lined up — otherwise the gap never closes.
        Vector3f slot = flock.slotTargetFor(goose);
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
        flight.steerToWithAvoidance(destination, FlightSteering.CRUISE_FLY_SPEED, deltaTime);
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
