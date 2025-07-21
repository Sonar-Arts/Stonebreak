package com.stonebreak.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonebreak.model.ModelDefinition.*;
import org.joml.Vector3f;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSON model loader for cow models.
 * Follows the same pattern as CowTextureLoader.java for consistency.
 */
public class ModelLoader {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Map<String, CowModelDefinition> cachedModels = new ConcurrentHashMap<>();
    
    // Paths to model definition JSON files
    private static final Map<String, String> MODEL_FILE_PATHS = Map.of(
        "standard_cow", "models/cow/standard_cow.json"
        // Future cow variants can be added here
    );
    
    /**
     * Gets a cow model definition, loading and caching it if necessary.
     */
    public static CowModelDefinition getCowModel(String modelName) {
        // Check if model is already cached
        CowModelDefinition cached = cachedModels.get(modelName);
        if (cached != null) {
            return cached;
        }
        
        // Attempt to load the model
        try {
            CowModelDefinition model = loadModel(modelName);
            if (model != null) {
                cachedModels.put(modelName, model);
                System.out.println("[ModelLoader] Successfully cached model: " + modelName);
                return model;
            }
        } catch (IOException e) {
            System.err.println("[ModelLoader] Failed to load model '" + modelName + "': " + e.getMessage());
        }
        
        // Fallback to standard_cow model
        if (!"standard_cow".equals(modelName)) {
            System.err.println("[ModelLoader] Using fallback standard_cow model for failed model: " + modelName);
            return getCowModel("standard_cow");
        }
        
        // If standard_cow model itself fails, return null
        System.err.println("[ModelLoader] Critical error: standard_cow model failed to load");
        return null;
    }
    
    /**
     * Load a model definition from its JSON file.
     */
    public static CowModelDefinition loadModel(String modelName) throws IOException {
        String filePath = MODEL_FILE_PATHS.get(modelName);
        if (filePath == null) {
            throw new IOException("Unknown cow model: " + modelName + ". Available models: " + MODEL_FILE_PATHS.keySet());
        }
        
        try (InputStream inputStream = ModelLoader.class.getClassLoader().getResourceAsStream(filePath)) {
            if (inputStream == null) {
                throw new IOException("Could not find resource: " + filePath);
            }
            
            CowModelDefinition model = objectMapper.readValue(inputStream, CowModelDefinition.class);
            
            // Validate the loaded model
            validateModel(model, modelName);
            
            System.out.println("[ModelLoader] Successfully loaded cow model '" + modelName + "' (" + 
                model.getDisplayName() + ") with " + countTotalParts(model) + " parts and " + 
                model.getAnimations().size() + " animations");
            
            return model;
        }
    }
    
    /**
     * Gets animated model parts for a specific animation and time.
     */
    public static ModelPart[] getAnimatedParts(String modelName, String animationName, float animationTime) {
        CowModelDefinition model = getCowModel(modelName);
        if (model == null) {
            System.err.println("[ModelLoader] Cannot get animated parts: model '" + modelName + "' not found");
            return new ModelPart[0];
        }
        
        // Get base model parts
        ModelPart[] parts = getAllParts(model);
        
        // Apply animation if it exists
        ModelAnimation animation = model.getAnimations().get(animationName);
        if (animation != null) {
            applyAnimation(parts, animation, animationTime);
        } else {
            System.err.println("[ModelLoader] Animation '" + animationName + "' not found in model '" + modelName + "'");
            System.err.println("  Available animations: " + model.getAnimations().keySet());
        }
        
        return parts;
    }
    
    /**
     * Gets all model parts in the correct order (compatible with existing rendering system).
     */
    public static ModelPart[] getAllParts(CowModelDefinition model) {
        if (model == null || model.getParts() == null) {
            return new ModelPart[0];
        }
        
        ModelParts parts = model.getParts();
        ModelPart[] allParts = new ModelPart[10]; // body, head, 4 legs, 2 horns, udder, tail
        
        // Body and head
        allParts[0] = parts.getBody() != null ? parts.getBody().copy() : createDefaultPart("body");
        allParts[1] = parts.getHead() != null ? parts.getHead().copy() : createDefaultPart("head");
        
        // Legs (4)
        if (parts.getLegs() != null && parts.getLegs().size() >= 4) {
            for (int i = 0; i < 4; i++) {
                allParts[2 + i] = parts.getLegs().get(i).copy();
            }
        } else {
            System.err.println("[ModelLoader] Warning: Model missing leg parts, using defaults");
            for (int i = 0; i < 4; i++) {
                allParts[2 + i] = createDefaultPart("leg" + (i + 1));
            }
        }
        
        // Horns (2)
        if (parts.getHorns() != null && parts.getHorns().size() >= 2) {
            for (int i = 0; i < 2; i++) {
                allParts[6 + i] = parts.getHorns().get(i).copy();
            }
        } else {
            System.err.println("[ModelLoader] Warning: Model missing horn parts, using defaults");
            for (int i = 0; i < 2; i++) {
                allParts[6 + i] = createDefaultPart("horn" + (i + 1));
            }
        }
        
        // Udder and tail
        allParts[8] = parts.getUdder() != null ? parts.getUdder().copy() : createDefaultPart("udder");
        allParts[9] = parts.getTail() != null ? parts.getTail().copy() : createDefaultPart("tail");
        
        return allParts;
    }
    
