package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.nav.AgentView;
import com.openmason.engine.nav.AirAgent;
import com.openmason.engine.nav.AirAgentDesc;
import com.openmason.engine.nav.AirProfile;
import com.openmason.engine.nav.NavLimits;
import com.openmason.engine.nav.NavPath;
import com.openmason.engine.nav.NavWorld;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import com.openmason.engine.physics.PhysicsWorld;
import com.stonebreak.mobs.entities.PhysicsFrame;
import com.stonebreak.world.physics.WorldPhysics;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * One flyer's navigation: tell it where it is going and it plans, re-plans and hands back the point
 * to fly at. The airborne counterpart of {@link PathAgent}.
 *
 * <p>Two things make it different from the ground agent, and both come from the same fact — a
 * migration is hundreds of blocks long and the world is only loaded near players.
 *
 * <ul>
 *   <li><b>It routes to a horizon, not to the destination.</b> Each search covers
 *       {@link #PLAN_RANGE} blocks along the bearing, and the horizon walks forward with the bird —
 *       the route is always about the terrain it can actually see.</li>
 *   <li><b>It does not steer.</b> It publishes a target; how a wing gets there is the flyer's own
 *       business. A goose blends the route's waypoint with its own look-ahead and its stuck
 *       recovery, and none of that belongs in a router.</li>
 * </ul>
 *
 * <p>The agent is the engine's ({@link AirAgent}, Open Mason {@code cn_air_agent}, OpenMason #116);
 * this class holds Stonebreak's tuning and feeds it the flyer's position. While a search is queued
 * the target is simply the horizon point, so a bird keeps flying the way it was going.
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
     * How far an air goal is nudged onto real airspace, in cells. Two cells clear a hillside or a
     * treetop the destination happened to land inside; further and the search would be answering a
     * different question than the one it was asked.
     */
    private static final int GOAL_SNAP_CELLS = 2;

    /**
     * Expansion budget. Larger than the ground agent's because a 3D graph fans out in three
     * dimensions rather than two, and because going around a mountain genuinely is a long route —
     * but still bounded, and a partial result already points the right way.
     */
    private static final NavLimits LIMITS = new NavLimits(3000, Float.MAX_VALUE, 1.25f);

    /**
     * Lowest and highest ABSOLUTE world Y a route may use: just above the bedrock floor and just under
     * the build limit (the world spans [MIN_Y, MAX_Y)).
     */
    private static final long FLIGHT_FLOOR = WorldConfiguration.MIN_Y + 1L;
    private static final long FLIGHT_CEILING = WorldConfiguration.MAX_Y - 2L;

    /** A bird: a mild pull toward cruise, climbing costed above descending, unloaded sky at a premium. */
    private static final AirProfile PROFILE = new AirProfile(CELL_SIZE, 0.0f, 0.35f, 0.5f, 0.15f, true, 0.75f,
            FLIGHT_FLOOR, FLIGHT_CEILING);

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
    private final AirAgent agent;
    private final double[] target = new double[3];

    /** The frame origin the agent's positions are relative to (kept equal to the physics world's). */
    private long originX, originY, originZ;

    public AirPathAgent(Entity entity) {
        this.entity = entity;
        PhysicsWorld physics = physicsOf(entity);
        this.originX = PhysicsFrame.originX(physics);
        this.originY = PhysicsFrame.originY(physics);
        this.originZ = PhysicsFrame.originZ(physics);
        this.agent = AirAgent.create(new AirAgentDesc(PROFILE, LIMITS, PLAN_RANGE, REPLAN_TRAVEL,
                REPLAN_INTERVAL_SECONDS, NO_PATH_RETRY_SECONDS, SATURATED_RETRY_SECONDS, WAYPOINT_RADIUS,
                GOAL_RADIUS, GOAL_SNAP_CELLS), originX, originY, originZ,
                entity.getX() - originX, entity.getY() - originY, entity.getZ() - originZ);
    }

    // ── Commands ─────────────────────────────────────────────────────────────

    /**
     * The altitude routes should prefer. Everything else being equal a route holds this height and
     * leaves it only where terrain forces it to, which is what keeps a flock cruising as a flock.
     */
    public void setCruiseAltitude(double altitude) {
        syncFrame();
        agent.setCruiseAltitude(altitude - originY);
    }

    /** Sets the far destination. Cheap to call every tick; re-planning is throttled internally. */
    public void moveTo(Vector3dc goal) {
        syncFrame();
        agent.moveTo(goal.x() - originX, goal.y() - originY, goal.z() - originZ);
    }

    /** Abandons the destination and the route. */
    public void stop() {
        agent.stop(navWorld());
    }

    /** Drops the current route but keeps the destination, forcing a fresh search. */
    public void replan() {
        agent.replan(navWorld());
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public void tick(float deltaTime) {
        syncFrame();
        agent.tick(navWorld(), entity.getX() - originX, entity.getY() - originY, entity.getZ() - originZ, deltaTime);
    }

    /**
     * The point to fly at: the next waypoint of the route, or — while there is no route — the
     * horizon along the bearing to the destination, at cruise altitude.
     */
    public Vector3d steerTarget(Vector3d out) {
        syncFrame();
        agent.steerTarget(entity.getX() - originX, entity.getY() - originY, entity.getZ() - originZ, target);
        return out.set(target[0] + originX, target[1] + originY, target[2] + originZ);
    }

    /** Whether a planned route is being flown right now, as opposed to a bare bearing. */
    public boolean isFollowing() {
        AgentView view = agent.view();
        return view.status() == AgentView.FOLLOWING && view.cursor() < view.pathPoints();
    }

    public Status status() {
        return switch (agent.view().status()) {
            case AgentView.SEARCHING -> Status.SEARCHING;
            case AgentView.FOLLOWING -> Status.FOLLOWING;
            case AgentView.NO_PATH -> Status.NO_PATH;
            default -> Status.IDLE;
        };
    }

    public boolean hasGoal() {
        return agent.view().hasGoal();
    }

    /** The route being flown; never null, possibly {@link NavPath#EMPTY}. For the debug overlay. */
    public NavPath path() {
        return agent.path();
    }

    /** Index of the waypoint currently being flown toward. */
    public int cursor() {
        return agent.view().cursor();
    }

    /** Writes the destination into {@code out}; unchanged when there is none. */
    public Vector3d goal(Vector3d out) {
        AgentView view = agent.view();
        return view.hasGoal() ? out.set(view.goalX() + originX, view.goalY() + originY, view.goalZ() + originZ) : out;
    }

    /** Releases navigation state when the flyer lands or is removed. */
    public void cleanup() {
        stop();
    }

    /** The frame origin {@link #path()} is relative to. */
    public long originX() {
        return originX;
    }

    public long originY() {
        return originY;
    }

    public long originZ() {
        return originZ;
    }

    private static PhysicsWorld physicsOf(Entity entity) {
        World world = entity.getWorld();
        return world == null ? null : WorldPhysics.of(world).engine();
    }

    /** Follows the physics world's frame origin. */
    private void syncFrame() {
        PhysicsWorld physics = physicsOf(entity);
        long ox = PhysicsFrame.originX(physics);
        long oy = PhysicsFrame.originY(physics);
        long oz = PhysicsFrame.originZ(physics);
        if (ox != originX || oy != originY || oz != originZ) {
            agent.setOrigin(ox, oy, oz);
            originX = ox;
            originY = oy;
            originZ = oz;
        }
    }

    private NavWorld navWorld() {
        World world = entity.getWorld();
        PathfindingService service = world == null ? null : world.pathfinding();
        return service == null || service.isClosed() ? null : service.navInFrame();
    }
}
