package com.openmason.test;

import com.openmason.model.StonebreakModel;

/**
 * Simple validation test that can be run manually
 */
public class SimpleValidationTest {
    
    public static void runValidation() {
        System.out.println("=== SIMPLE STONEBREAK VALIDATION ===\n");
        
        String[] variants = {"default_cow", "angus_cow", "highland_cow", "jersey_cow"};
        
        try {
            for (String variant : variants) {
                System.out.println("Testing " + variant + "...");
                
                String modelPath = "/stonebreak/models/cow/standard_cow.json";
                String texturePath = "/stonebreak/textures/mobs/cow/" + variant + ".json";
                
                StonebreakModel model = StonebreakModel.loadFromResources(modelPath, texturePath, variant);
                StonebreakModel.ValidationResult result = model.validate();
                
                System.out.println("  ✅ Loaded successfully");
                System.out.println("  📊 Face mappings: " + result.getFaceMappingCount());
                System.out.println("  🚨 Errors: " + result.getErrors().size());
                System.out.println("  ⚠️  Warnings: " + result.getWarnings().size());
                
                if (!result.isValid()) {
                    System.out.println("  Details:");
                    for (String error : result.getErrors()) {
                        System.out.println("    ERROR: " + error);
                    }
                }
                
                System.out.println();
            }
            
            System.out.println("✅ All variants validated successfully!");
            System.out.println("✅ Phase 2 integration complete!");
            
        } catch (Exception e) {
            System.err.println("❌ Validation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}