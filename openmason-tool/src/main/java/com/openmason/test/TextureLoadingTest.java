package com.openmason.test;

import com.openmason.texture.stonebreak.StonebreakTextureLoader;
import com.openmason.texture.TextureVariantManager;

/**
 * Simple test to verify texture loading functionality.
 */
public class TextureLoadingTest {
    
    public static void main(String[] args) {
        System.out.println("=== OpenMason Texture Loading Test ===");
        
        // Test 1: Check if texture files can be found
        System.out.println("\n1. Testing resource availability:");
        String[] paths = {
            "textures/mobs/cow/default_cow.json",
            "textures/mobs/cow/angus_cow.json", 
            "textures/mobs/cow/highland_cow.json",
            "textures/mobs/cow/jersey_cow.json"
        };
        
        for (String path : paths) {
            try (var stream = StonebreakTextureLoader.class.getClassLoader().getResourceAsStream(path)) {
                System.out.println("  " + path + ": " + (stream != null ? "✓ FOUND" : "✗ NOT FOUND"));
            } catch (Exception e) {
                System.out.println("  " + path + ": ✗ ERROR - " + e.getMessage());
            }
        }
        
        // Test 2: Check available variants
        System.out.println("\n2. Testing available variants:");
        try {
            String[] variants = StonebreakTextureLoader.getAvailableVariants();
            System.out.println("  Available variants: " + java.util.Arrays.toString(variants));
        } catch (Exception e) {
            System.out.println("  ✗ Error getting available variants: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Test 3: Try loading each variant
        System.out.println("\n3. Testing variant loading:");
        String[] testVariants = {"default", "angus", "highland", "jersey"};
        
        for (String variant : testVariants) {
            System.out.println("  Testing variant: " + variant);
            try {
                var cowVariant = StonebreakTextureLoader.getCowVariant(variant);
                if (cowVariant != null) {
                    System.out.println("    ✓ Successfully loaded: " + cowVariant.getDisplayName());
                    System.out.println("    Face mappings: " + cowVariant.getFaceMappings().size());
                    System.out.println("    Drawing instructions: " + 
                        (cowVariant.getDrawingInstructions() != null ? cowVariant.getDrawingInstructions().size() : 0));
                } else {
                    System.out.println("    ✗ Failed to load variant (returned null)");
                }
            } catch (Exception e) {
                System.out.println("    ✗ Exception loading variant: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // Test 4: Try TextureVariantManager integration
        System.out.println("\n4. Testing TextureVariantManager:");
        try {
            TextureVariantManager manager = TextureVariantManager.getInstance();
            System.out.println("  ✓ TextureVariantManager instance created");
            
            // Check performance stats
            var stats = manager.getPerformanceStats();
            System.out.println("  Performance stats: " + stats);
            
        } catch (Exception e) {
            System.out.println("  ✗ Error with TextureVariantManager: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("\n=== Test Complete ===");
    }
}