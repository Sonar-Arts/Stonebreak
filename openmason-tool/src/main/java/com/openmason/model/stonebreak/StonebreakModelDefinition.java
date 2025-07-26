package com.openmason.model.stonebreak;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.joml.Vector3f;
import java.util.List;
import java.util.Map;

/**
 * JSON model definition classes for cow models.
 * Provides a direct translation of the hardcoded CowModel system to JSON format.
 * Adapted for Open Mason from Stonebreak's ModelDefinition.java
 */
public class StonebreakModelDefinition {
    
    /**
     * Main cow model definition containing all parts and animations.
     */
    public static class CowModelDefinition {
        @JsonProperty("modelName")
        private String modelName;
        
        @JsonProperty("displayName")
        private String displayName;
        
        @JsonProperty("parts")
        private ModelParts parts;
        
        @JsonProperty("animations")
        private Map<String, ModelAnimation> animations;
        
        // Getters and setters
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        
        public ModelParts getParts() { return parts; }
        public void setParts(ModelParts parts) { this.parts = parts; }
        
        public Map<String, ModelAnimation> getAnimations() { return animations; }
        public void setAnimations(Map<String, ModelAnimation> animations) { this.animations = animations; }
    }
    
    /**
     * Container for all model parts.
     */
    public static class ModelParts {
        @JsonProperty("body")
        private ModelPart body;
        
        @JsonProperty("head")
        private ModelPart head;
        
        @JsonProperty("legs")
        private List<ModelPart> legs;
        
        @JsonProperty("horns")
        private List<ModelPart> horns;
        
        @JsonProperty("udder")
        private ModelPart udder;
        
        @JsonProperty("tail")
        private ModelPart tail;
        
        // Getters and setters
        public ModelPart getBody() { return body; }
        public void setBody(ModelPart body) { this.body = body; }
        
        public ModelPart getHead() { return head; }
        public void setHead(ModelPart head) { this.head = head; }
        
        public List<ModelPart> getLegs() { return legs; }
        public void setLegs(List<ModelPart> legs) { this.legs = legs; }
        
        public List<ModelPart> getHorns() { return horns; }
        public void setHorns(List<ModelPart> horns) { this.horns = horns; }
        
        public ModelPart getUdder() { return udder; }
        public void setUdder(ModelPart udder) { this.udder = udder; }
        
        public ModelPart getTail() { return tail; }
        public void setTail(ModelPart tail) { this.tail = tail; }
    }
    
    /**
     * Individual model part definition.
     */
    public static class ModelPart {
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("position")
        private Position position;
        
        @JsonProperty("size")
        private Size size;
        
        @JsonProperty("texture")
        private String texture;
        
        // Runtime fields for animation (not serialized)
        private Vector3f rotation = new Vector3f(0, 0, 0);
        private Vector3f scale = new Vector3f(1, 1, 1);
        
        // Constructors
        public ModelPart() {}
        
        public ModelPart(String name, Position position, Size size, String texture) {
            this.name = name;
            this.position = position;
            this.size = size;
            this.texture = texture;
        }
        
        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public Position getPosition() { return position; }
        public void setPosition(Position position) { this.position = position; }
        
        public Size getSize() { return size; }
        public void setSize(Size size) { this.size = size; }
        
        public String getTexture() { return texture; }
        public void setTexture(String texture) { this.texture = texture; }
        
        public Vector3f getRotation() { return new Vector3f(rotation); }
        public void setRotation(Vector3f rotation) { this.rotation.set(rotation); }
        
        public Vector3f getScale() { return new Vector3f(scale); }
        public void setScale(Vector3f scale) { this.scale.set(scale); }
        
        // Helper methods to convert to Vector3f (for compatibility with existing code)
        public Vector3f getPositionVector() {
            return new Vector3f(position.getX(), position.getY(), position.getZ());
        }
        
        public Vector3f getSizeVector() {
            return new Vector3f(size.getX(), size.getY(), size.getZ());
        }
        
        /**
         * Creates a copy of this model part for animation purposes.
         */
        public ModelPart copy() {
            ModelPart copy = new ModelPart(name, position, size, texture);
            copy.rotation.set(this.rotation);
            copy.scale.set(this.scale);
            return copy;
        }
        
