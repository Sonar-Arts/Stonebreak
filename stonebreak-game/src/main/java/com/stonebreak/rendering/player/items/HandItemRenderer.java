package com.stonebreak.rendering.player.items;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
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
    
    public HandItemRenderer(ShaderProgram shaderProgram, TextureAtlas textureAtlas) {
        this.shaderProgram = shaderProgram;
        this.textureAtlas = textureAtlas;
        this.handBlockGeometry = new HandBlockGeometry(textureAtlas);
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
        
        // Get UV coordinates for the flower using modern texture atlas system
        float[] uvCoords = textureAtlas.getBlockFaceUVs(flowerType, BlockType.Face.TOP);
        
        // Bind texture
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        shaderProgram.setUniform("texture_sampler", 0);
        
        // Enable blending for transparency
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        
        // No tint - use pure white
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Use BlockRenderer for cross-shaped geometry
        com.stonebreak.rendering.models.blocks.BlockRenderer.renderFlowerCross(uvCoords);
    }
    
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
    }
}