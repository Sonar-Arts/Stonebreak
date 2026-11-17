package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.nav.NavWorld;
import com.openmason.engine.physics.PhysicsWorld;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.physics.WorldPhysics;

/**
 * A world's mob path searching: the engine's search service ({@link NavWorld}, Open Mason
 * {@code cn_world}, OpenMason #116) over the world's physics cache.
 *
 * <p><b>What navigation reads.</b> The cells routes are planned over are the physics cache's own: a
 * cell is solid when its shape collides with mobs ({@link WorldPhysics#MOB_MASK}), its standing
 * height is the shape's real top (a snow layer, a stair tread, a stalagmite tier), water is a water
 * fluid at its surface height, and unloaded chunks and anything outside the world column are unknown
 * — impassable, never generated. So a route and the mob walking it can never disagree about a block,
 * and an edit reaches navigation through the same invalidation that keeps physics honest.
 *
 * <p><b>When searches run.</b> Agents queue them; {@link #step} runs them on the world's simulation
 * thread at the end of each entity update, at most {@link #STEP_EXPANSION_BUDGET} expansions per
 * tick (a started search always finishes within its own limit). A route asked for in one tick is
 * adopted the next. Over {@link #DEFAULT_MAX_IN_FLIGHT} queued searches the service refuses more, and
 * a refused agent simply asks again after its own back-off, so load sheds instead of piling up.
 *
 * <p>The service belongs to a {@link World}, and {@link #close} (from {@code World.cleanup}, before
 * the world's physics goes) drops everything queued — a world swap cannot leave a result for an
 * entity that no longer lives there.
 */
public final class PathfindingService implements AutoCloseable {

    /**
     * Queued searches allowed. Generous: at the spawner's density a full render distance holds a few
     * hundred mobs, and only the ones actively repathing are ever in here at once.
     */
    public static final int DEFAULT_MAX_IN_FLIGHT = 64;

    /**
     * Expansions one {@link #step} may spend: about forty full ground searches or eight full air
     * searches — a pathological cave system cannot stretch a tick much past that.
     */
    public static final int STEP_EXPANSION_BUDGET = 24_000;

    private final NavWorld nav;

    private PathfindingService(NavWorld nav) {
        this.nav = nav;
    }

    /** The service of {@code world}, over its physics. Call on the world's simulation thread. */
    public static PathfindingService forWorld(World world) {
        return over(WorldPhysics.of(world).engine(), WorldConfiguration.WORLD_HEIGHT);
    }

    /**
     * A service over a physics world filled the way {@link WorldPhysics} fills one (its categories),
     * for a column {@code [0, worldHeight)}. Tests build one over a hand-made physics world.
     */
    public static PathfindingService over(PhysicsWorld physics, int worldHeight) {
        return new PathfindingService(NavWorld.create(physics, WorldPhysics.MOB_MASK, 0, WorldPhysics.WATER,
                0, worldHeight, DEFAULT_MAX_IN_FLIGHT));
    }

    /** The engine service; agents search through it. */
    public NavWorld nav() {
        return nav;
    }

    /** Runs this tick's queued searches. Call once per world tick, after the entities update. */
    public void step() {
        nav.step(STEP_EXPANSION_BUDGET);
    }

    /** Snapshot of service load, for the debug overlay. Any thread. */
    public Stats stats() {
        NavWorld.Stats s = nav.stats();
        return new Stats(s.submitted(), s.rejected(), s.completed(), s.failed(), s.partial(), s.inFlight(),
                s.averageMicros());
    }

    public record Stats(long submitted, long rejected, long completed, long failed, long partial,
                        int inFlight, long averageMicros) {
    }

    /** Drops every queued search and result. Idempotent. */
    @Override
    public void close() {
        nav.close();
    }

    public boolean isClosed() {
        return nav.isClosed();
    }
}