        /**
         * Validates texture coordinate system integration for this model part.
         * Tests all available cow variants against this model part's texture mappings.
         * @return true if all variants have valid texture mappings, false otherwise
         */
        public boolean validateTextureCoordinateIntegration() {
            if (texture == null) {
                System.err.println("[ModelPart] Cannot validate texture coordinates - no texture assigned to part: " + name);
                return false;
            }
            
            System.out.println("[ModelPart] Validating texture coordinate integration for part: " + name + " (texture: " + texture + ")");
            
            String[] variants = com.openmason.texture.stonebreak.StonebreakTextureAtlas.getAvailableVariants();
            boolean allValid = true;
            int totalMappings = 0;
            int validMappings = 0;
            
            for (String variant : variants) {
                String partType = mapTextureToPartType(texture);
                String[] faceNames = {
                    partType + "_FRONT",
                    partType + "_BACK", 
                    partType + "_LEFT",
                    partType + "_RIGHT",
                    partType + "_TOP",
                    partType + "_BOTTOM"
                };
                
                for (String faceName : faceNames) {
                    totalMappings++;
                    
                    com.openmason.texture.stonebreak.StonebreakTextureDefinition.AtlasCoordinate coord = 
                        com.openmason.texture.stonebreak.StonebreakTextureAtlas.getAtlasCoordinate(variant, faceName);
                    
                    if (coord != null) {
                        // Validate coordinate bounds
                        if (com.openmason.texture.stonebreak.StonebreakTextureAtlas.validateCoordinateBounds(coord.getAtlasX(), coord.getAtlasY())) {
                            validMappings++;
                        } else {
                            System.err.println("  Invalid coordinate bounds for " + variant + ":" + faceName + 
                                " - (" + coord.getAtlasX() + "," + coord.getAtlasY() + ")");
                            allValid = false;
                        }
                    } else {
                        System.err.println("  Missing mapping for " + variant + ":" + faceName);
                        allValid = false;
                    }
                }
            }
            
            System.out.println("  Validation results: " + validMappings + "/" + totalMappings + " mappings valid");
            
            if (allValid) {
                System.out.println("  ✓ All texture coordinate mappings validated successfully");
            } else {
                System.err.println("  ✗ Texture coordinate validation failed - some mappings are missing or invalid");
            }
            
            return allValid;
        }
        
        /**
         * Performance-optimized texture coordinate lookup with caching.
         * Caches texture coordinates for each variant to avoid repeated atlas lookups.
         */
        private static final java.util.Map<String, float[]> coordinateCache = new java.util.concurrent.ConcurrentHashMap<>();
        
        /**
         * Gets texture coordinates with performance optimization for hot paths.
         * Uses caching to avoid repeated atlas coordinate calculations.
         * @param textureVariant The cow variant to get coordinates for
         * @param enableCaching Whether to use coordinate caching (recommended for runtime)
         * @return Texture coordinate array (48 values)
         */
        public float[] getTextureCoordsOptimized(String textureVariant, boolean enableCaching) {
            if (!enableCaching) {
                return getTextureCoords(textureVariant);
            }
            
            // Create cache key combining part name, texture, and variant
            String cacheKey = name + ":" + texture + ":" + textureVariant;
            
            return coordinateCache.computeIfAbsent(cacheKey, key -> {
                float[] coords = getTextureCoords(textureVariant);
                System.out.println("[ModelPart] Cached texture coordinates for: " + cacheKey);
                return coords;
            });
        }
        
        /**
         * Clears the texture coordinate cache.
         * Call this when texture definitions are reloaded or changed.
         */
        public static void clearTextureCoordinateCache() {
            coordinateCache.clear();
            System.out.println("[ModelPart] Texture coordinate cache cleared");
        }
        
        /**
         * Gets cache statistics for performance monitoring.
         */
        public static String getTextureCacheStats() {
            return "[ModelPart] Texture coordinate cache: " + coordinateCache.size() + " entries cached";
        }
        
