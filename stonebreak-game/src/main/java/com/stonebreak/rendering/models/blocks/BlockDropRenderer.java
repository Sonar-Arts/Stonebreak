package com.stonebreak.rendering.models.blocks;

import com.stonebreak.blocks.BlockDrop;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.TextureAtlas;
import org.joml.*;
import org.lwjgl.opengl.*;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Dedicated renderer for block drops with simplified state management.
 */
public class BlockDropRenderer {
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    // Rendering resources
    private ShaderProgram shaderProgram;
    private TextureAtlas textureAtlas;
    private final Map<Item, Integer> vaoCache = new HashMap<>();
    
    public BlockDropRenderer() {
        // Constructor simplified - no thread management needed
    }
    
    /**
     * Initialize the renderer with shader and texture resources.
     */
    public void initialize(ShaderProgram shaderProgram, TextureAtlas textureAtlas) {
        this.shaderProgram = shaderProgram;
        this.textureAtlas = textureAtlas;
        running.set(true);
    }
    
    /**
     * Render block drops using the provided data.
     */
    public void renderBlockDrops(List<BlockDrop> drops, Matrix4f projection, Matrix4f view) {
        if (!running.get() || drops.isEmpty()) {
            return;
        }
        
        renderDrops(drops, projection, view);
    }
    
    /**
     * Perform the actual block drop rendering with state management.
     */
    private void renderDrops(List<BlockDrop> drops, Matrix4f projection, Matrix4f view) {
        // Save current OpenGL state
        boolean blendEnabled = glIsEnabled(GL_BLEND);
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean cullFaceEnabled = glIsEnabled(GL_CULL_FACE);
        
        try {
            // Configure OpenGL for block drops
            glEnable(GL_DEPTH_TEST);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glDisable(GL_CULL_FACE); // Allow seeing both sides of drops
            
            shaderProgram.bind();
            setupShaderUniforms(projection, view);
            setupTexture();
            
            List<BlockDrop> sortedDrops = sortDropsByDistance(drops, view);
            Map<Item, List<BlockDrop>> dropsByType = groupDropsByType(sortedDrops);
            
            for (Map.Entry<Item, List<BlockDrop>> entry : dropsByType.entrySet()) {
                renderDropsBatch(entry.getKey(), entry.getValue());
            }
            
        } finally {
            resetShaderUniforms();
            shaderProgram.unbind();
            
            // Restore OpenGL state
            if (!blendEnabled) glDisable(GL_BLEND);
            if (!depthTestEnabled) glDisable(GL_DEPTH_TEST);
            if (cullFaceEnabled) glEnable(GL_CULL_FACE);
        }
    }
    
    /**
     * Setup shader uniforms for rendering.
     */
    private void setupShaderUniforms(Matrix4f projection, Matrix4f view) {
        shaderProgram.setUniform("projectionMatrix", projection);
        shaderProgram.setUniform("viewMatrix", view);
        shaderProgram.setUniform("modelMatrix", new Matrix4f()); // Will be overridden per drop
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_renderPass", 0);
        shaderProgram.setUniform("texture_sampler", 0);
    }
    
    /**
     * Setup texture for rendering.
     */
    private void setupTexture() {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }
    
    /**
     * Reset shader uniforms to safe defaults.
     */
    private void resetShaderUniforms() {
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_renderPass", 0);
        shaderProgram.setUniform("texture_sampler", 0);
        shaderProgram.setUniform("modelMatrix", new Matrix4f());
    }
    
    /**
     * Sort drops by distance from camera for proper depth rendering.
     */
    private List<BlockDrop> sortDropsByDistance(List<BlockDrop> drops, Matrix4f view) {
        Vector3f cameraPos = new Vector3f();
        view.invert(new Matrix4f()).getTranslation(cameraPos);
        
        List<BlockDrop> sortedDrops = new ArrayList<>(drops);
        sortedDrops.sort((a, b) -> {
            float distA = a.getPosition().distanceSquared(cameraPos);
            float distB = b.getPosition().distanceSquared(cameraPos);
            return Float.compare(distB, distA); // Back-to-front (farthest first)
        });
        
        return sortedDrops;
    }
    
