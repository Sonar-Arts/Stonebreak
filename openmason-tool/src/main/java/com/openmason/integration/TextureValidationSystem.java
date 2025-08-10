package com.openmason.integration;

import com.stonebreak.textures.CowTextureDefinition;
import com.openmason.ui.viewport.OpenMason3DViewport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Automatic texture validation system that ensures textures meet Stonebreak game requirements.
 * Provides real-time validation with visual feedback in the viewport.
 */
public class TextureValidationSystem {
    private static final Logger logger = LoggerFactory.getLogger(TextureValidationSystem.class);
    
    private final OpenMason3DViewport viewport;
    
    // Validation rules and constraints
    private static final int MIN_ATLAS_COORDINATE = 0;
    private static final int MAX_ATLAS_COORDINATE = 15;
    private static final Pattern COLOR_HEX_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Set<String> REQUIRED_FACE_MAPPINGS = Set.of(
        "HEAD_FRONT", "HEAD_BACK", "HEAD_LEFT", "HEAD_RIGHT", "HEAD_TOP", "HEAD_BOTTOM",
        "BODY_FRONT", "BODY_BACK", "BODY_LEFT", "BODY_RIGHT", "BODY_TOP", "BODY_BOTTOM",
        "LEG_FRONT_LEFT_FRONT", "LEG_FRONT_LEFT_BACK", "LEG_FRONT_LEFT_LEFT", "LEG_FRONT_LEFT_RIGHT", 
        "LEG_FRONT_LEFT_TOP", "LEG_FRONT_LEFT_BOTTOM",
        "LEG_FRONT_RIGHT_FRONT", "LEG_FRONT_RIGHT_BACK", "LEG_FRONT_RIGHT_LEFT", "LEG_FRONT_RIGHT_RIGHT", 
        "LEG_FRONT_RIGHT_TOP", "LEG_FRONT_RIGHT_BOTTOM",
        "LEG_BACK_LEFT_FRONT", "LEG_BACK_LEFT_BACK", "LEG_BACK_LEFT_LEFT", "LEG_BACK_LEFT_RIGHT", 
        "LEG_BACK_LEFT_TOP", "LEG_BACK_LEFT_BOTTOM",
        "LEG_BACK_RIGHT_FRONT", "LEG_BACK_RIGHT_BACK", "LEG_BACK_RIGHT_LEFT", "LEG_BACK_RIGHT_RIGHT", 
        "LEG_BACK_RIGHT_TOP", "LEG_BACK_RIGHT_BOTTOM"
    );
    
    // Validation results cache
    private final Map<String, ValidationResult> validationCache = new ConcurrentHashMap<>();
    
    // Validation listeners
    private final List<ValidationListener> validationListeners = new ArrayList<>();
    
    public TextureValidationSystem(OpenMason3DViewport viewport) {
        this.viewport = viewport;
    }
    
    /**
     * Validates a texture definition against Stonebreak game requirements.
     */
    public CompletableFuture<ValidationResult> validateTextureDefinition(String variantName, CowTextureDefinition.CowVariant variant) {
        return CompletableFuture.supplyAsync(() -> {
            logger.debug("Validating texture definition: {}", variantName);
            
            ValidationResult result = new ValidationResult(variantName);
            
            try {
                // Validate face mappings
                validateFaceMappings(variant, result);
                
                // Validate atlas coordinates
                validateAtlasCoordinates(variant, result);
                
                // Validate base colors
                validateBaseColors(variant, result);
                
                // Validate drawing instructions
                validateDrawingInstructions(variant, result);
                
                // Cache result
                validationCache.put(variantName, result);
                
                // Notify listeners
                notifyValidationListeners(result);
                
                // Update viewport with validation feedback
                updateViewportValidation(result);
                
                logger.info("Validation completed for {}: {} errors, {} warnings", 
                    variantName, result.getErrorCount(), result.getWarningCount());
                
            } catch (Exception e) {
                result.addError("VALIDATION_EXCEPTION", "Validation failed due to exception: " + e.getMessage());
                logger.error("Validation exception for {}", variantName, e);
            }
            
            return result;
        });
    }
    
