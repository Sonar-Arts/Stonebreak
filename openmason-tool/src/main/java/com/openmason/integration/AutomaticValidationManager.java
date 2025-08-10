package com.openmason.integration;

import com.openmason.ui.viewport.OpenMason3DViewport;
import com.openmason.texture.TextureManager;
import com.stonebreak.textures.CowTextureDefinition;
import com.stonebreak.textures.CowTextureLoader;

// JavaFX imports removed - using standard Java properties

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Phase 8 Deep Stonebreak Integration - Automatic Validation Manager
 * 
 * Provides real-time validation against Stonebreak game requirements with visual
 * indicators for validation errors directly in the viewport. Performs automatic
 * coordinate bounds checking, color space validation, and format validation.
 * 
 * Key Features:
 * - Real-time validation against Stonebreak game requirements
 * - Visual indicators for validation errors in viewport
 * - Automatic coordinate bounds checking (0-15 atlas range)
 * - Color space and format validation (hex colors)
 * - Face mapping completeness validation
 * - Drawing instruction consistency checking
 * - Performance-optimized validation with caching
 * - Comprehensive error reporting with suggestions
 */
public class AutomaticValidationManager {
    
    private static final Logger logger = LoggerFactory.getLogger(AutomaticValidationManager.class);
    
    // Core validation settings
    private final AtomicBoolean validationEnabled = new AtomicBoolean(true);
    private final AtomicBoolean realTimeValidation = new AtomicBoolean(true);
    private final AtomicBoolean visualErrorsEnabled = new AtomicBoolean(true);
    private final AtomicBoolean strictValidation = new AtomicBoolean(false);
    
    // Validation statistics
    private final AtomicInteger totalValidations = new AtomicInteger(0);
    private final AtomicInteger passedValidations = new AtomicInteger(0);
    private final AtomicInteger failedValidations = new AtomicInteger(0);
    private final AtomicInteger warningValidations = new AtomicInteger(0);
    
    // Integration components
    private final OpenMason3DViewport viewport;
    private final Map<String, ValidationResult> validationCache = new ConcurrentHashMap<>();
    private final AtomicReference<String> currentValidationTarget = new AtomicReference<>("");
    