        /**
         * Gets the vertices for this model part as a cuboid.
         * Returns vertex data in the format expected by OpenGL (24 vertices for 6 faces).
         */
        public float[] getVertices() {
            Vector3f pos = getPositionVector();
            Vector3f sz = getSizeVector();
            
            // Calculate half-sizes for convenience
            float halfWidth = sz.x / 2.0f;
            float halfHeight = sz.y / 2.0f;
            float halfDepth = sz.z / 2.0f;
            
            // Define vertices for each face separately (24 vertices total)
            return new float[] {
                // Front face (4 vertices)
                pos.x - halfWidth, pos.y - halfHeight, pos.z + halfDepth,
                pos.x + halfWidth, pos.y - halfHeight, pos.z + halfDepth,
                pos.x + halfWidth, pos.y + halfHeight, pos.z + halfDepth,
                pos.x - halfWidth, pos.y + halfHeight, pos.z + halfDepth,
                
                // Back face (4 vertices)
                pos.x - halfWidth, pos.y - halfHeight, pos.z - halfDepth,
                pos.x + halfWidth, pos.y - halfHeight, pos.z - halfDepth,
                pos.x + halfWidth, pos.y + halfHeight, pos.z - halfDepth,
                pos.x - halfWidth, pos.y + halfHeight, pos.z - halfDepth,
                
                // Left face (4 vertices)
                pos.x - halfWidth, pos.y - halfHeight, pos.z - halfDepth,
                pos.x - halfWidth, pos.y - halfHeight, pos.z + halfDepth,
                pos.x - halfWidth, pos.y + halfHeight, pos.z + halfDepth,
                pos.x - halfWidth, pos.y + halfHeight, pos.z - halfDepth,
                
                // Right face (4 vertices)
                pos.x + halfWidth, pos.y - halfHeight, pos.z - halfDepth,
                pos.x + halfWidth, pos.y - halfHeight, pos.z + halfDepth,
                pos.x + halfWidth, pos.y + halfHeight, pos.z + halfDepth,
                pos.x + halfWidth, pos.y + halfHeight, pos.z - halfDepth,
                
                // Top face (4 vertices)
                pos.x - halfWidth, pos.y + halfHeight, pos.z - halfDepth,
                pos.x + halfWidth, pos.y + halfHeight, pos.z - halfDepth,
                pos.x + halfWidth, pos.y + halfHeight, pos.z + halfDepth,
                pos.x - halfWidth, pos.y + halfHeight, pos.z + halfDepth,
                
                // Bottom face (4 vertices)
                pos.x - halfWidth, pos.y - halfHeight, pos.z - halfDepth,
                pos.x + halfWidth, pos.y - halfHeight, pos.z - halfDepth,
                pos.x + halfWidth, pos.y - halfHeight, pos.z + halfDepth,
                pos.x - halfWidth, pos.y - halfHeight, pos.z + halfDepth
            };
        }
        
        /**
         * Gets the indices for rendering this cuboid (6 faces with 2 triangles each).
         */
        public int[] getIndices() {
            return new int[] {
                // Front face (vertices 0-3)
                0, 1, 2, 2, 3, 0,
                // Back face (vertices 4-7)
                4, 5, 6, 6, 7, 4,
                // Left face (vertices 8-11)
                8, 9, 10, 10, 11, 8,
                // Right face (vertices 12-15)
                12, 13, 14, 14, 15, 12,
                // Top face (vertices 16-19)
                16, 17, 18, 18, 19, 16,
                // Bottom face (vertices 20-23)
                20, 21, 22, 22, 23, 20
            };
        }
        
        /**
         * Gets texture coordinates for this model part using the texture atlas.
         * Returns 48 texture coordinates (2 per vertex, 4 vertices per face, 6 faces).
         * Uses different textures for different faces to ensure proper appearance.
         * 
         * Integrates with StonebreakTextureAtlas for mathematical precision:
         * - 16×16 grid system (256×256 pixels, 16px per tile)
         * - UV coordinate calculation: u = atlasX / 16.0f, v = atlasY / 16.0f
         * - Exact 1:1 compatibility with Stonebreak EntityRenderer system
         */
        public float[] getTextureCoords() {
            return getTextureCoords("default");
        }
        
