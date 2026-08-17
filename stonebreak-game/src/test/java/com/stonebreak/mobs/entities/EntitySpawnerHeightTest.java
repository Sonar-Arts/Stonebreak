package com.stonebreak.mobs.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Standability rule for passive spawns: solid, non-trunk footing below the stand cell and two
 * passable cells for the body.
 *
 * <p><b>Why this exists.</b> The spawner used to scan a hardcoded y 60..120 band sized for the old
 * 256-tall world. When diffusion terrain raised {@code WORLD_HEIGHT} to 1024 and {@code SEA_LEVEL}
 * to 320, surfaces moved to roughly y 340-500 and the scan window fell hundreds of blocks into
 * solid stone, so surface animals silently stopped spawning. The rule is now height-agnostic —
 * {@link #standabilityIsIndependentOfAbsoluteHeight()} is the test that would have caught it and
 * that fails if any absolute-Y band creeps back in.
 */
class EntitySpawnerHeightTest {

    /** Typical diffusion-era land surface: the stand cell sits well above the old 60..120 band. */
    private static final int SURFACE_Y = 400;

    /** A column of {@code fill} up to (but excluding) {@code standY}, and air from there up. */
    private static EntitySpawner.ColumnBlocks ground(int standY, BlockType fill) {
        return y -> y < standY ? fill : BlockType.AIR;
    }

    /** {@link #ground} with {@code cover} placed in the stand cell itself. */
    private static EntitySpawner.ColumnBlocks groundWithCover(int standY, BlockType fill, BlockType cover) {
        return y -> {
            if (y < standY) return fill;
            if (y == standY) return cover;
            return BlockType.AIR;
        };
    }

    @Test
    void grassSurfaceIsStandable() {
        assertTrue(EntitySpawner.isStandable(SURFACE_Y, ground(SURFACE_Y, BlockType.GRASS)));
    }

    @Test
    void standabilityIsIndependentOfAbsoluteHeight() {
        // The same column shape must read identically wherever the terrain puts it. A reintroduced
        // MIN/MAX_SPAWN_HEIGHT band would make at least one of these disagree.
        for (int standY : new int[] {1, 100, 320, SURFACE_Y, 800, WorldConfiguration.WORLD_HEIGHT - 2}) {
            assertTrue(EntitySpawner.isStandable(standY, ground(standY, BlockType.GRASS)),
                    "grass surface must be standable at y=" + standY);
        }
    }

    @Test
    void buriedInSolidStoneIsNotStandable() {
        assertFalse(EntitySpawner.isStandable(SURFACE_Y, y -> BlockType.STONE));
    }

    @Test
    void seabedUnderWaterIsNotStandable() {
        // The heightmap points at the seabed for a submerged column; the water above rejects it,
        // so no separate water-level query is needed.
        assertFalse(EntitySpawner.isStandable(SURFACE_Y,
                groundWithCover(SURFACE_Y, BlockType.SAND, BlockType.WATER)));
    }

    @Test
    void treeCanopyIsNotStandable() {
        for (BlockType leaves : new BlockType[] {BlockType.LEAVES, BlockType.PINE_LEAVES, BlockType.ELM_LEAVES}) {
            assertFalse(EntitySpawner.isStandable(SURFACE_Y, ground(SURFACE_Y, leaves)),
                    "must not stand on " + leaves.name());
        }
    }

    @Test
    void trunkTopIsNotStandable() {
        for (BlockType log : new BlockType[] {BlockType.WOOD, BlockType.PINE, BlockType.ELM_WOOD_LOG}) {
            assertFalse(EntitySpawner.isStandable(SURFACE_Y, ground(SURFACE_Y, log)),
                    "must not stand on " + log.name());
        }
    }

    @Test
    void snowLayerOverGroundIsStandable() {
        assertTrue(EntitySpawner.isStandable(SURFACE_Y,
                groundWithCover(SURFACE_Y, BlockType.SNOWY_DIRT, BlockType.SNOW)));
    }

    @Test
    void flowersDoNotBlockTheStandCell() {
        for (BlockType flower : new BlockType[] {BlockType.ROSE, BlockType.DANDELION, BlockType.WILDGRASS}) {
            assertTrue(EntitySpawner.isStandable(SURFACE_Y,
                            groundWithCover(SURFACE_Y, BlockType.GRASS, flower)),
                    "must stand in " + flower.name());
        }
    }

    @Test
    void headroomIsTwoCells() {
        // Solid footing, one air cell, then stone at head+1 — too tight for a mob.
        assertFalse(EntitySpawner.isStandable(SURFACE_Y, y -> {
            if (y < SURFACE_Y) return BlockType.STONE;
            if (y == SURFACE_Y) return BlockType.AIR;
            return BlockType.STONE;
        }));
    }

    @Test
    void airUnderfootIsNotStandable() {
        assertFalse(EntitySpawner.isStandable(SURFACE_Y, y -> BlockType.AIR));
    }

    @Test
    void worldBoundsAreRejected() {
        assertFalse(EntitySpawner.isStandable(0, ground(0, BlockType.GRASS)));
        assertFalse(EntitySpawner.isStandable(-1, ground(-1, BlockType.GRASS)));
        assertFalse(EntitySpawner.isStandable(WorldConfiguration.WORLD_HEIGHT - 1,
                ground(WorldConfiguration.WORLD_HEIGHT - 1, BlockType.GRASS)));
    }

    @Test
    void nullBlocksAreTreatedAsAir() {
        // World.getBlockAt can return null defensively; a null floor is not footing, and a null
        // head cell must not block a spawn.
        assertFalse(EntitySpawner.isStandable(SURFACE_Y, y -> null));
        assertTrue(EntitySpawner.isStandable(SURFACE_Y, y -> y < SURFACE_Y ? BlockType.GRASS : null));
    }
}
