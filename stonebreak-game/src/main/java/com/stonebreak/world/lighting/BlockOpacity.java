package com.stonebreak.world.lighting;

import com.stonebreak.blocks.BlockType;

/**
 * Single predicate deciding whether a block blocks sky light for lighting
 * purposes.
 *
 * <p>Collapses block-type metadata down to a boolean — the heightmap design
 * only cares whether a block fully blocks the sky beam. Anything the asset
 * marks see-through ({@link BlockType#isAuthoredTransparent()}: leaves,
 * glass, ice, flowers, snow, torches, doors — plus air and water) passes
 * light, as do the thin shaped formations (stalagmites, cacti) whose opaque
 * model fills none of the cell's silhouette; every other block is solid.
 *
 * <p>Reads the <em>authored</em> flag on purpose: the "Leaf Transparency"
 * visual setting must not re-light the world.
 *
 * <p>If emissive / semi-transparent tiers are needed later, extend here.
 */
public final class BlockOpacity {

    private BlockOpacity() {}

    /** Returns true if the block occludes sky light (raises column heightmap). */
    public static boolean isOpaque(BlockType type) {
        if (type == null) return false;
        return type != BlockType.AIR
                && type != BlockType.WATER
                && !type.isAuthoredTransparent()
                && !isThinShapedFormation(type);
    }

    /**
     * Opaque shaped blocks too thin to shade the cells below them. Not an
     * asset flag yet — a cactus or stalagmite counted as an occluder would
     * darken its own stack and the ground it stands on.
     */
    private static boolean isThinShapedFormation(BlockType type) {
        return type == BlockType.LIMESTONE_STALAGMITE || type == BlockType.CACTUS;
    }
}
