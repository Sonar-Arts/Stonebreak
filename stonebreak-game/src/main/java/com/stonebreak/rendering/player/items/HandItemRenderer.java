package com.stonebreak.rendering.player.items;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.models.blocks.BlockRenderer;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinitionRegistry;
import com.stonebreak.rendering.player.geometry.ArmGeometry;
import com.stonebreak.rendering.player.geometry.HandBlockGeometry;
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.TextureAtlas;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.opengl.GL11.*;

/**
 * Handles rendering of items (blocks, tools, materials) in the player's hand.
 * Supports different rendering approaches for different item types.
 */
public class HandItemRenderer {
    
    private final ShaderProgram shaderProgram;
    private final TextureAtlas textureAtlas;
    private final HandBlockGeometry handBlockGeometry;
    private final BlockRenderer blockRenderer;
    
    public HandItemRenderer(ShaderProgram shaderProgram, TextureAtlas textureAtlas) {
        this.shaderProgram = shaderProgram;
        this.textureAtlas = textureAtlas;
        this.handBlockGeometry = new HandBlockGeometry(textureAtlas);
        this.blockRenderer = new BlockRenderer();
    }
    
    public HandItemRenderer(ShaderProgram shaderProgram, TextureAtlas textureAtlas, BlockDefinitionRegistry blockRegistry) {
        this.shaderProgram = shaderProgram;
        this.textureAtlas = textureAtlas;
        this.handBlockGeometry = new HandBlockGeometry(textureAtlas);
        this.blockRenderer = new BlockRenderer(textureAtlas, blockRegistry);
    }
    
    /**
     * Renders a block in the player's hand using appropriate rendering method.
     */
    public void renderBlockInHand(BlockType blockType) {
        switch (blockType) {
            case ROSE, DANDELION -> renderFlowerInHand(blockType); // Cross pattern for flowers
            default -> renderCubeBlockInHand(blockType); // 3D cube for regular blocks
        }
    }
    
    /**
     * Renders a regular block as a 3D cube in the hand.
     */
    private void renderCubeBlockInHand(BlockType blockType) {
        // Set up shader uniforms
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        
        // Bind texture
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        shaderProgram.setUniform("texture_sampler", 0);
        
        // No tint for block texture
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Disable blending to prevent transparency issues
        glDisable(GL_BLEND);
        
        // Get or create block-specific cube with proper face textures
        int blockSpecificVao = handBlockGeometry.getHandBlockVao(blockType);
        GL30.glBindVertexArray(blockSpecificVao);
        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0); // 36 indices for a cube
        
