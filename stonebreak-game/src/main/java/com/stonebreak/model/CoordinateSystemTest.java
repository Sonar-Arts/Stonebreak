package com.stonebreak.model;

/**
 * Test class to verify that baked coordinate transformations produce correct visual parity
 * between Stonebreak and OpenMason rendering systems.
 */
public class CoordinateSystemTest {
    
    /**
     * Test method to display both original and baked coordinate sets for comparison.
     */
    public static void testCoordinateParity() {
        System.out.println("=== COORDINATE SYSTEM PARITY TEST ===");
        
        try {
            // Load both models
            ModelDefinition.CowModelDefinition originalModel = ModelLoader.getCowModel("standard_cow");
            ModelDefinition.CowModelDefinition bakedModel = ModelLoader.getCowModel("standard_cow_baked");
            
            System.out.println("\nORIGINAL MODEL COORDINATES:");
            printModelCoordinates(originalModel);
            
            System.out.println("\nBAKED MODEL COORDINATES:");
            printModelCoordinates(bakedModel);
            
            System.out.println("\nCOORDINATE DIFFERENCES (Baked - Original):");
            compareModels(originalModel, bakedModel);
            
            System.out.println("\n=== TEST COMPLETED ===");
            
        } catch (Exception e) {
            System.err.println("Error during coordinate system test: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void printModelCoordinates(ModelDefinition.CowModelDefinition model) {
        ModelDefinition.ModelParts parts = model.getParts();
        
        System.out.println("Body: " + formatPosition(parts.getBody()));
        System.out.println("Head: " + formatPosition(parts.getHead()));
        
        for (int i = 0; i < parts.getLegs().size(); i++) {
            System.out.println("Leg " + (i + 1) + ": " + formatPosition(parts.getLegs().get(i)));
        }
        
        for (int i = 0; i < parts.getHorns().size(); i++) {
            System.out.println("Horn " + (i + 1) + ": " + formatPosition(parts.getHorns().get(i)));
        }
        
        System.out.println("Udder: " + formatPosition(parts.getUdder()));
        System.out.println("Tail: " + formatPosition(parts.getTail()));
    }
    
    private static String formatPosition(ModelDefinition.ModelPart part) {
        if (part == null) return "null";
        ModelDefinition.Position pos = part.getPosition();
        return String.format("Y=%.2f (X=%.2f, Z=%.2f)", pos.getY(), pos.getX(), pos.getZ());
    }
    
    private static void compareModels(ModelDefinition.CowModelDefinition original, ModelDefinition.CowModelDefinition baked) {
        ModelDefinition.ModelParts origParts = original.getParts();
        ModelDefinition.ModelParts bakedParts = baked.getParts();
        
        System.out.println("Body: " + formatDifference(origParts.getBody(), bakedParts.getBody()));
        System.out.println("Head: " + formatDifference(origParts.getHead(), bakedParts.getHead()));
        
        for (int i = 0; i < Math.min(origParts.getLegs().size(), bakedParts.getLegs().size()); i++) {
            System.out.println("Leg " + (i + 1) + ": " + formatDifference(origParts.getLegs().get(i), bakedParts.getLegs().get(i)));
        }
        
        for (int i = 0; i < Math.min(origParts.getHorns().size(), bakedParts.getHorns().size()); i++) {
            System.out.println("Horn " + (i + 1) + ": " + formatDifference(origParts.getHorns().get(i), bakedParts.getHorns().get(i)));
        }
        
        System.out.println("Udder: " + formatDifference(origParts.getUdder(), bakedParts.getUdder()));
        System.out.println("Tail: " + formatDifference(origParts.getTail(), bakedParts.getTail()));
    }
    
    private static String formatDifference(ModelDefinition.ModelPart original, ModelDefinition.ModelPart baked) {
        if (original == null || baked == null) return "null parts";
        
        float origY = original.getPosition().getY();
        float bakedY = baked.getPosition().getY();
        float difference = bakedY - origY;
        
        return String.format("Y difference: %.2f (%.2f → %.2f)", difference, origY, bakedY);
    }
    
    /**
     * Validates that the transformation is exactly +0.2f for all Y coordinates.
     */
    public static boolean validateTransformation() {
        try {
            ModelDefinition.CowModelDefinition originalModel = ModelLoader.getCowModel("standard_cow");
            ModelDefinition.CowModelDefinition bakedModel = ModelLoader.getCowModel("standard_cow_baked");
            
            float expectedOffset = 0.2f; // entity.getHeight() / 2.0f where height = 0.4f
            float tolerance = 0.001f;
            
            // Check all parts have exactly the expected Y offset
            ModelDefinition.ModelParts origParts = originalModel.getParts();
            ModelDefinition.ModelParts bakedParts = bakedModel.getParts();
            
            if (!validatePartOffset(origParts.getBody(), bakedParts.getBody(), expectedOffset, tolerance)) return false;
            if (!validatePartOffset(origParts.getHead(), bakedParts.getHead(), expectedOffset, tolerance)) return false;
            if (!validatePartOffset(origParts.getUdder(), bakedParts.getUdder(), expectedOffset, tolerance)) return false;
            if (!validatePartOffset(origParts.getTail(), bakedParts.getTail(), expectedOffset, tolerance)) return false;
            
            for (int i = 0; i < origParts.getLegs().size(); i++) {
                if (!validatePartOffset(origParts.getLegs().get(i), bakedParts.getLegs().get(i), expectedOffset, tolerance)) return false;
            }
            
            for (int i = 0; i < origParts.getHorns().size(); i++) {
                if (!validatePartOffset(origParts.getHorns().get(i), bakedParts.getHorns().get(i), expectedOffset, tolerance)) return false;
            }
            
            System.out.println("✅ Coordinate transformation validation PASSED - All parts have correct +0.2f Y offset");
            return true;
            
        } catch (Exception e) {
            System.err.println("❌ Coordinate transformation validation FAILED: " + e.getMessage());
            return false;
        }
    }
    
    private static boolean validatePartOffset(ModelDefinition.ModelPart original, ModelDefinition.ModelPart baked, float expectedOffset, float tolerance) {
        if (original == null || baked == null) return false;
        
        float origY = original.getPosition().getY();
        float bakedY = baked.getPosition().getY();
        float actualOffset = bakedY - origY;
        
        boolean isValid = Math.abs(actualOffset - expectedOffset) <= tolerance;
        if (!isValid) {
            System.err.println("❌ Part " + original.getName() + " has incorrect Y offset: " + actualOffset + " (expected: " + expectedOffset + ")");
        }
        
        return isValid;
    }
}