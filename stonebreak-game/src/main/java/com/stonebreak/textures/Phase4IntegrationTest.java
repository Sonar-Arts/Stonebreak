package com.stonebreak.textures;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import java.io.File;

/**
 * Phase 4 Integration Test - Validates texture atlas integration components.
 * This test verifies that the texture atlas system can properly load metadata
 * and provide coordinate lookups without requiring OpenGL context.
 */
public class Phase4IntegrationTest {
    
    public static void main(String[] args) {
        System.out.println("=== Phase 4 Texture Atlas Integration Test ===\n");
        
        // Test 1: Atlas metadata loading
        testAtlasMetadataLoading();
        
        // Test 2: Block texture name mapping
        testBlockTextureMapping();
        
        // Test 3: Item texture name mapping  
        testItemTextureMapping();
        
        // Test 4: Atlas files existence
        testAtlasFilesExist();
        
        // Test 5: Cache functionality
        testMetadataCache();
        
        System.out.println("\n=== Integration Test Complete ===");
    }
    
    /**
     * Test atlas metadata loading without OpenGL context.
     */
    private static void testAtlasMetadataLoading() {
        System.out.println("1. Testing Atlas Metadata Loading:");
        
        try {
            File metadataFile = new File("stonebreak-game/src/main/resources/Texture Atlas/atlas_metadata.json");
            if (!metadataFile.exists()) {
                System.out.println("   [ERROR] Atlas metadata file not found: " + metadataFile.getAbsolutePath());
                return;
            }
            
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            AtlasMetadata metadata = objectMapper.readValue(metadataFile, AtlasMetadata.class);
            metadata.initializeLookupMaps();
            
            System.out.println("   [OK] Metadata loaded successfully");
            System.out.println("   [INFO] Atlas version: " + metadata.getAtlasVersion());
            System.out.println("   [INFO] Schema version: " + metadata.getSchemaVersion());
            System.out.println("   [INFO] Texture count: " + metadata.getTextures().size());
            
            if (metadata.getAtlasSize() != null) {
                System.out.println("   [INFO] Atlas size: " + metadata.getAtlasSize().getWidth() + "x" + metadata.getAtlasSize().getHeight());
                System.out.println("   [INFO] Utilization: " + String.format("%.1f%%", metadata.getAtlasSize().getUtilizationPercent()));
            }
            
        } catch (Exception e) {
            System.out.println("   [ERROR] Failed to load metadata: " + e.getMessage());
        }
        
        System.out.println();
    }
    
    /**
     * Test block texture name mapping.
     */
    private static void testBlockTextureMapping() {
        System.out.println("2. Testing Block Texture Mapping:");
        
        // Test a few key block types
        BlockType[] testBlocks = {
            BlockType.GRASS, BlockType.DIRT, BlockType.STONE, 
            BlockType.WOOD, BlockType.WATER, BlockType.WORKBENCH,
            BlockType.LEAVES, BlockType.ICE, BlockType.DANDELION
        };
        
        for (BlockType block : testBlocks) {
            try {
                String textureName = getBlockTextureName(block);
                System.out.println("   [BLOCK] " + block.name() + " -> " + textureName);
            } catch (Exception e) {
                System.out.println("   [ERROR] Error mapping " + block.name() + ": " + e.getMessage());
            }
        }
        
        System.out.println();
    }
    
    /**
     * Test item texture name mapping.
     */
    private static void testItemTextureMapping() {
        System.out.println("3. Testing Item Texture Mapping:");
        
        // Test available item types
        ItemType[] testItems = ItemType.values();
        
        for (ItemType item : testItems) {
            try {
                String textureName = getItemTextureName(item);
                System.out.println("   [ITEM] " + item.name() + " (ID:" + item.getId() + ") -> " + textureName);
            } catch (Exception e) {
                System.out.println("   [ERROR] Error mapping " + item.name() + ": " + e.getMessage());
            }
        }
        
        System.out.println();
    }
    
    /**
     * Test that atlas files exist.
     */
    private static void testAtlasFilesExist() {
        System.out.println("4. Testing Atlas Files Existence:");
        
        String[] files = {
            "stonebreak-game/src/main/resources/Texture Atlas/TextureAtlas.png",
            "stonebreak-game/src/main/resources/Texture Atlas/atlas_metadata.json",
            "stonebreak-game/src/main/resources/Texture Atlas/texture_checksums.json"
        };
        
        for (String filePath : files) {
            File file = new File(filePath);
            if (file.exists()) {
                long size = file.length();
                System.out.println("   [OK] " + file.getName() + " (" + size + " bytes)");
            } else {
                System.out.println("   [ERROR] Missing: " + file.getName());
            }
        }
        
        System.out.println();
    }
    
    /**
     * Test metadata cache functionality.
     */
    private static void testMetadataCache() {
        System.out.println("5. Testing Metadata Cache:");
        
        try {
            AtlasMetadataCache cache = new AtlasMetadataCache();
            
            // Test cache put/get
            AtlasMetadataCache.TextureCoordinates testCoords = 
                new AtlasMetadataCache.TextureCoordinates(
                    "test_texture", 0.0f, 0.0f, 0.5f, 0.5f, 
                    0, 0, 16, 16, TextureResourceLoader.TextureType.BLOCK_UNIFORM
                );
            
            cache.put("test_key", testCoords);
            AtlasMetadataCache.TextureCoordinates retrieved = cache.get("test_key");
            
            if (retrieved != null && "test_texture".equals(retrieved.textureName)) {
                System.out.println("   [OK] Cache put/get working");
                System.out.println("   [INFO] Retrieved: " + retrieved.toString());
            } else {
                System.out.println("   [ERROR] Cache put/get failed");
            }
            
            // Test cache statistics
            AtlasMetadataCache.CacheStats stats = cache.getStats();
            System.out.println("   [INFO] Cache stats: " + stats.toString());
            
        } catch (Exception e) {
            System.out.println("   [ERROR] Cache test failed: " + e.getMessage());
        }
        
        System.out.println();
    }
    
    /**
     * Map BlockType enum to texture names (mirrors TextureAtlas implementation).
     */
    private static String getBlockTextureName(BlockType blockType) {
        if (blockType == null) return null;
        
        switch (blockType) {
            case GRASS: return "grass_block";
            case DIRT: return "dirt_block"; // Fixed: atlas has dirt_block_* textures
            case STONE: return "stone";
            case WOOD: return "wood";
            case SAND: return "sand";
            case WATER: return "water_temp"; // Fixed: atlas has water_temp_* textures
            case COAL_ORE: return "coal_ore";
            case IRON_ORE: return "iron_ore";
            case LEAVES: return "leaves";
            case BEDROCK: return "bedrock";
            case ICE: return "ice";
            case SNOW: return "snow";
            case DANDELION: return "dandelion";
            case ROSE: return "rose"; // Fixed: atlas has rose_* textures
            case ELM_WOOD_LOG: return "elm_wood_log";
            case MAGMA: return "magma";
            case WORKBENCH: return "workbench_custom"; // Fixed: atlas has workbench_custom_* textures
            case PINE: return "pine_wood";
            default: return blockType.name().toLowerCase();
        }
    }
    
    /**
     * Map ItemType enum to texture names (mirrors TextureAtlas implementation).
     */
    private static String getItemTextureName(ItemType itemType) {
        if (itemType == null) return null;
        
        switch (itemType) {
            case STICK: return "stick";
            case WOODEN_PICKAXE: return "wooden_pickaxe";
            default: return itemType.name().toLowerCase();
        }
    }
}