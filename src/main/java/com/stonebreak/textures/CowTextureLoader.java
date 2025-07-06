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
                TextureValidation.ValidationResult validation = TextureValidation.validateTextureDefinition(textureDefinition);
                if (!validation.isValid()) {
                    System.err.println("Texture definition validation failed for: " + resourcePath);
                    validation.printResults();
                    throw new IOException("Invalid texture definition: " + validation.getErrors());
                } else {
                    System.out.println("Successfully validated texture definition: " + resourcePath);
                    if (!validation.getWarnings().isEmpty()) {
                        validation.printResults();
                    }
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
                System.err.println("Failed to load cow texture definition: " + e.getMessage());
                return null;
            }
        }
        
        return cachedVariants.computeIfAbsent(variantName, name -> {
            CowTextureDefinition.CowVariant variant = textureDefinition.getCowVariants().get(name);
            if (variant == null) {
                System.err.println("Unknown cow variant: " + name + ". Available variants: " + 
                    textureDefinition.getCowVariants().keySet());
                return textureDefinition.getCowVariants().get("jersey");
            }
            return variant;
        });
    }
    
    public static CowTextureDefinition.UVCoordinate getUVCoordinate(String variantName, String bodyPart, String face) {
        CowTextureDefinition.CowVariant variant = getCowVariant(variantName);
        if (variant == null) {
            return null;
        }
        
        CowTextureDefinition.BodyPart bodyPartDef = variant.getBodyParts().get(bodyPart);
        if (bodyPartDef == null) {
            System.err.println("Unknown body part: " + bodyPart + " for variant: " + variantName);
            return null;
        }
        
        CowTextureDefinition.UVCoordinate uvCoord = bodyPartDef.getUvMapping().get(face);
        if (uvCoord == null) {
            System.err.println("Unknown face: " + face + " for body part: " + bodyPart);
            return null;
        }
        
        return uvCoord;
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
            System.err.println("Invalid hex color format: " + hexColor);
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
}