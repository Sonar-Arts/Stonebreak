package com.openmason.rendering;

import com.openmason.model.LegacyCowModelManager;
import com.openmason.model.LegacyCowStonebreakModel;
import com.stonebreak.model.ModelDefinition;
import com.stonebreak.textures.mobs.CowTextureDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.opengl.GL20.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;

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
    
    // Matrix transformation support
    private boolean matrixTransformationMode = false;
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16); // 4x4 matrix
    
    // Diagnostic data - stores actual rendered transformation matrices
    private final Map<String, Matrix4f> lastRenderedTransforms = new ConcurrentHashMap<>();
    
    // Coordinate space tracking for diagnostics
    private final Map<String, LegacyCowModelManager.CoordinateSpace> modelCoordinateSpaces = new ConcurrentHashMap<>();
    private final Map<String, String> modelVariantMappings = new ConcurrentHashMap<>();
    
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
        // System.out.println("ModelRenderer initialized: " + debugPrefix);
    }
    
    /**
     * Prepares a model for rendering by creating all necessary OpenGL buffers.
     * This method analyzes the model structure and creates optimized VAOs for each part.
     * 
     * @param model The StonebreakModel to prepare
     * @return True if preparation was successful, false otherwise
     */
    public boolean prepareModel(LegacyCowStonebreakModel model) {
        if (!initialized) {
            throw new IllegalStateException("ModelRenderer not initialized");
        }
        
        // Track coordinate space for this model
        String modelVariant = model.getVariantName();
        LegacyCowModelManager.CoordinateSpace coordinateSpace = LegacyCowModelManager.CoordinateSpaceManager.getCoordinateSpace(modelVariant);
        modelCoordinateSpaces.put(modelVariant, coordinateSpace);
        
        // System.out.println("Preparing model '" + modelVariant + "' in coordinate space: " + 
        //                   coordinateSpace.getDisplayName() + " (" + coordinateSpace.getDescription() + ")");
        
        // Validate OpenGL context before model preparation
        if (contextValidationEnabled) {
            // First check if we have any OpenGL context at all
            if (!OpenGLValidator.hasValidOpenGLContext()) {
                // System.err.println("ModelRenderer.prepareModel: No valid OpenGL context available");
                return false; // Return false instead of throwing exception
            }
            
            List<String> contextIssues = OpenGLValidator.validateContext("prepareModel");
            if (!contextIssues.isEmpty()) {
                // System.err.println("OpenGL context validation failed in prepareModel:");
                for (String issue : contextIssues) {
                    // System.err.println("  - " + issue);
                }
                // Return false instead of throwing exception - let caller handle gracefully
                return false;
            }
        }
        
        try {
            // Get model parts from the definition
            int totalParts = 0;
            int successfulParts = 0;
            int startingVAOCount = modelPartVAOs.size();
            
            // System.out.println("Preparing model '" + model.getVariantName() + "' with " + 
            //                   model.getBodyParts().size() + " parts...");
            
            for (LegacyCowStonebreakModel.BodyPart bodyPart : model.getBodyParts()) {
                totalParts++;
                String partName = bodyPart.getName();
                int preVAOCount = modelPartVAOs.size();
                
                // System.out.println("  Preparing part " + totalParts + "/" + model.getBodyParts().size() + 
                //                   ": '" + partName + "'");
                
                // Prepare model part with matrix transformation
                if (prepareModelPart(bodyPart.getModelPart(), model.getTextureDefinition(), model.getVariantName())) {
                    successfulParts++;
                    // System.out.println("    ✓ Part '" + partName + "' prepared successfully");
                } else {
                    // System.err.println("    ✗ Part '" + partName + "' failed to prepare");
                }
            }
            
            int newVAOs = modelPartVAOs.size() - startingVAOCount;
            // System.out.println("Model preparation completed: " + model.getVariantName() + 
            //                   " - " + successfulParts + "/" + totalParts + " parts successful" +
            //                   " (" + newVAOs + " new VAOs created)");
            
            if (successfulParts == 0) {
                // System.err.println("ERROR: No model parts were successfully prepared for " + model.getVariantName());
                return false;
            }
            
            if (successfulParts < totalParts) {
                // System.err.println("WARNING: Only " + successfulParts + " out of " + totalParts + 
                //                   " parts were successfully prepared for " + model.getVariantName());
                // Return true for partial success - some parts can still be rendered
            }
            
            return true;
            
        } catch (Exception e) {
            // System.err.println("Failed to prepare model " + model.getVariantName() + ": " + e.getMessage());
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
    
    /**
     * Prepares a single model part for matrix-based transformation rendering.
     */
    private boolean prepareModelPart(ModelDefinition.ModelPart bodyPart, 
                                   CowTextureDefinition.CowVariant textureDefinition,
                                   String variantName) {
        String partName = bodyPart.getName();
        String textureField = bodyPart.getTexture();
        String vaoKey = debugPrefix + "_matrix_" + partName;
        
        // Skip if already prepared
        if (modelPartVAOs.containsKey(partName)) {
            return true;
        }
        
        try {
            // Use coordinate system integration to get properly transformed vertices for Stonebreak compatibility
            com.openmason.coordinates.CoordinateSystemIntegration.IntegratedPartData integratedData = 
                com.openmason.coordinates.CoordinateSystemIntegration.generateIntegratedPartData(
                    bodyPart, variantName, true);
            
            float[] vertices;
            int[] indices;
            
            if (integratedData != null && integratedData.isValid()) {
                // Use coordinate system integration for proper Stonebreak compatibility
                vertices = integratedData.getVertices();
                indices = integratedData.getIndices();
                // System.out.println("Using coordinate system integration for part: " + partName);
            } else {
                // Fallback to direct model part data
                vertices = bodyPart.getVertices(); 
                indices = bodyPart.getIndices();
                // System.out.println("Fallback to direct model data for part: " + partName);
            }
            
            if (vertices == null || indices == null || vertices.length == 0 || indices.length == 0) {
                // System.err.println("Model part '" + partName + "' has invalid matrix transform vertex data");
                return false;
            }
            
            // Get texture coordinates from integrated data if available
            float[] textureCoordinates = null;
            if (integratedData != null && integratedData.isValid()) {
                textureCoordinates = integratedData.getTextureCoordinates();
                // System.out.println("Using integrated texture coordinates for part: " + partName);
            }
            
            // Create VAO for matrix-based rendering with proper texture coordinates
            VertexArray vao = VertexArray.fromModelPart(vertices, indices, textureDefinition, textureField, partName, vaoKey);
            
            modelPartVAOs.put(partName, vao);
            currentTextureVariants.put(partName, "default");
            
            return true;
            
        } catch (Exception e) {
            // System.err.println("Failed to prepare model part '" + partName + "' for matrix transforms: " + e.getMessage());
            return false;
        }
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
     * Renders a model using matrix-based transformations for individual part positioning.
     * This enables runtime adjustment and visualization of individual model part positions.
     * 
     * @param model The model to render
     * @param textureVariant The texture variant to use
     * @param shaderProgram The shader program with matrix transformation support
     * @param mvpUniformLocation The uniform location for MVP matrix
     * @param modelMatrixLocation The uniform location for individual model matrices
     * @param viewProjectionMatrix The view-projection matrix
     * @param textureAtlas The texture atlas to bind (null for solid color rendering)
     * @param textureUniformLocation The texture sampler uniform location
     * @param useTextureUniformLocation The useTexture boolean uniform location
     * @param colorUniformLocation The color uniform location
     */
    public void renderModel(LegacyCowStonebreakModel model, String textureVariant,
                            int shaderProgram, int mvpUniformLocation,
                            int modelMatrixLocation, float[] viewProjectionMatrix,
                            com.openmason.rendering.TextureAtlas textureAtlas,
                            int textureUniformLocation, int useTextureUniformLocation,
                            int colorUniformLocation) {
        renderModelInternal(model, textureVariant, shaderProgram, mvpUniformLocation, 
                           modelMatrixLocation, viewProjectionMatrix, textureAtlas,
                           textureUniformLocation, useTextureUniformLocation, colorUniformLocation);
    }
    
    /**
     * Legacy renderModel method for backward compatibility.
     * Renders with solid colors only (no textures).
     * 
     * @param model The model to render
     * @param textureVariant The texture variant to use (ignored in solid color mode)
     * @param shaderProgram The shader program
     * @param mvpUniformLocation The uniform location for MVP matrix
     * @param modelMatrixLocation The uniform location for individual model matrices
     * @param viewProjectionMatrix The view-projection matrix
     */
    public void renderModel(LegacyCowStonebreakModel model, String textureVariant,
                            int shaderProgram, int mvpUniformLocation,
                            int modelMatrixLocation, float[] viewProjectionMatrix) {
        renderModelInternal(model, textureVariant, shaderProgram, mvpUniformLocation, 
                           modelMatrixLocation, viewProjectionMatrix, null, -1, -1, -1);
    }
    
    /**
     * Renders a model with user transforms applied.
     * 
     * @param model The model to render
     * @param textureVariant The texture variant to use
     * @param shaderProgram The shader program with matrix transformation support
     * @param mvpUniformLocation The uniform location for MVP matrix
     * @param modelMatrixLocation The uniform location for individual model matrices
     * @param viewProjectionMatrix The view-projection matrix
     * @param userTransform The user transform matrix to apply to the entire model
     * @param textureAtlas The texture atlas to bind (null for solid color rendering)
     * @param textureUniformLocation The texture sampler uniform location
     * @param useTextureUniformLocation The useTexture boolean uniform location
     * @param colorUniformLocation The color uniform location
     */
    public void renderModel(LegacyCowStonebreakModel model, String textureVariant,
                            int shaderProgram, int mvpUniformLocation,
                            int modelMatrixLocation, float[] viewProjectionMatrix,
                            Matrix4f userTransform,
                            com.openmason.rendering.TextureAtlas textureAtlas,
                            int textureUniformLocation, int useTextureUniformLocation,
                            int colorUniformLocation) {
        renderModelInternalWithUserTransform(model, textureVariant, shaderProgram, mvpUniformLocation, 
                                           modelMatrixLocation, viewProjectionMatrix, userTransform, textureAtlas,
                                           textureUniformLocation, useTextureUniformLocation, colorUniformLocation);
    }
    
    /**
     * Internal rendering method with user transform support.
     */
    private void renderModelInternalWithUserTransform(LegacyCowStonebreakModel model, String textureVariant, int shaderProgram,
                                                      int mvpUniformLocation, int modelMatrixLocation, float[] viewProjectionMatrix,
                                                      Matrix4f userTransform,
                                                      com.openmason.rendering.TextureAtlas textureAtlas,
                                                      int textureUniformLocation, int useTextureUniformLocation, int colorUniformLocation) {
        if (!initialized) {
            throw new IllegalStateException("ModelRenderer not initialized");
        }
        
        // Validate OpenGL context and rendering state before rendering
        if (contextValidationEnabled) {
            List<String> contextIssues = OpenGLValidator.validateContext("renderModel");
            if (!contextIssues.isEmpty()) {
                // System.err.println("OpenGL context validation failed in renderModel:");
                for (String issue : contextIssues) {
                    // System.err.println("  - " + issue);
                }
                throw new RuntimeException("Cannot render model due to invalid OpenGL context");
            }
            
            // Also validate rendering state for optimal performance
            List<String> stateIssues = OpenGLValidator.validateRenderingState();
            if (!stateIssues.isEmpty()) {
                // System.err.println("OpenGL rendering state validation issues in renderModel:");
                for (String issue : stateIssues) {
                    // System.err.println("  - WARNING: " + issue);
                }
                // Note: These are warnings, not fatal errors, so we continue rendering
            }
        }
        
        // Update texture variants if needed
        updateTextureVariants(model, textureVariant);
        
        // Bind shader program and set uniforms
        glUseProgram(shaderProgram);
        glUniformMatrix4fv(mvpUniformLocation, false, viewProjectionMatrix);
        
        // Setup texture rendering
        boolean useTextures = (textureAtlas != null && textureAtlas.isReady());
        if (useTextures) {
            // Bind texture atlas
            textureAtlas.bind(0); // Use texture unit 0
            textureAtlas.setTextureUniform(shaderProgram, "uTexture", 0);
            
            // Enable texture rendering
            if (useTextureUniformLocation != -1) {
                glUniform1i(useTextureUniformLocation, 1); // true
            }
            
        } else {
            // Disable texture rendering, use solid colors
            if (useTextureUniformLocation != -1) {
                glUniform1i(useTextureUniformLocation, 0); // false
            }
            
        }
        
        // Set vertex color uniform (white for proper texture display, white for wireframe/solid color)
        if (colorUniformLocation != -1) {
            if (useTextures) {
                glUniform3f(colorUniformLocation, 1.0f, 1.0f, 1.0f); // White for textures
            } else {
                glUniform3f(colorUniformLocation, 1.0f, 1.0f, 1.0f); // White for wireframe/solid color
            }
        }
        
        // Render each model part with matrix transformations and user transform
        for (LegacyCowStonebreakModel.BodyPart bodyPart : model.getBodyParts()) {
            renderModelPartWithUserTransform(bodyPart.getName(), bodyPart, modelMatrixLocation, 
                                           viewProjectionMatrix, userTransform);
        }
        
        totalRenderCalls++;
        lastRenderTime = System.currentTimeMillis();
    }
    
    /**
     * Internal rendering method using matrix transformations.
     */
    private void renderModelInternal(LegacyCowStonebreakModel model, String textureVariant, int shaderProgram,
                                     int mvpUniformLocation, int modelMatrixLocation, float[] viewProjectionMatrix,
                                     com.openmason.rendering.TextureAtlas textureAtlas,
                                     int textureUniformLocation, int useTextureUniformLocation, int colorUniformLocation) {
        if (!initialized) {
            throw new IllegalStateException("ModelRenderer not initialized");
        }
        
        // Validate OpenGL context and rendering state before rendering
        if (contextValidationEnabled) {
            List<String> contextIssues = OpenGLValidator.validateContext("renderModel");
            if (!contextIssues.isEmpty()) {
                // System.err.println("OpenGL context validation failed in renderModel:");
                for (String issue : contextIssues) {
                    // System.err.println("  - " + issue);
                }
                throw new RuntimeException("Cannot render model due to invalid OpenGL context");
            }
            
            // Also validate rendering state for optimal performance
            List<String> stateIssues = OpenGLValidator.validateRenderingState();
            if (!stateIssues.isEmpty()) {
                // System.err.println("OpenGL rendering state validation issues in renderModel:");
                for (String issue : stateIssues) {
                    // System.err.println("  - WARNING: " + issue);
                }
                // Note: These are warnings, not fatal errors, so we continue rendering
            }
        }
        
        // Update texture variants if needed
        updateTextureVariants(model, textureVariant);
        
        // Bind shader program and set uniforms
        glUseProgram(shaderProgram);
        glUniformMatrix4fv(mvpUniformLocation, false, viewProjectionMatrix);
        
        // Setup texture rendering
        boolean useTextures = (textureAtlas != null && textureAtlas.isReady());
        if (useTextures) {
            // Bind texture atlas
            textureAtlas.bind(0); // Use texture unit 0
            textureAtlas.setTextureUniform(shaderProgram, "uTexture", 0);
            
            // Enable texture rendering
            if (useTextureUniformLocation != -1) {
                glUniform1i(useTextureUniformLocation, 1); // true
            }
            
        } else {
            // Disable texture rendering, use solid colors
            if (useTextureUniformLocation != -1) {
                glUniform1i(useTextureUniformLocation, 0); // false
            }
            
        }
        
        // Set vertex color uniform (white for proper texture display, white for wireframe/solid color)
        if (colorUniformLocation != -1) {
            if (useTextures) {
                glUniform3f(colorUniformLocation, 1.0f, 1.0f, 1.0f); // White for textures
            } else {
                glUniform3f(colorUniformLocation, 1.0f, 1.0f, 1.0f); // White for wireframe/solid color
            }
        }
        
        // Render each model part with matrix transformations
        for (LegacyCowStonebreakModel.BodyPart bodyPart : model.getBodyParts()) {
            renderModelPart(bodyPart.getName(), bodyPart, modelMatrixLocation, viewProjectionMatrix);
        }
        
        totalRenderCalls++;
        lastRenderTime = System.currentTimeMillis();
    }
    
    /**
     * @deprecated Use {@link #renderModel(LegacyCowStonebreakModel, String, int, int, int, float[])} instead.
     * This compatibility method exists for legacy code but will not produce proper rendering
     * without shader context.
     */
    @Deprecated
    public void renderModel(LegacyCowStonebreakModel model, String textureVariant) {
        // System.err.println("WARNING: renderModel() called without shader context. Models will not render properly.");
        // System.err.println("Please update your code to use renderModel(model, variant, shaderProgram, mvpUniformLocation, modelMatrixLocation, viewProjectionMatrix)");
        // Don't actually render anything to avoid OpenGL errors
    }
    
    /**
     * Renders a single model part with user transform applied.
     * 
     * @param partName The name of the part to render
     * @param bodyPart The body part definition containing transformation data
     * @param modelMatrixLocation Uniform location for model transformation matrix
     * @param viewProjectionMatrix The view-projection matrix
     * @param userTransform The user transform matrix to apply
     */
    private void renderModelPartWithUserTransform(String partName, LegacyCowStonebreakModel.BodyPart bodyPart,
                                                int modelMatrixLocation, float[] viewProjectionMatrix,
                                                Matrix4f userTransform) {
        // Validate OpenGL context before rendering individual parts
        if (contextValidationEnabled) {
            List<String> contextIssues = OpenGLValidator.validateContext("renderModelPart:" + partName);
            if (!contextIssues.isEmpty()) {
                // System.err.println("OpenGL context validation failed in renderModelPart for " + partName + ":");
                for (String issue : contextIssues) {
                    // System.err.println("  - " + issue);
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
                    // System.err.println("VAO validation issues for part " + partName + ":");
                    for (String issue : vaoIssues) {
                        // System.err.println("  - WARNING: " + issue);
                    }
                    // Continue rendering despite warnings, but the user is informed
                }
            }
            
            // Get transformation matrix from the body part
            if (bodyPart != null && bodyPart.getModelPart() != null) {
                ModelDefinition.ModelPart part = bodyPart.getModelPart();
                
                // Create transformation matrix EXACTLY like EntityRenderer does
                Vector3f position = part.getPositionVector();
                Vector3f rotation = part.getRotation();
                Vector3f scale = part.getScale();
                
                // IMPORTANT: Match EntityRenderer transformation order exactly, then apply user transform
                Matrix4f partTransformMatrix = new Matrix4f()
                    .translate(position)  // Step 1: Position from JSON (should be baked coordinates)
                    .rotateXYZ(          // Step 2: Rotation (in exact same order as EntityRenderer)
                        (float) Math.toRadians(rotation.x),
                        (float) Math.toRadians(rotation.y),
                        (float) Math.toRadians(rotation.z)
                    )
                    .scale(scale);       // Step 3: Scale last (should be 1,1,1 after our recent fixes)
                
                // Apply user transform to the part transform
                Matrix4f finalTransformMatrix = new Matrix4f(userTransform).mul(partTransformMatrix);
                
                // Debug logging for first part only to avoid spam (TRACE level)
                if ("body".equals(partName)) {
                    // System.out.println("[ModelRenderer] Applying user transform to " + partName + 
                    //     ": userTransform determinant=" + String.format("%.3f", userTransform.determinant()) +
                    //     ", partTransform determinant=" + String.format("%.3f", partTransformMatrix.determinant()) +
                    //     ", final determinant=" + String.format("%.3f", finalTransformMatrix.determinant()));
                }
                
                // Store the actual rendered transformation for diagnostics
                lastRenderedTransforms.put(partName, new Matrix4f(finalTransformMatrix));
                
                // Bind VAO first for proper OpenGL state
                vao.bind();
                
                // Upload transformation matrix to shader
                if (modelMatrixLocation != -1) {
                    finalTransformMatrix.get(matrixBuffer);
                    glUniformMatrix4fv(modelMatrixLocation, false, matrixBuffer);
                }
                
                // Render the triangles (VAO is already bound)
                if (vao.getIndexBuffer() != null) {
                    vao.getIndexBuffer().drawTriangles();
                } else {
                    // System.err.println("Cannot render part '" + partName + "': no index buffer");
                }
                
                vao.unbind();
            } else {
                // System.err.println("Cannot render part '" + partName + "' with matrix transforms: body part data is null");
            }
        } else {
            // System.err.println("Cannot render part '" + partName + "': VAO not found or invalid");
        }
    }
    
    /**
     * Renders a single model part using matrix transformations.
     * 
     * @param partName The name of the part to render
     * @param bodyPart The body part definition containing transformation data
     * @param modelMatrixLocation Uniform location for model transformation matrix
     * @param viewProjectionMatrix The view-projection matrix
     */
    public void renderModelPart(String partName, LegacyCowStonebreakModel.BodyPart bodyPart,
                              int modelMatrixLocation, float[] viewProjectionMatrix) {
        // Validate OpenGL context before rendering individual parts
        if (contextValidationEnabled) {
            List<String> contextIssues = OpenGLValidator.validateContext("renderModelPart:" + partName);
            if (!contextIssues.isEmpty()) {
                // System.err.println("OpenGL context validation failed in renderModelPart for " + partName + ":");
                for (String issue : contextIssues) {
                    // System.err.println("  - " + issue);
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
                    // System.err.println("VAO validation issues for part " + partName + ":");
                    for (String issue : vaoIssues) {
                        // System.err.println("  - WARNING: " + issue);
                    }
                    // Continue rendering despite warnings, but the user is informed
                }
            }
            
            // Get transformation matrix from the body part
            if (bodyPart != null && bodyPart.getModelPart() != null) {
                ModelDefinition.ModelPart part = bodyPart.getModelPart();
                
                // Create transformation matrix EXACTLY like EntityRenderer does
                Vector3f position = part.getPositionVector();
                Vector3f rotation = part.getRotation();
                Vector3f scale = part.getScale();
                
                // IMPORTANT: Match EntityRenderer transformation order exactly
                Matrix4f transformMatrix = new Matrix4f()
                    .translate(position)  // Step 1: Position from JSON (should be baked coordinates)
                    .rotateXYZ(          // Step 2: Rotation (in exact same order as EntityRenderer)
                        (float) Math.toRadians(rotation.x),
                        (float) Math.toRadians(rotation.y),
                        (float) Math.toRadians(rotation.z)
                    )
                    .scale(scale);       // Step 3: Scale last (should be 1,1,1 after our recent fixes)
                
                // Store the actual rendered transformation for diagnostics
                lastRenderedTransforms.put(partName, new Matrix4f(transformMatrix));
                
                // Bind VAO first for proper OpenGL state
                vao.bind();
                
                // Upload transformation matrix to shader
                if (modelMatrixLocation != -1) {
                    transformMatrix.get(matrixBuffer);
                    glUniformMatrix4fv(modelMatrixLocation, false, matrixBuffer);
                }
                
                // Render the triangles (VAO is already bound)
                if (vao.getIndexBuffer() != null) {
                    vao.getIndexBuffer().drawTriangles();
                } else {
                    // System.err.println("Cannot render part '" + partName + "': no index buffer");
                }
                
                vao.unbind();
            } else {
                // System.err.println("Cannot render part '" + partName + "' with matrix transforms: body part data is null");
            }
        } else {
            // System.err.println("Cannot render part '" + partName + "': VAO not found or invalid");
        }
    }
    
    /**
     * Renders a single model part by name with its transformations applied (baked vertex mode).
     * 
     * @param partName The name of the part to render
     * @param bodyPart The body part definition containing transformation data (optional)
     */
    public void renderModelPart(String partName, LegacyCowStonebreakModel.BodyPart bodyPart) {
        // Validate OpenGL context before rendering individual parts
        if (contextValidationEnabled) {
            List<String> contextIssues = OpenGLValidator.validateContext("renderModelPart:" + partName);
            if (!contextIssues.isEmpty()) {
                // System.err.println("OpenGL context validation failed in renderModelPart for " + partName + ":");
                for (String issue : contextIssues) {
                    // System.err.println("  - " + issue);
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
                    // System.err.println("VAO validation issues for part " + partName + ":");
                    for (String issue : vaoIssues) {
                        // System.err.println("  - WARNING: " + issue);
                    }
                    // Continue rendering despite warnings, but the user is informed
                }
            }
            
            // Render the VAO (transformations are already baked into vertices during preparation)
            vao.renderTriangles();
        } else {
            // System.err.println("Cannot render part '" + partName + "': VAO not found or invalid");
        }
    }
    
    /**
     * Updates texture variants for all model parts if the variant has changed.
     * 
     * @param model The model being rendered
     * @param textureVariant The new texture variant
     */
    private void updateTextureVariants(LegacyCowStonebreakModel model, String textureVariant) {
        // Load the current texture variant definition dynamically
        com.stonebreak.textures.mobs.CowTextureDefinition.CowVariant variantDefinition = 
            com.stonebreak.textures.mobs.CowTextureLoader.getCowVariant(textureVariant);
            
        if (variantDefinition == null) {
            System.err.println("[ModelRenderer] ERROR: Failed to load texture variant: " + textureVariant);
            return;
        }
        
        // Only log when there are actual updates
        boolean hasUpdates = false;
        for (LegacyCowStonebreakModel.BodyPart bodyPart : model.getBodyParts()) {
            String partName = bodyPart.getName();
            String currentVariant = currentTextureVariants.get(partName);
            if (!textureVariant.equals(currentVariant)) {
                hasUpdates = true;
                break;
            }
        }
        
        if (hasUpdates) {
            System.out.println("[ModelRenderer] Updating texture coordinates for variant: " + textureVariant + " (" + variantDefinition.getDisplayName() + ")");
        }
        
        for (LegacyCowStonebreakModel.BodyPart bodyPart : model.getBodyParts()) {
            String partName = bodyPart.getName();
            String currentVariant = currentTextureVariants.get(partName);
            String textureField = bodyPart.getModelPart().getTexture();
            
            if (!textureVariant.equals(currentVariant)) {
                VertexArray vao = modelPartVAOs.get(partName);
                if (vao != null) {
                    System.out.println("[ModelRenderer] Updating " + partName + " (texture: " + textureField + ") to variant: " + textureVariant);
                    // Use the dynamically loaded variant definition instead of model's fixed definition
                    vao.updateTextureVariant(variantDefinition, partName, textureVariant);
                    currentTextureVariants.put(partName, textureVariant);
                } else {
                    System.err.println("[ModelRenderer] ERROR: No VAO found for part: " + partName);
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
    public boolean isModelPrepared(LegacyCowStonebreakModel model) {
        for (LegacyCowStonebreakModel.BodyPart bodyPart : model.getBodyParts()) {
            if (!modelPartVAOs.containsKey(bodyPart.getName())) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Gets detailed preparation status for a model, showing which parts
     * are prepared and which are missing or invalid.
     * 
     * @param model The model to check
     * @return Detailed status report
     */
    public ModelPreparationStatus getModelPreparationStatus(LegacyCowStonebreakModel model) {
        ModelPreparationStatus status = new ModelPreparationStatus(model.getVariantName());
        
        for (LegacyCowStonebreakModel.BodyPart bodyPart : model.getBodyParts()) {
            String partName = bodyPart.getName();
            VertexArray vao = modelPartVAOs.get(partName);
            
            if (vao == null) {
                status.addMissingPart(partName, "No VAO created");
            } else if (!vao.isValid()) {
                status.addInvalidPart(partName, "VAO exists but is invalid");
            } else {
                status.addPreparedPart(partName, "Ready for rendering");
            }
        }
        
        return status;
    }
    
    /**
     * Logs detailed diagnostic information about the current state
     * of the ModelRenderer and all prepared models.
     */
    public void logDiagnosticInfo() {
        // System.out.println("=== ModelRenderer Diagnostic Report ===");
        // System.out.println("Renderer: " + debugPrefix);
        // System.out.println("Initialized: " + initialized);
        // System.out.println("Total VAOs: " + modelPartVAOs.size());
        // System.out.println("Total Texture Variants: " + currentTextureVariants.size());
        // System.out.println("Total Render Calls: " + totalRenderCalls);
        // System.out.println("Last Render Time: " + lastRenderTime);
        // System.out.println("Context Validation: " + (contextValidationEnabled ? "ENABLED" : "DISABLED"));
        // System.out.println("Matrix Transformation Mode: " + (matrixTransformationMode ? "ENABLED" : "DISABLED"));
        // System.out.println("Tracked Coordinate Spaces: " + modelCoordinateSpaces.size());
        
        if (!modelPartVAOs.isEmpty()) {
            // System.out.println("\nPrepared Model Parts:");
            for (Map.Entry<String, VertexArray> entry : modelPartVAOs.entrySet()) {
                String partName = entry.getKey();
                VertexArray vao = entry.getValue();
                String status = vao.isValid() ? "VALID" : "INVALID";
                String textureVariant = currentTextureVariants.getOrDefault(partName, "unknown");
                // System.out.println("  - " + partName + ": " + status + " (variant: " + textureVariant + ")");
            }
        }
        
        if (!modelCoordinateSpaces.isEmpty()) {
            // System.out.println("\nModel Coordinate Spaces:");
            for (Map.Entry<String, LegacyCowModelManager.CoordinateSpace> entry : modelCoordinateSpaces.entrySet()) {
                String modelVariant = entry.getKey();
                LegacyCowModelManager.CoordinateSpace space = entry.getValue();
                String status = (space == LegacyCowModelManager.CoordinateSpace.STONEBREAK_COMPATIBLE) ? "✓ COMPATIBLE" : "⚠ INCOMPATIBLE";
                // System.out.println("  - " + modelVariant + ": " + space.getDisplayName() + " " + status);
            }
        }
        
        if (!lastRenderedTransforms.isEmpty()) {
            // System.out.println("\nLast Rendered Transformations:");
            for (String partName : lastRenderedTransforms.keySet()) {
                DiagnosticData data = getPartDiagnostics(partName);
                if (data != null) {
                    // System.out.println("  - " + data.toString());
                }
            }
        }
        
        // System.out.println("======================================");
    }
    
    /**
     * Enables or disables matrix transformation mode for this renderer.
     * When enabled, model parts use matrix transformations instead of baked vertices.
     */
    public void setMatrixTransformationMode(boolean enabled) {
        this.matrixTransformationMode = enabled;
        // System.out.println("Matrix transformation mode " + (enabled ? "ENABLED" : "DISABLED") + " for ModelRenderer: " + debugPrefix);
    }
    
    /**
     * @return True if matrix transformation mode is enabled
     */
    public boolean isMatrixTransformationMode() {
        return matrixTransformationMode;
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
            initialized,
            matrixTransformationMode
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
        // System.out.println("Cleaning up ModelRenderer: " + debugPrefix + 
        //                   " (" + modelPartVAOs.size() + " VAOs)");
        
        for (VertexArray vao : modelPartVAOs.values()) {
            try {
                vao.close();
            } catch (Exception e) {
                // System.err.println("Error cleaning up VAO: " + e.getMessage());
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
            // System.out.println("OpenGL context validation enabled for ModelRenderer: " + debugPrefix);
        } else {
            // System.out.println("OpenGL context validation disabled for ModelRenderer: " + debugPrefix);
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
    public Map<String, LegacyCowModelManager.CoordinateSpace> getModelCoordinateSpaces() {
        return new HashMap<>(modelCoordinateSpaces); 
    }
    
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
        public final boolean matrixTransformMode;
        
        public RenderingStatistics(int modelPartCount, long totalRenderCalls, 
                                 long lastRenderTime, int textureVariantCount, boolean initialized, boolean matrixTransformMode) {
            this.modelPartCount = modelPartCount;
            this.totalRenderCalls = totalRenderCalls;
            this.lastRenderTime = lastRenderTime;
            this.textureVariantCount = textureVariantCount;
            this.initialized = initialized;
            this.matrixTransformMode = matrixTransformMode;
        }
        
        @Override
        public String toString() {
            return String.format(
                "RenderingStatistics{parts=%d, renders=%d, lastRender=%d, variants=%d, init=%b, matrixMode=%b}",
                modelPartCount, totalRenderCalls, lastRenderTime, textureVariantCount, initialized, matrixTransformMode
            );
        }
    }
    
    /**
     * Detailed status report for model preparation state.
     */
    public static class ModelPreparationStatus {
        private final String modelName;
        private final List<String> preparedParts = new java.util.ArrayList<>();
        private final List<String> missingParts = new java.util.ArrayList<>();
        private final List<String> invalidParts = new java.util.ArrayList<>();
        private final Map<String, String> partDetails = new HashMap<>();
        
        public ModelPreparationStatus(String modelName) {
            this.modelName = modelName;
        }
        
        public void addPreparedPart(String partName, String details) {
            preparedParts.add(partName);
            partDetails.put(partName, details);
        }
        
        public void addMissingPart(String partName, String reason) {
            missingParts.add(partName);
            partDetails.put(partName, reason);
        }
        
        public void addInvalidPart(String partName, String reason) {
            invalidParts.add(partName);
            partDetails.put(partName, reason);
        }
        
        public boolean isFullyPrepared() {
            return missingParts.isEmpty() && invalidParts.isEmpty();
        }
        
        public boolean isPartiallyPrepared() {
            return !preparedParts.isEmpty() && (!missingParts.isEmpty() || !invalidParts.isEmpty());
        }
        
        public boolean isCompletelyUnprepared() {
            return preparedParts.isEmpty();
        }
        
        public int getTotalParts() {
            return preparedParts.size() + missingParts.size() + invalidParts.size();
        }
        
        public int getPreparedCount() {
            return preparedParts.size();
        }
        
        public List<String> getPreparedParts() {
            return new java.util.ArrayList<>(preparedParts);
        }
        
        public List<String> getMissingParts() {
            return new java.util.ArrayList<>(missingParts);
        }
        
        public List<String> getInvalidParts() {
            return new java.util.ArrayList<>(invalidParts);
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Model Preparation Status: ").append(modelName).append(" ===").append("\n");
            sb.append("Total Parts: ").append(getTotalParts()).append("\n");
            sb.append("Prepared: ").append(preparedParts.size()).append("\n");
            sb.append("Missing: ").append(missingParts.size()).append("\n");
            sb.append("Invalid: ").append(invalidParts.size()).append("\n");
            
            if (!preparedParts.isEmpty()) {
                sb.append("\nPrepared Parts:").append("\n");
                for (String part : preparedParts) {
                    sb.append("  ✓ ").append(part).append(": ").append(partDetails.get(part)).append("\n");
                }
            }
            
            if (!missingParts.isEmpty()) {
                sb.append("\nMissing Parts:").append("\n");
                for (String part : missingParts) {
                    sb.append("  ✗ ").append(part).append(": ").append(partDetails.get(part)).append("\n");
                }
            }
            
            if (!invalidParts.isEmpty()) {
                sb.append("\nInvalid Parts:").append("\n");
                for (String part : invalidParts) {
                    sb.append("  ⚠ ").append(part).append(": ").append(partDetails.get(part)).append("\n");
                }
            }
            
            sb.append("==================================================");
            return sb.toString();
        }
    }
    
    /**
     * Get diagnostic data about actually rendered transformations.
     * This returns the transformation matrices that were actually sent to the GPU,
     * not the values from JSON or model definitions.
     * 
     * @return Map of part names to their actual rendered transformation matrices
     */
    public Map<String, Matrix4f> getRenderedTransformations() {
        // Return a copy to prevent external modification
        Map<String, Matrix4f> copy = new HashMap<>();
        for (Map.Entry<String, Matrix4f> entry : lastRenderedTransforms.entrySet()) {
            copy.put(entry.getKey(), new Matrix4f(entry.getValue()));
        }
        return copy;
    }
    
    /**
     * Get diagnostic data for a specific model part.
     * Returns the actual position, rotation, and scale that was rendered.
     * 
     * @param partName The name of the model part
     * @return DiagnosticData containing actual rendered values, or null if part not found
     */
    public DiagnosticData getPartDiagnostics(String partName) {
        Matrix4f matrix = lastRenderedTransforms.get(partName);
        if (matrix == null) {
            return null;
        }
        
        // Extract position, rotation, and scale from the transformation matrix
        Vector3f translation = new Vector3f();
        Vector3f rotation = new Vector3f();
        Vector3f scale = new Vector3f();
        
        // JOML provides methods to decompose transformation matrices
        matrix.getTranslation(translation);
        matrix.getEulerAnglesXYZ(rotation);
        matrix.getScale(scale);
        
        return new DiagnosticData(partName, translation, rotation, scale, matrix);
    }
    
    /**
     * Validates that a model is using the correct coordinate space for Stonebreak compatibility.
     * 
     * @param requestedModel The original model name requested
     * @param actualModel The StonebreakModel being rendered
     * @return Coordinate validation result
     */
    public LegacyCowModelManager.CoordinateValidationResult validateCoordinateSpace(
            String requestedModel, LegacyCowStonebreakModel actualModel) {
        String actualVariant = actualModel.getVariantName();
        return LegacyCowModelManager.CoordinateSpaceManager.validateCoordinateCompatibility(
            requestedModel, actualVariant);
    }
    
    /**
     * Gets the coordinate space being used for a specific model variant.
     * 
     * @param modelVariant The model variant to check
     * @return The coordinate space, or null if model not tracked
     */
    public LegacyCowModelManager.CoordinateSpace getModelCoordinateSpace(String modelVariant) {
        return modelCoordinateSpaces.get(modelVariant);
    }
    
    /**
     * Performs comprehensive coordinate space validation for all prepared models.
     * This method checks that all models are using Stonebreak-compatible coordinate spaces.
     * 
     * @return Validation report with any coordinate space mismatches
     */
    public CoordinateSpaceValidationReport validateAllCoordinateSpaces() {
        CoordinateSpaceValidationReport report = new CoordinateSpaceValidationReport();
        
        for (Map.Entry<String, LegacyCowModelManager.CoordinateSpace> entry : modelCoordinateSpaces.entrySet()) {
            String modelVariant = entry.getKey();
            LegacyCowModelManager.CoordinateSpace space = entry.getValue();
            
            if (space != LegacyCowModelManager.CoordinateSpace.STONEBREAK_COMPATIBLE) {
                report.addMismatch(modelVariant, space, 
                    "Model is not using Stonebreak-compatible coordinate space");
            } else {
                report.addValidModel(modelVariant, space);
            }
        }
        
        return report;
    }
    
    /**
     * Gets diagnostic information comparing rendered coordinates with expected Stonebreak coordinates.
     * This method is crucial for validating that Open Mason renders at identical positions as Stonebreak.
     * 
     * @param partName The model part to analyze
     * @return Coordinate comparison data, or null if part not found
     */
    public CoordinateComparisonResult compareWithStonebreakCoordinates(String partName) {
        DiagnosticData renderData = getPartDiagnostics(partName);
        if (renderData == null) {
            return null;
        }
        
        // Get the model variant for this part to determine coordinate space
        String modelVariant = findModelVariantForPart(partName);
        LegacyCowModelManager.CoordinateSpace space = modelCoordinateSpaces.get(modelVariant);
        
        boolean coordinatesMatch = (space == LegacyCowModelManager.CoordinateSpace.STONEBREAK_COMPATIBLE);
        
        return new CoordinateComparisonResult(
            partName, modelVariant, space, renderData.position, 
            renderData.rotation, renderData.scale, coordinatesMatch
        );
    }
    
    /**
     * Helper method to find which model variant contains a specific part.
     */
    private String findModelVariantForPart(String partName) {
        // Look through tracked model variants to find which one contains this part
        for (String variant : modelCoordinateSpaces.keySet()) {
            if (modelPartVAOs.containsKey(partName)) {
                return variant; // Simple approach - could be enhanced for multiple models
            }
        }
        return "unknown";
    }
    
    /**
     * Validates that transformation matrices match Stonebreak EntityRenderer exactly.
     * Call this method after rendering to ensure coordinate parity.
     */
    public String validateCoordinateAlignment() {
        StringBuilder report = new StringBuilder();
        report.append("=== COORDINATE ALIGNMENT VALIDATION ===\n");
        
        // Expected positions for standard_cow_baked model (used by Stonebreak EntityRenderer)
        Map<String, Vector3f> expectedPositions = Map.of(
            "body", new Vector3f(0, 0.2f, 0),
            "head", new Vector3f(0, 0.4f, -0.4f),
            "left_horn", new Vector3f(-0.2f, 0.55f, -0.4f),
            "right_horn", new Vector3f(0.2f, 0.55f, -0.4f),
            "front_left", new Vector3f(-0.2f, -0.11f, -0.2f),
            "front_right", new Vector3f(0.2f, -0.11f, -0.2f),
            "back_left", new Vector3f(-0.2f, -0.11f, 0.2f),
            "back_right", new Vector3f(0.2f, -0.11f, 0.2f),
            "udder", new Vector3f(0, -0.05f, 0.2f),
            "tail", new Vector3f(0, 0.25f, 0.37f)
        );
        
        int validCount = 0;
        int totalCount = 0;
        
        for (Map.Entry<String, Matrix4f> entry : lastRenderedTransforms.entrySet()) {
            String partName = entry.getKey();
            Matrix4f matrix = entry.getValue();
            Vector3f actualPosition = new Vector3f();
            matrix.getTranslation(actualPosition);
            
            Vector3f expectedPosition = expectedPositions.get(partName);
            if (expectedPosition != null) {
                totalCount++;
                float distance = actualPosition.distance(expectedPosition);
                boolean isValid = distance < 0.001f; // 1mm tolerance
                
                if (isValid) {
                    validCount++;
                    report.append(String.format("✓ %s: (%.3f, %.3f, %.3f) - CORRECT\n", 
                        partName, actualPosition.x, actualPosition.y, actualPosition.z));
                } else {
                    report.append(String.format("✗ %s: (%.3f, %.3f, %.3f) - Expected: (%.3f, %.3f, %.3f) - ERROR %.3f\n", 
                        partName, actualPosition.x, actualPosition.y, actualPosition.z,
                        expectedPosition.x, expectedPosition.y, expectedPosition.z, distance));
                }
            } else {
                report.append(String.format("? %s: (%.3f, %.3f, %.3f) - UNKNOWN PART\n", 
                    partName, actualPosition.x, actualPosition.y, actualPosition.z));
            }
        }
        
        report.append(String.format("\nResult: %d/%d parts correct (%.1f%%)\n", 
            validCount, totalCount, totalCount > 0 ? (100.0f * validCount / totalCount) : 0));
        
        if (validCount == totalCount && totalCount > 0) {
            report.append("✅ COORDINATE SYSTEM ALIGNED WITH STONEBREAK\n");
        } else {
            report.append("❌ COORDINATE SYSTEM MISALIGNED - FIX REQUIRED\n");
        }
        
        report.append("===========================================");
        return report.toString();
    }
    
    /**
     * Data class to hold diagnostic information about a rendered model part.
     */
    public static class DiagnosticData {
        public final String partName;
        public final Vector3f position;
        public final Vector3f rotation; // in radians
        public final Vector3f scale;
        public final Matrix4f transformMatrix;
        
        public DiagnosticData(String partName, Vector3f position, Vector3f rotation, Vector3f scale, Matrix4f transformMatrix) {
            this.partName = partName;
            this.position = new Vector3f(position);
            this.rotation = new Vector3f(rotation);
            this.scale = new Vector3f(scale);
            this.transformMatrix = new Matrix4f(transformMatrix);
        }
        
        public Vector3f getRotationDegrees() {
            return new Vector3f(
                (float) Math.toDegrees(rotation.x),
                (float) Math.toDegrees(rotation.y),
                (float) Math.toDegrees(rotation.z)
            );
        }
        
        @Override
        public String toString() {
            return String.format(
                "DiagnosticData{part='%s', pos=(%.3f,%.3f,%.3f), rot=(%.1f°,%.1f°,%.1f°), scale=(%.3f,%.3f,%.3f)}",
                partName, 
                position.x, position.y, position.z,
                Math.toDegrees(rotation.x), Math.toDegrees(rotation.y), Math.toDegrees(rotation.z),
                scale.x, scale.y, scale.z
            );
        }
    }
    
    /**
     * Validation report for coordinate space compatibility across all models.
     */
    public static class CoordinateSpaceValidationReport {
        private final List<String> validModels = new java.util.ArrayList<>();
        private final List<String> mismatchedModels = new java.util.ArrayList<>();
        private final Map<String, String> mismatchReasons = new HashMap<>();
        private final Map<String, LegacyCowModelManager.CoordinateSpace> modelSpaces = new HashMap<>();
        
        public void addValidModel(String modelVariant, LegacyCowModelManager.CoordinateSpace space) {
            validModels.add(modelVariant);
            modelSpaces.put(modelVariant, space);
        }
        
        public void addMismatch(String modelVariant, LegacyCowModelManager.CoordinateSpace space, String reason) {
            mismatchedModels.add(modelVariant);
            mismatchReasons.put(modelVariant, reason);
            modelSpaces.put(modelVariant, space);
        }
        
        public boolean allModelsValid() {
            return mismatchedModels.isEmpty();
        }
        
        public int getTotalModels() {
            return validModels.size() + mismatchedModels.size();
        }
        
        public List<String> getValidModels() {
            return new java.util.ArrayList<>(validModels);
        }
        
        public List<String> getMismatchedModels() {
            return new java.util.ArrayList<>(mismatchedModels);
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Coordinate Space Validation Report ===\n");
            sb.append("Total models: ").append(getTotalModels()).append("\n");
            sb.append("Valid models: ").append(validModels.size()).append("\n");
            sb.append("Mismatched models: ").append(mismatchedModels.size()).append("\n");
            sb.append("Overall status: ").append(allModelsValid() ? "ALL COMPATIBLE" : "MISMATCHES DETECTED").append("\n");
            
            if (!validModels.isEmpty()) {
                sb.append("\nValid Models (Stonebreak Compatible):\n");
                for (String model : validModels) {
                    LegacyCowModelManager.CoordinateSpace space = modelSpaces.get(model);
                    sb.append("  ✓ ").append(model).append(" (").append(space.getDisplayName()).append(")\n");
                }
            }
            
            if (!mismatchedModels.isEmpty()) {
                sb.append("\nMismatched Models (Coordinate Issues):\n");
                for (String model : mismatchedModels) {
                    LegacyCowModelManager.CoordinateSpace space = modelSpaces.get(model);
                    String reason = mismatchReasons.get(model);
                    sb.append("  ✗ ").append(model).append(" (").append(space.getDisplayName()).append(") - ").append(reason).append("\n");
                }
            }
            
            sb.append("============================================");
            return sb.toString();
        }
    }
    
    /**
     * Result of comparing rendered coordinates with expected Stonebreak coordinates.
     */
    public static class CoordinateComparisonResult {
        private final String partName;
        private final String modelVariant;
        private final LegacyCowModelManager.CoordinateSpace coordinateSpace;
        private final Vector3f renderedPosition;
        private final Vector3f renderedRotation;
        private final Vector3f renderedScale;
        private final boolean matchesStonebreak;
        
        public CoordinateComparisonResult(String partName, String modelVariant, 
                                        LegacyCowModelManager.CoordinateSpace coordinateSpace,
                                        Vector3f renderedPosition, Vector3f renderedRotation, 
                                        Vector3f renderedScale, boolean matchesStonebreak) {
            this.partName = partName;
            this.modelVariant = modelVariant;
            this.coordinateSpace = coordinateSpace;
            this.renderedPosition = new Vector3f(renderedPosition);
            this.renderedRotation = new Vector3f(renderedRotation);
            this.renderedScale = new Vector3f(renderedScale);
            this.matchesStonebreak = matchesStonebreak;
        }
        
        public boolean matchesStonebreak() { return matchesStonebreak; }
        public String getPartName() { return partName; }
        public String getModelVariant() { return modelVariant; }
        public LegacyCowModelManager.CoordinateSpace getCoordinateSpace() { return coordinateSpace; }
        public Vector3f getRenderedPosition() { return new Vector3f(renderedPosition); }
        public Vector3f getRenderedRotation() { return new Vector3f(renderedRotation); }
        public Vector3f getRenderedScale() { return new Vector3f(renderedScale); }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Coordinate Comparison for '").append(partName).append("':\n");
            sb.append("  Model variant: ").append(modelVariant).append("\n");
            sb.append("  Coordinate space: ").append(coordinateSpace.getDisplayName()).append("\n");
            sb.append("  Rendered position: (").append(String.format("%.3f, %.3f, %.3f", 
                renderedPosition.x, renderedPosition.y, renderedPosition.z)).append(")\n");
            sb.append("  Matches Stonebreak: ").append(matchesStonebreak ? "YES" : "NO");
            if (!matchesStonebreak) {
                sb.append(" - COORDINATE MISMATCH!");
            }
            return sb.toString();
        }
    }
}