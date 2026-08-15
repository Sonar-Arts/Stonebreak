package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.wayfind.SearchLimits;
import com.openmason.engine.wayfind.voxel.AirNavProfile;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

/**
 * One flyer's navigation: tell it where it is going and it plans, re-plans and hands back the point
 * to fly at. The airborne counterpart of {@link PathAgent}.
 *
 * <p>Two things make it different from the ground agent, and both come from the same fact — a
 * migration is hundreds of blocks long and the world is only loaded near players.
 *
 * <ul>
 *   <li><b>It routes to a horizon, not to the destination.</b> Searching the whole migration would
 *       be searching mostly unloaded terrain to answer a question that will have changed by the
 *       time the bird gets there. Instead each search covers {@link #PLAN_RANGE} blocks along the
 *       bearing, and the horizon walks forward with the bird — the route is always about the
 *       terrain it can actually see.</li>
 *   <li><b>It does not steer.</b> It publishes a target; how a wing gets there is the flyer's own
 *       business. A goose blends the route's waypoint with its own look-ahead and its stuck
 *       recovery, and none of that belongs in a router.</li>
 * </ul>
 *
 * <p>Like the ground agent it never blocks: while a search is in flight the target is simply the
 * horizon point, so a bird keeps flying the direction it was already going and the route refines it
 * when it lands.
 */
public final class AirPathAgent {

    /** Cell size of the air graph, in blocks. Also the route's clearance margin. */
    public static final int CELL_SIZE = 4;

    /**
     * How far ahead one search plans. Comfortably inside a normal render distance, so the route is
     * planned over terrain that is actually resident, and far enough to see a mountain as a
     * mountain rather than as a wall arriving.
     */
    private static final float PLAN_RANGE = 112.0f;

    /** Distance flown before the horizon has moved enough to be worth re-planning. */
    private static final float REPLAN_TRAVEL = 32.0f;

    /** Minimum time between searches for one flyer, however far it has flown. */
    private static final float REPLAN_INTERVAL_SECONDS = 1.5f;

    /** Backoff after a search that found nothing. */
    private static final float NO_PATH_RETRY_SECONDS = 3.0f;

    /** Backoff when the service is saturated. Short: the load that caused it is transient. */
    private static final float SATURATED_RETRY_SECONDS = 0.5f;

    /** How close counts as passing a waypoint. One cell — the grid's own resolution. */
    private static final float WAYPOINT_RADIUS = CELL_SIZE;

    /** How close counts as arriving at the goal a route was planned for. */
    private static final float GOAL_RADIUS = CELL_SIZE * 1.5f;

    /**
     * Expansion budget. Larger than the ground agent's because a 3D graph fans out in three
     * dimensions rather than two, and because going around a mountain genuinely is a long route —
     * but still bounded, and a partial result already points the right way.
     */
    private static final SearchLimits LIMITS = new SearchLimits(3000, Float.MAX_VALUE, 1.25f);

    /** Lowest and highest world Y a route may use. */
    private static final int FLIGHT_FLOOR = 1;
    private static final int FLIGHT_CEILING = WorldConfiguration.WORLD_HEIGHT - 2;

    public enum Status {
        /** No destination asked for. */
        IDLE,
        /** A destination is set and a search is in flight, or waiting on a cooldown. */
        SEARCHING,
        /** Flying a route. */
        FOLLOWING,
        /** The route ran out with the destination still ahead — flying the bearing instead. */
        NO_PATH
    }

    private final Entity entity;

    private final Vector3f destination = new Vector3f();
    private boolean hasGoal;
    private float cruiseAltitude;

    private Path path = Path.EMPTY;
    private int cursor;

    private PathRequest pending;
    private PathfindingService pendingService;

    private float searchCooldown;
    private final Vector3f planOrigin = new Vector3f();

    private Status status = Status.IDLE;

    // Scratch, reused every tick.
    private final Vector3f scratch = new Vector3f();
    private final Vector3f horizon = new Vector3f();

    public AirPathAgent(Entity entity) {
        this.entity = entity;
        this.cruiseAltitude = entity.getPosition().y;
    }

    // ── Commands ─────────────────────────────────────────────────────────────

    /**
     * The altitude routes should prefer. Everything else being equal a route holds this height and
     * leaves it only where terrain forces it to, which is what keeps a flock cruising as a flock.
     */
    public void setCruiseAltitude(float altitude) {
        this.cruiseAltitude = altitude;
    }

    /** Sets the far destination. Cheap to call every tick; re-planning is throttled internally. */
    public void moveTo(Vector3f goal) {
        if (!hasGoal) {
            hasGoal = true;
            status = Status.SEARCHING;
            searchCooldown = 0.0f;
        }
        destination.set(goal);
    }

    /** Abandons the destination and the route. */
    public void stop() {
        hasGoal = false;
        clearPath();
        cancelPending();
        status = Status.IDLE;
    }

