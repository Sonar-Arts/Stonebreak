package com.stonebreak.blocks.stalagmite;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.stairs.StairState.Facing;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Size, growth and break rules for multi-cell limestone stalagmites, over a single column. */
class StalagmiteTest {

    private static final BlockType S = BlockType.LIMESTONE_STALAGMITE;

    private static StalagmiteState upright(int size) {
        return new StalagmiteState(size, false, Facing.EAST);
    }

    private static StalagmiteState hanging(int size) {
        return new StalagmiteState(size, true, Facing.NORTH);
    }

    /** One column at x = z = 0; unset cells are air. */
    private static final class Column implements Stalagmite.Cells {
        final Map<Integer, BlockType> blocks = new HashMap<>();
        final Map<Integer, String> states = new HashMap<>();

        Column stone(int y) {
            blocks.put(y, BlockType.STONE);
            return this;
        }

        @Override public BlockType block(int x, int y, int z) {
            return blocks.getOrDefault(y, BlockType.AIR);
        }
        @Override public String state(int x, int y, int z) {
            return states.get(y);
        }
        @Override public void set(int x, int y, int z, BlockType block, String state) {
            if (block == BlockType.AIR) {
                blocks.remove(y);
                states.remove(y);
            } else {
                blocks.put(y, block);
                if (state == null) states.remove(y); else states.put(y, state);
            }
        }

        StalagmiteState anchor(int y) {
            return StalagmiteState.parse(states.get(y));
        }
    }

    @Test
    void stalagmiteAndLimestoneAreBothRegistered() {
        // The two SBOs once shared numericId 44 and the registry silently kept only one.
        assertNotNull(S);
        assertNotNull(BlockType.LIMESTONE);
        assertTrue(S.getId() != BlockType.LIMESTONE.getId());
        assertEquals(S, BlockType.getById(S.getId()));
        assertEquals(BlockType.LIMESTONE, BlockType.getById(BlockType.LIMESTONE.getId()));
    }

    @Test
    void placeWritesAnchorAndPartsInItsDirection() {
        Column c = new Column().stone(0).stone(10);
        Stalagmite.place(c, 0, 1, 0, upright(3));
        assertEquals(upright(3), c.anchor(1));
        assertEquals(Stalagmite.UPPER, c.state(0, 2, 0));
        assertEquals(Stalagmite.UPPER, c.state(0, 3, 0));

        Stalagmite.place(c, 0, 9, 0, hanging(2));
        assertEquals(hanging(2), c.anchor(9));
        assertEquals(Stalagmite.LOWER, c.state(0, 8, 0));
        assertEquals(BlockType.AIR, c.block(0, 7, 0));

        for (int y = 1; y <= 3; y++) assertEquals(1, Stalagmite.anchorY(c, 0, y, 0));
        for (int y = 8; y <= 9; y++) assertEquals(9, Stalagmite.anchorY(c, 0, y, 0));
    }

    @Test
    void uprightGrowsUpAndHangingGrowsDown() {
        Column c = new Column().stone(0).stone(10);
        c.set(0, 1, 0, S, upright(1).toStateString());
        Stalagmite.grow(c, 0, 1, 0);
        Stalagmite.grow(c, 0, 2, 0); // clicking a part grows the same stalagmite
        assertEquals(3, c.anchor(1).size());
        assertEquals(Facing.EAST, c.anchor(1).facing(), "growth keeps the facing");
        assertFalse(Stalagmite.canGrow(c, 0, 3, 0));

        c.set(0, 9, 0, S, hanging(1).toStateString());
        List<Vector3i> written = Stalagmite.grow(c, 0, 9, 0);
        assertEquals(new Vector3i(0, 8, 0), written.get(0));
        assertEquals(Stalagmite.LOWER, c.state(0, 8, 0));
        assertEquals(2, c.anchor(9).size());
        assertTrue(c.anchor(9).hanging());
    }

    @Test
    void growthNeedsAirWhereTheNextCellGoes() {
        Column c = new Column().stone(0);
        Stalagmite.place(c, 0, 1, 0, upright(1));
        c.set(0, 2, 0, BlockType.STONE, null);
        assertFalse(Stalagmite.canGrow(c, 0, 1, 0));
        assertTrue(Stalagmite.grow(c, 0, 1, 0).isEmpty());
        assertEquals(1, c.anchor(1).size());
    }

    @Test
    void anOldSizeOnlyStateStillReadsAsUpright() {
        Column c = new Column().stone(0);
        c.set(0, 1, 0, S, "Stalagmite2");
        c.set(0, 2, 0, S, Stalagmite.UPPER);
        assertEquals(1, Stalagmite.anchorY(c, 0, 2, 0));
        assertTrue(Stalagmite.canGrow(c, 0, 2, 0));
    }

