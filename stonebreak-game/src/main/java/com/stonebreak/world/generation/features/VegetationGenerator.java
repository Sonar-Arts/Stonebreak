package com.stonebreak.world.generation.features;

import com.stonebreak.blocks.BlockType;

/**
 * Tree vocabulary shared by FastLOD and the native generator's samplers. Trees and flowers are placed by the
 * native generator's population ({@code stonebreak-game/native}, #446); the Java vegetation generator is
 * gone (#447). Kind ordinals match the native {@code sbw_tree} enum.
 */
public final class VegetationGenerator {

    private VegetationGenerator() {
    }

    /** Tree variety identifier, carries the trunk + leaf block types it renders with. */
    public enum TreeKind {
        OAK(BlockType.WOOD, BlockType.LEAVES),
        ELM(BlockType.ELM_WOOD_LOG, BlockType.ELM_LEAVES),
        PINE(BlockType.PINE, BlockType.PINE_LEAVES),
        CYPRESS(BlockType.CYPRESS_LOG, BlockType.CYPRESS_LEAVES);

        private final BlockType trunk;
        private final BlockType leaves;
        TreeKind(BlockType trunk, BlockType leaves) { this.trunk = trunk; this.leaves = leaves; }
        public BlockType trunkBlock() { return trunk; }
        public BlockType leavesBlock() { return leaves; }
    }

    /** A tree the generator plants at a column. */
    public record TreeSample(TreeKind kind, int trunkHeight) {}

    /** Surfaces trees grow on (grass, snowy dirt, swampy grass). */
    public static boolean growsTreeOn(BlockType surfaceBlock) {
        return surfaceBlock == BlockType.GRASS || surfaceBlock == BlockType.SNOWY_DIRT
            || surfaceBlock == BlockType.SWAMPY_GRASS || surfaceBlock == BlockType.WATER;
    }
}
