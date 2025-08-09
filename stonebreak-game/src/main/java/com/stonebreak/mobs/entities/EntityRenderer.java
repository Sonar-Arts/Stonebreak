package com.stonebreak.mobs.entities;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import com.stonebreak.rendering.ShaderProgram;
import com.stonebreak.rendering.CowTextureAtlas;
import com.stonebreak.model.ModelLoader;
import com.stonebreak.model.ModelDefinition;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.system.MemoryUtil.*;

/**
 * Universal entity renderer that renders entities using their proper 3D models.
 * Supports multi-part textured models and can handle different entity types.
 */
public class EntityRenderer {
    private ShaderProgram shader;
    // Note: Using static CowTextureAtlas instead of instance variable
    private boolean initialized = false;
    
    // VAO and VBO storage for model parts - organized by entity type
    private final Map<EntityType, Map<String, Integer>> vaoMaps = new HashMap<>();
    private final Map<EntityType, Map<String, Integer>> vboMaps = new HashMap<>();
    private final Map<EntityType, Map<String, Integer>> eboMaps = new HashMap<>();
    private final Map<EntityType, Map<String, Integer>> texCoordVboMaps = new HashMap<>();
    private final Map<EntityType, Map<String, Integer>> vertexCountMaps = new HashMap<>();
    
    // Track current texture variant for each part to avoid unnecessary updates
    private final Map<EntityType, Map<String, String>> currentTextureVariants = new HashMap<>();
    
    // Simple cube model for fallback entities
    private int simpleCubeVAO;
    private int simpleCubeVBO;
    private int simpleCubeTexVBO;
    
    // Debug sphere model for pathfinding targets
    private int debugSphereVAO;
    private int debugSphereVBO;
    private int debugSphereTexVBO;
    
    public void initialize() {
        if (initialized) return;
        
        createShader();
        createTextureAtlas();
        createSimpleCubeModel();
        createDebugSphereModel();
        initializeEntityModels();
        initialized = true;
    }
    
    private void initializeEntityModels() {
        // Initialize models for each entity type
        for (EntityType entityType : EntityType.values()) {
            vaoMaps.put(entityType, new HashMap<>());
            vboMaps.put(entityType, new HashMap<>());
            eboMaps.put(entityType, new HashMap<>());
            texCoordVboMaps.put(entityType, new HashMap<>());
            vertexCountMaps.put(entityType, new HashMap<>());
            currentTextureVariants.put(entityType, new HashMap<>());
            
            switch (entityType) {
                case COW -> createCowModel();
                // Add other entity types here as they're implemented
                default -> {
                    // No special model needed - will use simple cube fallback
                }
            }
        }
    }
    
