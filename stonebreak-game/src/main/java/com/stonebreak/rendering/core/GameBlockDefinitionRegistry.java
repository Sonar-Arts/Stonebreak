package com.stonebreak.rendering.core;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinition;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinitionRegistry;

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
    
    private final Map<String, BlockDefinition> definitionsByResourceId;
    private final Map<Integer, BlockDefinition> definitionsByNumericId;
    
    public GameBlockDefinitionRegistry() {
        this.definitionsByResourceId = new ConcurrentHashMap<>();
        this.definitionsByNumericId = new ConcurrentHashMap<>();
        
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
        
        System.out.println("[GameBlockDefinitionRegistry] Initialized " + definitionsByResourceId.size() + " block definitions");
    }

    /**
     * Refreshes block definitions for leaf blocks.
     * Use when leaf transparency setting changes.
     */
    public void refreshLeafDefinitions() {
        BlockType[] leafBlocks = {BlockType.LEAVES, BlockType.SNOWY_LEAVES, BlockType.ELM_LEAVES};

        for (BlockType leafBlock : leafBlocks) {
            BlockDefinition newDefinition = createDefinitionForBlockType(leafBlock);
            registerDefinition(newDefinition);
        }

        System.out.println("[GameBlockDefinitionRegistry] Refreshed " + leafBlocks.length + " leaf block definitions");
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
                .numericId(blockType.ordinal())
                .renderType(renderType)
                .renderLayer(renderLayer)
                .build();
    }
    
    /**
     * Determines the appropriate render type for a block type.
     */
    private BlockDefinition.RenderType determineRenderType(BlockType blockType) {
        switch (blockType) {
            case ROSE, DANDELION:
                return BlockDefinition.RenderType.CROSS;
            case GRASS, SNOWY_DIRT:
                return BlockDefinition.RenderType.CUBE_DIRECTIONAL;
            case WOOD, PINE, ELM_WOOD_LOG:
                return BlockDefinition.RenderType.CUBE_DIRECTIONAL; // Wood logs have different top/side textures
            case SANDSTONE, RED_SANDSTONE:
                return BlockDefinition.RenderType.CUBE_DIRECTIONAL; // Different top/side textures
            case WORKBENCH:
                return BlockDefinition.RenderType.CUBE_DIRECTIONAL; // Has unique faces
            default:
                return BlockDefinition.RenderType.CUBE_ALL;
        }
    }
    
    /**
     * Determines the appropriate render layer for a block type.
     */
    private BlockDefinition.RenderLayer determineRenderLayer(BlockType blockType) {
        switch (blockType) {
            case ROSE, DANDELION:
                return BlockDefinition.RenderLayer.CUTOUT; // Transparent flowers
            case LEAVES, SNOWY_LEAVES, ELM_LEAVES:
                // Leaf blocks use cutout only when transparency is enabled
                // When disabled, use opaque to show the black spots
                try {
                    return blockType.isTransparent() ? BlockDefinition.RenderLayer.CUTOUT : BlockDefinition.RenderLayer.OPAQUE;
                } catch (Exception e) {
                    return BlockDefinition.RenderLayer.CUTOUT; // Default to cutout for leaves
                }
            default:
                return BlockDefinition.RenderLayer.OPAQUE;
        }
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
        System.out.println("[GameBlockDefinitionRegistry] Closed and cleaned up resources");
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