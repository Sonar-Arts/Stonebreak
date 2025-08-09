package com.stonebreak.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonebreak.model.ModelDefinition.*;
import org.joml.Vector3f;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Production-ready JSON model loader with sync/async APIs and comprehensive error handling.
 * 
 * Features:
 * - Synchronous and asynchronous model loading
 * - Thread-safe caching with proper concurrency control
 * - Comprehensive exception hierarchy for different error types
 * - Progress reporting for async operations
 * - Strict validation with no silent fallbacks
 * - Fail-fast error detection
 * 
 * @author Enhanced ModelLoader
 * @version 2.0
 */
public class ModelLoader {
    
    // Exception hierarchy for different error types
    public static class ModelException extends RuntimeException {
        public ModelException(String message) {
            super(message);
        }
        
        public ModelException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    public static class ModelNotFoundException extends ModelException {
        public ModelNotFoundException(String modelName) {
            super("Model not found: '" + modelName + "'. Available models: " + Arrays.toString(getAvailableModels()));
        }
    }
    
    public static class ModelValidationException extends ModelException {
        public ModelValidationException(String modelName, String reason) {
            super("Model validation failed for '" + modelName + "': " + reason);
        }
    }
    
    public static class ModelLoadingException extends ModelException {
        public ModelLoadingException(String modelName, String reason) {
            super("Failed to load model '" + modelName + "': " + reason);
        }
        
