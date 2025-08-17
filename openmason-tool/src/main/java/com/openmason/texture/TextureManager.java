package com.openmason.texture;

import com.stonebreak.textures.CowTextureDefinition;
import com.stonebreak.textures.CowTextureLoader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simplified texture management system for Open Mason.
 * Provides basic texture variant access and caching functionality.
 * Wraps the Stonebreak texture system with caching and validation.
 */
public class TextureManager {
    
    private static final Map<String, TextureVariantInfo> variantInfoCache = new ConcurrentHashMap<>();
    private static boolean initialized = false;
    
    /**
     * Information about a texture variant.
     */
    public static class TextureVariantInfo {
        private final String variantName;
        private final String displayName;
        private final int faceMappingCount;
        private final int drawingInstructionCount;
        private final CowTextureDefinition.CowVariant variantDefinition;
        private final Map<String, String> baseColors;
        
        public TextureVariantInfo(String variantName, String displayName, int faceMappingCount, 
                                 int drawingInstructionCount, CowTextureDefinition.CowVariant variantDefinition,
                                 Map<String, String> baseColors) {
            this.variantName = variantName;
            this.displayName = displayName;
            this.faceMappingCount = faceMappingCount;
            this.drawingInstructionCount = drawingInstructionCount;
            this.variantDefinition = variantDefinition;
            this.baseColors = baseColors;
        }
        
        public String getVariantName() { return variantName; }
        public String getDisplayName() { return displayName; }
        public int getFaceMappingCount() { return faceMappingCount; }
        public int getDrawingInstructionCount() { return drawingInstructionCount; }
        public CowTextureDefinition.CowVariant getVariantDefinition() { return variantDefinition; }
        public Map<String, String> getBaseColors() { return baseColors; }
        
        @Override
        public String toString() {
            return String.format("TextureVariantInfo{name='%s', display='%s', faces=%d, instructions=%d}", 
                variantName, displayName, faceMappingCount, drawingInstructionCount);
        }
    }
    
    /**
     * Initialize the TextureManager system.
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        
        System.out.println("[TextureManager] Initializing texture management system...");
        
        // Get available variants and pre-load them
        String[] availableVariants = CowTextureLoader.getAvailableVariants();
        for (String variantName : availableVariants) {
            try {
                loadVariantInfo(variantName);
            } catch (Exception e) {
                System.err.println("[TextureManager] Failed to load variant '" + variantName + "': " + e.getMessage());
            }
        }
        
        initialized = true;
        System.out.println("[TextureManager] Initialization complete. Loaded " + variantInfoCache.size() + " variants");
    }
    
    /**
     * Load variant information if not already cached.
     */
    private static TextureVariantInfo loadVariantInfo(String variantName) {
        // Check if already cached
        TextureVariantInfo cached = variantInfoCache.get(variantName);
        if (cached != null) {
            return cached;
        }
        
        // Load from texture system
        CowTextureDefinition.CowVariant variant = CowTextureLoader.getCowVariant(variantName);
        if (variant != null) {
            TextureVariantInfo info = createVariantInfo(variantName, variant);
            variantInfoCache.put(variantName, info);
            return info;
        }
        
        return null;
    }
    
    /**
     * Create TextureVariantInfo from a loaded variant definition.
     */
    private static TextureVariantInfo createVariantInfo(String variantName, CowTextureDefinition.CowVariant variant) {
        int faceMappingCount = variant.getFaceMappings() != null ? variant.getFaceMappings().size() : 0;
        int drawingInstructionCount = variant.getDrawingInstructions() != null ? variant.getDrawingInstructions().size() : 0;
        
        Map<String, String> baseColors = Map.of(
            "primary", variant.getBaseColors() != null ? variant.getBaseColors().getPrimary() : "#FFFFFF",
            "secondary", variant.getBaseColors() != null ? variant.getBaseColors().getSecondary() : "#FFFFFF",
            "accent", variant.getBaseColors() != null ? variant.getBaseColors().getAccent() : "#FFFFFF"
        );
        
        return new TextureVariantInfo(variantName, variant.getDisplayName(), 
            faceMappingCount, drawingInstructionCount, variant, baseColors);
    }
    
