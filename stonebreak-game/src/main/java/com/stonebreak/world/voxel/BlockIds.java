package com.stonebreak.world.voxel;

import com.stonebreak.blocks.BlockType;

/**
 * Array-backed id -&gt; {@link BlockType} for the hot paths reading native block ids ({@link BlockType#getById}
 * boxes through a map). Unknown ids answer {@link BlockType#AIR}. Rebuilt lazily when a block registers late.
 */
public final class BlockIds {

    private static volatile BlockType[] table = build();

    private BlockIds() {
    }

    private static BlockType[] build() {
        BlockType[] all = BlockType.values();
        int max = 0;
        for (BlockType t : all) {
            max = Math.max(max, t.getId());
        }
        BlockType[] out = new BlockType[max + 1];
        for (BlockType t : all) {
            if (t.getId() >= 0) {
                out[t.getId()] = t;
            }
        }
        return out;
    }

    /** The block of a native id; AIR for negative (missing) or unknown ids. */
    public static BlockType of(int id) {
        BlockType[] t = table;
        if (id >= 0 && id < t.length) {
            BlockType b = t[id];
            if (b != null) {
                return b;
            }
        }
        if (id >= 0) {
            BlockType late = BlockType.getById(id);
            if (late != null) {
                table = build();
                return late;
            }
        }
        return BlockType.AIR;
    }
}
