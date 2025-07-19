package com.stonebreak.textures;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class CowTextureLoader {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static CowTextureDefinition textureDefinition;
    private static final Map<String, CowTextureDefinition.CowVariant> cachedVariants = new HashMap<>();
    
    public static CowTextureDefinition loadTextureDefinition(String resourcePath) throws IOException {
        if (textureDefinition == null) {
            try (InputStream inputStream = CowTextureLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (inputStream == null) {
                    throw new IOException("Could not find resource: " + resourcePath);
                }
                textureDefinition = objectMapper.readValue(inputStream, CowTextureDefinition.class);
                
                // Validate the loaded definition
                validateTextureDefinition(textureDefinition);
                System.out.println("[CowTextureLoader] Successfully loaded cow texture definition: " + resourcePath);
                
                // Log loaded variants
                for (String variantName : textureDefinition.getCowVariants().keySet()) {
                    CowTextureDefinition.CowVariant variant = textureDefinition.getCowVariants().get(variantName);
                    System.out.println("  Loaded variant '" + variantName + "' (" + variant.getDisplayName() + ") with " + 
                        variant.getFaceMappings().size() + " face mappings");
                }
            }
        }
        return textureDefinition;
    }
    
    public static CowTextureDefinition.CowVariant getCowVariant(String variantName) {
        if (textureDefinition == null) {
            try {
                loadTextureDefinition("textures/mobs/cow/cow_textures.json");
            } catch (IOException e) {
                System.err.println("[CowTextureLoader] Failed to load cow texture definition: " + e.getMessage());
                return null;
            }
        }
        
        return cachedVariants.computeIfAbsent(variantName, name -> {
            CowTextureDefinition.CowVariant variant = textureDefinition.getCowVariants().get(name);
            if (variant == null) {
                System.err.println("[CowTextureLoader] Unknown cow variant: " + name + ". Available variants: " + 
                    textureDefinition.getCowVariants().keySet());
                return textureDefinition.getCowVariants().get("default");
            }
            return variant;
        });
    }
    
    /**
     * Get atlas coordinates for a specific body part face.
     * @param variantName The cow variant (default, angus, highland, jersey)
     * @param faceName The face name (e.g., "HEAD_FRONT", "BODY_LEFT", etc.)
     * @return AtlasCoordinate containing atlasX and atlasY, or null if not found
     */
    public static CowTextureDefinition.AtlasCoordinate getAtlasCoordinate(String variantName, String faceName) {
        CowTextureDefinition.CowVariant variant = getCowVariant(variantName);
        if (variant == null) {
            System.err.println("[CowTextureLoader] Could not get variant: " + variantName);
            return null;
        }
        
        CowTextureDefinition.AtlasCoordinate coordinate = variant.getFaceMappings().get(faceName);
        if (coordinate == null) {
            System.err.println("[CowTextureLoader] No mapping found for face: " + faceName + " in variant: " + variantName);
            System.err.println("  Available faces: " + variant.getFaceMappings().keySet());
            return null;
        }
        
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
        CowTextureDefinition.AtlasCoordinate coordinate = getAtlasCoordinate(variantName, faceName);
        if (coordinate == null) {
            // Return fallback coordinates (0,0 tile)
            float tileSize = 1.0f / gridSize;
            return new float[]{0.0f, 0.0f, tileSize, tileSize};
        }
        
        float tileSize = 1.0f / gridSize;
        float u1 = coordinate.getAtlasX() * tileSize;
        float v1 = coordinate.getAtlasY() * tileSize;
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
        CowTextureDefinition.CowVariant variant = getCowVariant(variantName);
        if (variant == null || variant.getBaseColors() == null) {
            return "#FFFFFF";
        }
        
        return switch (colorType.toLowerCase()) {
            case "primary" -> variant.getBaseColors().getPrimary();
            case "secondary" -> variant.getBaseColors().getSecondary();
            case "accent" -> variant.getBaseColors().getAccent();
            default -> "#FFFFFF";
        };
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
        cachedVariants.clear();
        textureDefinition = null;
    }
    
    public static boolean isValidVariant(String variantName) {
        if (textureDefinition == null) {
            try {
                loadTextureDefinition("textures/mobs/cow/cow_textures.json");
            } catch (IOException e) {
                return false;
            }
        }
        return textureDefinition.getCowVariants().containsKey(variantName);
    }
    
    /**
     * Validate the texture definition to ensure it has all required data.
     */
    private static void validateTextureDefinition(CowTextureDefinition definition) throws IOException {
        if (definition.getCowVariants() == null || definition.getCowVariants().isEmpty()) {
            throw new IOException("No cow variants defined in texture definition");
        }
        
        if (definition.getTextureAtlas() == null) {
            throw new IOException("No texture atlas information defined");
        }
        
        int gridSize = definition.getTextureAtlas().getGridSize();
        if (gridSize <= 0) {
            throw new IOException("Invalid grid size: " + gridSize);
        }
        
        // Validate each variant has required face mappings
        String[] requiredFaces = {
            "HEAD_FRONT", "HEAD_BACK", "HEAD_LEFT", "HEAD_RIGHT", "HEAD_TOP", "HEAD_BOTTOM",
            "BODY_FRONT", "BODY_BACK", "BODY_LEFT", "BODY_RIGHT", "BODY_TOP", "BODY_BOTTOM"
        };
        
        for (Map.Entry<String, CowTextureDefinition.CowVariant> entry : definition.getCowVariants().entrySet()) {
            String variantName = entry.getKey();
            CowTextureDefinition.CowVariant variant = entry.getValue();
            
            if (variant.getFaceMappings() == null) {
                throw new IOException("Variant '" + variantName + "' has no face mappings");
            }
            
            for (String requiredFace : requiredFaces) {
                if (!variant.getFaceMappings().containsKey(requiredFace)) {
                    throw new IOException("Variant '" + variantName + "' missing required face: " + requiredFace);
                }
                
                CowTextureDefinition.AtlasCoordinate coord = variant.getFaceMappings().get(requiredFace);
                if (coord.getAtlasX() < 0 || coord.getAtlasX() >= gridSize ||
                    coord.getAtlasY() < 0 || coord.getAtlasY() >= gridSize) {
                    throw new IOException("Variant '" + variantName + "' face '" + requiredFace + 
                        "' has invalid coordinates: (" + coord.getAtlasX() + "," + coord.getAtlasY() + 
                        ") - must be within 0-" + (gridSize-1));
                }
            }
        }
        
        System.out.println("[CowTextureLoader] Texture definition validation passed");
    }
}