    @Test
    void breakingAnyCellClearsTheWholeStalagmiteEitherWayUp() {
        for (boolean hang : new boolean[]{false, true}) {
            for (int broken = 0; broken < 3; broken++) {
                Column c = new Column().stone(0).stone(10);
                int anchor = hang ? 9 : 1;
                int dir = hang ? -1 : 1;
                Stalagmite.place(c, 0, anchor, 0, hang ? hanging(3) : upright(3));
                int by = anchor + broken * dir;
                c.set(0, by, 0, BlockType.AIR, null);
                Stalagmite.BreakResult r = Stalagmite.breakAt(c, 0, by, 0, true);
                String label = (hang ? "hanging" : "upright") + " broken " + broken;
                assertEquals(2, r.removed().size(), label);
                assertTrue(r.fallenAnchors().isEmpty(), label);
                for (int i = 0; i < 3; i++) {
                    assertEquals(BlockType.AIR, c.block(0, anchor + i * dir, 0), label);
                }
            }
        }
    }

    @Test
    void breakingAStalagmiteOnTopOfAnotherLeavesTheLowerOne() {
        Column c = new Column().stone(0);
        Stalagmite.place(c, 0, 1, 0, upright(2)); // cells 1-2
        Stalagmite.place(c, 0, 3, 0, upright(1)); // standing on its tip
        c.set(0, 3, 0, BlockType.AIR, null);
        Stalagmite.BreakResult r = Stalagmite.breakAt(c, 0, 3, 0, true);
        assertTrue(r.removed().isEmpty());
        assertEquals(2, c.anchor(1).size());
        assertEquals(Stalagmite.UPPER, c.state(0, 2, 0));
    }

    @Test
    void tipsThatTouchBelongToTheirOwnStalagmites() {
        Column c = new Column().stone(0).stone(5);
        Stalagmite.place(c, 0, 1, 0, upright(2));  // 1-2
        Stalagmite.place(c, 0, 4, 0, hanging(2));  // 4-3
        c.set(0, 3, 0, BlockType.AIR, null);       // break the hanging tip
        Stalagmite.breakAt(c, 0, 3, 0, true);
        assertEquals(BlockType.AIR, c.block(0, 4, 0));
        assertEquals(2, c.anchor(1).size(), "the upright one is untouched");
        assertEquals(Stalagmite.UPPER, c.state(0, 2, 0));
    }

    @Test
    void breakingTheFloorDropsTheStackStandingOnIt() {
        Column c = new Column().stone(0).stone(1);
        Stalagmite.place(c, 0, 2, 0, upright(2)); // cells 2-3
        Stalagmite.place(c, 0, 4, 0, upright(1)); // on top of it
        c.set(0, 1, 0, BlockType.AIR, null);
        Stalagmite.BreakResult r = Stalagmite.breakAt(c, 0, 1, 0, false);
        assertEquals(List.of(new Vector3i(0, 2, 0), new Vector3i(0, 4, 0)), r.fallenAnchors());
        assertEquals(3, r.removed().size());
        for (int y = 2; y <= 4; y++) assertEquals(BlockType.AIR, c.block(0, y, 0));
    }

    @Test
    void breakingTheCeilingDropsWhatHangsFromIt() {
        Column c = new Column().stone(10);
        Stalagmite.place(c, 0, 9, 0, hanging(3)); // 9-7
        Stalagmite.place(c, 0, 0, 0, upright(1)); // unrelated, far below
        c.set(0, 10, 0, BlockType.AIR, null);
        Stalagmite.BreakResult r = Stalagmite.breakAt(c, 0, 10, 0, false);
        assertEquals(List.of(new Vector3i(0, 9, 0)), r.fallenAnchors());
        assertEquals(3, r.removed().size());
        assertEquals(S, c.block(0, 0, 0));
    }

    @Test
    void anOrphanedPartIsNotPartOfAStalagmite() {
        Column c = new Column().stone(0);
        Stalagmite.place(c, 0, 1, 0, upright(1));
        c.set(0, 2, 0, S, Stalagmite.UPPER); // size 1 does not reach y=2
        assertEquals(Integer.MIN_VALUE, Stalagmite.anchorY(c, 0, 2, 0));
        c.set(0, 3, 0, S, Stalagmite.LOWER); // a lower part with a non-hanging cell above it
        assertEquals(Integer.MIN_VALUE, Stalagmite.anchorY(c, 0, 3, 0));
    }
}
