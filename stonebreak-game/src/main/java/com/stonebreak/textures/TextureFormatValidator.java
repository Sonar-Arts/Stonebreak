package com.stonebreak.textures;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates texture formats and dimensions according to Stonebreak standards.
 * Ensures all textures meet the required specifications for proper atlas generation.
 */
public class TextureFormatValidator {
    
    // Standard texture dimensions
    public static final int ITEM_TEXTURE_SIZE = 16;           // 16x16 for items
    public static final int BLOCK_UNIFORM_SIZE = 16;          // 16x16 for uniform blocks
    public static final int BLOCK_CUBE_CROSS_WIDTH = 16;      // 16 pixels wide
    public static final int BLOCK_CUBE_CROSS_HEIGHT = 96;     // 96 pixels tall (6 faces * 16)
    public static final int ERROCKSON_SIZE = 16;              // 16x16 for error texture
    
    /**
     * Validation result containing detailed information about texture compliance.
     */
    public static class ValidationResult {
        public final boolean isValid;
        public final String fileName;
        public final List<String> errors;
        public final List<String> warnings;
        public final TextureResourceLoader.TextureType detectedType;
        public final int actualWidth;
        public final int actualHeight;
        
        public ValidationResult(String fileName, boolean isValid, TextureResourceLoader.TextureType detectedType, 
                               int actualWidth, int actualHeight) {
            this.fileName = fileName;
            this.isValid = isValid;
            this.detectedType = detectedType;
            this.actualWidth = actualWidth;
            this.actualHeight = actualHeight;
            this.errors = new ArrayList<>();
            this.warnings = new ArrayList<>();
        }
        
        public void addError(String error) {
            this.errors.add(error);
        }
        
        public void addWarning(String warning) {
            this.warnings.add(warning);
        }
        
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
        
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ValidationResult for ").append(fileName).append(":\n");
            sb.append("  Valid: ").append(isValid).append("\n");
            sb.append("  Type: ").append(detectedType).append("\n");
            sb.append("  Dimensions: ").append(actualWidth).append("x").append(actualHeight).append("\n");
            
            if (hasErrors()) {
                sb.append("  Errors:\n");
                for (String error : errors) {
                    sb.append("    - ").append(error).append("\n");
                }
            }
            
