package com.openmason.rendering;

import com.openmason.model.StonebreakModel;
import com.openmason.model.stonebreak.StonebreakModelDefinition;

/**
 * Integration example demonstrating how to use the OpenGL buffer management system
 * with the existing StonebreakModel architecture. This class shows the complete
 * workflow from model loading to rendering with real-time texture variant switching.
 * 
 * This example maintains 1:1 rendering parity with Stonebreak's EntityRenderer
 * while providing the advanced buffer management needed for Open Mason Phase 3.
 */
public class RenderingIntegrationExample {
    
    /**
     * Demonstrates the complete workflow for setting up and using the buffer system.
     * This example shows how to:
     * 1. Initialize the buffer management system
     * 2. Load and prepare models for rendering
     * 3. Render models with texture variant switching
     * 4. Validate the system and handle errors
     * 5. Clean up resources properly
     */
    public static void demonstrateBufferSystemUsage() {
        System.out.println("=== OpenGL Buffer Management System Integration Example ===");
        
        // Step 1: Initialize the buffer management system
        BufferManager bufferManager = BufferManager.getInstance();
        bufferManager.setMemoryTrackingEnabled(true);
        bufferManager.setLeakDetectionEnabled(true);
        
        ModelRenderer renderer = null;
        
        try {
            // Step 2: Create and initialize a model renderer
            renderer = new ModelRenderer("ExampleRenderer");
            renderer.initialize();
            
            // Step 3: Load cow models with different texture variants
            StonebreakModel defaultCow = StonebreakModel.loadFromResources(
                "/stonebreak/models/cow/standard_cow.json",
                "/stonebreak/textures/mobs/cow/default_cow.json",
                "default"
            );
            
            StonebreakModel angusCow = StonebreakModel.loadFromResources(
                "/stonebreak/models/cow/standard_cow.json",
                "/stonebreak/textures/mobs/cow/angus_cow.json",
                "angus"
            );
            
            // Step 4: Prepare models for rendering (creates OpenGL buffers)
            System.out.println("\nPreparing models for rendering...");
            boolean defaultPrepared = renderer.prepareModel(defaultCow);
            boolean angusPrepared = renderer.prepareModel(angusCow);
            
            if (!defaultPrepared || !angusPrepared) {
                System.err.println("Failed to prepare models for rendering");
                return;
            }
            
            // Step 5: Validate model compatibility
            System.out.println("\nValidating models...");
            validateModel(defaultCow, "Default Cow");
            validateModel(angusCow, "Angus Cow");
            
            // Step 6: Demonstrate rendering with texture variant switching
            System.out.println("\nDemonstrating rendering workflow...");
            
            // Render default cow
            System.out.println("Rendering default cow variant...");
            renderer.renderModel(defaultCow, "default");
            
            // Switch to angus texture variant (demonstrates real-time switching)
            System.out.println("Switching to angus texture variant...");
            renderer.renderModel(defaultCow, "angus");
            
            // Render different model with its texture
            System.out.println("Rendering angus cow model...");
            renderer.renderModel(angusCow, "angus");
            
            // Step 7: Demonstrate individual part rendering
            System.out.println("\nDemonstrating individual part rendering...");
            renderer.renderModelPart("head");
            renderer.renderModelPart("body");
            renderer.renderModelPart("leg1");
            
            // Step 8: Validate the rendering system
            System.out.println("\nValidating rendering system...");
            validateRenderingSystem(renderer, bufferManager);
            
            // Step 9: Display performance statistics
            displayStatistics(renderer, bufferManager);
            
        } catch (Exception e) {
            System.err.println("Error during buffer system demonstration: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Step 10: Clean up resources
            System.out.println("\nCleaning up resources...");
            if (renderer != null) {
                renderer.close();
            }
            
            // Final validation to check for leaks
            System.out.println("\nFinal system validation...");
            OpenGLValidator.ValidationReport finalReport = OpenGLValidator.validateBufferSystem(bufferManager);
            if (finalReport.hasIssues()) {
                System.err.println("Issues found after cleanup:");
                System.err.println(finalReport);
            } else {
                System.out.println("System clean - no resource leaks detected!");
            }
        }
        
        System.out.println("=== Buffer System Integration Example Complete ===");
    }
    
    /**
     * Validates a model and prints detailed information about its structure.
     * 
     * @param model The model to validate
     * @param name Display name for the model
     */
    private static void validateModel(StonebreakModel model, String name) {
        System.out.println("Validating " + name + ":");
        
        StonebreakModel.ValidationResult validation = model.validate();
        System.out.println("  Face mappings: " + validation.getFaceMappingCount());
        System.out.println("  Body parts: " + model.getBodyParts().size());
        System.out.println("  Errors: " + validation.getErrors().size());
        System.out.println("  Warnings: " + validation.getWarnings().size());
        
        if (!validation.isValid()) {
            System.out.println("  Validation failed:");
            for (String error : validation.getErrors()) {
                System.out.println("    ERROR: " + error);
            }
        }
        
        for (String warning : validation.getWarnings()) {
            System.out.println("    WARNING: " + warning);
        }
        
        // Display model structure
        System.out.println("  Model structure:");
        for (StonebreakModel.BodyPart part : model.getBodyParts()) {
            System.out.println("    - " + part.getName() + 
                              " (texture: " + part.getTextureKey() + ")");
        }
    }
    
    /**
     * Validates the rendering system and displays any issues found.
     * 
     * @param renderer The model renderer to validate
     * @param bufferManager The buffer manager to validate
     */
    private static void validateRenderingSystem(ModelRenderer renderer, BufferManager bufferManager) {
        // Validate OpenGL context and buffer system
        OpenGLValidator.ValidationReport systemReport = OpenGLValidator.validateBufferSystem(bufferManager);
        
        if (systemReport.hasIssues()) {
            System.err.println("System validation issues found:");
            System.err.println(systemReport);
        } else {
            System.out.println("System validation passed - no issues detected");
        }
        
        // Validate all VAOs in the renderer
        var vaoValidation = renderer.validateAllVAOs();
        int vaoIssues = 0;
        
        for (var entry : vaoValidation.entrySet()) {
            VertexArray.ValidationResult result = entry.getValue();
            if (!result.isValid()) {
                System.err.println("VAO validation failed for " + entry.getKey() + ":");
                for (String error : result.getErrors()) {
                    System.err.println("  ERROR: " + error);
                }
                vaoIssues++;
            }
            
            if (!result.getWarnings().isEmpty()) {
                System.out.println("VAO warnings for " + entry.getKey() + ":");
                for (String warning : result.getWarnings()) {
                    System.out.println("  WARNING: " + warning);
                }
            }
        }
        
        if (vaoIssues == 0) {
            System.out.println("All VAOs validated successfully");
        } else {
            System.err.println("VAO validation issues found in " + vaoIssues + " objects");
        }
    }
    
    /**
     * Displays performance and memory statistics for the rendering system.
     * 
     * @param renderer The model renderer
     * @param bufferManager The buffer manager
     */
    private static void displayStatistics(ModelRenderer renderer, BufferManager bufferManager) {
        System.out.println("\n=== Performance Statistics ===");
        
        // Renderer statistics
        ModelRenderer.RenderingStatistics renderStats = renderer.getStatistics();
        System.out.println("Model Renderer:");
        System.out.println("  Model parts prepared: " + renderStats.modelPartCount);
        System.out.println("  Total render calls: " + renderStats.totalRenderCalls);
        System.out.println("  Texture variants loaded: " + renderStats.textureVariantCount);
        System.out.println("  Initialized: " + renderStats.initialized);
        
        // Buffer manager statistics
        BufferManager.BufferManagerStatistics bufferStats = bufferManager.getStatistics();
        System.out.println("\nBuffer Manager:");
        System.out.println("  Active buffers: " + bufferStats.activeBufferCount);
        System.out.println("  Active vertex arrays: " + bufferStats.activeVertexArrayCount);
        System.out.println("  Total buffers created: " + bufferStats.totalBuffersCreated);
        System.out.println("  Total VAOs created: " + bufferStats.totalVertexArraysCreated);
        System.out.println("  Current memory usage: " + formatBytes(bufferStats.currentMemoryUsage));
        System.out.println("  Total memory allocated: " + formatBytes(bufferStats.totalMemoryAllocated));
        System.out.println("  Total memory deallocated: " + formatBytes(bufferStats.totalMemoryDeallocated));
        
        long potentialLeaks = bufferStats.totalMemoryAllocated - bufferStats.totalMemoryDeallocated;
        if (potentialLeaks > 0) {
            System.out.println("  Potential memory leaks: " + formatBytes(potentialLeaks));
        } else {
            System.out.println("  No memory leaks detected");
        }
        
        System.out.println("==============================");
    }
    
    /**
     * Demonstrates advanced buffer management features.
     * This method shows more sophisticated usage patterns.
     */
    public static void demonstrateAdvancedFeatures() {
        System.out.println("\n=== Advanced Buffer Management Features ===");
        
        BufferManager bufferManager = BufferManager.getInstance();
        
        // Demonstrate direct buffer creation and management
        try (VertexBuffer vertexBuf = new VertexBuffer("AdvancedExample_Vertices");
             IndexBuffer indexBuf = new IndexBuffer("AdvancedExample_Indices");
             TextureCoordinateBuffer texCoordBuf = new TextureCoordinateBuffer("AdvancedExample_TexCoords");
             VertexArray vao = new VertexArray("AdvancedExample_VAO")) {
            
            // Create sample geometry (a simple quad)
            float[] vertices = {
                -0.5f, -0.5f, 0.0f,  // bottom-left
                 0.5f, -0.5f, 0.0f,  // bottom-right
                 0.5f,  0.5f, 0.0f,  // top-right
                -0.5f,  0.5f, 0.0f   // top-left
            };
            
            int[] indices = {
                0, 1, 2,  // first triangle
                2, 3, 0   // second triangle
            };
            
            float[] texCoords = {
                0.0f, 0.0f,  // bottom-left
                1.0f, 0.0f,  // bottom-right
                1.0f, 1.0f,  // top-right
                0.0f, 1.0f   // top-left
            };
            
            // Upload data to buffers
            vertexBuf.uploadVertices(vertices);
            indexBuf.uploadIndices(indices);
            texCoordBuf.uploadTextureCoords(texCoords);
            
            // Configure VAO
            vao.setVertexBuffer(vertexBuf);
            vao.setIndexBuffer(indexBuf);
            vao.setTextureCoordinateBuffer(texCoordBuf);
            
            // Validate the configuration
            VertexArray.ValidationResult validation = vao.validate();
            if (validation.isValid()) {
                System.out.println("Advanced VAO configuration successful");
                
                // Demonstrate rendering (would normally be called during render loop)
                // vao.renderTriangles(); // Commented out - would need proper OpenGL context
                
            } else {
                System.err.println("Advanced VAO configuration failed:");
                for (String error : validation.getErrors()) {
                    System.err.println("  ERROR: " + error);
                }
            }
            
            // Display buffer information
            System.out.println("Buffer details:");
            System.out.println("  " + vertexBuf);
            System.out.println("  " + indexBuf);
            System.out.println("  " + texCoordBuf);
            System.out.println("  " + vao);
            
        } catch (Exception e) {
            System.err.println("Error in advanced features demonstration: " + e.getMessage());
        }
        
        // Demonstrate memory monitoring
        BufferManager.BufferManagerStatistics stats = bufferManager.getStatistics();
        System.out.println("Buffer system stats after advanced demo:");
        System.out.println("  Active buffers: " + stats.activeBufferCount);
        System.out.println("  Memory usage: " + formatBytes(stats.currentMemoryUsage));
        
        System.out.println("=== Advanced Features Demo Complete ===");
    }
    
    /**
     * Formats byte count as human-readable string.
     * 
     * @param bytes Byte count
     * @return Formatted string
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    /**
     * Main method to run the integration examples.
     * This would typically be called from a test or demonstration context.
     */
    public static void main(String[] args) {
        System.out.println("OpenGL Buffer Management System Integration Examples");
        System.out.println("Note: These examples require a valid OpenGL context to run fully");
        System.out.println();
        
        // Run the main demonstration
        demonstrateBufferSystemUsage();
        
        // Run advanced features demonstration
        demonstrateAdvancedFeatures();
        
        System.out.println("\nIntegration examples complete!");
    }
}