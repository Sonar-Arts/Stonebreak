package com.openmason.engine.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for {@link BlockPos}: immutable 3D block position record.
 */
class BlockPosTest {

    @Test
    void recordEqualityAndHashCode() {
        BlockPos a = new BlockPos(1, 2, 3);
        BlockPos b = new BlockPos(1, 2, 3);
        BlockPos c = new BlockPos(1, 2, 4);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void accessorsReturnComponents() {
        BlockPos pos = new BlockPos(5, -3, 100);
        assertEquals(5, pos.x());
        assertEquals(-3, pos.y());
        assertEquals(100, pos.z());
    }

    @Test
    void offsetReturnsNewInstanceWithSummedComponents() {
        BlockPos base = new BlockPos(1, 2, 3);
        BlockPos moved = base.offset(10, -2, 0);

        assertEquals(11, moved.x());
        assertEquals(0, moved.y());
        assertEquals(3, moved.z());

        // Original is unchanged.
        assertEquals(1, base.x());
        assertEquals(2, base.y());
        assertEquals(3, base.z());
    }
}