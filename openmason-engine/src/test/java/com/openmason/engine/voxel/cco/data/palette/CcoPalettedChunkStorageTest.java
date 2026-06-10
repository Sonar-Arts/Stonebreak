package com.openmason.engine.voxel.cco.data.palette;

import com.openmason.engine.voxel.IBlockType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Behavioral equivalence of {@link CcoPalettedChunkStorage} against a simple
 * map-based reference model under randomized reads/writes, plus bounds,
 * copy independence, and highest-non-air queries.
 */
class CcoPalettedChunkStorageTest {

    private static final int SX = 16;
    private static final int SY = 256;
    private static final int SZ = 16;

    private static CcoPalettedChunkStorage newStorage() {
        return CcoPalettedChunkStorage.createEmpty(SX, SY, SZ, TestBlocks.air());
    }

    @Test
    void randomizedOpsMatchReferenceModel() {
        CcoPalettedChunkStorage storage = newStorage();
        Map<Long, IBlockType> model = new HashMap<>();
        Random random = new Random(1337);

        for (int op = 0; op < 200_000; op++) {
            int x = random.nextInt(SX);
            int y = random.nextInt(SY);
            int z = random.nextInt(SZ);
            long key = ((long) x << 40) | ((long) y << 8) | z;

            if (random.nextInt(4) == 0) {
                IBlockType expected = model.getOrDefault(key, TestBlocks.air());
                assertSame(expected, storage.get(x, y, z));
            } else {
                IBlockType block = TestBlocks.block(random.nextInt(300));
                storage.set(x, y, z, block);
                model.put(key, block);
            }
        }

        for (Map.Entry<Long, IBlockType> entry : model.entrySet()) {
            long key = entry.getKey();
            int x = (int) (key >>> 40);
            int y = (int) ((key >>> 8) & 0xFFFFFFFFL);
            int z = (int) (key & 0xFF);
            assertSame(entry.getValue(), storage.get(x, y, z));
        }
    }

    @Test
    void outOfBoundsGetReturnsNullAndSetReturnsFalse() {
        CcoPalettedChunkStorage storage = newStorage();
        assertNull(storage.get(-1, 0, 0));
        assertNull(storage.get(0, SY, 0));
        assertNull(storage.get(0, 0, 16));
        assertFalse(storage.set(-1, 0, 0, TestBlocks.block(1)));
        assertFalse(storage.set(0, -5, 0, TestBlocks.block(1)));
        assertFalse(storage.set(0, 0, 99, TestBlocks.block(1)));
    }

    @Test
    void countNonAirSumsSections() {
        CcoPalettedChunkStorage storage = newStorage();
        assertEquals(0, storage.countNonAirBlocks());
        storage.set(0, 0, 0, TestBlocks.block(1));
        storage.set(5, 100, 7, TestBlocks.block(2));
        storage.set(5, 100, 7, TestBlocks.block(3)); // overwrite, still one cell
        assertEquals(2, storage.countNonAirBlocks());
    }

    @Test
    void highestNonAirYSkipsUniformAirSections() {
        CcoPalettedChunkStorage storage = newStorage();
        assertEquals(-1, storage.getHighestNonAirY());
        storage.set(3, 64, 9, TestBlocks.block(1));
        assertEquals(64, storage.getHighestNonAirY());
        storage.set(0, 200, 0, TestBlocks.block(2));
        assertEquals(200, storage.getHighestNonAirY());
        storage.set(0, 200, 0, TestBlocks.air());
        assertEquals(64, storage.getHighestNonAirY());
    }

    @Test
    void copyIsIndependentOfOriginal() {
        CcoPalettedChunkStorage storage = newStorage();
        storage.set(1, 50, 2, TestBlocks.block(8));
        CcoPalettedChunkStorage copy = storage.copy();

        storage.set(1, 50, 2, TestBlocks.block(9));
        storage.set(1, 51, 2, TestBlocks.block(9));

        assertSame(TestBlocks.block(8), copy.get(1, 50, 2));
        assertSame(TestBlocks.air(), copy.get(1, 51, 2));
        assertEquals(1, copy.countNonAirBlocks());
    }

    @Test
    void copyFromReplacesAllContents() {
        CcoPalettedChunkStorage source = newStorage();
        source.set(4, 30, 4, TestBlocks.block(11));

        CcoPalettedChunkStorage target = newStorage();
        target.set(0, 0, 0, TestBlocks.block(99));
        target.copyFrom(source);

        assertSame(TestBlocks.block(11), target.get(4, 30, 4));
        assertSame(TestBlocks.air(), target.get(0, 0, 0));
        assertEquals(1, target.countNonAirBlocks());
    }

    @Test
    void copyFromRejectsMismatchedDimensions() {
        CcoPalettedChunkStorage storage = newStorage();
        CcoPalettedChunkStorage other = CcoPalettedChunkStorage.createEmpty(16, 128, 16, TestBlocks.air());
        assertThrows(IllegalArgumentException.class, () -> storage.copyFrom(other));
    }
}
