package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.chunk.utils.LocalBlockKey;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The carve-mask packing is an ABI, not a convenience.
 *
 * <p>Every carver writes its carve {@code BitSet} with {@link LocalBlockKey#pack}, the block
 * fill in {@code TerrainGenerationSystem} reads it back, and the carvers' own anchor and
 * collision math decodes it with {@link LocalBlockKey#x} / {@link LocalBlockKey#z}. A change
 * to the bit assignment that is not mirrored everywhere does not fail loudly — it relocates
 * carved blocks, which reads downstream as "the caves look wrong" with nothing pointing at the
 * cause.
 *
 * <p>So this pins the layout itself rather than any behaviour built on it: the literal bit
 * expression, the roundtrip over every cell a chunk can hold, that no two cells collide, and
 * that the full world height survives the packing (y is unmasked above x/z, so it is not
 * truncated the way a fixed-width field would be).
 */
class LocalBlockKeyMaskTest {

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;

    @Test
    void packedBitsAreTheDocumentedExpression() {
        assertEquals(0, LocalBlockKey.pack(0, 0, 0));
        assertEquals((137 << 8) | (11 << 4) | 5, LocalBlockKey.pack(5, 137, 11));
        assertEquals(((WORLD_HEIGHT - 1) << 8) | (15 << 4) | 15,
                LocalBlockKey.pack(15, WORLD_HEIGHT - 1, 15));
    }

    @Test
    void everyCellRoundTrips() {
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int y = 0; y < WORLD_HEIGHT; y++) {
                for (int z = 0; z < CHUNK_SIZE; z++) {
                    int bit = LocalBlockKey.pack(x, y, z);
                    assertEquals(x, LocalBlockKey.x(bit), () -> "x lost at " + bit);
                    assertEquals(y, LocalBlockKey.y(bit), () -> "y lost at " + bit);
                    assertEquals(z, LocalBlockKey.z(bit), () -> "z lost at " + bit);
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
                    seen.set(LocalBlockKey.pack(x, y, z));
                    cells++;
                }
            }
        }
        assertEquals(cells, seen.cardinality(), "two chunk cells packed to the same mask bit");
    }

    /** y is unmasked above x/z, so the full world height must survive the roundtrip. */
    @Test
    void theFullWorldHeightSurvivesThePacking() {
        int highest = LocalBlockKey.pack(CHUNK_SIZE - 1, WORLD_HEIGHT - 1, CHUNK_SIZE - 1);
        assertEquals(WORLD_HEIGHT - 1, LocalBlockKey.y(highest),
                "packing truncated y — LocalBlockKey must carry the whole world height");
        assertTrue(highest >= 0, "packed bit index went negative");
    }
}
