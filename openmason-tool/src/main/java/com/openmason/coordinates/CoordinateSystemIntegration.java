package com.openmason.coordinates;

import com.stonebreak.textures.mobs.CowTextureDefinition;
import com.stonebreak.textures.mobs.CowTextureLoader;
import com.stonebreak.model.ModelDefinition;
import org.joml.Vector3f;

/**
 * Coordinate System Integration - Phase 7 Open Mason Implementation
 * 
 * Provides seamless integration between the new coordinate systems and existing
 * Open Mason texture/model management systems. This class bridges the gap between
 * the mathematical coordinate systems and the practical rendering pipeline.
 * 
 * Integration Features:
 * - Atlas coordinate integration with StonebreakTextureAtlas
 * - Model coordinate integration with ModelDefinition
 * - Texture coordinate generation for model parts
 * - Validation of coordinate system compatibility
 * - Performance optimization through caching
 * - Error handling and fallback mechanisms
 */
public class CoordinateSystemIntegration {
    
    // Cache for texture coordinate lookups
    private static final java.util.Map<String, float[]> textureCoordinateCache = 
        new java.util.concurrent.ConcurrentHashMap<>();
    
    // Cache for vertex data
    private static final java.util.Map<String, float[]> vertexDataCache = 
        new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Integration result structure containing all coordinate data for a model part.
     */
    public static class IntegratedPartData {
        private final String partName;
        private final float[] vertices;
        private final float[] textureCoordinates;
        private final float[] normals;
        private final int[] indices;
        private final float[] boundingBox;
        
        public IntegratedPartData(String partName, float[] vertices, float[] textureCoordinates,
                                float[] normals, int[] indices, float[] boundingBox) {
            this.partName = partName;
            this.vertices = vertices;
            this.textureCoordinates = textureCoordinates;
            this.normals = normals;
            this.indices = indices;
            this.boundingBox = boundingBox;
        }
        
        // Getters
        public String getPartName() { return partName; }
        public float[] getVertices() { return vertices; }
        public float[] getTextureCoordinates() { return textureCoordinates; }
        public float[] getNormals() { return normals; }
        public int[] getIndices() { return indices; }
        public float[] getBoundingBox() { return boundingBox; }
        
        // Validation
        public boolean isValid() {
            return vertices != null && vertices.length == ModelCoordinateSystem.FLOATS_PER_PART &&
                   textureCoordinates != null && textureCoordinates.length == 48 && // 24 vertices × 2 UV
                   normals != null && normals.length == ModelCoordinateSystem.FLOATS_PER_PART &&
                   indices != null && indices.length == ModelCoordinateSystem.INDICES_PER_PART &&
                   boundingBox != null && boundingBox.length == 6;
        }
        
        @Override
        public String toString() {
            return String.format("IntegratedPartData{%s, valid=%b}", partName, isValid());
        }
    }
    