    /**
     * Validates that all required face mappings are present.
     */
    private void validateFaceMappings(CowTextureDefinition.CowVariant variant, ValidationResult result) {
        Map<String, CowTextureDefinition.AtlasCoordinate> faceMappings = variant.getFaceMappings();
        
        if (faceMappings == null || faceMappings.isEmpty()) {
            result.addError("MISSING_FACE_MAPPINGS", "No face mappings defined");
            return;
        }
        
        // Check for required face mappings
        for (String requiredFace : REQUIRED_FACE_MAPPINGS) {
            if (!faceMappings.containsKey(requiredFace)) {
                result.addError("MISSING_FACE_MAPPING", "Missing required face mapping: " + requiredFace);
            }
        }
        
        // Check for unknown face mappings
        for (String faceName : faceMappings.keySet()) {
            if (!REQUIRED_FACE_MAPPINGS.contains(faceName)) {
                result.addWarning("UNKNOWN_FACE_MAPPING", "Unknown face mapping: " + faceName);
            }
        }
        
        result.addInfo("FACE_MAPPINGS_CHECK", "Face mappings validation completed: " + faceMappings.size() + " mappings found");
    }
    
    /**
     * Validates atlas coordinates are within valid bounds.
     */
    private void validateAtlasCoordinates(CowTextureDefinition.CowVariant variant, ValidationResult result) {
        Map<String, CowTextureDefinition.AtlasCoordinate> faceMappings = variant.getFaceMappings();
        
        if (faceMappings == null) {
            return;
        }
        
        for (Map.Entry<String, CowTextureDefinition.AtlasCoordinate> entry : faceMappings.entrySet()) {
            String faceName = entry.getKey();
            CowTextureDefinition.AtlasCoordinate coord = entry.getValue();
            
            if (coord == null) {
                result.addError("NULL_COORDINATE", "Null coordinate for face: " + faceName);
                continue;
            }
            
            // Validate atlas X coordinate
            if (coord.getAtlasX() < MIN_ATLAS_COORDINATE || coord.getAtlasX() > MAX_ATLAS_COORDINATE) {
                result.addError("INVALID_ATLAS_X", 
                    String.format("Invalid atlas X coordinate for %s: %d (must be %d-%d)", 
                        faceName, coord.getAtlasX(), MIN_ATLAS_COORDINATE, MAX_ATLAS_COORDINATE));
            }
            
            // Validate atlas Y coordinate
            if (coord.getAtlasY() < MIN_ATLAS_COORDINATE || coord.getAtlasY() > MAX_ATLAS_COORDINATE) {
                result.addError("INVALID_ATLAS_Y", 
                    String.format("Invalid atlas Y coordinate for %s: %d (must be %d-%d)", 
                        faceName, coord.getAtlasY(), MIN_ATLAS_COORDINATE, MAX_ATLAS_COORDINATE));
            }
        }
        
        result.addInfo("ATLAS_COORDINATES_CHECK", "Atlas coordinates validation completed");
    }
    
    /**
     * Validates base colors are in valid hex format.
     */
    private void validateBaseColors(CowTextureDefinition.CowVariant variant, ValidationResult result) {
        CowTextureDefinition.BaseColors baseColors = variant.getBaseColors();
        
        if (baseColors == null) {
            result.addWarning("MISSING_BASE_COLORS", "No base colors defined");
            return;
        }
        
        // Create a map-like structure for validation
        Map<String, String> colorMap = new HashMap<>();
        if (baseColors.getPrimary() != null) colorMap.put("primary", baseColors.getPrimary());
        if (baseColors.getSecondary() != null) colorMap.put("secondary", baseColors.getSecondary());
        if (baseColors.getAccent() != null) colorMap.put("accent", baseColors.getAccent());
        
        if (colorMap.isEmpty()) {
            result.addWarning("MISSING_BASE_COLORS", "No base colors defined");
            return;
        }
        
        for (Map.Entry<String, String> entry : colorMap.entrySet()) {
            String colorName = entry.getKey();
            String colorValue = entry.getValue();
            
            if (colorValue == null || colorValue.trim().isEmpty()) {
                result.addError("EMPTY_COLOR_VALUE", "Empty color value for: " + colorName);
                continue;
            }
            
            if (!COLOR_HEX_PATTERN.matcher(colorValue.trim()).matches()) {
                result.addError("INVALID_COLOR_FORMAT", 
                    String.format("Invalid color format for %s: %s (expected #RRGGBB)", colorName, colorValue));
            }
        }
        
        result.addInfo("BASE_COLORS_CHECK", "Base colors validation completed: " + colorMap.size() + " colors found");
    }
    