    /**
     * Group drops by item type for batch rendering.
     */
    private Map<Item, List<BlockDrop>> groupDropsByType(List<BlockDrop> drops) {
        Map<Item, List<BlockDrop>> dropsByType = new HashMap<>();
        
        for (BlockDrop drop : drops) {
            int itemId = drop.getBlockTypeId();
            Item item = resolveItem(itemId);
            
            if (item != null) {
                dropsByType.computeIfAbsent(item, k -> new ArrayList<>()).add(drop);
            }
        }
        
        return dropsByType;
    }
    
    /**
     * Resolve item from ID, trying ItemType first then BlockType.
     */
    private Item resolveItem(int itemId) {
        Item item = ItemType.getById(itemId);
        if (item == null) {
            BlockType blockType = BlockType.getById(itemId);
            if (blockType != null && blockType != BlockType.AIR) {
                item = blockType;
            }
        }
        return item;
    }
    
    /**
     * Render a batch of drops of the same type.
     */
    private void renderDropsBatch(Item item, List<BlockDrop> drops) {
        if (drops.isEmpty()) {
            return;
        }
        
        if (item instanceof ItemType itemType) {
            renderItem2DDropsBatch(itemType, drops);
        } else {
            renderBlock3DDropsBatch(item, drops);
        }
    }
    
    /**
     * Render items (tools, sticks) as flat 2D sprites when dropped.
     */
    private void renderItem2DDropsBatch(ItemType itemType, List<BlockDrop> drops) {
        int vao = vaoCache.computeIfAbsent(itemType, item -> createSprite2DVAO((ItemType) item));
        
        glBindVertexArray(vao);
        
        for (BlockDrop drop : drops) {
            Matrix4f modelMatrix = createItemModelMatrix(drop);
            shaderProgram.setUniform("modelMatrix", modelMatrix);
            glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        }
        
        glBindVertexArray(0);
    }
    
    /**
     * Render blocks as traditional 3D cubes when dropped.
     */
    private void renderBlock3DDropsBatch(Item item, List<BlockDrop> drops) {
        int vao = vaoCache.computeIfAbsent(item, this::createCube3DVAO);
        
        glBindVertexArray(vao);
        
        for (BlockDrop drop : drops) {
            Matrix4f modelMatrix = createBlockModelMatrix(drop);
            shaderProgram.setUniform("modelMatrix", modelMatrix);
            glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
        }
        
        glBindVertexArray(0);
    }
    
    /**
     * Create model matrix for item rendering with rotation and scaling.
     */
    private Matrix4f createItemModelMatrix(BlockDrop drop) {
        Matrix4f modelMatrix = new Matrix4f();
        modelMatrix.translate(drop.getPosition());
        modelMatrix.rotateY((float) java.lang.Math.toRadians(drop.getRotationY()));
        modelMatrix.rotateX((float) java.lang.Math.toRadians(-15.0f)); // Tilt back slightly
        modelMatrix.scale(0.6f); // Make items more visible
        return modelMatrix;
    }
    
    /**
     * Create model matrix for block rendering with rotation and scaling.
     */
    private Matrix4f createBlockModelMatrix(BlockDrop drop) {
        Matrix4f modelMatrix = new Matrix4f();
        modelMatrix.translate(drop.getPosition());
        modelMatrix.rotateY((float) java.lang.Math.toRadians(drop.getRotationY()));
        modelMatrix.scale(0.25f); // 25% of full block size
        return modelMatrix;
    }
    
