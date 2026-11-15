package com.stonebreak.mobs.goblin.camp;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import org.joml.Vector3i;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What a camp builds as it grows, one piece at a time: a bedroll for every goblin once it is four
 * strong, then a meat rack by the fire and a palisade of stakes either side of the cave mouth once it
 * is eight. Never on the walk out to the sky, so the camp's way in stays open.
 *
 * <p>Pure planning over {@link CampTerrain}: {@link #next} says which block goes where; the registry
 * places it.
 */
public final class CampBuilder {

    /** Goblins before a camp lays out bedrolls. */
    public static final int BEDROLL_POPULATION = 4;
    /** Goblins before a camp puts up its rack and palisade. */
    public static final int FORT_POPULATION = 8;
    /** How far from the fire the camp builds. */
    static final int RADIUS = 4;

    /** A block to place. */
    public record Placement(Vector3i cell, BlockType block) { }

    private CampBuilder() {
    }

    /**
     * The next piece the camp at {@code fire} should build, or {@code null} when it has all it
     * needs for its {@code population}.
     *
     * @param reserved cells that must stay clear (the chest, the fire)
     */
    public static Placement next(CampTerrain terrain, BlockPos fire, int population, Predicate<Vector3i> reserved) {
        if (population < BEDROLL_POPULATION) {
            return null;
        }
        Vector3i hearth = new Vector3i(fire.x(), fire.y(), fire.z());
        List<Vector3i> walkway = CampSiteFinder.pathToSky(terrain, standingBeside(terrain, hearth));
        Set<Vector3i> keepClear = new HashSet<>();
        if (walkway != null) {
            for (Vector3i c : walkway) {
                keepClear.add(c);
                keepClear.add(new Vector3i(c.x, c.y + 1, c.z));
            }
        }
        if (count(terrain, hearth, BlockType.BEDROLL) < population) {
            Vector3i spot = floorSpot(terrain, hearth, 2, keepClear, reserved);
            return spot != null ? new Placement(spot, BlockType.BEDROLL) : null;
        }
        if (population < FORT_POPULATION) {
            return null;
        }
        if (count(terrain, hearth, BlockType.MEAT_RACK) == 0) {
            Vector3i spot = floorSpot(terrain, hearth, 1, keepClear, reserved);
            if (spot != null) {
                return new Placement(spot, BlockType.MEAT_RACK);
            }
        }
        if (walkway != null && walkway.size() >= 2) {
            for (Vector3i flank : flanks(walkway)) {
                // Where a tunnel surfaces it runs in a cutting: the ground beside it is up to three higher.
                for (int dy = 0; dy <= 3; dy++) {
                    Vector3i stake = new Vector3i(flank.x, flank.y + dy, flank.z);
                    if (empty(terrain, stake) && terrain.standable(stake.x, stake.y, stake.z)
                            && !keepClear.contains(stake) && !reserved.test(stake)) {
                        return new Placement(stake, BlockType.PALISADE);
                    }
                }
            }
        }
        return null;
    }

    /** Where to stand by the fire to start the walk out: the fire's own cell is not walkable. */
    private static Vector3i standingBeside(CampTerrain terrain, Vector3i hearth) {
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int dy = 0; dy >= -1; dy--) {
                if (terrain.standable(hearth.x + d[0], hearth.y + dy, hearth.z + d[1])) {
                    return new Vector3i(hearth.x + d[0], hearth.y + dy, hearth.z + d[1]);
                }
            }
        }
        return hearth;
    }

    /** The cells either side of the walk where it meets the sky: the cave mouth's two posts, inside and out. */
    static List<Vector3i> flanks(List<Vector3i> walkway) {
        Vector3i out = walkway.get(walkway.size() - 1);
        Vector3i in = walkway.get(walkway.size() - 2);
        int dx = out.x - in.x;
        int dz = out.z - in.z;
        return List.of(
                new Vector3i(in.x + dz, in.y, in.z - dx), new Vector3i(in.x - dz, in.y, in.z + dx),
                new Vector3i(out.x + dz, out.y, out.z - dx), new Vector3i(out.x - dz, out.y, out.z + dx));
    }

    /** The nearest covered floor cell round the fire, at least {@code minDistance} from it. */
    private static Vector3i floorSpot(CampTerrain terrain, Vector3i hearth, int minDistance,
                                      Set<Vector3i> keepClear, Predicate<Vector3i> reserved) {
        for (int r = minDistance; r <= RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    for (int dy = 0; dy >= -1; dy--) {
                        Vector3i c = new Vector3i(hearth.x + dx, hearth.y + dy, hearth.z + dz);
                        if (empty(terrain, c) && terrain.standable(c.x, c.y, c.z) && terrain.covered(c.x, c.y, c.z)
                                && !keepClear.contains(c) && !reserved.test(c)) {
                            return c;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Plain air: nothing built there yet (a bedroll is open, but taken). */
    private static boolean empty(CampTerrain terrain, Vector3i c) {
        return terrain.block(c.x, c.y, c.z) == BlockType.AIR;
    }

    /** How many {@code block}s stand round the fire. */
    static int count(CampTerrain terrain, Vector3i hearth, BlockType block) {
        int n = 0;
        for (int dx = -RADIUS - 1; dx <= RADIUS + 1; dx++) {
            for (int dz = -RADIUS - 1; dz <= RADIUS + 1; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    if (terrain.block(hearth.x + dx, hearth.y + dy, hearth.z + dz) == block) {
                        n++;
                    }
                }
            }
        }
        return n;
    }
}