    // Real-time validation scheduling
    private final ScheduledExecutorService validationScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ValidationManager-Scheduler");
        t.setDaemon(true);
        return t;
    });
    
    // Validation listeners
    private final List<Consumer<ValidationResult>> validationListeners = new ArrayList<>();
    private final List<Consumer<VisualErrorEvent>> visualErrorListeners = new ArrayList<>();
    
    // Visual error indicators
    private final Map<String, VisualError> activeErrors = new ConcurrentHashMap<>();
    
    /**
     * Comprehensive validation result with detailed information.
     */
    public static class ValidationResult {
        private final String variantName;
        private final boolean valid;
        private final ValidationLevel level;
        private final List<ValidationError> errors;
        private final List<ValidationWarning> warnings;
        private final long validationTime;
        private final Map<String, Object> metadata;
        
        public ValidationResult(String variantName, boolean valid, ValidationLevel level,
                              List<ValidationError> errors, List<ValidationWarning> warnings,
                              Map<String, Object> metadata) {
            this.variantName = variantName;
            this.valid = valid;
            this.level = level;
            this.errors = new ArrayList<>(errors);
            this.warnings = new ArrayList<>(warnings);
            this.validationTime = System.currentTimeMillis();
            this.metadata = new ConcurrentHashMap<>(metadata);
        }
        
        public String getVariantName() { return variantName; }
        public boolean isValid() { return valid; }
        public ValidationLevel getLevel() { return level; }
        public List<ValidationError> getErrors() { return new ArrayList<>(errors); }
        public List<ValidationWarning> getWarnings() { return new ArrayList<>(warnings); }
        public long getValidationTime() { return validationTime; }
        public Map<String, Object> getMetadata() { return new ConcurrentHashMap<>(metadata); }
        
        public boolean hasErrors() { return !errors.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
        public int getErrorCount() { return errors.size(); }
        public int getWarningCount() { return warnings.size(); }
        
        public String getSummary() {
            if (valid) {
                return warnings.isEmpty() ? "Valid" : "Valid with " + warnings.size() + " warnings";
            } else {
                return "Invalid: " + errors.size() + " errors" + 
                       (warnings.isEmpty() ? "" : ", " + warnings.size() + " warnings");
            }
        }
    }
    
    /**
     * Validation error with detailed information and suggestions.
     */
    public static class ValidationError {
        private final String errorCode;
        private final String message;
        private final String faceName;
        private final String fieldName;
        private final Object currentValue;
        private final Object expectedValue;
        private final String suggestion;
        private final ValidationSeverity severity;
        
        public ValidationError(String errorCode, String message, String faceName, String fieldName,
                             Object currentValue, Object expectedValue, String suggestion,
                             ValidationSeverity severity) {
            this.errorCode = errorCode;
            this.message = message;
            this.faceName = faceName;
            this.fieldName = fieldName;
            this.currentValue = currentValue;
            this.expectedValue = expectedValue;
            this.suggestion = suggestion;
            this.severity = severity;
        }
        
        public String getErrorCode() { return errorCode; }
        public String getMessage() { return message; }
        public String getFaceName() { return faceName; }
        public String getFieldName() { return fieldName; }
        public Object getCurrentValue() { return currentValue; }
        public Object getExpectedValue() { return expectedValue; }
        public String getSuggestion() { return suggestion; }
        public ValidationSeverity getSeverity() { return severity; }
    }
    
    /**
     * Validation warning with informational messages.
     */
    public static class ValidationWarning {
        private final String warningCode;
        private final String message;
        private final String faceName;
        private final String fieldName;
        private final String suggestion;
        
        public ValidationWarning(String warningCode, String message, String faceName, 
                               String fieldName, String suggestion) {
            this.warningCode = warningCode;
            this.message = message;
            this.faceName = faceName;
            this.fieldName = fieldName;
            this.suggestion = suggestion;
        }
        
        public String getWarningCode() { return warningCode; }
        public String getMessage() { return message; }
        public String getFaceName() { return faceName; }
        public String getFieldName() { return fieldName; }
        public String getSuggestion() { return suggestion; }
    }
    
    /**
     * Simple color representation for visual errors.
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
        public static final SimpleColor YELLOW = new SimpleColor(255, 255, 0);
        public static final SimpleColor LIGHTBLUE = new SimpleColor(173, 216, 230);
    }
    
    /**
     * Visual error for viewport display.
     */
    public static class VisualError {
        private final String errorId;
        private final String message;
        private final SimpleColor color;
        private final int x, y, width, height;
        private final long timestamp;
        private final boolean blinking;
        
        public VisualError(String errorId, String message, SimpleColor color, 
                         int x, int y, int width, int height, boolean blinking) {
            this.errorId = errorId;
            this.message = message;
            this.color = color;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.blinking = blinking;
            this.timestamp = System.currentTimeMillis();
        }
        
        public String getErrorId() { return errorId; }
        public String getMessage() { return message; }
        public SimpleColor getColor() { return color; }
        public int getX() { return x; }
        public int getY() { return y; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public long getTimestamp() { return timestamp; }
        public boolean isBlinking() { return blinking; }
    }
    
    /**
     * Visual error event for notifications.
     */
    public static class VisualErrorEvent {
        private final String eventType;
        private final VisualError visualError;
        private final long timestamp;
        
        public VisualErrorEvent(String eventType, VisualError visualError) {
            this.eventType = eventType;
            this.visualError = visualError;
            this.timestamp = System.currentTimeMillis();
        }
        
        public String getEventType() { return eventType; }
        public VisualError getVisualError() { return visualError; }
        public long getTimestamp() { return timestamp; }
    }
    
    /**
     * Validation levels for different strictness.
     */
    public enum ValidationLevel {
        BASIC,      // Basic format and bounds checking
        STANDARD,   // Standard Stonebreak compatibility
        STRICT,     // Strict validation with all warnings as errors
        COMPREHENSIVE // Complete validation including optimization suggestions
    }
    
    /**
     * Validation error severity levels.
     */
    public enum ValidationSeverity {
        CRITICAL,   // Prevents texture from loading
        HIGH,       // May cause rendering issues
        MEDIUM,     // Potential compatibility problems
        LOW         // Optimization suggestions
    }
    
    // Validation constants
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Set<String> REQUIRED_FACE_MAPPINGS = Set.of(
        "HEAD_FRONT", "HEAD_BACK", "HEAD_LEFT", "HEAD_RIGHT", "HEAD_TOP", "HEAD_BOTTOM",
        "BODY_FRONT", "BODY_BACK", "BODY_LEFT", "BODY_RIGHT", "BODY_TOP", "BODY_BOTTOM",
        "LEG_FRONT", "LEG_BACK", "LEG_LEFT", "LEG_RIGHT", "LEG_TOP", "LEG_BOTTOM"
    );
    private static final Set<String> REQUIRED_BASE_COLORS = Set.of("primary", "secondary", "accent");
    
    /**
     * Creates a new AutomaticValidationManager for the specified viewport.
     * 
     * @param viewport The OpenMason3DViewport to integrate with
     */
    public AutomaticValidationManager(OpenMason3DViewport viewport) {
        this.viewport = viewport;
        logger.info("AutomaticValidationManager initialized for viewport integration");
        
        // Start real-time validation if enabled
        if (realTimeValidation.get()) {
            startRealTimeValidation();
        }
    }
    
    /**
     * Validates a texture variant comprehensively.
     * 
     * @param variantName The variant to validate
     * @param level The validation level to use
     * @return CompletableFuture that resolves to ValidationResult
     */
    public CompletableFuture<ValidationResult> validateVariant(String variantName, ValidationLevel level) {
        if (!validationEnabled.get()) {
            return CompletableFuture.completedFuture(
                new ValidationResult(variantName, true, level, List.of(), List.of(), Map.of()));
        }
        
        logger.debug("Starting {} validation for variant '{}'", level, variantName);
        
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                totalValidations.incrementAndGet();
                
                // Load variant definition
                TextureManager.TextureVariantInfo variantInfo = TextureManager.getVariantInfo(variantName);
                if (variantInfo == null) {
                    ValidationError error = new ValidationError("VARIANT_NOT_FOUND", 
                        "Variant definition not found: " + variantName, "", "variant", 
                        variantName, null, "Check if the variant file exists and is properly formatted",
                        ValidationSeverity.CRITICAL);
                    
                    failedValidations.incrementAndGet();
                    return new ValidationResult(variantName, false, level, List.of(error), 
                                              List.of(), Map.of("validationDuration", 
                                              System.currentTimeMillis() - startTime));
                }
                
                CowTextureDefinition.CowVariant variant = variantInfo.getVariantDefinition();
                
                // Perform validation based on level
                List<ValidationError> errors = new ArrayList<>();
                List<ValidationWarning> warnings = new ArrayList<>();
                
                // Basic validation
                validateBasicFormat(variant, variantName, errors, warnings);
                
                if (level.ordinal() >= ValidationLevel.STANDARD.ordinal()) {
                    validateStonebreakCompatibility(variant, variantName, errors, warnings);
                }
                
                if (level.ordinal() >= ValidationLevel.STRICT.ordinal()) {
                    validateStrictRequirements(variant, variantName, errors, warnings);
                }
                
                if (level.ordinal() >= ValidationLevel.COMPREHENSIVE.ordinal()) {
                    validateComprehensive(variant, variantName, errors, warnings);
                }
                
                // Create result
                boolean valid = errors.isEmpty() || (level != ValidationLevel.STRICT && 
                    errors.stream().allMatch(e -> e.getSeverity() == ValidationSeverity.LOW));
                
                ValidationResult result = new ValidationResult(variantName, valid, level, errors, warnings,
                    Map.of("validationDuration", System.currentTimeMillis() - startTime,
                           "faceMappingCount", variant.getFaceMappings() != null ? variant.getFaceMappings().size() : 0,
                           "drawingInstructionCount", variant.getDrawingInstructions() != null ? variant.getDrawingInstructions().size() : 0));
                
                // Update statistics
                if (valid) {
                    passedValidations.incrementAndGet();
                } else {
                    failedValidations.incrementAndGet();
                }
                
                if (!warnings.isEmpty()) {
                    warningValidations.incrementAndGet();
                }
                
                // Cache result
                validationCache.put(variantName, result);
                
                // Show visual errors if enabled
                if (visualErrorsEnabled.get() && !errors.isEmpty()) {
                    showVisualErrors(variantName, errors);
                }
                
                // Notify listeners
                notifyValidationListeners(result);
                
                logger.debug("Validation completed for '{}' in {}ms: {}", variantName, 
                           System.currentTimeMillis() - startTime, result.getSummary());
                
                return result;
                
            } catch (Exception e) {
                failedValidations.incrementAndGet();
                logger.error("Validation failed for variant '{}'", variantName, e);
                
                ValidationError error = new ValidationError("VALIDATION_EXCEPTION", 
                    "Validation failed due to exception: " + e.getMessage(), "", "validation", 
                    null, null, "Check variant file format and content", ValidationSeverity.CRITICAL);
                
                return new ValidationResult(variantName, false, level, List.of(error), List.of(),
                    Map.of("validationDuration", System.currentTimeMillis() - startTime,
                           "exception", e.getMessage()));
            }
        });
    }
    
    /**
     * Validates basic format requirements.
     */
    private void validateBasicFormat(CowTextureDefinition.CowVariant variant, String variantName,
                                   List<ValidationError> errors, List<ValidationWarning> warnings) {
        
        // Validate face mappings exist
        if (variant.getFaceMappings() == null || variant.getFaceMappings().isEmpty()) {
            errors.add(new ValidationError("NO_FACE_MAPPINGS", 
                "Face mappings are required but not found", "", "faceMappings", 
                null, "Map of face names to atlas coordinates", 
                "Add face mappings for all required model faces", ValidationSeverity.CRITICAL));
            return;
        }
        
        // Validate each face mapping
        for (Map.Entry<String, CowTextureDefinition.AtlasCoordinate> entry : 
             variant.getFaceMappings().entrySet()) {
            
            String faceName = entry.getKey();
            CowTextureDefinition.AtlasCoordinate coord = entry.getValue();
            
            if (coord == null) {
                errors.add(new ValidationError("NULL_COORDINATE", 
                    "Atlas coordinate is null for face", faceName, "atlasCoordinate",
                    null, "Valid AtlasCoordinate object", 
                    "Provide valid atlas coordinates", ValidationSeverity.HIGH));
                continue;
            }
            
            // Validate coordinate bounds
            if (coord.getAtlasX() < 0 || coord.getAtlasX() >= 16) {
                errors.add(new ValidationError("INVALID_ATLAS_X", 
                    "Atlas X coordinate out of bounds (0-15)", faceName, "atlasX",
                    coord.getAtlasX(), "0-15", 
                    "Set atlasX to a value between 0 and 15", ValidationSeverity.HIGH));
            }
            
            if (coord.getAtlasY() < 0 || coord.getAtlasY() >= 16) {
                errors.add(new ValidationError("INVALID_ATLAS_Y", 
                    "Atlas Y coordinate out of bounds (0-15)", faceName, "atlasY",
                    coord.getAtlasY(), "0-15", 
                    "Set atlasY to a value between 0 and 15", ValidationSeverity.HIGH));
            }
        }
        
        // Validate base colors
        if (variant.getBaseColors() != null) {
            validateColor("primary", variant.getBaseColors().getPrimary(), errors, warnings);
            validateColor("secondary", variant.getBaseColors().getSecondary(), errors, warnings);
            validateColor("accent", variant.getBaseColors().getAccent(), errors, warnings);
        } else {
            warnings.add(new ValidationWarning("NO_BASE_COLORS", 
                "Base colors not defined, using defaults", "", "baseColors",
                "Define primary, secondary, and accent colors"));
        }
    }
    
    /**
     * Validates a single color value.
     */
    private void validateColor(String colorType, String colorValue, 
                             List<ValidationError> errors, List<ValidationWarning> warnings) {
        if (colorValue == null || colorValue.isEmpty()) {
            warnings.add(new ValidationWarning("MISSING_COLOR", 
                "Color not defined: " + colorType, "", colorType,
                "Define a hex color value like #FFFFFF"));
            return;
        }
        
        if (!HEX_COLOR_PATTERN.matcher(colorValue).matches()) {
            errors.add(new ValidationError("INVALID_COLOR_FORMAT", 
                "Invalid color format, must be hex (#RRGGBB)", "", colorType,
                colorValue, "#RRGGBB", 
                "Use hex color format like #FFFFFF", ValidationSeverity.MEDIUM));
        }
    }
    
    /**
     * Validates Stonebreak game compatibility.
     */
    private void validateStonebreakCompatibility(CowTextureDefinition.CowVariant variant, String variantName,
                                               List<ValidationError> errors, List<ValidationWarning> warnings) {
        
        // Check for required face mappings
        Set<String> foundMappings = variant.getFaceMappings().keySet();
        for (String requiredFace : REQUIRED_FACE_MAPPINGS) {
            if (!foundMappings.contains(requiredFace)) {
                warnings.add(new ValidationWarning("MISSING_REQUIRED_FACE", 
                    "Required face mapping not found: " + requiredFace, requiredFace, "faceMappings",
                    "Add mapping for " + requiredFace));
            }
        }
        
        // Check for coordinate conflicts
        Map<String, String> coordinateMap = new ConcurrentHashMap<>();
        for (Map.Entry<String, CowTextureDefinition.AtlasCoordinate> entry : 
             variant.getFaceMappings().entrySet()) {
            
            String faceName = entry.getKey();
            CowTextureDefinition.AtlasCoordinate coord = entry.getValue();
            
            if (coord != null) {
                String coordKey = coord.getAtlasX() + "," + coord.getAtlasY();
                String existingFace = coordinateMap.get(coordKey);
                
                if (existingFace != null) {
                    warnings.add(new ValidationWarning("COORDINATE_CONFLICT", 
                        "Atlas coordinate conflict between faces: " + faceName + " and " + existingFace,
                        faceName, "atlasCoordinate",
                        "Use unique coordinates for each face"));
                } else {
                    coordinateMap.put(coordKey, faceName);
                }
            }
        }
        
        // Validate drawing instructions if present
        if (variant.getDrawingInstructions() != null) {
            for (String faceName : variant.getDrawingInstructions().keySet()) {
                if (!foundMappings.contains(faceName)) {
                    warnings.add(new ValidationWarning("ORPHANED_DRAWING_INSTRUCTION", 
                        "Drawing instruction exists for unmapped face: " + faceName, faceName, "drawingInstructions",
                        "Add face mapping or remove drawing instruction"));
                }
            }
        }
    }
    
    /**
     * Validates strict requirements.
     */
    private void validateStrictRequirements(CowTextureDefinition.CowVariant variant, String variantName,
                                          List<ValidationError> errors, List<ValidationWarning> warnings) {
        
        // In strict mode, promote warnings to errors
        List<ValidationWarning> warningsToPromote = new ArrayList<>(warnings);
        warnings.clear();
        
        for (ValidationWarning warning : warningsToPromote) {
            errors.add(new ValidationError(warning.getWarningCode(), 
                warning.getMessage(), warning.getFaceName(), warning.getFieldName(),
                null, null, warning.getSuggestion(), ValidationSeverity.MEDIUM));
        }
        
        // Additional strict checks
        if (variant.getBaseColors() == null) {
            errors.add(new ValidationError("MISSING_BASE_COLORS", 
                "Base colors are required in strict mode", "", "baseColors",
                null, "Complete base color definition", 
                "Define primary, secondary, and accent colors", ValidationSeverity.HIGH));
        }
        
        if (variant.getDrawingInstructions() == null || variant.getDrawingInstructions().isEmpty()) {
            errors.add(new ValidationError("MISSING_DRAWING_INSTRUCTIONS", 
                "Drawing instructions are required in strict mode", "", "drawingInstructions",
                null, "Drawing instructions for texture generation", 
                "Add drawing instructions for texture faces", ValidationSeverity.MEDIUM));
        }
    }
    
    /**
     * Validates comprehensive requirements with optimization suggestions.
     */
    private void validateComprehensive(CowTextureDefinition.CowVariant variant, String variantName,
                                     List<ValidationError> errors, List<ValidationWarning> warnings) {
        
        // Performance optimization suggestions
        int totalMappings = variant.getFaceMappings() != null ? variant.getFaceMappings().size() : 0;
        if (totalMappings > 50) {
            warnings.add(new ValidationWarning("EXCESSIVE_FACE_MAPPINGS", 
                "Large number of face mappings may impact performance: " + totalMappings, 
                "", "faceMappings", "Consider optimizing face mapping count"));
        }
        
        // Color optimization suggestions
        if (variant.getBaseColors() != null) {
            String primary = variant.getBaseColors().getPrimary();
            String secondary = variant.getBaseColors().getSecondary();
            String accent = variant.getBaseColors().getAccent();
            
            if (primary != null && primary.equals(secondary)) {
                warnings.add(new ValidationWarning("DUPLICATE_COLORS", 
                    "Primary and secondary colors are identical", "", "baseColors",
                    "Use different colors for better visual distinction"));
            }
            
            if (primary != null && primary.equals(accent)) {
                warnings.add(new ValidationWarning("DUPLICATE_COLORS", 
                    "Primary and accent colors are identical", "", "baseColors",
                    "Use different colors for better visual distinction"));
            }
        }
        
        // Texture efficiency suggestions
        Set<String> usedCoordinates = new HashSet<>();
        if (variant.getFaceMappings() != null) {
            for (CowTextureDefinition.AtlasCoordinate coord : variant.getFaceMappings().values()) {
                if (coord != null) {
                    usedCoordinates.add(coord.getAtlasX() + "," + coord.getAtlasY());
                }
            }
        }
        
        double atlasUtilization = (double) usedCoordinates.size() / 256.0; // 16x16 atlas
        if (atlasUtilization < 0.1) {
            warnings.add(new ValidationWarning("LOW_ATLAS_UTILIZATION", 
                String.format("Low atlas utilization: %.1f%%", atlasUtilization * 100), 
                "", "faceMappings", "Consider using more atlas space or reducing atlas size"));
        }
    }
    
    /**
     * Shows visual errors in the viewport.
     */
    private void showVisualErrors(String variantName, List<ValidationError> errors) {
        // Clear existing errors for this variant
        clearVisualErrors(variantName);
        
        int errorIndex = 0;
        for (ValidationError error : errors) {
            String errorId = variantName + "_" + error.getErrorCode() + "_" + errorIndex++;
            
            // Determine error color based on severity
            SimpleColor errorColor;
            switch (error.getSeverity()) {
                case CRITICAL:
                    errorColor = SimpleColor.RED;
                    break;
                case HIGH:
                    errorColor = SimpleColor.ORANGE;
                    break;
                case MEDIUM:
                    errorColor = SimpleColor.YELLOW;
                    break;
                case LOW:
                default:
                    errorColor = SimpleColor.LIGHTBLUE;
                    break;
            }
            
            // Create visual error indicator
            // Position based on face name if available
            int x = 10;
            int y = 30 + (errorIndex * 25);
            int width = 300;
            int height = 20;
            
            boolean blinking = error.getSeverity() == ValidationSeverity.CRITICAL;
            
            VisualError visualError = new VisualError(errorId, error.getMessage(), errorColor,
                                                    x, y, width, height, blinking);
            
            activeErrors.put(errorId, visualError);
            
            // Notify visual error listeners
            notifyVisualErrorListeners(new VisualErrorEvent("ERROR_ADDED", visualError));
        }
        
        // Request viewport render to show errors
        viewport.requestRender();
    }
    
    /**
     * Clears visual errors for a variant.
     */
    private void clearVisualErrors(String variantName) {
        List<String> errorsToRemove = new ArrayList<>();
        
        for (String errorId : activeErrors.keySet()) {
            if (errorId.startsWith(variantName + "_")) {
                errorsToRemove.add(errorId);
            }
        }
        
        for (String errorId : errorsToRemove) {
            VisualError visualError = activeErrors.remove(errorId);
            if (visualError != null) {
                notifyVisualErrorListeners(new VisualErrorEvent("ERROR_REMOVED", visualError));
            }
        }
    }
    
    /**
     * Starts real-time validation monitoring.
     */
    private void startRealTimeValidation() {
        logger.info("Starting real-time validation monitoring");
        
        validationScheduler.scheduleAtFixedRate(() -> {
            try {
                String currentVariant = viewport.getCurrentTextureVariant();
                if (currentVariant != null && !currentVariant.isEmpty() && 
                    !currentVariant.equals(currentValidationTarget.get())) {
                    
                    currentValidationTarget.set(currentVariant);
                    
                    // Perform automatic validation
                    ValidationLevel level = strictValidation.get() ? ValidationLevel.STRICT : ValidationLevel.STANDARD;
                    validateVariant(currentVariant, level);
                }
            } catch (Exception e) {
                logger.warn("Error in real-time validation", e);
            }
        }, 1, 2, TimeUnit.SECONDS); // Check every 2 seconds
    }
    
    /**
     * Notifies validation listeners.
     */
    private void notifyValidationListeners(ValidationResult result) {
        for (Consumer<ValidationResult> listener : validationListeners) {
            try {
                listener.accept(result);
            } catch (Exception e) {
                logger.warn("Error notifying validation listener", e);
            }
        }
    }
    
    /**
     * Notifies visual error listeners.
     */
    private void notifyVisualErrorListeners(VisualErrorEvent event) {
        for (Consumer<VisualErrorEvent> listener : visualErrorListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.warn("Error notifying visual error listener", e);
            }
        }
    }
    
    // ===== PUBLIC API METHODS =====
    
    /**
     * Validates the currently active variant in the viewport.
     */
    public CompletableFuture<ValidationResult> validateCurrentVariant() {
        String currentVariant = viewport.getCurrentTextureVariant();
        if (currentVariant == null || currentVariant.isEmpty()) {
            return CompletableFuture.completedFuture(
                new ValidationResult("", false, ValidationLevel.BASIC, 
                    List.of(new ValidationError("NO_VARIANT", "No variant currently selected", 
                                              "", "variant", null, null, "Select a texture variant", 
                                              ValidationSeverity.HIGH)), 
                    List.of(), Map.of()));
        }
        
        ValidationLevel level = strictValidation.get() ? ValidationLevel.STRICT : ValidationLevel.STANDARD;
        return validateVariant(currentVariant, level);
    }
    
    /**
     * Gets cached validation result for a variant.
     */
    public ValidationResult getCachedValidationResult(String variantName) {
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
     * Gets all active visual errors.
     */
    public Map<String, VisualError> getActiveVisualErrors() {
        return new ConcurrentHashMap<>(activeErrors);
    }
    
    /**
     * Clears all visual errors.
     */
    public void clearAllVisualErrors() {
        for (VisualError error : activeErrors.values()) {
            notifyVisualErrorListeners(new VisualErrorEvent("ERROR_REMOVED", error));
        }
        activeErrors.clear();
        viewport.requestRender();
    }
    
    /**
     * Gets validation statistics.
     */
    public Map<String, Object> getValidationStatistics() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("validationEnabled", validationEnabled.get());
        stats.put("realTimeValidation", realTimeValidation.get());
        stats.put("visualErrorsEnabled", visualErrorsEnabled.get());
        stats.put("strictValidation", strictValidation.get());
        stats.put("totalValidations", totalValidations.get());
        stats.put("passedValidations", passedValidations.get());
        stats.put("failedValidations", failedValidations.get());
        stats.put("warningValidations", warningValidations.get());
        stats.put("cachedResults", validationCache.size());
        stats.put("activeVisualErrors", activeErrors.size());
        stats.put("currentValidationTarget", currentValidationTarget.get());
        
        double successRate = totalValidations.get() > 0 ? 
            (double) passedValidations.get() / totalValidations.get() : 0.0;
        stats.put("successRate", successRate);
        
        return stats;
    }
    
    /**
     * Adds a validation result listener.
     */
    public void addValidationListener(Consumer<ValidationResult> listener) {
        validationListeners.add(listener);
    }
    
    /**
     * Removes a validation result listener.
     */
    public void removeValidationListener(Consumer<ValidationResult> listener) {
        validationListeners.remove(listener);
    }
    
    /**
     * Adds a visual error event listener.
     */
    public void addVisualErrorListener(Consumer<VisualErrorEvent> listener) {
        visualErrorListeners.add(listener);
    }
    
    /**
     * Removes a visual error event listener.
     */
    public void removeVisualErrorListener(Consumer<VisualErrorEvent> listener) {
        visualErrorListeners.remove(listener);
    }
    
    // ===== PROPERTY ACCESSORS =====
    
    public boolean isValidationEnabled() { return validationEnabled.get(); }
    public void setValidationEnabled(boolean enabled) { validationEnabled.set(enabled); }
    
    public boolean isRealTimeValidation() { return realTimeValidation.get(); }
    public void setRealTimeValidation(boolean enabled) { 
        realTimeValidation.set(enabled);
        if (enabled) {
            startRealTimeValidation();
        }
    }
    
    public boolean isVisualErrorsEnabled() { return visualErrorsEnabled.get(); }
    public void setVisualErrorsEnabled(boolean enabled) { visualErrorsEnabled.set(enabled); }
    
    public boolean isStrictValidation() { return strictValidation.get(); }
    public void setStrictValidation(boolean enabled) { strictValidation.set(enabled); }
    
    /**
     * Disposes of the AutomaticValidationManager and cleans up resources.
     */
    public void dispose() {
        logger.info("Disposing AutomaticValidationManager");
        
        validationEnabled.set(false);
        realTimeValidation.set(false);
        
        // Shutdown validation scheduler
        validationScheduler.shutdown();
        try {
            if (!validationScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                validationScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            validationScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Clear caches and listeners
        validationCache.clear();
        activeErrors.clear();
        validationListeners.clear();
        visualErrorListeners.clear();
        
        logger.info("AutomaticValidationManager disposed");
    }
}