    /**
     * Create 2D sprite VAO for item rendering.
     */
    private int createSprite2DVAO(ItemType itemType) {
        float[] uvCoords = textureAtlas.getTextureCoordinatesForItem(itemType.getId());
        float size = 0.4f;
        
        // Vertical quad vertices for 2D sprite
        float[] vertices = {
            -size, -size, 0.0f,  uvCoords[0], uvCoords[3],  0.0f, 0.0f, 1.0f,  0.0f, 1.0f, // Bottom-left
             size, -size, 0.0f,  uvCoords[2], uvCoords[3],  0.0f, 0.0f, 1.0f,  0.0f, 1.0f, // Bottom-right  
             size,  size, 0.0f,  uvCoords[2], uvCoords[1],  0.0f, 0.0f, 1.0f,  0.0f, 1.0f, // Top-right
            -size,  size, 0.0f,  uvCoords[0], uvCoords[1],  0.0f, 0.0f, 1.0f,  0.0f, 1.0f  // Top-left
        };
        
        int[] indices = {0, 1, 2, 2, 3, 0};
        
        return createVAO(vertices, indices, 10); // 10 floats per vertex
    }
    
    /**
     * Create 3D cube VAO for block rendering.
     */
    private int createCube3DVAO(Item item) {
        BlockType blockType = (BlockType) item;
        
        // Get face UV coordinates 
        float[] frontUVs = textureAtlas.getBlockFaceUVs(blockType, BlockType.Face.SIDE_NORTH);
        float[] backUVs = textureAtlas.getBlockFaceUVs(blockType, BlockType.Face.SIDE_SOUTH);
        float[] topUVs = textureAtlas.getBlockFaceUVs(blockType, BlockType.Face.TOP);
        float[] bottomUVs = textureAtlas.getBlockFaceUVs(blockType, BlockType.Face.BOTTOM);
        float[] rightUVs = textureAtlas.getBlockFaceUVs(blockType, BlockType.Face.SIDE_EAST);
        float[] leftUVs = textureAtlas.getBlockFaceUVs(blockType, BlockType.Face.SIDE_WEST);
        
        float halfSize = 0.5f;
        
        // Create vertices with 10 floats per vertex: position (3), UV (2), normal (3), isWater (1), isAlphaTested (1)
        float[] vertices = {
            // Front face (+Z)
            -halfSize, -halfSize, +halfSize,  frontUVs[0], frontUVs[3],  0.0f, 0.0f, 1.0f,  0.0f, 0.0f, // Bottom-left
            +halfSize, -halfSize, +halfSize,  frontUVs[2], frontUVs[3],  0.0f, 0.0f, 1.0f,  0.0f, 0.0f, // Bottom-right
            +halfSize, +halfSize, +halfSize,  frontUVs[2], frontUVs[1],  0.0f, 0.0f, 1.0f,  0.0f, 0.0f, // Top-right
            -halfSize, +halfSize, +halfSize,  frontUVs[0], frontUVs[1],  0.0f, 0.0f, 1.0f,  0.0f, 0.0f, // Top-left

            // Back face (-Z)
            -halfSize, -halfSize, -halfSize,  backUVs[0], backUVs[3],  0.0f, 0.0f, -1.0f,  0.0f, 0.0f, // Bottom-left
            +halfSize, -halfSize, -halfSize,  backUVs[2], backUVs[3],  0.0f, 0.0f, -1.0f,  0.0f, 0.0f, // Bottom-right
            +halfSize, +halfSize, -halfSize,  backUVs[2], backUVs[1],  0.0f, 0.0f, -1.0f,  0.0f, 0.0f, // Top-right
            -halfSize, +halfSize, -halfSize,  backUVs[0], backUVs[1],  0.0f, 0.0f, -1.0f,  0.0f, 0.0f, // Top-left

            // Top face (+Y)
            -halfSize, +halfSize, -halfSize,  topUVs[0], topUVs[1],  0.0f, 1.0f, 0.0f,  0.0f, 0.0f, // Top-left
            +halfSize, +halfSize, -halfSize,  topUVs[2], topUVs[1],  0.0f, 1.0f, 0.0f,  0.0f, 0.0f, // Top-right
            +halfSize, +halfSize, +halfSize,  topUVs[2], topUVs[3],  0.0f, 1.0f, 0.0f,  0.0f, 0.0f, // Bottom-right
            -halfSize, +halfSize, +halfSize,  topUVs[0], topUVs[3],  0.0f, 1.0f, 0.0f,  0.0f, 0.0f, // Bottom-left

            // Bottom face (-Y)
            -halfSize, -halfSize, -halfSize,  bottomUVs[0], bottomUVs[1],  0.0f, -1.0f, 0.0f,  0.0f, 0.0f, // Top-left
            +halfSize, -halfSize, -halfSize,  bottomUVs[2], bottomUVs[1],  0.0f, -1.0f, 0.0f,  0.0f, 0.0f, // Top-right
            +halfSize, -halfSize, +halfSize,  bottomUVs[2], bottomUVs[3],  0.0f, -1.0f, 0.0f,  0.0f, 0.0f, // Bottom-right
            -halfSize, -halfSize, +halfSize,  bottomUVs[0], bottomUVs[3],  0.0f, -1.0f, 0.0f,  0.0f, 0.0f, // Bottom-left

            // Right face (+X)
            +halfSize, -halfSize, -halfSize,  rightUVs[0], rightUVs[3],  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, // Bottom-left
            +halfSize, -halfSize, +halfSize,  rightUVs[2], rightUVs[3],  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, // Bottom-right
            +halfSize, +halfSize, +halfSize,  rightUVs[2], rightUVs[1],  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, // Top-right
            +halfSize, +halfSize, -halfSize,  rightUVs[0], rightUVs[1],  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, // Top-left

            // Left face (-X)
            -halfSize, -halfSize, -halfSize,  leftUVs[0], leftUVs[3],  -1.0f, 0.0f, 0.0f,  0.0f, 0.0f, // Bottom-left
            -halfSize, -halfSize, +halfSize,  leftUVs[2], leftUVs[3],  -1.0f, 0.0f, 0.0f,  0.0f, 0.0f, // Bottom-right
            -halfSize, +halfSize, +halfSize,  leftUVs[2], leftUVs[1],  -1.0f, 0.0f, 0.0f,  0.0f, 0.0f, // Top-right
            -halfSize, +halfSize, -halfSize,  leftUVs[0], leftUVs[1],  -1.0f, 0.0f, 0.0f,  0.0f, 0.0f  // Top-left
        };

        int[] indices = {
            // Front face
            0, 1, 2, 2, 3, 0,
            // Back face
            4, 5, 6, 6, 7, 4,
            // Top face
            8, 9, 10, 10, 11, 8,
            // Bottom face
            12, 13, 14, 14, 15, 12,
            // Right face
            16, 17, 18, 18, 19, 16,
            // Left face
            20, 21, 22, 22, 23, 20
        };

        return createVAO(vertices, indices, 10);
    }
    
