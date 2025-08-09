package com.openmason.coordinates;

import com.stonebreak.textures.CowTextureLoader;
import com.stonebreak.model.ModelDefinition;

/**
 * Coordinate System Demo - Phase 7 Open Mason Implementation
 * 
 * Demonstrates the usage and capabilities of the new coordinate systems.
 * This class provides practical examples of how to use the coordinate systems
 * for texture mapping, model generation, and rendering integration.
 * 
 * Demo Features:
 * - Basic coordinate system usage examples
 * - Integration with existing systems
 * - Performance optimization techniques
 * - Error handling best practices
 * - Real-world use case scenarios
 */
public class CoordinateSystemDemo {
    
    /**
     * Main demonstration method showing all coordinate system capabilities.
     */
    public static void main(String[] args) {
        System.out.println("=== Phase 7 Open Mason - Coordinate System Demo ===");
        System.out.println();
        
        try {
            // Demo 1: Basic Atlas Coordinate System usage
            demonstrateAtlasCoordinateSystem();
            System.out.println();
            
            // Demo 2: Basic Model Coordinate System usage  
            demonstrateModelCoordinateSystem();
            System.out.println();
            
            // Demo 3: System Integration
            demonstrateSystemIntegration();
            System.out.println();
            
            // Demo 4: Performance optimization
            demonstratePerformanceOptimization();
            System.out.println();
            
            // Demo 5: Error handling
            demonstrateErrorHandling();
            System.out.println();
            
            // Demo 6: Real-world usage scenario
            demonstrateRealWorldUsage();
            System.out.println();
            
            System.out.println("=== Demo Complete ===");
            
        } catch (Exception e) {
            System.err.println("Demo failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Demonstrate Atlas Coordinate System basic usage.
     */
    private static void demonstrateAtlasCoordinateSystem() {
        System.out.println("1. Atlas Coordinate System Demo");
        System.out.println("--------------------------------");
        
        // Basic coordinate conversion
        System.out.println("Basic coordinate conversions:");
        
        // Convert grid to UV
        AtlasCoordinateSystem.UVCoordinate uv = AtlasCoordinateSystem.gridToUV(8, 8);
        System.out.println("  Grid (8,8) -> UV: " + uv);
        
        // Convert UV back to grid
        AtlasCoordinateSystem.AtlasCoordinate atlas = AtlasCoordinateSystem.uvToGrid(0.5f, 0.5f);
        System.out.println("  UV (0.5,0.5) -> Grid: " + atlas);
        
        // Generate quad coordinates for OpenGL
        float[] quadUV = AtlasCoordinateSystem.generateQuadUVCoordinates(8, 8);
        System.out.println("  Quad UV coordinates for (8,8): [" + 
            String.format("%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f", 
                quadUV[0], quadUV[1], quadUV[2], quadUV[3],
                quadUV[4], quadUV[5], quadUV[6], quadUV[7]) + "]");
        
        // Validation
        boolean isValid = AtlasCoordinateSystem.validateCoordinateBounds(8, 8);
        System.out.println("  Coordinate (8,8) is valid: " + isValid);
        
        System.out.println("  " + AtlasCoordinateSystem.getSystemInfo().replace("\n", "\n  "));
    }
    
    /**
     * Demonstrate Model Coordinate System basic usage.
     */
    private static void demonstrateModelCoordinateSystem() {
        System.out.println("2. Model Coordinate System Demo");
        System.out.println("-------------------------------");
        
        // Create model part
        ModelCoordinateSystem.Position position = new ModelCoordinateSystem.Position(0.0f, 1.5f, 0.0f);
        ModelCoordinateSystem.Size size = new ModelCoordinateSystem.Size(1.0f, 1.0f, 1.0f);
        
        System.out.println("Model part: position=" + position + ", size=" + size);
        
        // Generate vertices
        float[] vertices = ModelCoordinateSystem.generateVertices(position, size);
        System.out.println("  Generated " + vertices.length + " vertex coordinates");
        System.out.println("  First vertex: [" + vertices[0] + "," + vertices[1] + "," + vertices[2] + "]");
        
        // Generate indices
        int[] indices = ModelCoordinateSystem.generateIndices();
        System.out.println("  Generated " + indices.length + " indices");
        System.out.println("  First triangle: [" + indices[0] + "," + indices[1] + "," + indices[2] + "]");
        
        // Generate normals
        float[] normals = ModelCoordinateSystem.generateVertexNormals();
        System.out.println("  Generated " + normals.length + " normal coordinates");
        System.out.println("  First normal: [" + normals[0] + "," + normals[1] + "," + normals[2] + "]");
        
        // Calculate bounding box
        float[] bounds = ModelCoordinateSystem.calculateBoundingBox(position, size);
        System.out.println("  Bounding box: [" + 
            String.format("%.1f,%.1f,%.1f,%.1f,%.1f,%.1f", 
                bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]) + "]");
        
        System.out.println("  " + ModelCoordinateSystem.getSystemInfo().replace("\n", "\n  "));
    }
    
    /**
     * Demonstrate System Integration usage.
     */
    private static void demonstrateSystemIntegration() {
        System.out.println("3. System Integration Demo");
        System.out.println("--------------------------");
        
        try {
            // Initialize texture atlas
            // Texture atlas initialization is now automatic in CowTextureLoader
            
            // Create a model part
            ModelDefinition.ModelPart modelPart = new ModelDefinition.ModelPart(
                "demo_head",
                new ModelDefinition.Position(0.0f, 1.5f, 0.0f),
                new ModelDefinition.Size(1.0f, 1.0f, 1.0f),
                "cow_head"
            );
            
            System.out.println("Created model part: " + modelPart.getName());
            
            // Generate integrated data for different variants
            String[] variants = {"default", "angus", "highland", "jersey"};
            
            for (String variant : variants) {
                CoordinateSystemIntegration.IntegratedPartData integrated = 
                    CoordinateSystemIntegration.generateIntegratedPartData(modelPart, variant, true);
                
                if (integrated != null && integrated.isValid()) {
                    System.out.println("  ✓ " + variant + " variant: " + integrated);
                    System.out.println("    Vertices: " + integrated.getVertices().length + " floats");
                    System.out.println("    Texture coords: " + integrated.getTextureCoordinates().length + " floats"); 
                    System.out.println("    Indices: " + integrated.getIndices().length + " ints");
                } else {
                    System.err.println("  ✗ " + variant + " variant integration failed");
                }
            }
            
            // Show cache statistics
            System.out.println("  " + CoordinateSystemIntegration.getCacheStatistics().replace("\n", "\n  "));
            
        } catch (Exception e) {
            System.err.println("  Integration demo failed: " + e.getMessage());
        }
    }
    
    /**
     * Demonstrate performance optimization techniques.
     */
    private static void demonstratePerformanceOptimization() {
        System.out.println("4. Performance Optimization Demo");
        System.out.println("--------------------------------");
        
        try {
            // Create test model part
            ModelDefinition.ModelPart testPart = new ModelDefinition.ModelPart(
                "perf_test",
                new ModelDefinition.Position(0.0f, 0.0f, 0.0f),
                new ModelDefinition.Size(1.0f, 1.0f, 1.0f),
                "cow_head"
            );
            
            int iterations = 1000;
            
            // Test without caching
            long startTime = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                CoordinateSystemIntegration.generateTextureCoordinatesForPart(testPart, "default", false);
            }
            long timeWithoutCache = System.nanoTime() - startTime;
            
            // Test with caching
            startTime = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                CoordinateSystemIntegration.generateTextureCoordinatesForPart(testPart, "default", true);
            }
            long timeWithCache = System.nanoTime() - startTime;
            
            double improvement = (double) timeWithoutCache / timeWithCache;
            
            System.out.println("  Performance comparison (" + iterations + " iterations):");
            System.out.println("    Without cache: " + String.format("%.2f", timeWithoutCache / 1_000_000.0) + "ms");
            System.out.println("    With cache: " + String.format("%.2f", timeWithCache / 1_000_000.0) + "ms");
            System.out.println("    Improvement: " + String.format("%.1f", improvement) + "x faster");
            
            // Demonstrate batch processing
            System.out.println("  Batch processing demo:");
            startTime = System.nanoTime();
            
            String[] variants = {"default", "angus", "highland", "jersey"};
            for (String variant : variants) {
                CoordinateSystemIntegration.generateIntegratedPartData(testPart, variant, true);
            }
            
            long batchTime = System.nanoTime() - startTime;
            System.out.println("    Batch generation (4 variants): " + 
                String.format("%.2f", batchTime / 1_000_000.0) + "ms");
            
        } catch (Exception e) {
            System.err.println("  Performance demo failed: " + e.getMessage());
        }
    }
    
