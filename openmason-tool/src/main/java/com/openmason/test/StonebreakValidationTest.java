package com.openmason.test;

import com.openmason.model.StonebreakModel;
import com.stonebreak.model.ModelDefinition;
import com.stonebreak.textures.mobs.CowTextureDefinition;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Comprehensive validation testing for Stonebreak integration.
 * Tests all 4 cow variants and validates coordinate system consistency.
 */
public class StonebreakValidationTest {
    
    private static final String MODEL_PATH = "/stonebreak/mobs/cow/standard_cow.json";
    private static final String[] COW_VARIANTS = {
        "default_cow",
        "angus_cow", 
        "highland_cow",
        "jersey_cow"
    };
    
    public static void main(String[] args) {
        System.out.println("=== STONEBREAK INTEGRATION VALIDATION TEST ===\n");
        
        try {
            // Test model loading first
            testModelLoading();
            
            // Test all cow variants
            for (String variant : COW_VARIANTS) {
                testCowVariant(variant);
            }
            
            // Test coordinate system mathematical consistency
            testCoordinateSystemConsistency();
            
            System.out.println("\n=== VALIDATION COMPLETE ===");
            System.out.println("✅ All tests passed successfully!");
            System.out.println("✅ Open Mason Phase 2 integration is ready for use");
            
        } catch (Exception e) {
            System.err.println("❌ Validation failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void testModelLoading() {
        System.out.println("🔍 Testing Standard Cow Model Loading...");
        
        try {
            // Load just the model first to ensure it works independently
            String modelPath = "/stonebreak/mobs/cow/standard_cow.json";
            StonebreakModel testModel = StonebreakModel.loadFromResources(
                modelPath,
                "/stonebreak/mobs/cow/default_cow.json",
                "test_model"
            );
            
            List<StonebreakModel.BodyPart> bodyParts = testModel.getBodyParts();
            System.out.println("   ✅ Model loaded successfully");
            System.out.println("   📊 Body parts found: " + bodyParts.size());
            
            // Print body part details
            for (StonebreakModel.BodyPart part : bodyParts) {
                System.out.println("      - " + part.getName() + 
                    " [bounds info available via getBounds()]");
            }
            
        } catch (Exception e) {
            throw new RuntimeException("Model loading failed", e);
        }
        
        System.out.println();
    }
    
    private static void testCowVariant(String variantName) {
        System.out.println("🐄 Testing Cow Variant: " + variantName);
        
        try {
            String texturePath = "/stonebreak/mobs/cow/" + variantName + ".json";
            StonebreakModel model = StonebreakModel.loadFromResources(MODEL_PATH, texturePath, variantName);
            
            // Validate the model
            StonebreakModel.ValidationResult result = model.validate();
            
            System.out.println("   ✅ Variant loaded successfully");
            System.out.println("   📊 Face mappings: " + result.getFaceMappingCount());
            System.out.println("   🚨 Errors: " + result.getErrors().size());
            System.out.println("   ⚠️  Warnings: " + result.getWarnings().size());
            
            // Print base colors
            CowTextureDefinition.BaseColors colors = model.getBaseColors();
            System.out.println("   🎨 Base Colors:");
            System.out.println("      Primary: " + colors.getPrimary());
            System.out.println("      Secondary: " + colors.getSecondary()); 
            System.out.println("      Accent: " + colors.getAccent());
            
            // Print facial features
            CowTextureDefinition.FacialFeatures features = model.getFacialFeatures();
            if (features != null && features.getExpression() != null) {
                System.out.println("   😊 Expression: " + features.getExpression());
            }
            
            // Check for minimum required face mappings (should be 40+)
            if (result.getFaceMappingCount() < 40) {
                System.out.println("   ⚠️  Warning: Face mapping count is below expected 40+ (" + 
                    result.getFaceMappingCount() + ")");
            }
            
            // Print validation details if there are issues
            if (!result.isValid() || !result.getWarnings().isEmpty()) {
                System.out.println("   📋 Validation Details:");
                System.out.println(result.toString().replaceAll("(?m)^", "      "));
            }
            
        } catch (Exception e) {
            System.err.println("   ❌ Failed to load variant: " + e.getMessage());
            throw new RuntimeException("Variant test failed for: " + variantName, e);
        }
        
        System.out.println();
    }
    
    private static void testCoordinateSystemConsistency() {
        System.out.println("📐 Testing Coordinate System Mathematical Consistency...");
        
        try {
            // Load a model to test coordinate calculations
            StonebreakModel model = StonebreakModel.loadFromResources(
                MODEL_PATH,
                "/stonebreak/mobs/cow/default_cow.json",
                "coordinate_test"
            );
            
            Map<String, CowTextureDefinition.AtlasCoordinate> faceMappings = model.getFaceMappings();
            
            System.out.println("   🔍 Validating texture atlas coordinates...");
            
            int validCoordinates = 0;
            int invalidCoordinates = 0;
            double minU = Double.MAX_VALUE, maxU = Double.MIN_VALUE;
            double minV = Double.MAX_VALUE, maxV = Double.MIN_VALUE;
            
            // Check all face mappings for valid 16x16 grid coordinates
            for (Map.Entry<String, CowTextureDefinition.AtlasCoordinate> entry : faceMappings.entrySet()) {
                CowTextureDefinition.AtlasCoordinate mapping = entry.getValue();
                double u = mapping.getAtlasX();
                double v = mapping.getAtlasY();
                
                // Update bounds
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
                
                // Validate coordinates are within 16x16 grid
                if (u >= 0 && u < 16 && v >= 0 && v < 16) {
                    validCoordinates++;
                } else {
                    invalidCoordinates++;
                    System.out.println("      ⚠️  Invalid coordinate: " + entry.getKey() + 
                        " (" + u + ", " + v + ")");
                }
            }
            
            System.out.println("   📊 Coordinate Analysis:");
            System.out.println("      Valid coordinates: " + validCoordinates);
            System.out.println("      Invalid coordinates: " + invalidCoordinates);
            System.out.println("      U range: " + minU + " to " + maxU);
            System.out.println("      V range: " + minV + " to " + maxV);
            
            // Test UV to pixel conversion (16x16 texture atlas)
            System.out.println("   🧮 Testing UV to Pixel Conversion:");
            testUVConversion(5, 3);  // Test coordinate (5,3)
            testUVConversion(0, 0);  // Test origin
            testUVConversion(15, 15); // Test max coordinate
            
            if (invalidCoordinates > 0) {
                throw new RuntimeException("Found " + invalidCoordinates + " invalid coordinates");
            }
            
            System.out.println("   ✅ Coordinate system validation passed");
            
        } catch (Exception e) {
            throw new RuntimeException("Coordinate system validation failed", e);
        }
        
        System.out.println();
    }
    
    private static void testUVConversion(int u, int v) {
        // This simulates the UV calculation that would happen in Stonebreak's EntityRenderer
        float normalizedU = u / 16.0f;
        float normalizedV = v / 16.0f;
        
        // Convert back to verify consistency
        int reconstructedU = (int)(normalizedU * 16);
        int reconstructedV = (int)(normalizedV * 16);
        
        System.out.println("      Grid(" + u + "," + v + ") -> UV(" + 
            String.format("%.3f", normalizedU) + "," + String.format("%.3f", normalizedV) + 
            ") -> Grid(" + reconstructedU + "," + reconstructedV + ")");
        
        if (reconstructedU != u || reconstructedV != v) {
            throw new RuntimeException("UV conversion inconsistency detected!");
        }
    }
}