package com.stonebreak.mobs.goblin.camp;

import com.stonebreak.blocks.BlockType;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans a way in to a cave that has none: a staircase rising one block per step from the site,
 * straight out in one of the four directions, until it comes up under open sky. Each step clears
 * three cells — feet, head, and the headroom to step up into the next. Directions that would dig
 * into water, bedrock, unloaded ground or anything built ({@link NaturalGround}) are refused; the
 * shortest of the rest wins.
 */
public final class EntrancePlanner {

    /** Longest staircase a band will dig. */
    public static final int MAX_STEPS = 16;

    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private EntrancePlanner() {
    }

    /** Cells to clear, surface first; {@code null} when no direction surfaces within {@link #MAX_STEPS}. */
    public static List<Vector3i> plan(CampTerrain terrain, Vector3i site) {
        List<Vector3i> best = null;
        for (int[] d : DIRECTIONS) {
            List<Vector3i> cells = staircase(terrain, site, d[0], d[1]);
            if (cells != null && (best == null || cells.size() < best.size())) {
                best = cells;
            }
        }
        return best;
    }

    private static List<Vector3i> staircase(CampTerrain terrain, Vector3i site, int dx, int dz) {
        List<Vector3i> fromSite = new ArrayList<>();
        for (int k = 1; k <= MAX_STEPS; k++) {
            int x = site.x + dx * k;
            int y = site.y + k;
            int z = site.z + dz * k;
            for (int h = 0; h < 3; h++) {
                BlockType b = terrain.block(x, y + h, z);
                if (b == null || b == BlockType.WATER || b == BlockType.BEDROCK || !NaturalGround.isNatural(b)) {
                    return null; // never into water or bedrock, nor through anything someone built
                }
                if (b.isSolid()) {
                    fromSite.add(new Vector3i(x, y + h, z));
                }
            }
            if (!terrain.covered(x, y, z)) {
                // Out under the sky: the stair's top step. Dig it from the top down.
                List<Vector3i> surfaceFirst = new ArrayList<>(fromSite.size());
                for (int i = fromSite.size() - 1; i >= 0; i--) {
                    surfaceFirst.add(fromSite.get(i));
                }
                return surfaceFirst;
            }
        }
        return null;
    }
}
