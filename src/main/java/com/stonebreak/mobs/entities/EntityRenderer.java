package com.stonebreak.mobs.entities;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import com.stonebreak.rendering.ShaderProgram;
import com.stonebreak.rendering.MobTextureAtlas;
import com.stonebreak.mobs.cow.CowModel;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryUtil.*;

/**
 * Universal entity renderer that renders entities using their proper 3D models.
 * Supports multi-part textured models and can handle different entity types.
 */
public class EntityRenderer {
    private ShaderProgram shader;
    private MobTextureAtlas textureAtlas;
    private boolean initialized = false;
    
    // VAO and VBO storage for model parts - organized by entity type
    private final Map<EntityType, Map<String, Integer>> vaoMaps = new HashMap<>();
    private final Map<EntityType, Map<String, Integer>> vboMaps = new HashMap<>();
    private final Map<EntityType, Map<String, Integer>> eboMaps = new HashMap<>();
    private final Map<EntityType, Map<String, Integer>> texCoordVboMaps = new HashMap<>();
    private final Map<EntityType, Map<String, Integer>> vertexCountMaps = new HashMap<>();
    
    // Simple cube model for fallback entities
    private int simpleCubeVAO;
    private int simpleCubeVBO;
    private int simpleCubeTexVBO;
    
    public void initialize() {
        if (initialized) return;
        
        createShader();
        createTextureAtlas();
        createSimpleCubeModel();
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
        // Create a 16x16 texture atlas for mob textures
        textureAtlas = new MobTextureAtlas(16);
        textureAtlas.printDebugInfo();
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
    
    private void createCowModel() {
        CowModel cowModel = CowModel.getInstance();
        CowModel.ModelPart[] parts = cowModel.getAllParts();
        
        String[] partNames = {"body", "head", "leg1", "leg2", "leg3", "leg4", "horn1", "horn2", "udder", "tail"};
        
        for (int i = 0; i < parts.length; i++) {
            CowModel.ModelPart part = parts[i];
            String partName = partNames[i];
            
            createModelPart(EntityType.COW, partName, part);
        }
    }
    
    private void createModelPart(EntityType entityType, String partName, CowModel.ModelPart part) {
        float[] vertices = part.getVertices();
        int[] indices = part.getIndices();
        float[] texCoords = part.getTextureCoords(textureAtlas);
        
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
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureAtlas.getTextureId());
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
            case COW -> renderCowParts(baseMatrix, entity);
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
        // Get animated cow model parts
        CowModel cowModel = CowModel.getInstance();
        
        // Get the current animation and time from the cow entity
        CowModel.CowAnimation currentAnimation = CowModel.CowAnimation.IDLE;
        float animationTime = System.currentTimeMillis() / 1000.0f; // Default fallback
        
        if (entity instanceof com.stonebreak.mobs.cow.Cow cow) {
            currentAnimation = cow.getCurrentAnimation();
            // Use the cow's animation controller time for consistent timing
            animationTime = cow.getAnimationController().getTotalAnimationTime();
        }
        
        CowModel.ModelPart[] animatedParts = cowModel.getAnimatedParts(currentAnimation, animationTime);
        
        String[] partNames = {"body", "head", "leg1", "leg2", "leg3", "leg4", "horn1", "horn2", "udder", "tail"};
        Map<String, Integer> cowVaoMap = vaoMaps.get(EntityType.COW);
        Map<String, Integer> cowVertexCountMap = vertexCountMaps.get(EntityType.COW);
        
        // Render each part of the cow
        for (int i = 0; i < animatedParts.length && i < partNames.length; i++) {
            CowModel.ModelPart part = animatedParts[i];
            String partName = partNames[i];
            
            if (cowVaoMap.containsKey(partName)) {
                // Create model matrix for this part
                Matrix4f partMatrix = new Matrix4f(baseMatrix)
                    .translate(part.getPosition())
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
    
    private void renderSimpleEntity(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        shader.bind();
        
        // Bind texture
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureAtlas.getTextureId());
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
            
            // Cleanup texture atlas
            if (textureAtlas != null) {
                textureAtlas.cleanup();
            }
            
            // Clear all maps
            vaoMaps.clear();
            vboMaps.clear();
            eboMaps.clear();
            texCoordVboMaps.clear();
            vertexCountMaps.clear();
        }
    }
}