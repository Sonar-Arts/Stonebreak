package com.stonebreak.items;

import com.openmason.engine.format.sbo.SBOFormat;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.drops.BlockDropTables;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clay is mined into clay chunks (3-4) rather than dropping the block itself —
 * authored as the {@code drops} table in {@code SB_Clay.sbo}, not in code.
 * These guard the ways that quietly breaks: the chunk SBO drifting back into
 * {@code sbo/blocks/} (where it would register as a block and never exist as
 * an item), the clay SBO losing its table (it would drop itself again), and
 * an off-by-one that clips the top of the drop range.
 */
class ClayChunkDropTest {

    @Test
    void clayChunkIsRegisteredAsAnItem() {
        assertNotNull(ItemType.CLAY_CHUNK);
        assertEquals("Clay Chunk", ItemType.CLAY_CHUNK.getName());
        assertEquals(ItemCategory.MATERIALS, ItemType.CLAY_CHUNK.getCategory());
        assertEquals(ItemType.CLAY_CHUNK, ItemType.getByObjectId("stonebreak:clay_chunk"));
    }

    @Test
    void clayChunkIsNotAlsoRegisteredAsABlock() {
        assertNull(BlockType.getByName("CLAY_CHUNK"),
                "clay_chunk SBO belongs in sbo/items/ — in sbo/blocks/ it registers as a phantom block");
        assertNull(BlockType.getById(ItemType.CLAY_CHUNK.getId()),
                "item numeric IDs must not collide with block IDs");
    }

    @Test
    void claySboCarriesTheChunkDropTable() {
        SBOFormat.DropData table = BlockDropTables.tableFor(BlockType.CLAY);
        assertNotNull(table, "SB_Clay.sbo must declare a drops table");
        assertEquals(List.of(new SBOFormat.DropEntry("stonebreak:clay_chunk", 3, 4, 1f)),
                table.dropsFor(null));
    }

    @Test
    void dropCountStaysWithinThreeToFourInclusive() {
        Random random = new Random(1234);
        boolean sawMin = false;
        boolean sawMax = false;
        for (int i = 0; i < 5000; i++) {
            List<BlockDropTables.RolledDrop> rolled = BlockDropTables.roll(BlockType.CLAY, null, random);
            assertNotNull(rolled);
            assertEquals(1, rolled.size(), "exactly one clay-chunk stack per break");
            assertEquals(ItemType.CLAY_CHUNK, rolled.get(0).item());
            int count = rolled.get(0).count();
            assertTrue(count >= 3 && count <= 4, "clay chunk drop count out of range: " + count);
            if (count == 3) sawMin = true;
            if (count == 4) sawMax = true;
        }
        assertTrue(sawMin, "minimum of 3 was never rolled");
        assertTrue(sawMax, "maximum of 4 was never rolled");
    }
}
