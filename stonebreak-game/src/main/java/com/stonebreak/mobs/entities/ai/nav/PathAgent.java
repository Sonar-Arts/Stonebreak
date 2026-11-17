package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.nav.AgentApply;
import com.openmason.engine.nav.AgentBody;
import com.openmason.engine.nav.AgentView;
import com.openmason.engine.nav.GroundAgent;
import com.openmason.engine.nav.GroundAgentDesc;
import com.openmason.engine.nav.GroundProfile;
import com.openmason.engine.nav.NavLimits;
import com.openmason.engine.nav.NavPath;
import com.openmason.engine.nav.NavWorld;
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
 * a search or a thread.
 *
 * <p>The agent is the engine's ({@link GroundAgent}, Open Mason {@code cn_ground_agent}, OpenMason
 * #116), searching the world's {@link PathfindingService} and steering through the mob's
 * {@link Steering}. This class is the game's side: Stonebreak's tuning, the mob's body each tick, and
 * applying what the agent decided.
 *
 * <p>Searches run on the service's step at the end of the world tick, so there is always a tick
 * between asking and having a route. The mob holds position through it rather than blundering off in
 * the goal's rough direction — a wrong first step is worse than a late one.
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

    /** The route planned a rise the step cannot make: jump once within this of the waypoint. */
    private static final float JUMP_RISE_MARGIN = 0.05f;
    private static final float JUMP_APPROACH_BLOCKS = 1.5f;

    /**
     * Expansion budget per search. Roughly a 30-block route through cluttered ground; beyond it the
     * partial result is a better answer than a longer search, because the world will have moved on.
     */
    private static final NavLimits LIMITS = new NavLimits(600, Float.MAX_VALUE, 1.1f);

    /** How far up and down a start or goal position is snapped onto a standable surface. */
    private static final int SNAP_DOWN = 4;
    private static final int SNAP_UP = 2;

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

    private static final Status[] STATUSES = Status.values();

    private final LivingEntity entity;
    private final GroundProfile profile;
    private final Steering steering;
    private final GroundAgent agent;

    private final AgentBody body = new AgentBody();
    private final AgentApply apply = new AgentApply();

    public PathAgent(LivingEntity entity, Steering steering) {
        this(entity, steering, NavProfiles.forEntity(entity));
    }

    public PathAgent(LivingEntity entity, Steering steering, GroundProfile profile) {
        this.entity = entity;
        this.steering = steering;
        this.profile = profile;
        Vector3f position = entity.getPosition();
        this.agent = GroundAgent.create(new GroundAgentDesc(profile, LIMITS, SNAP_DOWN, SNAP_UP,
                REPATH_INTERVAL_SECONDS, NO_PATH_RETRY_SECONDS, SATURATED_RETRY_SECONDS, GOAL_DRIFT_BLOCKS,
                WAYPOINT_RADIUS, WAYPOINT_VERTICAL_SLACK, STUCK_SPEED_BLOCKS_PER_SECOND, STUCK_SECONDS,
                JUMP_RISE_MARGIN, JUMP_APPROACH_BLOCKS), position.x, position.y, position.z);
    }

    public Steering steering() {
        return steering;
    }

    public GroundProfile profile() {
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
        agent.moveTo(goal.x, goal.y, goal.z, goalRadius, speedMultiplier);
    }

    /** Abandons the destination and the route, and stops the mob where it stands. */
    public void stop() {
        agent.stop(navWorld(), apply);
        Steering.applyTo(entity, apply);
    }

    /** Drops the current route but keeps the destination, forcing a fresh search. */
    public void replan() {
        agent.replan(navWorld());
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    public void tick(float deltaTime) {
        agent.tick(steering.engine(), navWorld(), Steering.physicsOf(entity), Steering.bodyOf(entity, body),
                deltaTime, apply);
        Steering.applyTo(entity, apply);
    }

    // ── State ────────────────────────────────────────────────────────────────

    public Status status() {
        return STATUSES[agent.view().status()];
    }

    /** Whether the agent is walking a route right now. */
    public boolean isFollowing() {
        return status() == Status.FOLLOWING;
    }

    /**
     * Whether the agent has finished with its destination one way or another — arrived, gave up, or
     * got wedged. Behaviours use this to know when to pick a new goal.
     */
    public boolean isSettled() {
        Status status = status();
        return status == Status.ARRIVED || status == Status.NO_PATH || status == Status.STUCK;
    }

    public boolean hasGoal() {
        return agent.view().hasGoal();
    }

    /** Distance from the mob to its destination, or {@link Float#MAX_VALUE} when it has none. */
    public float distanceToGoal() {
        AgentView view = agent.view();
        return view.hasGoal() ? entity.getPosition().distance(view.goalX(), view.goalY(), view.goalZ())
                : Float.MAX_VALUE;
    }

    // ── Debug ────────────────────────────────────────────────────────────────

    /** The route being followed; never null, possibly {@link NavPath#EMPTY}. For the debug overlay. */
    public NavPath path() {
        return agent.path();
    }

    /** Index of the waypoint currently being walked toward. */
    public int cursor() {
        return agent.view().cursor();
    }

    /** Writes the destination into {@code out}; unchanged when there is none. */
    public Vector3f goal(Vector3f out) {
        AgentView view = agent.view();
        return view.hasGoal() ? out.set(view.goalX(), view.goalY(), view.goalZ()) : out;
    }

    /** Releases navigation state when the entity is removed. */
    public void cleanup() {
        stop();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** The service of the world the mob is in now, or null (no world, or it was cleaned up). */
    private NavWorld navWorld() {
        World world = entity.getWorld();
        PathfindingService service = world == null ? null : world.pathfinding();
        return service == null || service.isClosed() ? null : service.nav();
    }
}