    private void createShader() {
        String vertexShader = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aTexCoord;
            
            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;
            
            out vec2 TexCoord;
            
            void main() {
                gl_Position = projection * view * model * vec4(aPos, 1.0);
                TexCoord = aTexCoord;
            }
            """;
        
        String fragmentShader = """
            #version 330 core
            out vec4 FragColor;
            
            in vec2 TexCoord;
            uniform sampler2D textureSampler;
            
            void main() {
                FragColor = texture(textureSampler, TexCoord);
            }
            """;
        
        try {
            shader = new ShaderProgram();
            shader.createVertexShader(vertexShader);
            shader.createFragmentShader(fragmentShader);
            shader.link();
            
            shader.createUniform("model");
            shader.createUniform("view");
            shader.createUniform("projection");
            shader.createUniform("textureSampler");
        } catch (Exception e) {
            System.err.println("Failed to create entity shader: " + e.getMessage());
        }
    }
    
    private void createTextureAtlas() {
        // Initialize the static cow texture atlas
        CowTextureAtlas.initialize();
    }
    
    private void createSimpleCubeModel() {
        // Simple cube for fallback entity rendering
        float[] vertices = {
            // Front face
            -0.5f, -0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
            // Back face  
            -0.5f, -0.5f, -0.5f,  0.5f, -0.5f, -0.5f,  0.5f,  0.5f, -0.5f,  -0.5f,  0.5f, -0.5f,
            // Left face
            -0.5f, -0.5f, -0.5f, -0.5f, -0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  -0.5f,  0.5f, -0.5f,
            // Right face
             0.5f, -0.5f, -0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  0.5f,   0.5f,  0.5f, -0.5f,
            // Top face
            -0.5f,  0.5f, -0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
            // Bottom face
            -0.5f, -0.5f, -0.5f,  0.5f, -0.5f, -0.5f,  0.5f, -0.5f,  0.5f,  -0.5f, -0.5f,  0.5f
        };
        
        float[] texCoords = {
            // Simple UV mapping for all faces
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Front
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Back
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Left
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Right
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Top
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f  // Bottom
        };
        
        simpleCubeVAO = GL30.glGenVertexArrays();
        simpleCubeVBO = GL15.glGenBuffers();
        simpleCubeTexVBO = GL15.glGenBuffers();
        
        GL30.glBindVertexArray(simpleCubeVAO);
        
        // Upload vertex data
        FloatBuffer vertexBuffer = memAllocFloat(vertices.length);
        vertexBuffer.put(vertices).flip();
        
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, simpleCubeVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        
        // Upload texture coordinate data
        FloatBuffer texCoordBuffer = memAllocFloat(texCoords.length);
        texCoordBuffer.put(texCoords).flip();
        
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, simpleCubeTexVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, texCoordBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 2 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(1);
        
        GL30.glBindVertexArray(0);
        
        memFree(vertexBuffer);
        memFree(texCoordBuffer);
    }
    
    private void createDebugSphereModel() {
        // Create a simple sphere using triangle approximation
        List<Float> vertices = new ArrayList<>();
        List<Float> texCoords = new ArrayList<>();
        
        int segments = 12; // Lower resolution for performance
        int rings = 8;
        float radius = 0.1f; // Small red sphere
        
        // Generate sphere vertices
        for (int ring = 0; ring <= rings; ring++) {
            float phi = (float) (Math.PI * ring / rings);
            for (int segment = 0; segment <= segments; segment++) {
                float theta = (float) (2.0 * Math.PI * segment / segments);
                
                float x = radius * (float) (Math.sin(phi) * Math.cos(theta));
                float y = radius * (float) (Math.cos(phi));
                float z = radius * (float) (Math.sin(phi) * Math.sin(theta));
                
                vertices.add(x);
                vertices.add(y);
                vertices.add(z);
                
                // Simple texture coordinates
                texCoords.add((float) segment / segments);
                texCoords.add((float) ring / rings);
            }
        }
        
        // Convert to arrays
        float[] vertexArray = new float[vertices.size()];
        float[] texCoordArray = new float[texCoords.size()];
        
        for (int i = 0; i < vertices.size(); i++) {
            vertexArray[i] = vertices.get(i);
        }
        for (int i = 0; i < texCoords.size(); i++) {
            texCoordArray[i] = texCoords.get(i);
        }
        
        debugSphereVAO = GL30.glGenVertexArrays();
        debugSphereVBO = GL15.glGenBuffers();
        debugSphereTexVBO = GL15.glGenBuffers();
        
        GL30.glBindVertexArray(debugSphereVAO);
        
        // Upload vertex data
        FloatBuffer vertexBuffer = memAllocFloat(vertexArray.length);
        vertexBuffer.put(vertexArray).flip();
        
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, debugSphereVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        
        // Upload texture coordinate data
        FloatBuffer texCoordBuffer = memAllocFloat(texCoordArray.length);
        texCoordBuffer.put(texCoordArray).flip();
        
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, debugSphereTexVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, texCoordBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 2 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(1);
        
        GL30.glBindVertexArray(0);
        
        memFree(vertexBuffer);
        memFree(texCoordBuffer);
    }
    
    private void createCowModel() {
        ModelDefinition.ModelPart[] parts = ModelLoader.getAllPartsStrict(ModelLoader.getCowModel("standard_cow"));
        
        String[] partNames = {"body", "head", "leg1", "leg2", "leg3", "leg4", "horn1", "horn2", "udder", "tail"};
        
        for (int i = 0; i < parts.length; i++) {
            ModelDefinition.ModelPart part = parts[i];
            String partName = partNames[i];
            
            createModelPart(EntityType.COW, partName, part);
        }
    }
    
    private void createModelPart(EntityType entityType, String partName, ModelDefinition.ModelPart part) {
        float[] vertices = part.getVertices();
        int[] indices = part.getIndices();
        // Use default texture coordinates for initial VBO creation
        float[] texCoords = part.getTextureCoords();
        
        // Generate VAO, VBO, EBO, and texture coordinate VBO
        int vao = GL30.glGenVertexArrays();
        int vbo = GL15.glGenBuffers();
        int ebo = GL15.glGenBuffers();
        int texVbo = GL15.glGenBuffers();
        
        GL30.glBindVertexArray(vao);
        
        // Upload vertex data
        FloatBuffer vertexBuffer = memAllocFloat(vertices.length);
        vertexBuffer.put(vertices).flip();
        
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);
        
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        
        // Upload texture coordinate data
        FloatBuffer texCoordBuffer = memAllocFloat(texCoords.length);
        texCoordBuffer.put(texCoords).flip();
        
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, texVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, texCoordBuffer, GL15.GL_STATIC_DRAW);
        
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 2 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(1);
        
        // Upload index data
        IntBuffer indexBuffer = memAllocInt(indices.length);
        indexBuffer.put(indices).flip();
        
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL15.GL_STATIC_DRAW);
        
        GL30.glBindVertexArray(0);
        
        // Store references in entity-specific maps
        vaoMaps.get(entityType).put(partName, vao);
        vboMaps.get(entityType).put(partName, vbo);
        eboMaps.get(entityType).put(partName, ebo);
        texCoordVboMaps.get(entityType).put(partName, texVbo);
        vertexCountMaps.get(entityType).put(partName, indices.length);
        
        // Cleanup
        memFree(vertexBuffer);
        memFree(texCoordBuffer);
        memFree(indexBuffer);
        
        // Initialize texture variant tracking
        currentTextureVariants.get(entityType).put(partName, "default");
    }
    
    /**
     * Updates texture coordinates for a model part if the variant has changed.
     */
    private void updateTextureCoordinatesForVariant(EntityType entityType, String partName, 
                                                   ModelDefinition.ModelPart part, String textureVariant) {
        // Check if variant has changed
        String currentVariant = currentTextureVariants.get(entityType).get(partName);
        if (textureVariant.equals(currentVariant)) {
            return; // No update needed
        }
        
        // Get new texture coordinates for this variant
        float[] newTexCoords = part.getTextureCoords(textureVariant);
        
        // Update the texture coordinate VBO
        int texVbo = texCoordVboMaps.get(entityType).get(partName);
        
        FloatBuffer texCoordBuffer = memAllocFloat(newTexCoords.length);
        texCoordBuffer.put(newTexCoords).flip();
        
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, texVbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, texCoordBuffer);
        
        memFree(texCoordBuffer);
        
        // Update tracking
        currentTextureVariants.get(entityType).put(partName, textureVariant);
    }
    
    public void renderEntity(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        if (!initialized || !entity.isAlive()) return;
        
        EntityType entityType = entity.getType();
        
        // Check if we have a complex model for this entity type
        if (vaoMaps.containsKey(entityType) && !vaoMaps.get(entityType).isEmpty()) {
            renderComplexEntity(entity, viewMatrix, projectionMatrix);
        } else {
            // Fallback to simple cube rendering
            renderSimpleEntity(entity, viewMatrix, projectionMatrix);
        }
    }
    
    private void renderComplexEntity(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        EntityType entityType = entity.getType();
        
        // Save current OpenGL state
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int previousElementArrayBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean wasCullFaceEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        
        // Ensure clean state for entity rendering
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LESS);
        // Disable face culling to make textures visible from both sides
        GL11.glDisable(GL11.GL_CULL_FACE);
        
        shader.bind();
        
        // Bind texture
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, CowTextureAtlas.getTextureId());
        shader.setUniform("textureSampler", 0);
        
        // Set common uniforms
        shader.setUniform("view", viewMatrix);
        shader.setUniform("projection", projectionMatrix);
        
        // Entity position represents feet level, so offset upward by half height for rendering
        Vector3f renderPosition = new Vector3f(entity.getPosition());
        renderPosition.y += entity.getHeight() / 2.0f;
        
        // Base transformation matrix for the entire entity
        Matrix4f baseMatrix = new Matrix4f()
            .translate(renderPosition)
            .rotateY((float) Math.toRadians(entity.getRotation().y))
            .scale(entity.getScale());
        
        // Render based on entity type
        switch (entityType) {
            case COW -> {
                renderCowParts(baseMatrix, entity);
                // Render pathfinding target if it exists
                if (entity instanceof com.stonebreak.mobs.cow.Cow cow) {
                    renderCowPathfindingTarget(cow, viewMatrix, projectionMatrix);
                }
            }
            // Add other complex entity types here
            default -> {
                // Shouldn't reach here, but fallback to simple rendering
            }
        }
        
        // Clean up and restore previous OpenGL state
        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        shader.unbind();
        
        // Restore previous OpenGL state
        if (wasCullFaceEnabled) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
        GL20.glUseProgram(previousProgram);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, previousElementArrayBuffer);
        GL30.glBindVertexArray(previousVertexArray);
    }
    
    private void renderCowParts(Matrix4f baseMatrix, Entity entity) {
        // Get the current animation and time from the cow entity
        String currentAnimation = "IDLE";
        float animationTime = System.currentTimeMillis() / 1000.0f; // Default fallback
        String textureVariant = "default";
        
        if (entity instanceof com.stonebreak.mobs.cow.Cow cow) {
            currentAnimation = cow.getCurrentAnimation();
            // Use the cow's animation controller time for consistent timing
            animationTime = cow.getAnimationController().getTotalAnimationTime();
            textureVariant = cow.getTextureVariant();
            // Debug: Log texture variant being rendered (only occasionally to avoid spam)
            if (System.currentTimeMillis() % 5000 < 100) { // Log every ~5 seconds
                System.out.println("DEBUG: Rendering cow with variant: " + textureVariant + " at position " + cow.getPosition());
                System.out.println("DEBUG: Cow entity class: " + cow.getClass().getSimpleName());
            }
        }
        
        // Get animated cow model parts from JSON model system
        ModelDefinition.ModelPart[] animatedParts = ModelLoader.getAnimatedParts("standard_cow", currentAnimation, animationTime);
        
        String[] partNames = {"body", "head", "leg1", "leg2", "leg3", "leg4", "horn1", "horn2", "udder", "tail"};
        Map<String, Integer> cowVaoMap = vaoMaps.get(EntityType.COW);
        Map<String, Integer> cowVertexCountMap = vertexCountMaps.get(EntityType.COW);
        
        // Render each part of the cow
        for (int i = 0; i < animatedParts.length && i < partNames.length; i++) {
            ModelDefinition.ModelPart part = animatedParts[i];
            String partName = partNames[i];
            
            if (cowVaoMap.containsKey(partName)) {
                // Update texture coordinates for this variant if needed
                updateTextureCoordinatesForVariant(EntityType.COW, partName, part, textureVariant);
                
                // Create model matrix for this part
                Matrix4f partMatrix = new Matrix4f(baseMatrix)
                    .translate(part.getPositionVector())
                    .rotateXYZ(
                        (float) Math.toRadians(part.getRotation().x),
                        (float) Math.toRadians(part.getRotation().y),
                        (float) Math.toRadians(part.getRotation().z)
                    )
                    .scale(part.getScale());
                
                shader.setUniform("model", partMatrix);
                
                // Render this part
                GL30.glBindVertexArray(cowVaoMap.get(partName));
                GL11.glDrawElements(GL11.GL_TRIANGLES, cowVertexCountMap.get(partName), GL11.GL_UNSIGNED_INT, 0);
            }
        }
    }
    
    private void renderCowPathfindingTarget(com.stonebreak.mobs.cow.Cow cow, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        // Only render debug spheres when debug overlay is visible
        if (com.stonebreak.core.Game.getDebugOverlay() == null || 
            !com.stonebreak.core.Game.getDebugOverlay().isVisible()) {
            return;
        }
        
        // Get the pathfinding target from the cow's AI
        Vector3f wanderTarget = cow.getAI().getWanderTarget();
        
        // Only render if the cow has a wander target
        if (wanderTarget != null && cow.getAI().hasWanderTarget()) {
            // Save current OpenGL state
            int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            
            // Use a simple approach - render a larger red sphere floating above the target position
            // Create model matrix for the target position, floating 1 block above
            Vector3f floatingTarget = new Vector3f(wanderTarget.x, wanderTarget.y + 1.0f, wanderTarget.z);
            Matrix4f targetMatrix = new Matrix4f()
                .translate(floatingTarget)
                .scale(0.5f); // Larger red indicator
            
            // Temporarily disable depth testing to make it always visible
            boolean wasDepthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            
            // Use the existing shader
            shader.setUniform("view", viewMatrix);
            shader.setUniform("projection", projectionMatrix);
            shader.setUniform("model", targetMatrix);
            
            // Bind a solid red texture (use a single red pixel)
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            
            // Create a simple red texture on the fly
            int redTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, redTexture);
            
            // Single red pixel texture
            ByteBuffer redPixel = ByteBuffer.allocateDirect(4);
            redPixel.put((byte) 255); // Red
            redPixel.put((byte) 0);   // Green
            redPixel.put((byte) 0);   // Blue
            redPixel.put((byte) 255); // Alpha
            redPixel.flip();
            
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, redPixel);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            
            shader.setUniform("textureSampler", 0);
            
            // Render the debug sphere
            GL30.glBindVertexArray(debugSphereVAO);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, (8 + 1) * (12 + 1)); // rings * segments
            GL30.glBindVertexArray(0);
            
            // Cleanup temporary texture
            GL11.glDeleteTextures(redTexture);
            
            // Restore depth testing
            if (wasDepthTestEnabled) {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
            }
        }
    }
    
    private void renderSimpleEntity(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        shader.bind();
        
        // Bind texture
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, CowTextureAtlas.getTextureId());
        shader.setUniform("textureSampler", 0);
        
        // Set uniforms
        shader.setUniform("view", viewMatrix);
        shader.setUniform("projection", projectionMatrix);
        
        // Entity position represents feet level, so offset upward by half height for rendering
        Vector3f renderPosition = new Vector3f(entity.getPosition());
        renderPosition.y += entity.getHeight() / 2.0f;
        
        Matrix4f modelMatrix = new Matrix4f()
            .translate(renderPosition)
            .rotateY((float) Math.toRadians(entity.getRotation().y))
            .scale(entity.getScale());
        
        shader.setUniform("model", modelMatrix);
        
        // Render simple cube
        GL30.glBindVertexArray(simpleCubeVAO);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, 24); // 6 faces × 4 vertices
        GL30.glBindVertexArray(0);
        
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        shader.unbind();
    }
    
    public void cleanup() {
        if (initialized) {
            if (shader != null) {
                shader.cleanup();
            }
            
            // Cleanup all entity-specific VAOs and VBOs
            for (Map<String, Integer> vaoMap : vaoMaps.values()) {
                for (int vao : vaoMap.values()) {
                    GL30.glDeleteVertexArrays(vao);
                }
            }
            for (Map<String, Integer> vboMap : vboMaps.values()) {
                for (int vbo : vboMap.values()) {
                    GL15.glDeleteBuffers(vbo);
                }
            }
            for (Map<String, Integer> eboMap : eboMaps.values()) {
                for (int ebo : eboMap.values()) {
                    GL15.glDeleteBuffers(ebo);
                }
            }
            for (Map<String, Integer> texVboMap : texCoordVboMaps.values()) {
                for (int texVbo : texVboMap.values()) {
                    GL15.glDeleteBuffers(texVbo);
                }
            }
            
            // Cleanup simple cube model
            if (simpleCubeVAO != 0) {
                GL30.glDeleteVertexArrays(simpleCubeVAO);
            }
            if (simpleCubeVBO != 0) {
                GL15.glDeleteBuffers(simpleCubeVBO);
            }
            if (simpleCubeTexVBO != 0) {
                GL15.glDeleteBuffers(simpleCubeTexVBO);
            }
            
            // Cleanup debug sphere model
            if (debugSphereVAO != 0) {
                GL30.glDeleteVertexArrays(debugSphereVAO);
            }
            if (debugSphereVBO != 0) {
                GL15.glDeleteBuffers(debugSphereVBO);
            }
            if (debugSphereTexVBO != 0) {
                GL15.glDeleteBuffers(debugSphereTexVBO);
            }
            
            // Cleanup texture atlas
            CowTextureAtlas.cleanup();
            
            // Clear all maps
            vaoMaps.clear();
            vboMaps.clear();
            eboMaps.clear();
            texCoordVboMaps.clear();
            vertexCountMaps.clear();
            currentTextureVariants.clear();
        }
    }
}