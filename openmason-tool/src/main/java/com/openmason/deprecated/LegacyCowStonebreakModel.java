package com.openmason.deprecated;

import com.stonebreak.model.ModelDefinition;
import com.stonebreak.model.ModelLoader;
import com.stonebreak.textures.mobs.CowTextureDefinition;
import com.stonebreak.textures.mobs.CowTextureLoader;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Integration bridge between Stonebreak cow model data and Open Mason UI.
 *
 * <p>This class specifically handles cow mob models, combining {@link ModelDefinition.CowModelDefinition}
 * and {@link com.stonebreak.textures.mobs.CowTextureDefinition.CowVariant} into a unified interface
 * for the Open Mason development toolset.</p>
 *
 * <p><b>Scope:</b> This class only works with cow models (standard_cow, standard_cow_baked, etc.)
 * and cannot be used for other mob types without significant refactoring.</p>
 *
 * @deprecated This cow-specific model wrapper is deprecated due to its overly narrow scope.
 *             It only handles cow models and their textures. A general-purpose model wrapper
 *             that can handle multiple mob types should be implemented instead. Direct use of
 *             {@link com.stonebreak.model.ModelLoader} and texture loaders is recommended until
 *             a replacement is available.
 */
@Deprecated
public class LegacyCowStonebreakModel {
    private final ModelDefinition.CowModelDefinition modelDefinition;
    private final CowTextureDefinition.CowVariant textureDefinition;
    private final String variantName;

    public LegacyCowStonebreakModel(ModelDefinition.CowModelDefinition modelDefinition,
                                    CowTextureDefinition.CowVariant textureDefinition,
                                    String variantName) {
        this.modelDefinition = modelDefinition;
        this.textureDefinition = textureDefinition;
        this.variantName = variantName;
    }
    
    /**
     * Constructor for ModelManager integration (with default texture).
     */
    public LegacyCowStonebreakModel(LegacyCowModelManager.ModelInfo modelInfo, ModelDefinition.ModelPart[] parts) {
        this.modelDefinition = modelInfo.getModelDefinition();
        this.textureDefinition = getDefaultTextureVariant();
        this.variantName = "default";
    }
    
    /**
     * Get default texture variant for models without specific texture.
     */
    private static CowTextureDefinition.CowVariant getDefaultTextureVariant() {
        try {
            return CowTextureLoader.getCowVariant("default");
        } catch (Exception e) {
            System.err.println("[StonebreakModel] Warning: Could not load default texture variant: " + e.getMessage());
            return null;
        }
    }

    /**
     * Maps legacy model names to their correct baked variants for proper positioning.
     * This ensures compatibility with Stonebreak's rendering system which uses baked transformations.
     * 
     * @param modelName The requested model name
     * @return The actual model name to load (may be a baked variant)
     */
    private static String mapModelName(String modelName) {
        // Use baked model variants for proper positioning - these have pre-applied Y-offset transformations
        // that match Stonebreak's EntityRenderer expectations
        return switch (modelName) {
            case "standard_cow" -> "standard_cow_baked";
            // Future model variants can be added here
            default -> modelName;
        };
    }
    
