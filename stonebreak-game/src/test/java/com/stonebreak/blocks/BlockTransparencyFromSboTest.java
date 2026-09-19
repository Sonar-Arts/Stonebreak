package com.stonebreak.blocks;

import com.openmason.engine.rendering.cbr.models.BlockDefinition.RenderLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped SBO assets, read through {@link BlockType}, decide the transparency
 * pass — no code-side list. These pin what the shipped manifests declare so a
 * silently regressed asset (a glass re-exported as OPAQUE) fails here rather than
 * showing up as a solid-looking window in game.
 */
class BlockTransparencyFromSboTest {

    @Test
    void glassIsTranslucentAndSeeThroughFromItsSbo() {
        BlockType glass = BlockType.getByObjectId("stonebreak:glass");
        assertNotNull(glass, "SB_Glass.sbo must register");
        assertEquals(RenderLayer.TRANSLUCENT, glass.getRenderLayer());
        assertTrue(glass.isTranslucent());
        assertTrue(glass.isTransparent());
        assertTrue(glass.isAuthoredTransparent());
    }

    @Test
    void iceIsTranslucentFromItsSbo() {
        assertTrue(BlockType.ICE.isTranslucent());
        assertTrue(BlockType.ICE.isTransparent());
    }

    @Test
    void cutoutAssetsAreTransparentButNotTranslucent() {
        for (BlockType bt : new BlockType[]{BlockType.SNOW, BlockType.TORCH_PLACED,
                BlockType.ROSE, BlockType.DANDELION, BlockType.WILDGRASS}) {
            assertEquals(RenderLayer.CUTOUT, bt.getRenderLayer(), bt.name());
            assertTrue(bt.isTransparent(), bt.name());
            assertFalse(bt.isTranslucent(), bt.name());
        }
    }

    @Test
    void animatedDoorNeverOccludesItsNeighbours() {
        assertTrue(BlockType.OAK_DOOR.isAnimated());
        assertTrue(BlockType.OAK_DOOR.isTransparent());
        assertEquals(RenderLayer.OPAQUE, BlockType.OAK_DOOR.getRenderLayer());
    }

    @Test
    void plainCubesStayOpaqueOccluders() {
        for (BlockType bt : new BlockType[]{BlockType.STONE, BlockType.DIRT, BlockType.SAND,
                BlockType.OAK_STAIRS, BlockType.CACTUS, BlockType.WORKBENCH}) {
            assertEquals(RenderLayer.OPAQUE, bt.getRenderLayer(), bt.name());
            assertFalse(bt.isTransparent(), bt.name());
            assertFalse(bt.isTranslucent(), bt.name());
            assertFalse(bt.isAnimated(), bt.name());
        }
    }

    @Test
    void sentinelsAreSeeThrough() {
        assertTrue(BlockType.AIR.isTransparent());
        assertTrue(BlockType.WATER.isTransparent());
        assertTrue(BlockType.WATER.isTranslucent());
    }

    @Test
    void everyNonOpaqueBlockIsTransparent() {
        // Invariant the mesher relies on: a block drawn in the cutout or
        // blended pass can never hide the faces behind it.
        for (BlockType bt : BlockType.values()) {
            if (bt.getRenderTraits().renderLayer() != RenderLayer.OPAQUE) {
                assertTrue(bt.isAuthoredTransparent(), bt.name() + " is non-opaque but claims to occlude");
            }
        }
    }
}
