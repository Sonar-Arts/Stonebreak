package com.stonebreak.textures;

/**
 * Simple test class to verify atlas generation functionality.
 * This is for manual testing during development - not a unit test framework.
 */
public class AtlasGenerationTest {
    
    public static void main(String[] args) {
        System.out.println("=== Texture Atlas Generation Test ===");
        
        TextureAtlasBuilder builder = new TextureAtlasBuilder();
        
        try {
            // Test 1: Check if atlas needs regeneration
            System.out.println("\n1. Checking if atlas regeneration is needed...");
            boolean needsRegeneration = builder.shouldRegenerateAtlas();
            System.out.println("Atlas regeneration needed: " + needsRegeneration);
            
            // Test 2: Generate atlas
            System.out.println("\n2. Generating texture atlas...");
            boolean success = builder.generateAtlas();
            
            if (success) {
                System.out.println("✓ Atlas generation completed successfully!");
                
                // Test 3: Verify atlas can be loaded
                System.out.println("\n3. Testing atlas metadata access...");
                testMetadataAccess(builder);
                
            } else {
                System.err.println("✗ Atlas generation failed!");
                return;
            }
            
        } catch (Exception e) {
            System.err.println("Test failed with exception: " + e.getMessage());
            e.printStackTrace();
            
        } finally {
            // Cleanup
            builder.shutdown();
        }
        
        System.out.println("\n=== Test Complete ===");
    }
    
    /**
     * Test metadata access functionality.
     */
    private static void testMetadataAccess(TextureAtlasBuilder builder) {
        // Test some common texture names (individual items and error texture)
        String[] testTextures = {
            "stick", "wooden_pickaxe", "wooden_axe", "Errockson"
        };
        
        System.out.println("Testing texture metadata access:");
        
        for (String textureName : testTextures) {
            float[] coords = builder.getTextureCoordinates(textureName);
            if (coords != null) {
                System.out.printf("  %s: [%.0f, %.0f, %.0f, %.0f]%n", 
                    textureName, coords[0], coords[1], coords[2], coords[3]);
            } else {
                System.out.println("  " + textureName + ": NOT FOUND");
            }
        }
        
        // Test block face textures that should exist in atlas
        String[] blockFaces = {
            "grass_block_top", "dirt_block_north", "stone_top", "bedrock_west", "wood_east"
        };
        
        System.out.println("\nTesting block face textures:");
        for (String faceName : blockFaces) {
            float[] coords = builder.getTextureCoordinates(faceName);
            if (coords != null) {
                System.out.printf("  %s: [%.0f, %.0f, %.0f, %.0f]%n", 
                    faceName, coords[0], coords[1], coords[2], coords[3]);
            } else {
                System.out.println("  " + faceName + ": NOT FOUND");
            }
        }
    }
}