    /**
     * Validates drawing instructions structure and content.
     */
    private void validateDrawingInstructions(CowTextureDefinition.CowVariant variant, ValidationResult result) {
        Map<String, CowTextureDefinition.DrawingInstructions> drawingInstructions = variant.getDrawingInstructions();
        
        if (drawingInstructions == null || drawingInstructions.isEmpty()) {
            result.addWarning("MISSING_DRAWING_INSTRUCTIONS", "No drawing instructions defined");
            return;
        }
        
        // Check for drawing instructions
        boolean hasFacialFeatures = false;
        for (CowTextureDefinition.DrawingInstructions instructions : drawingInstructions.values()) {
            if (instructions.getFacialFeatures() != null) {
                hasFacialFeatures = true;
                validateFacialFeatures(instructions.getFacialFeatures(), result);
            }
        }
        
        if (!hasFacialFeatures) {
            result.addWarning("MISSING_FACIAL_FEATURES", "No facial features defined in drawing instructions");
        }
        
        result.addInfo("DRAWING_INSTRUCTIONS_CHECK", "Drawing instructions validation completed");
    }
    
    /**
     * Validates facial features structure.
     */
    private void validateFacialFeatures(CowTextureDefinition.FacialFeatures facialFeatures, ValidationResult result) {
        if (facialFeatures == null) {
            result.addError("NULL_FACIAL_FEATURES", "Facial features is null");
            return;
        }
        
        // Check for required facial feature sections
        if (facialFeatures.getEyes() == null) {
            result.addWarning("MISSING_FACIAL_FEATURE", "Missing facial feature section: eyes");
        }
        if (facialFeatures.getNose() == null) {
            result.addWarning("MISSING_FACIAL_FEATURE", "Missing facial feature section: nose");
        }
        if (facialFeatures.getMouth() == null) {
            result.addWarning("MISSING_FACIAL_FEATURE", "Missing facial feature section: mouth");
        }
    }
    
    /**
     * Validates body patterns structure.
     */
    private void validateBodyPatterns(List<CowTextureDefinition.Pattern> patterns, ValidationResult result) {
        if (patterns == null || patterns.isEmpty()) {
            result.addInfo("NO_BODY_PATTERNS", "No standard body patterns defined (this may be intentional)");
            return;
        }
        
        // Check for common body pattern types
        String[] commonPatterns = {"spots", "stripes", "patches"};
        boolean hasPatterns = false;
        
        for (CowTextureDefinition.Pattern pattern : patterns) {
            if (pattern.getType() != null) {
                for (String commonPattern : commonPatterns) {
                    if (pattern.getType().toLowerCase().contains(commonPattern)) {
                        hasPatterns = true;
                        break;
                    }
                }
            }
        }
        
        if (!hasPatterns) {
            result.addInfo("NO_STANDARD_PATTERNS", "No standard body patterns found, but custom patterns are defined");
        }
    }
    
    /**
     * Simple color representation for validation feedback.
     */
    public static class SimpleColor {
        private final int r, g, b;
        
        public SimpleColor(int r, int g, int b) {
            this.r = Math.max(0, Math.min(255, r));
            this.g = Math.max(0, Math.min(255, g));
            this.b = Math.max(0, Math.min(255, b));
        }
        
        public int getR() { return r; }
        public int getG() { return g; }
        public int getB() { return b; }
        
        public static final SimpleColor RED = new SimpleColor(255, 0, 0);
        public static final SimpleColor ORANGE = new SimpleColor(255, 165, 0);
        public static final SimpleColor GREEN = new SimpleColor(0, 128, 0);
    }
    
