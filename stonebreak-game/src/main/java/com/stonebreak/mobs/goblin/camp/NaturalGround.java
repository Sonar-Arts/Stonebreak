package com.stonebreak.mobs.goblin.camp;

import com.stonebreak.blocks.BlockType;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The blocks the world grows on its own — rock, soil, sand, ore, water, wild plants. Goblins camp
 * only where nothing else is about: anything not on this list (planks, cobblestone, glass, a torch,
 * a chest, a door) means someone built there, and it is left alone. Of these they dig only loose
 * {@linkplain #isDiggable soil} — never rock — and roof a camp only with {@linkplain #isGroundRoof
 * ground} — never a tree's leaves. Pure policy.
 */
public final class NaturalGround {

    private NaturalGround() {
    }

    /** Built on first use: the block constants are only valid once BlockType has loaded. */
    private static final class Holder {
        static final Set<BlockType> NATURAL = Stream.of(
                        BlockType.GRASS, BlockType.DIRT, BlockType.STONE, BlockType.BEDROCK, BlockType.SAND,
                        BlockType.COAL_ORE, BlockType.IRON_ORE, BlockType.COPPER_ORE, BlockType.TIN_ORE,
                        BlockType.SILVER_ORE, BlockType.GOLD_ORE, BlockType.RED_SAND, BlockType.MAGMA,
                        BlockType.CRYSTAL, BlockType.SANDSTONE, BlockType.RED_SANDSTONE, BlockType.SNOWY_DIRT,
                        BlockType.ICE, BlockType.SNOW, BlockType.SWAMPY_GRASS, BlockType.DRY_GRASS,
                        BlockType.GRAVEL, BlockType.CLAY, BlockType.LIMESTONE, BlockType.LIMESTONE_STALAGMITE,
                        BlockType.WATER,
                        BlockType.ROSE, BlockType.DANDELION, BlockType.WILDGRASS, BlockType.CACTUS,
                        BlockType.BLUEBERRY_BUSH, BlockType.CYPRESS_SAPLING, BlockType.DRY_OAK_SAPLING,
                        BlockType.LEAVES, BlockType.PINE_LEAVES, BlockType.ELM_LEAVES, BlockType.CYPRESS_LEAVES,
                        BlockType.DRY_OAK_LEAVES)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());

        /** Loose ground a goblin can scrape out with its hands: dirt and its grassy tops, sand, gravel, clay. */
        static final Set<BlockType> SOIL = Stream.of(
                        BlockType.DIRT, BlockType.GRASS, BlockType.SNOWY_DIRT, BlockType.SWAMPY_GRASS,
                        BlockType.DRY_GRASS, BlockType.SAND, BlockType.RED_SAND, BlockType.GRAVEL, BlockType.CLAY)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());

        /** Natural blocks that are no roof: a canopy, a bush, a cactus — they keep off rain, not the sky. */
        static final Set<BlockType> NOT_A_ROOF = Stream.of(
                        BlockType.LEAVES, BlockType.PINE_LEAVES, BlockType.ELM_LEAVES, BlockType.CYPRESS_LEAVES,
                        BlockType.DRY_OAK_LEAVES, BlockType.CACTUS, BlockType.BLUEBERRY_BUSH,
                        BlockType.CYPRESS_SAPLING, BlockType.DRY_OAK_SAPLING, BlockType.WATER)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Whether {@code block} is air or something the world made; false for an unloaded cell. */
    public static boolean isNatural(BlockType block) {
        return block != null && (block.isAir() || Holder.NATURAL.contains(block));
    }

    /** Whether a goblin may dig {@code block} out: soil only — dirt, sand, gravel, clay — never rock. */
    public static boolean isDiggable(BlockType block) {
        return block != null && Holder.SOIL.contains(block);
    }

    /** Whether {@code block} roofs a camp: solid natural ground — rock, soil, sand, ice — not leaves. */
    public static boolean isGroundRoof(BlockType block) {
        return block != null && block.isSolid() && Holder.NATURAL.contains(block) && !Holder.NOT_A_ROOF.contains(block);
    }
}