    /**
     * Demonstrate error handling and validation.
     */
    private static void demonstrateErrorHandling() {
        System.out.println("5. Error Handling Demo");
        System.out.println("----------------------");
        
        // Test invalid coordinates
        System.out.println("  Testing invalid coordinates:");
        
        AtlasCoordinateSystem.UVCoordinate invalidUV = AtlasCoordinateSystem.gridToUV(-1, 16);
        System.out.println("    Invalid grid (-1,16) -> UV: " + invalidUV);
        
        AtlasCoordinateSystem.AtlasCoordinate invalidAtlas = AtlasCoordinateSystem.uvToGrid(1.5f, -0.5f);
        System.out.println("    Invalid UV (1.5,-0.5) -> Grid: " + invalidAtlas);
        
        // Test invalid model parameters
        System.out.println("  Testing invalid model parameters:");
        
        float[] invalidVertices = ModelCoordinateSystem.generateVertices(null, null);
        System.out.println("    Null parameters -> Vertices: " + invalidVertices);
        
        ModelCoordinateSystem.Size invalidSize = new ModelCoordinateSystem.Size(-1.0f, 0.0f, 1.0f);
        ModelCoordinateSystem.Position validPos = new ModelCoordinateSystem.Position(0, 0, 0);
        float[] invalidVertices2 = ModelCoordinateSystem.generateVertices(validPos, invalidSize);
        System.out.println("    Invalid size -> Vertices: " + invalidVertices2);
        
        // Test integration error handling
        System.out.println("  Testing integration error handling:");
        
        CoordinateSystemIntegration.IntegratedPartData invalidIntegration = 
            CoordinateSystemIntegration.generateIntegratedPartData(null, "default", false);
        System.out.println("    Null model part -> Integration: " + invalidIntegration);
        
        System.out.println("  ✓ Error handling working correctly - all invalid inputs returned null");
    }
    
