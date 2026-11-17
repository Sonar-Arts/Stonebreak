package com.stonebreak.mobs.goblin.camp;

import com.stonebreak.blocks.BlockType;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.function.Predicate;

/**
 * Looks for somewhere a goblin band can make camp: a dark surface cave — a roomy covered floor a
 * few blocks under the ground's top, close to where the band is — that the band can walk to from
 * where it stands. Where it can walk is the engine navigation's own answer ({@link CampWalker}: the
 * moves and cells its routes use), so a site picked here is one its pathfinder can reach. When the
 * best caves are all sealed, the best one that can be dug into wins ({@link EntrancePlanner}). The
 * answer is a ranked list: when the real walk to the best site still fails (a route's own search
 * budget is shorter than the scan's), the band moves straight on to the next.
 *
 * <p>Incremental: {@link #step} scans a budget of columns per call, so a search spreads over
 * ticks rather than stalling one. Reads only loaded terrain.
 */
public final class CampSiteFinder {

    /** How far round the band it looks. */
    public static final int RADIUS = 24;
    /** Depth window under the column's top: shallow enough to be a surface cave, deep enough to be dark. */
    static final int MIN_DEPTH = 3;
    static final int MAX_DEPTH = 16;
    /** Covered standable cells needed in the 5x5 round a site: room for a camp. */
    static final int MIN_ROOM = 10;
    /** How far round a site, and how high over it, to look for anything built (NaturalGround). */
    static final int BUILT_REACH = 3;
    static final int BUILT_HEADROOM = 4;
    /** Walkable cells the walk out to the sky may visit before giving up. */
    static final int REACH_BUDGET = 4000;
    /** How far round its start the walk out to the sky may go, in columns. */
    static final int REACH_RADIUS = 64;
    /** Walkable cells the band's own walk round the search area may visit. */
    static final int WALK_BUDGET = 20000;
    /** Best candidates tried for a way out before settling for digging one. */
    static final int CANDIDATES_TRIED = 12;
    /** Plans handed back, best first. */
    static final int PLANS_KEPT = 4;
    /** Alternatives must be at least this far apart — another cave, not the next cell over. */
    static final int ALTERNATIVE_SPACING = 6;

    private record Candidate(Vector3i cell, float score) { }

    private final CampTerrain terrain;
    private final CampWalker walker;
    private final Vector3i start;
    private final int centerX;
    private final int centerZ;
    private final Predicate<Vector3i> allowed;
    private final List<Candidate> candidates = new ArrayList<>();
    private int nextColumn;
    private final int side = RADIUS * 2 + 1;
    private boolean done;
    private List<CampSitePlan> result = List.of();

    /**
     * @param walker  where the band can walk (its leader's routes)
     * @param start   where the band stands (its leader's feet)
     * @param allowed a final say on each site — e.g. not inside another camp's territory
     */
    public CampSiteFinder(CampTerrain terrain, CampWalker walker, Vector3i start, Predicate<Vector3i> allowed) {
        this.terrain = terrain;
        this.walker = walker;
        this.start = start;
        this.centerX = start.x;
        this.centerZ = start.z;
        this.allowed = allowed;
    }

    public boolean isDone() {
        return done;
    }

    /** Plans once done, best first; empty when nowhere suits. */
    public List<CampSitePlan> results() {
        return result;
    }

    /** The best plan once done, or {@code null} when nowhere suits. */
    public CampSitePlan result() {
        return result.isEmpty() ? null : result.get(0);
    }

    /** Scans up to {@code columnBudget} columns; true once the search is finished. */
    public boolean step(int columnBudget) {
        if (done) {
            return true;
        }
        int total = side * side;
        for (int n = 0; n < columnBudget && nextColumn < total; n++, nextColumn++) {
            int x = centerX - RADIUS + nextColumn % side;
            int z = centerZ - RADIUS + nextColumn / side;
            scanColumn(x, z);
        }
        if (nextColumn >= total) {
            result = choose();
            done = true;
        }
        return done;
    }

    private void scanColumn(int x, int z) {
        int top = terrain.columnTop(x, z);
        if (top < 0) {
            return;
        }
        for (int y = top - MIN_DEPTH; y >= top - MAX_DEPTH && y > 0; y--) {
            if (!terrain.standable(x, y, z) || !terrain.covered(x, y, z)) {
                continue;
            }
            int room = room(x, y, z);
            if (room < MIN_ROOM || builtNear(x, y, z)) {
                continue; // too small, or someone's house
            }
            Vector3i cell = new Vector3i(x, y, z);
            if (!allowed.test(cell)) {
                continue;
            }
            float distance = (float) Math.hypot(x - centerX, z - centerZ);
            float depth = Math.min(top - y, 8);
            candidates.add(new Candidate(cell, room + depth - distance * 0.25f));
        }
    }