    /**
     * Apply animation to model parts.
     */
    private static void applyAnimation(ModelPart[] parts, ModelAnimation animation, float animationTime) {
        String animationType = getAnimationType(animation);
        
        switch (animationType) {
            case "walking" -> applyWalkingAnimation(parts, animationTime);
            case "grazing" -> applyGrazingAnimation(parts, animationTime);
            case "idle" -> applyIdleAnimation(parts, animationTime);
            default -> {
                // Apply static animation values
                if (animation.getLegRotations() != null && animation.getLegRotations().length >= 4) {
                    for (int i = 0; i < 4 && i + 2 < parts.length; i++) {
                        parts[2 + i].setRotation(new Vector3f(animation.getLegRotations()[i], 0, 0));
                    }
                }
                if (parts.length > 1) {
                    parts[1].setRotation(new Vector3f(animation.getHeadPitch(), 0, 0));
                }
                if (parts.length > 9) {
                    parts[9].setRotation(new Vector3f(0, animation.getTailSway(), 0));
                }
            }
        }
    }
    
    /**
     * Determine animation type based on animation values (for dynamic animations).
     */
    private static String getAnimationType(ModelAnimation animation) {
        float[] legRotations = animation.getLegRotations();
        
        // Check if legs have non-zero values (indicates walking)
        if (legRotations != null && legRotations.length >= 4) {
            for (float rotation : legRotations) {
                if (rotation != 0) {
                    return "walking";
                }
            }
        }
        
        // Check if head is pitched down (indicates grazing)
        if (animation.getHeadPitch() < -20) {
            return "grazing";
        }
        
        return "idle";
    }
    
    /**
     * Apply walking animation with dynamic leg movement.
     */
    private static void applyWalkingAnimation(ModelPart[] parts, float time) {
        float walkCycle = time * 3.0f; // Walking speed
        
        // Animate legs with alternating pattern
        float frontLeftLeg = (float)Math.sin(walkCycle) * 25.0f; // Front left
        float frontRightLeg = (float)Math.sin(walkCycle + Math.PI) * 25.0f; // Front right (opposite)
        float backLeftLeg = (float)Math.sin(walkCycle + Math.PI) * 20.0f; // Back left (opposite to front left)
        float backRightLeg = (float)Math.sin(walkCycle) * 20.0f; // Back right (same as front left)
        
        if (parts.length > 5) {
            parts[2].setRotation(new Vector3f(frontLeftLeg, 0, 0));  // Front left leg
            parts[3].setRotation(new Vector3f(frontRightLeg, 0, 0)); // Front right leg
            parts[4].setRotation(new Vector3f(backLeftLeg, 0, 0));   // Back left leg
            parts[5].setRotation(new Vector3f(backRightLeg, 0, 0));  // Back right leg
        }
        
        // Subtle head bobbing while walking
        if (parts.length > 1) {
            float headBob = (float)Math.sin(walkCycle * 2.0f) * 2.0f;
            parts[1].setRotation(new Vector3f(headBob, 0, 0)); // Head
        }
        
        // Body slightly tilts with walking rhythm
        if (parts.length > 0) {
            Vector3f bodyPos = parts[0].getPositionVector();
            bodyPos.y += (float)Math.sin(walkCycle * 2.0f) * 0.02f; // Subtle vertical movement
            parts[0].setPosition(new Position(bodyPos.x, bodyPos.y, bodyPos.z));
        }
    }
    
    /**
     * Apply grazing animation.
     */
    private static void applyGrazingAnimation(ModelPart[] parts, float time) {
        // Head down for grazing
        float grazingCycle = time * 1.5f; // Slower grazing movement
        float headPitch = -35.0f + (float)Math.sin(grazingCycle) * 5.0f; // Head down with slight bobbing
        
        if (parts.length > 1) {
            parts[1].setRotation(new Vector3f(headPitch, 0, 0)); // Head
        }
        
        // Legs stable during grazing
        for (int i = 2; i < 6 && i < parts.length; i++) {
            parts[i].setRotation(new Vector3f(0, 0, 0));
        }
        
        // Body slightly lower when grazing
        if (parts.length > 0) {
            Vector3f bodyPos = parts[0].getPositionVector();
            bodyPos.y -= 0.05f;
            parts[0].setPosition(new Position(bodyPos.x, bodyPos.y, bodyPos.z));
        }
    }
    
