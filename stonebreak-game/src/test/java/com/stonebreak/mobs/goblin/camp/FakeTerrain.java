package com.stonebreak.mobs.goblin.camp;

import com.stonebreak.blocks.BlockType;

import java.util.HashMap;
import java.util.Map;

/**
 * A little hand-built world for the camp rules: flat ground of stone with a top at
 * {@link #GROUND_TOP}, air above, and whatever the test digs or builds in between.
 */
final class FakeTerrain implements CampTerrain {

    static final int GROUND_TOP = 40;
    static final int SIZE = 80; // loaded from -SIZE to SIZE in x and z

    private final Map<Long, BlockType> edits = new HashMap<>();

    private static long key(int x, int y, int z) {
        return ((long) (x + 1024) << 40) | ((long) (y + 1024) << 20) | (z + 1024);
    }

    void set(int x, int y, int z, BlockType b) {
        edits.put(key(x, y, z), b);
    }

    /** Hollows out a box of air (inclusive bounds). */
    void carve(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    set(x, y, z, BlockType.AIR);
                }
            }
        }
    }

    @Override
    public BlockType block(int x, int y, int z) {
        if (Math.abs(x) > SIZE || Math.abs(z) > SIZE) {
            return null;
        }
        BlockType edited = edits.get(key(x, y, z));
        if (edited != null) {
            return edited;
        }
        if (y <= 0) {
            return BlockType.BEDROCK;
        }
        return y <= GROUND_TOP ? BlockType.STONE : BlockType.AIR;
    }

    @Override
    public int columnTop(int x, int z) {
        if (Math.abs(x) > SIZE || Math.abs(z) > SIZE) {
            return -1;
        }
        for (int y = 120; y >= 0; y--) {
            BlockType b = block(x, y, z);
            if (b != null && b.isSolid()) {
                return y;
            }
        }
        return -1;
    }
}
