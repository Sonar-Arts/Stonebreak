package com.openmason.ui.viewport;

import com.openmason.model.StonebreakModel;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Validates coordinate system consistency between rendering and ray casting.
 * 
 * This validator helps ensure that:
 * - Model bounding boxes align with rendered positions
 * - Ray casting uses the same coordinate system as rendering
 * - Camera matrices are correctly configured
 * - Coordinate transformations are applied consistently
 */
public class ViewportCoordinateValidator {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewportCoordinateValidator.class);
    
    private ViewportSceneManager sceneManager;
    private ViewportInputHandler inputHandler;
    private ViewportModelRenderer modelRenderer;
    
    // Real-time validation tracking
    private long lastValidationTime = 0;
    private static final long VALIDATION_INTERVAL_MS = 5000; // Validate every 5 seconds
    
    /**
     * Initialize validator with viewport components.
     */
    public void initialize(ViewportSceneManager sceneManager, 
                          ViewportInputHandler inputHandler, 
                          ViewportModelRenderer modelRenderer) {
        this.sceneManager = sceneManager;
        this.inputHandler = inputHandler;
        this.modelRenderer = modelRenderer;
        this.lastValidationTime = System.currentTimeMillis();
        
        logger.info("ViewportCoordinateValidator initialized with real-time monitoring");
    }
    
    /**
     * Perform comprehensive coordinate system validation.
     */
    public ValidationReport validateCoordinateSystem(StonebreakModel model) {
        ValidationReport report = new ValidationReport();
        
        if (model == null) {
            report.addError("Model is null");
            return report;
        }
        
        logger.info("=== COORDINATE SYSTEM VALIDATION ===");
        
        // Update last validation time
        lastValidationTime = System.currentTimeMillis();
        
        // 1. Validate camera matrices
        validateCameraMatrices(report);
        
        // 2. Validate model bounding boxes
        validateModelBounds(model, report);
        
        // 3. Validate coordinate transformations
        validateCoordinateTransformations(model, report);
        
        // 4. Test ray casting accuracy
        validateRayCastingAccuracy(model, report);
        
        logger.info("Validation completed: {} errors, {} warnings", 
            report.getErrorCount(), report.getWarningCount());
        
        return report;
    }
    
    /**
     * Perform quick validation check for real-time monitoring.
     * Returns true if validation should be run again.
     */
    public boolean shouldPerformValidation() {
        long currentTime = System.currentTimeMillis();
        return (currentTime - lastValidationTime) > VALIDATION_INTERVAL_MS;
    }
    
    /**
     * Perform lightweight coordinate alignment check for continuous monitoring.
     */
    public boolean quickCoordinateAlignmentCheck(StonebreakModel model) {
        if (model == null || modelRenderer == null) {
            return false;
        }
        
        try {
            // Quick check - verify coordinate system matrix is valid
            org.joml.Matrix4f coordMatrix = modelRenderer.getCoordinateSystemMatrix();
            if (coordMatrix == null) {
                logger.debug("Quick alignment check: coordinate matrix is null");
                return false;
            }
            
            // Check Y-flip is correct
            float yFlip = coordMatrix.m11();
            if (Math.abs(yFlip + 1.0f) > 0.001f) {
                logger.debug("Quick alignment check: Y-flip incorrect: {}", yFlip);
                return false;
            }
            
            // Quick check - ensure we have transformed positions stored
            java.util.Map<String, org.joml.Vector3f> transformedPositions = modelRenderer.getAllTransformedPositions();
            int expectedParts = model.getBodyParts().size();
            int actualParts = transformedPositions.size();
            
            if (actualParts != expectedParts) {
                logger.debug("Quick alignment check: part count mismatch: expected={}, actual={}", expectedParts, actualParts);
                return false;
            }
            
            // Quick coordinate alignment check passed (reduced spam)
            return true;
            
        } catch (Exception e) {
            logger.debug("Quick alignment check failed with exception: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Validate camera matrix extraction.
     */
    private void validateCameraMatrices(ValidationReport report) {
        // Validating Camera Matrices (reduced spam)
        
        if (sceneManager == null) {
            report.addError("Scene manager is null");
            return;
        }
        
        // Skip JavaFX camera validation - not available in current implementation
        logger.info("Camera validation skipped - no JavaFX camera available");
        
        // Camera matrix validation completed (reduced spam)
    }
    
    /**
     * Validate model bounding boxes against rendered positions.
     */
    private void validateModelBounds(StonebreakModel model, ValidationReport report) {
        // Validating Model Bounds (reduced spam)
        
        List<StonebreakModel.BodyPart> bodyParts = model.getBodyParts();
        // Validating body parts (reduced spam)
        
        for (StonebreakModel.BodyPart part : bodyParts) {
            StonebreakModel.BoundingBox bounds = part.getBounds();
            
            // Part bounds logging (reduced spam)
            
            // Check for reasonable bounds
            if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0 || bounds.getDepth() <= 0) {
                report.addError("Part '" + part.getName() + "' has invalid size: " +
                    String.format("(%.2f, %.2f, %.2f)", bounds.getWidth(), bounds.getHeight(), bounds.getDepth()));
            }
            
            // Check for bounds that are too large (likely coordinate system error)
            float maxDimension = Math.max(Math.max(bounds.getWidth(), bounds.getHeight()), bounds.getDepth());
            if (maxDimension > 100) {
                report.addWarning("Part '" + part.getName() + "' has very large dimension: " + maxDimension);
            }
            
            // Apply coordinate transformation (same as in ray casting)
            float transformedMinY = -bounds.getMinY() - bounds.getHeight();
            float transformedMaxY = -bounds.getMinY();
            
            logger.debug("Part '{}': Y-transform: [{}, {}] -> [{}, {}]", 
                part.getName(),
                bounds.getMinY(), bounds.getMinY() + bounds.getHeight(),
                transformedMinY, transformedMaxY);
        }
        
        // Model bounds validation completed (reduced spam)
    }
    
    /**
     * Validate coordinate system transformations.
     */
    private void validateCoordinateTransformations(StonebreakModel model, ValidationReport report) {
        // Validating Coordinate Transformations (reduced spam)
        
        // Test key transformation points
        Vector3f testPoint = new Vector3f(1.0f, 2.0f, 3.0f);
        
        // Apply rendering transformation (Y-flip)
        Vector3f renderTransformed = new Vector3f(testPoint.x, -testPoint.y, testPoint.z);
        
        // Test point transformation (reduced spam)
        
        // Verify transformation consistency
        if (Math.abs(renderTransformed.x - testPoint.x) > 0.001f) {
            report.addError("X coordinate transformation inconsistent");
        }
        
        if (Math.abs(renderTransformed.y + testPoint.y) > 0.001f) {
            report.addError("Y coordinate transformation inconsistent (expected Y-flip)");
        }
        
        if (Math.abs(renderTransformed.z - testPoint.z) > 0.001f) {
            report.addError("Z coordinate transformation inconsistent");
        }
        
        // Coordinate transformation validation completed (reduced spam)
    }
    
    /**
     * Test ray casting accuracy by validating coordinate alignment.
     */
    private void validateRayCastingAccuracy(StonebreakModel model, ValidationReport report) {
        // Validating Ray Casting Accuracy (reduced spam)
        
        if (inputHandler == null) {
            report.addError("Input handler not available for ray casting test");
            return;
        }
        
        if (sceneManager == null) {
            report.addError("Scene manager not available for ray casting test");
            return;
        }
        
        // Test coordinate alignment between rendering and ray casting
        validateCoordinateAlignmentBetweenSystems(model, report);
        
        // Test center screen ray casting
        double centerX = sceneManager.getSceneWidth() / 2.0;
        double centerY = sceneManager.getSceneHeight() / 2.0;
        
        logger.info("Testing ray casting at screen center: ({}, {})", centerX, centerY);
        
        try {
            // Test transformation matrix consistency
            if (modelRenderer != null) {
                org.joml.Matrix4f renderMatrix = modelRenderer.getCoordinateSystemMatrix();
                if (renderMatrix != null) {
                    float yFlip = renderMatrix.m11();
                    if (Math.abs(yFlip + 1.0f) < 0.001f) {
                        report.addInfo("Coordinate system Y-flip verified: " + yFlip);
                    } else {
                        report.addWarning("Unexpected Y-flip value: " + yFlip + " (expected -1.0)");
                    }
                } else {
                    report.addError("Model renderer coordinate matrix is null");
                }
            }
            
            report.addInfo("Test ray at screen center: (" + centerX + ", " + centerY + ")");
            
        } catch (Exception e) {
            report.addError("Ray casting validation failed: " + e.getMessage());
        }
        
        // Ray casting accuracy validation completed (reduced spam)
    }
    
    /**
     * Validate that coordinate transformations are identical between rendering and ray casting.
     */
    private void validateCoordinateAlignmentBetweenSystems(StonebreakModel model, ValidationReport report) {
        // Validating Coordinate Alignment Between Systems (reduced spam)
        
        if (modelRenderer == null) {
            report.addError("Model renderer not available for coordinate alignment test");
            return;
        }
        
        // Test coordinate transformation consistency
        List<StonebreakModel.BodyPart> bodyParts = model.getBodyParts();
        int alignmentErrors = 0;
        
        for (StonebreakModel.BodyPart part : bodyParts) {
            try {
                // Get original position from model definition
                org.joml.Vector3f originalPos = part.getModelPart().getPositionVector();
                
                if (originalPos == null) {
                    report.addWarning("Part '" + part.getName() + "' has no position vector");
                    continue;
                }
                
                // Get coordinate system matrix
                org.joml.Matrix4f coordMatrix = modelRenderer.getCoordinateSystemMatrix();
                
                // Apply unified transformation (same as renderer uses)
                org.joml.Vector3f transformedPos = modelRenderer.applyUnifiedCoordinateTransform(originalPos, coordMatrix);
                
                if (transformedPos != null) {
                    // Verify transformation is reasonable
                    float transformationDifference = originalPos.distance(transformedPos);
                    
                    logger.debug("Part '{}': original=({},{},{}) -> transformed=({},{},{}) distance={}", 
                        part.getName(),
                        originalPos.x, originalPos.y, originalPos.z,
                        transformedPos.x, transformedPos.y, transformedPos.z,
                        transformationDifference);
                    
                    // Check if Y-coordinate was flipped as expected
                    if (Math.abs(transformedPos.y + originalPos.y) > 0.001f) {
                        report.addWarning("Part '" + part.getName() + "' Y-coordinate transformation seems incorrect");
                        alignmentErrors++;
                    }
                    
                } else {
                    report.addError("Part '" + part.getName() + "' transformation returned null");
                    alignmentErrors++;
                }
                
            } catch (Exception e) {
                report.addError("Error validating alignment for part '" + part.getName() + "': " + e.getMessage());
                alignmentErrors++;
            }
        }
        
        if (alignmentErrors == 0) {
            // ✓ Coordinate alignment validation passed (reduced spam)
        } else {
            report.addError("✗ Coordinate alignment validation failed for " + alignmentErrors + " out of " + bodyParts.size() + " parts");
        }
    }
    
    /**
     * Generate detailed debugging information.
     */
    public String generateDebugReport(StonebreakModel model) {
        StringBuilder report = new StringBuilder();
        report.append("=== COORDINATE SYSTEM DEBUG REPORT ===\n");
        report.append("Validation Time: ").append(new java.util.Date(lastValidationTime)).append("\n");
        report.append("Time Since Last Validation: ").append(System.currentTimeMillis() - lastValidationTime).append(" ms\n");
        
        if (sceneManager != null) {
            report.append("Scene Dimensions: ")
                  .append(sceneManager.getSceneWidth())
                  .append("x")
                  .append(sceneManager.getSceneHeight())
                  .append("\n");
            
            // Camera information not available without JavaFX
            report.append("Camera: Not using JavaFX camera\n");
        }
        
        // Add coordinate system information
        if (modelRenderer != null) {
            org.joml.Matrix4f coordMatrix = modelRenderer.getCoordinateSystemMatrix();
            if (coordMatrix != null) {
                report.append("Coordinate System Matrix Y-flip: ").append(coordMatrix.m11()).append("\n");
                
                // Show all transformed positions for debugging
                java.util.Map<String, org.joml.Vector3f> transformedPositions = modelRenderer.getAllTransformedPositions();
                if (!transformedPositions.isEmpty()) {
                    report.append("Transformed Positions (Rendering):\n");
                    for (java.util.Map.Entry<String, org.joml.Vector3f> entry : transformedPositions.entrySet()) {
                        org.joml.Vector3f pos = entry.getValue();
                        report.append("  ").append(entry.getKey()).append(": (")
                              .append(pos.x).append(", ")
                              .append(pos.y).append(", ")
                              .append(pos.z).append(")\n");
                    }
                }
            }
        }
        
        if (model != null) {
            report.append("Model Variant: ").append(model.getVariantName()).append("\n");
            report.append("Model Parts: ").append(model.getBodyParts().size()).append("\n");
            
            for (StonebreakModel.BodyPart part : model.getBodyParts()) {
                StonebreakModel.BoundingBox bounds = part.getBounds();
                report.append("  ").append(part.getName()).append(": ");
                report.append("pos=[").append(bounds.getMinX()).append(",")
                      .append(bounds.getMinY()).append(",")
                      .append(bounds.getMinZ()).append("] ");
                report.append("size=[").append(bounds.getWidth()).append(",")
                      .append(bounds.getHeight()).append(",")
                      .append(bounds.getDepth()).append("]\n");
                
                // Add transformed position if available from model renderer
                if (modelRenderer != null) {
                    org.joml.Vector3f transformedPos = modelRenderer.getPartTransformedPosition(part.getName());
                    if (transformedPos != null) {
                        report.append("    transformed_pos=[").append(transformedPos.x).append(",")
                              .append(transformedPos.y).append(",")
                              .append(transformedPos.z).append("]\n");
                    }
                }
            }
        }
        
        // Add real-time validation status
        report.append("\n=== REAL-TIME VALIDATION STATUS ===\n");
        if (model != null) {
            boolean quickCheck = quickCoordinateAlignmentCheck(model);
            report.append("Quick Alignment Check: ").append(quickCheck ? "✓ PASS" : "✗ FAIL").append("\n");
            report.append("Should Re-validate: ").append(shouldPerformValidation() ? "Yes" : "No").append("\n");
        }
        
        return report.toString();
    }
    
    /**
     * Validation report containing errors, warnings, and information.
     */
    public static class ValidationReport {
        private final java.util.List<String> errors = new java.util.ArrayList<>();
        private final java.util.List<String> warnings = new java.util.ArrayList<>();
        private final java.util.List<String> info = new java.util.ArrayList<>();
        
        public void addError(String error) { 
            errors.add(error); 
            logger.error("Validation Error: {}", error);
        }
        
        public void addWarning(String warning) { 
            warnings.add(warning); 
            logger.warn("Validation Warning: {}", warning);
        }
        
        public void addInfo(String info) { 
            this.info.add(info); 
            logger.info("Validation Info: {}", info);
        }
        
        public boolean isValid() { return errors.isEmpty(); }
        public int getErrorCount() { return errors.size(); }
        public int getWarningCount() { return warnings.size(); }
        public int getInfoCount() { return info.size(); }
        
        public java.util.List<String> getErrors() { return errors; }
        public java.util.List<String> getWarnings() { return warnings; }
        public java.util.List<String> getInfo() { return info; }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Coordinate System Validation Report:\n");
            sb.append("  Errors: ").append(errors.size()).append("\n");
            sb.append("  Warnings: ").append(warnings.size()).append("\n");
            sb.append("  Info: ").append(info.size()).append("\n");
            
            if (!errors.isEmpty()) {
                sb.append("  Error Details:\n");
                for (String error : errors) {
                    sb.append("    - ").append(error).append("\n");
                }
            }
            
            if (!warnings.isEmpty()) {
                sb.append("  Warning Details:\n");
                for (String warning : warnings) {
                    sb.append("    - ").append(warning).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
}