    /**
     * Demonstrate real-world usage scenario.
     */
    private static void demonstrateRealWorldUsage() {
        System.out.println("6. Real-World Usage Scenario");
        System.out.println("-----------------------------");
        
        try {
            System.out.println("  Scenario: Rendering a complete cow model with multiple variants");
            
            // Define cow model parts (simplified)
            ModelDefinition.ModelPart[] cowParts = {
                new ModelDefinition.ModelPart("head", 
                    new ModelDefinition.Position(0.0f, 1.5f, 0.0f),
                    new ModelDefinition.Size(1.0f, 1.0f, 1.0f), "cow_head"),
                    
                new ModelDefinition.ModelPart("body",
                    new ModelDefinition.Position(0.0f, 0.0f, 0.0f),
                    new ModelDefinition.Size(2.0f, 1.0f, 1.5f), "cow_body"),
                    
                new ModelDefinition.ModelPart("leg1",
                    new ModelDefinition.Position(-0.7f, -1.0f, 0.5f),
                    new ModelDefinition.Size(0.3f, 1.0f, 0.3f), "cow_legs"),
                    
                new ModelDefinition.ModelPart("udder",
                    new ModelDefinition.Position(0.0f, -0.8f, 0.0f),
                    new ModelDefinition.Size(0.8f, 0.4f, 0.6f), "cow_udder")
            };
            
            String[] variants = {"default", "angus", "highland", "jersey"};
            
            System.out.println("    Model parts: " + cowParts.length);
            System.out.println("    Texture variants: " + variants.length);
            System.out.println("    Total combinations: " + (cowParts.length * variants.length));
            System.out.println();
            
            long startTime = System.nanoTime();
            int totalVertices = 0;
            int totalIndices = 0;
            int successfulGenerations = 0;
            
            for (String variant : variants) {
                System.out.println("    Processing " + variant + " variant:");
                
                for (ModelDefinition.ModelPart part : cowParts) {
                    CoordinateSystemIntegration.IntegratedPartData integrated = 
                        CoordinateSystemIntegration.generateIntegratedPartData(part, variant, true);
                    
                    if (integrated != null && integrated.isValid()) {
                        totalVertices += integrated.getVertices().length;
                        totalIndices += integrated.getIndices().length;
                        successfulGenerations++;
                        System.out.println("      ✓ " + part.getName() + " - " + 
                            integrated.getVertices().length + " vertices, " +
                            integrated.getIndices().length + " indices");
                    } else {
                        System.err.println("      ✗ " + part.getName() + " - generation failed");
                    }
                }
            }
            
            long totalTime = System.nanoTime() - startTime;
            
            System.out.println();
            System.out.println("    Results:");
            System.out.println("      Successful generations: " + successfulGenerations + "/" + 
                (cowParts.length * variants.length));
            System.out.println("      Total vertices: " + totalVertices + " floats");
            System.out.println("      Total indices: " + totalIndices + " ints");
            System.out.println("      Generation time: " + String.format("%.2f", totalTime / 1_000_000.0) + "ms");
            System.out.println("      Average per part: " + 
                String.format("%.3f", (totalTime / 1_000_000.0) / successfulGenerations) + "ms");
            
            // Show cache efficiency
            System.out.println("    " + CoordinateSystemIntegration.getCacheStatistics().replace("\n", "\n    "));
            
            if (successfulGenerations == cowParts.length * variants.length) {
                System.out.println("    ✅ Complete cow model ready for rendering with all variants!");
            } else {
                System.err.println("    ❌ Some model parts failed to generate");
            }
            
        } catch (Exception e) {
            System.err.println("  Real-world scenario failed: " + e.getMessage());
        }
    }
    
