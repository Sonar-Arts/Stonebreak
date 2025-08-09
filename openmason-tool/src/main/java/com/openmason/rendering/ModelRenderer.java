package com.openmason.rendering;

import com.openmason.model.StonebreakModel;
import com.stonebreak.model.ModelDefinition;
import com.stonebreak.textures.CowTextureDefinition;

import java.util.HashMap;
import java.util.List;
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
    
    // Context validation tracking
    private boolean contextValidationEnabled = true;
    private long lastContextValidationTime = 0;
    
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
        
        // Validate OpenGL context before model preparation
        if (contextValidationEnabled) {
            // First check if we have any OpenGL context at all
            if (!OpenGLValidator.hasValidOpenGLContext()) {
                System.err.println("ModelRenderer.prepareModel: No valid OpenGL context available");
                return false; // Return false instead of throwing exception
            }
            
            List<String> contextIssues = OpenGLValidator.validateContext("prepareModel");
            if (!contextIssues.isEmpty()) {
                System.err.println("OpenGL context validation failed in prepareModel:");
                for (String issue : contextIssues) {
                    System.err.println("  - " + issue);
                }
                // Return false instead of throwing exception - let caller handle gracefully
                return false;
            }
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
    private void prepareModelPart(ModelDefinition.ModelPart bodyPart, 
                                CowTextureDefinition.CowVariant textureDefinition) {
        String partName = bodyPart.getName();
        String vaoKey = debugPrefix + "_" + partName;
        
        // Skip if already prepared
        if (modelPartVAOs.containsKey(partName)) {
            return;
        }
        
        // Validate OpenGL context before model part preparation
        if (contextValidationEnabled) {
            List<String> contextIssues = OpenGLValidator.validateContext("prepareModelPart:" + partName);
            if (!contextIssues.isEmpty()) {
                System.err.println("OpenGL context validation failed in prepareModelPart for " + partName + ":");
                for (String issue : contextIssues) {
                    System.err.println("  - " + issue);
                }
                throw new RuntimeException("Cannot prepare model part '" + partName + "' due to invalid OpenGL context");
            }
        }
        
        // Use the model part directly
        ModelDefinition.ModelPart modelPart = bodyPart;
        
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
    private ModelDefinition.ModelPart convertModelPartToModelPart(ModelDefinition.ModelPart bodyPart) {
        // Create position and size from body part bounds
        ModelDefinition.Position position = new ModelDefinition.Position(
            bodyPart.getPositionVector().x + bodyPart.getSizeVector().x / 2,
            bodyPart.getPositionVector().y + bodyPart.getSizeVector().y / 2,
            bodyPart.getPositionVector().z + bodyPart.getSizeVector().z / 2
        );
        
        ModelDefinition.Size size = new ModelDefinition.Size(
            bodyPart.getSizeVector().x,
            bodyPart.getSizeVector().y,
            bodyPart.getSizeVector().z
        );
        
        return new ModelDefinition.ModelPart(
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
        
        // Validate OpenGL context and rendering state before rendering
        if (contextValidationEnabled) {
            List<String> contextIssues = OpenGLValidator.validateContext("renderModel");
            if (!contextIssues.isEmpty()) {
                System.err.println("OpenGL context validation failed in renderModel:");
                for (String issue : contextIssues) {
                    System.err.println("  - " + issue);
                }
                throw new RuntimeException("Cannot render model due to invalid OpenGL context");
            }
            
            // Also validate rendering state for optimal performance
            List<String> stateIssues = OpenGLValidator.validateRenderingState();
            if (!stateIssues.isEmpty()) {
                System.err.println("OpenGL rendering state validation issues in renderModel:");
                for (String issue : stateIssues) {
                    System.err.println("  - WARNING: " + issue);
                }
                // Note: These are warnings, not fatal errors, so we continue rendering
            }
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
        // Validate OpenGL context before rendering individual parts
        if (contextValidationEnabled) {
            List<String> contextIssues = OpenGLValidator.validateContext("renderModelPart:" + partName);
            if (!contextIssues.isEmpty()) {
                System.err.println("OpenGL context validation failed in renderModelPart for " + partName + ":");
                for (String issue : contextIssues) {
                    System.err.println("  - " + issue);
                }
                throw new RuntimeException("Cannot render model part '" + partName + "' due to invalid OpenGL context");
            }
        }
        
        VertexArray vao = modelPartVAOs.get(partName);
        if (vao != null && vao.isValid()) {
            // Additional VAO validation before rendering
            if (contextValidationEnabled) {
                List<String> vaoIssues = OpenGLValidator.validateVertexArray(vao);
                if (!vaoIssues.isEmpty()) {
                    System.err.println("VAO validation issues for part " + partName + ":");
                    for (String issue : vaoIssues) {
                        System.err.println("  - WARNING: " + issue);
                    }
                    // Continue rendering despite warnings, but the user is informed
                }
            }
            
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
    
    /**
     * Enables or disables OpenGL context validation.
     * Validation can be disabled for performance in production builds.
     * 
     * @param enabled Whether to enable context validation
     */
    public void setContextValidationEnabled(boolean enabled) {
        this.contextValidationEnabled = enabled;
        if (enabled) {
            System.out.println("OpenGL context validation enabled for ModelRenderer: " + debugPrefix);
        } else {
            System.out.println("OpenGL context validation disabled for ModelRenderer: " + debugPrefix);
        }
    }
    
    /**
     * Performs a comprehensive validation of the current rendering state.
     * This method can be called periodically to check system health.
     * 
     * @return Validation report with any issues found
     */
    public ValidationReport validateRenderingSystem() {
        ValidationReport report = new ValidationReport();
        
        // Basic context validation
        report.contextIssues.addAll(OpenGLValidator.validateContext("validateRenderingSystem"));
        
        // Validate all VAOs
        for (Map.Entry<String, VertexArray> entry : modelPartVAOs.entrySet()) {
            String partName = entry.getKey();
            VertexArray vao = entry.getValue();
            
            List<String> vaoIssues = OpenGLValidator.validateVertexArray(vao);
            for (String issue : vaoIssues) {
                report.vaoIssues.add(partName + ": " + issue);
            }
        }
        
        // Check initialization state
        if (!initialized) {
            report.stateIssues.add("ModelRenderer not initialized");
        }
        
        // Check for resource consistency
        if (modelPartVAOs.size() != currentTextureVariants.size()) {
            report.stateIssues.add("Mismatch between VAO count (" + modelPartVAOs.size() + 
                                 ") and texture variant count (" + currentTextureVariants.size() + ")");
        }
        
        lastContextValidationTime = System.currentTimeMillis();
        return report;
    }
    
    // Getters
    public boolean isInitialized() { return initialized; }
    public String getDebugPrefix() { return debugPrefix; }
    public int getModelPartCount() { return modelPartVAOs.size(); }
    public long getTotalRenderCalls() { return totalRenderCalls; }
    public long getLastRenderTime() { return lastRenderTime; }
    public boolean isContextValidationEnabled() { return contextValidationEnabled; }
    public long getLastContextValidationTime() { return lastContextValidationTime; }
    
    /**
     * Validation report for ModelRenderer system health checks.
     */
    public static class ValidationReport {
        public final List<String> contextIssues = new java.util.ArrayList<>();
        public final List<String> vaoIssues = new java.util.ArrayList<>();
        public final List<String> stateIssues = new java.util.ArrayList<>();
        
        public boolean hasIssues() {
            return !contextIssues.isEmpty() || !vaoIssues.isEmpty() || !stateIssues.isEmpty();
        }
        
        public int getTotalIssueCount() {
            return contextIssues.size() + vaoIssues.size() + stateIssues.size();
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== ModelRenderer Validation Report ===").append("\n");
            
            if (!contextIssues.isEmpty()) {
                sb.append("OpenGL Context Issues (").append(contextIssues.size()).append("):").append("\n");
                for (String issue : contextIssues) {
                    sb.append("  - ").append(issue).append("\n");
                }
            }
            
            if (!vaoIssues.isEmpty()) {
                sb.append("VAO Issues (").append(vaoIssues.size()).append("):").append("\n");
                for (String issue : vaoIssues) {
                    sb.append("  - ").append(issue).append("\n");
                }
            }
            
            if (!stateIssues.isEmpty()) {
                sb.append("State Issues (").append(stateIssues.size()).append("):").append("\n");
                for (String issue : stateIssues) {
                    sb.append("  - ").append(issue).append("\n");
                }
            }
            
            if (!hasIssues()) {
                sb.append("No issues detected - ModelRenderer is healthy!").append("\n");
            }
            
            sb.append("===========================================");
            return sb.toString();
        }
    }
    
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