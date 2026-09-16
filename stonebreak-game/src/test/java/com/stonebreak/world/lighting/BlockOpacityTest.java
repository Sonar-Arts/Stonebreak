package com.stonebreak.world.lighting;

import com.stonebreak.blocks.BlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one predicate deciding which blocks raise the sky-light heightmap.
 *
 * <p>Thin shaped formations must NOT: a cactus or stalagmite that counted as an
 * occluder would shade its own lower cells (a stacked cactus rendered dark below
 * the seam) and the ground it stands on, exactly like flowers and torches would.
 * Full cubes and shaped blocks that fill their cell's silhouette (stairs) do.
 */
class BlockOpacityTest {

    @Test
    void thinShapedFormationsPassSkyLight() {
        assertFalse(BlockOpacity.isOpaque(BlockType.CACTUS), "a cactus must not shade its own stack or the sand under it");
        assertFalse(BlockOpacity.isOpaque(BlockType.LIMESTONE_STALAGMITE));
        assertFalse(BlockOpacity.isOpaque(BlockType.TORCH_PLACED));
        assertFalse(BlockOpacity.isOpaque(BlockType.ROSE));
        assertFalse(BlockOpacity.isOpaque(BlockType.AIR));
        assertFalse(BlockOpacity.isOpaque(null));
    }

    @Test
    void cubesAndCellFillingShapesOccludeSkyLight() {
        assertTrue(BlockOpacity.isOpaque(BlockType.SAND));
        assertTrue(BlockOpacity.isOpaque(BlockType.STONE));
        assertTrue(BlockOpacity.isOpaque(BlockType.OAK_STAIRS));
    }
}