    /** Drops the current route but keeps the destination, forcing a fresh search. */
    public void replan() {
        clearPath();
        cancelPending();
        searchCooldown = 0.0f;
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public void tick(float deltaTime) {
        searchCooldown = Math.max(0.0f, searchCooldown - deltaTime);
        collectResult();
        if (!hasGoal) {
            return;
        }
        advanceCursor();
        requestPathIfNeeded();
    }

    /**
     * The point to fly at: the next waypoint of the route, or — while there is no route — the
     * horizon along the bearing to the destination, at cruise altitude.
     */
    public Vector3f steerTarget(Vector3f out) {
        if (cursor < path.size()) {
            return path.waypoint(cursor, out);
        }
        return horizonGoal(out);
    }

    /** Whether a planned route is being flown right now, as opposed to a bare bearing. */
    public boolean isFollowing() {
        return status == Status.FOLLOWING && cursor < path.size();
    }

    public Status status() {
        return status;
    }

    public boolean hasGoal() {
        return hasGoal;
    }

    /** The route being flown; never null, possibly {@link Path#EMPTY}. For the debug overlay. */
    public Path path() {
        return path;
    }

    /** Index of the waypoint currently being flown toward. */
    public int cursor() {
        return cursor;
    }

    /** Writes the destination into {@code out}; unchanged when there is none. */
    public Vector3f goal(Vector3f out) {
        return hasGoal ? out.set(destination) : out;
    }

    /** Releases navigation state when the flyer lands or is removed. */
    public void cleanup() {
        stop();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Where this search aims: the destination itself when it is close enough to plan all the way
     * to, otherwise a point {@link #PLAN_RANGE} along the bearing at cruise altitude.
     */
    private Vector3f horizonGoal(Vector3f out) {
        Vector3f position = entity.getPosition();
        float dx = destination.x - position.x;
        float dz = destination.z - position.z;
        float distance = (float) Math.sqrt(dx * dx + dz * dz);

        if (distance <= PLAN_RANGE) {
            return out.set(destination);
        }
        if (distance < 1e-4f) {
            return out.set(position.x, cruiseAltitude, position.z);
        }
        return out.set(position.x + dx / distance * PLAN_RANGE,
                cruiseAltitude,
                position.z + dz / distance * PLAN_RANGE);
    }

    /** Adopts a finished search, or drops one that has become irrelevant. */
    private void collectResult() {
        if (pending == null) {
            return;
        }

        PathfindingService service = service();
        if (service != pendingService || service == null || service.isClosed()) {
            // The world was swapped underneath us; whatever comes back describes a world this flyer
            // no longer lives in.
            cancelPending();
            return;
        }
        if (!pending.isDone()) {
            return;
        }

        Path result = pending.result();
        pending = null;
        pendingService = null;

        if (!hasGoal) {
            return;
        }
        if (result == null || result.isEmpty()) {
            // Nothing found. The bearing is still a perfectly good direction to fly meanwhile.
            status = Status.NO_PATH;
            searchCooldown = Math.max(searchCooldown, NO_PATH_RETRY_SECONDS);
            return;
        }

        path = result;
        cursor = 0;
        planOrigin.set(entity.getPosition());
        // A route starts at the cell the flyer was in when it asked, and by now the flyer has moved
        // on a little. Dropping the waypoints already passed stops a bird doubling back on itself
        // the moment a search lands.
        advanceCursor();
        status = cursor < path.size() ? Status.FOLLOWING : Status.NO_PATH;
    }

    private void requestPathIfNeeded() {
        if (pending != null || searchCooldown > 0.0f) {
            return;
        }

        boolean needsRoute = path.isEmpty()
                || cursor >= path.size()
                || entity.getPosition().distance(planOrigin) > REPLAN_TRAVEL
                || !path.isComplete();
        if (!needsRoute) {
            return;
        }

        PathfindingService service = service();
        if (service == null) {
            status = Status.NO_PATH;
            searchCooldown = NO_PATH_RETRY_SECONDS;
            return;
        }

        horizonGoal(horizon);
        AirNavProfile profile = new AirNavProfile(CELL_SIZE, cruiseAltitude,
                0.35f, 0.5f, 0.15f, true, 0.75f, FLIGHT_FLOOR, FLIGHT_CEILING);

        PathRequest request = service.submitAir(entity.getPosition(), horizon,
                GOAL_RADIUS, profile, LIMITS);
        if (request == null) {
            searchCooldown = SATURATED_RETRY_SECONDS;
            return;
        }
        pending = request;
        pendingService = service;
        searchCooldown = REPLAN_INTERVAL_SECONDS;
        if (path.isEmpty()) {
            status = Status.SEARCHING;
        }
    }

    private void advanceCursor() {
        Vector3f position = entity.getPosition();
        while (cursor < path.size()
                && position.distance(path.x(cursor), path.y(cursor), path.z(cursor))
                        < WAYPOINT_RADIUS) {
            cursor++;
        }
        if (cursor >= path.size() && status == Status.FOLLOWING) {
            status = Status.NO_PATH; // route spent; the bearing carries on until the next one lands
        }
    }

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

    private PathfindingService service() {
        World world = entity.getWorld();
        return world == null ? null : world.pathfinding();
    }
}
