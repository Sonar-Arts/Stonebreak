package com.openmason.test;

import com.openmason.model.ModelManager;
import com.openmason.model.StonebreakModel;
import com.stonebreak.model.ModelDefinition;
import com.stonebreak.model.ModelLoader;

import java.util.List;
import java.util.Arrays;

/**
 * Comprehensive validation test for cow model positioning fixes.
 * Validates that the baked model variant mapping works correctly and that
 * all cow texture variants can be loaded and positioned properly.
 * 
 * This test verifies the complete fix for the cow model positioning issues.
 */
public class CowModelPositioningValidationTest {
    
    private static final String[] COW_VARIANTS = {
        "default", "angus", "highland", "jersey"
    };
    
    /**
     * Main test method that validates the complete cow model positioning fix.
     */
    public static void main(String[] args) {
        System.out.println("=== COW MODEL POSITIONING VALIDATION TEST ===");
        System.out.println("This test validates that the baked model mapping fixes positioning issues.");
        System.out.println();
        
        boolean allTestsPassed = true;
        
        try {
            // Test 1: Validate model mapping works correctly
            System.out.println("TEST 1: Model Name Mapping Validation");
            allTestsPassed &= testModelMapping();
            System.out.println();
            
            // Test 2: Validate Y-coordinate differences between models
            System.out.println("TEST 2: Y-Coordinate Positioning Validation");
            allTestsPassed &= testCoordinatePositioning();
            System.out.println();
            
            // Test 3: Validate all texture variants work with baked model
            System.out.println("TEST 3: Texture Variant Integration Validation");
            allTestsPassed &= testTextureVariantIntegration();
            System.out.println();
            
            // Test 4: Validate ModelManager integration
            System.out.println("TEST 4: ModelManager Integration Validation");
            allTestsPassed &= testModelManagerIntegration();
            System.out.println();
            
            // Test 5: Validate StonebreakModel integration
            System.out.println("TEST 5: StonebreakModel Integration Validation");
            allTestsPassed &= testStonebreakModelIntegration();
            System.out.println();
            
            // Final Results
            System.out.println("=== FINAL RESULTS ===");
            if (allTestsPassed) {
                System.out.println("✓ ALL TESTS PASSED");
                System.out.println("✓ Cow model positioning issues have been fixed");
                System.out.println("✓ Open Mason now uses baked model variants like Stonebreak");
                System.out.println("✓ All texture variants work correctly");
            } else {
                System.out.println("✗ SOME TESTS FAILED");
                System.out.println("✗ Additional fixes may be required");
            }
            System.out.println("==========================================");
            
        } catch (Exception e) {
            System.err.println("CRITICAL ERROR during validation: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Test that model name mapping works correctly.
     */
    private static boolean testModelMapping() {
        try {
            // Test direct model loading - should automatically use baked variant
            ModelDefinition.CowModelDefinition originalModel = ModelLoader.getCowModel("standard_cow");
            ModelDefinition.CowModelDefinition bakedModel = ModelLoader.getCowModel("standard_cow_baked");
            
            // The original call should now return the baked variant due to mapping
            // But we need to check the actual coordinates
            if (originalModel.getParts() != null && bakedModel.getParts() != null) {
                float originalBodyY = originalModel.getParts().getBody().getPosition().getY();
                float bakedBodyY = bakedModel.getParts().getBody().getPosition().getY();
                
                System.out.println("  Original model body Y: " + originalBodyY);
                System.out.println("  Baked model body Y: " + bakedBodyY);
                
                // If mapping is working, we should see the baked coordinates
                if (Math.abs(bakedBodyY - 0.2f) < 0.001f) {
                    System.out.println("  ✓ Baked model has correct Y offset (+0.2)");
                    return true;
                } else {
                    System.out.println("  ✗ Baked model Y coordinate unexpected: " + bakedBodyY);
                    return false;
                }
            } else {
                System.out.println("  ✗ Model parts are null");
                return false;
            }
            
        } catch (Exception e) {
            System.err.println("  ✗ Model mapping test failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Test Y-coordinate positioning differences between original and baked models.
     */
    private static boolean testCoordinatePositioning() {
        try {
            ModelDefinition.CowModelDefinition originalModel = ModelLoader.getCowModel("standard_cow");
            ModelDefinition.CowModelDefinition bakedModel = ModelLoader.getCowModel("standard_cow_baked");
            
            if (originalModel.getParts() == null || bakedModel.getParts() == null) {
                System.out.println("  ✗ Cannot access model parts");
                return false;
            }
            
            // Test key body parts for Y-offset
            String[] partNames = {"body", "head", "udder", "tail"};
            boolean allOffsetsCorrect = true;
            
            for (String partName : partNames) {
                ModelDefinition.ModelPart origPart = null;
                ModelDefinition.ModelPart bakedPart = null;
                
                switch (partName) {
                    case "body" -> {
                        origPart = originalModel.getParts().getBody();
                        bakedPart = bakedModel.getParts().getBody();
                    }
                    case "head" -> {
                        origPart = originalModel.getParts().getHead();
                        bakedPart = bakedModel.getParts().getHead();
                    }
                    case "udder" -> {
                        origPart = originalModel.getParts().getUdder();
                        bakedPart = bakedModel.getParts().getUdder();
                    }
                    case "tail" -> {
                        origPart = originalModel.getParts().getTail();
                        bakedPart = bakedModel.getParts().getTail();
                    }
                }
                
                if (origPart != null && bakedPart != null) {
                    float origY = origPart.getPosition().getY();
                    float bakedY = bakedPart.getPosition().getY();
                    float difference = bakedY - origY;
                    
                    System.out.println("  " + partName + ": Original Y=" + origY + ", Baked Y=" + bakedY + 
                                     ", Difference=" + difference);
                    
                    // Should have approximately +0.2 Y-offset
                    if (Math.abs(difference - 0.2f) > 0.01f) {
                        System.out.println("    ✗ Unexpected Y-offset for " + partName);
                        allOffsetsCorrect = false;
                    } else {
                        System.out.println("    ✓ Correct Y-offset for " + partName);
                    }
                } else {
                    System.out.println("  ✗ Missing " + partName + " part");
                    allOffsetsCorrect = false;
                }
            }
            
            return allOffsetsCorrect;
            
        } catch (Exception e) {
            System.err.println("  ✗ Coordinate positioning test failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Test that all texture variants can be loaded with the baked model.
     */
    private static boolean testTextureVariantIntegration() {
        boolean allVariantsWork = true;
        
        for (String variant : COW_VARIANTS) {
            try {
                System.out.println("  Testing variant: " + variant);
                
                // This should automatically use the baked model due to mapping
                StonebreakModel model = StonebreakModel.loadFromResources(
                    "standard_cow", variant, variant
                );
                
                // Validate the model loaded successfully
                if (model.getBodyParts().isEmpty()) {
                    System.out.println("    ✗ No body parts loaded for " + variant);
                    allVariantsWork = false;
                    continue;
                }
                
                // Check that we got the expected number of parts
                List<StonebreakModel.BodyPart> parts = model.getBodyParts();
                if (parts.size() < 10) { // Should have 10 parts: body, head, 4 legs, 2 horns, udder, tail
                    System.out.println("    ✗ Expected 10 parts, got " + parts.size() + " for " + variant);
                    allVariantsWork = false;
                } else {
                    System.out.println("    ✓ " + variant + " loaded successfully with " + parts.size() + " parts");
                }
                
                // Validate that Y-coordinates are using baked values
                for (StonebreakModel.BodyPart part : parts) {
                    if ("body".equals(part.getName())) {
                        ModelDefinition.Position pos = part.getModelPart().getPosition();
                        if (Math.abs(pos.getY() - 0.2f) < 0.001f) {
                            System.out.println("    ✓ " + variant + " body uses baked Y-coordinate (0.2)");
                        } else {
                            System.out.println("    ✗ " + variant + " body Y-coordinate: " + pos.getY() + " (expected 0.2)");
                            allVariantsWork = false;
                        }
                        break;
                    }
                }
                
            } catch (Exception e) {
                System.err.println("    ✗ Failed to load variant " + variant + ": " + e.getMessage());
                allVariantsWork = false;
            }
        }
        
        return allVariantsWork;
    }
    
    /**
     * Test ModelManager integration with model mapping.
     */
    private static boolean testModelManagerIntegration() {
        try {
            // Initialize ModelManager
            ModelManager.initialize();
            
            // Test getStaticModelParts - should use mapped model name
            ModelDefinition.ModelPart[] parts = ModelManager.getStaticModelParts("standard_cow");
            
            if (parts.length == 0) {
                System.out.println("  ✗ ModelManager returned no parts");
                return false;
            }
            
            System.out.println("  ModelManager returned " + parts.length + " parts");
            
            // Check that body part has baked coordinates
            for (ModelDefinition.ModelPart part : parts) {
                if ("body".equals(part.getName())) {
                    float bodyY = part.getPosition().getY();
                    if (Math.abs(bodyY - 0.2f) < 0.001f) {
                        System.out.println("  ✓ ModelManager uses baked model (body Y = " + bodyY + ")");
                        return true;
                    } else {
                        System.out.println("  ✗ ModelManager body Y coordinate: " + bodyY + " (expected 0.2)");
                        return false;
                    }
                }
            }
            
            System.out.println("  ✗ No body part found in ModelManager results");
            return false;
            
        } catch (Exception e) {
            System.err.println("  ✗ ModelManager integration test failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Test StonebreakModel integration with model mapping.
     */
    private static boolean testStonebreakModelIntegration() {
        try {
            // Test that StonebreakModel.loadFromResources uses mapping
            StonebreakModel model = StonebreakModel.loadFromResources(
                "standard_cow", "default", "default"
            );
            
            List<StonebreakModel.BodyPart> parts = model.getBodyParts();
            if (parts.isEmpty()) {
                System.out.println("  ✗ StonebreakModel returned no parts");
                return false;
            }
            
            System.out.println("  StonebreakModel returned " + parts.size() + " parts");
            
            // Check that body part has baked coordinates
            for (StonebreakModel.BodyPart part : parts) {
                if ("body".equals(part.getName())) {
                    ModelDefinition.Position pos = part.getModelPart().getPosition();
                    float bodyY = pos.getY();
                    if (Math.abs(bodyY - 0.2f) < 0.001f) {
                        System.out.println("  ✓ StonebreakModel uses baked model (body Y = " + bodyY + ")");
                        return true;
                    } else {
                        System.out.println("  ✗ StonebreakModel body Y coordinate: " + bodyY + " (expected 0.2)");
                        return false;
                    }
                }
            }
            
            System.out.println("  ✗ No body part found in StonebreakModel results");
            return false;
            
        } catch (Exception e) {
            System.err.println("  ✗ StonebreakModel integration test failed: " + e.getMessage());
            return false;
        }
    }
}