package com.stonebreak.world.generation.trees;

import java.util.Random;

/**
 * Helper for producing a deterministic per-tree {@link Random} seeded from world coordinates
 * and a tree-type tag. The same world seed and coordinates always yield the same Random state,
 * which keeps tree shape stable across reloads and across the immediate-vs-deferred placement
 * paths in TreeGenerator.
 */
final class TreeRandom {

    private TreeRandom() {}

    /**
     * Build a deterministic Random for a tree placed at the given world coordinates. The
     * {@code tag} differentiates seeds across tree types so a regular and an elm tree at the
     * same column don't collapse onto identical variant choices.
     */
    static Random forPosition(int worldX, int worldY, int worldZ, long tag) {
        long seed = ((long) worldX * 341873128712L)
                  ^ ((long) worldZ * 132897987541L)
                  ^ ((long) worldY * 2685821657736338717L)
                  ^ tag
                  ^ 0x9E3779B97F4A7C15L;
        return new Random(seed);
    }
}