    /**
     * Apply idle animation.
     */
    private static void applyIdleAnimation(ModelPart[] parts, float time) {
        float idleCycle = time * 0.8f; // Slow idle movement
        
        // Subtle breathing animation
        if (parts.length > 0) {
            float breathingScale = 1.0f + (float)Math.sin(idleCycle) * 0.02f;
            Vector3f bodyScale = new Vector3f(breathingScale, 1.0f, breathingScale);
            parts[0].setScale(bodyScale); // Body breathing
        }
        
        // Occasional head movements
        if (parts.length > 1) {
            float headMovement = (float)Math.sin(idleCycle * 0.3f) * 3.0f;
            parts[1].setRotation(new Vector3f(headMovement, (float)Math.sin(idleCycle * 0.2f) * 5.0f, 0)); // Head
        }
        
        // Legs at rest
        for (int i = 2; i < 6 && i < parts.length; i++) {
            parts[i].setRotation(new Vector3f(0, 0, 0));
        }
        
        // Tail swishing
        if (parts.length > 9) {
            float tailSway = (float)Math.sin(idleCycle * 2.0f) * 15.0f;
            parts[9].setRotation(new Vector3f(0, tailSway, 0)); // Tail
        }
    }
    
    /**
     * Creates a default model part for fallback purposes.
     */
    private static ModelPart createDefaultPart(String name) {
        return new ModelPart(name, new Position(0, 0, 0), new Size(0.1f, 0.1f, 0.1f), "missing_texture");
    }
    
    /**
     * Count total parts in a model definition.
     */
    private static int countTotalParts(CowModelDefinition model) {
        if (model.getParts() == null) return 0;
        
        int count = 0;
        ModelParts parts = model.getParts();
        
        if (parts.getBody() != null) count++;
        if (parts.getHead() != null) count++;
        if (parts.getLegs() != null) count += parts.getLegs().size();
        if (parts.getHorns() != null) count += parts.getHorns().size();
        if (parts.getUdder() != null) count++;
        if (parts.getTail() != null) count++;
        
        return count;
    }
    
    /**
     * Validate a loaded model definition.
     */
    private static void validateModel(CowModelDefinition model, String modelName) throws IOException {
        if (model == null) {
            throw new IOException("Model '" + modelName + "' is null");
        }
        
        if (model.getParts() == null) {
            throw new IOException("Model '" + modelName + "' has no parts");
        }
        
        if (model.getAnimations() == null || model.getAnimations().isEmpty()) {
            throw new IOException("Model '" + modelName + "' has no animations");
        }
        
        // Validate required parts
        ModelParts parts = model.getParts();
        if (parts.getBody() == null) {
            throw new IOException("Model '" + modelName + "' missing body part");
        }
        
        if (parts.getHead() == null) {
            throw new IOException("Model '" + modelName + "' missing head part");
        }
        
        if (parts.getLegs() == null || parts.getLegs().size() < 4) {
            throw new IOException("Model '" + modelName + "' missing required leg parts (need 4)");
        }
        
        if (parts.getHorns() == null || parts.getHorns().size() < 2) {
            throw new IOException("Model '" + modelName + "' missing required horn parts (need 2)");
        }
        
        // Validate required animations
        String[] requiredAnimations = {"IDLE", "WALKING", "GRAZING"};
        for (String requiredAnimation : requiredAnimations) {
            if (!model.getAnimations().containsKey(requiredAnimation)) {
                throw new IOException("Model '" + modelName + "' missing required animation: " + requiredAnimation);
            }
        }
        
        System.out.println("[ModelLoader] Model '" + modelName + "' validation passed");
    }
    
    /**
     * Clear the model cache.
     */
    public static void clearCache() {
        System.out.println("[ModelLoader] Clearing model cache. Current cached models: " + cachedModels.keySet());
        cachedModels.clear();
    }
    
    /**
     * Check if a model name is valid.
     */
    public static boolean isValidModel(String modelName) {
        return MODEL_FILE_PATHS.containsKey(modelName);
    }
    
    /**
     * Get all available model names.
     */
    public static String[] getAvailableModels() {
        return MODEL_FILE_PATHS.keySet().toArray(new String[0]);
    }
    
    /**
     * Debug method to get current cache status.
     */
    public static void printCacheStatus() {
        System.out.println("[ModelLoader] Cache Status:");
        System.out.println("  Available models: " + MODEL_FILE_PATHS.keySet());
        System.out.println("  Cached models: " + cachedModels.keySet());
        for (Map.Entry<String, CowModelDefinition> entry : cachedModels.entrySet()) {
            String modelName = entry.getKey();
            CowModelDefinition model = entry.getValue();
            String displayName = model != null ? model.getDisplayName() : "null";
            System.out.println("    " + modelName + " -> " + displayName);
        }
    }
}