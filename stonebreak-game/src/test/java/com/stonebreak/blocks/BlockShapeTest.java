package com.stonebreak.blocks;

import com.stonebreak.blocks.stairs.StairShape;
import com.stonebreak.blocks.stairs.StairState;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlockShape#collisionHeight} is the one rule player collision, entity collision, placement
 * validation and navigation all stand on — it exists because three copies of it had already
 * drifted. These tests pin the dispatch itself: full blocks answer a full block, air answers
 * nothing, snow answers its layer height, and stairs answer through {@link StairShape} with the
 * caller's footprint honoured. If this rule bends, mobs plan routes their own physics refuses.
 */
class BlockShapeTest {

    private static final int Y = 10;
    private static final float EPS = 1e-5f;

    private TestWorld world;
    private Chunk chunk;

    @BeforeEach
    void freshWorld() {
        world = new TestWorld(new WorldConfiguration(8, 4), 1L, true);
        chunk = new Chunk(0, 0);
        world.setChunk(0, 0, chunk);
    }

    @Test
    void aFullBlockAnswersAFullBlock() {
        chunk.setBlock(1, Y, 1, BlockType.STONE);

        assertEquals(1.0f, BlockShape.collisionHeight(world, 1, Y, 1), EPS);
    }

    @Test
    void airAnswersNothing() {
        assertEquals(0.0f, BlockShape.collisionHeight(world, 2, Y, 2), EPS);
    }

    @Test
    void snowAnswersItsLayerHeightNotAFullBlock() {
        chunk.setBlock(3, Y, 3, BlockType.SNOW);
        world.getSnowLayerManager().setSnowLayers(3, Y, 3, 3);

        assertEquals(0.375f, BlockShape.collisionHeight(world, 3, Y, 3), EPS,
                "three layers stand 3/8 of a block tall");
    }

    @Test
    void aFullSnowStackAnswersAFullBlock() {
        chunk.setBlock(4, Y, 4, BlockType.SNOW);
        world.getSnowLayerManager().setSnowLayers(4, Y, 4, 8);

        assertEquals(1.0f, BlockShape.collisionHeight(world, 4, Y, 4), EPS);
    }

    @Test
    void stairsAnswerExactlyWhatTheStairShapeAnswers() {
        chunk.setBlock(5, Y, 5, BlockType.OAK_STAIRS);
        chunk.setBlockState(5, Y, 5, StairState.stateStringFor(StairState.Facing.NORTH));

        // The whole cell, and a narrow leading-edge footprint — both must agree with the shape.
        assertEquals(
                StairShape.stepHeight(world, 5, Y, 5, BlockType.OAK_STAIRS, 5.0f, 5.0f, 6.0f, 6.0f),
                BlockShape.collisionHeight(world, 5, Y, 5), EPS);
        assertEquals(
                StairShape.stepHeight(world, 5, Y, 5, BlockType.OAK_STAIRS, 5.0f, 5.0f, 5.2f, 5.2f),
                BlockShape.collisionHeight(world, 5, Y, 5, 5.0f, 5.0f, 5.2f, 5.2f), EPS);
    }

    @Test
    void aStairFootprintRestsOnTheTreadItIsReallyOn() {
        chunk.setBlock(6, Y, 6, BlockType.OAK_STAIRS);
        chunk.setBlockState(6, Y, 6, StairState.stateStringFor(StairState.Facing.NORTH));

        float lowEnd = BlockShape.collisionHeight(world, 6, Y, 6, 6.4f, 6.0f, 6.6f, 6.1f);
        float highEnd = BlockShape.collisionHeight(world, 6, Y, 6, 6.4f, 6.9f, 6.6f, 7.0f);
        float min = Math.min(lowEnd, highEnd);
        float max = Math.max(lowEnd, highEnd);

        assertTrue(max - min > 0.25f, "a stair must answer differently at its two ends");
        assertEquals(1.0f, max, EPS, "the tall end is a full block");
        assertTrue(min > 0.0f && min <= 0.5f,
                "the first tread must be solid but steppable, was " + min);
        assertEquals(max, BlockShape.collisionHeight(world, 6, Y, 6), EPS,
                "the whole cell answers the tallest step the footprint overlaps");
    }
}
