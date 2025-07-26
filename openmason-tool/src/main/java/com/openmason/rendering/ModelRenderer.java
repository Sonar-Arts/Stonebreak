package com.openmason.rendering;

import com.openmason.model.StonebreakModel;
import com.openmason.model.stonebreak.StonebreakModelDefinition;
import com.openmason.texture.stonebreak.StonebreakTextureDefinition;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-level model renderer that integrates the buffer management system
 * with the existing StonebreakModel architecture. Provides a clean interface
 * for rendering models with real-time texture variant switching.
 * 
 * This class maintains 1:1 rendering parity with Stonebreak's EntityRenderer
 * while providing the advanced buffer management needed for Open Mason.
 */
public class ModelRenderer implements AutoCloseable {
    private final Map<String, VertexArray> modelPartVAOs = new ConcurrentHashMap<>();
    private final Map<String, String> currentTextureVariants = new ConcurrentHashMap<>();
    private boolean initialized = false;
    private String debugPrefix;
    
    // Performance tracking
    private long totalRenderCalls = 0;
    private long lastRenderTime = 0;
    
    /**
     * Creates a new ModelRenderer.
     * 
     * @param debugPrefix Prefix for debug names to identify this renderer's resources
     */
    public ModelRenderer(String debugPrefix) {
        this.debugPrefix = debugPrefix != null ? debugPrefix : "ModelRenderer";
    }
    
    /**
     * Initializes the renderer. Must be called before any rendering operations.
     */
    public void initialize() {
        if (initialized) {
            return;
        }
        
        initialized = true;
        System.out.println("ModelRenderer initialized: " + debugPrefix);
    }
    
