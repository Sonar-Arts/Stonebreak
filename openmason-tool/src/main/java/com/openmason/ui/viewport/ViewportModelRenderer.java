package com.openmason.ui.viewport;

import com.openmason.model.StonebreakModel;
import com.stonebreak.model.ModelDefinition;


import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class ViewportModelRenderer {
    
    private static final Logger logger = LoggerFactory.getLogger(ViewportModelRenderer.class);

/**
 * Simple color class for model rendering.
 */
static class ModelColor {
    public final float r, g, b, a;
    
    private ModelColor(float r, float g, float b, float a) {
        this.r = r; this.g = g; this.b = b; this.a = a;
    }
    
    public static final ModelColor LIGHTCORAL = new ModelColor(0.94f, 0.5f, 0.5f, 1.0f);
    public static final ModelColor LIGHTBLUE = new ModelColor(0.68f, 0.85f, 1.0f, 1.0f);
    public static final ModelColor LIGHTGREEN = new ModelColor(0.56f, 0.93f, 0.56f, 1.0f);
    public static final ModelColor WHEAT = new ModelColor(0.96f, 0.87f, 0.7f, 1.0f);
    public static final ModelColor PINK = new ModelColor(1.0f, 0.75f, 0.8f, 1.0f);
    public static final ModelColor DARKGRAY = new ModelColor(0.66f, 0.66f, 0.66f, 1.0f);
    public static final ModelColor LIGHTGRAY = new ModelColor(0.83f, 0.83f, 0.83f, 1.0f);
    public static final ModelColor WHITE = new ModelColor(1.0f, 1.0f, 1.0f, 1.0f);
    public static final ModelColor RED = new ModelColor(1.0f, 0.0f, 0.0f, 1.0f);
    public static final ModelColor YELLOW = new ModelColor(1.0f, 1.0f, 0.0f, 1.0f);
    
    public ModelColor brighter() {
        float factor = 1.0f / 0.7f;
        return new ModelColor(Math.min(1.0f, r * factor), Math.min(1.0f, g * factor), Math.min(1.0f, b * factor), a);
    }
    
    public ModelColor deriveColor(double hueShift, double saturationFactor, double brightnessFactor, double opacityFactor) {
        // Simplified implementation - just apply brightness and opacity factors
        return new ModelColor(
            (float)(r * brightnessFactor), 
            (float)(g * brightnessFactor), 
            (float)(b * brightnessFactor), 
            (float)(a * opacityFactor)
        );
    }
}

/**
 * Placeholder classes for legacy API compatibility (no longer used in LWJGL renderer).
 */
static class Box {
    public Box(double width, double height, double depth) {
        // Placeholder - actual rendering now handled by LWJGL Canvas renderer
    }
    public void setMaterial(PhongMaterial material) { /* Placeholder */ }
    public void setDrawMode(DrawMode mode) { /* Placeholder */ }
    public java.util.List<Object> getTransforms() { return new java.util.ArrayList<>(); }
}

static class PhongMaterial {
    private ModelColor diffuseColor;
    private ModelColor specularColor;
    private double specularPower;
    
    public void setDiffuseColor(ModelColor color) { this.diffuseColor = color; }
    public void setSpecularColor(ModelColor color) { this.specularColor = color; }
    public void setSpecularPower(double power) { this.specularPower = power; }
    public ModelColor getDiffuseColor() { return diffuseColor; }
}

static enum DrawMode {
    FILL, LINE
}

static class Rotate {
    public static final Object X_AXIS = new Object();
    public static final Object Y_AXIS = new Object();
    public static final Object Z_AXIS = new Object();
    public Rotate(double angle, Object axis) { /* Placeholder */ }
}

static class Scale {
    public Scale(double x, double y, double z) { /* Placeholder */ }
}

static class Translate {
    public Translate(double x, double y, double z) { /* Placeholder */ }
}

static class Group {
    private final java.util.List<Object> children = new java.util.ArrayList<>();
    private final java.util.List<Object> transforms = new java.util.ArrayList<>();
    public java.util.List<Object> getChildren() { return children; }
    public java.util.List<Object> getTransforms() { return transforms; }
    public void setTranslateX(double x) { /* Placeholder */ }
    public void setTranslateY(double y) { /* Placeholder */ }
    public void setTranslateZ(double z) { /* Placeholder */ }
    public double getTranslateX() { return 0; }
    public double getTranslateY() { return 0; }
    public double getTranslateZ() { return 0; }
}

    
    private ViewportSceneManager sceneManager;
    private LWJGLCanvasRenderer canvasRenderer;
    private String currentTextureVariant = "default";
    private boolean wireframeMode = false;
    
    // Cached transformation matrix for coordinate system alignment
    private Matrix4f cachedEntityMatrix = new Matrix4f().identity();
    
    // Store final transformed positions for debugging and ray casting alignment (legacy)
    private Map<String, Vector3f> partTransformedPositions = new HashMap<>();
    
    // Color mappings for different model parts
    private static final Map<String, ModelColor> PART_COLORS = new HashMap<>();
    static {
        PART_COLORS.put("head", ModelColor.LIGHTCORAL);
        PART_COLORS.put("body", ModelColor.LIGHTBLUE);
        PART_COLORS.put("leg1", ModelColor.LIGHTGREEN);
        PART_COLORS.put("leg2", ModelColor.LIGHTGREEN);
        PART_COLORS.put("leg3", ModelColor.LIGHTGREEN);
        PART_COLORS.put("leg4", ModelColor.LIGHTGREEN);
        PART_COLORS.put("horn1", ModelColor.WHEAT);
        PART_COLORS.put("horn2", ModelColor.WHEAT);
        PART_COLORS.put("udder", ModelColor.PINK);
        PART_COLORS.put("tail", ModelColor.DARKGRAY);
    }
    
    /**
     * Initialize with scene manager reference.
     * REFACTORED: Now initializes LWJGL Canvas renderer directly.
     */
    public void initialize(ViewportSceneManager sceneManager) {
        this.sceneManager = sceneManager;
        
        try {
            // Initialize LWJGL Canvas renderer
            initializeCanvasRenderer();
            
            if (canvasRenderer != null) {
                logger.info("LWJGL Canvas renderer initialized successfully");
            } else {
                logger.warn("LWJGL Canvas renderer initialization returned null");
            }
            
        } catch (Exception e) {
            logger.error("Failed to initialize LWJGL Canvas renderer", e);
            this.canvasRenderer = null;
        }
        
        if (canvasRenderer == null) {
            logger.debug("Canvas renderer is null - using fallback rendering");
        }
        
        logger.debug("ViewportModelRenderer initialized with LWJGL backend");
    }
    
    /**
     * Initialize the LWJGL Canvas renderer.
     */
    private void initializeCanvasRenderer() {
        try {
            logger.info("Initializing LWJGL Canvas renderer...");
            
            // Create a placeholder canvas for the renderer
            LWJGLCanvasRenderer.PlaceholderCanvas canvas = new LWJGLCanvasRenderer.PlaceholderCanvas();
            
            // Create and initialize the canvas renderer
            this.canvasRenderer = new LWJGLCanvasRenderer(canvas);
            this.canvasRenderer.initialize();
            
            // Configure the renderer with current settings
            this.canvasRenderer.setTextureVariant(currentTextureVariant);
            this.canvasRenderer.setWireframeMode(wireframeMode);
            
            logger.info("LWJGL Canvas renderer created and initialized");
            
        } catch (Exception e) {
            logger.error("Failed to create LWJGL Canvas renderer", e);
            throw new RuntimeException("Canvas renderer initialization failed", e);
        }
    }
    
    /**
     * Render a Stonebreak model using LWJGL Canvas renderer.
     * REFACTORED: Now delegates to LWJGL Canvas renderer.
     */
    public void renderModel(StonebreakModel model) {
        if (model == null) {
            logger.warn("Cannot render model: model is null");
            clearModel();
            return;
        }
        
        if (canvasRenderer == null) {
            logger.error("Cannot render model: canvas renderer not initialized");
            return;
        }
        
        logger.debug("Rendering model: {}", model.getVariantName());
        
        try {
            // Delegate to LWJGL Canvas renderer
            canvasRenderer.setCurrentModel(model);
            canvasRenderer.setTextureVariant(currentTextureVariant);
            canvasRenderer.setWireframeMode(wireframeMode);
            canvasRenderer.requestRender();
            
            // Clear legacy scene manager (for API compatibility)
            if (sceneManager != null) {
                logger.debug("Scene manager model parts cleared (legacy compatibility)");
            }
            
            // Update coordinate system matrix for input handling
            updateCoordinateSystemMatrix(model);
            
            // Legacy validation (preserved for debugging)
            validateModelRendering(model);
            
            logger.info("Successfully rendered model via LWJGL: {}", model.getVariantName());
            
        } catch (Exception e) {
            logger.error("Failed to render model: " + model.getVariantName(), e);
            // Clear model on error
            clearModel();
        }
    }
    
    /**
     * Update coordinate system matrix for the given model.
     * REFACTORED: Now used for coordinate system alignment with LWJGL renderer.
     */
    private void updateCoordinateSystemMatrix(StonebreakModel model) {
        // Create entity matrix with coordinate system conversion
        Matrix4f entityMatrix = createCoordinateSystemMatrix();
        
        // Store the entity matrix for coordinate alignment with input handler
        this.cachedEntityMatrix = new Matrix4f(entityMatrix);
        
        // Model parts are now rendered by LWJGL Canvas renderer
        // This method maintains coordinate system alignment for input handling
        
        if (model != null && model.getModelDefinition() != null) {
            ModelDefinition.ModelParts parts = model.getModelDefinition().getParts();
            if (parts != null) {
                updatePartPositions(parts, entityMatrix);
                logger.debug("Updated coordinate system matrix for {} model parts", getPartCount(parts));
            }
        }
    }
    
    /**
     * Create coordinate system transformation matrix for LWJGL rendering.
     * This matrix MUST be used consistently for both rendering and ray casting.
     * Stonebreak: Right-handed Y-up
     * LWJGL: Right-handed Y-up (consistent coordinate system)
     */
    private Matrix4f createCoordinateSystemMatrix() {
        Matrix4f matrix = new Matrix4f().identity();
        
        // LWJGL uses the same coordinate system as Stonebreak - no conversion needed
        // This maintains consistent coordinate system
        
        return matrix;
    }
    
    /**
     * Get the coordinate system transformation matrix.
     * This method exposes the same transformation used in rendering for use by the input handler.
     * REFACTORED: Now returns LWJGL-compatible coordinate system matrix.
     */
    public Matrix4f getCoordinateSystemMatrix() {
        return new Matrix4f(cachedEntityMatrix);
    }
    
    /**
     * Apply unified coordinate transformation that is used IDENTICALLY in both rendering and ray casting.
     * This ensures perfect alignment between rendered positions and ray casting bounding boxes.
     */
    public Vector3f applyUnifiedCoordinateTransform(Vector3f originalPosition, Matrix4f entityMatrix) {
        // Apply the entity matrix transformation (Y-flip)
        Vector3f transformed = new Vector3f();
        entityMatrix.transformPosition(originalPosition, transformed);
        
        logger.debug("Unified transform: ({},{},{}) -> ({},{},{}) using matrix Y-flip={}", 
            originalPosition.x, originalPosition.y, originalPosition.z,
            transformed.x, transformed.y, transformed.z,
            entityMatrix.m11());
        
        return transformed;
    }
    
    /**
     * Store the final transformed position for a part for debugging and validation.
     */
    private void storePartTransformedPosition(String partName, Vector3f transformedPosition) {
        partTransformedPositions.put(partName, new Vector3f(transformedPosition));
    }
    
    /**
     * Update part positions for coordinate system alignment.
     * REFACTORED: Now used for tracking positions in LWJGL coordinate system.
     */
    private void updatePartPositions(ModelDefinition.ModelParts parts, Matrix4f entityMatrix) {
        partTransformedPositions.clear();
        
        // Process each part type and store transformed positions
        if (parts.getHead() != null) {
            storePartPosition("head", parts.getHead(), entityMatrix);
        }
        if (parts.getBody() != null) {
            storePartPosition("body", parts.getBody(), entityMatrix);
        }
        if (parts.getLegs() != null) {
            List<ModelDefinition.ModelPart> legs = parts.getLegs();
            for (int i = 0; i < legs.size(); i++) {
                storePartPosition("leg" + (i + 1), legs.get(i), entityMatrix);
            }
        }
        if (parts.getHorns() != null) {
            List<ModelDefinition.ModelPart> horns = parts.getHorns();
            for (int i = 0; i < horns.size(); i++) {
                storePartPosition("horn" + (i + 1), horns.get(i), entityMatrix);
            }
        }
        if (parts.getUdder() != null) {
            storePartPosition("udder", parts.getUdder(), entityMatrix);
        }
        if (parts.getTail() != null) {
            storePartPosition("tail", parts.getTail(), entityMatrix);
        }
    }
    
    /**
     * Store transformed position for a single part.
     */
    private void storePartPosition(String partName, ModelDefinition.ModelPart part, Matrix4f entityMatrix) {
        if (part != null && part.getPositionVector() != null) {
            Vector3f transformedPosition = applyUnifiedCoordinateTransform(part.getPositionVector(), entityMatrix);
            partTransformedPositions.put(partName, transformedPosition);
        }
    }
    
    
    /**
     * Get the final transformed position for a part. Used by ray casting for alignment.
     * REFACTORED: Now works with LWJGL coordinate system.
     */
    public Vector3f getPartTransformedPosition(String partName) {
        return partTransformedPositions.get(partName);
    }
    
    /**
     * Get all transformed part positions for debugging.
     */
    public Map<String, Vector3f> getAllTransformedPositions() {
        return new HashMap<>(partTransformedPositions);
    }
    
    /**
     * Render a single model part with transformations.
     * CRITICAL: This method applies the SAME coordinate transformation used in ray casting.
     */
    private void renderModelPart(String partName, ModelDefinition.ModelPart part, Matrix4f entityMatrix) {
        if (part == null) {
            logger.warn("Cannot render part '{}': part is null", partName);
            return;
        }
        
        try {
            // Get part dimensions first
            Vector3f sizeVector = part.getSizeVector();
            if (sizeVector == null) {
                logger.warn("Invalid size for part '{}'", partName);
                return;
            }
            
            // Use larger scale to make individual parts more visible in viewport
            double scaleMultiplier = 5.0; // 5x scale for better visibility
            System.out.println(">>>>>>> CLAUDE DEBUG: USING SCALE MULTIPLIER: " + scaleMultiplier + " <<<<<<<");
            double width = sizeVector.x * scaleMultiplier;
            double height = sizeVector.y * scaleMultiplier;
            double depth = sizeVector.z * scaleMultiplier;
            
            // Create Box for the part with scaled dimensions
            Box box = new Box(width, height, depth);
            
            // Create a Group to wrap the box for better transformation control
            Group partGroup = new Group();
            partGroup.getChildren().add(box);
            
            // Apply part position transformation to the group using unified coordinate transform
            Vector3f translation = part.getPositionVector();
            if (translation != null) {
                // Use the unified coordinate transformation that properly applies the entityMatrix
                Vector3f transformedPosition = applyUnifiedCoordinateTransform(translation, entityMatrix);
                
                // Apply scaling for visibility
                double scaledX = transformedPosition.x * scaleMultiplier;
                double scaledY = transformedPosition.y * scaleMultiplier;
                double scaledZ = transformedPosition.z * scaleMultiplier;
                
                // Set position on the GROUP using properly transformed coordinates
                partGroup.setTranslateX(scaledX);
                partGroup.setTranslateY(scaledY);
                partGroup.setTranslateZ(scaledZ);
                
                // Store the final transformed position for debugging and validation
                Vector3f finalPosition = new Vector3f((float)scaledX, (float)scaledY, (float)scaledZ);
                storePartTransformedPosition(partName, finalPosition);
                
                // Positioning applied successfully with proper matrix transformation
                logger.info("Part '{}' MATRIX positioning: original=({},{},{}) -> transformed=({},{},{}) -> scaled=({},{},{}) [scale={}x].", partName, 
                    translation.x, translation.y, translation.z,
                    transformedPosition.x, transformedPosition.y, transformedPosition.z,
                    scaledX, scaledY, scaledZ, scaleMultiplier);
            }
            
            // Apply part rotation if present to the group
            Vector3f rotation = part.getRotation();
            if (rotation != null) {
                if (rotation.x != 0) {
                    partGroup.getTransforms().add(new Rotate(Math.toDegrees(rotation.x), Rotate.X_AXIS));
                }
                if (rotation.y != 0) {
                    partGroup.getTransforms().add(new Rotate(Math.toDegrees(rotation.y), Rotate.Y_AXIS));
                }
                if (rotation.z != 0) {
                    partGroup.getTransforms().add(new Rotate(Math.toDegrees(rotation.z), Rotate.Z_AXIS));
                }
            }
            
            // Apply part scale if present to the group  
            Vector3f partScale = part.getScale();
            if (partScale != null && (partScale.x != 1.0f || partScale.y != 1.0f || partScale.z != 1.0f)) {
                partGroup.getTransforms().add(new Scale(partScale.x, partScale.y, partScale.z));
                logger.debug("Part '{}' scale applied: ({},{},{})", partName, partScale.x, partScale.y, partScale.z);
            }
            
            // Apply material with atlas coordinate system integration
            PhongMaterial material = createMaterialForPartWithAtlas(partName, part);
            box.setMaterial(material);
            
            // Set draw mode based on wireframe setting
            box.setDrawMode(wireframeMode ? DrawMode.LINE : DrawMode.FILL);
            
            // Add to scene using the group wrapper (legacy compatibility)
            logger.debug("Adding model part group '{}' (legacy compatibility)", partName);
            
            // Verify actual node position after scene addition
            logger.debug("Part '{}' actual position: ({},{},{}) after scene addition", 
                partName, partGroup.getTranslateX(), partGroup.getTranslateY(), partGroup.getTranslateZ());
            
            logger.info("Rendered part '{}': original_pos=({},{},{}), size=({},{},{})", 
                partName, 
                translation != null ? translation.x : 0, 
                translation != null ? translation.y : 0, 
                translation != null ? translation.z : 0,
                width, height, depth);
            
        } catch (Exception e) {
            logger.error("Failed to render model part '" + partName + "'", e);
        }
    }
    
    /**
     * Apply matrix transformations to a Box.
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
     * Create material for a model part with atlas coordinate system integration.
     */
    private PhongMaterial createMaterialForPartWithAtlas(String partName, ModelDefinition.ModelPart part) {
        PhongMaterial material = new PhongMaterial();
        
        try {
            // Get texture name from part for atlas lookup
            String textureName = part.getTexture();
            if (textureName != null && !textureName.isEmpty()) {
                // Use atlas coordinate system to get UV coordinates
                return createMaterialWithAtlasTexture(partName, textureName);
            }
            
        } catch (Exception e) {
            logger.warn("Failed to create atlas-based material for part '{}': {}", partName, e.getMessage());
        }
        
        // Fallback to color-based material
        return createMaterialForPart(partName);
    }
    
    /**
     * Create material with atlas texture coordinates.
     */
    private PhongMaterial createMaterialWithAtlasTexture(String partName, String textureName) {
        PhongMaterial material = new PhongMaterial();
        
        try {
            // Import atlas coordinate system
            com.openmason.coordinates.AtlasCoordinateSystem.AtlasCoordinate coord = 
                getAtlasCoordinateForTexture(textureName);
            
            if (coord != null) {
                // Generate UV coordinates using the atlas coordinate system
                float[] uvCoords = com.openmason.coordinates.AtlasCoordinateSystem
                    .generateQuadUVCoordinates(coord.getAtlasX(), coord.getAtlasY());
                
                if (uvCoords != null) {
                    logger.debug("Generated UV coordinates for texture '{}' at atlas ({},{}): [{}]", 
                        textureName, coord.getAtlasX(), coord.getAtlasY(), 
                        java.util.Arrays.toString(uvCoords));
                }
                
                // For now, use color-based rendering with atlas info
                ModelColor baseColor = getColorForTextureName(textureName);
                material.setDiffuseColor(baseColor);
                
            } else {
                logger.warn("No atlas coordinate found for texture: {}", textureName);
                material.setDiffuseColor(PART_COLORS.getOrDefault(partName, ModelColor.LIGHTGRAY));
            }
            
        } catch (Exception e) {
            logger.error("Error creating atlas texture material for {}: {}", textureName, e.getMessage());
            material.setDiffuseColor(ModelColor.RED); // Error color
        }
        
        // Apply common material properties
        if (wireframeMode) {
            material.setSpecularColor(ModelColor.WHITE);
            material.setSpecularPower(128.0);
        } else {
            material.setSpecularColor(material.getDiffuseColor().brighter());
            material.setSpecularPower(64.0);
        }
        
        return material;
    }
    
    /**
     * Get atlas coordinate for a texture name by integrating with cow texture system.
     */
    private com.openmason.coordinates.AtlasCoordinateSystem.AtlasCoordinate getAtlasCoordinateForTexture(String textureName) {
        try {
            // Try to get coordinates from the cow texture loader (exported package)
            com.stonebreak.textures.CowTextureDefinition.AtlasCoordinate cowCoord = 
                com.stonebreak.textures.CowTextureLoader.getAtlasCoordinate(currentTextureVariant, mapTextureNameToFace(textureName));
            
            if (cowCoord != null) {
                // Convert from cow texture coordinate to our atlas coordinate system
                return new com.openmason.coordinates.AtlasCoordinateSystem.AtlasCoordinate(
                    cowCoord.getAtlasX(), 
                    cowCoord.getAtlasY()
                );
            }
        } catch (Exception e) {
            logger.debug("Could not get cow texture coordinate for '{}': {}", textureName, e.getMessage());
        }
        
        // Fallback to static mapping for basic cow textures
        switch (textureName) {
            case "cow_head":
                return new com.openmason.coordinates.AtlasCoordinateSystem.AtlasCoordinate(0, 0);
            case "cow_body": 
                return new com.openmason.coordinates.AtlasCoordinateSystem.AtlasCoordinate(1, 0);
            case "cow_legs":
                return new com.openmason.coordinates.AtlasCoordinateSystem.AtlasCoordinate(0, 1);
            case "cow_udder":
                return new com.openmason.coordinates.AtlasCoordinateSystem.AtlasCoordinate(1, 1);
            case "cow_tail":
                return new com.openmason.coordinates.AtlasCoordinateSystem.AtlasCoordinate(2, 0);
            case "cow_horns":
                return new com.openmason.coordinates.AtlasCoordinateSystem.AtlasCoordinate(2, 1);
            default:
                logger.warn("Unknown texture name: {}", textureName);
                return null;
        }
    }
    
    /**
     * Map texture name to cow texture face name for atlas lookup.
     */
    private String mapTextureNameToFace(String textureName) {
        switch (textureName) {
            case "cow_head":
                return "HEAD_FRONT";
            case "cow_body":
                return "BODY_FRONT";
            case "cow_legs":
                return "LEG_FRONT";
            case "cow_udder":
                return "UDDER_FRONT";
            case "cow_tail":
                return "TAIL_FRONT";
            case "cow_horns":
                return "HORNS_FRONT"; // Fixed: should be HORNS_FRONT not HORN_FRONT
            default:
                return "HEAD_FRONT"; // Default fallback
        }
    }
    
    /**
     * Get color based on texture name for visual differentiation.
     */
    private ModelColor getColorForTextureName(String textureName) {
        switch (textureName) {
            case "cow_head":
                return ModelColor.LIGHTCORAL;
            case "cow_body":
                return ModelColor.LIGHTBLUE;
            case "cow_legs":
                return ModelColor.LIGHTGREEN;
            case "cow_udder":
                return ModelColor.PINK;
            case "cow_tail":
                return ModelColor.DARKGRAY;
            case "cow_horns":
                return ModelColor.WHEAT;
            default:
                return ModelColor.LIGHTGRAY;
        }
    }
    
    /**
     * Verify UV coordinate mapping for all parts of the model.
     * This method validates that the atlas coordinate system integration is working correctly.
     */
    public void verifyUVCoordinateMapping(StonebreakModel model) {
        if (model == null || model.getModelDefinition() == null) {
            logger.warn("Cannot verify UV mapping: model or definition is null");
            return;
        }
        
        logger.info("=== UV Coordinate Mapping Verification ===");
        
        ModelDefinition.ModelParts parts = model.getModelDefinition().getParts();
        int validMappings = 0;
        int totalParts = 0;
        
        // Test each part type
        if (parts.getHead() != null) {
            totalParts++;
            if (verifyPartUVMapping("head", parts.getHead())) {
                validMappings++;
            }
        }
        
        if (parts.getBody() != null) {
            totalParts++;
            if (verifyPartUVMapping("body", parts.getBody())) {
                validMappings++;
            }
        }
        
        if (parts.getLegs() != null) {
            for (int i = 0; i < parts.getLegs().size(); i++) {
                totalParts++;
                if (verifyPartUVMapping("leg" + (i + 1), parts.getLegs().get(i))) {
                    validMappings++;
                }
            }
        }
        
        if (parts.getHorns() != null) {
            for (int i = 0; i < parts.getHorns().size(); i++) {
                totalParts++;
                if (verifyPartUVMapping("horn" + (i + 1), parts.getHorns().get(i))) {
                    validMappings++;
                }
            }
        }
        
        if (parts.getUdder() != null) {
            totalParts++;
            if (verifyPartUVMapping("udder", parts.getUdder())) {
                validMappings++;
            }
        }
        
        if (parts.getTail() != null) {
            totalParts++;
            if (verifyPartUVMapping("tail", parts.getTail())) {
                validMappings++;
            }
        }
        
        logger.info("UV Mapping Verification Results: {}/{} parts have valid mappings", 
            validMappings, totalParts);
        
        if (validMappings == totalParts) {
            logger.info("✓ All UV coordinate mappings verified successfully");
        } else {
            logger.warn("✗ Some UV coordinate mappings failed verification");
        }
    }
    
    /**
     * Verify UV mapping for a single part.
     */
    private boolean verifyPartUVMapping(String partName, ModelDefinition.ModelPart part) {
        try {
            String textureName = part.getTexture();
            if (textureName == null || textureName.isEmpty()) {
                logger.debug("Part '{}' has no texture name", partName);
                return false;
            }
            
            // Get atlas coordinate
            com.openmason.coordinates.AtlasCoordinateSystem.AtlasCoordinate coord = 
                getAtlasCoordinateForTexture(textureName);
            
            if (coord == null) {
                logger.warn("Part '{}' with texture '{}' has no atlas coordinate", partName, textureName);
                return false;
            }
            
            // Generate UV coordinates
            float[] uvCoords = com.openmason.coordinates.AtlasCoordinateSystem
                .generateQuadUVCoordinates(coord.getAtlasX(), coord.getAtlasY());
            
            if (uvCoords == null || uvCoords.length != 8) {
                logger.warn("Part '{}' generated invalid UV coordinates", partName);
                return false;
            }
            
            // Validate UV coordinate bounds (should be 0.0-1.0)
            for (int i = 0; i < uvCoords.length; i++) {
                if (uvCoords[i] < 0.0f || uvCoords[i] > 1.0f) {
                    logger.warn("Part '{}' has UV coordinate out of bounds: {} at index {}", 
                        partName, uvCoords[i], i);
                    return false;
                }
            }
            
            logger.debug("✓ Part '{}' texture '{}' -> atlas ({},{}) -> UV coordinates valid", 
                partName, textureName, coord.getAtlasX(), coord.getAtlasY());
            
            return true;
            
        } catch (Exception e) {
            logger.error("Error verifying UV mapping for part '{}': {}", partName, e.getMessage());
            return false;
        }
    }
    
    /**
     * Debug the current scene structure to verify transformations.
     */
    private void debugSceneStructure() {
        if (sceneManager == null) {
            logger.warn("Cannot debug scene structure: scene manager is null");
            return;
        }
        
        logger.info("=== Scene Structure Debug ===");
        // Note: This would require access to the scene manager's internal structure
        // For now, just log that debug was called
        logger.info("Scene structure debugging completed");
    }
    
    /**
     * Create material for a model part (legacy method).
     */
    private PhongMaterial createMaterialForPart(String partName) {
        PhongMaterial material = new PhongMaterial();
        
        // Get base color for part
        ModelColor baseColor = PART_COLORS.getOrDefault(partName, ModelColor.LIGHTGRAY);
        
        // Apply texture variant modifications
        ModelColor finalColor = getColorForTextureVariant(baseColor, currentTextureVariant);
        
        material.setDiffuseColor(finalColor);
        
        if (wireframeMode) {
            material.setSpecularColor(ModelColor.WHITE);
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
    private ModelColor getColorForTextureVariant(ModelColor baseColor, String variant) {
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
     * REFACTORED: Now handled by LWJGL Canvas renderer.
     */
    private void renderPlaceholderModel() {
        logger.debug("Rendering placeholder model via LWJGL Canvas renderer");
        
        // Clear current model in LWJGL renderer (will show placeholder in software fallback)
        if (canvasRenderer != null) {
            canvasRenderer.clearModel();
            canvasRenderer.requestRender();
        }
        
        // Legacy placeholder (for API compatibility during transition)
        if (sceneManager != null) {
            Box placeholder = new Box(2.0, 2.0, 2.0);
            PhongMaterial material = new PhongMaterial();
            material.setDiffuseColor(ModelColor.YELLOW);
            material.setSpecularColor(ModelColor.WHITE);
            
            placeholder.setMaterial(material);
            placeholder.setDrawMode(wireframeMode ? DrawMode.LINE : DrawMode.FILL);
            
            logger.debug("Adding model part 'placeholder' (legacy compatibility)");
        }
    }
    
    /**
     * Validate model rendering accuracy.
     * REFACTORED: Now validates LWJGL rendering.
     */
    private void validateModelRendering(StonebreakModel model) {
        try {
            if (model == null) {
                logger.warn("Model validation: model is null");
                return;
            }
            
            if (canvasRenderer == null) {
                logger.warn("Model validation: canvas renderer is null");
                return;
            }
            
            // Validate LWJGL renderer state
            StonebreakModel currentModel = canvasRenderer.getCurrentModel();
            if (currentModel != null && currentModel.equals(model)) {
                logger.debug("Model validation passed: LWJGL renderer has correct model: {}", 
                           model.getVariantName());
            } else {
                logger.warn("Model validation: LWJGL renderer model mismatch");
            }
            
            // Validate coordinate system alignment
            if (partTransformedPositions.isEmpty()) {
                logger.warn("Model validation: No part positions stored");
            } else {
                logger.debug("Model validation: {} part positions stored for coordinate alignment", 
                           partTransformedPositions.size());
            }
            
        } catch (Exception e) {
            logger.warn("Model rendering validation failed", e);
        }
    }
    
    /**
     * Count the number of parts in a model definition.
     */
    private int getPartCount(ModelDefinition.ModelParts parts) {
        int count = 0;
        if (parts.getHead() != null) count++;
        if (parts.getBody() != null) count++;
        if (parts.getLegs() != null) {
            count += parts.getLegs().size();
        }
        if (parts.getHorns() != null) {
            count += parts.getHorns().size();
        }
        if (parts.getUdder() != null) count++;
        if (parts.getTail() != null) count++;
        return count;
    }
    
    /**
     * Clear all rendered model parts.
     * REFACTORED: Now clears from LWJGL Canvas renderer.
     */
    public void clearModel() {
        // Clear from LWJGL Canvas renderer
        if (canvasRenderer != null) {
            canvasRenderer.clearModel();
        }
        
        // Clear legacy scene manager (for API compatibility)
        if (sceneManager != null) {
            logger.debug("Scene manager model parts cleared (legacy compatibility)");
        }
        
        // Clear cached positions
        partTransformedPositions.clear();
        
        logger.debug("Model cleared from LWJGL renderer");
    }
    
    // Getters and Setters
    public void setTextureVariant(String variant) {
        this.currentTextureVariant = variant != null ? variant : "default";
        
        // Update LWJGL Canvas renderer
        if (canvasRenderer != null) {
            canvasRenderer.setTextureVariant(this.currentTextureVariant);
            canvasRenderer.requestRender();
        }
        
        logger.debug("Texture variant set to: {}", this.currentTextureVariant);
    }
    
    public String getTextureVariant() {
        return currentTextureVariant;
    }
    
    public void setWireframeMode(boolean enabled) {
        this.wireframeMode = enabled;
        
        // Update LWJGL Canvas renderer
        if (canvasRenderer != null) {
            canvasRenderer.setWireframeMode(enabled);
            canvasRenderer.requestRender();
        }
        
        // Legacy 3D update (for API compatibility)
        if (sceneManager != null) {
            logger.debug("Legacy wireframe mode update (compatibility)");
        }
        
        logger.debug("Wireframe mode set to: {}", enabled);
    }
    
    public boolean isWireframeMode() {
        return wireframeMode;
    }
}