package com.stonebreak.textures.validation;

import com.stonebreak.textures.mobs.CowTextureDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TextureValidation {
    
    private static final String[] REQUIRED_FACES = {
        "HEAD_FRONT", "HEAD_BACK", "HEAD_LEFT", "HEAD_RIGHT", "HEAD_TOP", "HEAD_BOTTOM",
        "BODY_FRONT", "BODY_BACK", "BODY_LEFT", "BODY_RIGHT", "BODY_TOP", "BODY_BOTTOM"
    };
    
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;
        
        public ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = errors;
            this.warnings = warnings;
        }
        
        public boolean isValid() { return valid; }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
        
        public void printResults() {
            if (valid) {
                System.out.println("✓ Texture definition validation passed");
            } else {
                System.out.println("✗ Texture definition validation failed");
            }
            
            if (!errors.isEmpty()) {
                System.out.println("Errors:");
                for (String error : errors) {
                    System.out.println("  - " + error);
                }
            }
            
            if (!warnings.isEmpty()) {
                System.out.println("Warnings:");
                for (String warning : warnings) {
                    System.out.println("  - " + warning);
                }
            }
        }
    }
    
    public static ValidationResult validateTextureDefinition(CowTextureDefinition definition) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        if (definition == null) {
            errors.add("Texture definition is null");
            return new ValidationResult(false, errors, warnings);
        }
        
        // Validate texture atlas
        if (definition.getTextureAtlas() == null) {
            errors.add("Texture atlas definition is missing");
        } else {
            validateTextureAtlas(definition.getTextureAtlas(), errors, warnings);
        }
        
        // Validate cow variants
        if (definition.getCowVariants() == null || definition.getCowVariants().isEmpty()) {
            errors.add("No cow variants defined");
        } else {
            for (Map.Entry<String, CowTextureDefinition.CowVariant> entry : definition.getCowVariants().entrySet()) {
                validateCowVariant(entry.getKey(), entry.getValue(), errors, warnings);
            }
        }
        
        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }
    
    private static void validateTextureAtlas(CowTextureDefinition.TextureAtlas atlas, 
                                           List<String> errors, List<String> warnings) {
        if (atlas.getWidth() <= 0) {
            errors.add("Texture atlas width must be positive");
        }
        
        if (atlas.getHeight() <= 0) {
            errors.add("Texture atlas height must be positive");
        }
        
        if (atlas.getGridSize() <= 0) {
            errors.add("Texture atlas grid size must be positive");
        }
        
        if (atlas.getFile() != null && !atlas.getFile().trim().isEmpty()) {
            if (!atlas.getFile().toLowerCase().endsWith(".png")) {
                warnings.add("Texture atlas file should be a PNG image");
            }
        }
        
        // Check for power-of-two dimensions (common GPU optimization)
        if (!isPowerOfTwo(atlas.getWidth()) || !isPowerOfTwo(atlas.getHeight())) {
            warnings.add("Texture atlas dimensions should be powers of 2 for optimal GPU performance");
        }
    }
    
    private static void validateCowVariant(String variantName, CowTextureDefinition.CowVariant variant,
                                         List<String> errors, List<String> warnings) {
        if (variant == null) {
            errors.add("Cow variant '" + variantName + "' is null");
            return;
        }
        
        // Validate display name
        if (variant.getDisplayName() == null || variant.getDisplayName().trim().isEmpty()) {
            warnings.add("Cow variant '" + variantName + "' has no display name defined");
        }
        
        // Validate base colors
        if (variant.getBaseColors() == null) {
            warnings.add("Cow variant '" + variantName + "' has no base colors defined");
        } else {
            validateBaseColors(variantName, variant.getBaseColors(), errors, warnings);
        }
        
        // Validate face mappings
        if (variant.getFaceMappings() == null || variant.getFaceMappings().isEmpty()) {
            errors.add("Cow variant '" + variantName + "' has no face mappings defined");
        } else {
            validateFaceMappings(variantName, variant.getFaceMappings(), errors, warnings);
        }
    }
    
    private static void validateBaseColors(String variantName, CowTextureDefinition.BaseColors colors,
                                         List<String> errors, List<String> warnings) {
        validateColor("primary", variantName, colors.getPrimary(), errors, warnings);
        validateColor("secondary", variantName, colors.getSecondary(), errors, warnings);
        validateColor("accent", variantName, colors.getAccent(), errors, warnings);
    }
    
    private static void validateColor(String colorType, String variantName, String color,
                                    List<String> errors, List<String> warnings) {
        if (color == null || color.trim().isEmpty()) {
            warnings.add("Cow variant '" + variantName + "' has no " + colorType + " color defined");
            return;
        }
        
        if (!color.matches("#[0-9A-Fa-f]{6}")) {
            errors.add("Cow variant '" + variantName + "' " + colorType + " color '" + color + 
                      "' is not a valid hex color (format: #RRGGBB)");
        }
    }
    
    private static void validateFaceMappings(String variantName, Map<String, CowTextureDefinition.AtlasCoordinate> faceMappings,
                                           List<String> errors, List<String> warnings) {
        // Check for required faces
        for (String requiredFace : REQUIRED_FACES) {
            if (!faceMappings.containsKey(requiredFace)) {
                warnings.add("Cow variant '" + variantName + "' is missing required face: " + requiredFace);
            }
        }
        
        // Validate each face mapping
        for (Map.Entry<String, CowTextureDefinition.AtlasCoordinate> entry : faceMappings.entrySet()) {
            validateAtlasCoordinate(variantName, entry.getKey(), entry.getValue(), errors, warnings);
        }
    }
    
    private static void validateAtlasCoordinate(String variantName, String faceName,
                                              CowTextureDefinition.AtlasCoordinate coordinate,
                                              List<String> errors, List<String> warnings) {
        if (coordinate == null) {
            errors.add("Cow variant '" + variantName + "' face '" + faceName + "' atlas coordinate is null");
            return;
        }
        
        // Validate coordinate values (should be within atlas grid bounds)
        if (coordinate.getAtlasX() < 0) {
            errors.add("Cow variant '" + variantName + "' face '" + faceName + "' atlasX coordinate cannot be negative");
        }
        
        if (coordinate.getAtlasY() < 0) {
            errors.add("Cow variant '" + variantName + "' face '" + faceName + "' atlasY coordinate cannot be negative");
        }
        
        // Check for reasonable bounds (assuming 16x16 grid is max)
        if (coordinate.getAtlasX() >= 16) {
            warnings.add("Cow variant '" + variantName + "' face '" + faceName + 
                        "' atlasX coordinate (" + coordinate.getAtlasX() + ") is unusually high");
        }
        
        if (coordinate.getAtlasY() >= 16) {
            warnings.add("Cow variant '" + variantName + "' face '" + faceName + 
                        "' atlasY coordinate (" + coordinate.getAtlasY() + ") is unusually high");
        }
    }
    
    private static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }
}