        public ModelLoadingException(String modelName, String reason, Throwable cause) {
            super("Failed to load model '" + modelName + "': " + reason, cause);
        }
    }
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Map<String, CowModelDefinition> cachedModels = new ConcurrentHashMap<>();
    private static final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private static final ExecutorService asyncExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ModelLoader-Async");
        t.setDaemon(true);
        return t;
    });
    
    // Paths to model definition JSON files
    private static final Map<String, String> MODEL_FILE_PATHS = Map.of(
        "standard_cow", "models/cow/standard_cow.json"
        // Future cow variants can be added here
    );
    
    /**
     * Gets a cow model definition synchronously, loading and caching it if necessary.
     * 
     * @param modelName The name of the model to load
     * @return The loaded model definition (never null)
     * @throws ModelNotFoundException if the model name is not recognized
     * @throws ModelLoadingException if the model file cannot be loaded
     * @throws ModelValidationException if the model fails validation
     */
    public static CowModelDefinition getCowModel(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new ModelNotFoundException("null or empty");
        }
        
        modelName = modelName.trim();
        
        // Check cache with read lock
        cacheLock.readLock().lock();
        try {
            CowModelDefinition cached = cachedModels.get(modelName);
            if (cached != null) {
                return cached;
            }
        } finally {
            cacheLock.readLock().unlock();
        }
        
        // Load model with write lock
        cacheLock.writeLock().lock();
        try {
            // Double-check pattern
            CowModelDefinition cached = cachedModels.get(modelName);
            if (cached != null) {
                return cached;
            }
            
            // Validate model name exists
            if (!MODEL_FILE_PATHS.containsKey(modelName)) {
                throw new ModelNotFoundException(modelName);
            }
            
            // Load and validate model
            CowModelDefinition model = loadModelInternal(modelName);
            validateModelStrict(model, modelName);
            
            // Cache and return
            cachedModels.put(modelName, model);
            System.out.println("[ModelLoader] Successfully loaded and cached model: " + modelName);
            return model;
            
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * Gets a cow model definition asynchronously with progress reporting.
     * 
     * @param modelName The name of the model to load
     * @param progressCallback Optional progress callback (can be null)
     * @return CompletableFuture that completes with the loaded model
     * @throws ModelNotFoundException immediately if the model name is not recognized
     */
    public static CompletableFuture<CowModelDefinition> getCowModelAsync(String modelName, Consumer<String> progressCallback) {
        if (modelName == null || modelName.trim().isEmpty()) {
            return CompletableFuture.failedFuture(new ModelNotFoundException("null or empty"));
        }
        
        final String finalModelName = modelName.trim();
        
        // Validate model name exists immediately
        if (!MODEL_FILE_PATHS.containsKey(finalModelName)) {
            return CompletableFuture.failedFuture(new ModelNotFoundException(finalModelName));
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (progressCallback != null) {
                    progressCallback.accept("Checking cache for model: " + finalModelName);
                }
                
                // Check cache first
                cacheLock.readLock().lock();
                try {
                    CowModelDefinition cached = cachedModels.get(finalModelName);
                    if (cached != null) {
                        if (progressCallback != null) {
                            progressCallback.accept("Model found in cache: " + finalModelName);
                        }
                        return cached;
                    }
                } finally {
                    cacheLock.readLock().unlock();
                }
                
                if (progressCallback != null) {
                    progressCallback.accept("Loading model from file: " + finalModelName);
                }
                
                // Load model with write lock
                cacheLock.writeLock().lock();
                try {
                    // Double-check pattern
                    CowModelDefinition cached = cachedModels.get(finalModelName);
                    if (cached != null) {
                        return cached;
                    }
                    
                    if (progressCallback != null) {
                        progressCallback.accept("Parsing JSON for model: " + finalModelName);
                    }
                    
                    // Load and validate model
                    CowModelDefinition model = loadModelInternal(finalModelName);
                    
                    if (progressCallback != null) {
                        progressCallback.accept("Validating model: " + finalModelName);
                    }
                    
                    validateModelStrict(model, finalModelName);
                    
                    // Cache and return
                    cachedModels.put(finalModelName, model);
                    
                    if (progressCallback != null) {
                        progressCallback.accept("Successfully loaded model: " + finalModelName);
                    }
                    
                    System.out.println("[ModelLoader] Async: Successfully loaded and cached model: " + finalModelName);
                    return model;
                    
                } finally {
                    cacheLock.writeLock().unlock();
                }
                
            } catch (Exception e) {
                if (progressCallback != null) {
                    progressCallback.accept("Error loading model " + finalModelName + ": " + e.getMessage());
                }
                throw e;
            }
        }, asyncExecutor);
    }
    
    /**
     * Internal method to load a model definition from its JSON file.
     * Used by both sync and async APIs.
     */
    private static CowModelDefinition loadModelInternal(String modelName) {
        String filePath = MODEL_FILE_PATHS.get(modelName);
        if (filePath == null) {
            throw new ModelNotFoundException(modelName);
        }
        
        // Try different approaches for module compatibility
        InputStream inputStream = null;
        
        // First try: Module's class loader
        inputStream = ModelLoader.class.getClassLoader().getResourceAsStream(filePath);
        
        // Second try: Module class itself
        if (inputStream == null) {
            inputStream = ModelLoader.class.getResourceAsStream("/" + filePath);
        }
        
        // Third try: Context class loader
        if (inputStream == null) {
            inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(filePath);
        }
        
        try (InputStream finalInputStream = inputStream) {
            if (finalInputStream == null) {
                throw new ModelLoadingException(modelName, "Resource file not found: " + filePath);
            }
            
            CowModelDefinition model = objectMapper.readValue(finalInputStream, CowModelDefinition.class);
            
            System.out.println("[ModelLoader] Successfully loaded cow model '" + modelName + "' (" + 
                (model.getDisplayName() != null ? model.getDisplayName() : "unknown") + ") with " + 
                countTotalParts(model) + " parts and " + 
                (model.getAnimations() != null ? model.getAnimations().size() : 0) + " animations");
            
            return model;
            
        } catch (IOException e) {
            throw new ModelLoadingException(modelName, "JSON parsing failed", e);
        } catch (Exception e) {
            throw new ModelLoadingException(modelName, "Unexpected error during loading", e);
        }
    }
    
    /**
     * Gets animated model parts for a specific animation and time.
     * 
     * @param modelName The model to use
     * @param animationName The animation to apply
     * @param animationTime The current animation time
     * @return Array of animated model parts
     * @throws ModelException if model or animation cannot be found
     */
    public static ModelPart[] getAnimatedParts(String modelName, String animationName, float animationTime) {
        CowModelDefinition model = getCowModel(modelName); // This throws on error, never returns null
        
        // Get base model parts - strict validation, no fallbacks
        ModelPart[] parts = getAllPartsStrict(model);
        
        // Apply animation if it exists
        if (animationName != null && !animationName.trim().isEmpty()) {
            ModelAnimation animation = model.getAnimations().get(animationName.trim());
            if (animation != null) {
                applyAnimation(parts, animation, animationTime);
            } else {
                throw new ModelValidationException(modelName, 
                    "Animation '" + animationName + "' not found. Available animations: " + model.getAnimations().keySet());
            }
        }
        
        return parts;
    }
    
    /**
     * Gets all model parts in the correct order with strict validation.
     * NO FALLBACKS - if any required part is missing, throws an exception.
     * 
     * @param model The model definition
     * @return Array of model parts in rendering order
     * @throws ModelValidationException if any required part is missing
     */
    public static ModelPart[] getAllPartsStrict(CowModelDefinition model) {
        if (model == null) {
            throw new ModelValidationException("unknown", "Model is null");
        }
        
        if (model.getParts() == null) {
            throw new ModelValidationException(model.getModelName(), "Model has no parts");
        }
        
        ModelParts parts = model.getParts();
        ModelPart[] allParts = new ModelPart[10]; // body, head, 4 legs, 2 horns, udder, tail
        
        // Body and head - required parts
        if (parts.getBody() == null) {
            throw new ModelValidationException(model.getModelName(), "Missing required body part");
        }
        if (parts.getHead() == null) {
            throw new ModelValidationException(model.getModelName(), "Missing required head part");
        }
        
        allParts[0] = parts.getBody().copy();
        allParts[1] = parts.getHead().copy();
        
        // Legs (4) - required parts
        if (parts.getLegs() == null || parts.getLegs().size() < 4) {
            throw new ModelValidationException(model.getModelName(), 
                "Missing required leg parts (need 4, found " + (parts.getLegs() != null ? parts.getLegs().size() : 0) + ")");
        }
        
        for (int i = 0; i < 4; i++) {
            ModelPart leg = parts.getLegs().get(i);
            if (leg == null) {
                throw new ModelValidationException(model.getModelName(), "Leg " + (i + 1) + " is null");
            }
            allParts[2 + i] = leg.copy();
        }
        
        // Horns (2) - required parts
        if (parts.getHorns() == null || parts.getHorns().size() < 2) {
            throw new ModelValidationException(model.getModelName(), 
                "Missing required horn parts (need 2, found " + (parts.getHorns() != null ? parts.getHorns().size() : 0) + ")");
        }
        
        for (int i = 0; i < 2; i++) {
            ModelPart horn = parts.getHorns().get(i);
            if (horn == null) {
                throw new ModelValidationException(model.getModelName(), "Horn " + (i + 1) + " is null");
            }
            allParts[6 + i] = horn.copy();
        }
        
        // Udder and tail - required parts
        if (parts.getUdder() == null) {
            throw new ModelValidationException(model.getModelName(), "Missing required udder part");
        }
        if (parts.getTail() == null) {
            throw new ModelValidationException(model.getModelName(), "Missing required tail part");
        }
        
        allParts[8] = parts.getUdder().copy();
        allParts[9] = parts.getTail().copy();
        
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
     * Strict validation of a loaded model definition.
     * Validates all required parts, animations, and data integrity.
     */
    private static void validateModelStrict(CowModelDefinition model, String modelName) {
        if (model == null) {
            throw new ModelValidationException(modelName, "Model is null");
        }
        
        // Validate basic structure
        if (model.getModelName() == null || model.getModelName().trim().isEmpty()) {
            throw new ModelValidationException(modelName, "Model name is null or empty");
        }
        
        if (model.getDisplayName() == null || model.getDisplayName().trim().isEmpty()) {
            throw new ModelValidationException(modelName, "Display name is null or empty");
        }
        
        if (model.getParts() == null) {
            throw new ModelValidationException(modelName, "Model has no parts");
        }
        
        if (model.getAnimations() == null || model.getAnimations().isEmpty()) {
            throw new ModelValidationException(modelName, "Model has no animations");
        }
        
        // Validate required parts with detailed error messages
        ModelParts parts = model.getParts();
        
        if (parts.getBody() == null) {
            throw new ModelValidationException(modelName, "Missing required body part");
        }
        validateModelPart(parts.getBody(), "body", modelName);
        
        if (parts.getHead() == null) {
            throw new ModelValidationException(modelName, "Missing required head part");
        }
        validateModelPart(parts.getHead(), "head", modelName);
        
        if (parts.getLegs() == null || parts.getLegs().size() < 4) {
            throw new ModelValidationException(modelName, 
                "Missing required leg parts (need 4, found " + (parts.getLegs() != null ? parts.getLegs().size() : 0) + ")");
        }
        
        for (int i = 0; i < 4; i++) {
            ModelPart leg = parts.getLegs().get(i);
            if (leg == null) {
                throw new ModelValidationException(modelName, "Leg " + (i + 1) + " is null");
            }
            validateModelPart(leg, "leg" + (i + 1), modelName);
        }
        
        if (parts.getHorns() == null || parts.getHorns().size() < 2) {
            throw new ModelValidationException(modelName, 
                "Missing required horn parts (need 2, found " + (parts.getHorns() != null ? parts.getHorns().size() : 0) + ")");
        }
        
        for (int i = 0; i < 2; i++) {
            ModelPart horn = parts.getHorns().get(i);
            if (horn == null) {
                throw new ModelValidationException(modelName, "Horn " + (i + 1) + " is null");
            }
            validateModelPart(horn, "horn" + (i + 1), modelName);
        }
        
        if (parts.getUdder() == null) {
            throw new ModelValidationException(modelName, "Missing required udder part");
        }
        validateModelPart(parts.getUdder(), "udder", modelName);
        
        if (parts.getTail() == null) {
            throw new ModelValidationException(modelName, "Missing required tail part");
        }
        validateModelPart(parts.getTail(), "tail", modelName);
        
        // Validate required animations
        String[] requiredAnimations = {"IDLE", "WALKING", "GRAZING"};
        for (String requiredAnimation : requiredAnimations) {
            if (!model.getAnimations().containsKey(requiredAnimation)) {
                throw new ModelValidationException(modelName, 
                    "Missing required animation: " + requiredAnimation + 
                    ". Available animations: " + model.getAnimations().keySet());
            }
            
            ModelAnimation animation = model.getAnimations().get(requiredAnimation);
            if (animation == null) {
                throw new ModelValidationException(modelName, "Animation " + requiredAnimation + " is null");
            }
            
            // Validate animation data
            if (animation.getLegRotations() == null || animation.getLegRotations().length < 4) {
                throw new ModelValidationException(modelName, 
                    "Animation " + requiredAnimation + " has invalid leg rotations (need 4 values)");
            }
        }
        
        System.out.println("[ModelLoader] Model '" + modelName + "' passed strict validation");
    }
    
    /**
     * Validates an individual model part for completeness and consistency.
     */
    private static void validateModelPart(ModelPart part, String partName, String modelName) {
        if (part.getName() == null || part.getName().trim().isEmpty()) {
            throw new ModelValidationException(modelName, "Part " + partName + " has null or empty name");
        }
        
        if (part.getPosition() == null) {
            throw new ModelValidationException(modelName, "Part " + partName + " has null position");
        }
        
        if (part.getSize() == null) {
            throw new ModelValidationException(modelName, "Part " + partName + " has null size");
        }
        
        if (part.getTexture() == null || part.getTexture().trim().isEmpty()) {
            throw new ModelValidationException(modelName, "Part " + partName + " has null or empty texture");
        }
        
        // Validate size is not zero or negative
        Vector3f size = part.getSizeVector();
        if (size.x <= 0 || size.y <= 0 || size.z <= 0) {
            throw new ModelValidationException(modelName, 
                "Part " + partName + " has invalid size: " + size + " (must be positive)");
        }
    }
    
    /**
     * Clear the model cache in a thread-safe manner.
     */
    public static void clearCache() {
        cacheLock.writeLock().lock();
        try {
            System.out.println("[ModelLoader] Clearing model cache. Current cached models: " + cachedModels.keySet());
            cachedModels.clear();
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * Check if a model name is valid and the model can be loaded.
     * 
     * @param modelName The model name to check
     * @return true if the model exists and can be loaded, false otherwise
     */
    public static boolean isValidModel(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            return false;
        }
        
        return MODEL_FILE_PATHS.containsKey(modelName.trim());
    }
    
    /**
     * Check if a model is already loaded in the cache.
     * 
     * @param modelName The model name to check
     * @return true if the model is cached, false otherwise
     */
    public static boolean isModelCached(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) {
            return false;
        }
        
        cacheLock.readLock().lock();
        try {
            return cachedModels.containsKey(modelName.trim());
        } finally {
            cacheLock.readLock().unlock();
        }
    }
    
    /**
     * Get all available model names.
     */
    public static String[] getAvailableModels() {
        return MODEL_FILE_PATHS.keySet().toArray(new String[0]);
    }
    
    /**
     * Get the current cache size.
     * 
     * @return Number of models currently cached
     */
    public static int getCacheSize() {
        cacheLock.readLock().lock();
        try {
            return cachedModels.size();
        } finally {
            cacheLock.readLock().unlock();
        }
    }
    
    /**
     * Get cache status information.
     * 
     * @return Map containing cache statistics
     */
    public static Map<String, Object> getCacheStatus() {
        cacheLock.readLock().lock();
        try {
            Map<String, Object> status = new HashMap<>();
            status.put("availableModels", new ArrayList<>(MODEL_FILE_PATHS.keySet()));
            status.put("cachedModels", new ArrayList<>(cachedModels.keySet()));
            status.put("cacheSize", cachedModels.size());
            status.put("totalAvailable", MODEL_FILE_PATHS.size());
            
            Map<String, String> modelInfo = new HashMap<>();
            for (Map.Entry<String, CowModelDefinition> entry : cachedModels.entrySet()) {
                String modelName = entry.getKey();
                CowModelDefinition model = entry.getValue();
                String displayName = model != null ? model.getDisplayName() : "null";
                modelInfo.put(modelName, displayName);
            }
            status.put("modelDisplayNames", modelInfo);
            
            return status;
        } finally {
            cacheLock.readLock().unlock();
        }
    }
    
    /**
     * Debug method to print current cache status.
     */
    public static void printCacheStatus() {
        cacheLock.readLock().lock();
        try {
            System.out.println("[ModelLoader] Cache Status:");
            System.out.println("  Available models: " + MODEL_FILE_PATHS.keySet());
            System.out.println("  Cached models: " + cachedModels.keySet());
            System.out.println("  Cache utilization: " + cachedModels.size() + "/" + MODEL_FILE_PATHS.size());
            
            for (Map.Entry<String, CowModelDefinition> entry : cachedModels.entrySet()) {
                String modelName = entry.getKey();
                CowModelDefinition model = entry.getValue();
                String displayName = model != null ? model.getDisplayName() : "null";
                int partCount = countTotalParts(model);
                int animationCount = model != null && model.getAnimations() != null ? model.getAnimations().size() : 0;
                System.out.println("    " + modelName + " -> " + displayName + 
                    " (" + partCount + " parts, " + animationCount + " animations)");
            }
        } finally {
            cacheLock.readLock().unlock();
        }
    }
    
    /**
     * Shutdown the async executor (call when application is shutting down).
     */
    public static void shutdown() {
        System.out.println("[ModelLoader] Shutting down async executor");
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("[ModelLoader] Async executor did not shut down gracefully, forcing shutdown");
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            System.err.println("[ModelLoader] Interrupted while waiting for executor shutdown");
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}