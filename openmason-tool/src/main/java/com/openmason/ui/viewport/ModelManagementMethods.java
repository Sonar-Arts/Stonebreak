package com.openmason.ui.viewport;

import com.openmason.model.StonebreakModel;
import com.stonebreak.textures.CowTextureDefinition;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/**
 * Model management methods for OpenMason3DViewport.
 * These methods will be integrated into the main viewport class.
 */
public class ModelManagementMethods {
    
    private static final Logger logger = LoggerFactory.getLogger(ModelManagementMethods.class);
    
    /**
     * Loads a cow model with the specified texture variant.
     * 
     * @param textureVariant The texture variant to load ("default", "angus", "highland", "jersey")
     * @param viewport The viewport instance for callbacks
     * @return CompletableFuture that resolves when the model is loaded
     */
    public static CompletableFuture<StonebreakModel> loadCowModelAsync(String textureVariant, OpenMason3DViewport viewport) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Loading cow model with texture variant: {}", textureVariant);
                
                // Create StonebreakModel from resources
                StonebreakModel model = StonebreakModel.loadFromResources(
                    "standard_cow", // Model path
                    textureVariant,  // Texture variant
                    textureVariant   // Variant name
                );
                
                // Update viewport directly (no longer using JavaFX threading)
                viewport.setCurrentModel(model);
                viewport.setCurrentTextureVariant(textureVariant);
                logger.info("Cow model loaded successfully: {}", textureVariant);
                
                return model;
                
            } catch (Exception e) {
                logger.error("Failed to load cow model with variant: {}", textureVariant, e);
                throw new RuntimeException("Failed to load cow model: " + textureVariant, e);
            }
        });
    }
    
    /**
     * Loads all 4 cow texture variants in parallel.
     * 
     * @param viewport The viewport instance for callbacks
     * @return CompletableFuture that resolves when all variants are loaded
     */
    public static CompletableFuture<Map<String, StonebreakModel>> loadAllCowVariantsAsync(OpenMason3DViewport viewport) {
        String[] variants = {"default", "angus", "highland", "jersey"};
        
        List<CompletableFuture<Map.Entry<String, StonebreakModel>>> futures = new ArrayList<>();
        
        for (String variant : variants) {
            CompletableFuture<Map.Entry<String, StonebreakModel>> future = 
                loadCowModelAsync(variant, viewport)
                    .thenApply(model -> Map.entry(variant, model));
            futures.add(future);
        }
        
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0]));
        
        return allFutures.thenApply(v -> {
            Map<String, StonebreakModel> results = new HashMap<>();
            
            for (CompletableFuture<Map.Entry<String, StonebreakModel>> future : futures) {
                try {
                    Map.Entry<String, StonebreakModel> entry = future.get();
                    results.put(entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    logger.error("Failed to get cow variant from future", e);
                }
            }
            
            logger.info("Loaded {} cow variants", results.size());
            return results;
        });
    }
    
    /**
     * Gets the bounding box of a model for camera positioning.
     * 
     * @param model The model to analyze
     * @return Model bounding box, or null if model is null
     */
    public static Vector3f[] getModelBounds(StonebreakModel model) {
        if (model == null) {
            return null;
        }
        
        // Calculate overall model bounds from all body parts
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE, maxZ = Float.MIN_VALUE;
        
        for (StonebreakModel.BodyPart part : model.getBodyParts()) {
            StonebreakModel.BoundingBox bounds = part.getBounds();
            
            minX = Math.min(minX, bounds.getMinX());
            minY = Math.min(minY, bounds.getMinY());
            minZ = Math.min(minZ, bounds.getMinZ());
            
            maxX = Math.max(maxX, bounds.getMaxX());
            maxY = Math.max(maxY, bounds.getMaxY());
            maxZ = Math.max(maxZ, bounds.getMaxZ());
        }
        
        return new Vector3f[]{
            new Vector3f(minX, minY, minZ), // Min corner
            new Vector3f(maxX, maxY, maxZ)  // Max corner
        };
    }
    
    /**
     * Gets model information and statistics.
     * 
     * @param model The model to analyze
     * @param textureVariant Current texture variant
     * @param isPrepared Whether the model is prepared for rendering
     * @return Model information, or null if model is null
     */
    public static ModelInfo getModelInfo(StonebreakModel model, String textureVariant, boolean isPrepared) {
        if (model == null) {
            return null;
        }
        
        List<StonebreakModel.BodyPart> bodyParts = model.getBodyParts();
        Map<String, CowTextureDefinition.AtlasCoordinate> faceMappings = model.getFaceMappings();
        
        return new ModelInfo(
            model.getVariantName(),
            textureVariant,
            bodyParts.size(),
            faceMappings.size(),
            isPrepared
        );
    }
    
    /**
     * Information about a loaded model.
     */
    public static class ModelInfo {
        public final String variantName;
        public final String textureVariant;
        public final int bodyPartCount;
        public final int faceMappingCount;
        public final boolean isPrepared;
        
        public ModelInfo(String variantName, String textureVariant, int bodyPartCount, 
                        int faceMappingCount, boolean isPrepared) {
            this.variantName = variantName;
            this.textureVariant = textureVariant;
            this.bodyPartCount = bodyPartCount;
            this.faceMappingCount = faceMappingCount;
            this.isPrepared = isPrepared;
        }
        
        @Override
        public String toString() {
            return String.format(
                "ModelInfo{variant='%s', texture='%s', parts=%d, mappings=%d, prepared=%b}",
                variantName, textureVariant, bodyPartCount, faceMappingCount, isPrepared
            );
        }
    }
}