    /**
     * Get information about a specific texture variant.
     */
    public static TextureVariantInfo getVariantInfo(String variantName) {
        if (!initialized) {
            initialize();
        }
        
        return loadVariantInfo(variantName);
    }
    
    /**
     * Get UV coordinates for a specific face of a texture variant.
     */
    public static float[] getUVCoordinates(String variantName, String faceName) {
        if (!initialized) {
            initialize();
        }
        
        return CowTextureLoader.getNormalizedUVCoordinates(variantName, faceName, 16);
    }
    
    /**
     * Get normalized UV coordinates (0.0-1.0 range) for a specific face.
     */
    public static float[] getNormalizedUVCoordinates(String variantName, String faceName) {
        if (!initialized) {
            initialize();
        }
        
        return CowTextureLoader.getNormalizedUVCoordinates(variantName, faceName, 16);
    }
    
    /**
     * Get atlas coordinates for a specific face.
     */
    public static CowTextureDefinition.AtlasCoordinate getAtlasCoordinate(String variantName, String faceName) {
        if (!initialized) {
            initialize();
        }
        
        return CowTextureLoader.getAtlasCoordinate(variantName, faceName);
    }
    
    /**
     * Get base color for a texture variant.
     */
    public static String getBaseColor(String variantName, String colorType) {
        if (!initialized) {
            initialize();
        }
        
        return CowTextureLoader.getBaseColor(variantName, colorType);
    }
    
    /**
     * Get list of all available texture variants.
     */
    public static List<String> getAvailableVariants() {
        if (!initialized) {
            initialize();
        }
        
        return Arrays.asList(CowTextureLoader.getAvailableVariants());
    }
    
    /**
     * Get list of all face names for a texture variant.
     */
    public static List<String> getFaceNames(String variantName) {
        TextureVariantInfo info = getVariantInfo(variantName);
        if (info == null || info.getVariantDefinition().getFaceMappings() == null) {
            return List.of();
        }
        
        return List.copyOf(info.getVariantDefinition().getFaceMappings().keySet());
    }
    