    /**
     * Create VAO with vertex and index buffers.
     */
    private int createVAO(float[] vertices, int[] indices, int stride) {
        int vao = glGenVertexArrays();
        glBindVertexArray(vao);
        
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);
        
        int ibo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW);
        
        setupVertexAttributes(stride);
        
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
        
        return vao;
    }
    
    /**
     * Setup vertex attributes for the VAO.
     */
    private void setupVertexAttributes(int stride) {
        int strideBytes = stride * Float.BYTES;
        
        // Position (location 0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, strideBytes, 0);
        glEnableVertexAttribArray(0);
        
        // UV coordinates (location 1)  
        glVertexAttribPointer(1, 2, GL_FLOAT, false, strideBytes, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
        
        // Normal (location 2)
        glVertexAttribPointer(2, 3, GL_FLOAT, false, strideBytes, 5 * Float.BYTES);
        glEnableVertexAttribArray(2);
        
        if (stride >= 10) {
            // isWater (location 3)
            glVertexAttribPointer(3, 1, GL_FLOAT, false, strideBytes, 8 * Float.BYTES);
            glEnableVertexAttribArray(3);
            
            // isAlphaTested (location 4)
            glVertexAttribPointer(4, 1, GL_FLOAT, false, strideBytes, 9 * Float.BYTES);
            glEnableVertexAttribArray(4);
        }
    }

    /**
     * Cleanup resources.
     */
    public void cleanup() {
        running.set(false);
        
        // Cleanup VAO cache
        for (Integer vao : vaoCache.values()) {
            if (vao != null && vao != 0) {
                glDeleteVertexArrays(vao);
            }
        }
        vaoCache.clear();
    }
}