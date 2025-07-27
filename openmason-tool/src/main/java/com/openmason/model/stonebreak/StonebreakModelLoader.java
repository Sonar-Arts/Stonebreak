package com.openmason.model.stonebreak;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.model.stonebreak.StonebreakModelDefinition.*;
import org.joml.Vector3f;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;

/**
 * Asynchronous JSON model loader for cow models.
 * Provides both synchronous (legacy) and asynchronous APIs with progress reporting.
 * Thread-safe with cancellation support for Phase 2 Open Mason integration.
 * Adapted for Open Mason from Stonebreak's ModelLoader.java
 */
public class StonebreakModelLoader {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Map<String, CowModelDefinition> cachedModels = new ConcurrentHashMap<>();
    private static final ExecutorService loadingExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ModelLoader-" + System.currentTimeMillis());
        t.setDaemon(true);
        return t;
    });
    
    // Async loading state management
    private static final Map<String, CompletableFuture<CowModelDefinition>> activeLoads = new ConcurrentHashMap<>();
    private static final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    
    /**
     * Progress callback interface for async loading operations.
     */
    public interface ProgressCallback {
        void onProgress(String operation, int current, int total, String details);
        void onError(String operation, Throwable error);
        void onComplete(String operation, Object result);
    }
    
    /**
     * Loading priority levels for background queue management.
     */
    public enum LoadingPriority {
        IMMEDIATE,  // Load immediately, highest priority
        HIGH,       // Load as soon as possible
        NORMAL,     // Standard background loading
        LOW         // Load when system is idle
    }
    
    // Paths to model definition JSON files
    private static final Map<String, String> MODEL_FILE_PATHS = Map.of(
        "standard_cow", "models/cow/standard_cow.json"
        // Future cow variants can be added here
    );
    
    /**
     * ASYNC: Load a cow model definition asynchronously with progress reporting.
     * 
     * @param modelName The model to load
     * @param priority Loading priority for queue management
     * @param progressCallback Optional progress callback (can be null)
     * @return CompletableFuture that resolves to the loaded model
     */
    public static CompletableFuture<CowModelDefinition> getCowModelAsync(String modelName, 
                                                                         LoadingPriority priority, 
                                                                         ProgressCallback progressCallback) {
        if (shutdownRequested.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("ModelLoader is shutting down"));
        }
        
        // Check if already cached
        CowModelDefinition cached = cachedModels.get(modelName);
        if (cached != null) {
            if (progressCallback != null) {
                progressCallback.onComplete("getCowModelAsync", cached);
            }
            return CompletableFuture.completedFuture(cached);
        }
        
        // Check if already loading
        CompletableFuture<CowModelDefinition> existingLoad = activeLoads.get(modelName);
        if (existingLoad != null) {
            System.out.println("[StonebreakModelLoader] Model '" + modelName + "' already loading, reusing future");
            return existingLoad;
        }
        
        // Create new async loading operation
        CompletableFuture<CowModelDefinition> loadFuture = CompletableFuture.supplyAsync(() -> {
            try {
                if (progressCallback != null) {
                    progressCallback.onProgress("getCowModelAsync", 0, 100, "Starting model load: " + modelName);
                }
                
                if (progressCallback != null) {
                    progressCallback.onProgress("getCowModelAsync", 25, 100, "Reading JSON file");
                }
                
                CowModelDefinition model = loadModelSync(modelName);
                if (model != null) {
                    cachedModels.put(modelName, model);
                    
                    if (progressCallback != null) {
                        progressCallback.onProgress("getCowModelAsync", 100, 100, "Model loaded successfully");
                        progressCallback.onComplete("getCowModelAsync", model);
                    }
                    
                    System.out.println("[StonebreakModelLoader] Successfully cached model asynchronously: " + modelName);
                    return model;
                } else {
                    throw new RuntimeException("Failed to load model: " + modelName);
                }
                
            } catch (Exception e) {
                if (progressCallback != null) {
                    progressCallback.onError("getCowModelAsync", e);
                }
                throw new RuntimeException("Async model loading failed: " + e.getMessage(), e);
            }
        }, loadingExecutor).whenComplete((result, throwable) -> {
            // Clean up from active loads
            activeLoads.remove(modelName);
        });
        
        // Track active load
        activeLoads.put(modelName, loadFuture);
        return loadFuture;
    }
    
    /**
     * ASYNC: Load multiple models in parallel with progress reporting.
     * 
     * @param modelNames List of models to load
     * @param priority Loading priority
     * @param progressCallback Progress callback for overall operation
     * @return CompletableFuture that resolves when all models are loaded
     */
    public static CompletableFuture<Map<String, CowModelDefinition>> loadMultipleModelsAsync(
            List<String> modelNames, LoadingPriority priority, ProgressCallback progressCallback) {
        
        if (modelNames == null || modelNames.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        
        AtomicInteger completed = new AtomicInteger(0);
        int total = modelNames.size();
        
        if (progressCallback != null) {
            progressCallback.onProgress("loadMultipleModelsAsync", 0, total, 
                "Starting parallel load of " + total + " models");
        }
        
        // Create individual loading futures
        List<CompletableFuture<Map.Entry<String, CowModelDefinition>>> loadFutures = new ArrayList<>();
        
        for (String modelName : modelNames) {
            CompletableFuture<Map.Entry<String, CowModelDefinition>> future = 
                getCowModelAsync(modelName, priority, null)
                    .thenApply(model -> {
                        int currentCompleted = completed.incrementAndGet();
                        if (progressCallback != null) {
                            progressCallback.onProgress("loadMultipleModelsAsync", currentCompleted, total, 
                                "Loaded model: " + modelName);
                        }
                        return Map.entry(modelName, model);
                    });
            loadFutures.add(future);
        }
        
        // Combine all futures
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
            loadFutures.toArray(new CompletableFuture[0]));
        
        return allFutures.thenApply(v -> {
            Map<String, CowModelDefinition> results = new ConcurrentHashMap<>();
            
            for (CompletableFuture<Map.Entry<String, CowModelDefinition>> future : loadFutures) {
                try {
                    Map.Entry<String, CowModelDefinition> entry = future.get();
                    results.put(entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    System.err.println("[StonebreakModelLoader] Failed to get result from future: " + e.getMessage());
                }
            }
            
            if (progressCallback != null) {
                progressCallback.onComplete("loadMultipleModelsAsync", results);
            }
            
            return results;
        });
    }
    
    /**
     * LEGACY: Gets a cow model definition, loading and caching it if necessary.
     * This is the original synchronous method, preserved for backward compatibility.
     */
    public static CowModelDefinition getCowModel(String modelName) {
        // Check if model is already cached
        CowModelDefinition cached = cachedModels.get(modelName);
        if (cached != null) {
            return cached;
        }
        
        // Attempt to load the model
        try {
            CowModelDefinition model = loadModelSync(modelName);
            if (model != null) {
                cachedModels.put(modelName, model);
                System.out.println("[StonebreakModelLoader] Successfully cached model: " + modelName);
                return model;
            }
        } catch (IOException e) {
            System.err.println("[StonebreakModelLoader] Failed to load model '" + modelName + "': " + e.getMessage());
        }
        
        // Fallback to standard_cow model
        if (!"standard_cow".equals(modelName)) {
            System.err.println("[StonebreakModelLoader] Using fallback standard_cow model for failed model: " + modelName);
            return getCowModel("standard_cow");
        }
        
        // If standard_cow model itself fails, return null
        System.err.println("[StonebreakModelLoader] Critical error: standard_cow model failed to load");
        return null;
    }
    
    /**
     * Load a model definition from its JSON file synchronously.
     * Renamed from loadModel for clarity with async API.
     */
    public static CowModelDefinition loadModelSync(String modelName) throws IOException {
        String filePath = MODEL_FILE_PATHS.get(modelName);
        if (filePath == null) {
            throw new IOException("Unknown cow model: " + modelName + ". Available models: " + MODEL_FILE_PATHS.keySet());
        }
        
        try (InputStream inputStream = StonebreakModelLoader.class.getClassLoader().getResourceAsStream(filePath)) {
            if (inputStream == null) {
                throw new IOException("Could not find resource: " + filePath);
            }
            
            CowModelDefinition model = objectMapper.readValue(inputStream, CowModelDefinition.class);
            
            // Validate the loaded model
            validateModel(model, modelName);
            
            System.out.println("[StonebreakModelLoader] Successfully loaded cow model '" + modelName + "' (" + 
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
            System.err.println("[StonebreakModelLoader] Cannot get animated parts: model '" + modelName + "' not found");
            return new ModelPart[0];
        }
        
        // Get base model parts
        ModelPart[] parts = getAllParts(model);
        
        // Apply animation if it exists
        ModelAnimation animation = model.getAnimations().get(animationName);
        if (animation != null) {
            applyAnimation(parts, animation, animationTime);
        } else {
            System.err.println("[StonebreakModelLoader] Animation '" + animationName + "' not found in model '" + modelName + "'");
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
            System.err.println("[StonebreakModelLoader] Warning: Model missing leg parts, using defaults");
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
            System.err.println("[StonebreakModelLoader] Warning: Model missing horn parts, using defaults");
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
        
        System.out.println("[StonebreakModelLoader] Model '" + modelName + "' validation passed");
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
     * Cancel all active loading operations.
     * 
     * @param mayInterruptIfRunning Whether to interrupt running loads
     * @return Number of operations cancelled
     */
    public static int cancelAllLoads(boolean mayInterruptIfRunning) {
        System.out.println("[StonebreakModelLoader] Cancelling all active loads...");
        
        int cancelledCount = 0;
        for (Map.Entry<String, CompletableFuture<CowModelDefinition>> entry : activeLoads.entrySet()) {
            String modelName = entry.getKey();
            CompletableFuture<CowModelDefinition> future = entry.getValue();
            
            if (future.cancel(mayInterruptIfRunning)) {
                cancelledCount++;
                System.out.println("[StonebreakModelLoader] Cancelled load for: " + modelName);
            }
        }
        
        activeLoads.clear();
        return cancelledCount;
    }
    
    /**
     * Cancel loading for a specific model.
     * 
     * @param modelName The model to cancel loading for
     * @param mayInterruptIfRunning Whether to interrupt if currently running
     * @return true if the operation was cancelled
     */
    public static boolean cancelLoad(String modelName, boolean mayInterruptIfRunning) {
        CompletableFuture<CowModelDefinition> future = activeLoads.remove(modelName);
        if (future != null) {
            boolean cancelled = future.cancel(mayInterruptIfRunning);
            if (cancelled) {
                System.out.println("[StonebreakModelLoader] Cancelled load for: " + modelName);
            }
            return cancelled;
        }
        return false;
    }
    
    /**
     * Check if a model is currently being loaded.
     * 
     * @param modelName The model to check
     * @return true if currently loading
     */
    public static boolean isLoading(String modelName) {
        return activeLoads.containsKey(modelName);
    }
    
    /**
     * Get the number of active loading operations.
     * 
     * @return Number of models currently being loaded
     */
    public static int getActiveLoadCount() {
        return activeLoads.size();
    }
    
    /**
     * Shutdown the async loading system gracefully.
     * This should be called when the application is shutting down.
     */
    public static void shutdown() {
        System.out.println("[StonebreakModelLoader] Shutting down async loading system...");
        
        shutdownRequested.set(true);
        
        // Cancel all active loads
        int cancelled = cancelAllLoads(true);
        System.out.println("[StonebreakModelLoader] Cancelled " + cancelled + " active loads");
        
        // Shutdown executor
        loadingExecutor.shutdown();
        try {
            if (!loadingExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                loadingExecutor.shutdownNow();
                System.out.println("[StonebreakModelLoader] Forced shutdown of loading executor");
            } else {
                System.out.println("[StonebreakModelLoader] Loading executor shutdown gracefully");
            }
        } catch (InterruptedException e) {
            loadingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Debug method to get current cache status including async state.
     */
    public static void printCacheStatus() {
        System.out.println("[StonebreakModelLoader] Cache Status:");
        System.out.println("  Available models: " + MODEL_FILE_PATHS.keySet());
        System.out.println("  Cached models: " + cachedModels.keySet());
        System.out.println("  Active loads: " + activeLoads.keySet());
        System.out.println("  Shutdown requested: " + shutdownRequested.get());
        System.out.println("  Executor shutdown: " + loadingExecutor.isShutdown());
        
        for (Map.Entry<String, CowModelDefinition> entry : cachedModels.entrySet()) {
            String modelName = entry.getKey();
            CowModelDefinition model = entry.getValue();
            String displayName = model != null ? model.getDisplayName() : "null";
            System.out.println("    " + modelName + " -> " + displayName);
        }
        
        if (!activeLoads.isEmpty()) {
            System.out.println("  Currently loading:");
            for (String modelName : activeLoads.keySet()) {
                System.out.println("    " + modelName);
            }
        }
    }
    
    /**
     * Clear the model cache.
     * Enhanced to handle async state properly.
     */
    public static void clearCache() {
        System.out.println("[StonebreakModelLoader] Clearing model cache. Current cached models: " + cachedModels.keySet());
        
        // Cancel any active loads that might be caching results
        cancelAllLoads(false);
        
        cachedModels.clear();
    }
}