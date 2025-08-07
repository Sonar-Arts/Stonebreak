package com.openmason.ui.viewport;

import com.openmason.model.StonebreakModel;
import com.openmason.model.stonebreak.StonebreakModelDefinition;

import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.DrawMode;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Handles 3D model rendering using JavaFX 3D components.
 * 
 * Responsible for:
 * - Converting Stonebreak models to JavaFX 3D representation
 * - Model part rendering with proper transformations
 * - Material and texture application
 * - Wireframe and solid rendering modes
 * - Model validation and accuracy verification
 */
public class ViewportModelRenderer {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewportModelRenderer.class);
    
    private ViewportSceneManager sceneManager;
    private String currentTextureVariant = "default";
    private boolean wireframeMode = false;
    
    // Color mappings for different model parts
    private static final Map<String, Color> PART_COLORS = new HashMap<>();
    static {
        PART_COLORS.put("head", Color.LIGHTCORAL);
        PART_COLORS.put("body", Color.LIGHTBLUE);
        PART_COLORS.put("leg1", Color.LIGHTGREEN);
        PART_COLORS.put("leg2", Color.LIGHTGREEN);
        PART_COLORS.put("leg3", Color.LIGHTGREEN);
        PART_COLORS.put("leg4", Color.LIGHTGREEN);
        PART_COLORS.put("udder", Color.PINK);
        PART_COLORS.put("tail", Color.DARKGRAY);
    }
    
    /**
     * Initialize with scene manager reference.
     */
    public void initialize(ViewportSceneManager sceneManager) {
        this.sceneManager = sceneManager;
        logger.debug("ViewportModelRenderer initialized");
    }
    
    /**
     * Render a Stonebreak model using JavaFX 3D components.
     */
    public void renderModel(StonebreakModel model) {
        if (model == null) {
            logger.warn("Cannot render model: model is null");
            return;
        }
        
        if (sceneManager == null) {
            logger.error("Cannot render model: scene manager not initialized");
            return;
        }
        
        logger.debug("Rendering model: {}", model.getVariantName());
        
        // Clear existing model parts
        sceneManager.clearModelParts();
        
        try {
            // Get model definition
            StonebreakModelDefinition.CowModelDefinition definition = model.getModelDefinition();
            if (definition == null) {
                logger.warn("Model definition is null for model: {}", model.getVariantName());
                renderPlaceholderModel();
                return;
            }
            
            // Render model parts
            StonebreakModelDefinition.ModelParts parts = definition.getParts();
            if (parts != null) {
                renderModelParts(parts);
                validateModelRendering(model, parts);
            } else {
                logger.warn("Model parts are null for model: {}", model.getVariantName());
                renderPlaceholderModel();
            }
            
            logger.info("Successfully rendered model: {}", model.getVariantName());
            
        } catch (Exception e) {
            logger.error("Failed to render model: " + model.getVariantName(), e);
            renderPlaceholderModel();
        }
    }
    
    /**
     * Render individual model parts.
     */
    private void renderModelParts(StonebreakModelDefinition.ModelParts parts) {
        // Create entity matrix for root transformation
        Matrix4f entityMatrix = new Matrix4f().identity();
        
        // Render each model part
        if (parts.getHead() != null) {
            renderModelPart("head", parts.getHead(), entityMatrix);
        }
        if (parts.getBody() != null) {
            renderModelPart("body", parts.getBody(), entityMatrix);
        }
        if (parts.getLegs() != null) {
            for (int i = 0; i < parts.getLegs().size(); i++) {
                StonebreakModelDefinition.ModelPart leg = parts.getLegs().get(i);
                renderModelPart("leg" + (i + 1), leg, entityMatrix);
            }
        }
        if (parts.getUdder() != null) {
            renderModelPart("udder", parts.getUdder(), entityMatrix);
        }
        if (parts.getTail() != null) {
            renderModelPart("tail", parts.getTail(), entityMatrix);
        }
        
        logger.debug("Rendered {} model parts", getPartCount(parts));
    }
    
    /**
     * Render a single model part with transformations.
     */
    private void renderModelPart(String partName, StonebreakModelDefinition.ModelPart part, Matrix4f entityMatrix) {
        if (part == null) {
            logger.warn("Cannot render part '{}': part is null", partName);
            return;
        }
        
        try {
            // Calculate part matrix with transformations
            Matrix4f partMatrix = new Matrix4f(entityMatrix);
            
            // Apply part transformations
            Vector3f translation = part.getPositionVector();
            if (translation != null) {
                partMatrix.translate(translation.x, translation.y, translation.z);
            }
            
            Vector3f rotation = part.getRotation();
            if (rotation != null) {
                partMatrix.rotateX(rotation.x)
                          .rotateY(rotation.y)
                          .rotateZ(rotation.z);
            }
            
            // Get part dimensions
            Vector3f sizeVector = part.getSizeVector();
            if (sizeVector == null) {
                logger.warn("Invalid size for part '{}'", partName);
                return;
            }
            
            float width = sizeVector.x;
            float height = sizeVector.y;
            float depth = sizeVector.z;
            
            // Create JavaFX Box for the part
            Box box = new Box(width, height, depth);
            
            // Apply transformations to the box
            applyTransformationsToBox(box, partMatrix);
            
            // Apply material
            PhongMaterial material = createMaterialForPart(partName);
            box.setMaterial(material);
            
            // Set draw mode based on wireframe setting
            box.setDrawMode(wireframeMode ? DrawMode.LINE : DrawMode.FILL);
            
            // Add to scene
            sceneManager.addModelPart(partName, box, material);
            
            logger.trace("Rendered part '{}': size=({},{},{})", partName, width, height, depth);
            
        } catch (Exception e) {
            logger.error("Failed to render model part '" + partName + "'", e);
        }
    }
    
    /**
     * Apply matrix transformations to a JavaFX Box.
     */
    private void applyTransformationsToBox(Box box, Matrix4f matrix) {
        // Extract translation
        Vector3f translation = new Vector3f();
        matrix.getTranslation(translation);
        
        box.getTransforms().add(new Translate(translation.x, translation.y, translation.z));
        
        // Extract and apply rotation
        // Note: This is a simplified rotation extraction
        // For more complex cases, proper matrix decomposition would be needed
        Vector3f eulerAngles = extractEulerAngles(matrix);
        if (eulerAngles.x != 0) {
            box.getTransforms().add(new Rotate(Math.toDegrees(eulerAngles.x), Rotate.X_AXIS));
        }
        if (eulerAngles.y != 0) {
            box.getTransforms().add(new Rotate(Math.toDegrees(eulerAngles.y), Rotate.Y_AXIS));
        }
        if (eulerAngles.z != 0) {
            box.getTransforms().add(new Rotate(Math.toDegrees(eulerAngles.z), Rotate.Z_AXIS));
        }
        
        // Extract and apply scale (if needed)
        Vector3f scale = extractScale(matrix);
        if (scale.x != 1.0f || scale.y != 1.0f || scale.z != 1.0f) {
            box.getTransforms().add(new Scale(scale.x, scale.y, scale.z));
        }
    }
    
    /**
     * Extract Euler angles from transformation matrix (simplified).
     */
    private Vector3f extractEulerAngles(Matrix4f matrix) {
        // Simplified Euler angle extraction
        // This assumes a specific rotation order and may not work for all cases
        float sy = (float) Math.sqrt(matrix.m00() * matrix.m00() + matrix.m10() * matrix.m10());
        
        boolean singular = sy < 1e-6;
        
        float x, y, z;
        if (!singular) {
            x = (float) Math.atan2(matrix.m21(), matrix.m22());
            y = (float) Math.atan2(-matrix.m20(), sy);
            z = (float) Math.atan2(matrix.m10(), matrix.m00());
        } else {
            x = (float) Math.atan2(-matrix.m12(), matrix.m11());
            y = (float) Math.atan2(-matrix.m20(), sy);
            z = 0;
        }
        
        return new Vector3f(x, y, z);
    }
    
    /**
     * Extract scale from transformation matrix.
     */
    private Vector3f extractScale(Matrix4f matrix) {
        float scaleX = new Vector3f(matrix.m00(), matrix.m10(), matrix.m20()).length();
        float scaleY = new Vector3f(matrix.m01(), matrix.m11(), matrix.m21()).length();
        float scaleZ = new Vector3f(matrix.m02(), matrix.m12(), matrix.m22()).length();
        
        return new Vector3f(scaleX, scaleY, scaleZ);
    }
    
    /**
     * Create material for a model part.
     */
    private PhongMaterial createMaterialForPart(String partName) {
        PhongMaterial material = new PhongMaterial();
        
        // Get base color for part
        Color baseColor = PART_COLORS.getOrDefault(partName, Color.LIGHTGRAY);
        
        // Apply texture variant modifications
        Color finalColor = getColorForTextureVariant(baseColor, currentTextureVariant);
        
        material.setDiffuseColor(finalColor);
        
        if (wireframeMode) {
            material.setSpecularColor(Color.WHITE);
            material.setSpecularPower(128.0);
        } else {
            material.setSpecularColor(finalColor.brighter());
            material.setSpecularPower(64.0);
        }
        
        return material;
    }
    
    /**
     * Get color adjusted for texture variant.
     */
    private Color getColorForTextureVariant(Color baseColor, String variant) {
        switch (variant) {
            case "angus":
                return baseColor.deriveColor(0, 0.8, 0.9, 1.0); // Darker
            case "highland":
                return baseColor.deriveColor(30, 1.2, 1.1, 1.0); // Warmer, lighter
            case "jersey":
                return baseColor.deriveColor(-30, 1.1, 1.0, 1.0); // Cooler
            default:
                return baseColor;
        }
    }
    
    /**
     * Render a placeholder model when actual model fails.
     */
    private void renderPlaceholderModel() {
        logger.debug("Rendering placeholder model");
        
        // Create a simple placeholder cube
        Box placeholder = new Box(2.0, 2.0, 2.0);
        PhongMaterial material = new PhongMaterial();
        material.setDiffuseColor(Color.YELLOW);
        material.setSpecularColor(Color.WHITE);
        
        placeholder.setMaterial(material);
        placeholder.setDrawMode(wireframeMode ? DrawMode.LINE : DrawMode.FILL);
        
        sceneManager.addModelPart("placeholder", placeholder, material);
    }
    
    /**
     * Validate model rendering accuracy.
     */
    private void validateModelRendering(StonebreakModel model, StonebreakModelDefinition.ModelParts parts) {
        try {
            int expectedParts = getPartCount(parts);
            int renderedParts = sceneManager.getModelPartBoxes().size();
            
            if (expectedParts != renderedParts) {
                logger.warn("Model rendering validation: Expected {} parts, rendered {} parts", 
                    expectedParts, renderedParts);
            } else {
                logger.debug("Model rendering validation passed: {} parts rendered correctly", renderedParts);
            }
            
        } catch (Exception e) {
            logger.warn("Model rendering validation failed", e);
        }
    }
    
    /**
     * Count the number of parts in a model definition.
     */
    private int getPartCount(StonebreakModelDefinition.ModelParts parts) {
        int count = 0;
        if (parts.getHead() != null) count++;
        if (parts.getBody() != null) count++;
        if (parts.getLegs() != null) {
            count += parts.getLegs().size();
        }
        if (parts.getUdder() != null) count++;
        if (parts.getTail() != null) count++;
        return count;
    }
    
    /**
     * Clear all rendered model parts.
     */
    public void clearModel() {
        if (sceneManager != null) {
            sceneManager.clearModelParts();
        }
        logger.debug("Model cleared from renderer");
    }
    
    // Getters and Setters
    public void setTextureVariant(String variant) {
        this.currentTextureVariant = variant != null ? variant : "default";
        logger.debug("Texture variant set to: {}", this.currentTextureVariant);
    }
    
    public String getTextureVariant() {
        return currentTextureVariant;
    }
    
    public void setWireframeMode(boolean enabled) {
        this.wireframeMode = enabled;
        
        // Update existing model parts
        if (sceneManager != null) {
            Map<String, Box> boxes = sceneManager.getModelPartBoxes();
            for (Box box : boxes.values()) {
                box.setDrawMode(enabled ? DrawMode.LINE : DrawMode.FILL);
            }
        }
        
        logger.debug("Wireframe mode set to: {}", enabled);
    }
    
    public boolean isWireframeMode() {
        return wireframeMode;
    }
}