            if (hasWarnings()) {
                sb.append("  Warnings:\n");
                for (String warning : warnings) {
                    sb.append("    - ").append(warning).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
    
    /**
     * Validates an item texture.
     * @param image The loaded texture image
     * @param fileName The texture filename
     * @return ValidationResult with detailed compliance information
     */
    public static ValidationResult validateItemTexture(BufferedImage image, String fileName) {
        if (image == null) {
            ValidationResult result = new ValidationResult(fileName, false, TextureResourceLoader.TextureType.ITEM, 0, 0);
            result.addError("Image is null");
            return result;
        }
        
        ValidationResult result = new ValidationResult(fileName, true, TextureResourceLoader.TextureType.ITEM, 
                                                      image.getWidth(), image.getHeight());
        
        // Check dimensions
        if (image.getWidth() != ITEM_TEXTURE_SIZE) {
            result.addError("Invalid width: " + image.getWidth() + ", expected: " + ITEM_TEXTURE_SIZE);
        }
        
        if (image.getHeight() != ITEM_TEXTURE_SIZE) {
            result.addError("Invalid height: " + image.getHeight() + ", expected: " + ITEM_TEXTURE_SIZE);
        }
        
        // Validate image format
        validateImageFormat(image, fileName, result);
        
        return new ValidationResult(fileName, !result.hasErrors(), result.detectedType, 
                                  result.actualWidth, result.actualHeight) {{
            errors.addAll(result.errors);
            warnings.addAll(result.warnings);
        }};
    }
    
    /**
     * Validates a block texture, automatically detecting uniform vs cube cross format.
     * @param image The loaded texture image
     * @param fileName The texture filename
     * @return ValidationResult with detailed compliance information
     */
    public static ValidationResult validateBlockTexture(BufferedImage image, String fileName) {
        if (image == null) {
            ValidationResult result = new ValidationResult(fileName, false, TextureResourceLoader.TextureType.BLOCK_UNIFORM, 0, 0);
            result.addError("Image is null");
            return result;
        }
        
        // Detect texture type based on dimensions
        TextureResourceLoader.TextureType detectedType;
        if (image.getWidth() == BLOCK_UNIFORM_SIZE && image.getHeight() == BLOCK_UNIFORM_SIZE) {
            detectedType = TextureResourceLoader.TextureType.BLOCK_UNIFORM;
        } else if (image.getWidth() == BLOCK_CUBE_CROSS_WIDTH && image.getHeight() == BLOCK_CUBE_CROSS_HEIGHT) {
            detectedType = TextureResourceLoader.TextureType.BLOCK_CUBE_CROSS;
        } else {
            detectedType = TextureResourceLoader.TextureType.BLOCK_UNIFORM; // Default assumption
        }
        
        ValidationResult result = new ValidationResult(fileName, true, detectedType, 
                                                      image.getWidth(), image.getHeight());
        
        // Validate based on detected type
        if (detectedType == TextureResourceLoader.TextureType.BLOCK_UNIFORM) {
            if (image.getWidth() != BLOCK_UNIFORM_SIZE) {
                result.addError("Invalid width for uniform block: " + image.getWidth() + ", expected: " + BLOCK_UNIFORM_SIZE);
            }
            if (image.getHeight() != BLOCK_UNIFORM_SIZE) {
                result.addError("Invalid height for uniform block: " + image.getHeight() + ", expected: " + BLOCK_UNIFORM_SIZE);
            }
        } else if (detectedType == TextureResourceLoader.TextureType.BLOCK_CUBE_CROSS) {
            if (image.getWidth() != BLOCK_CUBE_CROSS_WIDTH) {
                result.addError("Invalid width for cube cross block: " + image.getWidth() + ", expected: " + BLOCK_CUBE_CROSS_WIDTH);
            }
            if (image.getHeight() != BLOCK_CUBE_CROSS_HEIGHT) {
                result.addError("Invalid height for cube cross block: " + image.getHeight() + ", expected: " + BLOCK_CUBE_CROSS_HEIGHT);
            }
            
            // Additional validation for cube cross format
            validateCubeCrossLayout(image, fileName, result);
        }
        
        // Validate image format
        validateImageFormat(image, fileName, result);
        
        return new ValidationResult(fileName, !result.hasErrors(), result.detectedType, 
                                  result.actualWidth, result.actualHeight) {{
            errors.addAll(result.errors);
            warnings.addAll(result.warnings);
        }};
    }
    
    /**
     * Validates Errockson.gif error texture.
     * @param image The loaded texture image
     * @param fileName The texture filename
     * @return ValidationResult with detailed compliance information
     */
    public static ValidationResult validateErrocksonTexture(BufferedImage image, String fileName) {
        if (image == null) {
            ValidationResult result = new ValidationResult(fileName, false, TextureResourceLoader.TextureType.ERROR, 0, 0);
            result.addError("Errockson.gif image is null");
            return result;
        }
        
        ValidationResult result = new ValidationResult(fileName, true, TextureResourceLoader.TextureType.ERROR, 
                                                      image.getWidth(), image.getHeight());
        
        // Check dimensions
        if (image.getWidth() != ERROCKSON_SIZE) {
            result.addError("Invalid Errockson width: " + image.getWidth() + ", expected: " + ERROCKSON_SIZE);
        }
        
        if (image.getHeight() != ERROCKSON_SIZE) {
            result.addError("Invalid Errockson height: " + image.getHeight() + ", expected: " + ERROCKSON_SIZE);
        }
        
        // Special validation for GIF format
        result.addWarning("GIF format detected - ensure this is the intended error texture");
        
        return new ValidationResult(fileName, !result.hasErrors(), result.detectedType, 
                                  result.actualWidth, result.actualHeight) {{
            errors.addAll(result.errors);
            warnings.addAll(result.warnings);
        }};
    }
    
    /**
     * Validates general image format properties.
     * @param image The image to validate
     * @param fileName The filename for error reporting
     * @param result The validation result to add errors/warnings to
     */
    private static void validateImageFormat(BufferedImage image, String fileName, ValidationResult result) {
        // Check color model
        if (image.getColorModel() == null) {
            result.addError("Image has no color model");
            return;
        }
        
        // Check for transparency support
        if (image.getColorModel().hasAlpha()) {
            result.addWarning("Image has alpha channel - ensure proper transparency handling");
        }
        
        // Check pixel format
        int imageType = image.getType();
        if (imageType == BufferedImage.TYPE_CUSTOM) {
            result.addWarning("Custom image type detected - may require format conversion");
        }
        
        // Validate image is not empty
        try {
            // Try to read a pixel to ensure image data is accessible
            image.getRGB(0, 0);
        } catch (Exception e) {
            result.addError("Image data is not accessible: " + e.getMessage());
        }
    }
    
    /**
     * Validates cube cross layout for 16x96 block textures.
     * Ensures the 6 faces are properly arranged vertically.
     * @param image The cube cross image
     * @param fileName The filename for error reporting
     * @param result The validation result to add errors/warnings to
     */
    private static void validateCubeCrossLayout(BufferedImage image, String fileName, ValidationResult result) {
        int faceSize = BLOCK_UNIFORM_SIZE;
        int expectedFaces = 6;
        
        if (image.getHeight() != faceSize * expectedFaces) {
            result.addError("Cube cross height mismatch: " + image.getHeight() + 
                          ", expected: " + (faceSize * expectedFaces) + " (6 faces * 16px)");
            return;
        }
        
        // Check that each face region is accessible
        try {
            for (int face = 0; face < expectedFaces; face++) {
                int faceY = face * faceSize;
                // Sample a few pixels from each face to ensure they're readable
                image.getRGB(0, faceY);
                image.getRGB(faceSize - 1, faceY + faceSize - 1);
            }
        } catch (Exception e) {
            result.addError("Cannot access cube cross face data: " + e.getMessage());
        }
        
        // Additional validation could include:
        // - Check for consistent face content
        // - Validate face ordering (TOP, BOTTOM, NORTH, SOUTH, EAST, WEST)
        // - Ensure faces are not duplicated
    }
    
    /**
     * Validates all textures in a list and returns comprehensive results.
     * @param textures List of loaded textures to validate
     * @return List of validation results
     */
    public static List<ValidationResult> validateTextures(List<TextureResourceLoader.LoadedTexture> textures) {
        List<ValidationResult> results = new ArrayList<>();
        
        for (TextureResourceLoader.LoadedTexture texture : textures) {
            ValidationResult result;
            
            switch (texture.type) {
                case ITEM:
                    result = validateItemTexture(texture.image, texture.fileName);
                    break;
                case BLOCK_UNIFORM:
                case BLOCK_CUBE_CROSS:
                    result = validateBlockTexture(texture.image, texture.fileName);
                    break;
                case ERROR:
                    result = validateErrocksonTexture(texture.image, texture.fileName);
                    break;
                default:
                    result = new ValidationResult(texture.fileName, false, texture.type, 
                                                texture.width, texture.height);
                    result.addError("Unknown texture type: " + texture.type);
            }
            
            results.add(result);
        }
        
        return results;
    }
    
    /**
     * Prints a summary of validation results.
     * @param results List of validation results
     */
    public static void printValidationSummary(List<ValidationResult> results) {
        int validCount = 0;
        int errorCount = 0;
        int warningCount = 0;
        
        for (ValidationResult result : results) {
            if (result.isValid) {
                validCount++;
            } else {
                errorCount++;
            }
            if (result.hasWarnings()) {
                warningCount++;
            }
        }
        
        System.out.println("=== Texture Validation Summary ===");
        System.out.println("Total textures: " + results.size());
        System.out.println("Valid textures: " + validCount);
        System.out.println("Invalid textures: " + errorCount);
        System.out.println("Textures with warnings: " + warningCount);
        System.out.println("==================================");
        
        // Print detailed results for invalid textures
        for (ValidationResult result : results) {
            if (!result.isValid || result.hasErrors()) {
                System.out.println(result);
            }
        }
    }
}