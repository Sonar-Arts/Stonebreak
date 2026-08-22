package com.stonebreak.items;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.util.DropUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clay is mined into clay chunks (3-4) rather than dropping the block itself.
 * These guard the two ways that quietly breaks: the SBO drifting back into
 * {@code sbo/blocks/} (where it would register as a block and never exist as
 * an item), and an off-by-one that clips the top of the drop range.
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
    void dropCountStaysWithinThreeToFourInclusive() {
        boolean sawMin = false;
        boolean sawMax = false;
        for (int i = 0; i < 5000; i++) {
            int count = DropUtil.rollClayChunkDropCount();
            assertTrue(count >= 3 && count <= 4, "clay chunk drop count out of range: " + count);
            if (count == 3) sawMin = true;
            if (count == 4) sawMax = true;
        }
        assertTrue(sawMin, "minimum of 3 was never rolled");
        assertTrue(sawMax, "maximum of 4 was never rolled");
    }
}
