package com.stonebreak.blocks.stalagmite;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3i;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Rules for {@link BlockType#LIMESTONE_STALAGMITE}: a 1, 2 or 3 block formation that stands on
 * a floor or hangs from a ceiling.
 *
 * <p>A stalagmite is a vertical run of stalagmite cells. The <b>anchor</b> is the cell attached
 * to the floor (upright) or ceiling (hanging); its {@link StalagmiteState} holds size, hanging
 * and facing, and selects the flipped/rotated model the renderer draws, whose geometry reaches
 * through the rest of the run. Every other cell holds the same block with a part state —
 * {@link #UPPER} above an upright anchor, {@link #LOWER} below a hanging one. Part cells draw
 * nothing (the renderer registers empty stamps for them) but are real cells, so nothing can be
 * placed inside a stalagmite and targeting resolves any cell to its anchor.
 *
 * <p>Ownership is geometric: a part cell belongs to the nearest anchor in its direction, and only
 * if that anchor's size reaches it. That lets a break be resolved from the world as it is
 * afterwards, without knowing the broken cell's state.
 */
public final class Stalagmite {
    public static final int MAX_SIZE = 3;
    /** Part of an upright stalagmite whose anchor is below. */
    public static final String UPPER = "StalagmiteUpper";
    /** Part of a hanging stalagmite whose anchor is above. */
    public static final String LOWER = "StalagmiteLower";

    private static final int H = WorldConfiguration.WORLD_HEIGHT;

    private Stalagmite() {}

    /** Block + state reads and writes the rules need; {@link #of(World)} adapts a real world. */
    public interface Cells {
        BlockType block(int x, int y, int z);
        String state(int x, int y, int z);
        void set(int x, int y, int z, BlockType block, String state);
    }

    public static Cells of(World world) {
        return new Cells() {
            @Override public BlockType block(int x, int y, int z) { return world.getBlockAt(x, y, z); }
            @Override public String state(int x, int y, int z) { return world.getBlockStateAt(x, y, z); }
            @Override public void set(int x, int y, int z, BlockType block, String state) {
                if (world.getBlockAt(x, y, z) != block) {
                    world.setBlockAt(x, y, z, block, false);
                }
                if (block != BlockType.AIR) {
                    world.setBlockStateAt(x, y, z, state);
                }
            }
        };
    }

    public static String partState(int direction) {
        return direction > 0 ? UPPER : LOWER;
    }

    public static boolean isPartState(String state) {
        return UPPER.equals(state) || LOWER.equals(state);
    }

    private static boolean isStalagmite(Cells cells, int x, int y, int z) {
        return y >= 0 && y < H && cells.block(x, y, z) == BlockType.LIMESTONE_STALAGMITE;
    }

    /** A part cell extending in {@code direction} from its anchor. */
    private static boolean isPart(Cells cells, int x, int y, int z, int direction) {
        return isStalagmite(cells, x, y, z) && partState(direction).equals(cells.state(x, y, z));
    }

    private static boolean isAnchor(Cells cells, int x, int y, int z) {
        return isStalagmite(cells, x, y, z) && !isPartState(cells.state(x, y, z));
    }

    /**
     * The anchor Y of the stalagmite occupying {@code (x, y, z)}, or {@code Integer.MIN_VALUE}
     * when the cell is not part of one (including an orphaned part cell).
     */
    public static int anchorY(Cells cells, int x, int y, int z) {
        if (!isStalagmite(cells, x, y, z)) {
            return Integer.MIN_VALUE;
        }
        String state = cells.state(x, y, z);
        if (!isPartState(state)) {
            return y;
        }
        int direction = UPPER.equals(state) ? 1 : -1;
        int ay = y;
        while (isPart(cells, x, ay, z, direction)) {
            ay -= direction;
        }
        if (!isAnchor(cells, x, ay, z)) {
            return Integer.MIN_VALUE;
        }
        StalagmiteState anchor = StalagmiteState.parse(cells.state(x, ay, z));
        return anchor.direction() == direction && Math.abs(y - ay) < anchor.size() ? ay : Integer.MIN_VALUE;
    }

    /** Writes a stalagmite with its anchor at {@code (x, y, z)}. Its cells must be clear. */
    public static void place(Cells cells, int x, int y, int z, StalagmiteState state) {
        cells.set(x, y, z, BlockType.LIMESTONE_STALAGMITE, state.toStateString());
        for (int i = 1; i < state.size(); i++) {
            cells.set(x, y + i * state.direction(), z, BlockType.LIMESTONE_STALAGMITE, partState(state.direction()));
        }
    }

    /** Whether {@link #grow} would succeed: below full size, with air where the next cell goes. */
    public static boolean canGrow(Cells cells, int x, int y, int z) {
        int ay = anchorY(cells, x, y, z);
        if (ay == Integer.MIN_VALUE) {
            return false;
        }
        StalagmiteState state = StalagmiteState.parse(cells.state(x, ay, z));
        int next = ay + state.size() * state.direction();
        return state.size() < MAX_SIZE && next >= 0 && next < H && cells.block(x, next, z) == BlockType.AIR;
    }

    /**
     * Grows the stalagmite containing {@code (x, y, z)} by one size — upward when it stands,
     * downward when it hangs.
     *
     * @return the cells written (the new part cell first, then the anchor), or an empty list
     *         when it cannot grow
     */
    public static List<Vector3i> grow(Cells cells, int x, int y, int z) {
        if (!canGrow(cells, x, y, z)) {
            return List.of();
        }
        int ay = anchorY(cells, x, y, z);
        StalagmiteState state = StalagmiteState.parse(cells.state(x, ay, z));
        int next = ay + state.size() * state.direction();
        cells.set(x, next, z, BlockType.LIMESTONE_STALAGMITE, partState(state.direction()));
        cells.set(x, ay, z, BlockType.LIMESTONE_STALAGMITE, state.withSize(state.size() + 1).toStateString());
        return List.of(new Vector3i(x, next, z), new Vector3i(x, ay, z));
    }

    /**
     * Clears everything that goes with a cell at {@code (x, y, z)} that was just broken (it
     * already reads as non-stalagmite): the rest of its own stalagmite, and every stalagmite that
     * stood on or hung from a cleared cell — repeatedly, so stacks collapse whole.
     *
     * @param brokeStalagmite true when the broken cell itself was a stalagmite
     * @return every cell cleared here, and the anchors of the stalagmites that fell off
     *         (each of those drops one item; the broken cell's own drop is the caller's)
     */
    public static BreakResult breakAt(Cells cells, int x, int y, int z, boolean brokeStalagmite) {
        List<Vector3i> removed = new ArrayList<>();
        List<Vector3i> fallen = new ArrayList<>();
        Deque<Integer> cleared = new ArrayDeque<>();
        cleared.add(y);

        if (brokeStalagmite) {
            // The broken cell may have been a part: clear the anchor (and parts) that owned it.
            for (int direction : new int[]{1, -1}) {
                int ay = y - direction;
                while (isPart(cells, x, ay, z, direction)) {
                    ay -= direction;
                }
                if (isAnchor(cells, x, ay, z)) {
                    StalagmiteState owner = StalagmiteState.parse(cells.state(x, ay, z));
                    if (owner.direction() == direction && Math.abs(y - ay) < owner.size()) {
                        for (int cy = ay; cy != y; cy += direction) {
                            clear(cells, x, cy, z, removed, cleared);
                        }
                    }
                }
            }
        }

        while (!cleared.isEmpty()) {
            int cy = cleared.poll();
            for (int direction : new int[]{1, -1}) {
                int ny = cy + direction;
                if (isPart(cells, x, ny, z, direction)) {
                    // Its chain ran through the cleared cell.
                    clear(cells, x, ny, z, removed, cleared);
                } else if (isAnchor(cells, x, ny, z)
                        && StalagmiteState.parse(cells.state(x, ny, z)).direction() == direction) {
                    // Stood on (or hung from) the cleared cell: falls whole.
                    fallen.add(new Vector3i(x, ny, z));
                    clear(cells, x, ny, z, removed, cleared);
                }
            }
        }
        return new BreakResult(removed, fallen);
    }

    private static void clear(Cells cells, int x, int y, int z, List<Vector3i> removed, Deque<Integer> cleared) {
        cells.set(x, y, z, BlockType.AIR, null);
        removed.add(new Vector3i(x, y, z));
        cleared.add(y);
    }

    /** Cells cleared by {@link #breakAt}, and the anchors of whole stalagmites that fell. */
    public record BreakResult(List<Vector3i> removed, List<Vector3i> fallenAnchors) {}
}
