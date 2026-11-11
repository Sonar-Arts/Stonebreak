package com.stonebreak.blocks.multipart;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.gameplay.MultipartData;
import com.stonebreak.gameplay.MultipartData.Box;
import com.stonebreak.gameplay.MultipartData.Condition;
import com.stonebreak.gameplay.MultipartData.Part;
import com.stonebreak.gameplay.MultipartData.Side;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A synthetic fence-like table: every test kind, negation, and collision boxes. */
class MultipartShapeTest {

    private static final String FENCE_TAG = "test:fence_connectable";
    private static final Box POST = new Box(0.375f, 0f, 0.375f, 0.625f, 1.5f, 0.625f);
    private static final Box ARM_NORTH = new Box(0.4375f, 0f, 0f, 0.5625f, 1.5f, 0.375f);

    private static MultipartShape fence() {
        MultipartData data = new MultipartData(List.of(
                new Part("Post", Map.of(), List.of(POST)),
                new Part("ArmNorth", Map.of(Side.NORTH, Condition.parse("tag:" + FENCE_TAG)), List.of(ARM_NORTH)),
                new Part("ArmEast", Map.of(Side.EAST, Condition.parse("same")), List.of()),
                new Part("Cap", Map.of(Side.UP, Condition.parse("!full_cube")), List.of())));
        return new MultipartShape(BlockType.LEAVES, data, t -> t == BlockType.STONE,
                t -> t == BlockType.WOOD ? List.of(FENCE_TAG) : List.of());
    }

    /** Neighbours: the cell sits at the origin; anything not listed is air. */
    private static MultipartShape.Neighbours around(BlockType north, BlockType east, BlockType up) {
        return (x, y, z) -> {
            if (x == 0 && y == 0 && z == -1) return north;
            if (x == 1 && y == 0 && z == 0) return east;
            if (x == 0 && y == 1 && z == 0) return up;
            return BlockType.AIR;
        };
    }

    @Test
    void eachDistinctTestIsOneBitAndNegationSharesIt() {
        assertEquals(8, fence().maskCount(), "tag north, same east, full_cube up");
    }

    @Test
    void partsDrawWhenEveryConditionHolds() {
        MultipartShape shape = fence();
        int all = shape.evaluate(around(BlockType.WOOD, BlockType.LEAVES, BlockType.AIR), 0, 0, 0);
        assertEquals(List.of("Post", "ArmNorth", "ArmEast", "Cap"), shape.states(all));

        int none = shape.evaluate(around(BlockType.STONE, BlockType.WOOD, BlockType.STONE), 0, 0, 0);
        assertEquals(List.of("Post"), shape.states(none), "stone isn't tagged, wood isn't the same, stone is full");
    }

    @Test
    void boxesFollowTheDrawnParts() {
        MultipartShape shape = fence();
        assertTrue(shape.hasBoxes());
        int lone = shape.evaluate(around(BlockType.AIR, BlockType.AIR, BlockType.AIR), 0, 0, 0);
        assertArrayEquals(new float[]{0.375f, 0f, 0.375f, 0.625f, 1.5f, 0.625f}, shape.boxes(lone));
        int joined = shape.evaluate(around(BlockType.WOOD, BlockType.AIR, BlockType.AIR), 0, 0, 0);
        assertEquals(12, shape.boxes(joined).length, "post + north arm");
    }

    @Test
    void keysAreDistinctPerCombination() {
        MultipartShape shape = fence();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (int m = 0; m < shape.maskCount(); m++) keys.add(shape.key(m));
        assertEquals(shape.maskCount(), keys.size());
        assertEquals(List.of("Post", "ArmNorth", "ArmEast", "Cap"), shape.partStates());
    }

    @Test
    void tooManyTestsAreRefused() {
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i <= MultipartData.MAX_ATOMS; i++) {
            parts.add(new Part("P" + i, Map.of(Side.values()[i % 6], Condition.parse("tag:t" + i)), List.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> new MultipartShape(BlockType.LEAVES,
                new MultipartData(parts), t -> false, t -> List.of()));
    }
}