    /** Covered standable cells in the 5x5 round a cell, a step up or down allowed. */
    private int room(int x, int y, int z) {
        int count = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (terrain.standable(x + dx, y + dy, z + dz) && terrain.covered(x + dx, y + dy, z + dz)) {
                        count++;
                        break;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Whether anything built stands round a cell — the floor, walls and roof a room that size
     * would have. A roofed house is as dark and covered as a cave; its planks give it away.
     */
    private boolean builtNear(int x, int y, int z) {
        for (int dx = -BUILT_REACH; dx <= BUILT_REACH; dx++) {
            for (int dz = -BUILT_REACH; dz <= BUILT_REACH; dz++) {
                for (int dy = -1; dy <= BUILT_HEADROOM; dy++) {
                    BlockType b = terrain.block(x + dx, y + dy, z + dz);
                    if (b != null && !NaturalGround.isNatural(b)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<CampSitePlan> choose() {
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<Vector3i> walkable = walkableFrom(walker, start, RADIUS + 8);
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        List<Vector3i> distinct = new ArrayList<>();
        for (Candidate c : candidates) {
            if (distinct.size() >= CANDIDATES_TRIED) {
                break;
            }
            if (distinct.stream().noneMatch(d -> d.distanceSquared(c.cell()) < ALTERNATIVE_SPACING * ALTERNATIVE_SPACING)) {
                distinct.add(c.cell());
            }
        }
        List<CampSitePlan> plans = new ArrayList<>();
        for (Vector3i cell : distinct) {
            if (plans.size() < PLANS_KEPT && walkable.contains(cell)) {
                plans.add(new CampSitePlan(cell, List.of()));
            }
        }
        for (Vector3i cell : distinct) {
            if (plans.size() >= PLANS_KEPT) {
                break;
            }
            List<Vector3i> dig = walkable.contains(cell) ? null : EntrancePlanner.plan(terrain, cell);
            if (dig != null && (dig.isEmpty() || nearAny(walkable, dig.get(0)))) {
                plans.add(new CampSitePlan(cell, dig)); // the band can reach the top of the stair
            }
        }
        return plans;
    }

    /** Whether some walkable cell lies within a block of {@code cell}. */
    private static boolean nearAny(Set<Vector3i> walkable, Vector3i cell) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (walkable.contains(new Vector3i(cell.x + dx, cell.y + dy, cell.z + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Every cell a goblin standing at {@code from} can walk to, within {@code radius} columns. */
    static Set<Vector3i> walkableFrom(CampWalker walker, Vector3i from, int radius) {
        com.openmason.engine.nav.NavWorld.Reach reach = walker.reach(from, radius, WALK_BUDGET);
        Set<Vector3i> cells = new HashSet<>();
        for (int i = 0; i < reach.count(); i++) {
            cells.add(new Vector3i(reach.x(i), reach.y(i), reach.z(i)));
        }
        return cells;
    }

    /** Whether a goblin standing at {@code from} can walk out to a cell under open sky. */
    static boolean reachesSky(CampWalker walker, CampTerrain terrain, Vector3i from) {
        return pathToSky(walker, terrain, from) != null;
    }

    /**
     * The walk from {@code from} out to the nearest cell under open sky — every cell on the way,
     * {@code from}'s footing first, the open cell last — or {@code null} when there is none within
     * {@link #REACH_BUDGET} walkable cells.
     */
    public static java.util.List<Vector3i> pathToSky(CampWalker walker, CampTerrain terrain, Vector3i from) {
        com.openmason.engine.nav.NavWorld.Reach reach = walker.reach(from, REACH_RADIUS, REACH_BUDGET);
        for (int i = 0; i < reach.count(); i++) {
            if (!terrain.covered(reach.x(i), reach.y(i), reach.z(i))) {
                java.util.LinkedList<Vector3i> path = new java.util.LinkedList<>();
                for (int at = i; at >= 0; at = reach.parent(at)) {
                    path.addFirst(new Vector3i(reach.x(at), reach.y(at), reach.z(at)));
                }
                return path;
            }
        }
        return null;
    }
}