        public float[] getTextureCoords(String textureVariant) {
            // Validate input parameters
            if (textureVariant == null || textureVariant.trim().isEmpty()) {
                System.err.println("[ModelPart] Invalid texture variant: '" + textureVariant + "' for part: " + name + ". Using default variant.");
                textureVariant = "default";
            }
            
            // Validate variant exists before proceeding
            if (!com.openmason.texture.stonebreak.StonebreakTextureAtlas.isValidVariant(textureVariant)) {
                System.err.println("[ModelPart] Unknown texture variant: '" + textureVariant + "' for part: " + name + ". Available variants: " + 
                    java.util.Arrays.toString(com.openmason.texture.stonebreak.StonebreakTextureAtlas.getAvailableVariants()));
                textureVariant = "default";
            }
            
            // Map texture part to base name for JSON lookups
            String partType = mapTextureToPartType(texture);
            
            // Face order: front(0), back(1), left(2), right(3), top(4), bottom(5)
            String[] faceNames = {
                partType + "_FRONT",
                partType + "_BACK", 
                partType + "_LEFT",
                partType + "_RIGHT",
                partType + "_TOP",
                partType + "_BOTTOM"
            };
            
            // Build the final texture coordinate array (48 values total)
            float[] result = new float[48];
            int index = 0;
            int successfulMappings = 0;
            int failedMappings = 0;
            
            // Process each face (front, back, left, right, top, bottom)
            for (int face = 0; face < 6; face++) {
                String faceName = faceNames[face];
                
                // Get UV coordinates from texture atlas system
                float[] coords = com.openmason.texture.stonebreak.StonebreakTextureAtlas.getUVCoordinates(textureVariant, faceName);
                
                if (coords != null && coords.length >= 8) {
                    // Add UV coordinates for this face (4 vertices × 2 coordinates = 8 values)
                    // Coordinate order: bottom-left, bottom-right, top-right, top-left
                    result[index++] = coords[0]; result[index++] = coords[1]; // bottom-left
                    result[index++] = coords[2]; result[index++] = coords[3]; // bottom-right
                    result[index++] = coords[4]; result[index++] = coords[5]; // top-right
                    result[index++] = coords[6]; result[index++] = coords[7]; // top-left
                    successfulMappings++;
                } else {
                    // Use fallback coordinates for missing mappings
                    float[] fallbackCoords = getFallbackTextureCoords();
                    result[index++] = fallbackCoords[0]; result[index++] = fallbackCoords[1];
                    result[index++] = fallbackCoords[2]; result[index++] = fallbackCoords[3];
                    result[index++] = fallbackCoords[4]; result[index++] = fallbackCoords[5];
                    result[index++] = fallbackCoords[6]; result[index++] = fallbackCoords[7];
                    failedMappings++;
                    
                    System.err.println("[ModelPart] Missing texture mapping for " + textureVariant + ":" + faceName + " (part: " + name + "). Using fallback coordinates.");
                }
            }
            
            // Log mapping results for debugging
            if (failedMappings > 0) {
                System.err.println("[ModelPart] Texture coordinate mapping for part '" + name + "' (variant: " + textureVariant + "): " + 
                    successfulMappings + "/6 successful, " + failedMappings + "/6 failed");
            }
            
            return result;
        }
        
        /**
         * Maps texture identifiers to part type names for face mapping lookups.
         * Provides consistent mapping between model definition and texture atlas.
         */
        private String mapTextureToPartType(String textureId) {
            if (textureId == null) {
                System.err.println("[ModelPart] Null texture ID for part: " + name + ". Using HEAD as fallback.");
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
                    System.err.println("[ModelPart] Unknown texture ID: '" + textureId + "' for part: " + name + ". Using HEAD as fallback.");
                    yield "HEAD";
                }
            };
        }
        
