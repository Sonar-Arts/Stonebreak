package com.stonebreak.mobs.goblin.camp;

import com.stonebreak.blocks.BlockType;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Where a band with no cave or overhang to walk into digs a home of its own: a den scraped into a
 * soil hillside — a short tunnel, one wide and two tall, into a 3x3 room two tall, the fire in the
 * middle. A soil hollow that is already part dug out is just a den with less to dig, so the
 * cheapest plan wins and a hollow is widened before a fresh hillside is opened.
 *
 * <p>Every cell to clear must be soil ({@link NaturalGround#isDiggable}) or already open; every
 * cell must keep a solid floor under it and a ground roof over it ({@link NaturalGround#isGroundRoof}),
 * so the den is covered end to end; and nothing dug may sit beside water, so it never floods.
 * Pure planning over {@link CampTerrain}.
 */
public final class DenPlanner {

    /** Tunnel lengths tried before the room: a thin bank needs one, a fat hillside two. */
    static final int MAX_TUNNEL = 2;
    /** Half the room's width: a 3x3 room. */
    static final int ROOM_HALF = 1;

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] NEIGHBOURS = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};

    private DenPlanner() {
    }

    /**
     * Dens a goblin standing on any of {@code doorsteps} could dig, cheapest first.
     *
     * @param doorsteps where the band can stand (its walk): each is tried as the den's way in
     * @param allowed   a final say on each den's fire cell
     */
    public static List<CampSitePlan> plan(CampTerrain terrain, Iterable<Vector3i> doorsteps,
                                          Predicate<Vector3i> allowed) {
        List<CampSitePlan> plans = new ArrayList<>();
        for (Vector3i door : doorsteps) {
            for (int[] d : DIRECTIONS) {
                // Cheap first test: a den starts under a roof one step in, so open ground is skipped.
                if (!terrain.roofed(door.x + d[0], door.y, door.z + d[1])) {
                    continue;
                }
                for (int tunnel = 1; tunnel <= MAX_TUNNEL; tunnel++) {
                    CampSitePlan den = den(terrain, door, d[0], d[1], tunnel);
                    if (den != null && allowed.test(den.site())) {
                        plans.add(den);
                        break; // the shorter tunnel is the cheaper den
                    }
                }
            }
        }
        plans.sort(Comparator.comparingInt(p -> p.dig().size()));
        return plans;
    }

    /**
     * The den dug from {@code door} along {@code (dx, dz)} with a {@code tunnel}-long passage, or
     * {@code null} when the ground there will not take one.
     */
    static CampSitePlan den(CampTerrain terrain, Vector3i door, int dx, int dz, int tunnel) {
        List<Vector3i> cells = new ArrayList<>();
        for (int k = 1; k <= tunnel; k++) {
            cells.add(new Vector3i(door.x + dx * k, door.y, door.z + dz * k));
        }
        // The room's rows, nearest the tunnel first, the middle of each row before its sides.
        int px = -dz;
        int pz = dx;
        for (int row = 1; row <= 2 * ROOM_HALF + 1; row++) {
            for (int side : new int[] {0, 1, -1}) {
                int k = tunnel + row;
                cells.add(new Vector3i(door.x + dx * k + px * side, door.y, door.z + dz * k + pz * side));
            }
        }
        List<Vector3i> dig = new ArrayList<>();
        for (Vector3i c : cells) {
            if (!terrain.solid(c.x, c.y - 1, c.z) || !terrain.roofed(c.x, c.y, c.z)) {
                return null; // a den needs ground underfoot and overhead the whole way
            }
            for (int h = 1; h >= 0; h--) { // the head cell first: a goblin digs down to the floor
                int y = c.y + h;
                BlockType b = terrain.block(c.x, y, c.z);
                if (b == null || b == BlockType.WATER || !NaturalGround.isNatural(b)) {
                    return null; // unloaded, flooded, or someone's house
                }
                if (b.isSolid()) {
                    if (!NaturalGround.isDiggable(b)) {
                        return null; // rock: goblins dig only soil
                    }
                    if (besideWater(terrain, c.x, y, c.z)) {
                        return null; // opening it would let the water in
                    }
                    dig.add(new Vector3i(c.x, y, c.z));
                }
            }
        }
        Vector3i hearth = new Vector3i(door.x + dx * (tunnel + ROOM_HALF + 1), door.y,
                door.z + dz * (tunnel + ROOM_HALF + 1));
        return new CampSitePlan(hearth, dig);
    }

    private static boolean besideWater(CampTerrain terrain, int x, int y, int z) {
        for (int[] n : NEIGHBOURS) {
            if (terrain.block(x + n[0], y + n[1], z + n[2]) == BlockType.WATER) {
                return true;
            }
        }
        return false;
    }
}
