package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.chunk.utils.LocalBlockKey;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The carve-mask packing is an ABI, not a convenience.
 *
 * <p>Every carver writes its {@code BitSet} with {@link CarveMaskKey#pack}, the block fill in
 * {@code TerrainGenerationSystem} reads it back, and {@code generator.cpp} re-implements the
 * same layout over a {@code long[1024]} on the native side. A change to the bit assignment
 * that is not mirrored in all three places does not fail loudly — it relocates carved blocks,
 * which reads downstream as "the caves look wrong" with nothing pointing at the cause.
 *
 * <p>So this pins the layout itself rather than any behaviour built on it: the literal bit
 * expression, the roundtrip over every cell a 256-tall chunk can hold, that no two cells
 * collide, that the whole range fits the native mask's 65536 bits, and that it stays distinct
 * from {@link LocalBlockKey} — the other 16-bit-per-chunk packing in the codebase, which the
 * two classes' javadocs warn are not interchangeable.
 */
class CarveMaskKeyTest {

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;

    /** Bits in the native kernel's {@code long[1024]} carve mask. */
    private static final int NATIVE_MASK_BITS = 1024 * 64;

    @Test
    void packedBitsAreTheDocumentedExpression() {
        assertEquals(0, CarveMaskKey.pack(0, 0, 0));
        assertEquals((5 << 12) | (137 << 4) | 11, CarveMaskKey.pack(5, 137, 11));
        assertEquals((15 << 12) | (255 << 4) | 15, CarveMaskKey.pack(15, 255, 15));
    }

    @Test
    void everyCellRoundTrips() {
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int y = 0; y < WORLD_HEIGHT; y++) {
                for (int z = 0; z < CHUNK_SIZE; z++) {
                    int bit = CarveMaskKey.pack(x, y, z);
                    assertEquals(x, CarveMaskKey.x(bit), () -> "x lost at " + bit);
                    assertEquals(y, CarveMaskKey.y(bit), () -> "y lost at " + bit);
                    assertEquals(z, CarveMaskKey.z(bit), () -> "z lost at " + bit);
                }
            }
        }
    }

    /**
     * Distinct cells must occupy distinct bits. A collision would make one carved block
     * silently erase another's, which no roundtrip check can see.
     */
    @Test
    void distinctCellsNeverShareABit() {
        BitSet seen = new BitSet();
        int cells = 0;
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int y = 0; y < WORLD_HEIGHT; y++) {
                for (int z = 0; z < CHUNK_SIZE; z++) {
                    seen.set(CarveMaskKey.pack(x, y, z));
                    cells++;
                }
            }
        }
        assertEquals(cells, seen.cardinality(), "two chunk cells packed to the same mask bit");
    }

    /**
     * The native kernel hands back a fixed {@code long[1024]}. A world taller than 256 would
     * overflow y out of bits 4-11 and off the end of that array at the same time, so this is
     * the one assertion that catches a WORLD_HEIGHT bump before it corrupts the ABI.
     */
    @Test
    void theWholeRangeFitsTheNativeMask() {
        assertTrue(WORLD_HEIGHT <= 256,
                "CarveMaskKey gives y only bits 4-11; WORLD_HEIGHT=" + WORLD_HEIGHT
                        + " no longer fits and the native long[1024] mask ABI is broken");
        int highest = CarveMaskKey.pack(CHUNK_SIZE - 1, WORLD_HEIGHT - 1, CHUNK_SIZE - 1);
        assertTrue(highest < NATIVE_MASK_BITS,
                "top chunk cell packs to bit " + highest + ", past the native mask's "
                        + NATIVE_MASK_BITS + " bits");
    }

    /** The two 16-bit chunk packings must not be mistaken for one another. */
    @Test
    void theLayoutIsNotLocalBlockKeys() {
        assertNotEquals(LocalBlockKey.pack(5, 137, 11), CarveMaskKey.pack(5, 137, 11),
                "CarveMaskKey and LocalBlockKey agree on a cell — one of the two layouts "
                        + "has been changed into the other, and masks read with the wrong "
                        + "one will decode to the wrong blocks");
    }
}
