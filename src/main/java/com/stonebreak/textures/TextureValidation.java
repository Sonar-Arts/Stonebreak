package com.stonebreak.textures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TextureValidation {
    
    private static final String[] REQUIRED_FACES = {"front", "back", "left", "right", "top", "bottom"};
    private static final String[] REQUIRED_BODY_PARTS = {"head", "body", "legs", "horns", "tail", "udder"};
    
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
        
        if (atlas.getFile() == null || atlas.getFile().trim().isEmpty()) {
            errors.add("Texture atlas file path is required");
        } else if (!atlas.getFile().toLowerCase().endsWith(".png")) {
            warnings.add("Texture atlas file should be a PNG image");
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
        
        // Validate base colors
        if (variant.getBaseColors() == null) {
            warnings.add("Cow variant '" + variantName + "' has no base colors defined");
        } else {
            validateBaseColors(variantName, variant.getBaseColors(), errors, warnings);
        }
        
        // Validate body parts
        if (variant.getBodyParts() == null || variant.getBodyParts().isEmpty()) {
            errors.add("Cow variant '" + variantName + "' has no body parts defined");
        } else {
            validateBodyParts(variantName, variant.getBodyParts(), errors, warnings);
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
    
    private static void validateBodyParts(String variantName, Map<String, CowTextureDefinition.BodyPart> bodyParts,
                                        List<String> errors, List<String> warnings) {
        // Check for required body parts
        for (String requiredPart : REQUIRED_BODY_PARTS) {
            if (!bodyParts.containsKey(requiredPart)) {
                warnings.add("Cow variant '" + variantName + "' is missing body part: " + requiredPart);
            }
        }
        
        // Validate each body part
        for (Map.Entry<String, CowTextureDefinition.BodyPart> entry : bodyParts.entrySet()) {
            validateBodyPart(variantName, entry.getKey(), entry.getValue(), errors, warnings);
        }
    }
    
    private static void validateBodyPart(String variantName, String partName, CowTextureDefinition.BodyPart bodyPart,
                                       List<String> errors, List<String> warnings) {
        if (bodyPart == null) {
            errors.add("Cow variant '" + variantName + "' body part '" + partName + "' is null");
            return;
        }
        
        // Validate UV mapping
        if (bodyPart.getUvMapping() == null || bodyPart.getUvMapping().isEmpty()) {
            errors.add("Cow variant '" + variantName + "' body part '" + partName + "' has no UV mapping");
        } else {
            validateUVMapping(variantName, partName, bodyPart.getUvMapping(), errors, warnings);
        }
        
        // Validate patterns (optional but warn if missing)
        if (bodyPart.getPatterns() == null || bodyPart.getPatterns().isEmpty()) {
            warnings.add("Cow variant '" + variantName + "' body part '" + partName + "' has no patterns defined");
        } else {
            validatePatterns(variantName, partName, bodyPart.getPatterns(), errors, warnings);
        }
    }
    
    private static void validateUVMapping(String variantName, String partName, 
                                        Map<String, CowTextureDefinition.UVCoordinate> uvMapping,
                                        List<String> errors, List<String> warnings) {
        // Check for required faces
        for (String requiredFace : REQUIRED_FACES) {
            if (!uvMapping.containsKey(requiredFace)) {
                warnings.add("Cow variant '" + variantName + "' body part '" + partName + 
                           "' is missing face: " + requiredFace);
            }
        }
        
        // Validate each UV coordinate
        for (Map.Entry<String, CowTextureDefinition.UVCoordinate> entry : uvMapping.entrySet()) {
            validateUVCoordinate(variantName, partName, entry.getKey(), entry.getValue(), errors, warnings);
        }
    }
    
    private static void validateUVCoordinate(String variantName, String partName, String faceName,
                                           CowTextureDefinition.UVCoordinate uvCoord,
                                           List<String> errors, List<String> warnings) {
        if (uvCoord == null) {
            errors.add("Cow variant '" + variantName + "' body part '" + partName + 
                      "' face '" + faceName + "' UV coordinate is null");
            return;
        }
        
        // Validate coordinate values
        if (uvCoord.getU() < 0) {
            errors.add("Cow variant '" + variantName + "' body part '" + partName + 
                      "' face '" + faceName + "' U coordinate cannot be negative");
        }
        
        if (uvCoord.getV() < 0) {
            errors.add("Cow variant '" + variantName + "' body part '" + partName + 
                      "' face '" + faceName + "' V coordinate cannot be negative");
        }
        
        if (uvCoord.getWidth() <= 0) {
            errors.add("Cow variant '" + variantName + "' body part '" + partName + 
                      "' face '" + faceName + "' width must be positive");
        }
        
        if (uvCoord.getHeight() <= 0) {
            errors.add("Cow variant '" + variantName + "' body part '" + partName + 
                      "' face '" + faceName + "' height must be positive");
        }
    }
    
    private static void validatePatterns(String variantName, String partName, 
                                       List<CowTextureDefinition.Pattern> patterns,
                                       List<String> errors, List<String> warnings) {
        for (int i = 0; i < patterns.size(); i++) {
            CowTextureDefinition.Pattern pattern = patterns.get(i);
            if (pattern == null) {
                errors.add("Cow variant '" + variantName + "' body part '" + partName + 
                          "' pattern " + i + " is null");
                continue;
            }
            
            validatePattern(variantName, partName, i, pattern, errors, warnings);
        }
    }
    
    private static void validatePattern(String variantName, String partName, int patternIndex,
                                      CowTextureDefinition.Pattern pattern,
                                      List<String> errors, List<String> warnings) {
        String context = "Cow variant '" + variantName + "' body part '" + partName + "' pattern " + patternIndex;
        
        // Validate pattern type
        if (pattern.getType() == null || pattern.getType().trim().isEmpty()) {
            errors.add(context + " has no type defined");
        }
        
        // Validate density
        if (pattern.getDensity() < 0 || pattern.getDensity() > 1) {
            warnings.add(context + " density should be between 0 and 1");
        }
        
        // Validate size
        if (pattern.getSize() <= 0) {
            errors.add(context + " size must be positive");
        }
        
        // Validate color
        if (pattern.getColor() != null && !pattern.getColor().matches("#[0-9A-Fa-f]{6}")) {
            errors.add(context + " color '" + pattern.getColor() + 
                      "' is not a valid hex color (format: #RRGGBB)");
        }
    }
    
    private static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }
}