    /**
     * Prepares a model for rendering by creating all necessary OpenGL buffers.
     * This method analyzes the model structure and creates optimized VAOs for each part.
     * 
     * @param model The StonebreakModel to prepare
     * @return True if preparation was successful, false otherwise
     */
    public boolean prepareModel(StonebreakModel model) {
        if (!initialized) {
            throw new IllegalStateException("ModelRenderer not initialized");
        }
        
        try {
            // Get model parts from the definition
            for (StonebreakModel.BodyPart bodyPart : model.getBodyParts()) {
                prepareModelPart(bodyPart.getModelPart(), model.getTextureDefinition());
            }
            
            System.out.println("Model prepared successfully: " + model.getVariantName() + 
                              " (" + modelPartVAOs.size() + " parts)");
            return true;
            
        } catch (Exception e) {
            System.err.println("Failed to prepare model " + model.getVariantName() + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Prepares a single model part for rendering.
     * 
     * @param bodyPart The body part definition from the model
     * @param textureDefinition The texture definition for UV mapping
     */
    private void prepareModelPart(StonebreakModelDefinition.ModelPart bodyPart, 
                                StonebreakTextureDefinition.CowVariant textureDefinition) {
        String partName = bodyPart.getName();
        String vaoKey = debugPrefix + "_" + partName;
        
        // Skip if already prepared
        if (modelPartVAOs.containsKey(partName)) {
            return;
        }
        
        // Use the model part directly
        StonebreakModelDefinition.ModelPart modelPart = bodyPart;
        
        // Generate vertex data
        float[] vertices = modelPart.getVertices();
        int[] indices = modelPart.getIndices();
        
        // Create VAO with all buffers  
        VertexArray vao = VertexArray.fromModelPart(
            vertices, indices, null, partName, vaoKey
        );
        
        // Validate the VAO
        VertexArray.ValidationResult validation = vao.validate();
        if (!validation.isValid()) {
            System.err.println("VAO validation failed for " + partName + ":");
            for (String error : validation.getErrors()) {
                System.err.println("  ERROR: " + error);
            }
            for (String warning : validation.getWarnings()) {
                System.err.println("  WARNING: " + warning);
            }
        }
        
        modelPartVAOs.put(partName, vao);
        currentTextureVariants.put(partName, "default");
        
        System.out.println("Prepared model part: " + partName + 
                          " (vertices: " + vertices.length / 3 + 
                          ", triangles: " + indices.length / 3 + ")");
    }
    
    /**
     * Converts a ModelPart definition to a ModelPart for vertex generation.
     * This bridges the gap between the high-level body part definitions
     * and the low-level model part vertex generation.
     * 
     * @param bodyPart The body part to convert
     * @return ModelPart suitable for vertex generation
     */
    private StonebreakModelDefinition.ModelPart convertModelPartToModelPart(StonebreakModelDefinition.ModelPart bodyPart) {
        // Create position and size from body part bounds
        StonebreakModelDefinition.Position position = new StonebreakModelDefinition.Position(
            bodyPart.getPositionVector().x + bodyPart.getSizeVector().x / 2,
            bodyPart.getPositionVector().y + bodyPart.getSizeVector().y / 2,
            bodyPart.getPositionVector().z + bodyPart.getSizeVector().z / 2
        );
        
        StonebreakModelDefinition.Size size = new StonebreakModelDefinition.Size(
            bodyPart.getSizeVector().x,
            bodyPart.getSizeVector().y,
            bodyPart.getSizeVector().z
        );
        
        return new StonebreakModelDefinition.ModelPart(
            bodyPart.getName(),
            position,
            size,
            bodyPart.getTexture()
        );
    }
    
    /**
     * Renders a model with the specified texture variant.
     * Automatically handles texture variant switching and buffer updates.
     * 
     * @param model The model to render
     * @param textureVariant The texture variant to use (e.g., "default", "angus", "highland")
     */
    public void renderModel(StonebreakModel model, String textureVariant) {
        if (!initialized) {
            throw new IllegalStateException("ModelRenderer not initialized");
        }
        
        // Update texture variants if needed
        updateTextureVariants(model, textureVariant);
        
        // Render each model part
        for (StonebreakModel.BodyPart bodyPart : model.getBodyParts()) {
            renderModelPart(bodyPart.getName());
        }
        
        totalRenderCalls++;
        lastRenderTime = System.currentTimeMillis();
    }
    
    /**
     * Renders a single model part by name.
     * 
     * @param partName The name of the part to render
     */
    public void renderModelPart(String partName) {
        VertexArray vao = modelPartVAOs.get(partName);
        if (vao != null && vao.isValid()) {
            vao.renderTriangles();
        } else {
            System.err.println("Cannot render part '" + partName + "': VAO not found or invalid");
        }
    }
    
    /**
     * Updates texture variants for all model parts if the variant has changed.
     * 
     * @param model The model being rendered
     * @param textureVariant The new texture variant
     */
    private void updateTextureVariants(StonebreakModel model, String textureVariant) {
        for (StonebreakModel.BodyPart bodyPart : model.getBodyParts()) {
            String partName = bodyPart.getName();
            String currentVariant = currentTextureVariants.get(partName);
            
            if (!textureVariant.equals(currentVariant)) {
                VertexArray vao = modelPartVAOs.get(partName);
                if (vao != null) {
                    vao.updateTextureVariant(model.getTextureDefinition(), partName, textureVariant);
                    currentTextureVariants.put(partName, textureVariant);
                }
            }
        }
    }
    
    /**
     * Checks if a model is prepared for rendering.
     * 
     * @param model The model to check
     * @return True if all model parts are prepared, false otherwise
     */
    public boolean isModelPrepared(StonebreakModel model) {
        for (StonebreakModel.BodyPart bodyPart : model.getBodyParts()) {
            if (!modelPartVAOs.containsKey(bodyPart.getName())) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Gets rendering statistics for this renderer.
     * 
     * @return Statistics about rendering performance and resource usage
     */
    public RenderingStatistics getStatistics() {
        return new RenderingStatistics(
            modelPartVAOs.size(),
            totalRenderCalls,
            lastRenderTime,
            currentTextureVariants.size(),
            initialized
        );
    }
    
    /**
     * Validates all VAOs and returns any issues found.
     * 
     * @return Map of part names to validation results
     */
    public Map<String, VertexArray.ValidationResult> validateAllVAOs() {
        Map<String, VertexArray.ValidationResult> results = new HashMap<>();
        
        for (Map.Entry<String, VertexArray> entry : modelPartVAOs.entrySet()) {
            results.put(entry.getKey(), entry.getValue().validate());
        }
        
        return results;
    }
    
    /**
     * Cleans up all OpenGL resources associated with this renderer.
     */
    @Override
    public void close() {
        System.out.println("Cleaning up ModelRenderer: " + debugPrefix + 
                          " (" + modelPartVAOs.size() + " VAOs)");
        
        for (VertexArray vao : modelPartVAOs.values()) {
            try {
                vao.close();
            } catch (Exception e) {
                System.err.println("Error cleaning up VAO: " + e.getMessage());
            }
        }
        
        modelPartVAOs.clear();
        currentTextureVariants.clear();
        initialized = false;
    }
    
    // Getters
    public boolean isInitialized() { return initialized; }
    public String getDebugPrefix() { return debugPrefix; }
    public int getModelPartCount() { return modelPartVAOs.size(); }
    public long getTotalRenderCalls() { return totalRenderCalls; }
    public long getLastRenderTime() { return lastRenderTime; }
    
    /**
     * Statistics class for rendering performance monitoring.
     */
    public static class RenderingStatistics {
        public final int modelPartCount;
        public final long totalRenderCalls;
        public final long lastRenderTime;
        public final int textureVariantCount;
        public final boolean initialized;
        
        public RenderingStatistics(int modelPartCount, long totalRenderCalls, 
                                 long lastRenderTime, int textureVariantCount, boolean initialized) {
            this.modelPartCount = modelPartCount;
            this.totalRenderCalls = totalRenderCalls;
            this.lastRenderTime = lastRenderTime;
            this.textureVariantCount = textureVariantCount;
            this.initialized = initialized;
        }
        
        @Override
        public String toString() {
            return String.format(
                "RenderingStatistics{parts=%d, renders=%d, lastRender=%d, variants=%d, init=%b}",
                modelPartCount, totalRenderCalls, lastRenderTime, textureVariantCount, initialized
            );
        }
    }
}