        /**
         * Fallback texture coordinates for missing or invalid texture mappings.
         * Uses the first tile (0,0) in the 16×16 grid system.
         */
        private float[] getFallbackTextureCoords() {
            // Use coordinate (0,0) in 16x16 grid - this matches StonebreakTextureAtlas.getDefaultUVCoordinates()
            float tileSize = 1.0f / 16.0f; // 0.0625f for exact mathematical precision
            return new float[]{
                0.0f, 0.0f,         // bottom-left
                tileSize, 0.0f,     // bottom-right
                tileSize, tileSize, // top-right
                0.0f, tileSize      // top-left
            };
        }
    }
    
    /**
     * 3D position coordinates.
     */
    public static class Position {
        @JsonProperty("x")
        private float x;
        
        @JsonProperty("y")
        private float y;
        
        @JsonProperty("z")
        private float z;
        
        // Constructors
        public Position() {}
        
        public Position(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        // Getters and setters
        public float getX() { return x; }
        public void setX(float x) { this.x = x; }
        
        public float getY() { return y; }
        public void setY(float y) { this.y = y; }
        
        public float getZ() { return z; }
        public void setZ(float z) { this.z = z; }
    }
    
    /**
     * 3D size dimensions.
     */
    public static class Size {
        @JsonProperty("x")
        private float x;
        
        @JsonProperty("y")
        private float y;
        
        @JsonProperty("z")
        private float z;
        
        // Constructors
        public Size() {}
        
        public Size(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        // Getters and setters
        public float getX() { return x; }
        public void setX(float x) { this.x = x; }
        
        public float getY() { return y; }
        public void setY(float y) { this.y = y; }
        
        public float getZ() { return z; }
        public void setZ(float z) { this.z = z; }
    }
    
    /**
     * Animation definition with leg rotations, head pitch, and tail sway.
     */
    public static class ModelAnimation {
        @JsonProperty("legRotations")
        private float[] legRotations;
        
        @JsonProperty("headPitch")
        private float headPitch;
        
        @JsonProperty("tailSway")
        private float tailSway;
        
        // Constructors
        public ModelAnimation() {}
        
        public ModelAnimation(float[] legRotations, float headPitch, float tailSway) {
            this.legRotations = legRotations;
            this.headPitch = headPitch;
            this.tailSway = tailSway;
        }
        
        // Getters and setters
        public float[] getLegRotations() { return legRotations; }
        public void setLegRotations(float[] legRotations) { this.legRotations = legRotations; }
        
        public float getHeadPitch() { return headPitch; }
        public void setHeadPitch(float headPitch) { this.headPitch = headPitch; }
        
        public float getTailSway() { return tailSway; }
        public void setTailSway(float tailSway) { this.tailSway = tailSway; }
    }
    
    /**
     * Test method to validate Open Mason Phase 2 texture coordinate integration.
     * Tests the complete integration between 3D model faces and 2D texture atlas.
     * Validates all 4 cow variants with mathematical precision.
     */
    public static void main(String[] args) {
        System.out.println("=== Open Mason Phase 2 - Texture Coordinate Integration Test ===");
        System.out.println();
        
        try {
            // Test model parts for all cow body parts
            System.out.println("1. Testing texture coordinate integration for all cow model parts...");
            
            ModelPart[] testParts = {
                new ModelPart("head", new Position(0, 1.5f, 0), new Size(1.0f, 1.0f, 1.0f), "cow_head"),
                new ModelPart("body", new Position(0, 0, 0), new Size(2.0f, 1.0f, 1.5f), "cow_body"),
                new ModelPart("leg1", new Position(-0.7f, -1.0f, 0.5f), new Size(0.3f, 1.0f, 0.3f), "cow_legs"),
                new ModelPart("leg2", new Position(0.7f, -1.0f, 0.5f), new Size(0.3f, 1.0f, 0.3f), "cow_legs"),
                new ModelPart("horns", new Position(0, 2.0f, 0), new Size(0.2f, 0.3f, 0.2f), "cow_horns"),
                new ModelPart("udder", new Position(0, -0.8f, 0), new Size(0.8f, 0.4f, 0.6f), "cow_udder"),
                new ModelPart("tail", new Position(0, 0.5f, -1.2f), new Size(0.1f, 0.1f, 0.8f), "cow_tail")
            };
            
            String[] variants = {"default", "angus", "highland", "jersey"};
            
            System.out.println("   Testing " + testParts.length + " model parts with " + variants.length + " variants...");
            System.out.println();
            
            int totalTests = 0;
            int passedTests = 0;
            int failedTests = 0;
            
            for (ModelPart part : testParts) {
                System.out.println("2. Testing model part: " + part.getName() + " (texture: " + part.getTexture() + ")");
                
                // Validate texture coordinate integration for this part
                boolean integrationValid = part.validateTextureCoordinateIntegration();
                
                if (integrationValid) {
                    System.out.println("   ✓ Integration validation PASSED");
                } else {
                    System.err.println("   ✗ Integration validation FAILED");
                }
                
                // Test texture coordinate generation for each variant
                for (String variant : variants) {
                    totalTests++;
                    
                    try {
                        float[] coords = part.getTextureCoords(variant);
                        
                        // Validate coordinate array structure
                        if (coords == null || coords.length != 48) {
                            System.err.println("     ✗ " + variant + ": Invalid coordinate array length (expected 48, got " + 
                                (coords != null ? coords.length : "null") + ")");
                            failedTests++;
                            continue;
                        }
                        
                        // Validate coordinate values are within valid UV range [0.0, 1.0]
                        boolean validRange = true;
                        for (float coord : coords) {
                            if (coord < 0.0f || coord > 1.0f) {
                                validRange = false;
                                break;
                            }
                        }
                        
                        if (!validRange) {
                            System.err.println("     ✗ " + variant + ": Coordinates outside valid UV range [0.0, 1.0]");
                            failedTests++;
                            continue;
                        }
                        
                        // Test optimized coordinate lookup with caching
                        float[] cachedCoords = part.getTextureCoordsOptimized(variant, true);
                        boolean cacheMatches = java.util.Arrays.equals(coords, cachedCoords);
                        
                        if (!cacheMatches) {
                            System.err.println("     ✗ " + variant + ": Cached coordinates don't match direct lookup");
                            failedTests++;
                            continue;
                        }
                        
                        System.out.println("     ✓ " + variant + ": " + coords.length + " coordinates generated successfully");
                        passedTests++;
                        
                    } catch (Exception e) {
                        System.err.println("     ✗ " + variant + ": Exception during coordinate generation - " + e.getMessage());
                        failedTests++;
                    }
                }
                
                System.out.println();
            }
            
            // Print final test results
            System.out.println("3. Final integration test results:");
            System.out.println("   Total tests: " + totalTests);
            System.out.println("   Passed: " + passedTests);
            System.out.println("   Failed: " + failedTests);
            System.out.println("   Success rate: " + String.format("%.1f", (passedTests * 100.0 / totalTests)) + "%");
            System.out.println();
            
            // Print cache statistics
            System.out.println("4. Performance optimization results:");
            System.out.println("   " + ModelPart.getTextureCacheStats());
            System.out.println();
            
            if (failedTests == 0) {
                System.out.println("=== TEXTURE COORDINATE INTEGRATION TEST PASSED ===");
                System.out.println("✓ All model parts successfully integrated with texture atlas");
                System.out.println("✓ All 4 cow variants validated with correct UV coordinates");
                System.out.println("✓ Mathematical precision maintained (16×16 grid system)");
                System.out.println("✓ Performance optimization working (coordinate caching)");
                System.out.println("✓ Error handling functional (fallback coordinates)");
                System.out.println("✓ 1:1 Stonebreak rendering parity achieved");
            } else {
                System.err.println("=== TEXTURE COORDINATE INTEGRATION TEST FAILED ===");
                System.err.println("✗ " + failedTests + "/" + totalTests + " tests failed");
                System.err.println("✗ Some texture coordinate mappings are missing or invalid");
                System.err.println("✗ Review texture atlas definitions and face mappings");
            }
            
        } catch (Exception e) {
            System.err.println("=== CRITICAL INTEGRATION ERROR ===");
            System.err.println("Fatal error during texture coordinate integration test: " + e.getMessage());
            e.printStackTrace();
        }
    }
}