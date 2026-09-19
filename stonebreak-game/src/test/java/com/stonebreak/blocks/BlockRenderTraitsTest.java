package com.stonebreak.blocks;

import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.rendering.cbr.models.BlockDefinition.RenderLayer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SBO is the only source for which render pass a block needs and whether it
 * can hide a neighbour's face. These pin the resolution rules on raw manifest
 * pieces, with no registry or renderer.
 */
class BlockRenderTraitsTest {

    private static SBOFormat.GameProperties props(String renderLayer, boolean transparent) {
        return new SBOFormat.GameProperties(1, 1f, true, true, 0, 0,
                renderLayer, transparent, false, false, 64, "BLOCKS", true);
    }

    private static ParsedMaterialData material(String renderLayer) {
        return new ParsedMaterialData(100, "m", "m.png", null, renderLayer, false, 0);
    }

    @Test
    void manifestRenderLayerSelectsThePass() {
        BlockRenderTraits glass = BlockRenderTraits.resolve(props("TRANSLUCENT", true), List.of(), false);
        assertEquals(RenderLayer.TRANSLUCENT, glass.renderLayer());
        assertTrue(glass.translucent());
        assertTrue(glass.transparent());

        BlockRenderTraits leaves = BlockRenderTraits.resolve(props("CUTOUT", true), List.of(), false);
        assertEquals(RenderLayer.CUTOUT, leaves.renderLayer());
        assertFalse(leaves.translucent());
        assertTrue(leaves.transparent());

        BlockRenderTraits stone = BlockRenderTraits.resolve(props("OPAQUE", false), List.of(), false);
        assertEquals(BlockRenderTraits.OPAQUE_CUBE, stone);
    }

    @Test
    void nonOpaqueLayerImpliesTransparencyEvenIfFlagIsOff() {
        // A blended or alpha-tested block is see-through by definition: hiding
        // the faces behind it would leave holes visible through the block.
        BlockRenderTraits t = BlockRenderTraits.resolve(props("TRANSLUCENT", false), List.of(), false);
        assertTrue(t.transparent());
        assertTrue(BlockRenderTraits.resolve(props("CUTOUT", false), List.of(), false).transparent());
    }

    @Test
    void transparentFlagAloneKeepsTheOpaquePass() {
        // A see-through but fully opaque-textured shape (open lattice) stays in
        // the opaque pass — transparency is about occlusion, not blending.
        BlockRenderTraits t = BlockRenderTraits.resolve(props("OPAQUE", true), List.of(), false);
        assertEquals(RenderLayer.OPAQUE, t.renderLayer());
        assertTrue(t.transparent());
    }

    @Test
    void authoredFaceMaterialsCanLiftTheLayer() {
        // One translucent face material is enough to route the block through
        // the blended pass; the manifest never lowers what a material asked for.
        BlockRenderTraits t = BlockRenderTraits.resolve(props("OPAQUE", false),
                List.of(material("OPAQUE"), material("translucent"), material(null)), false);
        assertEquals(RenderLayer.TRANSLUCENT, t.renderLayer());
        assertTrue(t.transparent());

        BlockRenderTraits cutout = BlockRenderTraits.resolve(props("CUTOUT", true),
                List.of(material("OPAQUE")), false);
        assertEquals(RenderLayer.CUTOUT, cutout.renderLayer(), "materials can only raise, never lower");
    }

    @Test
    void animatedBlocksNeverOccludeNeighbours() {
        // Drawn per frame by the AnimatedBlockRenderer, not baked into the chunk
        // mesh — the mesh must keep every face bordering the cell.
        BlockRenderTraits door = BlockRenderTraits.resolve(props("OPAQUE", false), List.of(), true);
        assertTrue(door.transparent());
        assertTrue(door.animated());
        assertEquals(RenderLayer.OPAQUE, door.renderLayer());
    }

    @Test
    void legacyAssetsWithoutMetadataAreOpaqueCubes() {
        assertEquals(BlockRenderTraits.OPAQUE_CUBE, BlockRenderTraits.resolve(null, null, false));
        assertEquals(RenderLayer.OPAQUE, BlockRenderTraits.parseRenderLayer("not-a-layer"));
        assertEquals(RenderLayer.OPAQUE, BlockRenderTraits.parseRenderLayer(null));
        assertEquals(RenderLayer.CUTOUT, BlockRenderTraits.parseRenderLayer(" cutout "));
    }
}
