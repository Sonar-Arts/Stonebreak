package com.openmason.main.systems.menus.dialogs.validation;

import com.stonebreak.blocks.BlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WATER (id 8) and AIR (id 0) are engine sentinels with no SBO, so they are
 * absent from the BlockRegistry — the exporter used to suggest 8 for every
 * new block. They must read as taken.
 */
class NumericIdValidatorSentinelTest {

    @Test
    void waterIdIsReportedAsConflict() {
        NumericIdValidator.Result r = NumericIdValidator.validate(
                NumericIdValidator.Domain.BLOCK, BlockType.WATER.getId(), "stonebreak:new_block");
        assertInstanceOf(NumericIdValidator.Result.Conflict.class, r);
    }

    @Test
    void suggestedBlockIdSkipsSentinels() {
        int suggested = NumericIdValidator.suggestNextFreeId(NumericIdValidator.Domain.BLOCK);
        assertNotEquals(BlockType.WATER.getId(), suggested);
        assertNotEquals(BlockType.AIR.getId(), suggested);
        assertTrue(suggested > 0);
    }

    @Test
    void takenIdsListIncludesWater() {
        assertTrue(NumericIdValidator.listTakenIds(NumericIdValidator.Domain.BLOCK).stream()
                .anyMatch(t -> t.numericId() == BlockType.WATER.getId()));
    }
}
