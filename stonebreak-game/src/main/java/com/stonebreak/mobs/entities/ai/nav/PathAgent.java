package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.wayfind.SearchLimits;
import com.openmason.engine.wayfind.voxel.NavProfile;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * One mob's navigation: ask it to go somewhere and it plans, re-plans, walks and gives up on its
 * own.
 *
 * <p>This is the whole interface behaviours use. A behaviour calls {@link #moveTo} with wherever it
 * wants the mob — every tick if the target is moving — and {@link #tick} does the rest: throttling
 * searches, adopting results when they arrive, advancing along waypoints, jumping ledges the route
 * planned for, noticing when the mob is wedged, and stopping when it arrives. Behaviours never see
 * a {@link Path}, a search, or a thread.
 *
 * <p>Searches are asynchronous, so there is always a window between asking and having a route. The
 * mob holds position through it rather than blundering off in the goal's rough direction — a wrong
 * first step is worse than a late one, and at a fraction of a second nobody sees the pause.
 */
public final class PathAgent {

    /** Minimum time between searches for one agent, however often the goal moves. */
    private static final float REPATH_INTERVAL_SECONDS = 1.0f;

    /** Backoff after a search that found nothing; longer, because retrying rarely helps. */
    private static final float NO_PATH_RETRY_SECONDS = 3.0f;

    /** Backoff when the service is saturated. Short: the load that caused it is transient. */
    private static final float SATURATED_RETRY_SECONDS = 0.5f;

    /** How far the goal may drift from the one a route was planned for before replanning. */
    private static final float GOAL_DRIFT_BLOCKS = 2.0f;

    /** Horizontal distance at which a waypoint counts as reached. */
    private static final float WAYPOINT_RADIUS = 0.7f;

    /** Vertical slack allowed when passing a waypoint — a mob mid-hop is briefly above its route. */
    private static final float WAYPOINT_VERTICAL_SLACK = 1.6f;

    /** Below this speed, a mob that is trying to move is considered wedged. */
    private static final float STUCK_SPEED_BLOCKS_PER_SECOND = 0.15f;
    private static final float STUCK_SECONDS = 1.5f;

    /**
     * Expansion budget per search. Roughly a 30-block route through cluttered ground; beyond it the
     * partial result is a better answer than a longer search, because the world will have moved on.
     */
    private static final SearchLimits LIMITS = new SearchLimits(600, Float.MAX_VALUE, 1.1f);

    public enum Status {
        /** No destination asked for. */
        IDLE,
        /** A destination is set and a search is in flight. */
        SEARCHING,
        /** Walking a route. */
        FOLLOWING,
        /** Reached the end of the route. */
        ARRIVED,
        /** The search could not find a way there; the destination is unreachable for now. */
        NO_PATH,
        /** Wedged: the mob is trying to move and is not moving. */
        STUCK
    }

    private final LivingEntity entity;
    private final NavProfile profile;
    private final Steering steering;

    private final Vector3f desiredGoal = new Vector3f();
    private final Vector3f plannedGoal = new Vector3f();
    private boolean hasGoal;
    private float goalRadius = 1.0f;
    private float speedMultiplier = 1.0f;

    private Path path = Path.EMPTY;
    private int cursor;

    private PathRequest pending;
    private PathfindingService pendingService;

    private float searchCooldown;
    private float stuckTimer;
    private final Vector3f previousPosition = new Vector3f();

    private Status status = Status.IDLE;

    // Scratch, reused every tick — this runs for every mob, every tick.
    private final Vector3f scratch = new Vector3f();
    private final Vector3f heading = new Vector3f();

    public PathAgent(LivingEntity entity, Steering steering) {
        this(entity, steering, NavProfiles.forEntity(entity));
    }

    public PathAgent(LivingEntity entity, Steering steering, NavProfile profile) {
        this.entity = entity;
        this.steering = steering;
        this.profile = profile;
        this.previousPosition.set(entity.getPosition());
    }

    public Steering steering() {
        return steering;
    }

    public NavProfile profile() {
        return profile;
    }

    // ── Commands ─────────────────────────────────────────────────────────────

    /**
     * Sets where the mob should go. Safe and cheap to call every tick with a moving target — the
     * agent replans only when the goal has actually drifted, or when it has no route to follow.
     *
     * @param goalRadius      how close counts as arrival; at least 1 for a moving target
     * @param speedMultiplier scales the mob's base move speed while walking this route
     */
    public void moveTo(Vector3f goal, float goalRadius, float speedMultiplier) {
        this.desiredGoal.set(goal);
        this.goalRadius = goalRadius;
        this.speedMultiplier = speedMultiplier;
        if (!hasGoal) {
            hasGoal = true;
            status = Status.SEARCHING;
        }
    }

    /** Abandons the destination and the route, and stops the mob where it stands. */
    public void stop() {
        hasGoal = false;
        clearPath();
        cancelPending();
        stuckTimer = 0.0f;
        status = Status.IDLE;
        steering.stopMoving();
    }

    /** Drops the current route but keeps the destination, forcing a fresh search. */
    public void replan() {
        clearPath();
        cancelPending();
        searchCooldown = 0.0f;
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public void tick(float deltaTime) {
        steering.tick(deltaTime);
        searchCooldown = Math.max(0.0f, searchCooldown - deltaTime);

        collectResult();

        if (!hasGoal) {
            return;
        }

        requestPathIfNeeded();
        followPath(deltaTime);
        updateStuck(deltaTime);
    }

    /** Adopts a finished search, or drops one that has become irrelevant. */
    private void collectResult() {
        if (pending == null) {
            return;
        }

        PathfindingService service = service();
        if (service != pendingService || service == null || service.isClosed()) {
            // The world was swapped underneath us; whatever comes back describes a world this mob
            // no longer lives in.
            cancelPending();
            return;
        }
        if (!pending.isDone()) {
            return;
        }

        Path result = pending.result();
        pending.goal(scratch);
        pending = null;
        pendingService = null;

        if (!hasGoal) {
            return;
        }
        if (scratch.distance(desiredGoal) > GOAL_DRIFT_BLOCKS) {
            // The target moved while we searched; the route is already wrong.
            searchCooldown = 0.0f;
            return;
        }
        if (result == null || result.isEmpty()) {
            status = Status.NO_PATH;
            searchCooldown = NO_PATH_RETRY_SECONDS;
            return;
        }

        path = result;
        cursor = 0;
        plannedGoal.set(scratch);
        stuckTimer = 0.0f;
        status = Status.FOLLOWING;
    }

    private void requestPathIfNeeded() {
        if (pending != null || searchCooldown > 0.0f) {
            return;
        }
        boolean needsRoute = path.isEmpty()
                || cursor >= path.size()
                || plannedGoal.distance(desiredGoal) > GOAL_DRIFT_BLOCKS;
        if (!needsRoute) {
            return;
        }

        PathfindingService service = service();
        if (service == null) {
            status = Status.NO_PATH;
            searchCooldown = NO_PATH_RETRY_SECONDS;
            return;
        }

        PathRequest request = service.submit(feet(scratch), desiredGoal, goalRadius, profile, LIMITS);
        if (request == null) {
            searchCooldown = SATURATED_RETRY_SECONDS;
            return;
        }
        pending = request;
        pendingService = service;
        searchCooldown = REPATH_INTERVAL_SECONDS;
        if (path.isEmpty()) {
            status = Status.SEARCHING;
        }
    }

    private void followPath(float deltaTime) {
        if (path.isEmpty()) {
            steering.stopMoving(); // hold position until a route arrives
            return;
        }
        if (cursor >= path.size()) {
            arrive();
            return;
        }

        Vector3f position = entity.getPosition();
        float feetY = position.y - entity.getLegHeight();

        float dx = path.x(cursor) - position.x;
        float dz = path.z(cursor) - position.z;
        float horizontal = (float) Math.sqrt(dx * dx + dz * dz);

        if (horizontal < WAYPOINT_RADIUS
                && Math.abs(path.y(cursor) - feetY) < WAYPOINT_VERTICAL_SLACK) {
            cursor++;
            if (cursor >= path.size()) {
                arrive();
                return;
            }
            dx = path.x(cursor) - position.x;
            dz = path.z(cursor) - position.z;
            horizontal = (float) Math.sqrt(dx * dx + dz * dz);
        }

        if (horizontal < 1e-4f) {
            steering.stopMoving();
            return;
        }

        heading.set(dx / horizontal, 0.0f, dz / horizontal);
        steering.steerAlong(heading, speedMultiplier, deltaTime, true);

        // The route planned a rise the auto-step cannot make, so it planned a jump. Take it on the
        // approach rather than at the wall, or the hop starts with no room to carry the body over.
        if (path.y(cursor) - feetY > profile.maxStepUp() + 0.05f && horizontal < 1.5f) {
            steering.requestJump();
        }
        status = Status.FOLLOWING;
    }

    private void arrive() {
        clearPath();
        steering.stopMoving();
        status = Status.ARRIVED;
    }

    /**
     * Notices a mob that wants to move and is not moving — pinned by another mob, wedged on
     * geometry the route did not model — and forces a replan rather than letting it shuffle
     * against a wall indefinitely.
     */
    private void updateStuck(float deltaTime) {
        Vector3f position = entity.getPosition();
        if (status != Status.FOLLOWING) {
            previousPosition.set(position);
            stuckTimer = 0.0f;
            return;
        }

        float moved = position.distance(previousPosition);
        previousPosition.set(position);

        if (moved < STUCK_SPEED_BLOCKS_PER_SECOND * deltaTime) {
            stuckTimer += deltaTime;
            if (stuckTimer >= STUCK_SECONDS) {
                stuckTimer = 0.0f;
                clearPath();
                searchCooldown = 0.0f;
                status = Status.STUCK;
            }
        } else {
            stuckTimer = 0.0f;
        }
    }

    // ── State ────────────────────────────────────────────────────────────────

    public Status status() {
        return status;
    }

    /** Whether the agent is walking a route right now. */
    public boolean isFollowing() {
        return status == Status.FOLLOWING;
    }

    /**
     * Whether the agent has finished with its destination one way or another — arrived, gave up, or
     * got wedged. Behaviours use this to know when to pick a new goal.
     */
    public boolean isSettled() {
        return status == Status.ARRIVED || status == Status.NO_PATH || status == Status.STUCK;
    }

    public boolean hasGoal() {
        return hasGoal;
    }

    /** Distance from the mob to its destination, or {@link Float#MAX_VALUE} when it has none. */
    public float distanceToGoal() {
        return hasGoal ? entity.getPosition().distance(desiredGoal) : Float.MAX_VALUE;
    }

    // ── Debug ────────────────────────────────────────────────────────────────

    /** The route being followed; never null, possibly {@link Path#EMPTY}. For the debug overlay. */
    public Path path() {
        return path;
    }

    /** Index of the waypoint currently being walked toward. */
    public int cursor() {
        return cursor;
    }

    /** Writes the destination into {@code out}; unchanged when there is none. */
    public Vector3f goal(Vector3f out) {
        return hasGoal ? out.set(desiredGoal) : out;
    }

    /** Releases navigation state when the entity is removed. */
    public void cleanup() {
        stop();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void clearPath() {
        path = Path.EMPTY;
        cursor = 0;
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel();
            pending = null;
            pendingService = null;
        }
    }

    /** The mob's foot position — its origin sits at the top of its legs. */
    private Vector3f feet(Vector3f out) {
        Vector3f position = entity.getPosition();
        return out.set(position.x, position.y - entity.getLegHeight(), position.z);
    }

    private PathfindingService service() {
        World world = entity.getWorld();
        return world == null ? null : world.pathfinding();
    }
}