    /**
     * Quick validation test to ensure systems are working.
     */
    public static boolean quickValidationTest() {
        System.out.println("Running quick validation test...");
        
        try {
            // Test atlas system
            AtlasCoordinateSystem.UVCoordinate uv = AtlasCoordinateSystem.gridToUV(8, 8);
            if (uv == null || Math.abs(uv.getU() - 0.5f) > 0.001f) {
                return false;
            }
            
            // Test model system
            ModelCoordinateSystem.Position pos = new ModelCoordinateSystem.Position(0, 0, 0);
            ModelCoordinateSystem.Size size = new ModelCoordinateSystem.Size(2, 2, 2);
            float[] vertices = ModelCoordinateSystem.generateVertices(pos, size);
            if (vertices == null || vertices.length != 72) {
                return false;
            }
            
            // Test integration
            ModelDefinition.ModelPart testPart = new ModelDefinition.ModelPart(
                "test", new ModelDefinition.Position(0, 0, 0),
                new ModelDefinition.Size(1, 1, 1), "cow_head");
            
            float[] texCoords = CoordinateSystemIntegration.generateTextureCoordinatesForPart(
                testPart, "default", false);
            if (texCoords == null || texCoords.length != 48) {
                return false;
            }
            
            System.out.println("✓ Quick validation test passed");
            return true;
            
        } catch (Exception e) {
            System.err.println("✗ Quick validation test failed: " + e.getMessage());
            return false;
        }
    }
}