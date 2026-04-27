package com.stonebreak.world.lighting;

import com.stonebreak.blocks.BlockType;

/**
 * Single predicate deciding whether a block blocks sky light for lighting
 * purposes.
 *
 * <p>Collapses block-type metadata down to a boolean — the heightmap design
 * only cares whether a block fully blocks the sky beam. Leaves, glass,
 * flowers, water, and snow all pass light through; every other block is
 * treated as solid.
 *
 * <p>If emissive / semi-transparent tiers are needed later, extend here.
 */
public final class BlockOpacity {

    private BlockOpacity() {}

    /** Returns true if the block occludes sky light (raises column heightmap). */
    public static boolean isOpaque(BlockType type) {
        if (type == null) return false;
        switch (type) {
            case AIR:
            case WATER:
            case ICE:
            case ROSE:
            case DANDELION:
            case WILDGRASS:
            case SNOW:
            case LEAVES:
            case PINE_LEAVES:
            case ELM_LEAVES:
                return false;
            default:
                return true;
        }
    }
}
