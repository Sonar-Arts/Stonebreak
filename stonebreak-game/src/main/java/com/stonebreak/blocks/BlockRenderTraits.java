package com.stonebreak.blocks;

import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.rendering.cbr.models.BlockDefinition;
import com.stonebreak.blocks.registry.BlockRegistry;

import java.util.List;

/**
 * How a block takes part in the chunk render passes, resolved once from its
 * SBO. This is the single source of truth for "does this block need the
 * cutout / translucent pass" and "can this block hide a neighbour's face" —
 * there is deliberately no code-side list of transparent block types.
 *
 * <p>Sources, most permissive wins ({@code TRANSLUCENT > CUTOUT > OPAQUE}):
 * <ul>
 *   <li>{@code gameProperties.renderLayer} in the SBO manifest (the Open Mason
 *       SBO editor's "Render Layer" combo), and</li>
 *   <li>the {@code renderLayer} of every face material authored in the
 *       embedded OMO, so a single face material marked translucent is enough
 *       to route the block through the blended pass.</li>
 * </ul>
 *
 * <p>{@link #transparent()} is true when the block must not occlude the faces
 * of the blocks around it: authored {@code gameProperties.transparent}, or any
 * non-opaque render layer (a translucent or cutout block is see-through by
 * definition), or an animated block (its model is drawn per frame by the
 * AnimatedBlockRenderer, not baked into the chunk mesh, so the mesh has to
 * keep every face that borders the cell).
 *
 * @param renderLayer pass the block's chunk-mesh geometry is drawn in
 * @param transparent whether neighbouring faces stay visible through this block
 * @param animated    whether the SBO embeds per-state animation clips
 */
public record BlockRenderTraits(
        BlockDefinition.RenderLayer renderLayer,
        boolean transparent,
        boolean animated
) {

    /** A plain full cube: opaque, occluding, static. */
    public static final BlockRenderTraits OPAQUE_CUBE =
            new BlockRenderTraits(BlockDefinition.RenderLayer.OPAQUE, false, false);

    public BlockRenderTraits {
        renderLayer = renderLayer == null ? BlockDefinition.RenderLayer.OPAQUE : renderLayer;
    }

    /** True when the block is drawn in the alpha-blended (sorted) pass. */
    public boolean translucent() {
        return renderLayer == BlockDefinition.RenderLayer.TRANSLUCENT;
    }

    /** Resolve from a registered SBO block entry. */
    public static BlockRenderTraits from(BlockRegistry.BlockEntry entry) {
        List<ParsedMaterialData> materials = entry.sboData() != null ? entry.sboData().materials() : null;
        boolean animated = entry.sboData() != null && entry.sboData().hasAnimations();
        return resolve(entry.properties(), materials, animated);
    }

    /**
     * Resolve from the raw SBO pieces. {@code properties} and {@code materials}
     * may be null (legacy assets), in which case the block is an opaque cube.
     */
    public static BlockRenderTraits resolve(SBOFormat.GameProperties properties,
                                            List<ParsedMaterialData> materials,
                                            boolean animated) {
        BlockDefinition.RenderLayer layer = properties != null
                ? parseRenderLayer(properties.renderLayer())
                : BlockDefinition.RenderLayer.OPAQUE;
        if (materials != null) {
            for (ParsedMaterialData material : materials) {
                layer = mostPermissive(layer, parseRenderLayer(material.renderLayer()));
            }
        }
        boolean transparent = (properties != null && properties.transparent())
                || layer != BlockDefinition.RenderLayer.OPAQUE
                || animated;
        return new BlockRenderTraits(layer, transparent, animated);
    }

    /** Parse an SBO/OMO render-layer string; unknown or missing values are OPAQUE. */
    public static BlockDefinition.RenderLayer parseRenderLayer(String raw) {
        if (raw == null) return BlockDefinition.RenderLayer.OPAQUE;
        return switch (raw.trim().toUpperCase()) {
            case "TRANSLUCENT" -> BlockDefinition.RenderLayer.TRANSLUCENT;
            case "CUTOUT" -> BlockDefinition.RenderLayer.CUTOUT;
            default -> BlockDefinition.RenderLayer.OPAQUE;
        };
    }

    private static BlockDefinition.RenderLayer mostPermissive(BlockDefinition.RenderLayer a,
                                                              BlockDefinition.RenderLayer b) {
        // Enum order is OPAQUE < CUTOUT < TRANSLUCENT.
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
