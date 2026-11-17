package com.stonebreak.mobs.goblin.camp;

import com.openmason.engine.nav.GroundProfile;
import com.openmason.engine.nav.NavWorld;
import com.stonebreak.mobs.entities.ai.nav.PathfindingService;
import com.stonebreak.world.World;
import org.joml.Vector3i;

/**
 * Where a goblin can walk, asked of the engine's navigation ({@link NavWorld#reachGround}, Open
 * Mason {@code cn_reach_ground}): every cell its routes could reach from where it stands, by the
 * same moves and over the same cells (the physics cache) as the route it will then really walk —
 * so a camp the band picks is a camp its pathfinder can take it to.
 */
@FunctionalInterface
public interface CampWalker {

    /** No world to walk in: nothing reachable. */
    NavWorld.Reach NOTHING = new NavWorld.Reach(0, new int[0], new int[0], new float[0], new float[0]);

    /** How far below the given feet the walk looks for where the goblin really stands. */
    int FOOTING_DROP = 2;

    /**
     * Every cell a goblin standing at {@code feet} (or just below: a position's cell can be the air
     * over its floor) can walk to within {@code radius} columns, cheapest first, at most
     * {@code maxCells} of them.
     */
    NavWorld.Reach reach(Vector3i feet, int radius, int maxCells);

    /** A goblin with route profile {@code goblin} walking {@code world}. Simulation thread. */
    static CampWalker of(World world, GroundProfile goblin) {
        return (feet, radius, maxCells) -> {
            PathfindingService service = world.pathfinding();
            if (service == null || service.isClosed()) {
                return NOTHING;
            }
            return service.nav().reachGround(feet.x + 0.5f, feet.y, feet.z + 0.5f, FOOTING_DROP, 0, radius,
                    maxCells, Float.MAX_VALUE, goblin);
        };
    }
}
