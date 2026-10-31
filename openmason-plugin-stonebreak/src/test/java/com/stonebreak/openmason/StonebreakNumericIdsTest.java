package com.stonebreak.openmason;

import com.openmason.api.target.NumericIdSource;
import com.stonebreak.blocks.BlockType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stonebreak's numeric ids (#382; was the tool's {@code NumericIdValidatorSentinelTest}). WATER (8)
 * and AIR (0) are engine sentinels with no SBO, so they are absent from the BlockRegistry — the
 * exporter used to suggest 8 for every new block. They must read as taken.
 */
class StonebreakNumericIdsTest {

    private final NumericIdSource ids = new StonebreakNumericIds();

    @Test
    void waterIdIsHeld() {
        Optional<NumericIdSource.TakenId> water = ids.holder("block", BlockType.WATER.getId());
        assertTrue(water.isPresent());
        assertNotEquals("stonebreak:new_block", water.get().objectId());
    }

    @Test
    void takenIdsIncludeTheSentinels() {
        assertTrue(ids.taken("block").stream().anyMatch(t -> t.numericId() == BlockType.WATER.getId()));
        assertTrue(ids.taken("block").stream().anyMatch(t -> t.numericId() == BlockType.AIR.getId()));
    }

    @Test
    void registryBlocksAreHeldByTheirObjectId() {
        NumericIdSource.TakenId some = ids.taken("block").stream()
                .filter(t -> !t.objectId().startsWith("builtin:")).findFirst().orElseThrow();
        assertEquals(some.objectId(), ids.holder("block", some.numericId()).orElseThrow().objectId());
    }

    @Test
    void domains() {
        assertTrue(ids.numbers("block"));
        assertTrue(ids.numbers(" Item "));
        assertFalse(ids.numbers("entity"));
        assertTrue(ids.taken("entity").isEmpty());
        assertEquals(1, ids.firstId("block"));
        assertEquals(StonebreakNumericIds.FIRST_ITEM_ID, ids.firstId("item"));
    }
}
