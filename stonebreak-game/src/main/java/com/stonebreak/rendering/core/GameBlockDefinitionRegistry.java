package com.stonebreak.rendering.core;

import com.stonebreak.blocks.BlockType;
import com.openmason.engine.rendering.cbr.models.BlockDefinition;
import com.openmason.engine.rendering.cbr.models.BlockDefinitionRegistry;
import com.stonebreak.rendering.sbo.SBOBlockBridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Game-specific implementation of BlockDefinitionRegistry.
 * Provides block definitions for the main Stonebreak game, mapping BlockTypes
 * to BlockDefinitions for use with the CBR (Common Block Resources) system.
 * 
 * This registry supports all block types used in the game and provides
 * proper render type mappings for different block categories.
 */
public class GameBlockDefinitionRegistry implements BlockDefinitionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(GameBlockDefinitionRegistry.class);

    private final Map<String, BlockDefinition> definitionsByResourceId;
    private final Map<Integer, BlockDefinition> definitionsByNumericId;
    private final SBOBlockBridge sboBlockBridge;

    public GameBlockDefinitionRegistry() {
        this(null);
    }

    /**
     * Construct the registry. Render layers are resolved by
     * {@link BlockType#getRenderLayer()} from each block's SBO, so the bridge
     * is no longer consulted for them; the parameter is kept so existing call
     * sites (and future SBO-driven lookups) stay stable.
     *
     * @param sboBlockBridge optional SBO bridge; may be {@code null}
     */
    public GameBlockDefinitionRegistry(SBOBlockBridge sboBlockBridge) {
        this.definitionsByResourceId = new ConcurrentHashMap<>();
        this.definitionsByNumericId = new ConcurrentHashMap<>();
        this.sboBlockBridge = sboBlockBridge;

        initializeBlockDefinitions();
    }
    
    /**
     * Initializes all block definitions for the game.
     * Maps BlockTypes to their corresponding BlockDefinitions with appropriate render types.
     */
    private void initializeBlockDefinitions() {
        // Initialize definitions for all BlockTypes
        for (BlockType blockType : BlockType.values()) {
            BlockDefinition definition = createDefinitionForBlockType(blockType);
            registerDefinition(definition);
        }
        
        logger.debug("[GameBlockDefinitionRegistry] Initialized {} block definitions", definitionsByResourceId.size());
    }

    /**
     * Refreshes block definitions for leaf blocks.
     * Use when leaf transparency setting changes.
     */
    public void refreshLeafDefinitions() {
        BlockType[] leafBlocks = {BlockType.LEAVES, BlockType.PINE_LEAVES, BlockType.ELM_LEAVES};

        for (BlockType leafBlock : leafBlocks) {
            BlockDefinition newDefinition = createDefinitionForBlockType(leafBlock);
            registerDefinition(newDefinition);
        }

        logger.debug("[GameBlockDefinitionRegistry] Refreshed {} leaf block definitions", leafBlocks.length);
    }
    
    /**
     * Creates a BlockDefinition for a given BlockType.
     */
    private BlockDefinition createDefinitionForBlockType(BlockType blockType) {
        String resourceId = "stonebreak:" + blockType.name().toLowerCase();
        BlockDefinition.RenderType renderType = determineRenderType(blockType);
        BlockDefinition.RenderLayer renderLayer = determineRenderLayer(blockType);
        
        return new BlockDefinition.Builder()
                .resourceId(resourceId)
                .numericId(blockType.getId())
                .renderType(renderType)
                .renderLayer(renderLayer)
                .build();
    }
    
    /**
     * Determines the appropriate render type for a block type.
     */
    private BlockDefinition.RenderType determineRenderType(BlockType blockType) {
        // Flowers / cross-plane blocks are sourced from BlockType.isFlower() so
        // new flowers only need to be added to that one registry method.
        if (blockType.isFlower()) {
            return BlockDefinition.RenderType.CROSS;
        }
        // Reference equality on the static-final instances. Directional blocks
        // have different top/side/bottom textures and need per-face mesh data.
        if (blockType == BlockType.GRASS || blockType == BlockType.SNOWY_DIRT
                || blockType == BlockType.WOOD || blockType == BlockType.PINE
                || blockType == BlockType.ELM_WOOD_LOG
                || blockType == BlockType.SANDSTONE || blockType == BlockType.RED_SANDSTONE
                || blockType == BlockType.WORKBENCH
                || blockType == BlockType.FURNACE) {
            return BlockDefinition.RenderType.CUBE_DIRECTIONAL;
        }
        return BlockDefinition.RenderType.CUBE_ALL;
    }
    
    /**
     * Determines the render layer for a block type. The SBO is the source of
     * truth ({@link BlockType#getRenderLayer()} — manifest {@code renderLayer},
     * authored face materials, and the leaf-transparency setting for foliage);
     * there is no per-block list here. Cross-plane geometry can never be drawn
     * opaque (its empty texels would show as black), so a flower whose asset
     * says OPAQUE is lifted to CUTOUT.
     */
    private BlockDefinition.RenderLayer determineRenderLayer(BlockType blockType) {
        BlockDefinition.RenderLayer layer = blockType.getRenderLayer();
        if (layer == BlockDefinition.RenderLayer.OPAQUE && blockType.isFlower()) {
            return BlockDefinition.RenderLayer.CUTOUT;
        }
        return layer;
    }
    
    /**
     * Formats a block type name for display.
     */
    private String formatDisplayName(String name) {
        return Arrays.stream(name.split("_"))
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase())
                .reduce((a, b) -> a + " " + b)
                .orElse(name);
    }
    
    // BlockDefinitionRegistry implementation
    
    @Override
    public Optional<BlockDefinition> getDefinition(String resourceId) {
        return Optional.ofNullable(definitionsByResourceId.get(resourceId));
    }
    
    @Override
    public Optional<BlockDefinition> getDefinition(int numericId) {
        return Optional.ofNullable(definitionsByNumericId.get(numericId));
    }
    
    @Override
    public boolean hasDefinition(String resourceId) {
        return definitionsByResourceId.containsKey(resourceId);
    }
    
    @Override
    public boolean hasDefinition(int numericId) {
        return definitionsByNumericId.containsKey(numericId);
    }
    
    @Override
    public Collection<BlockDefinition> getAllDefinitions() {
        return new ArrayList<>(definitionsByResourceId.values());
    }
    
    @Override
    public Collection<BlockDefinition> getDefinitionsByNamespace(String namespace) {
        return definitionsByResourceId.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(namespace + ":"))
                .map(Map.Entry::getValue)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
    
    @Override
    public int getDefinitionCount() {
        return definitionsByResourceId.size();
    }
    
    @Override
    public void registerDefinition(BlockDefinition definition) {
        definitionsByResourceId.put(definition.getResourceId(), definition);
        definitionsByNumericId.put(definition.getNumericId(), definition);
    }
    
    @Override
    public boolean isModifiable() {
        return true; // Allow runtime modifications if needed
    }
    
    @Override
    public String getSchemaVersion() {
        return "1.0.0";
    }
    
    @Override
    public void close() {
        definitionsByResourceId.clear();
        definitionsByNumericId.clear();
        logger.debug("[GameBlockDefinitionRegistry] Closed and cleaned up resources");
    }
    
    /**
     * Gets a BlockDefinition for a specific BlockType (convenience method).
     */
    public Optional<BlockDefinition> getDefinitionForBlockType(BlockType blockType) {
        String resourceId = "stonebreak:" + blockType.name().toLowerCase();
        return getDefinition(resourceId);
    }
    
    /**
     * Checks if a BlockType has a definition registered.
     */
    public boolean hasDefinitionForBlockType(BlockType blockType) {
        String resourceId = "stonebreak:" + blockType.name().toLowerCase();
        return hasDefinition(resourceId);
    }
}