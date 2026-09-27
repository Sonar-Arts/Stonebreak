package com.stonebreak.world.generation.heightmap;

/**
 * Packs a chunk-local block position into the bit index the carvers' {@code BitSet} masks use:
 * {@code (x << 12) | (y << 4) | z}.
 *
 * <p>Distinct from {@link com.stonebreak.world.chunk.utils.LocalBlockKey}, which packs
 * {@code (y << 8) | (z << 4) | x} for block-state maps. Both are 16-bit-per-chunk layouts and
 * neither is interchangeable with the other, which is exactly why this one has a name: the
 * layout is shared by every carver, by the block fill in {@code TerrainGenerationSystem}, and by
 * the native kernel's {@code long[1024]} mask ABI ({@code generator.cpp}), so a reader decoding a
 * mask has one definition to find rather than an expression repeated in seven files.
 *
 * <p>Valid for {@code WORLD_HEIGHT <= 256}: y occupies bits 4-11.
 */
public final class CarveMaskKey {

    private CarveMaskKey() {
    }

    public static int pack(int x, int y, int z) {
        return (x << 12) | (y << 4) | z;
    }

    public static int x(int bit) {
        return bit >>> 12;
    }

    public static int y(int bit) {
        return (bit >> 4) & 0xFF;
    }

    public static int z(int bit) {
        return bit & 0xF;
    }
}
