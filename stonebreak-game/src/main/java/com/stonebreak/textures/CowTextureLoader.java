package com.stonebreak.textures;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CowTextureLoader {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Map<String, CowTextureDefinition.CowVariant> cachedVariants = new ConcurrentHashMap<>();
    
    // Paths to individual cow variant JSON files
    private static final Map<String, String> VARIANT_FILE_PATHS = Map.of(
        "default", "textures/mobs/cow/default_cow.json",
        "angus", "textures/mobs/cow/angus_cow.json",
        "highland", "textures/mobs/cow/highland_cow.json",
        "jersey", "textures/mobs/cow/jersey_cow.json"
    );
    
    
    public static CowTextureDefinition.CowVariant getCowVariant(String variantName) {
        // Check if variant is already cached
        CowTextureDefinition.CowVariant cached = cachedVariants.get(variantName);
        if (cached != null) {
            return cached;
        }
        
        // Attempt to load the variant
        try {
            CowTextureDefinition.CowVariant variant = loadIndividualVariant(variantName);
            if (variant != null) {
                cachedVariants.put(variantName, variant);
                System.out.println("[CowTextureLoader] Successfully cached variant: " + variantName);
                return variant;
            }
        } catch (IOException e) {
            System.err.println("[CowTextureLoader] Failed to load variant '" + variantName + "': " + e.getMessage());
        }
        
        // Fallback to default variant WITHOUT corrupting the cache
        if (!"default".equals(variantName)) {
            System.err.println("[CowTextureLoader] Using fallback default variant for failed variant: " + variantName);
            CowTextureDefinition.CowVariant defaultVariant = getCowVariant("default");
            // DO NOT cache the default variant under the failed variant's name
            // This prevents cache corruption while still providing a fallback
            return defaultVariant;
        }
        
        // If default variant itself fails, return null
        System.err.println("[CowTextureLoader] Critical error: default variant failed to load");
        return null;
    }
    
    /**
     * Load an individual cow variant from its JSON file.
     */
    public static CowTextureDefinition.CowVariant loadIndividualVariant(String variantName) throws IOException {
        String filePath = VARIANT_FILE_PATHS.get(variantName);
        if (filePath == null) {
            throw new IOException("Unknown cow variant: " + variantName + ". Available variants: " + VARIANT_FILE_PATHS.keySet());
        }
        
        // Try different approaches for module compatibility
        InputStream inputStream = null;
        
        // First try: Module's class loader
        inputStream = CowTextureLoader.class.getClassLoader().getResourceAsStream(filePath);
        
        // Second try: Module class itself
        if (inputStream == null) {
            inputStream = CowTextureLoader.class.getResourceAsStream("/" + filePath);
        }
        
        // Third try: Context class loader
        if (inputStream == null) {
            inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(filePath);
        }
        
        try (InputStream finalInputStream = inputStream) {
            if (finalInputStream == null) {
                throw new IOException("Could not find resource: " + filePath);
            }
            
            CowTextureDefinition.CowVariant variant = objectMapper.readValue(finalInputStream, CowTextureDefinition.CowVariant.class);
            
            // Validate the loaded variant
            validateCowVariant(variant, variantName);
            
            System.out.println("[CowTextureLoader] Successfully loaded cow variant '" + variantName + "' (" + 
                variant.getDisplayName() + ") with " + variant.getFaceMappings().size() + " face mappings");
            
            // Log drawing instructions if present
            if (variant.getDrawingInstructions() != null) {
                System.out.println("  Drawing instructions loaded for " + variant.getDrawingInstructions().size() + " body parts");
            }
            
            return variant;
        }
    }
    
    /**
     * Get atlas coordinates for a specific body part face.
     * @param variantName The cow variant (default, angus, highland, jersey)
     * @param faceName The face name (e.g., "HEAD_FRONT", "BODY_LEFT", etc.)
     * @return AtlasCoordinate containing atlasX and atlasY, or null if not found
     */
    public static CowTextureDefinition.AtlasCoordinate getAtlasCoordinate(String variantName, String faceName) {
        if (variantName == null || faceName == null) {
            System.err.println("[CowTextureLoader] Null parameters in getAtlasCoordinate: variantName=" + variantName + ", faceName=" + faceName);
            return null;
        }
        
        CowTextureDefinition.CowVariant variant = getCowVariant(variantName);
        if (variant == null) {
            System.err.println("[CowTextureLoader] Could not get variant: " + variantName);
            return null;
        }
        
        if (variant.getFaceMappings() == null) {
            System.err.println("[CowTextureLoader] No face mappings available for variant: " + variantName);
            return null;
        }
        
        CowTextureDefinition.AtlasCoordinate coordinate = variant.getFaceMappings().get(faceName);
        if (coordinate == null) {
            System.err.println("[CowTextureLoader] No mapping found for face: " + faceName + " in variant: " + variantName);
            System.err.println("  Available faces: " + variant.getFaceMappings().keySet());
            return null;
        }
        
        // coordinate values are primitive ints, no null check needed
        
        return coordinate;
    }
    
    /**
     * Get normalized UV coordinates for a specific body part face.
     * @param variantName The cow variant
     * @param faceName The face name
     * @param gridSize The texture atlas grid size (usually 16)
     * @return float array with UV coordinates [u1, v1, u2, v2] normalized to 0.0-1.0 range
     */
    public static float[] getNormalizedUVCoordinates(String variantName, String faceName, int gridSize) {
        if (gridSize <= 0) {
            System.err.println("[CowTextureLoader] Invalid grid size: " + gridSize);
            return new float[]{0.0f, 0.0f, 0.0625f, 0.0625f}; // Default for 16x16 grid
        }
        
        CowTextureDefinition.AtlasCoordinate coordinate = getAtlasCoordinate(variantName, faceName);
        if (coordinate == null) {
            // Return fallback coordinates (0,0 tile)
            float tileSize = 1.0f / gridSize;
            return new float[]{0.0f, 0.0f, tileSize, tileSize};
        }
        
        // Additional safety checks
        int x = coordinate.getAtlasX();
        int y = coordinate.getAtlasY();
        
        if (x < 0 || x >= gridSize || y < 0 || y >= gridSize) {
            System.err.println("[CowTextureLoader] Coordinate out of bounds for " + variantName + ":" + faceName + 
                              " - (" + x + "," + y + ") must be within 0-" + (gridSize-1));
            float tileSize = 1.0f / gridSize;
            return new float[]{0.0f, 0.0f, tileSize, tileSize};
        }
        
        float tileSize = 1.0f / gridSize;
        float u1 = x * tileSize;
        float v1 = y * tileSize;
        float u2 = u1 + tileSize;
        float v2 = v1 + tileSize;
        
        return new float[]{u1, v1, u2, v2};
    }
    
    /**
     * Get UV coordinates formatted for quad rendering (bottom-left, bottom-right, top-right, top-left).
     * @param variantName The cow variant
     * @param faceName The face name
     * @param gridSize The texture atlas grid size
     * @return float array with 8 UV coordinates for quad vertices
     */
    public static float[] getQuadUVCoordinates(String variantName, String faceName, int gridSize) {
        float[] coords = getNormalizedUVCoordinates(variantName, faceName, gridSize);
        float u1 = coords[0];
        float v1 = coords[1]; 
        float u2 = coords[2];
        float v2 = coords[3];
        
        // Return coordinates for quad vertices (OpenGL style)
        return new float[]{
            u1, v1,  // bottom-left
            u2, v1,  // bottom-right
            u2, v2,  // top-right
            u1, v2   // top-left
        };
    }
    
    public static String getBaseColor(String variantName, String colorType) {
        if (variantName == null || colorType == null) {
            System.err.println("[CowTextureLoader] Null parameters in getBaseColor: variantName=" + variantName + ", colorType=" + colorType);
            return "#FFFFFF";
        }
        
        CowTextureDefinition.CowVariant variant = getCowVariant(variantName);
        if (variant == null || variant.getBaseColors() == null) {
            System.err.println("[CowTextureLoader] No base colors available for variant: " + variantName);
            return "#FFFFFF";
        }
        
        String color = switch (colorType.toLowerCase()) {
            case "primary" -> variant.getBaseColors().getPrimary();
            case "secondary" -> variant.getBaseColors().getSecondary();
            case "accent" -> variant.getBaseColors().getAccent();
            default -> null;
        };
        
        if (color == null) {
            System.err.println("[CowTextureLoader] Color '" + colorType + "' not found for variant: " + variantName);
            return "#FFFFFF";
        }
        
        return color;
    }
    
    public static int hexColorToInt(String hexColor) {
        if (hexColor == null || !hexColor.startsWith("#") || hexColor.length() != 7) {
            return 0xFFFFFF;
        }
        
        try {
            return Integer.parseInt(hexColor.substring(1), 16);
        } catch (NumberFormatException e) {
            System.err.println("[CowTextureLoader] Invalid hex color format: " + hexColor);
            return 0xFFFFFF;
        }
    }
    
    public static void clearCache() {
        System.out.println("[CowTextureLoader] Clearing texture cache. Current cached variants: " + cachedVariants.keySet());
        cachedVariants.clear();
    }
    
    public static boolean isValidVariant(String variantName) {
        return VARIANT_FILE_PATHS.containsKey(variantName);
    }
    
    /**
     * Get all available cow variant names.
     */
    public static String[] getAvailableVariants() {
        return VARIANT_FILE_PATHS.keySet().toArray(new String[0]);
    }
    
    /**
     * Debug method to get current cache status.
     */
    public static void printCacheStatus() {
        System.out.println("[CowTextureLoader] Cache Status:");
        System.out.println("  Available variants: " + VARIANT_FILE_PATHS.keySet());
        System.out.println("  Cached variants: " + cachedVariants.keySet());
        for (Map.Entry<String, CowTextureDefinition.CowVariant> entry : cachedVariants.entrySet()) {
            String variantName = entry.getKey();
            CowTextureDefinition.CowVariant variant = entry.getValue();
            String displayName = variant != null ? variant.getDisplayName() : "null";
            System.out.println("    " + variantName + " -> " + displayName);
        }
    }
    
    /**
     * Test method to verify all variants can be loaded correctly.
     * Useful for debugging texture loading issues.
     */
    public static void testAllVariants() {
        System.out.println("[CowTextureLoader] Testing all variants...");
        for (String variantName : VARIANT_FILE_PATHS.keySet()) {
            try {
                CowTextureDefinition.CowVariant variant = getCowVariant(variantName);
                if (variant != null) {
                    System.out.println("  ✓ " + variantName + " -> " + variant.getDisplayName());
                    
                    // Test drawing instructions for each face mapping
                    int faceMappingCount = variant.getFaceMappings().size();
                    int drawingInstructionCount = variant.getDrawingInstructions() != null ? variant.getDrawingInstructions().size() : 0;
                    System.out.println("    Face mappings: " + faceMappingCount + ", Drawing instructions: " + drawingInstructionCount);
                    
                    if (faceMappingCount != drawingInstructionCount) {
                        System.err.println("    WARNING: Mismatch between face mappings and drawing instructions!");
                    }
                } else {
                    System.err.println("  ✗ " + variantName + " -> FAILED (null)");
                }
            } catch (Exception e) {
                System.err.println("  ✗ " + variantName + " -> ERROR: " + e.getMessage());
            }
        }
        printCacheStatus();
    }
    
    /**
     * Get drawing instructions for a specific cow variant and body part.
     */
    public static CowTextureDefinition.DrawingInstructions getDrawingInstructions(String variantName, String bodyPart) {
        if (variantName == null || bodyPart == null) {
            System.err.println("[CowTextureLoader] Null parameters in getDrawingInstructions: variantName=" + variantName + ", bodyPart=" + bodyPart);
            return null;
        }
        
        CowTextureDefinition.CowVariant variant = getCowVariant(variantName);
        if (variant == null) {
            System.err.println("[CowTextureLoader] Could not get variant for drawing instructions: " + variantName);
            return null;
        }
        
        if (variant.getDrawingInstructions() == null) {
            System.err.println("[CowTextureLoader] No drawing instructions available for variant: " + variantName);
            return null;
        }
        
        CowTextureDefinition.DrawingInstructions instructions = variant.getDrawingInstructions().get(bodyPart);
        if (instructions == null) {
            System.err.println("[CowTextureLoader] No drawing instructions found for " + variantName + ":" + bodyPart);
            System.err.println("  Available body parts: " + variant.getDrawingInstructions().keySet());
            System.err.println("  This will cause fallback texture generation for this part!");
        }
        
        return instructions;
    }
    
    /**
     * Validate an individual cow variant to ensure it has all required data.
     */
    private static void validateCowVariant(CowTextureDefinition.CowVariant variant, String variantName) throws IOException {
        if (variant == null) {
            throw new IOException("Variant '" + variantName + "' is null");
        }
        
        if (variant.getFaceMappings() == null || variant.getFaceMappings().isEmpty()) {
            throw new IOException("Variant '" + variantName + "' has no face mappings");
        }
        
        if (variant.getBaseColors() == null) {
            throw new IOException("Variant '" + variantName + "' has no base colors");
        }
        
        // Validate required face mappings
        String[] requiredFaces = {
            "HEAD_FRONT", "HEAD_BACK", "HEAD_LEFT", "HEAD_RIGHT", "HEAD_TOP", "HEAD_BOTTOM",
            "BODY_FRONT", "BODY_BACK", "BODY_LEFT", "BODY_RIGHT", "BODY_TOP", "BODY_BOTTOM"
        };
        
        for (String requiredFace : requiredFaces) {
            if (!variant.getFaceMappings().containsKey(requiredFace)) {
                throw new IOException("Variant '" + variantName + "' missing required face: " + requiredFace);
            }
            
            CowTextureDefinition.AtlasCoordinate coord = variant.getFaceMappings().get(requiredFace);
            if (coord.getAtlasX() < 0 || coord.getAtlasX() >= 16 ||
                coord.getAtlasY() < 0 || coord.getAtlasY() >= 16) {
                throw new IOException("Variant '" + variantName + "' face '" + requiredFace + 
                    "' has invalid coordinates: (" + coord.getAtlasX() + "," + coord.getAtlasY() + 
                    ") - must be within 0-15");
            }
        }
        
        // Validate base colors
        if (variant.getBaseColors().getPrimary() == null || 
            variant.getBaseColors().getSecondary() == null || 
            variant.getBaseColors().getAccent() == null) {
            throw new IOException("Variant '" + variantName + "' missing required base colors");
        }
        
        // Validate drawing instructions if present
        if (variant.getDrawingInstructions() != null) {
            validateDrawingInstructions(variant.getDrawingInstructions(), variantName);
            
            // Check for missing drawing instructions for mapped faces
            for (String faceName : variant.getFaceMappings().keySet()) {
                if (!variant.getDrawingInstructions().containsKey(faceName)) {
                    System.err.println("[CowTextureLoader] WARNING: Variant '" + variantName + 
                        "' has face mapping '" + faceName + "' but no corresponding drawing instructions!");
                    System.err.println("  This will cause fallback texture generation for this part.");
                }
            }
        }
        
        System.out.println("[CowTextureLoader] Variant '" + variantName + "' validation passed");
    }
    
    /**
     * Validate drawing instructions for a cow variant.
     */
    private static void validateDrawingInstructions(Map<String, CowTextureDefinition.DrawingInstructions> instructions, String variantName) throws IOException {
        for (Map.Entry<String, CowTextureDefinition.DrawingInstructions> entry : instructions.entrySet()) {
            String bodyPart = entry.getKey();
            CowTextureDefinition.DrawingInstructions instruction = entry.getValue();
            
            if (instruction == null) {
                throw new IOException("Variant '" + variantName + "' has null drawing instructions for " + bodyPart);
            }
            
            if (instruction.getBaseTexture() == null) {
                throw new IOException("Variant '" + variantName + "' " + bodyPart + " missing base texture information");
            }
            
            if (instruction.getBaseTexture().getFillColor() == null) {
                throw new IOException("Variant '" + variantName + "' " + bodyPart + " missing base texture fill color");
            }
        }
    }
    
}