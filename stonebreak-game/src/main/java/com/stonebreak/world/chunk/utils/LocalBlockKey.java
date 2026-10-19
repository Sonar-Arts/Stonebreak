package com.stonebreak.world.chunk.utils;

/**
 * Packs chunk-local block coordinates (x:0-15, y:0-(WORLD_HEIGHT-1), z:0-15) into a
 * single int map key: {@code (y << 8) | (z << 4) | x}. y is unmasked in the low 24
 * bits above x/z, so it isn't truncated regardless of {@code WORLD_HEIGHT}. Replaces
 * the old {@code "x,y,z"} string keys — no allocation or string hashing per lookup.
 */
public final class LocalBlockKey {

    private LocalBlockKey() {
    }

    public static int pack(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    public static int x(int key) {
        return key & 0xF;
    }

    public static int z(int key) {
        return (key >> 4) & 0xF;
    }

    public static int y(int key) {
        return key >>> 8;
    }
}