    /**
     * Validate that a texture variant can be loaded successfully.
     */
    public static boolean validateVariant(String variantName) {
        if (!CowTextureLoader.isValidVariant(variantName)) {
            return false;
        }
        
        try {
            TextureVariantInfo info = getVariantInfo(variantName);
            return info != null && info.getFaceMappingCount() > 0;
        } catch (Exception e) {
            System.err.println("[TextureManager] Variant validation failed for '" + variantName + "': " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Validate that all face mappings have valid coordinates.
     */
    public static boolean validateCoordinates(String variantName) {
        TextureVariantInfo info = getVariantInfo(variantName);
        if (info == null) {
            return false;
        }
        
        Map<String, CowTextureDefinition.AtlasCoordinate> faceMappings = 
            info.getVariantDefinition().getFaceMappings();
        
        if (faceMappings == null) {
            return false;
        }
        
        for (Map.Entry<String, CowTextureDefinition.AtlasCoordinate> entry : faceMappings.entrySet()) {
            String faceName = entry.getKey();
            CowTextureDefinition.AtlasCoordinate coord = entry.getValue();
            
            if (coord == null) {
                System.err.println("[TextureManager] Null coordinate for face: " + faceName);
                return false;
            }
            
            if (coord.getAtlasX() < 0 || coord.getAtlasX() >= 16 || 
                coord.getAtlasY() < 0 || coord.getAtlasY() >= 16) {
                System.err.println("[TextureManager] Invalid coordinate for face " + faceName + 
                    ": (" + coord.getAtlasX() + "," + coord.getAtlasY() + ") - must be 0-15");
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Get detailed statistics about a texture variant.
     */
    public static String getVariantStatistics(String variantName) {
        TextureVariantInfo info = getVariantInfo(variantName);
        if (info == null) {
            return "Variant not found: " + variantName;
        }
        
        boolean coordinatesValid = validateCoordinates(variantName);
        List<String> faceNames = getFaceNames(variantName);
        
        StringBuilder stats = new StringBuilder();
        stats.append(String.format("Variant: %s (%s)\n", info.getVariantName(), info.getDisplayName()));
        stats.append(String.format("Face Mappings: %d\n", info.getFaceMappingCount()));
        stats.append(String.format("Drawing Instructions: %d\n", info.getDrawingInstructionCount()));
        stats.append(String.format("Coordinates Valid: %s\n", coordinatesValid ? "✓" : "✗"));
        stats.append(String.format("Base Colors: Primary=%s, Secondary=%s, Accent=%s\n", 
            info.getBaseColors().get("primary"),
            info.getBaseColors().get("secondary"), 
            info.getBaseColors().get("accent")));
        stats.append(String.format("Face Names: [%s]\n", String.join(", ", faceNames)));
        
        return stats.toString();
    }
    
    /**
     * Test UV coordinate generation for a specific variant and face.
     */
    public static void testUVGeneration(String variantName, String faceName) {
        System.out.println("[TextureManager] Testing UV generation for " + variantName + ":" + faceName);
        
        // Get atlas coordinate
        CowTextureDefinition.AtlasCoordinate coord = getAtlasCoordinate(variantName, faceName);
        if (coord == null) {
            System.err.println("  ✗ Atlas coordinate not found");
            return;
        }
        
        System.out.println("  Atlas Coordinate: (" + coord.getAtlasX() + "," + coord.getAtlasY() + ")");
        
        // Get UV coordinates
        float[] uv = getNormalizedUVCoordinates(variantName, faceName);
        System.out.println("  UV Coordinates: [" + uv[0] + ", " + uv[1] + ", " + uv[2] + ", " + uv[3] + "]");
        
        // Test mathematical consistency
        float expectedU1 = coord.getAtlasX() / 16.0f;
        float expectedV1 = coord.getAtlasY() / 16.0f;
        float expectedU2 = expectedU1 + (1.0f / 16.0f);
        float expectedV2 = expectedV1 + (1.0f / 16.0f);
        
        boolean uvValid = Math.abs(uv[0] - expectedU1) < 0.001f &&
                         Math.abs(uv[1] - expectedV1) < 0.001f &&
                         Math.abs(uv[2] - expectedU2) < 0.001f &&
                         Math.abs(uv[3] - expectedV2) < 0.001f;
        
        System.out.println("  Mathematical Consistency: " + (uvValid ? "✓" : "✗"));
        if (!uvValid) {
            System.out.println("    Expected: [" + expectedU1 + ", " + expectedV1 + ", " + expectedU2 + ", " + expectedV2 + "]");
        }
    }
    
    /**
     * Test method to validate all texture variants can be loaded correctly.
     */
    public static boolean testAllVariants() {
        System.out.println("[TextureManager] Testing all texture variants...");
        boolean allValid = true;
        
        for (String variantName : getAvailableVariants()) {
            boolean valid = validateVariant(variantName) && validateCoordinates(variantName);
            String status = valid ? "✓" : "✗";
            TextureVariantInfo info = getVariantInfo(variantName);
            System.out.println("  " + status + " " + variantName + (valid && info != null ? 
                " -> " + info.getDisplayName() + " (" + info.getFaceMappingCount() + " faces)" : " -> FAILED"));
            
            if (!valid) {
                allValid = false;
            }
        }
        
        System.out.println("[TextureManager] Texture variant testing complete. Result: " + (allValid ? "ALL PASS" : "SOME FAILED"));
        return allValid;
    }
    
    /**
     * Clear all cached texture data and reset initialization state.
     */
    public static synchronized void clearCache() {
        System.out.println("[TextureManager] Clearing texture cache...");
        
        variantInfoCache.clear();
        CowTextureLoader.clearCache();
        initialized = false;
        
        System.out.println("[TextureManager] Cache cleared and system reset");
    }
    
    /**
     * Print all variant information.
     */
    public static void printAllVariantInfo() {
        if (!initialized) {
            System.out.println("[TextureManager] System not initialized. Call initialize() first.");
            return;
        }
        
        System.out.println("[TextureManager] === Texture Management System Status ===");
        System.out.println("  Initialized: " + initialized);
        System.out.println("  Cached variants: " + variantInfoCache.size());
        System.out.println();
        
        for (TextureVariantInfo info : variantInfoCache.values()) {
            System.out.println(getVariantStatistics(info.getVariantName()));
            System.out.println("---");
        }
        
        CowTextureLoader.printCacheStatus();
    }
}