package com.openmason.model;

import com.stonebreak.model.ModelDefinition;
import com.stonebreak.model.ModelLoader;
import com.stonebreak.textures.CowTextureDefinition;
import com.stonebreak.textures.CowTextureLoader;
import com.openmason.model.ModelManager;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * Integration bridge between Stonebreak game data and Open Mason UI.
 * Combines model definition and texture data into a unified interface
 * for the 3D model development tool.
 */
public class StonebreakModel {
    private final ModelDefinition.CowModelDefinition modelDefinition;
    private final CowTextureDefinition.CowVariant textureDefinition;
    private final String variantName;

    public StonebreakModel(ModelDefinition.CowModelDefinition modelDefinition, 
                          CowTextureDefinition.CowVariant textureDefinition,
                          String variantName) {
        this.modelDefinition = modelDefinition;
        this.textureDefinition = textureDefinition;
        this.variantName = variantName;
    }
    
    /**
     * Constructor for ModelManager integration (with default texture).
     */
    public StonebreakModel(ModelManager.ModelInfo modelInfo, ModelDefinition.ModelPart[] parts) {
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
     * Factory method to create a StonebreakModel from resource files
     */
    public static StonebreakModel loadFromResources(String modelPath, String texturePath, String variantName) {
        try {
            ModelDefinition.CowModelDefinition model = ModelLoader.getCowModel(modelPath);
            CowTextureDefinition.CowVariant texture = CowTextureLoader.getCowVariant(variantName);
            
            return new StonebreakModel(model, texture, variantName);
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
     * Validate that the model and texture data are consistent
     */
    public ValidationResult validate() {
        ValidationResult result = new ValidationResult();
        
        // Check that all body parts have corresponding face mappings
        List<BodyPart> bodyParts = getBodyParts();
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
        
        return result;
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
        private int faceMappingCount = 0;

        public void addError(String error) { errors.add(error); }
        public void addWarning(String warning) { warnings.add(warning); }
        public void setFaceMappingCount(int count) { this.faceMappingCount = count; }

        public boolean isValid() { return errors.isEmpty(); }
        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
        public int getFaceMappingCount() { return faceMappingCount; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Validation Result:\n");
            sb.append("  Face Mappings: ").append(faceMappingCount).append("\n");
            sb.append("  Errors: ").append(errors.size()).append("\n");
            sb.append("  Warnings: ").append(warnings.size()).append("\n");
            
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
        
        public float getMinX() { return minX; }
        public float getMinY() { return minY; }
        public float getMinZ() { return minZ; }
        public float getWidth() { return width; }
        public float getHeight() { return height; }
        public float getDepth() { return depth; }
        
        public float getMaxX() { return minX + width; }
        public float getMaxY() { return minY + height; }
        public float getMaxZ() { return minZ + depth; }
    }
}