        // Re-enable blending for other elements
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }
    
    /**
     * Renders flowers as a cross pattern in the player's hand.
     */
    private void renderFlowerInHand(BlockType flowerType) {
        // Set up shader for flower rendering
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        
        // Bind texture
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        shaderProgram.setUniform("texture_sampler", 0);
        
        // Enable blending for transparency
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        
        // No tint - use pure white
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Use CBR system for rendering flower cross
        if (!blockRenderer.hasCBRSupport()) {
            throw new IllegalStateException("HandItemRenderer requires CBR support. Use constructor with BlockDefinitionRegistry.");
        }
        
        CBRResourceManager.BlockRenderResource resource = blockRenderer.getFlowerCrossResource(flowerType);
        resource.getMesh().bind();
        glDrawElements(GL_TRIANGLES, resource.getMesh().getIndexCount(), GL_UNSIGNED_INT, 0);
    }
    
    /**
     * Fallback method to render flower cross when CBR is not available.
     * @deprecated CBR support is now required
     */
    /*
    private void createAndRenderFlowerCross(BlockType flowerType) {
        // Get UV coordinates for the flower using modern texture atlas system
        float[] uvCoords = textureAtlas.getBlockFaceUVs(flowerType, BlockType.Face.TOP);
        
        // Create vertices for two intersecting quads forming a cross
        float[] vertices1 = {
            // Quad 1: Front-back cross section
            -0.5f, -0.5f, 0.0f,  0.0f, 0.0f, 1.0f,  uvCoords[0], uvCoords[3], // Bottom-left
             0.5f, -0.5f, 0.0f,  0.0f, 0.0f, 1.0f,  uvCoords[2], uvCoords[3], // Bottom-right
             0.5f,  0.5f, 0.0f,  0.0f, 0.0f, 1.0f,  uvCoords[2], uvCoords[1], // Top-right
            -0.5f,  0.5f, 0.0f,  0.0f, 0.0f, 1.0f,  uvCoords[0], uvCoords[1]  // Top-left
        };
        
        // Second quad (X-aligned, rotated 90 degrees)
        float[] vertices2 = {
            0.0f, -0.5f, -0.5f,  1.0f, 0.0f, 0.0f,  uvCoords[0], uvCoords[3], // Bottom-left
            0.0f, -0.5f,  0.5f,  1.0f, 0.0f, 0.0f,  uvCoords[2], uvCoords[3], // Bottom-right
            0.0f,  0.5f,  0.5f,  1.0f, 0.0f, 0.0f,  uvCoords[2], uvCoords[1], // Top-right
            0.0f,  0.5f, -0.5f,  1.0f, 0.0f, 0.0f,  uvCoords[0], uvCoords[1]  // Top-left
        };
        
        // Combine both quads
        float[] vertices = new float[vertices1.length + vertices2.length];
        System.arraycopy(vertices1, 0, vertices, 0, vertices1.length);
        System.arraycopy(vertices2, 0, vertices, vertices1.length, vertices2.length);
        
        int[] indices = {
            0, 1, 2, 0, 2, 3,  // First quad
            4, 5, 6, 4, 6, 7   // Second quad
        };
        
        // Create temporary buffers and render
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        
        int vbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        java.nio.FloatBuffer vertexBuffer = org.lwjgl.BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);
        
        int stride = 8 * Float.BYTES; // 3 pos, 3 normal, 2 texCoord
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(2, 3, GL20.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, stride, 6 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);
        
        int ibo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        java.nio.IntBuffer indexBuffer = org.lwjgl.BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);
        
        // Render
        glDrawElements(GL_TRIANGLES, indices.length, GL_UNSIGNED_INT, 0);
        
        // Cleanup
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        GL30.glDeleteVertexArrays(vao);
        GL20.glDeleteBuffers(vbo);
        GL20.glDeleteBuffers(ibo);
    }
    */
    
    /**
     * Renders tools as 2D sprites in the player's hand.
     */
    public void renderToolInHand(ItemType itemType) {
        // Get UV coordinates for the item
        float[] uvCoords = textureAtlas.getTextureCoordinatesForItem(itemType.getId());
        
        // Set up shader uniforms (keeping existing projection/view matrices)
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false); 
        shaderProgram.setUniform("u_transformUVsForItem", false);
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Bind texture
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        shaderProgram.setUniform("texture_sampler", 0);
        
        // Set up OpenGL state for item rendering
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE);
        
        // Create a simple 2D quad for the item - positioned higher up in the hand
        float size = 0.3f;
        float yOffset = 0.4f; // Move the item up in the hand
        float[] vertices = {
            // Position                        UV coordinates
            -size, -size + yOffset, 0.0f,   uvCoords[0], uvCoords[3], // Bottom-left
             size, -size + yOffset, 0.0f,   uvCoords[2], uvCoords[3], // Bottom-right
             size,  size + yOffset, 0.0f,   uvCoords[2], uvCoords[1], // Top-right
            -size,  size + yOffset, 0.0f,   uvCoords[0], uvCoords[1]  // Top-left
        };
        
        int[] indices = { 0, 1, 2, 0, 2, 3 };
        
        // Create temporary VAO for the item quad
        ArmGeometry.createTemporaryQuad(vertices, indices);
        
        // Restore OpenGL state
        glEnable(GL_CULL_FACE);
    }
    
    /**
     * Cleanup resources when the renderer is destroyed.
     */
    public void cleanup() {
        handBlockGeometry.cleanup();
        blockRenderer.cleanup();
    }
}