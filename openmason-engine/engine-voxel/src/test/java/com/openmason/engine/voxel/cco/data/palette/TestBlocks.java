package com.openmason.engine.voxel.cco.data.palette;

import com.openmason.engine.voxel.IBlockType;

/**
 * Interned test {@link IBlockType} instances. The paletted storage matches
 * palette entries by reference (like the game's enum constants), so tests
 * must reuse one instance per id.
 */
final class TestBlocks {

    static final int COUNT = 1024;
    private static final TestBlock[] BLOCKS = new TestBlock[COUNT];

    static {
        for (int i = 0; i < COUNT; i++) {
            BLOCKS[i] = new TestBlock(i);
        }
    }

    private TestBlocks() {
    }

    /** Block for an id; id 0 is air. */
    static IBlockType block(int id) {
        return BLOCKS[id];
    }

    static IBlockType air() {
        return BLOCKS[0];
    }

    private record TestBlock(int id) implements IBlockType {
        public int getId() { return id; }
        public String getName() { return "block" + id; }
        public boolean isSolid() { return id != 0; }
        public boolean isBreakable() { return id != 0; }
        public boolean isTransparent() { return false; }
        public boolean isAir() { return id == 0; }
    }
}