    /**
     * Factory method to create a StonebreakModel from resource files.
     * Automatically maps model names to their baked variants for proper positioning.
     */
    public static LegacyCowStonebreakModel loadFromResources(String modelPath, String texturePath, String variantName) {
        try {
            // Map to baked model variant if needed for proper positioning
            String actualModelName = mapModelName(modelPath);
            if (!actualModelName.equals(modelPath)) {
                System.out.println("[StonebreakModel] Mapped model '" + modelPath + "' to '" + actualModelName + 
                                 "' for proper positioning compatibility");
            }
            
            ModelDefinition.CowModelDefinition model = ModelLoader.getCowModel(actualModelName);
            CowTextureDefinition.CowVariant texture = CowTextureLoader.getCowVariant(variantName);
            
            return new LegacyCowStonebreakModel(model, texture, variantName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Stonebreak model: " + variantName, e);
        }
    }

    /**
     * Get all body parts defined in the model
     */
    public List<BodyPart> getBodyParts() {
        // Convert CowModelDefinition parts to BodyPart wrappers
        List<BodyPart> bodyParts = new ArrayList<>();
        
        if (modelDefinition.getParts() != null) {
            ModelDefinition.ModelParts parts = modelDefinition.getParts();
            
            // Add body
            if (parts.getBody() != null) {
                bodyParts.add(new BodyPart(parts.getBody()));
            }
            
            // Add head
            if (parts.getHead() != null) {
                bodyParts.add(new BodyPart(parts.getHead()));
            }
            
            // Add legs
            if (parts.getLegs() != null) {
                for (ModelDefinition.ModelPart leg : parts.getLegs()) {
                    bodyParts.add(new BodyPart(leg));
                }
            }
            
            // Add horns
            if (parts.getHorns() != null) {
                for (ModelDefinition.ModelPart horn : parts.getHorns()) {
                    bodyParts.add(new BodyPart(horn));
                }
            }
            
            // Add udder
            if (parts.getUdder() != null) {
                bodyParts.add(new BodyPart(parts.getUdder()));
            }
            
            // Add tail
            if (parts.getTail() != null) {
                bodyParts.add(new BodyPart(parts.getTail()));
            }
        }
        
        return bodyParts;
    }

    /**
     * Get texture face mappings for all body parts
     */
    public Map<String, CowTextureDefinition.AtlasCoordinate> getFaceMappings() {
        return textureDefinition.getFaceMappings();
    }

    /**
     * Get base colors for the texture variant
     */
    public CowTextureDefinition.BaseColors getBaseColors() {
        return textureDefinition.getBaseColors();
    }

    /**
     * Get facial features configuration
     */
    public CowTextureDefinition.FacialFeatures getFacialFeatures() {
        if (textureDefinition.getDrawingInstructions() == null) return null;
        for (CowTextureDefinition.DrawingInstructions instr : textureDefinition.getDrawingInstructions().values()) {
            if (instr.getFacialFeatures() != null) {
                return instr.getFacialFeatures();
            }
        }
        return null;
    }

    /**
     * Get drawing instructions for procedural texture generation
     */
    public Map<String, CowTextureDefinition.DrawingInstructions> getDrawingInstructions() {
        return textureDefinition.getDrawingInstructions();
    }

    /**
     * Get the texture atlas for coordinate lookups
     */
    public Object getTextureAtlas() {
        // Texture atlas functionality is now in CowTextureLoader static methods
        return null;
    }

    /**
     * Validate that the model and texture data are consistent and complete
     */
    public ValidationResult validate() {
        ValidationResult result = new ValidationResult();
        
        // First validate model part completeness
        validateModelPartCompleteness(result);
        
        // Then validate texture mappings
        validateTextureMappings(result);
        
        return result;
    }
    
    /**
     * Validates that all model parts have complete geometric data for rendering
     */
    private void validateModelPartCompleteness(ValidationResult result) {
        List<BodyPart> bodyParts = getBodyParts();
        
        if (bodyParts.isEmpty()) {
            result.addError("Model has no body parts defined");
            return;
        }
        
        result.setModelPartCount(bodyParts.size());
        
        for (BodyPart bodyPart : bodyParts) {
            validateModelPart(bodyPart, result);
        }
    }
    
    /**
     * Validates a single model part for completeness
     */
    private void validateModelPart(BodyPart bodyPart, ValidationResult result) {
        String partName = bodyPart.getName();
        ModelDefinition.ModelPart modelPart = bodyPart.getModelPart();
        
        // Check basic structure
        if (modelPart == null) {
            result.addError("Model part '" + partName + "' is null");
            return;
        }
        
        // Validate name
        if (partName == null || partName.trim().isEmpty()) {
            result.addError("Model part has null or empty name");
        }
        
        // Validate position data
        ModelDefinition.Position position = modelPart.getPosition();
        if (position == null) {
            result.addError("Model part '" + partName + "' has no position data");
        } else {
            if (Float.isNaN(position.getX()) || Float.isNaN(position.getY()) || Float.isNaN(position.getZ())) {
                result.addError("Model part '" + partName + "' has NaN position values");
            }
            if (Float.isInfinite(position.getX()) || Float.isInfinite(position.getY()) || Float.isInfinite(position.getZ())) {
                result.addError("Model part '" + partName + "' has infinite position values");
            }
        }
        
        // Validate size data
        ModelDefinition.Size size = modelPart.getSize();
        if (size == null) {
            result.addError("Model part '" + partName + "' has no size data");
        } else {
            if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
                result.addError("Model part '" + partName + "' has invalid size: (" + 
                               size.getX() + ", " + size.getY() + ", " + size.getZ() + ") - all dimensions must be positive");
            }
            if (Float.isNaN(size.getX()) || Float.isNaN(size.getY()) || Float.isNaN(size.getZ())) {
                result.addError("Model part '" + partName + "' has NaN size values");
            }
            if (Float.isInfinite(size.getX()) || Float.isInfinite(size.getY()) || Float.isInfinite(size.getZ())) {
                result.addError("Model part '" + partName + "' has infinite size values");
            }
        }
        
        // Test vertex generation (this is critical for rendering)
        if (position != null && size != null) {
            try {
                float[] vertices = modelPart.getVertices();
                if (vertices == null) {
                    result.addError("Model part '" + partName + "' getVertices() returns null");
                } else if (vertices.length == 0) {
                    result.addError("Model part '" + partName + "' has empty vertices array");
                } else if (vertices.length % 3 != 0) {
                    result.addError("Model part '" + partName + "' has invalid vertex data length (" + 
                                   vertices.length + " - must be multiple of 3)");
                } else {
                    result.addValidModelPart(partName, vertices.length / 3);
                    
                    // Check for NaN or infinite vertices
                    for (int i = 0; i < vertices.length; i++) {
                        if (Float.isNaN(vertices[i]) || Float.isInfinite(vertices[i])) {
                            result.addError("Model part '" + partName + "' has invalid vertex data at index " + i + ": " + vertices[i]);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                result.addError("Model part '" + partName + "' vertex generation failed: " + e.getMessage());
            }
            
            // Test index generation
            try {
                int[] indices = modelPart.getIndices();
                if (indices == null) {
                    result.addError("Model part '" + partName + "' getIndices() returns null");
                } else if (indices.length == 0) {
                    result.addError("Model part '" + partName + "' has empty indices array");
                } else if (indices.length % 3 != 0) {
                    result.addError("Model part '" + partName + "' has invalid indices data length (" + 
                                   indices.length + " - must be multiple of 3)");
                } else {
                    // Check for valid index ranges (if we have vertex data)
                    try {
                        float[] vertices = modelPart.getVertices();
                        if (vertices != null) {
                            int maxValidIndex = (vertices.length / 3) - 1;
                            for (int i = 0; i < indices.length; i++) {
                                if (indices[i] < 0 || indices[i] > maxValidIndex) {
                                    result.addError("Model part '" + partName + "' has out-of-range index at position " + i + 
                                                   ": " + indices[i] + " (valid range: 0-" + maxValidIndex + ")");
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Already reported vertex error above
                    }
                }
            } catch (Exception e) {
                result.addError("Model part '" + partName + "' index generation failed: " + e.getMessage());
            }
        }
        
        // Validate texture key
        String textureKey = modelPart.getTexture();
        if (textureKey == null || textureKey.trim().isEmpty()) {
            result.addWarning("Model part '" + partName + "' has no texture key defined");
        }
    }
    
    /**
     * Validates texture mappings and coordinates
     */
    private void validateTextureMappings(ValidationResult result) {
        if (textureDefinition == null) {
            result.addError("No texture definition available");
            return;
        }
        
        List<BodyPart> bodyParts = getBodyParts();
        
        // Check that all body parts have corresponding face mappings
        for (BodyPart bodyPart : bodyParts) {
            String partName = bodyPart.getName().toUpperCase();
            
            // Check for required face mappings per body part
            String[] requiredFaces = {"_FRONT", "_BACK", "_LEFT", "_RIGHT", "_TOP", "_BOTTOM"};
            for (String face : requiredFaces) {
                String faceKey = partName + face;
                if (!textureDefinition.getFaceMappings().containsKey(faceKey)) {
                    result.addWarning("Missing face mapping: " + faceKey);
                }
            }
        }
        
        // Validate texture atlas coordinates are within valid range (16x16 grid)
        for (Map.Entry<String, CowTextureDefinition.AtlasCoordinate> entry : textureDefinition.getFaceMappings().entrySet()) {
            CowTextureDefinition.AtlasCoordinate mapping = entry.getValue();
            if (mapping.getAtlasX() < 0 || mapping.getAtlasX() >= 16 || 
                mapping.getAtlasY() < 0 || mapping.getAtlasY() >= 16) {
                result.addError("Invalid texture coordinates for " + entry.getKey() + 
                              ": (" + mapping.getAtlasX() + ", " + mapping.getAtlasY() + ")");
            }
        }
        
        // Count face mappings
        result.setFaceMappingCount(textureDefinition.getFaceMappings().size());
    }

    // Getters
    public ModelDefinition.CowModelDefinition getModelDefinition() { return modelDefinition; }
    public CowTextureDefinition.CowVariant getTextureDefinition() { return textureDefinition; }
    public String getVariantName() { return variantName; }

    /**
     * Result of model validation containing errors, warnings, and statistics
     */
    public static class ValidationResult {
        private final List<String> errors = new java.util.ArrayList<>();
        private final List<String> warnings = new java.util.ArrayList<>();
        private final Map<String, Integer> validModelParts = new HashMap<>();
        private int faceMappingCount = 0;
        private int modelPartCount = 0;

        public void addError(String error) { errors.add(error); }
        public void addWarning(String warning) { warnings.add(warning); }
        public void setFaceMappingCount(int count) { this.faceMappingCount = count; }
        public void setModelPartCount(int count) { this.modelPartCount = count; }
        public void addValidModelPart(String partName, int vertexCount) { 
            validModelParts.put(partName, vertexCount); 
        }

        public boolean isValid() { return errors.isEmpty(); }
        public boolean hasModelPartErrors() {
            return errors.stream().anyMatch(error -> 
                error.contains("Model part") || error.contains("vertex") || error.contains("indices"));
        }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
        public int getFaceMappingCount() { return faceMappingCount; }
        public int getModelPartCount() { return modelPartCount; }
        public int getValidModelPartCount() { return validModelParts.size(); }
        public Map<String, Integer> getValidModelParts() { return new HashMap<>(validModelParts); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Model Validation Result ===\n");
            sb.append("Model Parts: ").append(getValidModelPartCount()).append("/").append(modelPartCount).append(" valid\n");
            sb.append("Face Mappings: ").append(faceMappingCount).append("\n");
            sb.append("Errors: ").append(errors.size()).append("\n");
            sb.append("Warnings: ").append(warnings.size()).append("\n");
            
            if (!validModelParts.isEmpty()) {
                sb.append("\nValid Model Parts:\n");
                for (Map.Entry<String, Integer> entry : validModelParts.entrySet()) {
                    sb.append("  ✓ ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" vertices\n");
                }
            }
            
            if (!errors.isEmpty()) {
                sb.append("\nError Details:\n");
                for (String error : errors) {
                    sb.append("  ✗ ").append(error).append("\n");
                }
            }
            
            if (!warnings.isEmpty()) {
                sb.append("\nWarning Details:\n");
                for (String warning : warnings) {
                    sb.append("  ⚠ ").append(warning).append("\n");
                }
            }
            
            sb.append("===============================");
            return sb.toString();
        }
    }
    
    /**
     * Wrapper class for model parts that provides a unified interface
     * for the buffer management system.
     */
    public static class BodyPart {
        private final ModelDefinition.ModelPart modelPart;
        
        public BodyPart(ModelDefinition.ModelPart modelPart) {
            this.modelPart = modelPart;
        }
        
        public String getName() {
            return modelPart.getName();
        }
        
        public String getTextureKey() {
            return modelPart.getTexture();
        }
        
        public BoundingBox getBounds() {
            return new BoundingBox(modelPart);
        }
        
        public ModelDefinition.ModelPart getModelPart() {
            return modelPart;
        }
        
        /**
         * Get the current transformation matrix for this body part.
         */
        public Matrix4f getTransformationMatrix() {
            return modelPart.getTransformationMatrix();
        }
        
        /**
         * Get the current position of this body part.
         */
        public Vector3f getPosition() {
            return modelPart.getPositionVector();
        }
        
        /**
         * Get the current rotation of this body part.
         */
        public Vector3f getRotation() {
            return modelPart.getRotation();
        }
        
        /**
         * Get the current scale of this body part.
         */
        public Vector3f getScale() {
            return modelPart.getScale();
        }
        
        /**
         * Matrix transformations are always enabled.
         * @return Always true
         */
        public boolean isUsingMatrixTransform() {
            return true;
        }
    }
    
    /**
     * Bounding box representation for model parts.
     */
    public static class BoundingBox {
        private final float minX, minY, minZ;
        private final float width, height, depth;
        
        public BoundingBox(ModelDefinition.ModelPart modelPart) {
            ModelDefinition.Position pos = modelPart.getPosition();
            ModelDefinition.Size size = modelPart.getSize();
            
            this.width = size.getX();
            this.height = size.getY();
            this.depth = size.getZ();
            
            // Calculate min coordinates (position is center, so subtract half-size)
            this.minX = pos.getX() - width / 2.0f;
            this.minY = pos.getY() - height / 2.0f;
            this.minZ = pos.getZ() - depth / 2.0f;
        }
        

        public float getWidth() { return width; }
        public float getHeight() { return height; }
        public float getDepth() { return depth; }

    }
}