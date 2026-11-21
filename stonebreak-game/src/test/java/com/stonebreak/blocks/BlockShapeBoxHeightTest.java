package com.stonebreak.blocks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Multipart collision boxes answer the footprint height rule that stairs and snow answer. */
class BlockShapeBoxHeightTest {

    /** A fence post and its north arm, cell space. */
    private static final float[] POST_AND_NORTH_ARM = {
            0.375f, 0f, 0.375f, 0.625f, 1.5f, 0.625f,
            0.4375f, 0f, 0f, 0.5625f, 1.0f, 0.375f};

    @Test
    void theTallestOverlappingBoxWins() {
        assertEquals(1.5f, BlockShape.boxHeight(POST_AND_NORTH_ARM, 0f, 0f, 1f, 1f), 1e-6);
        assertEquals(1.0f, BlockShape.boxHeight(POST_AND_NORTH_ARM, 0.4f, 0f, 0.6f, 0.2f), 1e-6, "over the arm only");
    }

    @Test
    void aFootprintMissingEveryBoxIsPassable() {
        assertEquals(0f, BlockShape.boxHeight(POST_AND_NORTH_ARM, 0f, 0.7f, 0.3f, 1f), 1e-6);
        assertEquals(0f, BlockShape.boxHeight(new float[0], 0f, 0f, 1f, 1f), 1e-6);
    }

    @Test
    void touchingIsNotOverlapping() {
        assertEquals(0f, BlockShape.boxHeight(POST_AND_NORTH_ARM, 0.625f, 0.375f, 1f, 0.625f), 1e-6);
    }
}