    /**
     * Updates the viewport with validation feedback.
     */
    private void updateViewportValidation(ValidationResult result) {
        try {
            // Set validation indicator color based on result
            SimpleColor indicatorColor;
            if (result.hasErrors()) {
                indicatorColor = SimpleColor.RED;
            } else if (result.hasWarnings()) {
                indicatorColor = SimpleColor.ORANGE;
            } else {
                indicatorColor = SimpleColor.GREEN;
            }
            
            // Update viewport validation indicator (method not implemented yet)
            // viewport.setValidationStatus(result.getVariantName(), indicatorColor, result.getSummary());
            logger.debug("Validation status for {}: {}", result.getVariantName(), result.getSummary());
            
        } catch (Exception e) {
            logger.error("Failed to update viewport validation", e);
        }
    }
    
    /**
     * Notifies all validation listeners of the result.
     */
    private void notifyValidationListeners(ValidationResult result) {
        for (ValidationListener listener : validationListeners) {
            try {
                listener.onValidationComplete(result);
            } catch (Exception e) {
                logger.error("Error notifying validation listener", e);
            }
        }
    }
    
    /**
     * Gets cached validation result for a variant.
     */
    public ValidationResult getCachedValidation(String variantName) {
        return validationCache.get(variantName);
    }
    
    /**
     * Clears validation cache.
     */
    public void clearValidationCache() {
        validationCache.clear();
        logger.info("Validation cache cleared");
    }
    
    /**
     * Adds a validation listener.
     */
    public void addValidationListener(ValidationListener listener) {
        validationListeners.add(listener);
    }
    
    /**
     * Removes a validation listener.
     */
    public void removeValidationListener(ValidationListener listener) {
        validationListeners.remove(listener);
    }
    
    /**
     * Validation result container.
     */
    public static class ValidationResult {
        private final String variantName;
        private final List<ValidationIssue> errors = new ArrayList<>();
        private final List<ValidationIssue> warnings = new ArrayList<>();
        private final List<ValidationIssue> info = new ArrayList<>();
        private final long timestamp = System.currentTimeMillis();
        
        public ValidationResult(String variantName) {
            this.variantName = variantName;
        }
        
        public void addError(String code, String message) {
            errors.add(new ValidationIssue(code, message, ValidationLevel.ERROR));
        }
        
        public void addWarning(String code, String message) {
            warnings.add(new ValidationIssue(code, message, ValidationLevel.WARNING));
        }
        
        public void addInfo(String code, String message) {
            info.add(new ValidationIssue(code, message, ValidationLevel.INFO));
        }
        
        public String getVariantName() { return variantName; }
        public List<ValidationIssue> getErrors() { return new ArrayList<>(errors); }
        public List<ValidationIssue> getWarnings() { return new ArrayList<>(warnings); }
        public List<ValidationIssue> getInfo() { return new ArrayList<>(info); }
        
        public int getErrorCount() { return errors.size(); }
        public int getWarningCount() { return warnings.size(); }
        public int getInfoCount() { return info.size(); }
        
        public boolean hasErrors() { return !errors.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
        public boolean isValid() { return errors.isEmpty(); }
        
        public long getTimestamp() { return timestamp; }
        
        public String getSummary() {
            if (hasErrors()) {
                return String.format("❌ %d errors, %d warnings", getErrorCount(), getWarningCount());
            } else if (hasWarnings()) {
                return String.format("⚠️ %d warnings", getWarningCount());
            } else {
                return "✅ Valid";
            }
        }
    }
    
    /**
     * Individual validation issue.
     */
    public static class ValidationIssue {
        private final String code;
        private final String message;
        private final ValidationLevel level;
        private final long timestamp = System.currentTimeMillis();
        
        public ValidationIssue(String code, String message, ValidationLevel level) {
            this.code = code;
            this.message = message;
            this.level = level;
        }
        
        public String getCode() { return code; }
        public String getMessage() { return message; }
        public ValidationLevel getLevel() { return level; }
        public long getTimestamp() { return timestamp; }
    }
    
    /**
     * Validation severity levels.
     */
    public enum ValidationLevel {
        ERROR, WARNING, INFO
    }
    
    /**
     * Interface for validation listeners.
     */
    public interface ValidationListener {
        void onValidationComplete(ValidationResult result);
    }
}