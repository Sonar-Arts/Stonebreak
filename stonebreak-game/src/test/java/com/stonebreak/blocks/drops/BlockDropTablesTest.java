package com.stonebreak.blocks.drops;

import com.openmason.engine.format.sbo.SBOFormat;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rolling an SBO {@code drops} table into concrete stacks: tool overrides,
 * per-line chance, count ranges, objectId resolution, and the
 * "no table ⇒ null, empty table ⇒ nothing" contract {@code DropUtil} relies on.
 */
class BlockDropTablesTest {

    private static final String CHUNK = "stonebreak:clay_chunk";
    private static final String COBBLE = "stonebreak:cobblestone";

    @Test
    void toolOverrideReplacesDefaultsAndUnknownToolUsesDefaults() {
        SBOFormat.DropData table = new SBOFormat.DropData(
                List.of(SBOFormat.DropEntry.of(COBBLE, 1)),
                List.of(new SBOFormat.ToolDropOverride("stonebreak:wooden_shovel",
                        List.of(SBOFormat.DropEntry.of(CHUNK, 2)))));
        Random r = new Random(7);

        List<BlockDropTables.RolledDrop> byHand = BlockDropTables.roll(table, null, r);
        assertEquals(List.of(new BlockDropTables.RolledDrop(BlockType.COBBLESTONE, 1)), byHand);

        List<BlockDropTables.RolledDrop> shovel = BlockDropTables.roll(table, "stonebreak:wooden_shovel", r);
        assertEquals(List.of(new BlockDropTables.RolledDrop(ItemType.CLAY_CHUNK, 2)), shovel);

        List<BlockDropTables.RolledDrop> pick = BlockDropTables.roll(table, "stonebreak:wooden_pickaxe", r);
        assertEquals(byHand, pick, "a tool without an override falls back to the default list");
    }

    @Test
    void emptyOverrideMeansThatToolYieldsNothing() {
        SBOFormat.DropData table = new SBOFormat.DropData(
                List.of(SBOFormat.DropEntry.of(COBBLE, 1)),
                List.of(new SBOFormat.ToolDropOverride("stonebreak:wooden_axe", List.of())));
        assertTrue(BlockDropTables.roll(table, "stonebreak:wooden_axe", new Random(1)).isEmpty());
    }

    @Test
    void chanceAndCountRangeAreHonoured() {
        SBOFormat.DropData table = new SBOFormat.DropData(
                List.of(new SBOFormat.DropEntry(CHUNK, 2, 5, 0.5f)), List.of());
        Random r = new Random(42);
        int fired = 0;
        for (int i = 0; i < 4000; i++) {
            List<BlockDropTables.RolledDrop> out = BlockDropTables.roll(table, null, r);
            assertTrue(out.size() <= 1);
            if (out.isEmpty()) continue;
            fired++;
            int c = out.get(0).count();
            assertTrue(c >= 2 && c <= 5, "count out of range: " + c);
        }
        assertTrue(fired > 1700 && fired < 2300, "50% chance fired " + fired + "/4000");
    }

    @Test
    void zeroCountRollsAndUnknownObjectIdsAreSkipped() {
        SBOFormat.DropData table = new SBOFormat.DropData(
                List.of(new SBOFormat.DropEntry(CHUNK, 0, 0, 1f),
                        SBOFormat.DropEntry.of("stonebreak:does_not_exist", 3),
                        SBOFormat.DropEntry.of(COBBLE, 1)),
                List.of());
        List<BlockDropTables.RolledDrop> out = BlockDropTables.roll(table, null, new Random(3));
        assertEquals(List.of(new BlockDropTables.RolledDrop(BlockType.COBBLESTONE, 1)), out);
    }

    @Test
    void blocksWithoutATableReturnNullSoTheBuiltInRuleApplies() {
        assertFalse(BlockDropTables.hasTable(BlockType.STONE), "stone still uses the built-in cobblestone rule");
        assertNull(BlockDropTables.roll(BlockType.STONE, null, new Random(1)));
        assertNull(BlockDropTables.tableFor(null));
    }

    @Test
    void resolvesBlocksBeforeItems() {
        assertSame(BlockType.COBBLESTONE, BlockDropTables.resolveItem(COBBLE));
        assertSame(ItemType.CLAY_CHUNK, BlockDropTables.resolveItem(CHUNK));
        assertNull(BlockDropTables.resolveItem("nope:nothing"));
    }
}
