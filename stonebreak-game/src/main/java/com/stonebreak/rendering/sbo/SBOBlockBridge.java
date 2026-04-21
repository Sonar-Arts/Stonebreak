package com.stonebreak.rendering.sbo;

import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Bridges SBO object IDs to BlockType enum values.
 * When an SBO block is registered, its texture data overrides
 * the legacy atlas coordinates for that BlockType.
 *
 * <p>Mapping is based on the SBO manifest's {@code objectId} field,
 * which follows the format {@code "stonebreak:blockname"}.
 */
public class SBOBlockBridge {

    private static final Logger logger = LoggerFactory.getLogger(SBOBlockBridge.class);

    /**
     * Maps objectId suffixes to BlockType.
     * e.g., "stonebreak:dirt" -> BlockType.DIRT
     */
    private static final Map<String, BlockType> OBJECT_ID_TO_BLOCK_TYPE = Map.ofEntries(
            Map.entry("stonebreak:dirt", BlockType.DIRT),
            Map.entry("stonebreak:stone", BlockType.STONE),
            Map.entry("stonebreak:grass", BlockType.GRASS),
            Map.entry("stonebreak:sand", BlockType.SAND),
            Map.entry("stonebreak:red_sand", BlockType.RED_SAND),
            Map.entry("stonebreak:gravel", BlockType.GRAVEL),
            Map.entry("stonebreak:cobblestone", BlockType.COBBLESTONE),
            Map.entry("stonebreak:coal_ore", BlockType.COAL_ORE),
            Map.entry("stonebreak:wood", BlockType.WOOD),
            Map.entry("stonebreak:pine_wood_log", BlockType.PINE),
            Map.entry("stonebreak:elm_wood_log", BlockType.ELM_WOOD_LOG),
            Map.entry("stonebreak:leaves", BlockType.LEAVES),
            Map.entry("stonebreak:pine_leaves", BlockType.PINE_LEAVES),
            Map.entry("stonebreak:elm_leaves", BlockType.ELM_LEAVES),
            Map.entry("stonebreak:wood_planks", BlockType.WOOD_PLANKS),
            Map.entry("stonebreak:pine_wood_planks", BlockType.PINE_WOOD_PLANKS),
            Map.entry("stonebreak:elm_wood_planks", BlockType.ELM_WOOD_PLANKS),
            Map.entry("stonebreak:water", BlockType.WATER),
            Map.entry("stonebreak:sand_stone", BlockType.SANDSTONE),
            Map.entry("stonebreak:red_sand_stone", BlockType.RED_SANDSTONE),
            Map.entry("stonebreak:sand_cobblestone", BlockType.SAND_COBBLESTONE),
            Map.entry("stonebreak:red_sand_cobblestone", BlockType.RED_SAND_COBBLESTONE),
            Map.entry("stonebreak:rose", BlockType.ROSE),
            Map.entry("stonebreak:dandelion", BlockType.DANDELION),
            Map.entry("stonebreak:wildgrass", BlockType.WILDGRASS),
            Map.entry("stonebreak:clay", BlockType.CLAY),
            Map.entry("stonebreak:snow", BlockType.SNOW),
            Map.entry("stonebreak:ice", BlockType.ICE),
            Map.entry("stonebreak:bedrock", BlockType.BEDROCK),
            Map.entry("stonebreak:crystal", BlockType.CRYSTAL),
            Map.entry("stonebreak:iron_ore", BlockType.IRON_ORE),
            Map.entry("stonebreak:magma", BlockType.MAGMA),
            Map.entry("stonebreak:workbench", BlockType.WORKBENCH)
    );

    private final EnumMap<BlockType, SBOParseResult> sboByBlockType = new EnumMap<>(BlockType.class);

    /**
     * Initialize the bridge from a populated registry.
     * Maps each SBO's objectId to the corresponding BlockType.
     *
     * @param registry the loaded SBO block registry
     */
    public void initialize(SBOBlockRegistry registry) {
        sboByBlockType.clear();

        for (SBOParseResult result : registry.getAll()) {
            String objectId = result.getObjectId();
            BlockType blockType = OBJECT_ID_TO_BLOCK_TYPE.get(objectId);
            if (blockType != null) {
                sboByBlockType.put(blockType, result);
                logger.info("Bridged SBO {} -> BlockType.{}", objectId, blockType.name());
            } else {
                logger.debug("No BlockType mapping for SBO objectId: {}", objectId);
            }
        }

        logger.info("SBO bridge: {} blocks mapped", sboByBlockType.size());
    }

    /**
     * Check if a BlockType has an SBO override.
     *
     * @param blockType the block type to check
     * @return true if an SBO definition exists for this block type
     */
    public boolean isSBOBlock(BlockType blockType) {
        return sboByBlockType.containsKey(blockType);
    }

    /**
     * Get the SBO parse result for a BlockType.
     *
     * @param blockType the block type
     * @return the SBO data, or null if not an SBO block
     */
    public SBOParseResult getSBODefinition(BlockType blockType) {
        return sboByBlockType.get(blockType);
    }

    /**
     * Get the number of bridged SBO blocks.
     */
    public int size() {
        return sboByBlockType.size();
    }

    /**
     * Resolve the most permissive render layer explicitly declared by an
     * SBO's materials. TRANSLUCENT dominates CUTOUT.
     *
     * <p>Returns {@code null} when the SBO either isn't registered or its
     * materials only declare OPAQUE (or nothing at all). This lets the
     * caller fall back to legacy hardcoded rules for blocks whose SBO
     * doesn't opt into a non-opaque layer — important during the transition
     * period where older SBOs may be missing the renderLayer field.
     *
     * @param blockType the block type
     * @return the resolved render layer, or {@code null} if no non-opaque
     *         declaration was found
     */
    public BlockDefinition.RenderLayer getRenderLayer(BlockType blockType) {
        SBOParseResult result = sboByBlockType.get(blockType);
        if (result == null || result.materials() == null || result.materials().isEmpty()) {
            return null;
        }

        BlockDefinition.RenderLayer resolved = null;
        for (ParsedMaterialData material : result.materials()) {
            BlockDefinition.RenderLayer layer = parseRenderLayer(material.renderLayer());
            if (layer == BlockDefinition.RenderLayer.TRANSLUCENT) {
                return BlockDefinition.RenderLayer.TRANSLUCENT;
            }
            if (layer == BlockDefinition.RenderLayer.CUTOUT) {
                resolved = BlockDefinition.RenderLayer.CUTOUT;
            }
        }
        return resolved;
    }

    private static BlockDefinition.RenderLayer parseRenderLayer(String raw) {
        if (raw == null) return BlockDefinition.RenderLayer.OPAQUE;
        return switch (raw.trim().toUpperCase()) {
            case "TRANSLUCENT" -> BlockDefinition.RenderLayer.TRANSLUCENT;
            case "CUTOUT" -> BlockDefinition.RenderLayer.CUTOUT;
            default -> BlockDefinition.RenderLayer.OPAQUE;
        };
    }
}
