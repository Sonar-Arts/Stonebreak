package com.openmason.texture.stonebreak;

import com.fasterxml.jackson.databind.ObjectMapper;
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

public class StonebreakTextureLoader {
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Map<String, StonebreakTextureDefinition.CowVariant> cachedVariants = new ConcurrentHashMap<>();
    private static final ExecutorService loadingExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "TextureLoader-" + System.currentTimeMillis());
        t.setDaemon(true);
        return t;
    });
    
    // Async loading state management
    private static final Map<String, CompletableFuture<StonebreakTextureDefinition.CowVariant>> activeLoads = new ConcurrentHashMap<>();
    private static final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    
    /**
     * Progress callback interface for async texture loading operations.
     */
    public interface ProgressCallback {
        void onProgress(String operation, int current, int total, String details);
        void onError(String operation, Throwable error);
        void onComplete(String operation, Object result);
    }
    
    /**
     * Loading priority levels for texture loading queue management.
     */
    public enum LoadingPriority {
        IMMEDIATE,  // Load immediately, highest priority
        HIGH,       // Load as soon as possible
        NORMAL,     // Standard background loading
        LOW         // Load when system is idle
    }
    
    // Paths to individual cow variant JSON files (from stonebreak-game module)
    private static final Map<String, String> VARIANT_FILE_PATHS = Map.of(
        "default", "textures/mobs/cow/default_cow.json",
        "angus", "textures/mobs/cow/angus_cow.json",
        "highland", "textures/mobs/cow/highland_cow.json",
        "jersey", "textures/mobs/cow/jersey_cow.json"
    );
    
    
    /**
     * ASYNC: Load a cow texture variant asynchronously with progress reporting.
     * 
     * @param variantName The texture variant to load
     * @param priority Loading priority for queue management
     * @param progressCallback Optional progress callback (can be null)
     * @return CompletableFuture that resolves to the loaded variant
     */
    public static CompletableFuture<StonebreakTextureDefinition.CowVariant> getCowVariantAsync(String variantName, 
                                                                                               LoadingPriority priority, 
                                                                                               ProgressCallback progressCallback) {
        if (shutdownRequested.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("TextureLoader is shutting down"));
        }
        
        // Check if already cached
        StonebreakTextureDefinition.CowVariant cached = cachedVariants.get(variantName);
        if (cached != null) {
            if (progressCallback != null) {
                progressCallback.onComplete("getCowVariantAsync", cached);
            }
            return CompletableFuture.completedFuture(cached);
        }
        
        // Check if already loading
        CompletableFuture<StonebreakTextureDefinition.CowVariant> existingLoad = activeLoads.get(variantName);
        if (existingLoad != null) {
            System.out.println("[StonebreakTextureLoader] Variant '" + variantName + "' already loading, reusing future");
            return existingLoad;
        }
        
        // Create new async loading operation
        CompletableFuture<StonebreakTextureDefinition.CowVariant> loadFuture = CompletableFuture.supplyAsync(() -> {
            try {
                if (progressCallback != null) {
                    progressCallback.onProgress("getCowVariantAsync", 0, 100, "Starting variant load: " + variantName);
                }
                
                if (progressCallback != null) {
                    progressCallback.onProgress("getCowVariantAsync", 25, 100, "Reading JSON file");
                }
                
                StonebreakTextureDefinition.CowVariant variant = loadIndividualVariantSync(variantName);
                if (variant != null) {
                    cachedVariants.put(variantName, variant);
                    
                    if (progressCallback != null) {
                        progressCallback.onProgress("getCowVariantAsync", 75, 100, "Validating texture mappings");
                    }
                    
                    // Quick validation
                    if (variant.getFaceMappings() != null && !variant.getFaceMappings().isEmpty()) {
                        if (progressCallback != null) {
                            progressCallback.onProgress("getCowVariantAsync", 100, 100, "Variant loaded successfully");
                            progressCallback.onComplete("getCowVariantAsync", variant);
                        }
                        
                        System.out.println("[StonebreakTextureLoader] Successfully cached variant asynchronously: " + variantName);
                        return variant;
                    } else {
                        throw new RuntimeException("Variant loaded but has no face mappings: " + variantName);
                    }
                } else {
                    throw new RuntimeException("Failed to load variant: " + variantName);
                }
                
            } catch (Exception e) {
                if (progressCallback != null) {
                    progressCallback.onError("getCowVariantAsync", e);
                }
                throw new RuntimeException("Async variant loading failed: " + e.getMessage(), e);
            }
        }, loadingExecutor).whenComplete((result, throwable) -> {
            // Clean up from active loads
            activeLoads.remove(variantName);
        });
        
        // Track active load
        activeLoads.put(variantName, loadFuture);
        return loadFuture;
    }
    
    /**
     * ASYNC: Load multiple texture variants in parallel with progress reporting.
     * 
     * @param variantNames List of variants to load
     * @param priority Loading priority
     * @param progressCallback Progress callback for overall operation
     * @return CompletableFuture that resolves when all variants are loaded
     */
    public static CompletableFuture<Map<String, StonebreakTextureDefinition.CowVariant>> loadMultipleVariantsAsync(
            List<String> variantNames, LoadingPriority priority, ProgressCallback progressCallback) {
        
        if (variantNames == null || variantNames.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        
        AtomicInteger completed = new AtomicInteger(0);
        int total = variantNames.size();
        
        if (progressCallback != null) {
            progressCallback.onProgress("loadMultipleVariantsAsync", 0, total, 
                "Starting parallel load of " + total + " texture variants");
        }
        
        // Create individual loading futures
        List<CompletableFuture<Map.Entry<String, StonebreakTextureDefinition.CowVariant>>> loadFutures = new ArrayList<>();
        
        for (String variantName : variantNames) {
            CompletableFuture<Map.Entry<String, StonebreakTextureDefinition.CowVariant>> future = 
                getCowVariantAsync(variantName, priority, null)
                    .thenApply(variant -> {
                        int currentCompleted = completed.incrementAndGet();
                        if (progressCallback != null) {
                            progressCallback.onProgress("loadMultipleVariantsAsync", currentCompleted, total, 
                                "Loaded variant: " + variantName);
                        }
                        return Map.entry(variantName, variant);
                    });
            loadFutures.add(future);
        }
        
        // Combine all futures
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
            loadFutures.toArray(new CompletableFuture[0]));
        
        return allFutures.thenApply(v -> {
            Map<String, StonebreakTextureDefinition.CowVariant> results = new ConcurrentHashMap<>();
            
            for (CompletableFuture<Map.Entry<String, StonebreakTextureDefinition.CowVariant>> future : loadFutures) {
                try {
                    Map.Entry<String, StonebreakTextureDefinition.CowVariant> entry = future.get();
                    results.put(entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    System.err.println("[StonebreakTextureLoader] Failed to get result from future: " + e.getMessage());
                }
            }
            
            if (progressCallback != null) {
                progressCallback.onComplete("loadMultipleVariantsAsync", results);
            }
            
            return results;
        });
    }
    
    /**
     * LEGACY: Gets a cow texture variant, loading and caching it if necessary.
     * This is the original synchronous method, preserved for backward compatibility.
     */
    public static StonebreakTextureDefinition.CowVariant getCowVariant(String variantName) {
        // Check if variant is already cached
        StonebreakTextureDefinition.CowVariant cached = cachedVariants.get(variantName);
        if (cached != null) {
            return cached;
        }
        
        // Attempt to load the variant
        try {
            StonebreakTextureDefinition.CowVariant variant = loadIndividualVariantSync(variantName);
            if (variant != null) {
                cachedVariants.put(variantName, variant);
                System.out.println("[StonebreakTextureLoader] Successfully cached variant: " + variantName);
                return variant;
            }
        } catch (IOException e) {
            System.err.println("[StonebreakTextureLoader] Failed to load variant '" + variantName + "': " + e.getMessage());
        }
        
        // Fallback to default variant WITHOUT corrupting the cache
        if (!"default".equals(variantName)) {
            System.err.println("[StonebreakTextureLoader] Using fallback default variant for failed variant: " + variantName);
            StonebreakTextureDefinition.CowVariant defaultVariant = getCowVariant("default");
            // DO NOT cache the default variant under the failed variant's name
            // This prevents cache corruption while still providing a fallback
            return defaultVariant;
        }
        
        // If default variant itself fails, return null
        System.err.println("[StonebreakTextureLoader] Critical error: default variant failed to load");
        return null;
    }
    
    /**
     * Load an individual cow variant from its JSON file synchronously.
     * Renamed for clarity with async API.
     */
    public static StonebreakTextureDefinition.CowVariant loadIndividualVariantSync(String variantName) throws IOException {
        String filePath = VARIANT_FILE_PATHS.get(variantName);
        if (filePath == null) {
            throw new IOException("Unknown cow variant: " + variantName + ". Available variants: " + VARIANT_FILE_PATHS.keySet());
        }
        
        try (InputStream inputStream = StonebreakTextureLoader.class.getClassLoader().getResourceAsStream(filePath)) {
            if (inputStream == null) {
                throw new IOException("Could not find resource: " + filePath);
            }
            
            StonebreakTextureDefinition.CowVariant variant = objectMapper.readValue(inputStream, StonebreakTextureDefinition.CowVariant.class);
            
            // Validate the loaded variant
            validateCowVariant(variant, variantName);
            
            System.out.println("[StonebreakTextureLoader] Successfully loaded cow variant '" + variantName + "' (" + 
                variant.getDisplayName() + ") with " + variant.getFaceMappings().size() + " face mappings");
            
            // Log drawing instructions if present
            if (variant.getDrawingInstructions() != null) {
                System.out.println("  Drawing instructions loaded for " + variant.getDrawingInstructions().size() + " body parts");
            }
            
            return variant;
        }
    }
    
    /**
     * Get atlas coordinates for a specific body part face.
     * @param variantName The cow variant (default, angus, highland, jersey)
     * @param faceName The face name (e.g., "HEAD_FRONT", "BODY_LEFT", etc.)
     * @return AtlasCoordinate containing atlasX and atlasY, or null if not found
     */
    public static StonebreakTextureDefinition.AtlasCoordinate getAtlasCoordinate(String variantName, String faceName) {
        if (variantName == null || faceName == null) {
            System.err.println("[StonebreakTextureLoader] Null parameters in getAtlasCoordinate: variantName=" + variantName + ", faceName=" + faceName);
            return null;
        }
        
        StonebreakTextureDefinition.CowVariant variant = getCowVariant(variantName);
        if (variant == null) {
            System.err.println("[StonebreakTextureLoader] Could not get variant: " + variantName);
            return null;
        }
        
        if (variant.getFaceMappings() == null) {
            System.err.println("[StonebreakTextureLoader] No face mappings available for variant: " + variantName);
            return null;
        }
        
        StonebreakTextureDefinition.AtlasCoordinate coordinate = variant.getFaceMappings().get(faceName);
        if (coordinate == null) {
            System.err.println("[StonebreakTextureLoader] No mapping found for face: " + faceName + " in variant: " + variantName);
            System.err.println("  Available faces: " + variant.getFaceMappings().keySet());
            return null;
        }
        
        // coordinate values are primitive ints, no null check needed
        
        return coordinate;
    }
    
    /**
     * Get normalized UV coordinates for a specific body part face.
     * @param variantName The cow variant
     * @param faceName The face name
     * @param gridSize The texture atlas grid size (usually 16)
     * @return float array with UV coordinates [u1, v1, u2, v2] normalized to 0.0-1.0 range
     */
    public static float[] getNormalizedUVCoordinates(String variantName, String faceName, int gridSize) {
        if (gridSize <= 0) {
            System.err.println("[StonebreakTextureLoader] Invalid grid size: " + gridSize);
            return new float[]{0.0f, 0.0f, 0.0625f, 0.0625f}; // Default for 16x16 grid
        }
        
        StonebreakTextureDefinition.AtlasCoordinate coordinate = getAtlasCoordinate(variantName, faceName);
        if (coordinate == null) {
            // Return fallback coordinates (0,0 tile)
            float tileSize = 1.0f / gridSize;
            return new float[]{0.0f, 0.0f, tileSize, tileSize};
        }
        
        // Additional safety checks
        int x = coordinate.getAtlasX();
        int y = coordinate.getAtlasY();
        
        if (x < 0 || x >= gridSize || y < 0 || y >= gridSize) {
            System.err.println("[StonebreakTextureLoader] Coordinate out of bounds for " + variantName + ":" + faceName + 
                              " - (" + x + "," + y + ") must be within 0-" + (gridSize-1));
            float tileSize = 1.0f / gridSize;
            return new float[]{0.0f, 0.0f, tileSize, tileSize};
        }
        
        float tileSize = 1.0f / gridSize;
        float u1 = x * tileSize;
        float v1 = y * tileSize;
        float u2 = u1 + tileSize;
        float v2 = v1 + tileSize;
        
        return new float[]{u1, v1, u2, v2};
    }
    
    /**
     * Get UV coordinates formatted for quad rendering (bottom-left, bottom-right, top-right, top-left).
     * @param variantName The cow variant
     * @param faceName The face name
     * @param gridSize The texture atlas grid size
     * @return float array with 8 UV coordinates for quad vertices
     */
    public static float[] getQuadUVCoordinates(String variantName, String faceName, int gridSize) {
        float[] coords = getNormalizedUVCoordinates(variantName, faceName, gridSize);
        float u1 = coords[0];
        float v1 = coords[1]; 
        float u2 = coords[2];
        float v2 = coords[3];
        
        // Return coordinates for quad vertices (OpenGL style)
        return new float[]{
            u1, v1,  // bottom-left
            u2, v1,  // bottom-right
            u2, v2,  // top-right
            u1, v2   // top-left
        };
    }
    
    public static String getBaseColor(String variantName, String colorType) {
        if (variantName == null || colorType == null) {
            System.err.println("[StonebreakTextureLoader] Null parameters in getBaseColor: variantName=" + variantName + ", colorType=" + colorType);
            return "#FFFFFF";
        }
        
        StonebreakTextureDefinition.CowVariant variant = getCowVariant(variantName);
        if (variant == null || variant.getBaseColors() == null) {
            System.err.println("[StonebreakTextureLoader] No base colors available for variant: " + variantName);
            return "#FFFFFF";
        }
        
        String color = switch (colorType.toLowerCase()) {
            case "primary" -> variant.getBaseColors().getPrimary();
            case "secondary" -> variant.getBaseColors().getSecondary();
            case "accent" -> variant.getBaseColors().getAccent();
            default -> null;
        };
        
        if (color == null) {
            System.err.println("[StonebreakTextureLoader] Color '" + colorType + "' not found for variant: " + variantName);
            return "#FFFFFF";
        }
        
        return color;
    }
    
    public static int hexColorToInt(String hexColor) {
        if (hexColor == null || !hexColor.startsWith("#") || hexColor.length() != 7) {
            return 0xFFFFFF;
        }
        
        try {
            return Integer.parseInt(hexColor.substring(1), 16);
        } catch (NumberFormatException e) {
            System.err.println("[StonebreakTextureLoader] Invalid hex color format: " + hexColor);
            return 0xFFFFFF;
        }
    }
    
    
    public static boolean isValidVariant(String variantName) {
        return VARIANT_FILE_PATHS.containsKey(variantName);
    }
    
    /**
     * Get all available cow variant names.
     */
    public static String[] getAvailableVariants() {
        return VARIANT_FILE_PATHS.keySet().toArray(new String[0]);
    }
    
    
    /**
     * Test method to verify all variants can be loaded correctly.
     * Useful for debugging texture loading issues.
     */
    public static void testAllVariants() {
        System.out.println("[StonebreakTextureLoader] Testing all variants...");
        for (String variantName : VARIANT_FILE_PATHS.keySet()) {
            try {
                StonebreakTextureDefinition.CowVariant variant = getCowVariant(variantName);
                if (variant != null) {
                    System.out.println("  ✓ " + variantName + " -> " + variant.getDisplayName());
                    
                    // Test drawing instructions for each face mapping
                    int faceMappingCount = variant.getFaceMappings().size();
                    int drawingInstructionCount = variant.getDrawingInstructions() != null ? variant.getDrawingInstructions().size() : 0;
                    System.out.println("    Face mappings: " + faceMappingCount + ", Drawing instructions: " + drawingInstructionCount);
                    
                    if (faceMappingCount != drawingInstructionCount) {
                        System.err.println("    WARNING: Mismatch between face mappings and drawing instructions!");
                    }
                } else {
                    System.err.println("  ✗ " + variantName + " -> FAILED (null)");
                }
            } catch (Exception e) {
                System.err.println("  ✗ " + variantName + " -> ERROR: " + e.getMessage());
            }
        }
        printCacheStatus();
    }
    
    /**
     * Get drawing instructions for a specific cow variant and body part.
     */
    public static StonebreakTextureDefinition.DrawingInstructions getDrawingInstructions(String variantName, String bodyPart) {
        if (variantName == null || bodyPart == null) {
            System.err.println("[StonebreakTextureLoader] Null parameters in getDrawingInstructions: variantName=" + variantName + ", bodyPart=" + bodyPart);
            return null;
        }
        
        StonebreakTextureDefinition.CowVariant variant = getCowVariant(variantName);
        if (variant == null) {
            System.err.println("[StonebreakTextureLoader] Could not get variant for drawing instructions: " + variantName);
            return null;
        }
        
        if (variant.getDrawingInstructions() == null) {
            System.err.println("[StonebreakTextureLoader] No drawing instructions available for variant: " + variantName);
            return null;
        }
        
        StonebreakTextureDefinition.DrawingInstructions instructions = variant.getDrawingInstructions().get(bodyPart);
        if (instructions == null) {
            System.err.println("[StonebreakTextureLoader] No drawing instructions found for " + variantName + ":" + bodyPart);
            System.err.println("  Available body parts: " + variant.getDrawingInstructions().keySet());
            System.err.println("  This will cause fallback texture generation for this part!");
        }
        
        return instructions;
    }
    
    /**
     * Validate an individual cow variant to ensure it has all required data.
     */
    private static void validateCowVariant(StonebreakTextureDefinition.CowVariant variant, String variantName) throws IOException {
        if (variant == null) {
            throw new IOException("Variant '" + variantName + "' is null");
        }
        
        if (variant.getFaceMappings() == null || variant.getFaceMappings().isEmpty()) {
            throw new IOException("Variant '" + variantName + "' has no face mappings");
        }
        
        if (variant.getBaseColors() == null) {
            throw new IOException("Variant '" + variantName + "' has no base colors");
        }
        
        // Validate required face mappings
        String[] requiredFaces = {
            "HEAD_FRONT", "HEAD_BACK", "HEAD_LEFT", "HEAD_RIGHT", "HEAD_TOP", "HEAD_BOTTOM",
            "BODY_FRONT", "BODY_BACK", "BODY_LEFT", "BODY_RIGHT", "BODY_TOP", "BODY_BOTTOM"
        };
        
        for (String requiredFace : requiredFaces) {
            if (!variant.getFaceMappings().containsKey(requiredFace)) {
                throw new IOException("Variant '" + variantName + "' missing required face: " + requiredFace);
            }
            
            StonebreakTextureDefinition.AtlasCoordinate coord = variant.getFaceMappings().get(requiredFace);
            if (coord.getAtlasX() < 0 || coord.getAtlasX() >= 16 ||
                coord.getAtlasY() < 0 || coord.getAtlasY() >= 16) {
                throw new IOException("Variant '" + variantName + "' face '" + requiredFace + 
                    "' has invalid coordinates: (" + coord.getAtlasX() + "," + coord.getAtlasY() + 
                    ") - must be within 0-15");
            }
        }
        
        // Validate base colors
        if (variant.getBaseColors().getPrimary() == null || 
            variant.getBaseColors().getSecondary() == null || 
            variant.getBaseColors().getAccent() == null) {
            throw new IOException("Variant '" + variantName + "' missing required base colors");
        }
        
        // Validate drawing instructions if present
        if (variant.getDrawingInstructions() != null) {
            validateDrawingInstructions(variant.getDrawingInstructions(), variantName);
            
            // Check for missing drawing instructions for mapped faces
            for (String faceName : variant.getFaceMappings().keySet()) {
                if (!variant.getDrawingInstructions().containsKey(faceName)) {
                    System.err.println("[StonebreakTextureLoader] WARNING: Variant '" + variantName + 
                        "' has face mapping '" + faceName + "' but no corresponding drawing instructions!");
                    System.err.println("  This will cause fallback texture generation for this part.");
                }
            }
        }
        
        System.out.println("[StonebreakTextureLoader] Variant '" + variantName + "' validation passed");
    }
    
    /**
     * Validate drawing instructions for a cow variant.
     */
    private static void validateDrawingInstructions(Map<String, StonebreakTextureDefinition.DrawingInstructions> instructions, String variantName) throws IOException {
        for (Map.Entry<String, StonebreakTextureDefinition.DrawingInstructions> entry : instructions.entrySet()) {
            String bodyPart = entry.getKey();
            StonebreakTextureDefinition.DrawingInstructions instruction = entry.getValue();
            
            if (instruction == null) {
                throw new IOException("Variant '" + variantName + "' has null drawing instructions for " + bodyPart);
            }
            
            if (instruction.getBaseTexture() == null) {
                throw new IOException("Variant '" + variantName + "' " + bodyPart + " missing base texture information");
            }
            
            if (instruction.getBaseTexture().getFillColor() == null) {
                throw new IOException("Variant '" + variantName + "' " + bodyPart + " missing base texture fill color");
            }
        }
    }
    
    /**
     * Cancel all active texture loading operations.
     * 
     * @param mayInterruptIfRunning Whether to interrupt running loads
     * @return Number of operations cancelled
     */
    public static int cancelAllLoads(boolean mayInterruptIfRunning) {
        System.out.println("[StonebreakTextureLoader] Cancelling all active loads...");
        
        int cancelledCount = 0;
        for (Map.Entry<String, CompletableFuture<StonebreakTextureDefinition.CowVariant>> entry : activeLoads.entrySet()) {
            String variantName = entry.getKey();
            CompletableFuture<StonebreakTextureDefinition.CowVariant> future = entry.getValue();
            
            if (future.cancel(mayInterruptIfRunning)) {
                cancelledCount++;
                System.out.println("[StonebreakTextureLoader] Cancelled load for: " + variantName);
            }
        }
        
        activeLoads.clear();
        return cancelledCount;
    }
    
    /**
     * Cancel loading for a specific texture variant.
     * 
     * @param variantName The variant to cancel loading for
     * @param mayInterruptIfRunning Whether to interrupt if currently running
     * @return true if the operation was cancelled
     */
    public static boolean cancelLoad(String variantName, boolean mayInterruptIfRunning) {
        CompletableFuture<StonebreakTextureDefinition.CowVariant> future = activeLoads.remove(variantName);
        if (future != null) {
            boolean cancelled = future.cancel(mayInterruptIfRunning);
            if (cancelled) {
                System.out.println("[StonebreakTextureLoader] Cancelled load for: " + variantName);
            }
            return cancelled;
        }
        return false;
    }
    
    /**
     * Check if a texture variant is currently being loaded.
     * 
     * @param variantName The variant to check
     * @return true if currently loading
     */
    public static boolean isLoading(String variantName) {
        return activeLoads.containsKey(variantName);
    }
    
    /**
     * Get the number of active loading operations.
     * 
     * @return Number of variants currently being loaded
     */
    public static int getActiveLoadCount() {
        return activeLoads.size();
    }
    
    /**
     * Shutdown the async texture loading system gracefully.
     * This should be called when the application is shutting down.
     */
    public static void shutdown() {
        System.out.println("[StonebreakTextureLoader] Shutting down async loading system...");
        
        shutdownRequested.set(true);
        
        // Cancel all active loads
        int cancelled = cancelAllLoads(true);
        System.out.println("[StonebreakTextureLoader] Cancelled " + cancelled + " active loads");
        
        // Shutdown executor
        loadingExecutor.shutdown();
        try {
            if (!loadingExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                loadingExecutor.shutdownNow();
                System.out.println("[StonebreakTextureLoader] Forced shutdown of loading executor");
            } else {
                System.out.println("[StonebreakTextureLoader] Loading executor shutdown gracefully");
            }
        } catch (InterruptedException e) {
            loadingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Clear the texture cache.
     * Enhanced to handle async state properly.
     */
    public static void clearCache() {
        System.out.println("[StonebreakTextureLoader] Clearing texture cache. Current cached variants: " + cachedVariants.keySet());
        
        // Cancel any active loads that might be caching results
        cancelAllLoads(false);
        
        cachedVariants.clear();
    }
    
    /**
     * Debug method to get current cache status including async state.
     */
    public static void printCacheStatus() {
        System.out.println("[StonebreakTextureLoader] Cache Status:");
        System.out.println("  Available variants: " + VARIANT_FILE_PATHS.keySet());
        System.out.println("  Cached variants: " + cachedVariants.keySet());
        System.out.println("  Active loads: " + activeLoads.keySet());
        System.out.println("  Shutdown requested: " + shutdownRequested.get());
        System.out.println("  Executor shutdown: " + loadingExecutor.isShutdown());
        
        for (Map.Entry<String, StonebreakTextureDefinition.CowVariant> entry : cachedVariants.entrySet()) {
            String variantName = entry.getKey();
            StonebreakTextureDefinition.CowVariant variant = entry.getValue();
            String displayName = variant != null ? variant.getDisplayName() : "null";
            System.out.println("    " + variantName + " -> " + displayName);
        }
        
        if (!activeLoads.isEmpty()) {
            System.out.println("  Currently loading:");
            for (String variantName : activeLoads.keySet()) {
                System.out.println("    " + variantName);
            }
        }
    }
    
}