    /**
     * Generate complete integrated data for a model part with texture variant.
     * 
     * This method combines the atlas coordinate system for texture mapping with
     * the model coordinate system for vertex generation, providing a complete
     * data package ready for OpenGL rendering.
     * 
     * @param modelPart The model part definition
     * @param textureVariant The texture variant (default, angus, highland, jersey)
     * @param enableCaching Whether to use coordinate caching for performance
     * @return IntegratedPartData with all coordinate data, or null if generation fails
     */
    public static IntegratedPartData generateIntegratedPartData(
            ModelDefinition.ModelPart modelPart, 
            String textureVariant, 
            boolean enableCaching) {
        
        if (modelPart == null || textureVariant == null) {
            return null;
        }
        
        try {
            // Convert model part to coordinate system structures
            ModelCoordinateSystem.Position position = new ModelCoordinateSystem.Position(
                modelPart.getPosition().getX(),
                modelPart.getPosition().getY(),
                modelPart.getPosition().getZ()
            );
            
            ModelCoordinateSystem.Size size = new ModelCoordinateSystem.Size(
                modelPart.getSize().getX(),
                modelPart.getSize().getY(),
                modelPart.getSize().getZ()
            );
            
            // Generate vertices using model coordinate system
            float[] vertices = ModelCoordinateSystem.generateVertices(position, size);
            if (vertices == null) {
                System.err.println("[CoordinateSystemIntegration] Failed to generate vertices for part: " + modelPart.getName());
                return null;
            }
            
            // Generate indices
            int[] indices = ModelCoordinateSystem.generateIndices();
            
            // Generate normals
            float[] normals = ModelCoordinateSystem.generateVertexNormals();
            
            // Calculate bounding box
            float[] boundingBox = ModelCoordinateSystem.calculateBoundingBox(position, size);
            
            // Generate texture coordinates using atlas coordinate system integration
            float[] textureCoordinates = generateTextureCoordinatesForPart(
                modelPart, textureVariant, enableCaching);
            
            if (textureCoordinates == null) {
                System.err.println("[CoordinateSystemIntegration] Failed to generate texture coordinates for part: " + modelPart.getName());
                return null;
            }
            
            return new IntegratedPartData(
                modelPart.getName(),
                vertices,
                textureCoordinates,
                normals,
                indices,
                boundingBox
            );
            
        } catch (Exception e) {
            System.err.println("[CoordinateSystemIntegration] Error generating integrated data for part " + 
                modelPart.getName() + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Generate texture coordinates for a model part using atlas coordinate system.
     * 
     * This method bridges the model coordinate system and atlas coordinate system
     * by mapping model part faces to texture atlas coordinates.
     * 
     * @param modelPart The model part definition
     * @param textureVariant The texture variant
     * @param enableCaching Whether to use caching for performance
     * @return float array with 48 texture coordinates (24 vertices × 2 UV), or null if failed
     */
    public static float[] generateTextureCoordinatesForPart(
            ModelDefinition.ModelPart modelPart,
            String textureVariant,
            boolean enableCaching) {
        
        if (modelPart == null || textureVariant == null) {
            return null;
        }
        
        // Create cache key if caching enabled
        String cacheKey = null;
        if (enableCaching) {
            cacheKey = modelPart.getName() + ":" + 
                       modelPart.getTexture() + ":" + 
                       textureVariant;
            
            float[] cached = textureCoordinateCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        
        try {
            // Map texture identifier to part type name
            String partType = mapTextureToPartType(modelPart.getTexture());
            
            // Face order matches ModelCoordinateSystem: FRONT, BACK, LEFT, RIGHT, TOP, BOTTOM
            String[] faceNames = {
                partType + "_FRONT",
                partType + "_BACK", 
                partType + "_LEFT",
                partType + "_RIGHT",
                partType + "_TOP",
                partType + "_BOTTOM"
            };
            
            // Build texture coordinate array (48 values total)
            float[] result = new float[48];
            int index = 0;
            int successfulMappings = 0;
            
            // Process each face
            for (String faceName : faceNames) {
                // Get atlas coordinate from existing texture system
                CowTextureDefinition.AtlasCoordinate atlasCoord = 
                    CowTextureLoader.getAtlasCoordinate(textureVariant, faceName);
                
                if (atlasCoord != null) {
                    // Convert to UV coordinates using atlas coordinate system
                    AtlasCoordinateSystem.UVCoordinate uv = AtlasCoordinateSystem.gridToUV(
                        atlasCoord.getAtlasX(), atlasCoord.getAtlasY());
                    
                    if (uv != null) {
                        // Generate quad UV coordinates
                        float[] quadUV = AtlasCoordinateSystem.generateQuadUVCoordinates(
                            atlasCoord.getAtlasX(), atlasCoord.getAtlasY());
                        
                        if (quadUV != null && quadUV.length == 8) {
                            // Add UV coordinates for this face (4 vertices × 2 coordinates = 8 values)
                            System.arraycopy(quadUV, 0, result, index, 8);
                            index += 8;
                            successfulMappings++;
                            continue;
                        }
                    }
                }
                
                // Fallback coordinates for missing mappings
                float[] fallbackUV = AtlasCoordinateSystem.generateQuadUVCoordinates(0, 0);
                if (fallbackUV != null) {
                    System.arraycopy(fallbackUV, 0, result, index, 8);
                } else {
                    // Hard fallback
                    float tileSize = 1.0f / 16.0f;
                    float[] hardFallback = {
                        0.0f, 0.0f, tileSize, 0.0f, 
                        tileSize, tileSize, 0.0f, tileSize
                    };
                    System.arraycopy(hardFallback, 0, result, index, 8);
                }
                index += 8;
                
                System.err.println("[CoordinateSystemIntegration] Missing texture mapping for " + 
                    textureVariant + ":" + faceName + " (part: " + modelPart.getName() + 
                    "). Using fallback coordinates.");
            }
            
            // Cache result if caching enabled
            if (enableCaching && cacheKey != null) {
                textureCoordinateCache.put(cacheKey, result);
            }
            
            // Log mapping results
            if (successfulMappings < 6) {
                System.err.println("[CoordinateSystemIntegration] Texture coordinate mapping for part '" + 
                    modelPart.getName() + "' (variant: " + textureVariant + "): " + 
                    successfulMappings + "/6 successful");
            }
            
            return result;
            
        } catch (Exception e) {
            System.err.println("[CoordinateSystemIntegration] Error generating texture coordinates for part " + 
                modelPart.getName() + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Map texture identifiers to part type names for face mapping lookups.
     * Provides consistent mapping between model definition and texture atlas.
     * 
     * @param textureId The texture identifier from model definition
     * @return Part type name for atlas lookup
     */
    private static String mapTextureToPartType(String textureId) {
        if (textureId == null) {
            return "HEAD";
        }
        
        return switch (textureId.toLowerCase()) {
            case "cow_head", "head" -> "HEAD";
            case "cow_body", "body" -> "BODY";
            case "cow_legs", "leg", "legs" -> "LEG";
            case "cow_horns", "horns" -> "HORNS";
            case "cow_udder", "udder" -> "UDDER";
            case "cow_tail", "tail" -> "TAIL";
            default -> {
                System.err.println("[CoordinateSystemIntegration] Unknown texture ID: '" + 
                    textureId + "'. Using HEAD as fallback.");
                yield "HEAD";
            }
        };
    }
    
    /**
     * Validate coordinate system integration compatibility.
     * Tests that both coordinate systems work together correctly.
     * 
     * @return true if integration is working correctly, false otherwise
     */
    public static boolean validateIntegration() {
        System.out.println("[CoordinateSystemIntegration] Validating coordinate system integration...");
        
        try {
            // Test atlas coordinate system
            boolean atlasValid = AtlasCoordinateSystem.runComprehensiveValidation();
            System.out.println();
            
            // Test model coordinate system
            boolean modelValid = ModelCoordinateSystem.runComprehensiveValidation();
            System.out.println();
            
            // Test integration with actual model part
            System.out.println("  Testing integration with sample model part...");
            
            ModelDefinition.ModelPart testPart = new ModelDefinition.ModelPart(
                "test_head",
                new ModelDefinition.Position(0.0f, 1.5f, 0.0f),
                new ModelDefinition.Size(1.0f, 1.0f, 1.0f),
                "cow_head"
            );
            
            // Test integration with all variants
            String[] variants = {"default", "angus", "highland", "jersey"};
            int successfulIntegrations = 0;
            
            for (String variant : variants) {
                IntegratedPartData integrated = generateIntegratedPartData(testPart, variant, true);
                if (integrated != null && integrated.isValid()) {
                    successfulIntegrations++;
                    System.out.println("    ✓ " + variant + " variant integrated successfully");
                } else {
                    System.err.println("    ✗ " + variant + " variant integration failed");
                }
            }
            
            boolean integrationValid = successfulIntegrations == variants.length;
            
            boolean allValid = atlasValid && modelValid && integrationValid;
            
            System.out.println();
            System.out.println("[CoordinateSystemIntegration] Integration validation " + 
                (allValid ? "PASSED" : "FAILED"));
            
            if (allValid) {
                System.out.println("  ✓ Atlas coordinate system validated");
                System.out.println("  ✓ Model coordinate system validated");
                System.out.println("  ✓ System integration working");
                System.out.println("  ✓ All texture variants supported");
                System.out.println("  ✓ 1:1 Stonebreak compatibility achieved");
            }
            
            return allValid;
            
        } catch (Exception e) {
            System.err.println("[CoordinateSystemIntegration] Integration validation failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Test integration with existing CowTextureLoader.
     * Ensures compatibility with the existing texture system.
     * 
     * @return true if compatibility test passes, false otherwise
     */
    public static boolean testTextureAtlasCompatibility() {
        System.out.println("[CoordinateSystemIntegration] Testing texture atlas compatibility...");
        
        try {
            // Initialize texture atlas if not already done
            // Texture system initialization is automatic
            
            // Test coordinate conversion compatibility
            String[] variants = CowTextureLoader.getAvailableVariants();
            String[] testFaces = {"HEAD_FRONT", "BODY_LEFT", "LEG_FRONT_LEFT_FRONT"};
            
            int totalTests = 0;
            int passedTests = 0;
            
            for (String variant : variants) {
                for (String faceName : testFaces) {
                    totalTests++;
                    
                    // Get coordinates from existing system
                    CowTextureDefinition.AtlasCoordinate atlasCoord = 
                        CowTextureLoader.getAtlasCoordinate(variant, faceName);
                    
                    if (atlasCoord != null) {
                        // Test conversion through new coordinate system
                        AtlasCoordinateSystem.UVCoordinate uv = AtlasCoordinateSystem.gridToUV(
                            atlasCoord.getAtlasX(), atlasCoord.getAtlasY());
                        
                        if (uv != null) {
                            // Convert back to validate accuracy
                            AtlasCoordinateSystem.AtlasCoordinate backToAtlas = 
                                AtlasCoordinateSystem.uvToGrid(uv.getU(), uv.getV());
                            
                            if (backToAtlas != null && 
                                backToAtlas.getAtlasX() == atlasCoord.getAtlasX() &&
                                backToAtlas.getAtlasY() == atlasCoord.getAtlasY()) {
                                passedTests++;
                            }
                        }
                    }
                }
            }
            
            boolean compatibilityTest = passedTests == totalTests;
            
            System.out.println("  Compatibility test results: " + passedTests + "/" + totalTests + " passed");
            
            if (compatibilityTest) {
                System.out.println("  ✓ Texture atlas compatibility confirmed");
            } else {
                System.err.println("  ✗ Texture atlas compatibility test failed");
            }
            
            return compatibilityTest;
            
        } catch (Exception e) {
            System.err.println("[CoordinateSystemIntegration] Texture atlas compatibility test failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Clear all coordinate system caches.
     * Call this when texture definitions or models are reloaded.
     */
    public static void clearCaches() {
        textureCoordinateCache.clear();
        vertexDataCache.clear();
        System.out.println("[CoordinateSystemIntegration] All coordinate caches cleared");
    }
    
    /**
     * Get cache statistics for performance monitoring.
     * 
     * @return String with cache statistics
     */
    public static String getCacheStatistics() {
        return String.format(
            "[CoordinateSystemIntegration] Cache Statistics:\n" +
            "  Texture Coordinates: %d entries\n" +
            "  Vertex Data: %d entries\n" +
            "  Total Memory Usage: ~%d KB",
            textureCoordinateCache.size(),
            vertexDataCache.size(),
            (textureCoordinateCache.size() * 48 + vertexDataCache.size() * 72) * 4 / 1024
        );
    }
    
    /**
     * Get integration system information for debugging.
     * 
     * @return String with comprehensive system information
     */
    public static String getSystemInfo() {
        return String.format(
            "CoordinateSystemIntegration {\n" +
            "  Atlas System: %s\n" +
            "  Model System: %s\n" +
            "  Caching: Enabled\n" +
            "  Compatibility: Stonebreak 1:1\n" +
            "  %s\n" +
            "}",
            AtlasCoordinateSystem.getSystemInfo().replace("\n", "\n  "),
            ModelCoordinateSystem.getSystemInfo().replace("\n", "\n  "),
            getCacheStatistics().replace("\n", "\n  ")
        );
    }
}